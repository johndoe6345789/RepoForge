package com.repoforge.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.SubcomposeAsyncImage
import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.IssueState
import com.repoforge.ui.theme.StateColors
import java.time.Instant

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Shows a [Loadable]'s loading and error states, and [content] once it has a value. */
@Composable
fun <T> LoadableContent(loadable: Loadable<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    LaunchedEffect(loadable) { loadable.ensureLoaded() }
    val value = loadable.value
    when {
        value != null -> content(value)
        loadable.error != null -> ErrorState(loadable.error!!, onRetry = loadable::refresh, modifier = modifier)
        else -> LoadingState(modifier)
    }
}

/** Footer for a [Paged] list: loads the next page when it becomes visible. */
fun <T> LazyListScope.pagedFooter(paged: Paged<T>, emptyMessage: String) {
    item(key = "paged-footer") {
        when {
            paged.error != null -> ErrorState(paged.error!!, onRetry = paged::retry)
            paged.isEmptyAndDone -> EmptyState(emptyMessage)
            !paged.endReached -> {
                LaunchedEffect(paged.items.size) { paged.loadMore() }
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
fun Avatar(url: String?, name: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name?.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = if (size < 32.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
            )
        }
    }
    if (url.isNullOrBlank()) {
        Box(modifier) { fallback() }
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
            loading = { fallback() },
            error = { fallback() },
        )
    }
}

/** A small coloured tag naming the provider, so accounts on different hosts are easy to tell apart. */
@Composable
fun ProviderBadge(type: ForgeType, modifier: Modifier = Modifier) {
    val color = when (type) {
        ForgeType.GITHUB -> Color(0xFF24292F)
        ForgeType.GITLAB -> Color(0xFFE24329)
        ForgeType.BITBUCKET -> Color(0xFF0052CC)
        ForgeType.GITEA -> Color(0xFF609926)
    }
    Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(4.dp), modifier = modifier) {
        Text(
            type.displayName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun StateChip(state: IssueState, isDraft: Boolean, modifier: Modifier = Modifier) {
    val (label, color) = when {
        isDraft && state == IssueState.OPEN -> "Draft" to StateColors.draft
        state == IssueState.OPEN -> "Open" to StateColors.open
        state == IssueState.MERGED -> "Merged" to StateColors.merged
        else -> "Closed" to StateColors.closed
    }
    Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun LabelChip(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun MetaRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

val ListContentPadding = PaddingValues(bottom = 96.dp)

fun relativeTime(instant: Instant?): String =
    instant?.let {
        DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }.orEmpty()

fun openUrl(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())
    } catch (_: Exception) {
        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}

fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, null))
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}
