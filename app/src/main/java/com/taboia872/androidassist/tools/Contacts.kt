package com.taboia872.androidassist.tools

import android.content.Context
import android.provider.ContactsContract

/**
 * Read the user's contacts to turn a NAME into a phone number.
 *
 * The permission (READ_CONTACTS) is requested by the user explicitly in
 * Settings > Tools — this is a "sensitive" permission, so the app never
 * asks for it on its own.
 *
 * The app only READS the name/number and hands it to the messaging app; the
 * user still confirms the message before it is sent (the platform requires
 * it — no app may send in the user's name without the final tap).
 */
object Contacts {

    /** A contact hit: display name + the best phone number found. */
    data class Hit(val name: String, val phone: String)

    /**
     * Find contacts whose name matches [query].
     *
     * The match is "contains, case and accent insensitive" — the model
     * usually passes a partial name ("joao" for "João Silva").
     */
    fun search(context: Context, query: String): List<Hit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val needle = normalize(q)
        if (needle.isBlank()) return emptyList()
        val out = mutableListOf<Hit>()
        val seen = mutableSetOf<String>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null
            )?.use { c ->
                val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                // Start at the FIRST row: a bare moveToNext() skips it.
                if (!c.moveToFirst()) return@use emptyList()
                do {
                    if (out.size >= 20) break
                    val name = if (nameCol >= 0) c.getString(nameCol) else null
                    val number = if (numCol >= 0) c.getString(numCol) else null
                    if (name.isNullOrBlank() || number.isNullOrBlank()) continue
                    if (!normalize(name).contains(needle)) continue
                    // One hit per person (the first number is enough).
                    val key = normalize(name)
                    if (!seen.add(key)) continue
                    out.add(Hit(name, number.trim()))
                } while (c.moveToNext())
            }
        } catch (e: SecurityException) {
            // Permission revoked in the system settings behind our back.
            return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    /** Strip accents and lowercase, so "joao" matches "João". */
    private fun normalize(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(java.util.Locale.ROOT)
            .trim()
    }

    /**
     * The number in the dial format a messaging app expects: only digits and
     * a leading '+' (WhatsApp rejects spaces, dashes and parentheses).
     */
    fun toDialFormat(raw: String): String {
        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (plus) "+$digits" else digits
    }
}
