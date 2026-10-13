package com.taboia872.androidassist.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Markdown renderer for chat messages.
 * Supports: **bold**, *italic*, `code`, [links](url), ```code blocks```,
 * headers, lists and GFM pipe TABLES.
 *
 * Tables were the "truncated" case before: the old parser had no table
 * awareness, so | pipes flowed through the inline formatter and rows
 * rendered as broken lines. Now a table block is detected, parsed and
 * drawn as a monospaced aligned grid with a proper separator row.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    codeBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    onLinkClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val baseStyle = LocalTextStyle.current.copy(color = color)

    val annotatedString = remember(text, color, linkColor) {
        parseMarkdown(text, baseStyle, linkColor, codeBackground)
    }

    // SelectionContainer: long-press anywhere on the response opens the
    // native text-selection handles (partial or full copy). Single taps
    // still go through to ClickableText for links.
    SelectionContainer {
        @Suppress("DEPRECATION")
        ClickableText(
            text = annotatedString,
            modifier = modifier,
            style = baseStyle,
            onClick = { offset ->
                annotatedString.getStringAnnotations("URL", offset, offset)
                    .firstOrNull()?.let { annotation ->
                        if (onLinkClick != null) {
                            onLinkClick(annotation.item)
                        } else {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Ignore invalid URLs
                            }
                        }
                    }
            }
        )
    }
}

/** A GFM table: header row, |---|---| separator, then body rows. */
private class MarkdownTable(
    private val header: List<String>,
    private val rows: List<List<String>>
) {
    companion object {
        fun parseIfTable(lines: List<String>, startIndex: Int): MarkdownTable? {
            val headerLine = lines.getOrNull(startIndex) ?: return null
            val sepLine = lines.getOrNull(startIndex + 1) ?: return null
            if (!headerLine.trim().startsWith("|")) return null
            if (!sepLine.trim().matches(
                    Regex("^\\|?[\\s:-]*-{2,}[\\s:-]*(\\|[\\s:-]*-{2,}[\\s:-]*)*\\|?$")
                )
            ) return null
            val header = splitRow(headerLine)
            if (header.isEmpty()) return null
            val rows = mutableListOf<List<String>>()
            var i = startIndex + 2
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                val row = splitRow(lines[i])
                if (row.isNotEmpty()) rows.add(row)
                i++
            }
            return MarkdownTable(header, rows)
        }

        fun tableEnd(startIndex: Int, table: MarkdownTable): Int =
            startIndex + 2 + table.rows.size

        private fun splitRow(line: String): List<String> =
            line.trim().trim('|').split('|').map { it.trim() }
    }

    /** Render as a monospace, column-aligned grid string. */
    fun render(): String {
        val columns = header.size
        val widths = IntArray(columns) { c ->
            maxOf(
                header.getOrElse(c) { "" }.length,
                rows.maxOfOrNull { it.getOrElse(c) { "" }.length } ?: 0
            )
        }
        fun pad(cells: List<String>): String =
            (0 until columns).joinToString(" | ") { c ->
                cells.getOrElse(c) { "" }.padEnd(widths[c])
            }
        val sb = StringBuilder()
        sb.append(pad(header)).append('\n')
        sb.append((0 until columns).joinToString("|") { "-".repeat(widths[it] + 2) }).append('\n')
        rows.forEach { sb.append(pad(it)).append('\n') }
        return sb.toString().trimEnd('\n')
    }
}

private fun parseMarkdown(
    text: String,
    baseStyle: TextStyle,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        val monoStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)

        var i = 0
        while (i < lines.size) {
            if (i > 0) append("\n")

            // ---- GFM table block ----
            val table = MarkdownTable.parseIfTable(lines, i)
            if (table != null) {
                withStyle(monoStyle) {
                    append(table.render())
                }
                i = MarkdownTable.tableEnd(i, table)
                continue
            }

            val line = lines[i]
            i++

            // ---- fenced code block (``` ... ```) spanning lines ----
            if (line.trimStart().startsWith("```")) {
                val lang = line.trim().removePrefix("```").trim()
                if (lang.isNotEmpty()) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(lang) }
                    append("\n")
                }
                withStyle(monoStyle) {
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        append(lines[i])
                        append("\n")
                        i++
                    }
                }
                if (i < lines.size) i++ // skip the closing ``` line
                continue
            }

            // ---- headers ----
            val headerMatch = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val content = headerMatch.groupValues[2]
                val headerStyle = when (level) {
                    1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.5f)
                    2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.3f)
                    3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.1f)
                    else -> SpanStyle(fontWeight = FontWeight.Bold)
                }
                withStyle(headerStyle) {
                    appendInline(content, linkColor, codeBackground)
                }
                continue
            }

            // ---- list items ----
            val listMatch = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
            if (listMatch != null) {
                append("• ")
                appendInline(listMatch.groupValues[1], linkColor, codeBackground)
                continue
            }

            // ---- numbered list ----
            val numberedMatch = Regex("^\\s*(\\d+)\\.\\s+(.+)$").find(line)
            if (numberedMatch != null) {
                append("${numberedMatch.groupValues[1]}. ")
                appendInline(numberedMatch.groupValues[2], linkColor, codeBackground)
                continue
            }

            appendInline(line, linkColor, codeBackground)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(
    text: String,
    linkColor: Color,
    codeBackground: Color
) {
    var i = 0
    while (i < text.length) {
        // Inline code (`)
        if (text[i] == '`') {
            val endIndex = text.indexOf('`', i + 1)
            if (endIndex != -1) {
                val code = text.substring(i + 1, endIndex)
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground
                )) {
                    append(code)
                }
                i = endIndex + 1
                continue
            }
        }

        // Links [text](url)
        if (text[i] == '[') {
            val linkMatch = Regex("^\\[([^]]+)]\\(([^)]+)\\)").find(text.substring(i))
            if (linkMatch != null) {
                val linkText = linkMatch.groupValues[1]
                val url = linkMatch.groupValues[2]
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )) {
                    append(linkText)
                }
                pop()
                i += linkMatch.value.length
                continue
            }
        }

        // Auto-detect URLs
        val urlMatch = Regex("^https?://[^\\s]+").find(text.substring(i))
        if (urlMatch != null && i < text.length) {
            val url = urlMatch.value
            pushStringAnnotation("URL", url)
            withStyle(SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            )) {
                append(url)
            }
            pop()
            i += url.length
            continue
        }

        // Bold (**text** or __text__)
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val marker = text.substring(i, i + 2)
            val endIndex = text.indexOf(marker, i + 2)
            if (endIndex != -1) {
                val boldText = text.substring(i + 2, endIndex)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldText)
                }
                i = endIndex + 2
                continue
            }
        }

        // Italic (*text* or _text_) - must check after bold
        if ((text[i] == '*' || text[i] == '_') &&
            (i + 1 < text.length && text[i + 1] != text[i])
        ) {
            val marker = text[i]
            val endIndex = text.indexOf(marker, i + 1)
            if (endIndex != -1 && endIndex > i + 1) {
                val italicText = text.substring(i + 1, endIndex)
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(italicText)
                }
                i = endIndex + 1
                continue
            }
        }

        // Strikethrough (~~text~~)
        if (text.startsWith("~~", i)) {
            val endIndex = text.indexOf("~~", i + 2)
            if (endIndex != -1) {
                val strikeText = text.substring(i + 2, endIndex)
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(strikeText)
                }
                i = endIndex + 2
                continue
            }
        }

        // Regular character
        append(text[i])
        i++
    }
}
