package com.taboia872.androidassist.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Chat persistence: one JSON file per chat in filesDir/chats. The current
 * (active) chat is remembered by id so reopening the app restores it.
 *
 * FORMAT v2 (block-based): each message carries blocks[] where every block
 * is {kind: reasoning|answer|tool, text}. Old v1 files (flat role/content)
 * load as one answer block — transparent migration on read.
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

    /** Block kinds persisted per message. */
    // kind: "reasoning" | "answer" | "tool"

    fun save(
        context: Context,
        id: String,
        messages: List<StoredMessage>
    ): String {
        if (messages.isEmpty()) return id

        val title = messages
            .firstOrNull { it.role == "user" }?.blocks
            ?.firstOrNull { it.kind == "answer" }?.text
            ?.take(40)?.ifBlank { "Chat" }
            ?: "Chat"

        val json = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("timestamp", System.currentTimeMillis())
            put("format", 2)
            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    val mo = JSONObject().put("role", m.role)
                    val blocks = JSONArray()
                    m.blocks.forEach { b ->
                        blocks.put(JSONObject().apply {
                            put("kind", b.kind)
                            put("text", b.text)
                        })
                    }
                    if (m.imageBase64 != null) mo.put("image", m.imageBase64)
                    mo.put("blocks", blocks)
                    put(mo)
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

    data class Block(val kind: String, val text: String)
    data class StoredMessage(
        val role: String,
        val blocks: List<Block>,
        val imageBase64: String? = null
    ) {
        /** Flat text of answer blocks only (for LLM history + TTS). */
        val answerText: String
            get() = blocks.filter { it.kind == "answer" }.joinToString("\n\n") { it.text }
    }

    fun load(context: Context, id: String): List<StoredMessage>? {
        val f = File(dir(context), "$id.json")
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("messages") ?: JSONArray()
            val msgs = buildList {
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val role = m.optString("role")
                    if (role.isBlank()) continue
                    val img = m.optString("image").takeIf { it.isNotBlank() }
                    val blocksArr = m.optJSONArray("blocks")
                    val blocks = if (blocksArr != null) {
                        buildList {
                            for (j in 0 until blocksArr.length()) {
                                val b = blocksArr.optJSONObject(j) ?: continue
                                val kind = b.optString("kind").ifBlank { "answer" }
                                val text = b.optString("text")
                                if (text.isNotBlank()) add(Block(kind, text))
                            }
                        }
                    } else {
                        // v1 migration: flat content -> single answer block
                        val text = m.optString("content")
                        if (text.isNotBlank()) listOf(Block("answer", text)) else emptyList()
                    }
                    if (blocks.isNotEmpty()) add(StoredMessage(role, blocks, img))
                }
            }
            if (msgs.isEmpty()) null else msgs
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
                val preview = if (root.optInt("format", 1) >= 2) {
                    arr.optJSONObject(0)?.optJSONArray("blocks")
                        ?.optJSONObject(0)?.optString("text")?.take(80) ?: ""
                } else {
                    arr.optJSONObject(0)?.optString("content")?.take(80) ?: ""
                }
                ChatSummaryData(
                    id = root.optString("id").ifBlank { f.nameWithoutExtension },
                    title = root.optString("title").ifBlank { "Chat" },
                    timestamp = root.optLong("timestamp", f.lastModified()),
                    messageCount = arr.length(),
                    preview = preview
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timestamp }
    }
}
