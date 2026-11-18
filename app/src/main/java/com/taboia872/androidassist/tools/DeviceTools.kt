package com.taboia872.androidassist.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.taboia872.androidassist.net.Net
import com.taboia872.androidassist.settings.AppSettings
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * TIER 1+2 DEVICE TOOLS — executed LOCALLY via Android Intents (no network,
 * no special permissions except SET_ALARM which is a normal permission).
 *
 * The model picks a tool via function calling; ChatSession routes the call
 * here. Every execution returns a user-visible summary + the result text
 * fed back to the model so it can confirm in natural language.
 */
object DeviceTools {

    /** Execution outcome for the chat timeline + model replay. */
    data class Result(
        val ok: Boolean,
        /** Short user-facing line (the tool block header). */
        val userText: String,
        /** Full result fed back to the MODEL on the follow-up round. */
        val modelText: String,
        /** Block icon id (mapped in ToolBlock). */
        val icon: String
    )

    fun execute(context: Context, name: String, argsJson: String): Result {
        return try {
            val args = org.json.JSONObject(argsJson.ifBlank { "{}" })
            when (name) {
                "set_alarm" -> setAlarm(context, args)
                "set_timer" -> setTimer(context, args)
                "add_calendar_event" -> addCalendarEvent(context, args)
                "open_app" -> openApp(context, args)
                "read_clipboard" -> readClipboard(context)
                "add_to_clipboard" -> copyToClipboard(context, args)
                "share_text" -> shareText(context, args)
                "get_location" -> getLocation(context)
                "open_map" -> openMap(context, args)
                "write_file" -> writeFile(context, args)
                "read_file" -> readFile(context, args)
                "list_files" -> listFiles(context, args)
                "create_folder" -> createFolder(context, args)
                "delete_file" -> deleteFile(context, args)
                "delete_folder" -> deleteFolder(context, args)
                "play_media" -> playMedia(context, args)
                "list_media" -> listMedia(context, args)
                "send_message" -> sendMessage(context, args)
                else -> Result(
                    ok = false,
                    userText = "? $name",
                    modelText = "Unknown tool '$name'.",
                    icon = "search"
                )
            }
        } catch (e: Exception) {
            Result(
                ok = false,
                userText = "\u26a0\ufe0f $name",
                modelText = "The tool '$name' failed: ${e.message}",
                icon = "search"
            )
        }
    }

    // ------------------------------------------------------------- alarm

    private fun setAlarm(context: Context, args: org.json.JSONObject): Result {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", 0)
        if (hour !in 0..23) {
            return Result(false, "\u23f0 ?", "Invalid hour '$hour' — use 0-23.", "alarm")
        }
        val label = args.optString("label", "")
        val days = mutableListOf<Int>()
        val dayNames = args.optJSONArray("days")
        if (dayNames != null) {
            val map = mapOf(
                "mon" to java.util.Calendar.MONDAY, "tue" to java.util.Calendar.TUESDAY,
                "wed" to java.util.Calendar.WEDNESDAY, "thu" to java.util.Calendar.THURSDAY,
                "fri" to java.util.Calendar.FRIDAY, "sat" to java.util.Calendar.SATURDAY,
                "sun" to java.util.Calendar.SUNDAY
            )
            for (i in 0 until dayNames.length()) {
                map[dayNames.optString(i).lowercase(Locale.ROOT)]?.let { days.add(it) }
            }
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val time = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
            val dayStr = if (days.isEmpty()) "" else " (" + days.size + "x/semana)"
            val txt = if (label.isBlank()) time else "$time \u2014 $label"
            Result(
                true, "\u23f0 $txt$dayStr",
                "Alarm set for $time$dayStr" +
                    if (label.isBlank()) "" else ", label: '$label'" + ".",
                "alarm"
            )
        } catch (e: Exception) {
            Result(false, "\u23f0 ?", "Could not set the alarm: ${e.message}. " +
                "A clock app that supports alarms must be installed.", "alarm")
        }
    }

    // ------------------------------------------------------------- timer

    private fun setTimer(context: Context, args: org.json.JSONObject): Result {
        val seconds = args.optInt("seconds", 0)
        if (seconds <= 0) {
            return Result(false, "\u23f1 ?", "Invalid duration '$seconds' — use seconds > 0.", "timer")
        }
        val label = args.optString("label", "")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val mins = seconds / 60
            val rest = seconds % 60
            val dur = when {
                seconds >= 3600 -> String.format(Locale.ROOT, "%dh%02dm", seconds / 3600, (seconds % 3600) / 60)
                rest == 0 -> "$mins min"
                else -> "${mins}m ${rest}s"
            }
            val txt = if (label.isBlank()) dur else "$dur \u2014 $label"
            Result(true, "\u23f1 $txt", "Timer started: $dur" +
                if (label.isBlank()) "." else ", label: '$label'.", "timer")
        } catch (e: Exception) {
            Result(false, "\u23f1 ?", "Could not start the timer: ${e.message}.", "timer")
        }
    }

    // ------------------------------------------------------------- calendar

    private fun addCalendarEvent(context: Context, args: org.json.JSONObject): Result {
        val title = args.optString("title", "")
        if (title.isBlank()) {
            return Result(false, "\ud83d\udcc5 ?", "The event needs a title.", "calendar")
        }
        val beginRaw = args.optString("begin_time", "")
        val begin = parseTime(beginRaw)
            ?: return Result(false, "\ud83d\udcc5 ?",
                "Invalid begin_time '$beginRaw' — use ISO 8601 like 2026-10-22T15:30.", "calendar")

        val allDay = args.optBoolean("all_day", false)
        val durationMin = if (allDay) 0 else args.optInt("duration_minutes", 60)
        val end = if (allDay) begin else begin.plusMinutes(durationMin.toLong())
        val description = args.optString("description", "")
        val tz = ZoneId.systemDefault().id

        val mode = AppSettings.config.value.calendarMode
        val calId = AppSettings.config.value.defaultCalendarId

        // DIRECT mode: insert straight into the chosen system calendar via
        // ContentResolver — hands-free, no screen. Requires WRITE_CALENDAR
        // (runtime permission asked by the screen hosting the chat) and a
        // picked calendar. Falls back to the confirm-intent otherwise.
        val canDirect = mode == "direct" && calId > 0 &&
            context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        if (canDirect) {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                if (description.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART,
                    begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND,
                    end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                put(CalendarContract.Events.ALL_DAY, allDay)
            }
            return try {
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    // Default reminder 30min before, so the event actually notifies
                    val rem = android.content.ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, uri.lastPathSegment?.toLong() ?: 0L)
                        put(CalendarContract.Reminders.MINUTES, 30)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    }
                    try {
                        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rem)
                    } catch (e: Exception) { }
                    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE dd/MM HH:mm")
                        .withLocale(Locale.ROOT)
                    val whenTxt = begin.format(fmt) + if (allDay) " (dia todo)" else ""
                    val calName = calendarName(context, calId)
                    Result(
                        true, "\ud83d\udcc5 $title \u00b7 $whenTxt",
                        "Event SAVED directly in the calendar '$calName' (id $calId): " +
                            "title '$title', start $whenTxt, " +
                            if (allDay) "all-day." else "duration ${durationMin}min, " +
                                "default reminder 30 minutes before. No further action needed.",
                        "calendar"
                    )
                } else {
                    confirmFallback(context, title, description, begin, end, allDay, durationMin)
                }
            } catch (e: SecurityException) {
                confirmFallback(context, title, description, begin, end, allDay, durationMin)
            }
        }
        return confirmFallback(context, title, description, begin, end, allDay, durationMin)
    }

    /** CONFIRM mode: open the calendar app pre-filled; user saves there. */
    private fun confirmFallback(
        context: Context,
        title: String,
        description: String,
        begin: LocalDateTime,
        end: LocalDateTime,
        allDay: Boolean,
        durationMin: Int
    ): Result {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE dd/MM HH:mm")
                .withLocale(Locale.ROOT)
            val whenTxt = begin.format(fmt) + if (allDay) " (dia todo)" else ""
            Result(
                true, "\ud83d\udcc5 $title \u00b7 $whenTxt",
                "Calendar event opened in the calendar app with these fields (the user " +
                    "can review and save it there): title '$title', start $whenTxt, " +
                    if (allDay) "all-day." else "duration ${durationMin}min.",
                "calendar"
            )
        } catch (e: Exception) {
            Result(false, "\ud83d\udcc5 ?", "No calendar app handled the event: ${e.message}.", "calendar")
        }
    }

    /** Display name of a system calendar id (for the confirmation text). */
    private fun calendarName(context: Context, calId: Long): String {
        return try {
            val uri = CalendarContract.Calendars.CONTENT_URI
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(calId.toString()),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else "calendar"
            } ?: "calendar"
        } catch (e: Exception) { "calendar" }
    }

    /** ISO 8601 (with or without offset) or plain yyyy-MM-ddTHH:mm -> LocalDateTime. */
    private fun parseTime(raw: String): LocalDateTime? {
        return try {
            OffsetDateTime.parse(raw).toLocalDateTime()
        } catch (e: Exception) {
            try { LocalDateTime.parse(raw) } catch (e2: Exception) {
                try { LocalDateTime.parse(raw + "T00:00") } catch (e3: Exception) { null }
            }
        }
    }

    // ------------------------------------------------------------- open app

    private fun openApp(context: Context, args: org.json.JSONObject): Result {
        // Accept the common key variants: some models send "app" or "name".
        val wanted = args.optString("app_name", "")
            .ifBlank { args.optString("app", args.optString("name", "")) }
            .trim()
            // Some providers stringify a null argument as the literal "null".
            .takeUnless { it.isBlank() || it == "null" } ?: ""
        if (wanted.isBlank()) {
            return Result(false, "\ud83d\udcf1 ?", "Which app? Provide app_name.", "app")
        }
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        // Normalize the wanted name the SAME way as the labels: lower case
        // and WITHOUT accents ("musica" must match "Música").
        val w = normalizeName(wanted)

        // User-curated whitelist (Tools tab): when not empty, ONLY those
        // packages may be opened by the model.
        val allowed = AppSettings.allowedAppList()
        // best match: exact label > label starts with > word starts with >
        // label contains > package contains
        var best: android.content.pm.ResolveInfo? = null
        var bestScore = -1
        for (ri in apps) {
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { "" }
            val pkg = ri.activityInfo.packageName
            val l = normalizeName(label)
            val p = pkg.lowercase(Locale.ROOT)
            val score = when {
                l == w -> 100
                l.startsWith(w) -> 85
                // word-boundary match: "spot" matches "Spotify", "map"
                // matches "Google Maps" — but "ap" does NOT match "Maps".
                TextNormalize.wordStartsWith(l, w) -> 70
                l.contains(w) -> 55
                p.contains(w) -> 40
                else -> -1
            }
            if (score > bestScore) { bestScore = score; best = ri }
        }
        if (best == null || bestScore < 0) {
            // Listing a few candidates helps the model retry with the right
            // name instead of giving up ("no app matches X").
            val sample = apps.mapNotNull { ri ->
                try { ri.loadLabel(pm).toString() } catch (e: Exception) { null }
            }.filter { it.isNotBlank() }.distinct().take(8).joinToString(", ")
            return Result(false, "\ud83d\udcf1 ? '$wanted'",
                "No installed app matches '$wanted'." +
                    if (sample.isNotBlank())
                        " Apps available: $sample. Retry with one of these names."
                    else
                        " No launcher app could be listed at all (package " +
                            "visibility blocked the query).", "app")
        }
        if (allowed.isNotEmpty() && !allowed.contains(best!!.activityInfo.packageName)) {
            return Result(false, "\ud83d\udcf1 '$wanted' bloqueado",
                "The app '${best!!.loadLabel(pm)}' is not in the user's allowed-apps " +
                    "list (Settings > Tools). Tell the user to add it there if they want " +
                    "the assistant to open it.", "app")
        }
        val label = best!!.loadLabel(pm).toString()
        val launch = pm.getLaunchIntentForPackage(best!!.activityInfo.packageName)
            ?: return Result(false, "\ud83d\udcf1 ?", "Cannot launch $label.", "app")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return Result(
            true, "\ud83d\udcf1 $label",
            "Opened the app '$label' (${best!!.activityInfo.packageName}).", "app"
        )
    }

    /**
     * Lower case + strip accents, so "musica" matches "Música" and
     * "map" matches "Google Maps".
     */
    private fun normalizeName(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .trim()
    }

    /**
     * True when [needle] starts a WORD of [haystack] ("spot" matches
     * "Spotify"; "ap" does NOT match "Maps").
     */


    // ------------------------------------------------------------- clipboard

    private fun readClipboard(context: Context): Result {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0)?.coerceToText(context)?.toString() else null
        return if (text.isNullOrBlank()) {
            Result(true, "\ud83d\udccb vazio",
                "The clipboard is empty or not accessible.", "clipboard")
        } else {
            Result(true, "\ud83d\udccb ${text.length} chars",
                "Clipboard content:\n\n" + text.take(4000), "clipboard")
        }
    }

    private fun copyToClipboard(context: Context, args: org.json.JSONObject): Result {
        val text = args.optString("text", "")
        if (text.isBlank()) {
            return Result(false, "\ud83d\udccb ?", "Nothing to copy — provide text.", "clipboard")
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("AndroidAssist", text))
        return Result(true, "\ud83d\udccb copiado",
            "Copied to the clipboard (${text.length} characters).", "clipboard")
    }

    // ------------------------------------------------------------- share

    private fun shareText(context: Context, args: org.json.JSONObject): Result {
        val text = args.optString("text", "")
        if (text.isBlank()) {
            return Result(false, "\ud83d\udd17 ?", "Nothing to share — provide text.", "share")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            Result(true, "\ud83d\udd17 compartilhar",
                "The system share sheet is open with the text — the user picks the target app.", "share")
        } catch (e: Exception) {
            Result(false, "\ud83d\udd17 ?", "Could not open the share sheet: ${e.message}.", "share")
        }
    }

    // ------------------------------------------------------------- location

    /**
     * Coarse location via the passive/network provider — NO Google services,
     * no geocoding API. Returns lat/lon (+approx accuracy) so the model can
     * feed transit sites (bus schedules etc.) with web_search.
     */
    private fun getLocation(context: Context): Result {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return Result(
                true, "\ud83d\udccd permiss\u00e3o necess\u00e1ria",
                "Location permission is not granted. Ask the user to grant " +
                    "location permission to the app in system settings, then try again.",
                "location"
            )
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        // Fresh fix request: coarse (network/cell ~100m) when fine is denied.
        // Falls back to lastKnown when no provider answers in time.
        var best: android.location.Location? = null
        try {
            val future = java.util.concurrent.CompletableFuture<android.location.Location?>()
            val listener = android.location.LocationListener { l ->
                future.complete(l)
            }
            val provider = when {
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) &&
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED ->
                    android.location.LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                    android.location.LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(android.location.LocationManager.PASSIVE_PROVIDER) ->
                    android.location.LocationManager.PASSIVE_PROVIDER
                else -> null
            }
            if (provider != null) {
                lm.requestLocationUpdates(provider, 0L, 0f, listener,
                    context.mainLooper)
                try {
                    best = future.get(12, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                } finally {
                    lm.removeUpdates(listener)
                }
            }
        } catch (e: SecurityException) {
        } catch (e: Exception) {
        }

        // fallback: most recent lastKnown across providers
        if (best == null) {
            for (p in listOf(
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER,
                android.location.LocationManager.GPS_PROVIDER
            )) {
                try {
                    val l = lm.getLastKnownLocation(p) ?: continue
                    if (best == null || l.time > best!!.time) best = l
                } catch (e: SecurityException) {
                } catch (e: IllegalArgumentException) {
                }
            }
        }

        if (best == null) {
            return Result(
                true, "\ud83d\udccd sem fix",
                "No location available. Ask the user to enable location and try again.",
                "location"
            )
        }

        val lat = best!!.latitude
        val lon = best!!.longitude
        val acc = best!!.accuracy.toInt()

        // Reverse geocode via Nominatim (OpenStreetMap — no key, no Google,
        // matches the user's privacy choice of Organic Maps). One request.
        var place = ""
        try {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2" +
                "&lat=$lat&lon=$lon&zoom=16&accept-language=pt-BR"
            val conn = Net.open(url).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "AndroidAssist/0.1 (personal assistant app)")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val addr = org.json.JSONObject(body).optJSONObject("address")
                    if (addr != null) {
                        val road = addr.optString("road", "")
                        val suburb = addr.optString("suburb", addr.optString("neighbourhood", ""))
                        val city = addr.optString("city", addr.optString("town",
                            addr.optString("village", addr.optString("county", ""))))
                        val state = addr.optString("state", "")
                        place = listOf(road, suburb, city, state)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
        }

        val header = if (place.isNotBlank())
            "\ud83d\udccd ${place.take(40)}"
        else
            "\ud83d\udccd ${"%.4f".format(lat)}, ${"%.4f".format(lon)} (~${acc}m)"

        val detail = buildString {
            append("The user's approximate location: ")
            if (place.isNotBlank()) {
                append("near '$place' (this is the street/neighbourhood/city from OpenStreetMap). ")
            }
            append("Coordinates latitude $lat, longitude $lon, accuracy ~${acc}m. ")
            append("Use this for local questions: transit stops and schedules, ")
            append("nearby places, distances. When answering, mention the place ")
            append("name, not the raw coordinates.")
        }

        return Result(true, header, detail, "location")
    }

    /** Open a point in the user's map app (geo: URI — Organic Maps, OsmAnd...). */
    private fun openMap(context: Context, args: org.json.JSONObject): Result {
        val lat = args.optDouble("latitude", Double.NaN)
        val lon = args.optDouble("longitude", Double.NaN)
        val label = args.optString("label", "")
        // A PLACE NAME instead of coordinates: "open the map" with no
        // coordinates is a normal request, and refusing it made the tool
        // look broken. geo:0,0?q=<place> opens the map app's search.
        val place = args.optString("place", args.optString("query", ""))
            .trim()
            // Some providers stringify a null argument as the literal "null".
            .takeUnless { it.isBlank() || it == "null" } ?: ""
        if (lat.isNaN() || lon.isNaN()) {
            if (place.isBlank()) {
                return Result(
                    false, "\ud83d\udccd ?",
                    "Provide latitude and longitude, OR a place name to search for.",
                    "location"
                )
            }
            val search = android.net.Uri.parse(
                "geo:0,0?q=" + java.net.URLEncoder.encode(place, "UTF-8")
            )
            val searchIntent = Intent(Intent.ACTION_VIEW, search).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(Intent.createChooser(searchIntent, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Result(
                    true, "\ud83d\udccd $place",
                    "Opened the map app searching for '$place'. " +
                        "The user picks the map app in the chooser.",
                    "location"
                )
            } catch (e: Exception) {
                Result(
                    false, "\ud83d\udccd ?",
                    "No map app handles geo: URIs: ${e.message}. " +
                        "Suggest installing Organic Maps or OsmAnd.",
                    "location"
                )
            }
        }
        // geo:lat,lon?z=17&q=lat,lon(label) — works on Organic Maps,
        // woheller69 maps and OsmAnd; a chooser appears when several are
        // installed. NO Google Maps dependency.
        val uri = android.net.Uri.parse(
            "geo:$lat,$lon?z=17&q=$lat,$lon(${java.net.URLEncoder.encode(label.ifBlank { "Ponto" }, "UTF-8")})"
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Result(
                true, "\ud83d\udccd ${label.ifBlank { "mapa" }}",
                "Map opened at $lat,$lon" + if (label.isBlank()) "." else " ('$label') — " +
                    "the user picks the map app in the chooser.",
                "location"
            )
        } catch (e: Exception) {
            Result(false, "\ud83d\udccd ?", "No map app handles geo: URIs: ${e.message}. " +
                "Suggest installing Organic Maps or OsmAnd.", "location")
        }
    }

    // ------------------------------------------------------------- files

    /**
     * The workspace folder must be chosen, or the file tools refuse to run.
     *
     * The wording matters: the model used to answer "the folder is empty"
     * (an invention) because the refusal was too soft. Now it says the
     * folder was never selected, and the USER sees it in the tool block
     * too — not a "?".
     */
    private fun workspaceMissing(): Result =
        Result(
            false, "\ud83d\udcc1 sem pasta",
            "STOP: there is NO workspace folder. The user has NOT selected a " +
                "folder yet — this is NOT the same as an empty folder. " +
                "Do NOT say the folder is empty and do NOT invent any file " +
                "contents. Tell the user exactly this: the workspace folder " +
                "has not been set yet, and to choose one in " +
                "Settings > Tools > Workspace folder.",
            "files"
        )

    /** Create or replace a text file in the workspace folder. */
    private fun writeFile(context: Context, args: org.json.JSONObject): Result {
        if (Workspace.folderUri() == null) return workspaceMissing()
        val name = args.optString("filename", "").trim()
        if (name.isBlank()) {
            return Result(false, "\ud83d\udcc1 ?", "The file name is empty.", "files")
        }
        val content = args.optString("content", "")
        val folder = args.optString("folder", "").trim()
        val res = Workspace.writeTextFile(context, name, content, folder)
        return if (res.ok) {
            Result(
                true, "\ud83d\udcc1 ${res.message}",
                "File '${res.message}' written to the workspace folder " +
                    "(${content.length} characters)" +
                    if (folder.isEmpty()) "."
                    else ", inside the folder '$folder'.",
                "files"
            )
        } else {
            Result(false, "\ud83d\udcc1 ?", res.message, "files")
        }
    }

    /** Read a text file from the workspace folder. */
    private fun readFile(context: Context, args: org.json.JSONObject): Result {
        if (Workspace.folderUri() == null) return workspaceMissing()
        val name = args.optString("filename", "").trim()
        if (name.isBlank()) {
            return Result(false, "\ud83d\udcc1 ?", "The file name is empty.", "files")
        }
        val folder = args.optString("folder", "").trim()
        val where = if (folder.isEmpty()) "the workspace folder" else "the folder '$folder'"
        val text = Workspace.readTextFile(context, name, folder)
            ?: return Result(
                false, "\ud83d\udcc1 ?",
                "No file named '$name' in $where. Use list_files to see what is there.",
                "files"
            )
        return Result(
            true, "\ud83d\udcc1 $name",
            "Contents of '$name' (${text.length} characters):\n---\n$text\n---",
            "files"
        )
    }

    /**
     * List what is inside a folder of the workspace: the files AND the
     * subfolders.
     *
     * The optional "folder" argument goes INTO a subfolder of the
     * workspace root ("" = the root itself).
     */
    private fun listFiles(context: Context, args: org.json.JSONObject): Result {
        if (Workspace.folderUri() == null) return workspaceMissing()
        val folder = args.optString("folder", "").trim()
        val entries = Workspace.entriesIn(context, folder)
            ?: return Result(
                false, "\ud83d\udcc1 ?",
                "There is no folder named '$folder' in the workspace folder. " +
                    "Use list_files without arguments to see the top level.",
                "files"
            )
        val where = if (folder.isEmpty()) "the workspace folder" else "the folder '$folder'"
        if (entries.isEmpty()) {
            return Result(
                true, "\ud83d\udcc1 vazio",
                "The $where is empty — no files and no subfolders yet.",
                "files"
            )
        }
        val listing = entries.joinToString("\n") { e ->
            if (e.isFolder) "- ${e.name}/ (folder)"
            else "- ${e.name} (${e.size} bytes)"
        }
        val fileCount = entries.count { !it.isFolder }
        val folderCount = entries.size - fileCount
        return Result(
            true, "\ud83d\udcc1 ${entries.size} item(ns)",
            "Contents of $where:\n$listing\n\n" +
                "Use list_files with folder=\"<name>\" to go into a subfolder.",
            "files"
        )
    }

    /** Create a FOLDER inside the workspace (or inside one of its subfolders). */
    private fun createFolder(context: Context, args: org.json.JSONObject): Result {
        if (Workspace.folderUri() == null) return workspaceMissing()
        val name = args.optString("name", "").trim()
        if (name.isBlank()) {
            return Result(false, "\ud83d\udcc1 ?", "The folder name is empty.", "files")
        }
        val inside = args.optString("folder", "").trim()
        val res = Workspace.createFolder(context, name, inside)
        return if (res.ok) {
            Result(
                true, "\ud83d\udcc1 ${res.message}/",
                "Folder '${res.message}' created" +
                    if (inside.isEmpty()) " in the workspace folder."
                    else " inside '$inside'.",
                "files"
            )
        } else {
            Result(false, "\ud83d\udcc1 ?", res.message, "files")
        }
    }

    /** Delete a FILE from the workspace folder. */
    private fun deleteFile(context: Context, args: org.json.JSONObject): Result {
        if (Workspace.folderUri() == null) return workspaceMissing()
        val name = args.optString("filename", "").trim()
        if (name.isBlank()) {
            return Result(false, "\ud83d\udcc1 ?", "The file name is empty.", "files")
        }
        val folder = args.optString("folder", "").trim()
        // A FOLDER is not a file: point the model to delete_folder
        // instead of failing with something cryptic.
        if (Workspace.isFolder(context, name, folder)) {
            return Result(
                false, "\ud83d\udcc1 ?",
                "'$name' is a FOLDER, not a file. Use delete_folder to remove a " +
                    "folder (and everything inside it).",
                "files"
            )
        }
        val res = Workspace.deleteByName(context, name, folder)
        return if (res.ok) {
            Result(true, "\ud83d\udcc1 $name apagado", "File '$name' deleted.", "files")
        } else {
            Result(false, "\ud83d\udcc1 ?", res.message, "files")
        }
    }

    /** Delete a FOLDER (and everything inside it) from the workspace. */
    private fun deleteFolder(context: Context, args: org.json.JSONObject): Result {
        if (Workspace.folderUri() == null) return workspaceMissing()
        val name = args.optString("name", "").trim()
            .ifBlank { args.optString("filename", "").trim() }
        if (name.isBlank()) {
            return Result(false, "\ud83d\udcc1 ?", "The folder name is empty.", "files")
        }
        val folder = args.optString("folder", "").trim()
        val res = Workspace.deleteFolder(context, name, folder)
        return if (res.ok) {
            Result(
                true, "\ud83d\udcc1 ${res.message} apagado",
                "Folder '$name' deleted (${res.message}).",
                "files"
            )
        } else {
            Result(false, "\ud83d\udcc1 ?", res.message, "files")
        }
    }

    // ------------------------------------------------------------- media

    /**
     * The media tools need the permission to read the audio library of
     * the device. Without it the model gets a clear instruction instead
     * of an empty list.
     */
    private fun mediaPermissionMissing(): Result =
        Result(
            false, "\ud83c\udfb5 sem permissao",
            "The assistant cannot read the music library of the device because " +
                "the media permission was not granted. Tell the user to open " +
                "Settings > Tools > Media permission and allow it. Do NOT say " +
                "the library is empty.",
            "media"
        )

    /** List the audio files of the device (the user's music). */
    private fun listMedia(context: Context, args: org.json.JSONObject): Result {
        if (!MediaLibrary.hasPermission(context)) return mediaPermissionMissing()
        val query = args.optString("query", "").trim()
        val tracks = MediaLibrary.search(context, query)
        if (tracks.isEmpty()) {
            val what = if (query.isEmpty()) "the music library" else "anything matching '$query'"
            return Result(
                true, "\ud83c\udfb5 0 faixa(s)",
                "There is no audio file in $what of this device.",
                "media"
            )
        }
        val listing = tracks.joinToString("\n") { t ->
            "- ${t.title} — ${t.artist} (${t.durationText()})"
        }
        return Result(
            true, "\ud83c\udfb5 ${tracks.size} faixa(s)",
            "Audio files found on the device:\n$listing\n\n" +
                "Use play_media with one of these titles to play it.",
            "media"
        )
    }

    /**
     * Play a track.
     *
     * "source" decides WHERE: "device" (the user's own music, by title)
     * or "youtube" (a streaming site, opened on the web).
     */
    private fun playMedia(context: Context, args: org.json.JSONObject): Result {
        val title = args.optString("title", "").trim()
            .ifBlank { args.optString("query", "").trim() }
            .ifBlank { args.optString("name", "").trim() }
        if (title.isBlank()) {
            return Result(false, "\ud83c\udfb5 ?", "Which track? Provide the title.", "media")
        }
        val source = args.optString("source", "device").trim().lowercase(Locale.ROOT)
        // A YouTube request does not need the media permission.
        if (source == "youtube" || source == "yt" || source == "web") {
            val ok = MediaLibrary.playOnYouTube(context, title)
            return if (ok) {
                Result(
                    true, "\ud83c\udfb5 YouTube: $title",
                    "Opened the YouTube search for '$title' on the device. The user " +
                        "picks the track there and the app plays it.",
                    "media"
                )
            } else {
                Result(
                    false, "\ud83c\udfb5 ?",
                    "Could not open YouTube (no browser or the YouTube app).",
                    "media"
                )
            }
        }
        // The device library.
        if (!MediaLibrary.hasPermission(context)) return mediaPermissionMissing()
        val tracks = MediaLibrary.search(context, title, limit = 10)
        if (tracks.isEmpty()) {
            // Hand the model the closest tracks: without them it just
            // said "there is no version of the song in your library" and
            // offered YouTube, without knowing what IS there.
            val closest = MediaLibrary.search(context, "", limit = 12)
            val names = closest.joinToString(", ") { "'${it.title}' (${it.artist})" }
            return Result(
                false, "\ud83c\udfb5 ?",
                "No audio file on the device matches '$title'. " +
                    (if (names.isNotEmpty())
                        "These tracks ARE available: $names. If one of them is the " +
                            "one the user meant, play THAT exact title."
                    else
                        "The device library appears to be empty.") +
                    " Do NOT call play_media again in this turn — answer the user " +
                    "with what is available.",
                "media"
            )
        }
        // The best match: exact title > title starts with > title contains.
        val needle = title.lowercase(Locale.ROOT).trim()
        val best = tracks.minByOrNull { t ->
            val tt = t.title.lowercase(Locale.ROOT)
            when {
                tt == needle -> 0
                tt.startsWith(needle) -> 1
                tt.contains(needle) -> 2
                else -> 3
            }
        } ?: tracks.first()
        val player = args.optString("player", "").trim().ifBlank { null }
        val ok = MediaLibrary.play(context, best, player)
        return if (ok) {
            Result(
                true, "\ud83c\udfb5 ${best.title}",
                "Sent '${best.title}' (${best.artist}) to the music player app.",
                "media"
            )
        } else {
            Result(
                false, "\ud83c\udfb5 ?",
                "Could not open a music player for '${best.title}'. " +
                    "Maybe no player app is installed, or the requested player " +
                    "'$player' does not exist.",
                "media"
            )
        }
    }

    // ---------------------------------------------------------- messaging

    /**
     * Open a messaging app with the message ready to send.
     *
     * The contact is resolved from the agenda by name; the messaging app is
     * opened with the text PRE-FILLED and the user still taps "send" —
     * no app may send in the user's name without that confirmation.
     */
    private fun sendMessage(context: Context, args: org.json.JSONObject): Result {
        val contact = args.optString("contact", "").trim()
        if (contact.isBlank()) {
            return Result(
                false, "\ud83d\udce4 ?",
                "Tell the user which contact the message is for.",
                "share"
            )
        }
        val message = args.optString("message", "")
        val preferred = args.optString("app", "").lowercase(Locale.ROOT)

        val hits = Contacts.search(context, contact)
        if (hits.isEmpty()) {
            return Result(
                false, "\ud83d\udce4 ?",
                "No contact matching '$contact' was found in the agenda. " +
                    "Ask the user for the exact name, or list what the tool found.",
                "share"
            )
        }
        val hit = hits.first()
        val number = Contacts.toDialFormat(hit.phone)

        // Package per preferred app; empty = let the system choose.
        val packages = when (preferred) {
            "whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
            "telegram" -> listOf("org.telegram.messenger")
            "sms", "mms" -> listOf("com.google.android.apps.messaging")
            else -> emptyList()
        }
        // Only pin the package when it is actually INSTALLED: a chooser
        // with a missing target has nothing to show and the intent fails
        // with no feedback.
        val installed = packages.firstOrNull { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra("sms_body", message)
            putExtra("android.intent.extra.TEXT", message)
            if (installed != null) setPackage(installed)
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            val who = if (hits.size > 1)
                "${hit.name} (${hits.size} contacts matched — the user should check the one opened)"
            else hit.name
            Result(
                true, "\ud83d\udce4 $who",
                "The messaging app is open with the message to '$who' " +
                    "(number $number) ready to send. The user confirms and sends it. " +
                    "Do NOT claim the message was already sent.",
                "share"
            )
        } catch (e: Exception) {
            Result(
                false, "\ud83d\udce4 ?",
                "Could not open a messaging app: ${e.message}. " +
                    "Suggest installing WhatsApp, Telegram or using SMS.",
                "share"
            )
        }
    }
}
