package com.taboia872.androidassist.tasks

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * The scheduled tasks, persisted as one JSON file in the app's filesDir
 * (the same pattern as the chat history).
 */
object TaskStore {

    private const val FILE = "tasks.json"

    private fun file(context: Context): File = File(context.filesDir, FILE)

    /** Every saved task. */
    fun all(context: Context): List<ScheduledTask> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<ScheduledTask>()
            for (i in 0 until arr.length()) {
                ScheduledTask.fromJson(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Replace the whole list. */
    fun save(context: Context, tasks: List<ScheduledTask>) {
        try {
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            // A broken write must not crash the app.
        }
    }

    /** Insert or update a task. */
    fun upsert(context: Context, task: ScheduledTask) {
        val list = all(context).toMutableList()
        val idx = list.indexOfFirst { it.id == task.id }
        if (idx >= 0) list[idx] = task else list.add(task)
        save(context, list)
    }

    /** Delete a task. */
    fun remove(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
    }

    /** One task by id. */
    fun byId(context: Context, id: String): ScheduledTask? =
        all(context).firstOrNull { it.id == id }
}
