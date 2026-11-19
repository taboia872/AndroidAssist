package com.taboia872.androidassist.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
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
import androidx.compose.ui.Alignment
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.taboia872.androidassist.i18n.Strings
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl

/**
 * FINISHED-message markdown: multiplatform-markdown-renderer (JetBrains
 * parser — remark-class correctness, like the llama.cpp chat): real GFM
 * tables, fenced code with COPY button, lists, headers. Darker block
 * backgrounds; table/code tint derived from onSurface for contrast.
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
    val linkHandler = remember { ReferenceLinkHandlerImpl() }
    // Darker block tint than the bubble surface so tables/code stand out
    val blockBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Markdown(
        content = text,
        components = markdownComponents(
            codeFence = { state: MarkdownComponentModel ->
                CopyableCodeFence(state.content, state.node)
            },
            // The library gives every column the same width and cuts
            // the text with an ellipsis; the tables of the app follow
            // the content of each column.
            table = { state: MarkdownComponentModel ->
                MarkdownTableFromNode(
                    state.content,
                    state.node,
                    LocalTextStyle.current.copy(color = color),
                    linkColor,
                    blockBg
                )
            }
        ),
        colors = markdownColor(
            text = color,
            codeBackground = blockBg,
            inlineCodeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            tableBackground = blockBg
        ),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.titleLarge,
            h2 = MaterialTheme.typography.titleLarge,
            h3 = MaterialTheme.typography.titleMedium,
            h4 = MaterialTheme.typography.titleMedium,
            h5 = MaterialTheme.typography.titleSmall,
            h6 = MaterialTheme.typography.titleSmall,
            text = MaterialTheme.typography.bodyLarge,
            paragraph = MaterialTheme.typography.bodyLarge
        ),
        modifier = modifier.fillMaxWidth(),
        referenceLinkHandler = linkHandler
    )
}

/** Code fence with a COPY button pinned top-right (user request). */
@Composable
private fun CopyableCodeFence(content: String, node: ASTNode) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val blockBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val style = LocalMarkdownTypography.current.code

    Box(modifier = Modifier.fillMaxWidth()) {
        MarkdownCodeFence(
            content = content,
            node = node,
            style = style,
            block = { code, language, textStyle ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = blockBg
                ) {
                    Text(
                        code,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp),
                        style = textStyle
                    )
                }
            }
        )
        IconButton(
            onClick = {
                clipboard.setText(AnnotatedString(extractFenceCode(content, node)))
                Toast.makeText(context, Strings.t(Strings.copied), Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = Strings.t(Strings.copyCode),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Extract only the code lines from the fence node span. */
private fun extractFenceCode(content: String, node: ASTNode): String {
    val span = node.getTextInNode(content).toString()
    val lines = span.split("\n")
    val body = if (lines.size > 2) lines.drop(1).dropLast(1) else lines.drop(1)
    return body.joinToString("\n").trimEnd()
}

@Composable
fun MarkdownTextStreaming(
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
            if (block.tableRows.isNotEmpty()) {
                TableBlock(block.tableRows, baseStyle, linkColor, codeBackground)
            } else if (block.code) {
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

/**
 * The column weights, from the CONTENT.
 *
 * The library (and the first version of this block) gave every column
 * the SAME width: a 2-digit track number took a third of the screen
 * while the title — the column the user actually reads — was squeezed
 * and truncated. The width now follows the longest cell of the column.
 */
private fun columnWeights(rows: List<List<String>>): List<Float> {
    if (rows.isEmpty()) return emptyList()
    val columns = rows.maxOf { it.size }
    val weights = ArrayList<Float>(columns)
    for (c in 0 until columns) {
        var maxLen = 0
        for (row in rows) {
            val cell = row.getOrNull(c)?.trim() ?: ""
            if (cell.length > maxLen) maxLen = cell.length
        }
        // Narrow columns stay narrow, but never narrower than a number
        // plus a padding; long ones are capped so one verbose column
        // does not eat the whole table.
        weights.add(maxLen.coerceIn(3, 40).toFloat())
    }
    return weights
}

/** GFM table -> real grid: bold header + divider, columns by content. */
@Composable
private fun TableBlock(
    rows: List<List<String>>,
    baseStyle: TextStyle,
    linkColor: Color,
    codeBackground: Color
) {
    val header = rows.first()
    val body = rows.drop(1)
    val colCount = header.size
    val weights = remember(rows) { columnWeights(rows) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        shape = RoundedCornerShape(10.dp),
        color = codeBackground.copy(alpha = 0.6f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // header
            Row {
                header.forEachIndexed { idx, cell ->
                    Text(
                        inlineFormatted(cell.trim(), baseStyle, linkColor, codeBackground),
                        style = baseStyle.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier
                            .weight(weights.getOrElse(idx) { 1f })
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
            // body
            body.forEach { row ->
                Row {
                    row.forEachIndexed { idx, cell ->
                        Text(
                            inlineFormatted(cell.trim(), baseStyle, linkColor, codeBackground),
                            style = baseStyle,
                            modifier = Modifier
                                .weight(weights.getOrElse(idx) { 1f })
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The table of the FINISHED message (the library parses it): the same
 * grid of the streaming renderer, with the columns by content.
 *
 * The raw markdown of the node is reused (the library hands over the
 * whole document plus the table node) — no need to walk the GFM AST.
 */
@Composable
private fun MarkdownTableFromNode(
    content: String,
    node: ASTNode,
    baseStyle: TextStyle,
    linkColor: Color,
    codeBackground: Color
) {
    val rows = remember(node, content) {
        node.getTextInNode(content).toString()
            .split("\n")
            .filter { isTableRow(it) && !isTableSeparator(it) }
            .map { splitRow(it) }
    }
    if (rows.isEmpty()) return
    TableBlock(rows, baseStyle, linkColor, codeBackground)
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
private data class MdBlock(
    val code: Boolean,
    val text: String,
    /** table rows: first = header (raw markdown cells) */
    val tableRows: List<List<String>> = emptyList()
)

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
        // A TABLE: the header row, the separator and the body rows all
        // start with "|". While the model is still writing it, the table
        // is INCOMPLETE (no separator yet, or the last row without its
        // closing pipe) — as prose it wraps into a huge column of text
        // (one cell per line) and only compacts when the table closes.
        // A monospace preview keeps the height stable while it streams.
        if (line.trimStart().startsWith("|")) {
            flushProse()
            val tableLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                tableLines.add(lines[i])
                i++
            }
            val complete = tableLines.size >= 2 && isTableSeparator(tableLines[1])
            if (complete) {
                val rows = tableLines
                    .filter { !isTableSeparator(it) }
                    .map { splitRow(it) }
                out.add(MdBlock(false, "", tableRows = rows))
            } else {
                out.add(MdBlock(true, tableLines.joinToString("\n")))
            }
            continue
        }
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
        val sep = SpanStyle(color = linkColor.copy(alpha = 0.55f))

        val lines = text.split("\n")
        var i = 0
        var firstBlock = true
        while (i < lines.size) {
            if (!firstBlock) append("\n")

            val line = lines[i]

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

/** Cell text with inline markdown (bold/code/links) applied. */
private fun inlineFormatted(
    text: String,
    baseStyle: TextStyle,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString = buildAnnotatedString {
    appendInline(text, linkColor, codeBackground)
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
