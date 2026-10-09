package com.repoforge.ui.issue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.repoforge.R
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.forge.WebLinks
import com.repoforge.data.model.Account
import com.repoforge.data.model.Comment
import com.repoforge.data.model.Issue
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.MergeMethod
import com.repoforge.data.model.PullDetail
import com.repoforge.data.model.Repo
import com.repoforge.ui.common.Avatar
import com.repoforge.ui.common.DiffSummary
import com.repoforge.ui.common.ListContentPadding
import com.repoforge.ui.common.SkeletonList
import com.repoforge.ui.common.defaultExpanded
import com.repoforge.ui.common.diffFiles
import com.repoforge.ui.common.EmptyState
import com.repoforge.ui.common.ErrorState
import com.repoforge.ui.common.LabelChip
import com.repoforge.ui.common.Loadable
import com.repoforge.ui.common.MarkdownView
import com.repoforge.ui.common.StateChip
import com.repoforge.ui.common.openUrl
import com.repoforge.ui.common.relativeTime
import com.repoforge.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class IssueModel(
    private val scope: CoroutineScope,
    val account: Account,
    private val client: ForgeClient,
    val repo: Repo,
    initial: Issue,
) {
    /** Updated after a merge so the header shows the new state. */
    var issue by mutableStateOf(initial)
        private set
    val comments = Loadable(scope) { client.listComments(repo, initial) }
    /** Mergeability and branch details, for pull/merge requests. */
    val pull = Loadable(scope) { client.getPullRequest(repo, initial.number).also { issue = it.pull } }
    val mergeMethods: List<MergeMethod> get() = client.mergeMethods
    var busy by mutableStateOf(false)
        private set
    /** A one-off message for the snackbar, e.g. "Merged". */
    var notice by mutableStateOf<String?>(null)

    fun merge(method: MergeMethod, title: String, message: String, deleteBranch: Boolean) {
        val detail = pull.value ?: return
        runAction {
            val outcome = client.mergePullRequest(repo, detail, method, title, message, deleteBranch)
            issue = issue.copy(state = IssueState.MERGED)
            notice = when {
                outcome.pending -> "Merge started; it will finish shortly"
                outcome.branchError != null -> "Merged, but the branch wasn't deleted: ${outcome.branchError}"
                outcome.branchDeleted -> "Merged and deleted ${detail.headBranch}"
                else -> "Merged"
            }
            pull.refresh()
        }
    }

    fun deleteBranch() {
        val detail = pull.value ?: return
        runAction {
            client.deleteBranch(repo, detail)
            notice = "Deleted ${detail.headBranch}"
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
    /** Changed files, for pull/merge requests. */
    val files = Loadable(scope) { client.getPullRequestDiff(repo, initial) }
    val expanded = mutableStateMapOf<String, Boolean>()
    var showFiles by mutableStateOf(false)
    /** Comments posted from this screen, shown after the loaded thread. */
    val posted = mutableStateListOf<Comment>()
    var draft by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set

    fun send() {
        val body = draft.trim()
        if (body.isEmpty() || sending) return
        sending = true
        sendError = null
        scope.launch {
            try {
                posted += client.addComment(repo, issue, body)
                draft = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError = e.userMessage()
            } finally {
                sending = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueScreen(model: IssueModel, onBack: () -> Unit, onResolveConflicts: (PullDetail) -> Unit = {}) {
    val issue = model.issue
    val context = LocalContext.current
    val type = model.account.type
    val resolveLink: (String) -> String? = { link -> WebLinks.resolveRelative(link, "")?.let { WebLinks.blob(type, model.repo, model.repo.defaultBranch ?: "main", it) } }

    LaunchedEffect(model) { model.comments.ensureLoaded() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.notice) {
        model.notice?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            model.notice = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text("${if (issue.isPullRequest) type.pullRequestName.removeSuffix("s") else "Issue"} #${issue.number}")
                        Text(model.repo.fullName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    issue.webUrl?.let { url ->
                        IconButton(onClick = { openUrl(context, url) }) { Icon(painterResource(R.drawable.ic_open_in_browser), "Open in browser") }
                    }
                },
            )
        },
        bottomBar = { if (!model.showFiles) CommentComposer(model) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (issue.isPullRequest) {
                PrimaryTabRow(selectedTabIndex = if (model.showFiles) 1 else 0) {
                    Tab(selected = !model.showFiles, onClick = { model.showFiles = false }, text = { Text("Conversation") })
                    Tab(
                        selected = model.showFiles,
                        onClick = { model.showFiles = true },
                        text = { Text(model.files.value?.let { "Files changed (${it.size})" } ?: "Files changed") },
                    )
                }
            }
            if (model.showFiles) FilesTab(model) else ConversationTab(model, resolveLink, onResolveConflicts)
        }
    }
}

@Composable
private fun ConversationTab(model: IssueModel, resolveLink: (String) -> String?, onResolveConflicts: (PullDetail) -> Unit) {
    val issue = model.issue
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(issue.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateChip(issue.state, issue.isDraft)
                    Text(
                        buildString {
                            issue.author?.let { append(it.login) }
                            relativeTime(issue.createdAt).takeIf { it.isNotEmpty() }?.let { append(" opened $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (issue.sourceBranch != null && issue.targetBranch != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(painterResource(R.drawable.ic_branch), null, Modifier.size(16.dp))
                        Text("${issue.sourceBranch} → ${issue.targetBranch}", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (issue.labels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { issue.labels.forEach { LabelChip(it) } }
                }
            }
        }
        if (issue.isPullRequest) {
            item(key = "merge") { MergePanel(model, onResolveConflicts) }
        }
        item(key = "body") {
            CommentCard(
                author = issue.author?.login,
                avatar = issue.author?.avatarUrl,
                time = issue.createdAt,
                body = issue.body?.takeIf { it.isNotBlank() } ?: "_No description provided._",
                resolveLink = resolveLink,
            )
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        val comments = model.comments.value
        when {
            comments != null -> {
                if (comments.isEmpty() && model.posted.isEmpty()) item(key = "none") { EmptyState("No comments yet") }
                items(comments + model.posted, key = { "comment:" + it.id }) { comment ->
                    CommentCard(comment.author?.login, comment.author?.avatarUrl, comment.createdAt, comment.body, resolveLink)
                }
            }
            model.comments.error != null -> item(key = "error") {
                ErrorState(model.comments.error!!, onRetry = model.comments::refresh)
            }
            else -> item(key = "loading") { SkeletonList(rows = 3) }
        }
    }
}

@Composable
private fun FilesTab(model: IssueModel) {
    LaunchedEffect(model) { model.files.ensureLoaded() }
    val files = model.files.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
        when {
            files != null -> {
                item(key = "summary") { DiffSummary(files) }
                if (files.isEmpty()) item(key = "empty") { EmptyState("No changed files") }
                diffFiles(files, model.expanded, defaultExpanded(files))
            }
            model.files.error != null -> item(key = "error") { ErrorState(model.files.error!!, onRetry = model.files::refresh) }
            else -> item(key = "loading") { SkeletonList(rows = 5, avatar = false) }
        }
    }
}

@Composable
private fun CommentCard(author: String?, avatar: String?, time: java.time.Instant?, body: String, resolveLink: (String) -> String?) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(avatar, author, size = 24.dp)
            Text(
                listOfNotNull(author, relativeTime(time).ifEmpty { null }).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        MarkdownView(body, modifier = Modifier.padding(12.dp), resolveLink = resolveLink)
    }
}

@Composable
private fun CommentComposer(model: IssueModel) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            model.sendError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = model.draft,
                    onValueChange = { model.draft = it },
                    placeholder = { Text("Leave a comment (Markdown)") },
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                if (model.sending) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                } else {
                    IconButton(onClick = model::send, enabled = model.draft.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}
