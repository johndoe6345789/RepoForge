package com.repoforge.ui.common

import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Renders markdown. [resolveLink] maps relative links (e.g. `docs/setup.md`) to absolute URLs;
 * links it can't resolve show a message instead of crashing the app.
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    resolveLink: (String) -> String? = { null },
) {
    val context = LocalContext.current
    val handler = remember(resolveLink) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val target = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(uri)) uri else resolveLink(uri)
                if (target != null) {
                    openUrl(context, target)
                } else {
                    Toast.makeText(context, "Can't open $uri", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        val type = MaterialTheme.typography
        Markdown(
            content = markdown,
            modifier = modifier,
            // The library maps headings to display styles, which are far too large inside cards.
            typography = markdownTypography(
                h1 = type.headlineSmall,
                h2 = type.titleLarge,
                h3 = type.titleMedium,
                h4 = type.titleSmall,
                h5 = type.labelLarge,
                h6 = type.labelMedium,
            ),
            imageTransformer = Coil3ImageTransformerImpl,
        )
    }
}
