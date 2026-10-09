package com.repoforge.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** A git failure with a message fit to show the user. */
class GitException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Progress of a long git operation, e.g. "Receiving objects" 120/400. */
data class GitProgress(val task: String, val done: Int, val total: Int) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

enum class ChangeKind { ADDED, MODIFIED, DELETED, UNTRACKED, CONFLICTING }

data class LocalChange(val path: String, val kind: ChangeKind)

data class LocalStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val changes: List<LocalChange>,
) {
    val isClean: Boolean get() = changes.isEmpty()
}

data class PullOutcome(val upToDate: Boolean, val conflicts: List<String>, val description: String)

/**
 * Blocking JGit operations run on the IO dispatcher. Operations that talk to a remote take a
 * [CredentialsProvider]; null means anonymous (public repositories over HTTPS).
 */
class GitService {

    suspend fun clone(
        url: String,
        dir: File,
        branch: String?,
        credentials: CredentialsProvider?,
        onProgress: (GitProgress) -> Unit,
    ): Unit = git("Clone failed") {
        if (dir.exists() && dir.list()?.isNotEmpty() == true) throw GitException("${dir.path} already exists and isn't empty")
        val context = currentCoroutineContext()
        try {
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir)
                .apply { if (!branch.isNullOrBlank()) setBranch(branch) }
                .setCredentialsProvider(credentials)
                .setProgressMonitor(CoroutineProgress(context, onProgress))
                .call()
                .use { git -> configureClone(git) }
        } catch (e: Exception) {
            // Leave nothing half-cloned behind (including after cancellation).
            dir.deleteRecursively()
            throw e
        }
    }

    /** Settings that suit a phone: no automatic gc (it needs process APIs Android lacks). */
    private fun configureClone(git: Git) {
        val config = git.repository.config
        config.setInt(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTO, 0)
        config.save()
    }

    suspend fun status(dir: File): LocalStatus = git("Couldn't read the repository") {
        Git.open(dir).use { git ->
            val repo = git.repository
            val branch = repo.branch ?: "(detached)"
            val tracking = runCatching { BranchTrackingStatus.of(repo, branch) }.getOrNull()
            val status = git.status().call()
            val changes = buildList {
                status.conflicting.forEach { add(LocalChange(it, ChangeKind.CONFLICTING)) }
                (status.added).forEach { add(LocalChange(it, ChangeKind.ADDED)) }
                (status.changed + status.modified).forEach { add(LocalChange(it, ChangeKind.MODIFIED)) }
                (status.removed + status.missing).forEach { add(LocalChange(it, ChangeKind.DELETED)) }
                status.untracked.forEach { add(LocalChange(it, ChangeKind.UNTRACKED)) }
            }.distinctBy { it.path }.sortedBy { it.path }
            LocalStatus(branch, tracking?.aheadCount ?: 0, tracking?.behindCount ?: 0, changes)
        }
    }

    suspend fun pull(dir: File, credentials: CredentialsProvider?, onProgress: (GitProgress) -> Unit): PullOutcome =
        git("Pull failed") {
            val context = currentCoroutineContext()
            Git.open(dir).use { git ->
                val result = git.pull()
                    .setCredentialsProvider(credentials)
                    .setProgressMonitor(CoroutineProgress(context, onProgress))
                    .call()
                val merge = result.mergeResult
                when {
                    !result.isSuccessful && merge?.mergeStatus == MergeResult.MergeStatus.CONFLICTING ->
                        PullOutcome(false, merge.conflicts?.keys?.sorted().orEmpty(), "Pulled with conflicts")
                    !result.isSuccessful -> throw GitException("Pull failed: ${merge?.mergeStatus ?: result.rebaseResult?.status}")
                    merge?.mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> PullOutcome(true, emptyList(), "Already up to date")
                    else -> PullOutcome(false, emptyList(), "Updated (${merge?.mergeStatus?.toString()?.lowercase()?.replace('_', ' ') ?: "merged"})")
                }
            }
        }

    /** Stages every change (including deletions and new files) and commits. Returns the new commit id. */
    suspend fun commitAll(dir: File, message: String, author: PersonIdent): String = git("Commit failed") {
        Git.open(dir).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            git.commit().setMessage(message).setAuthor(author).setCommitter(author).call().name
        }
    }

    /** Pushes the current branch to `origin`, failing if the server rejects it. */
    suspend fun push(dir: File, credentials: CredentialsProvider?, onProgress: (GitProgress) -> Unit): Unit =
        git("Push failed") {
            val context = currentCoroutineContext()
            Git.open(dir).use { git ->
                val branch = git.repository.branch
                val results = git.push()
                    .setRemote(Constants.DEFAULT_REMOTE_NAME)
                    .add(branch)
                    .setCredentialsProvider(credentials)
                    .setProgressMonitor(CoroutineProgress(context, onProgress))
                    .call()
                checkPushResults(results.flatMap { it.remoteUpdates })
            }
        }

    /** Local branches first, then remote branches that have no local counterpart. */
    suspend fun branches(dir: File): List<String> = git("Couldn't list branches") {
        Git.open(dir).use { git ->
            val refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call().map { it.name }
            val local = refs.filter { it.startsWith(Constants.R_HEADS) }.map { it.removePrefix(Constants.R_HEADS) }
            val remote = refs.filter { it.startsWith("${Constants.R_REMOTES}origin/") }
                .map { it.removePrefix("${Constants.R_REMOTES}origin/") }
                .filter { it != Constants.HEAD && it !in local }
            local.sorted() + remote.sorted()
        }
    }

    /** Switches branch, creating a local tracking branch from `origin` when needed. */
    suspend fun checkout(dir: File, branch: String): Unit = git("Couldn't switch branch") {
        Git.open(dir).use { git ->
            val exists = git.repository.findRef(Constants.R_HEADS + branch) != null
            git.checkout().setName(branch).apply {
                if (!exists) {
                    setCreateBranch(true)
                    setStartPoint("origin/$branch")
                    setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                }
            }.call()
        }
    }

    /** Throws away uncommitted changes, including untracked files. */
    suspend fun discardChanges(dir: File): Unit = git("Couldn't discard changes") {
        Git.open(dir).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            git.clean().setCleanDirectories(true).call()
        }
    }

    companion object {
        fun checkPushResults(updates: Collection<RemoteRefUpdate>) {
            val failed = updates.filter {
                it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
            }
            if (failed.isNotEmpty()) {
                val detail = failed.joinToString { u ->
                    val reason = when (u.status) {
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> "the branch has new commits; pull first"
                        RemoteRefUpdate.Status.REJECTED_NODELETE, RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                            u.message ?: "rejected by the server"
                        else -> u.message ?: u.status.name.lowercase().replace('_', ' ')
                    }
                    "${u.remoteName.removePrefix(Constants.R_HEADS)}: $reason"
                }
                throw GitException("Push rejected — $detail")
            }
        }

        /** Runs a JGit call on the IO dispatcher, turning JGit's exceptions into [GitException]s. */
        internal suspend fun <T> git(failure: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: GitException) {
                throw e
            } catch (e: GitAPIException) {
                throw GitException("$failure: ${friendly(e)}", e)
            } catch (e: org.eclipse.jgit.errors.TransportException) {
                throw GitException("$failure: ${friendly(e)}", e)
            } catch (e: IOException) {
                throw GitException("$failure: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }

        private fun friendly(e: Throwable): String {
            val message = generateSequence(e) { it.cause }.mapNotNull { it.message }.firstOrNull().orEmpty()
            return when {
                "not authorized" in message.lowercase() || "authentication" in message.lowercase() ->
                    "the server rejected the credentials — the token may lack write access"
                "cancel" in message.lowercase() -> "cancelled"
                else -> message.ifBlank { e.javaClass.simpleName }
            }
        }
    }
}

/** Forwards JGit progress to a callback and stops the operation when the coroutine is cancelled. */
internal class CoroutineProgress(
    private val context: CoroutineContext,
    private val onProgress: (GitProgress) -> Unit,
) : ProgressMonitor {
    private var task = ""
    private var total = 0
    private var done = 0
    private var lastReport = 0L

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(title: String, totalWork: Int) {
        task = title
        total = if (totalWork == ProgressMonitor.UNKNOWN) 0 else totalWork
        done = 0
        onProgress(GitProgress(task, done, total))
    }

    override fun update(completed: Int) {
        done += completed
        val now = System.currentTimeMillis()
        if (now - lastReport > 100) { // throttle UI updates
            lastReport = now
            onProgress(GitProgress(task, done, total))
        }
    }

    override fun endTask() = onProgress(GitProgress(task, total, total))

    override fun isCancelled(): Boolean = !context.isActive
}
