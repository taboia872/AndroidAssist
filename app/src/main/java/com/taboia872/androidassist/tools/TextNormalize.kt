package com.taboia872.androidassist.tools

import java.util.Locale

/**
 * Text helpers shared by the tools.
 *
 * [normalize] strips the accents and lower-cases, so a search made by the
 * model ("musica", "joao") finds what the user actually has on the
 * device ("Música", "João") — the same normalisation the app already
 * used in two different places, now in ONE.
 */
object TextNormalize {

    /**
     * Lower case, WITHOUT accents, trimmed.
     *
     * "Ação" -> "acao";  "JOÃO" -> "joao".
     */
    fun normalize(text: String): String {
        val nfd = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .trim()
    }

    /**
     * Does [needle] start a word of [haystack]?
     *
     * "spot" matches "Spotify", "map" matches "Google Maps" — but "ap"
     * does NOT match "Maps".
     */
    fun wordStartsWith(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        val h = normalize(haystack)
        val n = normalize(needle)
        if (h == n) return true
        var from = 0
        while (true) {
            val at = h.indexOf(n, from)
            if (at < 0) return false
            if (at == 0 || h[at - 1] == ' ' || h[at - 1] == '-' ||
                h[at - 1] == '_' || h[at - 1] == '('
            ) return true
            from = at + 1
        }
    }
}
