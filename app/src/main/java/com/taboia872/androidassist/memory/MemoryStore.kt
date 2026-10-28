package com.taboia872.androidassist.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One memory entry. Kinds:
 *  - "core":       standing INSTRUCTIONS the model must follow (rides the
 *                  system prompt every request, appended below the user's
 *                  fixed persona prompt — never replacing it)
 *  - "preference": short tastes/choices (also always in the prompt)
 *  - "fact":       world facts about the user (bus line, dentist name) —
 *                  retrievable via search_memories, not in the prompt
 *  - "skill":     reusable routine: steps in [text], trigger words in
 *                  [trigger], [schedule] reserved for the future 24h
 *                  autonomous executor (empty in v1)
 */
data class Memory(
    val id: String,
    val kind: String,          // core | preference | fact | skill
    val text: String,
    val trigger: String = "",
    val schedule: String = "",
    val source: String = "model",  // model | user | routine
    val created: Long = System.currentTimeMillis()
)

/**
 * Persistent memories: one JSON file in filesDir, same family as ChatStore.
 * The chat's system prompt gains a dynamic block (core + preference) and
 * the model retrieves facts/skills on demand via the search_memories tool.
 */
object MemoryStore {

    private const val FILE = "memories.json"

    private fun file(context: Context): File =
        File(context.filesDir, FILE)

    // ------------------------------------------------------------ load/save

    fun all(context: Context): MutableList<Memory> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Memory>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val text = o.optString("text")
                if (id.isBlank() || text.isBlank()) continue
                out.add(Memory(
                    id = id,
                    kind = o.optString("kind").ifBlank { "fact" },
                    text = text,
                    trigger = o.optString("trigger", ""),
                    schedule = o.optString("schedule", ""),
                    source = o.optString("source", "model"),
                    created = o.optLong("created", 0L)
                ))
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun save(context: Context, list: List<Memory>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("kind", m.kind)
                put("text", m.text)
                put("trigger", m.trigger)
                put("schedule", m.schedule)
                put("source", m.source)
                put("created", m.created)
            })
        }
        file(context).writeText(arr.toString())
    }

    // ---------------------------------------------------------------- CRUD

    /** Create a memory; dedupes identical (kind+text). Returns the memory. */
    fun add(context: Context, kind: String, text: String, source: String = "model"): Memory {
        val list = all(context)
        val clean = text.trim()
        val existing = list.firstOrNull { it.kind == kind && it.text.equals(clean, ignoreCase = true) }
        if (existing != null) return existing
        val m = Memory(
            id = UUID.randomUUID().toString(),
            kind = kind, text = clean, source = source
        )
        list.add(m)
        save(context, list)
        return m
    }

    fun update(context: Context, id: String, newText: String): Boolean {
        val list = all(context)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        list[i] = list[i].copy(text = newText.trim())
        save(context, list)
        return true
    }

    /** Delete by id, or by fuzzy text match (the forget_memory tool). */
    fun forget(context: Context, idOrText: String): Boolean {
        val list = all(context)
        val before = list.size
        val clean = idOrText.trim()
        val remaining = list.filter { m ->
            m.id != idOrText &&
                !m.text.equals(clean, ignoreCase = true) &&
                !m.text.contains(clean, ignoreCase = true)
        }
        save(context, remaining)
        return remaining.size < before
    }

    /** User-facing delete (exact id, no fuzzy). */
    fun delete(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
    }

    // --------------------------------------------------------------- search

    /**
     * Naive keyword search (offline, private — no embeddings): scores by
     * word overlap between query and text+trigger. Top [limit] results.
     */
    fun search(context: Context, query: String, limit: Int = 5): List<Memory> {
        val q = query.trim().lowercase().split(Regex("\\W+")).filter { it.length > 2 }
        if (q.isEmpty()) return all(context).sortedBy { it.created }.take(limit)
        return all(context).map { m ->
            val hay = (m.text + " " + m.trigger).lowercase()
            val score = q.count { hay.contains(it) }
            m to score
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // ------------------------------------------------------- prompt block

    /**
     * The dynamic memory block for the system prompt: CORE (instructions)
     * and PREFERENCES always ride along. FACTS/SKILLS stay retrievable.
     * Never touches the user's fixed persona prompt — appended below it.
     */
    fun promptBlock(context: Context): String {
        val list = all(context)
        val core = list.filter { it.kind == "core" }
        val prefs = list.filter { it.kind == "preference" }
        if (core.isEmpty() && prefs.isEmpty()) return ""

        return buildString {
            append("\n\n## Persistent memories about the user\n")
            if (core.isNotEmpty()) {
                append("Standing instructions you MUST follow:\n")
                core.forEach { append("- ").append(it.text).append("\n") }
            }
            if (prefs.isNotEmpty()) {
                append("User preferences:\n")
                prefs.forEach { append("- ").append(it.text).append("\n") }
            }
            append(
                "You also have memories you cannot see right now (facts and " +
                    "skills). When the question seems to involve personal " +
                    "facts or a routine, call search_memories first.\n"
            )
        }
    }
}
