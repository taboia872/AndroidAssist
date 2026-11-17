package com.taboia872.androidassist.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.taboia872.androidassist.settings.AppSettings

/**
 * The WORKSPACE folder: a system folder the user pointed at once, where the
 * assistant can create, read and list files.
 *
 * Android's Storage Access Framework (SAF) is the only way an app can write
 * outside its own sandbox WITHOUT a dangerous permission: the user picks a
 * folder with ACTION_OPEN_DOCUMENT_TREE and the app holds a persistable URI
 * grant on it. That grant survives restarts — which is why the URI is stored
 * in AppSettings instead of being re-requested every time.
 *
 * Everything here works on the tree URI; no absolute paths, no MediaStore.
 */
object Workspace {

    /**
     * The exact shape of UTF-8 bytes that were decoded as Latin-1:
     * a lead byte (C2-DF = 2 bytes, E0-EF = 3 bytes, F0-F4 = 4 bytes)
     * followed by its continuation bytes (80-BF).
     *
     * Only these runs are repaired, so a legitimate emoji (a char above
     * U+00FF) is never touched.
     */
    private val MOJIBAKE_RUN = Regex(
        "[\\u00c2-\\u00df][\\u0080-\\u00bf]" +
            "|[\\u00e0-\\u00ef][\\u0080-\\u00bf]{2}" +
            "|[\\u00f0-\\u00f4][\\u0080-\\u00bf]{3}"
    )

    /** The tree URI the user pointed at ("" = none chosen). */
    fun folderUri(): Uri? {
        val raw = AppSettings.config.value.workspaceFolderUri
        if (raw.isBlank()) return null
        return try {
            Uri.parse(raw)
        } catch (e: Exception) {
            null
        }
    }

    /** Is a folder chosen AND still accessible? */
    fun isReady(context: Context): Boolean {
        val uri = folderUri() ?: return false
        return try {
            // A persisted grant can die (folder deleted, SD card removed).
            // Probing the tree is the only reliable check.
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                uri, DocumentsContract.getTreeDocumentId(uri)
            )
            context.contentResolver.query(
                doc,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** The display name of the pointed folder ("", if unavailable). */
    fun folderName(context: Context): String {
        val uri = folderUri() ?: return ""
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                uri, DocumentsContract.getTreeDocumentId(uri)
            )
            context.contentResolver.query(
                doc,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * The URI that LISTS the children of the workspace folder.
     *
     * CRITICAL: `buildDocumentUriUsingTree` points at the directory ITSELF
     * (querying it returns a single row — the folder), while
     * `buildChildDocumentsUriUsingTree` points at the LIST OF ITS CHILDREN.
     * Listing/looking up files needs the second one; using the first made
     * list_files/read_file/delete_file never find anything.
     */
    private fun childrenUri(tree: android.net.Uri): android.net.Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )

    /** Build the URI of a child document by NAME inside the workspace. */
    private fun childUri(context: Context, name: String): Uri? {
        val tree = folderUri() ?: return null
        return try {
            context.contentResolver.query(
                childrenUri(tree),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                // Start at the FIRST row: a bare moveToNext() skips it.
                if (c.moveToFirst()) {
                    do {
                        val docName = if (nameCol >= 0) c.getString(nameCol) else null
                        if (docName == name) {
                            val docId = if (idCol >= 0) c.getString(idCol) else null
                            if (docId != null) {
                                // A labeled return: a plain `return` here
                                // would return from the `use` lambda (Unit).
                                return@use DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                            }
                        }
                    } while (c.moveToNext())
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Read a file's whole text. Returns null when it does not exist. */
    fun readTextFile(context: Context, name: String): String? {
        val uri = childUri(context, name) ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create or REPLACE a text file. Returns the display name on success.
     *
     * Replace = delete the old one first, then create: some providers refuse
     * to create a document whose name already exists.
     */
    fun writeTextFile(context: Context, name: String, content: String): Result {
        val tree = folderUri()
            ?: return Result(false, "No workspace folder chosen — set one in Settings > Tools.")
        val safeName = name.trim().substringAfterLast('/')
        if (safeName.isBlank()) {
            return Result(false, "The file name is empty.")
        }
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            // Replace an existing file of the same name.
            childUri(context, safeName)?.let { old ->
                try {
                    DocumentsContract.deleteDocument(context.contentResolver, old)
                } catch (e: Exception) {
                    // Some providers refuse deletion — fall through and try
                    // to create anyway.
                }
            }
            // The MIME follows the extension: some providers force the
            // extension of the MIME type (a "text/plain" would turn
            // "resumo.md" into "resumo.txt").
            val mime = mimeFor(safeName)
            val created = DocumentsContract.createDocument(
                context.contentResolver, parent, mime, safeName
            ) ?: return Result(false, "Could not create '$safeName' in the workspace folder.")
            // Default "w" mode: truncates and is supported by every
            // provider (the "wt" mode is not).
            // Some providers deliver the text already mangled (the bytes
            // were decoded as Latin-1 upstream). Repair the classic
            // "UTF-8 read as Latin-1" pattern before writing, otherwise
            // the accents end up in the file as garbage.
            val repaired = repairMojibake(content)
            context.contentResolver.openOutputStream(created)?.use {
                it.write(repaired.toByteArray(Charsets.UTF_8))
            } ?: return Result(false, "Could not write to '$safeName'.")
            Result(true, safeName)
        } catch (e: Exception) {
            Result(false, "Could not write '$safeName': ${e.message}")
        }
    }

    /**
     * Repair text that was decoded as Latin-1 but is really UTF-8.
     *
     * The signature is the sequence "Ã" followed by a Latin-1 supplement
     * character (as in "Ã§" for "ç"). When it is present, the whole text
     * is re-decoded: the UTF-8 bytes are read back as Latin-1 and decoded
     * as UTF-8.
     */
    fun repairMojibake(text: String): String {
        if (!MOJIBAKE_RUN.containsMatchIn(text)) return text
        return MOJIBAKE_RUN.replace(text) { m ->
            try {
                val decoded = String(
                    m.value.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8
                )
                // Keep the original when the decode produced a replacement
                // char (it was not really UTF-8).
                if (decoded.contains('\uFFFD')) m.value else decoded
            } catch (e: Exception) {
                m.value
            }
        }
    }

    /** The MIME type that matches the file extension (text/plain default). */
    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        return when (ext) {
            "md" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "xml" -> "text/xml"
            else -> "text/plain"
        }
    }

    /** Delete a file by name. */
    fun deleteByName(context: Context, name: String): Result {
        val uri = childUri(context, name)
            ?: return Result(false, "No file named '$name' in the workspace folder.")
        return try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
            Result(true, name)
        } catch (e: Exception) {
            Result(false, "Could not delete '$name': ${e.message}")
        }
    }

    /** The files in the workspace folder (name + size in bytes). */
    fun filesIn(context: Context): List<Pair<String, Long>> {
        val tree = folderUri() ?: return emptyList()
        return try {
            context.contentResolver.query(
                childrenUri(tree),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                val out = mutableListOf<Pair<String, Long>>()
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                // Start at the FIRST row: a bare moveToNext() skips it.
                if (c.moveToFirst()) {
                    do {
                        val n = if (nameCol >= 0) c.getString(nameCol) else null
                        if (n.isNullOrBlank()) continue
                        // Folders are not files.
                        val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                        out.add(n to size)
                    } while (c.moveToNext())
                }
                out
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Persist the access grant for the chosen tree, so the folder stays
     * reachable after an app restart. Returns true when the grant was taken.
     */
    fun persistPermission(context: Context, treeUri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Release the grant (when the user points at a different folder). */
    fun releasePermission(context: Context) {
        val uri = folderUri() ?: return
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Already gone — nothing to release.
        }
    }

    /** Small result holder (the tool layer maps it to the timeline block). */
    data class Result(val ok: Boolean, val message: String)

    /** The ContentResolver, for callers that need it directly. */
    fun resolver(context: Context): ContentResolver = context.contentResolver
}
