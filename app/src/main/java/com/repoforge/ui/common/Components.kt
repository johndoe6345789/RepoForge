package com.repoforge.ui.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.SubcomposeAsyncImage
import com.repoforge.R
import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.IssueState
import com.repoforge.data.net.ForgeException
import com.repoforge.ui.theme.StateColors
import java.time.Instant

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val offline = message.startsWith(ForgeException.NETWORK_PREFIX)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (offline) {
            Icon(painterResource(R.drawable.ic_cloud_off), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        } else {
            Icon(Icons.Filled.Warning, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
        }
        Text(
            if (offline) "Can't reach the server" else "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) FilledTonalButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_inbox) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A pulsing grey block standing in for content that is still loading. */
@Composable
fun Placeholder(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp)) {
    val transition = rememberInfiniteTransition(label = "placeholder")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.alpha(alpha).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
}

/** Skeleton rows shaped like list items (avatar or icon, title, subtitle). */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 8, avatar: Boolean = true) {
    Column(modifier.fillMaxWidth()) {
        repeat(rows) { index ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (avatar) {
                    Placeholder(Modifier.size(40.dp), CircleShape)
                } else {
                    Placeholder(Modifier.size(24.dp))
                }
                Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Placeholder(Modifier.height(14.dp).fillMaxWidth(if (index % 3 == 0) 0.5f else 0.7f))
                    if (avatar) Placeholder(Modifier.height(10.dp).fillMaxWidth(if (index % 2 == 0) 0.85f else 0.6f))
                }
            }
        }
    }
}

/** Skeleton lines for a block of text such as a README. */
@Composable
fun SkeletonText(modifier: Modifier = Modifier, lines: Int = 6) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Placeholder(Modifier.height(20.dp).fillMaxWidth(0.5f))
        repeat(lines) { Placeholder(Modifier.height(12.dp).fillMaxWidth(if (it % 3 == 2) 0.6f else 0.95f)) }
    }
}

/** Shows a [Loadable]'s loading and error states, and [content] once it has a value. */
@Composable
fun <T> LoadableContent(
    loadable: Loadable<T>,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = { LoadingState(modifier) },
    content: @Composable (T) -> Unit,
) {
    LaunchedEffect(loadable) { loadable.ensureLoaded() }
    val value = loadable.value
    when {
        value != null -> content(value)
        loadable.error != null -> ErrorState(loadable.error!!, onRetry = loadable::refresh, modifier = modifier)
        else -> placeholder()
    }
}

/**
 * Footer for a [Paged] list: loads the next page when it becomes visible. While the first page
 * loads it shows skeleton rows instead of a spinner.
 */
fun <T> LazyListScope.pagedFooter(paged: Paged<T>, emptyMessage: String, skeletonAvatars: Boolean = true) {
    item(key = "paged-footer") {
        when {
            paged.error != null -> ErrorState(paged.error!!, onRetry = paged::retry)
            paged.isEmptyAndDone -> EmptyState(emptyMessage)
            !paged.endReached -> {
                LaunchedEffect(paged.items.size) { paged.loadMore() }
                if (paged.items.isEmpty()) {
                    SkeletonList(avatar = skeletonAvatars)
                } else {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun Avatar(url: String?, name: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name?.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Medium,
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

fun brandColor(type: ForgeType): Color = when (type) {
    ForgeType.GITHUB -> Color(0xFF24292F)
    ForgeType.GITLAB -> Color(0xFFE24329)
    ForgeType.BITBUCKET -> Color(0xFF0052CC)
    ForgeType.GITEA -> Color(0xFF609926)
}

/** A small coloured tag naming the provider, so accounts on different hosts are easy to tell apart. */
@Composable
fun ProviderBadge(type: ForgeType, modifier: Modifier = Modifier) {
    Surface(color = brandColor(type), contentColor = Color.White, shape = RoundedCornerShape(4.dp), modifier = modifier) {
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

/** An outlined label such as "Private" or "Archived". */
@Composable
fun OutlineBadge(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outline) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** An icon (vector or drawable) followed by a short label, for stats like stars and forks. */
@Composable
fun IconLabel(text: String, icon: ImageVector? = null, @DrawableRes drawable: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        when {
            icon != null -> Icon(icon, null, Modifier.size(14.dp), tint = tint)
            drawable != null -> Icon(painterResource(drawable), null, Modifier.size(14.dp), tint = tint)
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun LanguageLabel(language: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(languageColor(language)))
        Text(
            language,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/** GitHub Linguist's colours for common languages; others get a stable colour from their name. */
fun languageColor(language: String): Color {
    val known = mapOf(
        "kotlin" to 0xFFA97BFF, "java" to 0xFFB07219, "javascript" to 0xFFF1E05A, "typescript" to 0xFF3178C6,
        "python" to 0xFF3572A5, "go" to 0xFF00ADD8, "rust" to 0xFFDEA584, "c" to 0xFF555555, "c++" to 0xFFF34B7D,
        "c#" to 0xFF178600, "swift" to 0xFFF05138, "ruby" to 0xFF701516, "php" to 0xFF4F5D95, "shell" to 0xFF89E051,
        "html" to 0xFFE34C26, "css" to 0xFF563D7C, "scss" to 0xFFC6538C, "dart" to 0xFF00B4AB, "vue" to 0xFF41B883,
        "svelte" to 0xFFFF3E00, "markdown" to 0xFF083FA1, "objective-c" to 0xFF438EFF, "scala" to 0xFFC22D40,
        "elixir" to 0xFF6E4A7E, "haskell" to 0xFF5E5086, "lua" to 0xFF000080, "dockerfile" to 0xFF384D54,
        "nix" to 0xFF7E7EFF, "zig" to 0xFFEC915C, "jupyter notebook" to 0xFFDA5B0B, "clojure" to 0xFFDB5855,
        "perl" to 0xFF0298C3, "r" to 0xFF198CE7, "julia" to 0xFFA270BA, "groovy" to 0xFF4298B8, "hcl" to 0xFF844FBA,
        "makefile" to 0xFF427819, "cmake" to 0xFFDA3434, "erlang" to 0xFFB83998, "ocaml" to 0xFFEF7A08,
        "powershell" to 0xFF012456, "tex" to 0xFF3D6117, "assembly" to 0xFF6E4C13, "vim script" to 0xFF199F4B,
    )
    known[language.lowercase()]?.let { return Color(it) }
    val hue = (language.lowercase().hashCode().toUInt() % 360u).toFloat()
    return Color.hsl(hue, 0.55f, 0.5f)
}

@Composable
fun MetaRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Room below list content so the last row clears floating action buttons and gesture bars. */
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
    // Android 13+ confirms copies itself; a toast on top would be a duplicate.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }
}

fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, null))
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.height(48.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(text)
        }
    }
}

/** A section heading inside a scrolling screen. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}
