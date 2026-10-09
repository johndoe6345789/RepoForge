package com.repoforge.ui.repo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.repoforge.R
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.forge.WebLinks
import com.repoforge.data.model.Commit
import com.repoforge.data.model.EntryType
import com.repoforge.data.model.Issue
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.StateFilter
import com.repoforge.data.model.TreeEntry
import com.repoforge.ui.common.Avatar
import com.repoforge.ui.common.ErrorState
import com.repoforge.ui.common.LabelChip
import com.repoforge.ui.common.ListContentPadding
import com.repoforge.ui.common.LoadableContent
import com.repoforge.ui.common.LoadingState
import com.repoforge.ui.common.MarkdownView
import com.repoforge.ui.common.MetaRow
import com.repoforge.ui.common.Paged
import com.repoforge.ui.common.copyToClipboard
import com.repoforge.ui.common.openUrl
import com.repoforge.ui.common.pagedFooter
import com.repoforge.ui.common.relativeTime
import com.repoforge.ui.common.share
import com.repoforge.ui.theme.StateColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(
    model: RepoModel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenIssue: (Issue) -> Unit,
    onNewIssue: () -> Unit,
) {
    val repo = model.repo
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var branchPicker by remember { mutableStateOf(false) }

    // Back walks up the folder tree before leaving the repository.
    BackHandler(enabled = model.tab == RepoTab.CODE && model.path.isNotEmpty()) { model.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(repo.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(repo.owner, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    repo.webUrl?.let { url ->
                        IconButton(onClick = { openUrl(context, url) }) {
                            Icon(painterResource(R.drawable.ic_open_in_browser), "Open in browser")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            repo.cloneHttps?.let { url ->
                                DropdownMenuItem(
                                    text = { Text("Copy HTTPS clone URL") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), null) },
                                    onClick = { menuOpen = false; copyToClipboard(context, "Clone URL", url) },
                                )
                            }
                            repo.cloneSsh?.let { url ->
                                DropdownMenuItem(
                                    text = { Text("Copy SSH clone URL") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), null) },
                                    onClick = { menuOpen = false; copyToClipboard(context, "Clone URL", url) },
                                )
                            }
                            repo.webUrl?.let { url ->
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                                    onClick = { menuOpen = false; share(context, url) },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (model.tab == RepoTab.ISSUES) {
                ExtendedFloatingActionButton(onClick = onNewIssue, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New issue") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = model.tab.ordinal, edgePadding = 8.dp) {
                RepoTab.entries.forEach { tab ->
                    Tab(
                        selected = model.tab == tab,
                        onClick = { model.tab = tab },
                        text = {
                            Text(
                                when (tab) {
                                    RepoTab.CODE -> "Code"
                                    RepoTab.COMMITS -> "Commits"
                                    RepoTab.ISSUES -> "Issues"
                                    RepoTab.PULLS -> model.account.type.pullRequestName
                                }
                            )
                        },
                    )
                }
            }
            when (model.tab) {
                RepoTab.CODE -> CodeTab(model, onOpenFile, onPickBranch = { branchPicker = true })
                RepoTab.COMMITS -> CommitsTab(model, onPickBranch = { branchPicker = true })
                RepoTab.ISSUES -> IssueList(
                    paged = model.issues(),
                    filter = model.issueFilter,
                    onFilter = { model.issueFilter = it },
                    closedLabel = "Closed",
                    onOpen = onOpenIssue,
                )
                RepoTab.PULLS -> IssueList(
                    paged = model.pulls(),
                    filter = model.pullFilter,
                    onFilter = { model.pullFilter = it },
                    closedLabel = "Merged / closed",
                    onOpen = onOpenIssue,
                )
            }
        }
    }

    if (branchPicker) {
        BranchDialog(model, onDismiss = { branchPicker = false })
    }
}

@Composable
private fun RepoHeader(model: RepoModel, onPickBranch: () -> Unit) {
    val repo = model.repo
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repo.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        MetaRow {
            if (repo.isPrivate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(14.dp))
                    Text(" Private", style = MaterialTheme.typography.labelMedium)
                }
            }
            repo.stars?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, Modifier.size(14.dp))
                    Text(" $it", style = MaterialTheme.typography.labelMedium)
                }
            }
            repo.forks?.let { Text("$it forks", style = MaterialTheme.typography.labelMedium) }
            repo.language?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
        BranchChip(model.ref, onPickBranch)
    }
}

@Composable
private fun BranchChip(ref: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(ref, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_branch), null, Modifier.size(18.dp)) },
    )
}

@Composable
private fun CodeTab(model: RepoModel, onOpenFile: (String) -> Unit, onPickBranch: () -> Unit) {
    val tree = model.tree()
    val context = LocalContext.current
    PullToRefreshBox(isRefreshing = tree.loading && tree.value != null, onRefresh = model::refreshCode) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
            item(key = "header") { RepoHeader(model, onPickBranch) }
            item(key = "breadcrumbs") { Breadcrumbs(model.path, onNavigate = model::openDir) }
            item(key = "divider") { HorizontalDivider() }
            val entries = tree.value
            when {
                entries != null -> {
                    if (entries.isEmpty()) item(key = "empty") { com.repoforge.ui.common.EmptyState("This folder is empty") }
                    items(entries, key = { "entry:" + it.path }) { entry ->
                        EntryRow(entry) {
                            when (entry.type) {
                                EntryType.DIR -> model.openDir(entry.path)
                                EntryType.SUBMODULE -> WebLinks.blob(model.account.type, model.repo, model.ref, entry.path, isDir = true)
                                    ?.let { openUrl(context, it) }
                                else -> onOpenFile(entry.path)
                            }
                        }
                    }
                    ForgeClient.findReadme(entries)?.let { readme ->
                        item(key = "readme:" + readme.path) { ReadmeCard(model, readme) }
                    }
                }
                tree.error != null -> item(key = "error") { ErrorState(tree.error!!, onRetry = tree::refresh) }
                else -> item(key = "loading") {
                    androidx.compose.runtime.LaunchedEffect(tree) { tree.ensureLoaded() }
                    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp)) { LoadingState() }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onNavigate("") }) { Text("root") }
        parts.forEachIndexed { index, part ->
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp))
            val target = parts.take(index + 1).joinToString("/")
            TextButton(onClick = { onNavigate(target) }, enabled = index != parts.lastIndex) { Text(part) }
        }
    }
}

@Composable
private fun EntryRow(entry: TreeEntry, onClick: () -> Unit) {
    val icon = when (entry.type) {
        EntryType.DIR -> R.drawable.ic_folder
        EntryType.SUBMODULE -> R.drawable.ic_commit
        EntryType.SYMLINK -> R.drawable.ic_link
        EntryType.FILE -> R.drawable.ic_file
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                painterResource(icon),
                null,
                tint = if (entry.type == EntryType.DIR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = entry.size?.takeIf { entry.type == EntryType.FILE }?.let { size ->
            { Text(formatSize(size), style = MaterialTheme.typography.labelSmall) }
        },
    )
}

@Composable
private fun ReadmeCard(model: RepoModel, readme: TreeEntry) {
    val loadable = model.readme(readme)
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(readme.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        LoadableContent(loadable, Modifier.heightIn(min = 120.dp)) { blob ->
            val type = model.account.type
            if (blob.isMarkdown) {
                val markdown = remember(blob) {
                    WebLinks.rewriteImages(blob.text, blob.path) { WebLinks.raw(type, model.repo, model.ref, it) }
                }
                MarkdownView(
                    markdown,
                    modifier = Modifier.padding(16.dp),
                    resolveLink = { link ->
                        WebLinks.resolveRelative(link, blob.path)?.let { WebLinks.blob(type, model.repo, model.ref, it) }
                    },
                )
            } else {
                Text(blob.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun CommitsTab(model: RepoModel, onPickBranch: () -> Unit) {
    val commits = model.commits()
    val context = LocalContext.current
    PullToRefreshBox(isRefreshing = commits.refreshing, onRefresh = commits::refresh) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
            item(key = "branch") { Box(Modifier.padding(horizontal = 16.dp)) { BranchChip(model.ref, onPickBranch) } }
            items(commits.items, key = { "commit:" + it.sha }) { commit ->
                CommitRow(commit) { commit.webUrl?.let { openUrl(context, it) } }
                HorizontalDivider()
            }
            pagedFooter(commits, emptyMessage = "No commits")
        }
    }
}

@Composable
private fun CommitRow(commit: Commit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(commit.authorAvatarUrl, commit.authorName, size = 32.dp) },
        headlineContent = { Text(commit.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(commit.authorName, relativeTime(commit.date).ifEmpty { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = { Text(commit.shortSha, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium) },
    )
}

@Composable
private fun IssueList(
    paged: Paged<Issue>,
    filter: StateFilter,
    onFilter: (StateFilter) -> Unit,
    closedLabel: String,
    onOpen: (Issue) -> Unit,
) {
    PullToRefreshBox(isRefreshing = paged.refreshing, onRefresh = paged::refresh) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
            item(key = "filters") {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { onFilter(option) },
                            label = {
                                Text(
                                    when (option) {
                                        StateFilter.OPEN -> "Open"
                                        StateFilter.CLOSED -> closedLabel
                                        StateFilter.ALL -> "All"
                                    }
                                )
                            },
                        )
                    }
                }
            }
            items(paged.items, key = { "issue:" + it.number }) { issue ->
                IssueRow(issue) { onOpen(issue) }
                HorizontalDivider()
            }
            pagedFooter(paged, emptyMessage = "Nothing here")
        }
    }
}

@Composable
fun IssueRow(issue: Issue, onClick: () -> Unit) {
    val color = when {
        issue.isDraft && issue.state == IssueState.OPEN -> StateColors.draft
        issue.state == IssueState.OPEN -> StateColors.open
        issue.state == IssueState.MERGED -> StateColors.merged
        else -> StateColors.closed
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(painterResource(if (issue.isPullRequest) R.drawable.ic_pull_request else R.drawable.ic_issue), issue.state.name, tint = color)
        },
        headlineContent = { Text(issue.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    buildString {
                        append("#${issue.number}")
                        issue.author?.let { append(" by ${it.login}") }
                        relativeTime(issue.updatedAt ?: issue.createdAt).takeIf { it.isNotEmpty() }?.let { append(" · updated $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (issue.labels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { issue.labels.take(4).forEach { LabelChip(it) } }
                }
            }
        },
        trailingContent = issue.commentCount?.takeIf { it > 0 }?.let { count ->
            { Text("$count 💬", style = MaterialTheme.typography.labelMedium) }
        },
    )
}

@Composable
private fun BranchDialog(model: RepoModel, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch branch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = filter, onValueChange = { filter = it }, placeholder = { Text("Filter branches") }, singleLine = true)
                LoadableContent(model.branches, Modifier.heightIn(min = 120.dp)) { branches ->
                    val shown = branches.filter { it.name.contains(filter.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.name }) { branch ->
                            ListItem(
                                modifier = Modifier.clickable { model.selectRef(branch.name); onDismiss() },
                                leadingContent = { Icon(painterResource(R.drawable.ic_branch), null) },
                                headlineContent = {
                                    Text(
                                        branch.name,
                                        fontWeight = if (branch.name == model.ref) FontWeight.Bold else null,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = if (branch.name == model.repo.defaultBranch) ({ Text("default") }) else null,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { IconButton(onClick = model.branches::refresh) { Icon(Icons.Filled.Refresh, "Reload") } },
    )
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024))
}
