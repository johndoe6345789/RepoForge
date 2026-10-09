package com.repoforge.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.repoforge.data.forge.Diffs
import com.repoforge.data.model.ChangeType
import com.repoforge.data.model.FileDiff
import com.repoforge.ui.theme.StateColors
import com.repoforge.ui.theme.diffColors

/** Diffs with more changed lines than this start collapsed, so huge changes don't swamp the screen. */
private const val AUTO_EXPAND_LINES = 400

/** Whether a file starts expanded: everything when the change is small, otherwise nothing. */
fun defaultExpanded(files: List<FileDiff>): Boolean = files.sumOf { it.additions + it.deletions } <= AUTO_EXPAND_LINES * 3

/** "3 files changed, +12 −4" summary row. */
@Composable
fun DiffSummary(files: List<FileDiff>, modifier: Modifier = Modifier) {
    val added = files.sumOf { it.additions }
    val removed = files.sumOf { it.deletions }
    Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${files.size} ${if (files.size == 1) "file" else "files"} changed",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text("+$added", color = StateColors.open, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        Text(" −$removed", color = StateColors.closed, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
    }
}

/** One collapsible section per file: a header with the path and counts, then its diff lines. */
fun LazyListScope.diffFiles(files: List<FileDiff>, expanded: SnapshotStateMap<String, Boolean>, expandByDefault: Boolean) {
    files.forEachIndexed { index, file ->
        val key = "$index:${file.path}"
        val open = expanded[key] ?: (expandByDefault && file.additions + file.deletions <= AUTO_EXPAND_LINES)
        item(key = "file:$key") {
            DiffFileHeader(file, open) { expanded[key] = !open }
        }
        if (open) {
            val patch = file.patch
            if (patch == null) {
                item(key = "nopatch:$key") {
                    Text(
                        if (file.change == ChangeType.RENAMED && file.additions + file.deletions == 0) "File renamed without changes"
                        else "Binary file or diff too large to show",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else {
                item(key = "lines:$key") { DiffLines(patch) }
            }
        }
        item(key = "divider:$key") { HorizontalDivider() }
    }
}

@Composable
private fun DiffFileHeader(file: FileDiff, open: Boolean, onToggle: () -> Unit) {
    val (label, color) = when (file.change) {
        ChangeType.ADDED -> "A" to StateColors.open
        ChangeType.DELETED -> "D" to StateColors.closed
        ChangeType.RENAMED -> "R" to StateColors.merged
        ChangeType.MODIFIED -> "M" to Color(0xFFBF8700)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
        Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                file.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val dir = file.path.substringBeforeLast('/', "")
            val subtitle = file.oldPath?.let { "from $it" } ?: dir
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (file.additions > 0) Text("+${file.additions}", color = StateColors.open, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        if (file.deletions > 0) Text("−${file.deletions}", color = StateColors.closed, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DiffLines(patch: String) {
    val lines = remember(patch) { Diffs.lines(patch) }
    val colors = diffColors()
    val scroll: ScrollState = rememberScrollState()
    val maxNumber = lines.maxOfOrNull { maxOf(it.oldNumber ?: 0, it.newNumber ?: 0) } ?: 0
    val gutter = (maxNumber.toString().length * 7 + 8).dp
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // One horizontally scrolling column per file keeps long lines aligned and the gutters fixed.
    Row(Modifier.fillMaxWidth()) {
        Column {
            lines.forEach { line ->
                val background = when (line.type) {
                    Diffs.LineType.ADDED -> colors.added
                    Diffs.LineType.REMOVED -> colors.removed
                    Diffs.LineType.HUNK -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    else -> Color.Transparent
                }
                Row(Modifier.background(background)) {
                    Text(line.oldNumber?.toString().orEmpty(), style = style, color = muted.copy(alpha = 0.7f), textAlign = TextAlign.End, modifier = Modifier.width(gutter).padding(end = 4.dp))
                    Text(line.newNumber?.toString().orEmpty(), style = style, color = muted.copy(alpha = 0.7f), textAlign = TextAlign.End, modifier = Modifier.width(gutter).padding(end = 6.dp))
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewport = maxWidth
            // As wide as the longest line or the screen, whichever is larger, so tints span the row.
            Column(Modifier.horizontalScroll(scroll).widthIn(min = viewport).width(IntrinsicSize.Max)) {
            lines.forEach { line ->
                val (background, prefix, color) = when (line.type) {
                    Diffs.LineType.ADDED -> Triple(colors.added, "+", colors.addedText)
                    Diffs.LineType.REMOVED -> Triple(colors.removed, "−", colors.removedText)
                    Diffs.LineType.HUNK -> Triple(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), "", muted)
                    Diffs.LineType.NOTE -> Triple(Color.Transparent, "", muted)
                    Diffs.LineType.CONTEXT -> Triple(Color.Transparent, " ", MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    prefix + line.text.replace("\t", "    "),
                    style = style,
                    color = if (line.type == Diffs.LineType.ADDED || line.type == Diffs.LineType.REMOVED) MaterialTheme.colorScheme.onSurface else color,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().background(background).padding(end = 16.dp),
                )
            }
            }
        }
    }
}
