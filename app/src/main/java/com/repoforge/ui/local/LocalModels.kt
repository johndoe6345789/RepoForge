package com.repoforge.ui.local

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.repoforge.data.git.GitProgress
import com.repoforge.data.git.GitService
import com.repoforge.data.git.LocalClone
import com.repoforge.data.model.Account
import com.repoforge.data.model.Repo
import com.repoforge.ui.common.Loadable
import com.repoforge.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.File
import java.util.UUID

/** A clone in progress; it keeps running if the user leaves the repository page. */
class CloneTask(
    scope: CoroutineScope,
    git: GitService,
    val account: Account,
    val repo: Repo,
    val dir: File,
    credentials: CredentialsProvider?,
    onCloned: (LocalClone) -> Unit,
) {
    var progress by mutableStateOf<GitProgress?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<LocalClone?>(null)
        private set
    val running: Boolean get() = result == null && error == null

    private val job: Job = scope.launch {
        try {
            val url = repo.cloneHttps ?: throw IllegalStateException("This repository has no HTTPS clone URL")
            git.clone(url, dir, branch = null, credentials = credentials) { progress = it }
            val clone = LocalClone(
                id = UUID.randomUUID().toString(),
                accountId = account.id,
                fullName = repo.fullName,
                name = repo.name,
                path = dir.absolutePath,
                cloneUrl = url,
                webUrl = repo.webUrl,
                clonedAt = System.currentTimeMillis(),
            )
            onCloned(clone)
            result = clone
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }

    fun cancel() = job.cancel()
}

enum class LocalTab { CHANGES, FILES }

data class LocalEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)

/** A working copy on the device: status, commit, pull, push, branches and a file browser. */
class LocalRepoModel(
    private val scope: CoroutineScope,
    val clone: LocalClone,
    private val git: GitService,
    /** Looked up on each use, so a removed account falls back to anonymous access. */
    private val credentials: () -> CredentialsProvider?,
    private val author: () -> PersonIdent,
) {
    val status = Loadable(scope) { git.status(clone.dir) }
    val branches = Loadable(scope) { git.branches(clone.dir) }
    var tab by mutableStateOf(LocalTab.CHANGES)
    var path by mutableStateOf("")
        private set
    var commitMessage by mutableStateOf("")
    /** What's running, e.g. "Pulling", or null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<GitProgress?>(null)
        private set
    /** A one-off message for the snackbar. */
    var notice by mutableStateOf<String?>(null)
    /** Bumped whenever the working tree may have changed, so the file list reloads. */
    var treeVersion by mutableIntStateOf(0)
        private set

    fun open(dir: String) {
        path = dir
    }

    fun up() {
        path = path.substringBeforeLast('/', "")
    }

    suspend fun list(dir: String): List<LocalEntry> = withContext(Dispatchers.IO) {
        val folder = if (dir.isEmpty()) clone.dir else File(clone.dir, dir)
        (folder.listFiles() ?: emptyArray())
            .filter { it.name != ".git" }
            .map { LocalEntry(it.name, if (dir.isEmpty()) it.name else "$dir/${it.name}", it.isDirectory, it.length()) }
            .sortedWith(compareBy<LocalEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    fun refresh() {
        status.refresh()
        treeVersion++
    }

    fun pull() = run("Pulling") {
        val outcome = git.pull(clone.dir, credentials()) { progress = it }
        notice = if (outcome.conflicts.isNotEmpty()) {
            "Pulled with conflicts in ${outcome.conflicts.size} file${if (outcome.conflicts.size == 1) "" else "s"}"
        } else {
            outcome.description
        }
    }

    fun push() = run("Pushing") {
        git.push(clone.dir, credentials()) { progress = it }
        notice = "Pushed ${status.value?.branch ?: "branch"}"
    }

    fun commit() {
        val message = commitMessage.trim()
        if (message.isEmpty()) return
        run("Committing") {
            val id = git.commitAll(clone.dir, message, author())
            commitMessage = ""
            notice = "Committed ${id.take(7)}"
        }
    }

    fun checkout(branch: String) = run("Switching branch") {
        git.checkout(clone.dir, branch)
        branches.refresh()
        path = ""
        notice = "Switched to $branch"
    }

    fun discardChanges() = run("Discarding changes") {
        git.discardChanges(clone.dir)
        notice = "Changes discarded"
    }

    private fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        progress = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.userMessage()
            } finally {
                busy = null
                progress = null
                refresh()
            }
        }
    }
}
