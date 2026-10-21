package com.taboia872.androidassist.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl

/**
 * Markdown rendering via multiplatform-markdown-renderer (JetBrains markdown
 * parser — the remark/rehype class of correctness the llama.cpp chat UI
 * uses): real GFM tables, fenced code blocks, lists, headers, links.
 *
 * Link color follows the Material primary (accent-aware via the app theme);
 * the library opens links through its own handler.
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

    Markdown(
        content = text,
        colors = markdownColor(
            text = color,
            codeBackground = codeBackground,
            inlineCodeBackground = codeBackground,
            dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
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
