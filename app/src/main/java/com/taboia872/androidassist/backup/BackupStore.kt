package com.taboia872.androidassist.backup

import android.content.Context
import android.net.Uri
import com.taboia872.androidassist.memory.MemoryStore
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.storage.ChatStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * FRACTIONAL backup: the user picks WHAT to export — memories, provider
 * keys, personas (the profile: config + persona prompt), or chats — each
 * into its own single JSON file via the system file picker (SAF). Restore
 * merges/replaces per kind.
 *
 * File envelope: {format, kind, app, exported, data}
 *  - memories:  data = [ {id,kind,text,trigger,schedule,source,created} ]
 *  - keys:       data = {providerId, apiKeys:{slot:[keys]}, customEndpoints}
 *  - personas:   data = { activePersonaId, current:{persona fields},
 *                          personas:[{persona fields}] }
 *  - chats:      data = [ {id,title,timestamp,messages:[{role,content,image,blocks:[...]}]} ]
 *
 * LEGACY: the old "prompt" kind (system prompt only) is still IMPORTED —
 * it restores the system prompt and nothing else.
 */
object BackupStore {

    const val KIND_MEMORIES = "memories"
    const val KIND_KEYS = "keys"
    const val KIND_PERSONAS = "personas"
    const val KIND_CHATS = "chats"
    /** Legacy kind — import only (the system prompt alone). */
    const val KIND_PROMPT = "prompt"

    // ------------------------------------------------------------- export

    fun export(context: Context, kind: String, target: Uri) {
        val data = when (kind) {
            KIND_MEMORIES -> exportMemories(context)
            KIND_KEYS -> exportKeys()
            KIND_PERSONAS -> exportPersonas()
            KIND_CHATS -> exportChats(context)
            else -> throw IllegalArgumentException("unknown kind $kind")
        }
        val envelope = JSONObject().apply {
            put("format", 1)
            put("kind", kind)
            put("app", "AndroidAssist")
            put("exported", System.currentTimeMillis())
            put("data", data)
        }
        context.contentResolver.openOutputStream(target)?.use { out ->
            out.write(envelope.toString(2).toByteArray(Charsets.UTF_8))
        } ?: throw RuntimeException("Cannot open output stream")
    }

    private fun exportMemories(context: Context): JSONArray {
        val arr = JSONArray()
        MemoryStore.all(context).forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("kind", m.kind); put("text", m.text)
                put("trigger", m.trigger); put("schedule", m.schedule)
                put("source", m.source); put("created", m.created)
            })
        }
        return arr
    }

    private fun exportKeys(): JSONObject {
        val cfg = AppSettings.config.value
        return JSONObject().apply {
            put("providerId", cfg.providerId)
            // v2: every slot carries the LIST of its keys
            val keys = JSONObject()
            cfg.apiKeys.forEach { (slot, list) ->
                val arr = JSONArray()
                list.forEach { arr.put(it) }
                keys.put(slot, arr)
            }
            put("apiKeys", keys)
            put("customEndpoints", JSONArray(cfg.customEndpoints))
        }
    }

    /**
     * PERSONAS: the full profile — the ACTIVE persona (name, model, prompt,
     * voice in/out) plus every saved persona. The "current" entry is the
     * live config exported AS a persona, so an unsaved setup is backed up
     * too (the old "prompt" backup only saved the system prompt).
     */
    private fun exportPersonas(): JSONObject {
        val cfg = AppSettings.config.value
        val current = com.taboia872.androidassist.settings.Persona.fromConfig(
            id = cfg.activePersonaId.ifBlank { "current" },
            name = AppSettings.activePersonaName(),
            c = cfg
        )
        val arr = JSONArray()
        AppSettings.personas().forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("activePersonaId", cfg.activePersonaId)
            put("current", current.toJson())
            put("personas", arr)
        }
    }

    private fun exportChats(context: Context): JSONArray {
        val arr = JSONArray()
        val dir = ChatStore.dir(context)
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            try {
                arr.put(JSONObject(f.readText()))
            } catch (e: Exception) {
            }
        }
        return arr
    }

    // ------------------------------------------------------------- import

    /** Read a backup envelope; returns (kind, dataNode). */
    fun read(source: Uri, context: Context): Pair<String, Any> {
        val text = context.contentResolver.openInputStream(source)?.use {
            it.bufferedReader().readText()
        } ?: throw RuntimeException("Cannot read backup file")
        val root = JSONObject(text)
        val kind = root.optString("kind")
        val data: Any = when (kind) {
            KIND_CHATS, KIND_MEMORIES -> root.optJSONArray("data")
                ?: JSONArray()
            else -> root.optJSONObject("data") ?: JSONObject()
        }
        return kind to data
    }

    /** Restore memories: MERGE by id (never duplicate, existing kept). */
    fun restoreMemories(context: Context, data: JSONArray) {
        val existing = MemoryStore.all(context).map { it.id }.toHashSet()
        val toAdd = mutableListOf<com.taboia872.androidassist.memory.Memory>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || existing.contains(id)) continue
            toAdd.add(com.taboia872.androidassist.memory.Memory(
                id = id,
                kind = o.optString("kind", "fact"),
                text = o.optString("text", ""),
                trigger = o.optString("trigger", ""),
                schedule = o.optString("schedule", ""),
                source = o.optString("source", "user"),
                created = o.optLong("created", System.currentTimeMillis())
            ))
        }
        if (toAdd.isNotEmpty()) {
            val all = MemoryStore.all(context)
            all.addAll(toAdd)
            MemoryStore.replaceAll(context, all)
        }
    }

    /**
     * Restore keys: MERGE slots (imported keys win on conflict? No — keep
     * existing, add missing; the user can edit in Settings).
     *
     * Accepts BOTH formats: v2 {slot: ["k1","k2"]} and the legacy
     * v1 {slot: "k1"}. Keys already saved in a slot are NEVER overwritten;
     * only missing ones are appended.
     */
    fun restoreKeys(data: JSONObject) {
        val cfg = AppSettings.config.value
        val merged = cfg.apiKeys.toMutableMap()
        val keysObj = data.optJSONObject("apiKeys") ?: JSONObject()
        for (slot in keysObj.keys()) {
            val incoming = mutableListOf<String>()
            when (val v = keysObj.get(slot)) {
                is String -> if (v.isNotBlank()) incoming.add(v)
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) {
                        val s = v.optString(i)
                        if (s.isNotBlank()) incoming.add(s)
                    }
                }
                else -> Unit
            }
            if (incoming.isEmpty()) continue
            val current = merged[slot] ?: emptyList()
            val list = current.toMutableList()
            for (k in incoming) {
                // Existing key wins — only fill the gaps
                if (!list.contains(k)) list.add(k)
            }
            merged[slot] = list
        }
        AppSettings.update { it.copy(apiKeys = merged) }
        // custom endpoints: merge by id
        val eps = data.optJSONArray("customEndpoints")
        if (eps != null) {
            val existing = AppSettings.customEndpointList()
            val list = existing.toMutableList()
            val known = existing.map { it.id }.toHashSet()
            for (i in 0 until eps.length()) {
                val o = eps.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotBlank() && !known.contains(id)) {
                    list.add(AppSettings.CustomEndpoint(
                        id, o.optString("name", id), o.optString("baseUrl", "")
                    ))
                }
            }
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl))
            }
            AppSettings.update { it.copy(customEndpoints = arr.toString()) }
        }
    }

    /**
     * Restore personas: the saved list is MERGED by id (existing personas
     * keep their contents — importing never destroys a persona the user
     * already has) and the "current" profile (config + prompt + voice
     * in/out of the ACTIVE persona) is APPLIED to the live config, so the
     * restored profile really takes effect.
     */
    fun restorePersonas(data: JSONObject) {
        // 1. merge the saved personas by id
        val arr = data.optJSONArray("personas")
        if (arr != null) {
            val existing = AppSettings.personas()
            val byId = existing.associateBy { it.id }.toMutableMap()
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = com.taboia872.androidassist.settings.Persona.fromJson(o) ?: continue
                if (!byId.containsKey(p.id)) {
                    byId[p.id] = p
                    changed = true
                }
            }
            if (changed) AppSettings.replacePersonas(byId.values.toList())
        }
        // 2. apply the backed-up profile to the live config
        val current = data.optJSONObject("current")
            ?: data.optJSONObject("active")
        if (current != null) {
            val p = com.taboia872.androidassist.settings.Persona.fromJson(current)
            if (p != null) AppSettings.applyPersonaProfile(p)
        }
    }

    /**
     * Restore the LEGACY "prompt" backup: the system prompt only.
     * REPLACES the prompt (a prompt backup is the whole point).
     */
    fun restorePrompt(data: JSONObject) {
        val prompt = data.optString("systemPrompt")
        if (prompt.isNotBlank()) {
            AppSettings.update { it.copy(systemPrompt = prompt) }
        }
    }

    /** Restore chats: MERGE by chat id — existing ids untouched, new files
     *  written into filesDir/chats. */
    fun restoreChats(context: Context, data: JSONArray) {
        val dir = ChatStore.dir(context)
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            if (File(dir, "$id.json").exists()) continue
            File(dir, "$id.json").writeText(o.toString())
        }
    }
}
