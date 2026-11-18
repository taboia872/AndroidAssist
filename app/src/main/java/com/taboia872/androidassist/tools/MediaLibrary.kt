package com.taboia872.androidassist.tools

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/**
 * The MEDIA LIBRARY of the device: every audio file the system knows
 * about (MediaStore.Audio.Media).
 *
 * WHY MediaStore and not the workspace folder: the user's music is
 * already indexed by the system, in /Music, /Downloads, on the SD card,
 * everywhere. Copying it into the assistant's folder would be absurd —
 * and the assistant has no business doing that. MediaStore is the
 * system's own index and it is READ-ONLY for us: we look, we never
 * modify.
 *
 * PERMISSION: reading the audio of OTHER apps needs READ_MEDIA_AUDIO
 * (Android 13+) or READ_EXTERNAL_STORAGE (Android 12 and older).
 */
object MediaLibrary {

    /** One track of the device library. */
    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val mimeType: String,
        /** When the system indexed the file (seconds since the epoch). */
        val dateAdded: Long = 0L,
        /**
         * The storage volume the track lives on ("primary", the SD
         * card's uuid, ...).
         *
         * It MATTERS for playback: the URI of a track on the SD card is
         * built from ITS volume — a URI built from the primary volume
         * opens nothing on the player.
         */
        val volumeName: String = ""
    ) {
        /**
         * The content:// URI of the track (readable by a player app).
         *
         * Built from the track's OWN volume: `content://media/<volume>/
         * audio/media/<id>`. Using the merged "external" volume here is
         * what made the SD card tracks fail to play.
         */
        fun uri(): Uri {
            // The volume name is only usable from API 29 (Q) — and it must
            // be a REAL volume name ("primary", the SD card's uuid), not
            // the synthetic "external_primary", or the URI points at a
            // volume that does not exist and the player opens nothing.
            val collection = if (android.os.Build.VERSION.SDK_INT >= 29 &&
                volumeName.isNotBlank()
            ) {
                MediaStore.Audio.Media.getContentUri(volumeName)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            return ContentUris.withAppendedId(collection, id)
        }

        /** "3:42" */
        fun durationText(): String {
            val total = durationMs / 1000
            return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        }
    }

    /** Is the permission to read the audio library granted? */
    fun hasPermission(context: Context): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** The permission this Android version needs to read the audio. */
    fun permission(): String = if (android.os.Build.VERSION.SDK_INT >= 33) {
        android.Manifest.permission.READ_MEDIA_AUDIO
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }

    /**
     * Search the device library.
     *
     * [query] matches the title, the artist or the album (without
     * accents). An empty query lists the library (the most recent first).
     * Returns at most [limit] tracks.
     */
    fun search(context: Context, query: String, limit: Int = 20): List<Track> {
        val out = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.VOLUME_NAME,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val needle = TextNormalize.normalize(query)
        // EVERY external volume: the primary storage AND the SD card.
        // A single query on the merged "external" volume does not always
        // see the SD card, and the SD card is where the music lives.
        // The same track can be seen twice (the merged volume and the
        // real one): keep one copy of each.
        val seen = mutableSetOf<String>()
        val volumeNames = volumes(context)
        // The QUOTA per volume. The limit applies to the RESULT, never to
        // the SCAN: with a global limit the first volume drained the
        // list and the SD card was never even queried.
        val perVolume = if (needle.isEmpty()) {
            // A LIST: every volume gets a share, so the SD card is
            // always represented (even with a full primary storage).
            maxOf(1, (limit + volumeNames.size - 1) / volumeNames.size)
        } else {
            // A SEARCH: the matches are what matter.
            limit
        }
        for (volume in volumeNames) {
            val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
                MediaStore.Audio.Media.getContentUri(volume)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            searchIn(context, collection, volume, projection, needle, out, perVolume, seen)
        }
        // The list is coherent across the volumes: the most recent
        // first, wherever the track lives.
        out.sortByDescending { it.dateAdded }
        return out.take(limit)
    }

    /**
     * The external volumes of the device: the primary storage AND the SD
     * cards.
     *
     * The names come from getExternalVolumeNames() and look like
     * ["external_primary", "e4f4-c068"] — the first is the internal
     * storage, the second the SD card's uuid. Each of them works with
     * getContentUri(volumeName).
     *
     * The fallback is the MERGED "external" volume (a view of all of
     * them at once) — it always exists.
     */
    private fun volumes(context: Context): List<String> {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            return listOf(MediaStore.VOLUME_EXTERNAL)
        }
        return try {
            val names = MediaStore.getExternalVolumeNames(context).toList()
            if (names.isEmpty()) listOf(MediaStore.VOLUME_EXTERNAL) else names
        } catch (e: Exception) {
            listOf(MediaStore.VOLUME_EXTERNAL)
        }
    }

    /** The external volumes of the device (for the diagnostic message). */
    fun volumeNames(context: Context): List<String> = volumes(context)

    /** Query ONE volume and collect the matching tracks. */
    private fun searchIn(
        context: Context,
        collection: Uri,
        volume: String,
        projection: Array<String>,
        needle: String,
        out: MutableList<Track>,
        limit: Int,
        seen: MutableSet<String>
    ) {
        try {
            context.contentResolver.query(
                collection,
                projection,
                null, null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val volCol = c.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
                val dateCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                // Start at the FIRST row: a bare moveToNext() skips it.
                if (c.moveToFirst()) {
                    do {
                        val title = if (titleCol >= 0) c.getString(titleCol) else null
                        val artist = if (artistCol >= 0) c.getString(artistCol) else null
                        val album = if (albumCol >= 0) c.getString(albumCol) else null
                        // A query that matches nothing in title/artist/album
                        // is skipped — but an EMPTY query lists everything.
                        if (needle.isNotEmpty()) {
                            val hay = "${title ?: ""} ${artist ?: ""} ${album ?: ""}"
                            if (!TextNormalize.normalize(hay).contains(needle)) continue
                        }
                        val id = if (idCol >= 0) c.getLong(idCol) else -1L
                        if (id < 0) continue
                        val rowVolume = if (volCol >= 0) {
                            c.getString(volCol) ?: volume
                        } else volume
                        if (!seen.add("$rowVolume/$id")) continue
                        out.add(
                            Track(
                                id = id,
                                title = title?.takeIf { it.isNotBlank() } ?: "(sem titulo)",
                                artist = artist?.takeIf { it.isNotBlank() } ?: "(desconhecido)",
                                album = album?.takeIf { it.isNotBlank() } ?: "",
                                durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                                mimeType = if (mimeCol >= 0) {
                                    c.getString(mimeCol) ?: "audio/*"
                                } else "audio/*",
                                dateAdded = if (dateCol >= 0) c.getLong(dateCol) else 0L,
                                volumeName = rowVolume
                            )
                        )
                        if (out.size >= limit) return
                    } while (c.moveToNext())
                }
            }
        } catch (e: Exception) {
            // A volume that cannot be read (ejected SD card) must not
            // kill the whole search.
        }
    }

    /**
     * Play a track in the user's music player app.
     *
     * The intent is ACTION_VIEW with the content:// URI of the track and
     * FLAG_GRANT_READ_URI_PERMISSION — without the flag the player app
     * (which does not have our permission) cannot read the file.
     */
    fun play(context: Context, track: Track, playerName: String? = null): Boolean {
        // The player arrives as a NAME ("Spotify"); setPackage() needs the
        // PACKAGE NAME ("com.spotify.music"). Resolve it — and when the
        // name does not match any app, play with the default one.
        val pkg = playerName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            resolvePlayerPackage(context, it)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(track.uri(), track.mimeType.ifBlank { "audio/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!pkg.isNullOrBlank()) {
                setPackage(pkg)
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The package name of the player app whose name matches [wanted].
     *
     * Matches the app LABEL ("Spotify") or the package itself
     * ("com.spotify.music"). Returns null when nothing matches.
     */
    fun resolvePlayerPackage(context: Context, wanted: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://media/external/audio/media/0"), "audio/*")
        }
        val apps = pm.queryIntentActivities(intent, 0)
        val w = TextNormalize.normalize(wanted)
        var best: String? = null
        var bestScore = -1
        for (ri in apps) {
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { "" }
            val pkg = ri.activityInfo.packageName
            val l = TextNormalize.normalize(label)
            val p = pkg.lowercase(java.util.Locale.ROOT)
            val score = when {
                l == w -> 100
                p == w -> 95
                l.startsWith(w) -> 80
                TextNormalize.wordStartsWith(l, w) -> 70
                l.contains(w) -> 50
                p.contains(w) -> 40
                else -> -1
            }
            if (score > bestScore) {
                bestScore = score
                best = pkg
            }
        }
        return if (bestScore >= 0) best else null
    }

    /**
     * Search a music streaming site (YouTube) on the web.
     *
     * There is NO public API to play a YouTube track from another app —
     * the only honest thing is to open the search results, where the
     * user picks the track and the app plays it.
     */
    fun playOnYouTube(context: Context, query: String): Boolean {
        val url = "https://www.youtube.com/results?search_query=" +
            java.net.URLEncoder.encode(query, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

}
