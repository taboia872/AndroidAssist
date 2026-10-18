package com.taboia872.androidassist.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp

/**
 * Markdown renderer for CHAT — chat-first with REAL code blocks.
 *
 * The text splits into document blocks: fenced ``` code becomes a proper
 * mono SURFACE with horizontal scroll (no highlight attempts), everything
 * else flows through the inline parser (tables as "a | b | c" lines,
 * thematic breaks as separators, headers scaled, lists bulleted).
 *
 * Text is selectable (long-press) via SelectionContainer.
 */

private data class MdBlock(
    val code: Boolean,
    val text: String
)

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

    val blocks = remember(text) { splitBlocks(text) }

    Column(modifier = modifier) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(modifier = Modifier.height(6.dp))
            if (block.code) {
                CodeBlockSurface(block.text, baseStyle, codeBackground)
            } else {
                val annotatedString = remember(block.text, color, linkColor) {
                    parseMarkdown(block.text, baseStyle, linkColor, codeBackground)
                }
                SelectionContainer {
                    @Suppress("DEPRECATION")
                    ClickableText(
                        text = annotatedString,
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
                                        }
                                    }
                                }
                        }
                    )
                }
            }
        }
    }
}

/** Fenced code -> mono surface with horizontal scroll. */
@Composable
private fun CodeBlockSurface(code: String, baseStyle: TextStyle, codeBackground: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = codeBackground
    ) {
        Column {
            Text(
                code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                style = baseStyle.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

/** Split into code blocks and prose blocks (fences ``` consumed whole). */
private fun splitBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = text.split("\n")
    var i = 0
    val prose = StringBuilder()
    fun flushProse() {
        if (prose.isNotEmpty()) {
            out.add(MdBlock(false, prose.toString().trimEnd()))
            prose.setLength(0)
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```")) {
            flushProse()
            val fenceIndent = line.indexOf("```")
            val lang = line.trim().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++  // skip closing fence
            out.add(MdBlock(true, codeLines.joinToString("\n")))
        } else {
            if (prose.isNotEmpty()) prose.append("\n")
            prose.append(line)
            i++
        }
    }
    flushProse()
    return out
}

/** True when [line] is a GFM table row like " | a | b | ". */
private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.endsWith("|") && t.length > 2
}

/** True when [line] is a table separator like |---|---| or | :---: |. */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|")) return false
    return t.split("|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .all { Regex("^:?-{2,}:?$").matches(it) }
}

/**
 * Document-block markdown parser (prose): tables, thematic breaks, headers,
 * lists then inline formatting per line.
 */
private fun parseMarkdown(
    text: String,
    baseStyle: TextStyle,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString {
    return buildAnnotatedString {
        val mono = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
        val sep = SpanStyle(color = linkColor.copy(alpha = 0.55f))

        val lines = text.split("\n")
        var i = 0
        var firstBlock = true
        while (i < lines.size) {
            if (!firstBlock) append("\n")

            val line = lines[i]

            // ---- GFM table: header + |---| separator + body rows ----
            if (isTableRow(line) && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                val headerCells = splitRow(line)
                i += 2
                val body = mutableListOf<List<String>>()
                while (i < lines.size && isTableRow(lines[i])) {
                    body.add(splitRow(lines[i]))
                    i++
                }
                headerCells.forEachIndexed { idx, cell ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(cell.trim(), linkColor, codeBackground)
                    }
                    if (idx < headerCells.size - 1) append("  |  ")
                }
                append("\n")
                withStyle(sep) { append("─".repeat(24)) }
                body.forEach { row ->
                    append("\n")
                    row.forEachIndexed { idx, cell ->
                        appendInline(cell.trim(), linkColor, codeBackground)
                        if (idx < row.size - 1) append("  |  ")
                    }
                }
                firstBlock = false
                continue
            }

            // ---- thematic break: ---, ***, ___ ----
            if (Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$").matches(line)) {
                withStyle(sep) { append("────────────") }
                i++
                firstBlock = false
                continue
            }

            // ---- headers ----
            val headerMatch = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val headerStyle = when (level) {
                    1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.5f)
                    2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.3f)
                    3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.1f)
                    else -> SpanStyle(fontWeight = FontWeight.Bold)
                }
                withStyle(headerStyle) {
                    appendInline(headerMatch.groupValues[2], linkColor, codeBackground)
                }
                i++
                firstBlock = false
                continue
            }

            // ---- lists / regular line ----
            val listMatch = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
            if (listMatch != null) {
                append("• ")
                appendInline(listMatch.groupValues[1], linkColor, codeBackground)
                i++
                firstBlock = false
                continue
            }
            val numberedMatch = Regex("^\\s*(\\d+)\\.\\s+(.+)$").find(line)
            if (numberedMatch != null) {
                append("${numberedMatch.groupValues[1]}. ")
                appendInline(numberedMatch.groupValues[2], linkColor, codeBackground)
                i++
                firstBlock = false
                continue
            }

            appendInline(line, linkColor, codeBackground)
            i++
            firstBlock = false
        }
    }
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|')

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
