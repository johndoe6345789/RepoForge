package com.repoforge.ui.conflicts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.repoforge.data.git.ConflictKind
import com.repoforge.data.git.ConflictMarkers
import com.repoforge.ui.common.EmptyState
import com.repoforge.ui.common.ErrorState
import com.repoforge.ui.common.OutlineBadge
import com.repoforge.ui.theme.StateColors
import com.repoforge.ui.theme.diffColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(model: ConflictModel, onBack: () -> Unit, onOpenSettings: () -> Unit, onDone: () -> Unit) {
    val detail = model.detail
    var editing by remember { mutableStateOf<FileState?>(null) }
    val leave = { model.discard(); onBack() }
    BackHandler(enabled = model.phase != Phase.PUSHING) { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = leave, enabled = model.phase != Phase.PUSHING) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text("Resolve conflicts")
                        Text(
                            "${detail.baseBranch} → ${detail.headBranch}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (model.phase == Phase.CONFLICTS || model.phase == Phase.CLEAN || model.phase == Phase.PUSHING) {
                PushBar(model)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (model.phase) {
                Phase.PREPARING -> Working("Merging ${detail.baseBranch} into ${detail.headBranch}", model)
                Phase.FAILED -> ErrorState(model.error ?: "Something went wrong", onRetry = model::prepare)
                Phase.UP_TO_DATE -> EmptyState("${detail.headBranch} already contains everything in ${detail.baseBranch}. Refresh the pull request; the service may still be updating.")
                Phase.DONE -> Done(model, onDone)
                Phase.CLEAN -> EmptyState(
                    "${detail.baseBranch} merges into ${detail.headBranch} without conflicts on the device. " +
                        "Push the merge to update the pull request.",
                )
                Phase.CONFLICTS, Phase.PUSHING -> ConflictList(model, onOpenSettings, onEdit = { editing = it })
            }
        }
    }

    editing?.let { state ->
        EditorDialog(
            state = state,
            onDismiss = { editing = null },
            onSave = { text -> model.setText(state, text); editing = null },
        )
    }
}

@Composable
private fun Working(title: String, model: ConflictModel) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val progress = model.progress
        val fraction = progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text(
            progress?.task?.takeIf { it.isNotBlank() }?.let { task ->
                if (progress.total > 0) "$task  ${progress.done}/${progress.total}" else task
            } ?: "Fetching the branches",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "The first run downloads the repository; later runs only fetch new commits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Done(model: ConflictModel, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.CheckCircle, null, Modifier.size(56.dp), tint = StateColors.open)
        Text("Conflicts resolved", style = MaterialTheme.typography.titleLarge)
        Text(
            "Pushed ${model.pushedCommit?.take(7)} to ${model.detail.headBranch}. The pull request can be merged " +
                "once the service has re-checked it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDone) { Text("Back to the pull request") }
    }
}

@Composable
private fun ConflictList(model: ConflictModel, onOpenSettings: () -> Unit, onEdit: (FileState) -> Unit) {
    val resolved = model.files.count { it.isResolved }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item(key = "summary") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${model.files.size} ${if (model.files.size == 1) "file has" else "files have"} conflicts · $resolved resolved",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (model.hasAi) {
                    val anyAi = model.files.any { it.canUseAi && !it.isResolved }
                    FilledTonalButton(onClick = model::resolveAllWithAi, enabled = anyAi && model.phase == Phase.CONFLICTS) {
                        Text("Resolve all with Claude")
                    }
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Add an Anthropic API key in Settings to let Claude propose resolutions.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onOpenSettings) { Text("Settings") }
                        }
                    }
                }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        items(model.files, key = { it.file.path }) { state ->
            FileCard(model, state, onEdit)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileCard(model: ConflictModel, state: FileState, onEdit: (FileState) -> Unit) {
    val file = state.file
    val enabled = model.phase == Phase.CONFLICTS
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StatusBadge(state)
            }
            Text(describe(model, state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (val r = state.resolution) {
                is Resolution.Text -> {
                    r.explanation?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (r.byAi && state.segments != null) HunkReview(model, state, r.hunks)
                    if (hasConflictMarkers(r.content)) {
                        Text("Conflict markers are still in this file.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Resolution.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Claude is working on it…", style = MaterialTheme.typography.bodySmall)
                }
                else -> Unit
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.isResolved) {
                    OutlinedButton(onClick = { model.reset(state) }, enabled = enabled) { Text("Undo") }
                }
                if (state.canUseAi && model.hasAi && !state.isResolved) {
                    Button(onClick = { model.resolveWithAi(state) }, enabled = enabled && state.resolution != Resolution.Working) {
                        Text("Ask Claude")
                    }
                }
                when (file.kind) {
                    ConflictKind.DELETED_IN_HEAD -> {
                        OutlinedButton(onClick = { model.keepSide(state, head = true) }, enabled = enabled) { Text("Keep it deleted") }
                        OutlinedButton(onClick = { model.keepSide(state, head = false) }, enabled = enabled) { Text("Keep ${model.detail.baseBranch}'s file") }
                    }
                    ConflictKind.DELETED_IN_BASE -> {
                        OutlinedButton(onClick = { model.keepSide(state, head = true) }, enabled = enabled) { Text("Keep this branch's file") }
                        OutlinedButton(onClick = { model.keepSide(state, head = false) }, enabled = enabled) { Text("Delete it") }
                    }
                    else -> {
                        OutlinedButton(onClick = { model.keepSide(state, head = true) }, enabled = enabled) { Text("Use ${model.detail.headBranch}") }
                        OutlinedButton(onClick = { model.keepSide(state, head = false) }, enabled = enabled) { Text("Use ${model.detail.baseBranch}") }
                    }
                }
                if (file.kind == ConflictKind.CONTENT) {
                    OutlinedButton(onClick = { onEdit(state) }, enabled = enabled) {
                        Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
                        Text("Edit", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

private fun describe(model: ConflictModel, state: FileState): String {
    val head = model.detail.headBranch
    val base = model.detail.baseBranch
    val hunks = state.segments?.let { ConflictMarkers.hunks(it).size } ?: 0
    return when (state.file.kind) {
        ConflictKind.CONTENT -> "$hunks conflicting ${if (hunks == 1) "change" else "changes"} between $head and $base"
        ConflictKind.DELETED_IN_HEAD -> "Deleted in $head, changed in $base"
        ConflictKind.DELETED_IN_BASE -> "Changed in $head, deleted in $base"
        ConflictKind.BINARY -> "Binary file changed on both branches: pick one version"
    }
}

@Composable
private fun StatusBadge(state: FileState) {
    when (val r = state.resolution) {
        Resolution.Unresolved -> OutlineBadge("Unresolved", color = Color(0xFFBF8700))
        Resolution.Working -> OutlineBadge("Asking Claude")
        is Resolution.Text -> OutlineBadge(if (r.byAi) "Resolved by Claude" else "Edited", color = StateColors.open)
        is Resolution.KeepSide -> OutlineBadge("Resolved", color = StateColors.open)
    }
}

/** Each conflict next to Claude's replacement, so the reviewer sees exactly what changed. */
@Composable
private fun HunkReview(model: ConflictModel, state: FileState, resolutions: List<String>) {
    val hunks = ConflictMarkers.hunks(state.segments ?: return)
    val colors = diffColors()
    hunks.forEachIndexed { i, hunk ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Conflict ${i + 1}", style = MaterialTheme.typography.labelLarge)
            CodeBlock(model.detail.headBranch, hunk.head, colors.removed)
            CodeBlock(model.detail.baseBranch, hunk.base, colors.removed)
            CodeBlock("Resolution", resolutions.getOrElse(i) { "" }, colors.added)
        }
    }
}

@Composable
private fun CodeBlock(label: String, text: String, tint: Color) {
    Surface(color = tint, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text.trimEnd('\n').ifEmpty { "(nothing)" },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                softWrap = false,
            )
        }
    }
}

@Composable
private fun PushBar(model: ConflictModel) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (model.phase == Phase.PUSHING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(model.progress?.task?.takeIf { it.isNotBlank() } ?: "Pushing…", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(
                    value = model.commitMessage,
                    onValueChange = { model.commitMessage = it },
                    label = { Text("Commit message") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                val blocked = model.files.any { (it.resolution as? Resolution.Text)?.content?.let(::hasConflictMarkers) == true }
                Button(
                    onClick = model::commitAndPush,
                    enabled = model.allResolved && !blocked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (model.allResolved) "Commit and push to ${model.detail.headBranch}"
                        else "Resolve ${model.files.count { !it.isResolved }} more to push",
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorDialog(state: FileState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(state) { mutableStateOf(state.editableText) }
    var confirmMarkers by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                    Text(state.file.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (hasConflictMarkers(text)) confirmMarkers = true else onSave(text) }) { Text("Save") }
                }
                Text(
                    "Edit the file so it reads the way it should after the merge, and remove the <<<<<<< ======= >>>>>>> lines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp).heightIn(min = 200.dp)
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
    if (confirmMarkers) {
        AlertDialog(
            onDismissRequest = { confirmMarkers = false },
            icon = { Icon(Icons.Filled.Warning, null) },
            title = { Text("Conflict markers remain") },
            text = { Text("The file still contains conflict markers. Save anyway? You won't be able to push until they're gone.") },
            confirmButton = { TextButton(onClick = { confirmMarkers = false; onSave(text) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirmMarkers = false }) { Text("Keep editing") } },
        )
    }
}
