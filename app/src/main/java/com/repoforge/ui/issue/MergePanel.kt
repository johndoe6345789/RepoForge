package com.repoforge.ui.issue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.repoforge.R
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.MergeMethod
import com.repoforge.data.model.Mergeability
import com.repoforge.data.model.PullDetail
import com.repoforge.ui.theme.StateColors

/** Mergeability, the merge button, and branch cleanup for an open or finished pull request. */
@Composable
fun MergePanel(model: IssueModel, onResolveConflicts: (PullDetail) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(model) { model.pull.ensureLoaded() }
    var showDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val detail = model.pull.value
    val issue = model.issue

    Card(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                detail == null && model.pull.error != null -> StatusLine(
                    icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
                    title = "Couldn't check whether this can be merged",
                    note = model.pull.error,
                    onRefresh = model.pull::refresh,
                )
                detail == null -> StatusLine(
                    icon = { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
                    title = "Checking mergeability…",
                    note = null,
                    onRefresh = null,
                )
                issue.state == IssueState.MERGED || issue.state == IssueState.CLOSED -> {
                    StatusLine(
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_pull_request),
                                null,
                                tint = if (issue.state == IssueState.MERGED) StateColors.merged else StateColors.closed,
                            )
                        },
                        title = if (issue.state == IssueState.MERGED) "Merged into ${detail.baseBranch}" else "Closed without merging",
                        note = "You can delete the ${detail.headBranch} branch if it's no longer needed.",
                        onRefresh = null,
                    )
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = !model.busy && detail.headRepoApiId != null) {
                        Text("Delete branch")
                    }
                }
                else -> {
                    when (detail.mergeability) {
                        Mergeability.MERGEABLE -> StatusLine(
                            icon = { Icon(Icons.Filled.CheckCircle, null, tint = StateColors.open) },
                            title = "No conflicts with ${detail.baseBranch}",
                            note = detail.mergeNote,
                            onRefresh = model.pull::refresh,
                        )
                        Mergeability.CONFLICTS -> StatusLine(
                            icon = { Icon(Icons.Filled.Warning, null, tint = Color(0xFFBF8700)) },
                            title = "This branch has conflicts with ${detail.baseBranch}",
                            note = "Resolve them before merging. RepoForge can merge ${detail.baseBranch} into " +
                                "${detail.headBranch} on your phone and let Claude propose the resolutions.",
                            onRefresh = model.pull::refresh,
                        )
                        Mergeability.BLOCKED -> StatusLine(
                            icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            title = "Merging is blocked",
                            note = detail.mergeNote,
                            onRefresh = model.pull::refresh,
                        )
                        Mergeability.CHECKING, Mergeability.UNKNOWN -> StatusLine(
                            icon = { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
                            title = "Still checking for conflicts",
                            note = detail.mergeNote ?: "Refresh in a moment.",
                            onRefresh = model.pull::refresh,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (detail.mergeability == Mergeability.CONFLICTS) {
                            Button(onClick = { onResolveConflicts(detail) }, enabled = !model.busy && detail.headCloneUrl != null) {
                                Text("Resolve with AI")
                            }
                        } else {
                            Button(onClick = { showDialog = true }, enabled = !model.busy) { Text("Merge…") }
                        }
                        if (model.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }

    if (showDialog && detail != null) {
        MergeDialog(
            detail = detail,
            methods = model.mergeMethods,
            onDismiss = { showDialog = false },
            onMerge = { method, title, message, delete ->
                showDialog = false
                model.merge(method, title, message, delete)
            },
        )
    }
    if (confirmDelete && detail != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${detail.headBranch}?") },
            text = { Text("The branch is removed from ${detail.headRepoApiId}. Its commits stay in ${detail.baseBranch} if they were merged.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; model.deleteBranch() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusLine(icon: @Composable () -> Unit, title: String, note: String?, onRefresh: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(top = 2.dp)) { icon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (onRefresh != null) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Refresh, "Check again", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun MergeDialog(
    detail: PullDetail,
    methods: List<MergeMethod>,
    onDismiss: () -> Unit,
    onMerge: (MergeMethod, String, String, Boolean) -> Unit,
) {
    val pull = detail.pull
    var method by remember { mutableStateOf(methods.first()) }
    fun defaultTitle(m: MergeMethod) = when (m) {
        MergeMethod.SQUASH -> "${pull.title} (#${pull.number})"
        else -> "Merge #${pull.number} from ${detail.headBranch}"
    }
    var title by remember { mutableStateOf(defaultTitle(method)) }
    var message by remember { mutableStateOf("") }
    var deleteBranch by remember { mutableStateOf(detail.headRepoApiId != null) }
    val takesMessage = method == MergeMethod.MERGE || method == MergeMethod.SQUASH

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge into ${detail.baseBranch}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                methods.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (title == defaultTitle(method)) title = defaultTitle(option)
                            method = option
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = method == option, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (takesMessage) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Commit title") }, singleLine = true)
                    OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Description (optional)") }, minLines = 2)
                }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = detail.headRepoApiId != null) { deleteBranch = !deleteBranch },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = deleteBranch, onCheckedChange = null, enabled = detail.headRepoApiId != null)
                    Text("Delete ${detail.headBranch} after merging", Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onMerge(method, if (takesMessage) title else "", if (takesMessage) message else "", deleteBranch) }) {
                Text("Confirm merge")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
