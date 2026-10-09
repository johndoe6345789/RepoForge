package com.repoforge.ui.local

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.repoforge.R
import com.repoforge.data.git.ChangeKind
import com.repoforge.data.git.LocalClone
import com.repoforge.data.git.LocalStatus
import com.repoforge.ui.common.EmptyState
import com.repoforge.ui.common.ErrorState
import com.repoforge.ui.common.ListContentPadding
import com.repoforge.ui.common.LoadingState
import com.repoforge.ui.common.OutlineBadge
import com.repoforge.ui.common.SectionHeader
import com.repoforge.ui.common.copyToClipboard
import com.repoforge.ui.common.relativeTime
import com.repoforge.ui.repo.formatSize
import com.repoforge.ui.theme.StateColors
import java.time.Instant

private val CompactPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/** Repositories cloned onto this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalReposScreen(clones: List<LocalClone>, onBack: () -> Unit, onOpen: (LocalClone) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("On this device") },
            )
        },
    ) { padding ->
        if (clones.isEmpty()) {
            EmptyState(
                "Nothing cloned yet. Open a repository and choose Clone to device to work on it here.",
                Modifier.padding(padding),
                icon = R.drawable.ic_phone,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ListContentPadding) {
                items(clones.sortedByDescending { it.clonedAt }, key = { it.id }) { clone ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(clone) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(clone.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                "Cloned ${relativeTime(Instant.ofEpochMilli(clone.clonedAt))} · ${clone.path}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** A clone's working copy: changes to commit, sync with the server, and its files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalRepoScreen(
    model: LocalRepoModel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var branchPicker by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val status = model.status.value

    LaunchedEffect(model) { model.status.ensureLoaded() }
    LaunchedEffect(model.notice) {
        model.notice?.let { snackbar.showSnackbar(it); model.notice = null }
    }
    BackHandler(enabled = model.tab == LocalTab.FILES && model.path.isNotEmpty()) { model.up() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(model.clone.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("On this device", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = model::refresh, enabled = model.busy == null) { Icon(Icons.Filled.Refresh, "Refresh") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy folder path") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), null) },
                                onClick = { menuOpen = false; copyToClipboard(context, "Folder", model.clone.path) },
                            )
                            DropdownMenuItem(
                                text = { Text("Discard all changes") },
                                enabled = status?.isClean == false && model.busy == null,
                                onClick = { menuOpen = false; confirmDiscard = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete from device") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                enabled = model.busy == null,
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SyncBar(model, status, onPickBranch = { branchPicker = true; model.branches.refresh() })
            PrimaryTabRow(selectedTabIndex = model.tab.ordinal) {
                LocalTab.entries.forEach { tab ->
                    Tab(
                        selected = model.tab == tab,
                        onClick = { model.tab = tab },
                        text = {
                            Text(
                                when (tab) {
                                    LocalTab.CHANGES -> status?.changes?.size?.takeIf { it > 0 }?.let { "Changes ($it)" } ?: "Changes"
                                    LocalTab.FILES -> "Files"
                                }
                            )
                        },
                    )
                }
            }
            when (model.tab) {
                LocalTab.CHANGES -> ChangesTab(model)
                LocalTab.FILES -> FilesTab(model, onOpenFile)
            }
        }
    }

    if (branchPicker) {
        BranchDialog(model, current = status?.branch, onDismiss = { branchPicker = false })
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard all changes?") },
            text = { Text("Edited files go back to the last commit and new files are deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; model.discardChanges() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this clone?") },
            text = {
                Text(
                    "The folder ${model.clone.path} is removed from your phone. Commits you haven't pushed are lost. " +
                        "The repository on the server isn't affected."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SyncBar(model: LocalRepoModel, status: LocalStatus?, onPickBranch: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The buttons keep their size; the branch name gets the rest and ellipsizes.
            Box(Modifier.weight(1f)) {
                AssistChip(
                    onClick = onPickBranch,
                    enabled = model.busy == null,
                    label = { Text(status?.branch ?: "…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_branch), null, Modifier.size(18.dp)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
            }
            OutlinedButton(onClick = model::pull, enabled = model.busy == null, contentPadding = CompactPadding) {
                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                Text(if ((status?.behind ?: 0) > 0) " Pull ${status!!.behind}" else " Pull", maxLines = 1)
            }
            FilledTonalButton(onClick = model::push, enabled = model.busy == null, contentPadding = CompactPadding) {
                Icon(painterResource(R.drawable.ic_upload), null, Modifier.size(18.dp))
                Text(if ((status?.ahead ?: 0) > 0) " Push ${status!!.ahead}" else " Push", maxLines = 1)
            }
        }
        model.busy?.let { label ->
            val progress = model.progress
            Text(
                listOfNotNull(label, progress?.task).joinToString(" · ") +
                    (progress?.takeIf { it.total > 0 }?.let { " ${it.done}/${it.total}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            val fraction = progress?.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangesTab(model: LocalRepoModel) {
    val loadable = model.status
    val status = loadable.value
    when {
        status == null && loadable.error != null -> ErrorState(loadable.error!!, onRetry = loadable::refresh)
        status == null -> LoadingState()
        else -> PullToRefreshBox(isRefreshing = loadable.loading, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
                if (status.isClean) {
                    item("clean") {
                        Column(
                            Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(40.dp), tint = StateColors.open)
                            Text("Nothing to commit", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    status.ahead > 0 -> "${status.ahead} commit${if (status.ahead == 1) "" else "s"} waiting to be pushed."
                                    status.behind > 0 -> "The server has ${status.behind} new commit${if (status.behind == 1) "" else "s"}. Pull to get them."
                                    else -> "Edit files from the Files tab and commit them here."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    item("commit") { CommitCard(model, status) }
                    item("header") { SectionHeader("${status.changes.size} changed file${if (status.changes.size == 1) "" else "s"}") }
                    items(status.changes, key = { it.path }) { change ->
                        val (letter, color) = when (change.kind) {
                            ChangeKind.ADDED -> "A" to StateColors.open
                            ChangeKind.UNTRACKED -> "N" to StateColors.open
                            ChangeKind.MODIFIED -> "M" to Color(0xFFBF8700)
                            ChangeKind.DELETED -> "D" to StateColors.closed
                            ChangeKind.CONFLICTING -> "C" to MaterialTheme.colorScheme.error
                        }
                        ListItem(
                            leadingContent = { OutlineBadge(letter, color = color) },
                            headlineContent = {
                                Text(change.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = change.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                                { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitCard(model: LocalRepoModel, status: LocalStatus) {
    val conflicted = status.changes.any { it.kind == ChangeKind.CONFLICTING }
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (conflicted) {
                Text(
                    "Some files have merge conflicts. Edit them to remove the conflict markers, then commit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = model.commitMessage,
                onValueChange = { model.commitMessage = it },
                label = { Text("Commit message") },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = model::commit, enabled = model.busy == null && model.commitMessage.isNotBlank()) {
                    Text("Commit ${status.changes.size} file${if (status.changes.size == 1) "" else "s"}")
                }
                if (model.busy == "Committing") CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Text(
                "Every change is included. Push afterwards to send the commit to the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilesTab(model: LocalRepoModel, onOpenFile: (String) -> Unit) {
    val path = model.path
    val entries by produceState<List<LocalEntry>?>(null, path, model.treeVersion) { value = model.list(path) }
    Column(Modifier.fillMaxSize()) {
        if (path.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                TextButton(onClick = { model.open("") }) { Text(model.clone.name) }
                val parts = path.split('/')
                parts.forEachIndexed { index, part ->
                    Text("/", Modifier.align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.outline)
                    TextButton(onClick = { model.open(parts.take(index + 1).joinToString("/")) }, enabled = index != parts.lastIndex) {
                        Text(part, fontWeight = if (index == parts.lastIndex) FontWeight.SemiBold else null)
                    }
                }
            }
            HorizontalDivider()
        }
        val list = entries
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState("This folder is empty", icon = R.drawable.ic_folder)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
                items(list, key = { it.path }) { entry ->
                    ListItem(
                        modifier = Modifier.clickable { if (entry.isDir) model.open(entry.path) else onOpenFile(entry.path) },
                        leadingContent = {
                            Icon(
                                painterResource(if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_file),
                                null,
                                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = if (entry.isDir) null else ({ Text(formatSize(entry.size), style = MaterialTheme.typography.labelSmall) }),
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchDialog(model: LocalRepoModel, current: String?, onDismiss: () -> Unit) {
    val branches = model.branches
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch branch") },
        text = {
            val list = branches.value
            when {
                list == null && branches.error != null -> Text(branches.error!!, color = MaterialTheme.colorScheme.error)
                list == null -> Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list) { branch ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = branch != current) { onDismiss(); model.checkout(branch) },
                            leadingContent = { Icon(painterResource(R.drawable.ic_branch), null) },
                            headlineContent = {
                                Text(branch, fontFamily = FontFamily.Monospace, fontWeight = if (branch == current) FontWeight.SemiBold else null)
                            },
                            trailingContent = if (branch == current) ({ Icon(Icons.Filled.CheckCircle, "Current", tint = StateColors.open) }) else null,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Progress and result of cloning a repository onto the device. */
@Composable
fun CloneDialog(task: CloneTask, onDismiss: () -> Unit, onOpen: (LocalClone) -> Unit) {
    val result = task.result
    val error = task.error
    AlertDialog(
        onDismissRequest = { if (!task.running) onDismiss() },
        icon = { Icon(painterResource(R.drawable.ic_download), null) },
        title = {
            Text(
                when {
                    result != null -> "Cloned ${task.repo.name}"
                    error != null -> "Clone failed"
                    else -> "Cloning ${task.repo.name}"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    result != null -> Text("Saved to ${result.path}. Open it to browse files, commit, pull and push.")
                    error != null -> Text(error)
                    else -> {
                        val progress = task.progress
                        Text(
                            progress?.let { p -> p.task + (if (p.total > 0) " · ${p.done}/${p.total}" else "") } ?: "Connecting…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val fraction = progress?.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(
                            "Into ${task.dir.path}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                result != null -> Button(onClick = { onOpen(result) }) { Text("Open") }
                error != null -> TextButton(onClick = onDismiss) { Text("Close") }
                else -> TextButton(onClick = { task.cancel(); onDismiss() }) { Text("Cancel") }
            }
        },
        dismissButton = if (result != null) ({ TextButton(onClick = onDismiss) { Text("Close") } }) else null,
    )
}
