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
 * keys, system prompt, or chats — each into its own single JSON file via
 * the system file picker (SAF). Restore merges/replaces per kind.
 *
 * File envelope: {format, kind, app, exported, data}
 *  - memories:  data = [ {id,kind,text,trigger,schedule,source,created} ]
 *  - keys:       data = {providerId, apiKeys:{slot:key}, customEndpoints}
 *  - prompt:     data = {systemPrompt, activePersonaPrompt?}
 *  - chats:      data = [ {id,title,timestamp,messages:[{role,content,image,blocks:[...]}]} ]
 */
object BackupStore {

    const val KIND_MEMORIES = "memories"
    const val KIND_KEYS = "keys"
    const val KIND_PROMPT = "prompt"
    const val KIND_CHATS = "chats"

    // ------------------------------------------------------------- export

    fun export(context: Context, kind: String, target: Uri) {
        val data = when (kind) {
            KIND_MEMORIES -> exportMemories(context)
            KIND_KEYS -> exportKeys()
            KIND_PROMPT -> exportPrompt()
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
            put("apiKeys", JSONObject(cfg.apiKeys))
            put("customEndpoints", JSONArray(cfg.customEndpoints))
        }
    }

    private fun exportPrompt(): JSONObject {
        return JSONObject().apply {
            put("systemPrompt", AppSettings.config.value.systemPrompt)
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

    /** Restore keys: MERGE slots (imported keys win on conflict? No — keep
     *  existing, add missing; the user can edit in Settings). */
    fun restoreKeys(data: JSONObject) {
        val cfg = AppSettings.config.value
        val incoming = mutableMapOf<String, String>()
        val keysObj = data.optJSONObject("apiKeys") ?: JSONObject()
        for (k in keysObj.keys()) incoming[k] = keysObj.getString(k)
        val merged = cfg.apiKeys.toMutableMap()
        incoming.forEach { (k, v) ->
            if (!merged.containsKey(k) || merged[k].isBlank()) merged[k] = v
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

    /** Restore prompt: REPLACE the system prompt (a prompt backup is the
     *  whole point — restore means adopt it). */
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
