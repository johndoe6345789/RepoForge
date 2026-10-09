package com.repoforge.ui.ci

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.repoforge.R
import com.repoforge.data.ci.ArchiveEntry
import com.repoforge.data.ci.LogLine
import com.repoforge.data.ci.LogLineKind
import com.repoforge.data.ci.ParsedLog
import com.repoforge.data.model.CiArtifact
import com.repoforge.data.model.CiJob
import com.repoforge.data.model.CiRun
import com.repoforge.data.model.CiStatus
import com.repoforge.ui.common.Avatar
import com.repoforge.ui.common.ErrorState
import com.repoforge.ui.common.ListContentPadding
import com.repoforge.ui.common.LoadableContent
import com.repoforge.ui.common.MetaRow
import com.repoforge.ui.common.OutlineBadge
import com.repoforge.ui.common.Paged
import com.repoforge.ui.common.SectionHeader
import com.repoforge.ui.common.SkeletonList
import com.repoforge.ui.common.openUrl
import com.repoforge.ui.common.pagedFooter
import com.repoforge.ui.common.relativeTime
import com.repoforge.ui.repo.formatSize
import com.repoforge.ui.theme.LocalDarkTheme
import com.repoforge.ui.theme.StateColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private val Amber = Color(0xFFBF8700)

fun CiStatus.label(): String = when (this) {
    CiStatus.QUEUED -> "Queued"
    CiStatus.RUNNING -> "In progress"
    CiStatus.SUCCESS -> "Succeeded"
    CiStatus.FAILURE -> "Failed"
    CiStatus.CANCELLED -> "Cancelled"
    CiStatus.SKIPPED -> "Skipped"
    CiStatus.NEUTRAL -> "Neutral"
    CiStatus.ACTION_REQUIRED -> "Waiting for action"
    CiStatus.UNKNOWN -> "Unknown"
}

@Composable
fun CiStatusIcon(status: CiStatus, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val muted = MaterialTheme.colorScheme.outline
    when (status) {
        CiStatus.RUNNING -> {
            // A spinning ring rather than an indeterminate bar: it reads at icon size.
            val angle by rememberInfiniteTransition(label = "ci").animateFloat(
                0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "spin",
            )
            CircularProgressIndicator(
                progress = { 0.7f },
                modifier = modifier.size(size).padding(2.dp).rotate(angle),
                color = Amber,
                strokeWidth = 2.5.dp,
                trackColor = Amber.copy(alpha = 0.2f),
            )
        }
        CiStatus.SUCCESS -> Icon(Icons.Filled.CheckCircle, status.label(), modifier.size(size), tint = StateColors.open)
        CiStatus.FAILURE -> Icon(painterResource(R.drawable.ic_cancel), status.label(), modifier.size(size), tint = StateColors.closed)
        CiStatus.QUEUED -> Icon(painterResource(R.drawable.ic_schedule), status.label(), modifier.size(size), tint = Amber)
        CiStatus.ACTION_REQUIRED -> Icon(Icons.Filled.Warning, status.label(), modifier.size(size), tint = Amber)
        CiStatus.CANCELLED -> Icon(painterResource(R.drawable.ic_block), status.label(), modifier.size(size), tint = muted)
        CiStatus.SKIPPED, CiStatus.NEUTRAL, CiStatus.UNKNOWN ->
            Icon(painterResource(R.drawable.ic_skipped), status.label(), modifier.size(size), tint = muted)
    }
}

/** "1h 2m", "3m 4s", "12s" — or null when the start isn't known. */
fun formatDuration(start: Instant?, end: Instant?): String? {
    start ?: return null
    val seconds = Duration.between(start, end ?: Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

/** The repository's CI runs, newest first; refreshes itself while any are in progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CiRunsTab(paged: Paged<CiRun>, onOpen: (CiRun) -> Unit) {
    val active = paged.items.any { !it.status.isFinished }
    LaunchedEffect(paged, active) {
        while (active) {
            delay(15_000)
            paged.refresh()
        }
    }
    PullToRefreshBox(isRefreshing = paged.refreshing, onRefresh = paged::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
            items(paged.items, key = { it.id }) { run -> RunRow(run) { onOpen(run) } }
            pagedFooter(paged, emptyMessage = "No runs yet", skeletonAvatars = true)
        }
    }
}

@Composable
private fun RunRow(run: CiRun, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { CiStatusIcon(run.status) },
        headlineContent = { Text(run.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOfNotNull(run.workflow, run.number?.let { "#$it" }, run.event).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetaRow {
                    run.branch?.let { BranchLabel(it) }
                    Text(
                        listOfNotNull(
                            relativeTime(run.createdAt).takeIf { it.isNotEmpty() },
                            formatDuration(run.startedAt ?: run.createdAt, run.finishedAt),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = run.actor?.let { actor -> { Avatar(actor.avatarUrl, actor.login, size = 28.dp) } },
    )
}

@Composable
private fun BranchLabel(branch: String) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_branch), null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            branch,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** One run: summary, jobs (tap for the log) and artifacts to download, save, open or install. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CiRunScreen(model: CiRunModel, onBack: () -> Unit, onOpenJob: (CiJob) -> Unit) {
    val context = LocalContext.current
    val run = model.run
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) {
        model.jobs.ensureLoaded()
        model.artifacts.ensureLoaded()
    }
    // Follow the run until it finishes.
    LaunchedEffect(model, run.status.isFinished) {
        while (!model.run.status.isFinished) {
            delay(10_000)
            model.refresh()
        }
    }
    LaunchedEffect(model.notice) {
        model.notice?.let { snackbar.showSnackbar(it); model.notice = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(
                            listOfNotNull(run.workflow ?: model.client.ci.name, run.number?.let { "#$it" }).joinToString(" "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(model.repo.fullName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = model::refresh) { Icon(Icons.Filled.Refresh, "Refresh") }
                    run.webUrl?.let { url ->
                        IconButton(onClick = { openUrl(context, url) }) { Icon(painterResource(R.drawable.ic_open_in_browser), "Open in browser") }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = model.jobs.loading && model.jobs.value != null,
            onRefresh = model::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
                item("summary") { RunSummary(run) }
                jobsSection(model, onOpenJob)
                artifactsSection(model) { intent ->
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        model.notice = "No app on this phone can open that file"
                    }
                }
            }
        }
    }
}

@Composable
private fun RunSummary(run: CiRun) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CiStatusIcon(run.status, size = 28.dp)
                Column {
                    Text(run.status.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    formatDuration(run.startedAt ?: run.createdAt, run.finishedAt)?.let {
                        Text(
                            if (run.status.isFinished) "in $it" else "running for $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(run.title, style = MaterialTheme.typography.bodyLarge)
            MetaRow {
                run.branch?.let { BranchLabel(it) }
                run.sha?.let {
                    Text(it.take(7), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                run.event?.let { OutlineBadge(it) }
            }
            run.actor?.let { actor ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(actor.avatarUrl, actor.login, size = 22.dp)
                    Text(
                        "${actor.name ?: actor.login} · ${relativeTime(run.createdAt)}" + (run.attempt?.takeIf { it > 1 }?.let { " · attempt $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.jobsSection(model: CiRunModel, onOpenJob: (CiJob) -> Unit) {
    val jobs = model.jobs
    val list = jobs.value
    item("jobs-header") { SectionHeader(if (list != null) "Jobs (${list.size})" else "Jobs") }
    when {
        list == null && jobs.error != null -> item("jobs-error") { ErrorState(jobs.error!!, onRetry = jobs::refresh) }
        list == null -> item("jobs-loading") { SkeletonList(rows = 3, avatar = true) }
        list.isEmpty() -> item("jobs-empty") {
            Text("No jobs reported yet.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            var lastStage: String? = null
            list.forEach { job ->
                if (job.stage != null && job.stage != lastStage) {
                    lastStage = job.stage
                    item("stage-${job.stage}-${job.id}") {
                        Text(
                            job.stage.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                }
                item("job-${job.id}") { JobRow(job) { onOpenJob(job) } }
            }
        }
    }
}

@Composable
private fun JobRow(job: CiJob, onOpen: () -> Unit) {
    var showSteps by remember(job.id) { mutableStateOf(job.status == CiStatus.FAILURE) }
    Column {
        ListItem(
            modifier = Modifier.clickable(onClick = onOpen),
            leadingContent = { CiStatusIcon(job.status) },
            headlineContent = { Text(job.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(listOfNotNull(job.status.label(), formatDuration(job.startedAt, job.finishedAt)).joinToString(" · "))
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (job.steps.isNotEmpty()) {
                        IconButton(onClick = { showSteps = !showSteps }) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                if (showSteps) "Hide steps" else "Show steps",
                                Modifier.rotate(if (showSteps) 180f else 0f),
                            )
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "View log", tint = MaterialTheme.colorScheme.outline)
                }
            },
        )
        if (showSteps) {
            job.steps.forEach { step ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CiStatusIcon(step.status, size = 16.dp)
                    Text(step.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    formatDuration(step.startedAt, step.finishedAt)?.takeIf { step.status != CiStatus.SKIPPED }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

private fun LazyListScope.artifactsSection(model: CiRunModel, startActivity: (Intent) -> Unit) {
    val artifacts = model.artifacts
    val list = artifacts.value
    item("artifacts-header") { SectionHeader(if (!list.isNullOrEmpty()) "Artifacts (${list.size})" else "Artifacts") }
    when {
        !model.client.ci.artifacts -> item("artifacts-unsupported") {
            Text(
                "This service doesn't offer pipeline artifacts through its API. Open the run in the browser to download them.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        list == null && artifacts.error != null -> item("artifacts-error") { ErrorState(artifacts.error!!, onRetry = artifacts::refresh) }
        list == null -> item("artifacts-loading") { SkeletonList(rows = 1, avatar = true) }
        list.isEmpty() -> item("artifacts-empty") {
            Text(
                if (model.run.status.isFinished) "This run didn't upload any artifacts." else "Artifacts appear here as jobs upload them.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> items(list, key = { "artifact-${it.id}" }) { artifact ->
            ArtifactCard(model, artifact, startActivity)
        }
    }
}

@Composable
private fun ArtifactCard(model: CiRunModel, artifact: CiArtifact, startActivity: (Intent) -> Unit) {
    val download = model.downloads[artifact.id]
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_archive), null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(artifact.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            artifact.sizeBytes?.let(::formatSize),
                            when {
                                artifact.expired -> "expired"
                                artifact.expiresAt != null -> "expires ${relativeTime(artifact.expiresAt)}"
                                else -> null
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (artifact.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (download) {
                    null, is ArtifactDownload.Failed -> FilledTonalButton(
                        onClick = { model.download(artifact) },
                        enabled = !artifact.expired && artifact.downloadUrl.isNotEmpty(),
                    ) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                        Text(" Download")
                    }
                    is ArtifactDownload.Running -> IconButton(onClick = { model.cancelDownload(artifact) }) {
                        Icon(Icons.Filled.Close, "Cancel download")
                    }
                    is ArtifactDownload.Done -> TextButton(onClick = { model.save(artifact, null) }) { Text("Save .zip") }
                }
            }
            when (download) {
                is ArtifactDownload.Running -> {
                    val fraction = download.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        formatSize(download.done) + (download.total?.let { " of ${formatSize(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ArtifactDownload.Failed -> Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                is ArtifactDownload.Done -> {
                    if (download.entries.isEmpty()) {
                        OutlinedButton(onClick = { model.open(artifact, null, startActivity) }) { Text("Open") }
                    } else {
                        HorizontalDivider()
                        download.entries.take(MAX_ENTRIES).forEach { entry -> EntryRow(entry, model, artifact, startActivity) }
                        if (download.entries.size > MAX_ENTRIES) {
                            Text(
                                "and ${download.entries.size - MAX_ENTRIES} more files — save the .zip to get them all.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                null -> Unit
            }
        }
    }
}

private const val MAX_ENTRIES = 30

@Composable
private fun EntryRow(entry: ArchiveEntry, model: CiRunModel, artifact: CiArtifact, startActivity: (Intent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            painterResource(if (entry.isApk) R.drawable.ic_android else R.drawable.ic_file),
            null,
            Modifier.size(20.dp),
            tint = if (entry.isApk) StateColors.open else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(entry.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(entry.name.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }, formatSize(entry.size)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { model.save(artifact, entry) }) { Icon(painterResource(R.drawable.ic_download), "Save to Downloads") }
        if (entry.isApk) {
            FilledTonalButton(onClick = { model.open(artifact, entry, startActivity) }) { Text("Install") }
        } else {
            TextButton(onClick = { model.open(artifact, entry, startActivity) }) { Text("Open") }
        }
    }
}

/** A job's log with collapsible groups, colours, and shortcuts to the first error and the end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CiLogScreen(model: CiLogModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val job = model.job
    val parsed = model.log.value
    val listState = rememberLazyListState()
    val visible = remember(parsed, model.expanded.toMap()) { parsed?.let(model::visible).orEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(job.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(job.status.label(), formatDuration(job.startedAt, job.finishedAt)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = model.log::refresh) { Icon(Icons.Filled.Refresh, "Reload") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Wrap lines") },
                                trailingIcon = { Checkbox(checked = model.wrap, onCheckedChange = null) },
                                onClick = { model.wrap = !model.wrap },
                            )
                            if (parsed != null) {
                                DropdownMenuItem(text = { Text("Expand all") }, onClick = { menuOpen = false; model.expandAll(parsed) })
                                DropdownMenuItem(text = { Text("Collapse all") }, onClick = { menuOpen = false; model.collapseAll() })
                                DropdownMenuItem(
                                    text = { Text("Share log") },
                                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            val file = model.logFile()
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                                                .putExtra(Intent.EXTRA_STREAM, uri)
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            context.startActivity(Intent.createChooser(intent, null))
                                        }
                                    },
                                )
                            }
                            job.webUrl?.let { url ->
                                DropdownMenuItem(
                                    text = { Text("Open in browser") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_open_in_browser), null) },
                                    onClick = { menuOpen = false; openUrl(context, url) },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (parsed != null && visible.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    parsed.firstError?.let { errorIndex ->
                        SmallFloatingActionButton(
                            onClick = {
                                val target = parsed.lines[errorIndex]
                                target.group?.let { model.expanded[it] = true }
                                scope.launch {
                                    val index = model.visible(parsed).indexOfFirst { it.number == target.number }
                                    if (index >= 0) listState.animateScrollToItem(index + 1)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ) { Icon(Icons.Filled.Warning, "Jump to the first error") }
                    }
                    SmallFloatingActionButton(onClick = { scope.launch { listState.scrollToItem(0) } }) {
                        Icon(painterResource(R.drawable.ic_to_top), "Jump to the top")
                    }
                    SmallFloatingActionButton(onClick = { scope.launch { listState.scrollToItem(visible.size) } }) {
                        Icon(painterResource(R.drawable.ic_to_bottom), "Jump to the end")
                    }
                }
            }
        },
    ) { padding ->
        LoadableContent(model.log, Modifier.padding(padding)) { log ->
            LogView(log, visible, model, listState, Modifier.padding(padding))
        }
    }
}

@Composable
private fun LogView(
    log: ParsedLog,
    lines: List<LogLine>,
    model: CiLogModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
) {
    val dark = LocalDarkTheme.current
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.45)
    val measurer = rememberTextMeasurer()
    val maxNumber = log.lines.lastOrNull()?.number ?: 1
    val gutter = with(LocalDensity.current) { measurer.measure("0".repeat(maxNumber.toString().length), style).size.width.toDp() + 12.dp }
    val hScroll = rememberScrollState()
    val palette = if (dark) DARK_PALETTE else LIGHT_PALETTE

    if (log.lines.isEmpty()) {
        Text("The log is empty.", modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    SelectionContainer(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 160.dp)) {
            item("dropped") {
                if (log.droppedLines > 0) {
                    Text(
                        "Showing the last ${log.lines.size} lines; ${log.droppedLines} earlier lines are in the shared log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(lines, key = { it.number }) { line ->
                val background = when (line.kind) {
                    LogLineKind.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    LogLineKind.WARNING -> Amber.copy(alpha = 0.16f)
                    LogLineKind.GROUP -> MaterialTheme.colorScheme.surfaceContainer
                    else -> Color.Transparent
                }
                val textColor = when (line.kind) {
                    LogLineKind.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                    LogLineKind.COMMAND -> MaterialTheme.colorScheme.primary
                    LogLineKind.DEBUG -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val expanded = line.group != null && model.expanded[line.group] == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(background)
                        .then(if (line.kind == LogLineKind.GROUP) Modifier.clickable { model.toggle(line.group!!) } else Modifier),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        line.number.toString(),
                        style = style,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(gutter).padding(end = 8.dp),
                    )
                    if (line.kind == LogLineKind.GROUP) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            if (expanded) "Collapse" else "Expand",
                            Modifier.size(16.dp).rotate(if (expanded) 90f else 0f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val text = remember(line, palette) { annotate(line, palette) }
                    Text(
                        text,
                        style = style.copy(fontWeight = if (line.kind == LogLineKind.GROUP) FontWeight.SemiBold else null),
                        color = textColor,
                        softWrap = model.wrap,
                        modifier = if (model.wrap) Modifier.padding(end = 8.dp) else Modifier.horizontalScroll(hScroll).padding(end = 16.dp),
                    )
                }
            }
        }
    }
}

private fun annotate(line: LogLine, palette: List<Color>): AnnotatedString {
    if (line.spans.isEmpty()) return AnnotatedString(line.text)
    return buildAnnotatedString {
        append(line.text)
        line.spans.forEach { span ->
            addStyle(
                SpanStyle(
                    color = span.color?.let { palette.getOrNull(it) } ?: Color.Unspecified,
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                ),
                span.start.coerceAtMost(line.text.length),
                span.end.coerceAtMost(line.text.length),
            )
        }
    }
}

// ANSI colours 0–15, tuned for contrast on each theme's background.
private val LIGHT_PALETTE = listOf(
    0xFF24292F, 0xFFCF222E, 0xFF116329, 0xFF7D4E00, 0xFF0969DA, 0xFF8250DF, 0xFF1B7C83, 0xFF6E7781,
    0xFF57606A, 0xFFA40E26, 0xFF1A7F37, 0xFF633C01, 0xFF218BFF, 0xFFA475F9, 0xFF3192AA, 0xFF8C959F,
).map { Color(it) }

private val DARK_PALETTE = listOf(
    0xFF8B949E, 0xFFFF7B72, 0xFF3FB950, 0xFFD29922, 0xFF58A6FF, 0xFFBC8CFF, 0xFF39C5CF, 0xFFB1BAC4,
    0xFF6E7681, 0xFFFFA198, 0xFF56D364, 0xFFE3B341, 0xFF79C0FF, 0xFFD2A8FF, 0xFF56D4DD, 0xFFF0F6FC,
).map { Color(it) }
