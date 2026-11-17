package com.taboia872.androidassist.tools

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.taboia872.androidassist.settings.AppSettings

/**
 * The WORKSPACE folder: a system folder the user pointed at once, where the
 * assistant can create, read and list files and subfolders.
 *
 * Android's Storage Access Framework (SAF) is the only way an app can write
 * outside its own sandbox WITHOUT a dangerous permission: the user picks a
 * folder with ACTION_OPEN_DOCUMENT_TREE and the app holds a persistable URI
 * grant on it. That grant survives restarts — which is why the URI is stored
 * in AppSettings instead of being re-requested every time.
 *
 * Everything here works on the tree URI; no absolute paths, no MediaStore.
 *
 * FOLDERS: SAF addresses a document by its id, so a subfolder has to be
 * looked up by name before its children can be listed. Paths are relative to
 * the workspace root and use "/" as the separator ("", "Notes", "Notes/2026").
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
     * The URI that LISTS the children of the workspace root.
     *
     * CRITICAL: `buildDocumentUriUsingTree` points at the directory ITSELF
     * (querying it returns a single row — the folder), while
     * `buildChildDocumentsUriUsingTree` points at the LIST OF ITS CHILDREN.
     * Listing/looking up files needs the second one; using the first made
     * list_files/read_file/delete_file never find anything.
     */
    private fun childrenUri(tree: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )

    /**
     * The document id of the FOLDER at [path] (relative to the root).
     *
     * "" = the root itself. "Notes" = the "Notes" subfolder.
     * "Notes/2026" = a folder inside a folder.
     *
     * Returns null when any segment does not exist (or is not a folder).
     */
    private fun folderDocId(context: Context, tree: Uri, path: String): String? {
        var currentId = DocumentsContract.getTreeDocumentId(tree)
        val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        for (segment in segments) {
            val nextId = childDocId(context, tree, currentId, segment, foldersOnly = true)
                ?: return null
            currentId = nextId
        }
        return currentId
    }

    /**
     * The document id of a child by NAME inside the folder [parentId].
     *
     * With [foldersOnly] the child has to be a directory — that is how a
     * path segment is resolved.
     */
    private fun childDocId(
        context: Context,
        tree: Uri,
        parentId: String,
        name: String,
        foldersOnly: Boolean = false
    ): String? {
        val queryUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return try {
            context.contentResolver.query(
                queryUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                var found: String? = null
                // Start at the FIRST row: a bare moveToNext() skips it.
                if (c.moveToFirst()) {
                    do {
                        val docName = if (nameCol >= 0) c.getString(nameCol) else null
                        val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                        val isFolder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        if (docName == name && (!foldersOnly || isFolder)) {
                            val id = if (idCol >= 0) c.getString(idCol) else null
                            if (id != null) {
                                found = id
                                break
                            }
                        }
                    } while (c.moveToNext())
                }
                found
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * One entry of a folder listing: a FILE or a FOLDER.
     *
     * The folders matter as much as the files: the assistant has to be able
     * to see the subfolders of the pointed folder and go into them.
     */
    data class Entry(
        val name: String,
        val size: Long,
        val isFolder: Boolean
    )

    /**
     * The CONTENT of a folder: the files AND the subfolders.
     *
     * [path] is the folder, relative to the workspace root ("" = the root
     * itself). Returns null when the folder does not exist, so the caller
     * can tell "empty" from "not there".
     */
    fun entriesIn(context: Context, path: String = ""): List<Entry>? {
        val tree = folderUri() ?: return null
        val parentId = folderDocId(context, tree, path) ?: return null
        val queryUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return try {
            context.contentResolver.query(
                queryUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                val out = mutableListOf<Entry>()
                val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                // Start at the FIRST row: a bare moveToNext() skips it.
                if (c.moveToFirst()) {
                    do {
                        val n = if (nameCol >= 0) c.getString(nameCol) else null
                        if (n.isNullOrBlank()) continue
                        val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                        val isFolder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        val size = if (isFolder) 0L
                        else if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                        out.add(Entry(n, size, isFolder))
                    } while (c.moveToNext())
                }
                out
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Build the URI of a child FILE by NAME inside [path]. */
    private fun childUri(context: Context, name: String, path: String = ""): Uri? {
        val tree = folderUri() ?: return null
        val parentId = folderDocId(context, tree, path) ?: return null
        val docId = childDocId(context, tree, parentId, name, foldersOnly = false)
            ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    }

    /** Read a file's whole text. Returns null when it does not exist. */
    fun readTextFile(context: Context, name: String, path: String = ""): String? {
        val uri = childUri(context, name, path) ?: return null
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
    fun writeTextFile(
        context: Context,
        name: String,
        content: String,
        path: String = ""
    ): Result {
        val tree = folderUri()
            ?: return Result(false, "No workspace folder chosen — set one in Settings > Tools.")
        val safeName = name.trim().substringAfterLast('/')
        if (safeName.isBlank()) {
            return Result(false, "The file name is empty.")
        }
        return try {
            val parentId = folderDocId(context, tree, path)
                ?: return Result(false, "The folder '$path' does not exist.")
            // The parent for createDocument is the DIRECTORY uri, not the
            // children uri.
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            // Replace an existing file of the same name.
            childDocId(context, tree, parentId, safeName)?.let { oldId ->
                try {
                    DocumentsContract.deleteDocument(
                        context.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree, oldId)
                    )
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
                context.contentResolver, parentDoc, mime, safeName
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
    fun deleteByName(context: Context, name: String, path: String = ""): Result {
        val tree = folderUri()
            ?: return Result(false, "No workspace folder chosen — set one in Settings > Tools.")
        val parentId = folderDocId(context, tree, path)
            ?: return Result(false, "The folder '$path' does not exist.")
        val docId = childDocId(context, tree, parentId, name, foldersOnly = false)
            ?: return Result(false, "No file named '$name' in the workspace folder.")
        return try {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            )
            Result(true, name)
        } catch (e: Exception) {
            Result(false, "Could not delete '$name': ${e.message}")
        }
    }

    /**
     * Create a FOLDER inside the workspace (or inside one of its
     * subfolders).
     *
     * SAF has no "mkdir": a directory is a document whose MIME type is
     * MIME_TYPE_DIR, created with createDocument() — the same call that
     * creates a file, with another MIME.
     */
    fun createFolder(context: Context, name: String, path: String = ""): Result {
        val tree = folderUri()
            ?: return Result(false, "No workspace folder chosen — set one in Settings > Tools.")
        val safeName = name.trim().substringAfterLast('/')
        if (safeName.isBlank()) {
            return Result(false, "The folder name is empty.")
        }
        return try {
            val parentId = folderDocId(context, tree, path)
                ?: return Result(false, "The folder '$path' does not exist.")
            // Already there? Do not create a second one.
            if (childDocId(context, tree, parentId, safeName, foldersOnly = true) != null) {
                return Result(true, safeName)
            }
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val created = DocumentsContract.createDocument(
                context.contentResolver, parentDoc,
                DocumentsContract.Document.MIME_TYPE_DIR, safeName
            ) ?: return Result(false, "Could not create the folder '$safeName'.")
            Result(true, safeName)
        } catch (e: Exception) {
            Result(false, "Could not create the folder '$safeName': ${e.message}")
        }
    }

    /**
     * Create the folder at [path] if it is missing, and return its URI.
     *
     * Used by the backup: the app owns a folder inside the workspace and
     * has to be able to recreate it (the user may delete it).
     */
    fun ensureFolder(context: Context, path: String): Uri? {
        val tree = folderUri() ?: return null
        val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var currentId = DocumentsContract.getTreeDocumentId(tree)
        for (segment in segments) {
            val existing = childDocId(context, tree, currentId, segment, foldersOnly = true)
            val id = existing ?: run {
                // A null result means the provider refused to create it.
                DocumentsContract.createDocument(
                    context.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, currentId),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    segment
                ) ?: return null
                // Look the new folder up by name: the id of the CREATED
                // uri is not dependable across providers.
                childDocId(context, tree, currentId, segment, foldersOnly = true)
                    ?: return null
            }
            currentId = id
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, currentId)
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
