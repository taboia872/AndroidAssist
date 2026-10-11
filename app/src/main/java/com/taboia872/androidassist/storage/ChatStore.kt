package com.taboia872.androidassist.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Chat persistence: one JSON file per chat in filesDir/chats. The current
 * (active) chat is remembered by id so reopening the app restores it, like
 * the base app's ChatHistoryManager.
 */
object ChatStore {

    fun dir(context: Context): File =
        File(context.filesDir, "chats").apply { mkdirs() }

    fun currentId(context: Context): String? =
        File(context.filesDir, "assistant_state.json")
            .takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }

    fun setCurrentId(context: Context, id: String?) {
        val f = File(context.filesDir, "assistant_state.json")
        if (id.isNullOrBlank()) f.delete() else f.writeText(id)
    }

    // ---------------------------------------------------------------- save

    fun save(
        context: Context,
        id: String,
        messages: List<Pair<String, String>> // (role, content)
    ): String {
        if (messages.isEmpty()) return id

        val title = messages
            .firstOrNull { it.first == "user" }?.second?.take(40)?.ifBlank { "Chat" }
            ?: "Chat"

        val json = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("timestamp", System.currentTimeMillis())
            put("messages", JSONArray().apply {
                messages.forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content))
                }
            })
        }
        File(dir(context), "$id.json").writeText(json.toString())
        return id
    }

    /** Delete a chat file; clears currentId if it pointed at it. */
    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
        if (currentId(context) == id) setCurrentId(context, null)
    }

    // ---------------------------------------------------------------- load

    data class LoadedChat(
        val id: String,
        val messages: List<Pair<String, String>>
    )

    fun load(context: Context, id: String): LoadedChat? {
        val f = File(dir(context), "$id.json")
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("messages") ?: JSONArray()
            val msgs = buildList {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val role = m.optString("role")
                    val content = m.optString("content")
                    if (role.isNotBlank()) add(role to content)
                }
            }
            if (msgs.isEmpty()) null else LoadedChat(id, msgs)
        } catch (e: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- listing

    data class ChatSummaryData(
        val id: String,
        val title: String,
        val timestamp: Long,
        val messageCount: Int,
        val preview: String
    )

    fun list(context: Context): List<ChatSummaryData> {
        val files = dir(context).listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val root = JSONObject(f.readText())
                val arr = root.optJSONArray("messages") ?: return@mapNotNull null
                ChatSummaryData(
                    id = root.optString("id").ifBlank { f.nameWithoutExtension },
                    title = root.optString("title").ifBlank { "Chat" },
                    timestamp = root.optLong("timestamp", f.lastModified()),
                    messageCount = arr.length(),
                    preview = arr.optJSONObject(0)?.optString("content")?.take(80) ?: ""
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timestamp }
    }
}
