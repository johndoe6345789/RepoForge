package com.repoforge.ui.ci

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.repoforge.data.ci.ArchiveEntry
import com.repoforge.data.ci.ArtifactFiles
import com.repoforge.data.ci.CiLogParser
import com.repoforge.data.ci.LogLineKind
import com.repoforge.data.ci.ParsedLog
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.forge.safeFileName
import com.repoforge.data.model.CiArtifact
import com.repoforge.data.model.CiJob
import com.repoforge.data.model.CiRun
import com.repoforge.data.model.Repo
import com.repoforge.ui.common.Loadable
import com.repoforge.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where one artifact's download stands. */
sealed interface ArtifactDownload {
    data class Running(val done: Long, val total: Long?) : ArtifactDownload {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
    }

    /** Downloaded; [entries] lists the archive's files (empty if it isn't a zip). */
    data class Done(val archive: File, val entries: List<ArchiveEntry>) : ArtifactDownload

    data class Failed(val message: String) : ArtifactDownload
}

/** One CI run: its jobs, artifacts and their downloads. Follows the run while it's in progress. */
class CiRunModel(
    private val scope: CoroutineScope,
    val client: ForgeClient,
    val repo: Repo,
    initial: CiRun,
    private val files: ArtifactFiles,
) {
    var run by mutableStateOf(initial)
        private set
    val jobs = Loadable(scope) { client.listCiJobs(repo, run) }
    val artifacts = Loadable(scope) { if (client.ci.artifacts) client.listCiArtifacts(repo, run) else emptyList() }
    val downloads = mutableStateMapOf<String, ArtifactDownload>()
    private val downloadJobs = mutableMapOf<String, Job>()

    /** A one-off message for the snackbar. */
    var notice by mutableStateOf<String?>(null)

    fun refresh() {
        scope.launch {
            runCatching { client.getCiRun(repo, run.id) }.onSuccess { run = it }
            jobs.refresh()
            artifacts.refresh()
        }
    }

    fun download(artifact: CiArtifact) {
        if (downloads[artifact.id] is ArtifactDownload.Running) return
        downloads[artifact.id] = ArtifactDownload.Running(0, artifact.sizeBytes)
        downloadJobs[artifact.id] = scope.launch {
            val target = files.archive(artifact)
            val partial = File(target.path + ".part")
            try {
                files.prune()
                withContext(Dispatchers.IO) { target.parentFile!!.mkdirs() }
                withContext(Dispatchers.IO) {
                    partial.outputStream().use { out ->
                        client.downloadCiArtifact(artifact, out) { done, total ->
                            downloads[artifact.id] = ArtifactDownload.Running(done, total ?: artifact.sizeBytes)
                        }
                    }
                    if (!partial.renameTo(target)) error("Couldn't store the download")
                }
                downloads[artifact.id] = ArtifactDownload.Done(target, files.entries(target))
            } catch (e: CancellationException) {
                downloads.remove(artifact.id)
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { partial.delete() }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { partial.delete() }
                downloads[artifact.id] = ArtifactDownload.Failed(e.userMessage())
            }
        }
    }

    fun cancelDownload(artifact: CiArtifact) {
        downloadJobs.remove(artifact.id)?.cancel()
    }

    /** Saves the whole archive, or one file from it, to Downloads. */
    fun save(artifact: CiArtifact, entry: ArchiveEntry?) {
        val done = downloads[artifact.id] as? ArtifactDownload.Done ?: return
        scope.launch {
            notice = try {
                val file = if (entry == null) done.archive else files.extract(done.archive, entry)
                "Saved to ${files.saveToDownloads(file)}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Couldn't save: ${e.userMessage()}"
            }
        }
    }

    /** Extracts a file and hands it to [open] (another app, or the package installer for an APK). */
    fun open(artifact: CiArtifact, entry: ArchiveEntry?, open: (android.content.Intent) -> Unit) {
        val done = downloads[artifact.id] as? ArtifactDownload.Done ?: return
        scope.launch {
            try {
                val file = if (entry == null) done.archive else files.extract(done.archive, entry)
                open(files.viewIntent(file))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = "Couldn't open: ${e.userMessage()}"
            }
        }
    }
}

/** A job's log, parsed into lines with collapsible groups. */
class CiLogModel(
    scope: CoroutineScope,
    val client: ForgeClient,
    val repo: Repo,
    val run: CiRun,
    val job: CiJob,
    private val cacheDir: File,
) {
    private var raw: String = ""
    val log = Loadable(scope) {
        val text = client.getCiJobLog(repo, run, job)
        raw = text
        withContext(Dispatchers.Default) { CiLogParser.parse(text) }.also { parsed ->
            expanded.clear()
            parsed.groupsWithErrors.forEach { expanded[it] = true }
        }
    }
    /** Groups the user opened (and those holding errors); the rest start collapsed. */
    val expanded = mutableStateMapOf<Int, Boolean>()
    var wrap by mutableStateOf(false)

    fun toggle(group: Int) {
        expanded[group] = expanded[group] != true
    }

    fun expandAll(parsed: ParsedLog) {
        parsed.lines.filter { it.kind == LogLineKind.GROUP }.forEach { expanded[it.group!!] = true }
    }

    fun collapseAll() = expanded.clear()

    /** Lines to show: group headers always, their contents when expanded. */
    fun visible(parsed: ParsedLog) = parsed.lines.filter { line ->
        line.kind == LogLineKind.GROUP || line.group == null || expanded[line.group] == true
    }

    /** Writes the raw log to the cache for sharing (too big for the clipboard). */
    suspend fun logFile(): File = withContext(Dispatchers.IO) {
        File(cacheDir, "logs").apply { mkdirs() }.let { dir ->
            File(dir, "${safeFileName(job.name)}-${job.id.take(12).let(::safeFileName)}.log").apply { writeText(raw) }
        }
    }
}
