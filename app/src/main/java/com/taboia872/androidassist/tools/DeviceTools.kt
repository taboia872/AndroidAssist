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
        val wanted = args.optString("app_name", "").trim()
        if (wanted.isBlank()) {
            return Result(false, "\ud83d\udcf1 ?", "Which app? Provide app_name.", "app")
        }
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val w = wanted.lowercase(Locale.ROOT)

        // User-curated whitelist (Tools tab): when not empty, ONLY those
        // packages may be opened by the model.
        val allowed = AppSettings.allowedAppList()
        // best match: exact label > label contains > package contains
        var best: android.content.pm.ResolveInfo? = null
        var bestScore = -1
        for (ri in apps) {
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { "" }
            val pkg = ri.activityInfo.packageName
            val l = label.lowercase(Locale.ROOT)
            val p = pkg.lowercase(Locale.ROOT)
            val score = when {
                l == w -> 100
                l.startsWith(w) -> 80
                l.contains(w) -> 60
                p.contains(w) -> 40
                else -> -1
            }
            if (score > bestScore) { bestScore = score; best = ri }
        }
        if (best == null || bestScore < 0) {
            return Result(false, "\ud83d\udcf1 ? '$wanted'",
                "No installed app matches '$wanted'. Ask the user to check the name.", "app")
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
        if (lat.isNaN() || lon.isNaN()) {
            return Result(false, "\ud83d\udccd ?", "Provide latitude and longitude.", "location")
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
}
