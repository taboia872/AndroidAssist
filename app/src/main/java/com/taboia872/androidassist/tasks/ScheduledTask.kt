package com.taboia872.androidassist.tasks

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * A task the assistant runs BY ITSELF, on a schedule.
 *
 * The user creates it with a prompt, a time and the list of tools it
 * may use; the app wakes up, runs the turn and saves the result as a
 * file (plus a notification).
 */
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    /** Short name (shown in the list). */
    val title: String,
    /** What the assistant should do. */
    val prompt: String,
    /**
     * The tools the task MAY use: the ability ids (alarm, timer,
     * calendar, open_app, clipboard, share, location, map, files,
     * messaging, open_url) + "web_search" + "memory". Empty = no tools
     * at all (the model only writes the report).
     */
    val allowedTools: List<String> = emptyList(),
    /** "daily" | "weekly" | "once". */
    val repeatMode: String = REPEAT_DAILY,
    val hour: Int = 7,
    val minute: Int = 0,
    /** For "weekly": the days (Calendar.DAY_OF_WEEK: 1=Mon .. 7=Sun). */
    val daysOfWeek: List<Int> = emptyList(),
    /** For "once": the exact moment (epoch millis). */
    val onceAt: Long = 0L,
    /**
     * The persona the task uses (its prompt, its model, its provider).
     * Empty = the app's current settings.
     */
    val personaId: String = "",
    /**
     * The instructions the model reads BEFORE the task itself (the
     * default tells it the answer is automatic, not a live chat).
     */
    val contextPrompt: String = DEFAULT_CONTEXT,
    /** The task speaks the answer out loud (the TTS engine). */
    val speak: Boolean = false,
    val enabled: Boolean = true,
    /** When it last ran (epoch millis). */
    val lastRunAt: Long = 0L,
    /** A one-line preview of the last result. */
    val lastResult: String = ""
) {

    /**
     * The next moment this task should run (0 = never).
     *
     * The "weekly" mode walks the next 8 days looking for a day in the
     * list (the day of the week is Calendar.DAY_OF_WEEK: Sunday = 1).
     */
    fun nextRunAt(from: Long = System.currentTimeMillis()): Long {
        when (repeatMode) {
            REPEAT_ONCE -> return if (onceAt > from) onceAt else 0L
            REPEAT_WEEKLY -> {
                if (daysOfWeek.isEmpty()) return 0L
                for (offset in 0..7) {
                    val probe = Calendar.getInstance()
                    probe.timeInMillis = from
                    probe.add(Calendar.DAY_OF_YEAR, offset)
                    probe.set(Calendar.HOUR_OF_DAY, hour)
                    probe.set(Calendar.MINUTE, minute)
                    probe.set(Calendar.SECOND, 0)
                    probe.set(Calendar.MILLISECOND, 0)
                    if (probe.get(Calendar.DAY_OF_WEEK) in daysOfWeek &&
                        probe.timeInMillis > from
                    ) return probe.timeInMillis
                }
                return 0L
            }
            else -> {
                val cal = Calendar.getInstance()
                cal.timeInMillis = from
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }
        }
    }

    /** A human label of the schedule. */
    fun scheduleLabel(): String = when (repeatMode) {
        REPEAT_ONCE -> "Uma vez"
        REPEAT_WEEKLY -> "Semanal"
        else -> "Diário"
    }

    /** The time as "07:00". */
    fun timeLabel(): String =
        String.format("%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    /** The chat id of the task's conversation (one chat per task). */
    fun chatId(): String = "task-$id"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("prompt", prompt)
        put("allowedTools", JSONArray(allowedTools))
        put("repeatMode", repeatMode)
        put("hour", hour)
        put("minute", minute)
        put("daysOfWeek", JSONArray(daysOfWeek))
        put("onceAt", onceAt)
        put("personaId", personaId)
        put("contextPrompt", contextPrompt)
        put("speak", speak)
        put("enabled", enabled)
        put("lastRunAt", lastRunAt)
        put("lastResult", lastResult)
    }

    companion object {
        /**
         * The default pre-prompt: the model has to know the answer is
         * an automatic execution (no one is typing) and that it may end
         * up in a notification and in the chat history.
         */
        const val DEFAULT_CONTEXT =
            "This is an AUTOMATIC scheduled task: the app woke up and ran " +
                "this request by itself at the scheduled time (nobody is typing " +
                "right now). Your answer will be shown in a notification, saved " +
                "in the chat history and may be read out loud. Answer as if the " +
                "user had asked it right now: complete, self-contained and " +
                "practical, without asking for confirmation."

        const val REPEAT_DAILY = "daily"
        const val REPEAT_WEEKLY = "weekly"
        const val REPEAT_ONCE = "once"

        fun fromJson(o: JSONObject): ScheduledTask? {
            val id = o.optString("id").ifBlank { return null }
            return ScheduledTask(
                id = id,
                title = o.optString("title"),
                prompt = o.optString("prompt"),
                allowedTools = o.optJSONArray("allowedTools").toStringList(),
                repeatMode = o.optString("repeatMode", REPEAT_DAILY),
                hour = o.optInt("hour", 7),
                minute = o.optInt("minute", 0),
                daysOfWeek = o.optJSONArray("daysOfWeek").toIntList(),
                onceAt = o.optLong("onceAt", 0L),
                personaId = o.optString("personaId", ""),
                contextPrompt = o.optString("contextPrompt", DEFAULT_CONTEXT),
                speak = o.optBoolean("speak", false),
                enabled = o.optBoolean("enabled", true),
                lastRunAt = o.optLong("lastRunAt", 0L),
                lastResult = o.optString("lastResult")
            )
        }
    }
}

/** JSONArray -> List<String> (the malformed entries are ignored). */
private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until length()) {
        optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
    }
    return out
}

/** JSONArray -> List<Int> (only the positive values). */
private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    val out = mutableListOf<Int>()
    for (i in 0 until length()) {
        val v = optInt(i, -1)
        if (v > 0) out.add(v)
    }
    return out
}
