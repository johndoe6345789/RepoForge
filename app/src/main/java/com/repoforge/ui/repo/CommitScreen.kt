package com.repoforge.ui.repo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.repoforge.R
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.model.Account
import com.repoforge.data.model.Commit
import com.repoforge.data.model.FileDiff
import com.repoforge.data.model.Repo
import com.repoforge.ui.common.Avatar
import com.repoforge.ui.common.DiffSummary
import com.repoforge.ui.common.EmptyState
import com.repoforge.ui.common.ErrorState
import com.repoforge.ui.common.ListContentPadding
import com.repoforge.ui.common.Loadable
import com.repoforge.ui.common.SkeletonList
import com.repoforge.ui.common.copyToClipboard
import com.repoforge.ui.common.defaultExpanded
import com.repoforge.ui.common.diffFiles
import com.repoforge.ui.common.openUrl
import com.repoforge.ui.common.relativeTime
import kotlinx.coroutines.CoroutineScope

class CommitModel(scope: CoroutineScope, val account: Account, client: ForgeClient, val repo: Repo, val commit: Commit) {
    val files = Loadable<List<FileDiff>>(scope) { client.getCommitDiff(repo, commit.sha) }
    /** Which files the user expanded or collapsed, keyed by position and path. */
    val expanded = mutableStateMapOf<String, Boolean>()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitScreen(model: CommitModel, onBack: () -> Unit) {
    val commit = model.commit
    val context = LocalContext.current
    LaunchedEffect(model) { model.files.ensureLoaded() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text("Commit ${commit.shortSha}", fontFamily = FontFamily.Monospace)
                        Text(model.repo.fullName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    commit.webUrl?.let { url ->
                        IconButton(onClick = { openUrl(context, url) }) { Icon(painterResource(R.drawable.ic_open_in_browser), "Open in browser") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ListContentPadding) {
            item(key = "header") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(commit.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            val body = commit.message.substringAfter('\n', "").trim()
                            if (body.isNotEmpty()) {
                                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(commit.authorAvatarUrl, commit.authorName, size = 28.dp)
                        Text(
                            listOfNotNull(commit.authorName, relativeTime(commit.date).ifEmpty { null }?.let { "committed $it" }).joinToString(" "),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        AssistChip(
                            onClick = { copyToClipboard(context, "Commit SHA", commit.sha) },
                            label = { Text(commit.shortSha, fontFamily = FontFamily.Monospace) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), "Copy SHA", Modifier.padding(0.dp)) },
                        )
                    }
                }
                HorizontalDivider()
            }
            val files = model.files.value
            when {
                files != null -> {
                    item(key = "summary") { DiffSummary(files) }
                    if (files.isEmpty()) item(key = "empty") { EmptyState("This commit doesn't change any files") }
                    diffFiles(files, model.expanded, defaultExpanded(files))
                }
                model.files.error != null -> item(key = "error") { ErrorState(model.files.error!!, onRetry = model.files::refresh) }
                else -> item(key = "loading") { SkeletonList(rows = 5, avatar = false) }
            }
        }
    }
}
