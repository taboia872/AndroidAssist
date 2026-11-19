package com.taboia872.androidassist.tools

/**
 * The Android abilities the assistant can use, each ONE independently
 * toggleable (Settings > Tools). The master switch
 * (AppSettings.deviceToolsEnabled) gates the whole group; the per-ability
 * switches live in AppSettings.deviceToolToggles (a JSON map).
 *
 * A MISSING id in that map means ENABLED — installs that never touched the
 * switches keep every ability available.
 *
 * The web search (web_search) and the memory tools
 * (save_memory/search_memories/forget_memory) are NOT abilities: they have
 * their own independent switches (webSearchEnabled / memoryToolsEnabled).
 */
object ToolAbility {

    const val ALARM = "alarm"
    const val TIMER = "timer"
    const val CALENDAR = "calendar"
    const val OPEN_APP = "open_app"
    const val CLIPBOARD = "clipboard"
    const val SHARE = "share"
    const val LOCATION = "location"
    const val MAP = "map"
    /** The folder the user pointed at: create/read/list/delete files. */
    const val FILES = "files"
    /** Send a message to a contact of the agenda (WhatsApp and friends). */
    const val MESSAGING = "messaging"
    /** Open a web page (a link from a search, a site, a playlist). */
    const val OPEN_URL = "open_url"

    /** Ability id -> the tool function names it covers (one or more). */
    val TOOLS: Map<String, List<String>> = mapOf(
        ALARM to listOf("set_alarm"),
        TIMER to listOf("set_timer"),
        CALENDAR to listOf("add_calendar_event"),
        OPEN_APP to listOf("open_app"),
        CLIPBOARD to listOf("read_clipboard", "add_to_clipboard"),
        SHARE to listOf("share_text"),
        LOCATION to listOf("get_location"),
        MAP to listOf("open_map"),
        FILES to listOf(
            "write_file", "read_file", "list_files", "create_folder",
            "delete_file", "delete_folder"
        ),
        MESSAGING to listOf("send_message"),
        OPEN_URL to listOf("open_url")
    )

    /** The memory tools — their own group, independent from the abilities. */
    val MEMORY_TOOLS: List<String> = listOf(
        "save_memory", "search_memories", "forget_memory"
    )

    /** Which ability covers this tool function name (null = not an ability). */
    fun of(toolName: String): String? =
        TOOLS.entries.firstOrNull { toolName in it.value }?.key
}
