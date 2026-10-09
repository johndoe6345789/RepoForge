package com.repoforge.ui.repo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import com.repoforge.ui.common.CodeHighlighter
import com.repoforge.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.repoforge.R
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.forge.WebLinks
import com.repoforge.data.model.Account
import com.repoforge.data.model.FileBlob
import com.repoforge.data.model.Repo
import com.repoforge.ui.common.Loadable
import com.repoforge.ui.common.LoadableContent
import com.repoforge.ui.common.MarkdownView
import com.repoforge.ui.common.copyToClipboard
import com.repoforge.ui.common.openUrl
import kotlinx.coroutines.CoroutineScope

class FileModel(
    scope: CoroutineScope,
    val account: Account,
    client: ForgeClient,
    val repo: Repo,
    val ref: String,
    val path: String,
) {
    val blob = Loadable(scope) { client.getFile(repo, ref, path) }
    var showSource by mutableStateOf(false)
    var wrapLines by mutableStateOf(false)
    val webUrl: String? get() = WebLinks.blob(account.type, repo, ref, path)
}

/** Files above this size show only their first lines, to keep scrolling smooth. */
private const val MAX_LINES = 20_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileScreen(model: FileModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val blob = model.blob.value

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(model.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                "${model.repo.name} @ ${model.ref}",
                                blob?.takeIf { !it.isBinary && !it.isImage }?.let { "${it.text.count { c -> c == '\n' } + 1} lines" },
                                blob?.let { formatSize(it.bytes.size.toLong()) },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (blob != null && blob.isMarkdown) {
                            DropdownMenuItem(
                                text = { Text(if (model.showSource) "Show rendered" else "Show source") },
                                onClick = { menuOpen = false; model.showSource = !model.showSource },
                            )
                        }
                        if (blob != null && !blob.isBinary) {
                            DropdownMenuItem(
                                text = { Text("Wrap lines") },
                                trailingIcon = { Checkbox(checked = model.wrapLines, onCheckedChange = null) },
                                onClick = { model.wrapLines = !model.wrapLines },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy contents") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), null) },
                                onClick = { menuOpen = false; copyToClipboard(context, "File contents", blob.text) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Copy path") },
                            onClick = { menuOpen = false; copyToClipboard(context, "Path", model.path) },
                        )
                        model.webUrl?.let { url ->
                            DropdownMenuItem(
                                text = { Text("Open in browser") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_open_in_browser), null) },
                                onClick = { menuOpen = false; openUrl(context, url) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LoadableContent(model.blob, Modifier.padding(padding)) { file ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when {
                    file.isImage -> ZoomableImage(file)
                    file.isBinary -> BinaryNotice(file) { model.webUrl?.let { openUrl(context, it) } }
                    file.isMarkdown && !model.showSource -> {
                        val type = model.account.type
                        val markdown = remember(file) {
                            WebLinks.rewriteImages(file.text, file.path) { WebLinks.raw(type, model.repo, model.ref, it) }
                        }
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            MarkdownView(markdown, resolveLink = { link ->
                                WebLinks.resolveRelative(link, file.path)?.let { WebLinks.blob(type, model.repo, model.ref, it) }
                            })
                        }
                    }
                    else -> CodeView(file, wrap = model.wrapLines)
                }
            }
        }
    }
}

@Composable
private fun BinaryNotice(file: FileBlob, onOpenInBrowser: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Binary file · ${formatSize(file.bytes.size.toLong())}", textAlign = TextAlign.Center)
        OutlinedButton(onClick = onOpenInBrowser) { Text("Open in browser") }
    }
}

@Composable
private fun ZoomableImage(file: FileBlob) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = file.bytes,
            contentDescription = file.name,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

@Composable
private fun CodeView(file: FileBlob, wrap: Boolean) {
    val text = file.text
    val dark = LocalDarkTheme.current
    val plain = remember(text) { text.removeSuffix("\n").split('\n') }
    // Highlighting runs off the main thread; plain text shows until it's ready.
    val highlighted by produceState<List<AnnotatedString>?>(null, text, dark) {
        value = withContext(Dispatchers.Default) {
            runCatching { CodeHighlighter.highlightLines(text, file.name, dark) }.getOrNull()
        }
    }
    val lineCount = minOf(plain.size, MAX_LINES)
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.5)
    // Sized to the widest line number so the gutter never wraps.
    val measurer = rememberTextMeasurer()
    val gutterWidth = with(LocalDensity.current) {
        measurer.measure("0".repeat(lineCount.toString().length), codeStyle).size.width.toDp() + 12.dp
    }
    // One horizontal scroll state shared by every line, so the whole file pans together.
    val hScroll = rememberScrollState()

    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            items(lineCount) { index ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${index + 1}",
                        style = codeStyle,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(gutterWidth).padding(end = 12.dp),
                    )
                    val line = highlighted?.getOrNull(index) ?: AnnotatedString(plain[index])
                    Text(
                        line,
                        style = codeStyle,
                        softWrap = wrap,
                        modifier = if (wrap) Modifier.padding(end = 8.dp) else Modifier.horizontalScroll(hScroll).padding(end = 16.dp),
                    )
                }
            }
            if (plain.size > MAX_LINES) {
                item {
                    Text(
                        "Showing the first $MAX_LINES of ${plain.size} lines.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
