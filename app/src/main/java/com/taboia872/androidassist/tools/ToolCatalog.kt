package com.taboia872.androidassist.tools

/**
 * Catalogue of EVERY tool the assistant can call (web search, Android
 * abilities and memory tools) with a SHORT purpose for each one.
 *
 * The purpose is what goes into the system prompt: the model reads a
 * one-line summary of what it can do right now instead of a wall of
 * parameter schemas.
 *
 * IMPORTANT: this map is about WORDING only. Which tools are AVAILABLE is
 * decided by AppSettings (the same gates LlmClient uses to declare the
 * specs) — so the prompt list and the declared tools can never disagree.
 */
object ToolCatalog {

    /** Tool function name -> one-line purpose (English; the model reads it). */
    val PURPOSE: Map<String, String> = mapOf(
        "web_search" to "search the web for current information",
        "set_alarm" to "set an alarm on the phone",
        "set_timer" to "start a countdown timer",
        "add_calendar_event" to "add an event to the calendar",
        "open_app" to "open an installed app by name",
        "read_clipboard" to "read the text currently on the clipboard",
        "add_to_clipboard" to "copy text to the clipboard",
        "share_text" to "open the share sheet with a text",
        "get_location" to "get the approximate current location",
        "open_map" to "show a place on a map app",
        "write_file" to "write a text file in the workspace folder",
        "read_file" to "read a text file from the workspace folder",
        "list_files" to "list the files and subfolders of a folder in the workspace",
        "create_folder" to "create a folder inside the workspace",
        "delete_file" to "delete a file from the workspace folder",
        "delete_folder" to "delete a folder and everything inside it",
        "send_message" to "send a message to a contact of the agenda",
        "open_url" to "open a web page, site or YouTube link in the browser",
        "save_memory" to "save a lasting memory about the user",
        "search_memories" to "search the memories already saved",
        "forget_memory" to "delete a memory that is wrong or outdated"
    )

    /**
     * EVERY tool function, in the order the request declares them: web
     * search first, then the Android abilities, then the memory tools.
     *
     * Derived from ToolAbility (the single source of truth for which tool
     * functions belong to which ability), so a new ability shows up here
     * automatically.
     */
    val ALL_TOOLS: List<String> =
        listOf("web_search") + ToolAbility.TOOLS.values.flatten() + ToolAbility.MEMORY_TOOLS

    /**
     * The system-prompt block that tells the model which tools it has RIGHT
     * NOW.
     *
     * [declared] = the tools this request actually declares (read back from
     * the very same specs LlmClient sends — they can never drift apart).
     * [usedUp] = tools that are enabled but already consumed in this turn
     * (the web search runs at most once per turn).
     *
     * The block NEVER mentions the tools that are switched OFF: the model
     * can only call what is declared, and naming the missing ones only
     * makes the prompt bigger (the user asked for this).
     *
     * The block is built FRESH per request (never appended to a stored
     * prompt), so it cannot be duplicated.
     */
    fun availabilityBlock(
        declared: List<String>,
        usedUp: List<String> = emptyList(),
        /**
         * The SLIM mode: the list of the available tools is NOT sent (the
         * JSON the model already receives says what it can call — saying
         * it twice only makes the prompt bigger). The "already used" line
         * stays: that one is not in the JSON.
         */
        slim: Boolean = false
    ): String {
        // Nothing available: the block is not sent at all.
        if (declared.isEmpty() && usedUp.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n")
        if (slim) {
            if (usedUp.isNotEmpty()) {
                sb.append("Already used in this turn (do not call again): ")
                    .append(usedUp.joinToString(", "))
                    .append(". Use the result you already have.")
                    .append("\n")
            }
            return sb.toString()
        }
        if (declared.isNotEmpty()) {
            sb.append("Tools available to you in this turn (call them with the ")
                .append("function-calling interface):\n")
            declared.forEach { name ->
                sb.append("- ").append(name).append(": ")
                    .append(PURPOSE[name] ?: name).append("\n")
            }
        }
        if (usedUp.isNotEmpty()) {
            sb.append("Already used in this turn (do not call again): ")
                .append(usedUp.joinToString(", "))
                .append(". Use the result you already have.")
                .append("\n")
        }
        return sb.toString()
    }
}
