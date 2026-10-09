package com.repoforge.data.git

import com.repoforge.data.git.GitService.Companion.git
import kotlinx.coroutines.currentCoroutineContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import java.io.File

/** Which pull request branch to update, and from where. */
data class MergeTarget(
    /** Clone URL of the repository the pull request targets. */
    val cloneUrl: String,
    val baseBranch: String,
    val headBranch: String,
    /** Clone URL of the pull request's branch when it lives in a fork; null when it's [cloneUrl]. */
    val headCloneUrl: String? = null,
) {
    val isFork: Boolean get() = headCloneUrl != null && headCloneUrl != cloneUrl
}

enum class ConflictKind { CONTENT, DELETED_IN_HEAD, DELETED_IN_BASE, BINARY }

/**
 * One conflicted file. "Head" is the pull request branch being updated; "base" is the branch it
 * merges into, whose new commits are being brought in.
 */
data class ConflictFile(
    val path: String,
    val kind: ConflictKind,
    val ancestor: String?,
    val head: String?,
    val base: String?,
    /** The working-tree file with conflict markers, for [ConflictKind.CONTENT]. */
    val withMarkers: String?,
)

sealed interface PrepareResult {
    /** The pull request branch already contains the base branch. */
    data object UpToDate : PrepareResult

    /** The base branch merged in without conflicts; commit and push to update the pull request. */
    data object Clean : PrepareResult

    data class Conflicts(val files: List<ConflictFile>) : PrepareResult
}

/**
 * A private working copy used to update a pull request branch with its base branch, the same
 * thing GitHub's "Update branch" / "Resolve conflicts" does, but on the device.
 */
class MergeWorkspace(
    private val dir: File,
    private val target: MergeTarget,
    private val credentials: CredentialsProvider?,
) {
    private val headRemote = if (target.isFork) "head" else Constants.DEFAULT_REMOTE_NAME

    suspend fun prepare(onProgress: (GitProgress) -> Unit): PrepareResult = git("Couldn't prepare the merge") {
        val progress = CoroutineProgress(currentCoroutineContext(), onProgress)
        if (!File(dir, ".git").exists()) {
            dir.deleteRecursively()
            Git.cloneRepository()
                .setURI(target.cloneUrl)
                .setDirectory(dir)
                .setNoCheckout(true)
                .setCredentialsProvider(credentials)
                .setProgressMonitor(progress)
                .call()
                .use { git ->
                    git.repository.config.apply {
                        setInt(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTO, 0)
                    }.save()
                }
        }
        Git.open(dir).use { git ->
            val repo = git.repository
            // Start from a clean slate: a previous session may have stopped half-way.
            if (repo.resolve(Constants.HEAD) != null) {
                git.reset().setMode(ResetCommand.ResetType.HARD).call()
                git.clean().setCleanDirectories(true).setForce(true).call()
            }

            git.fetch().setRemote(Constants.DEFAULT_REMOTE_NAME)
                .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                .setRemoveDeletedRefs(true)
                .setCredentialsProvider(credentials)
                .setProgressMonitor(progress)
                .call()
            if (target.isFork) {
                setRemote(git, headRemote, target.headCloneUrl!!)
                git.fetch().setRemote(headRemote)
                    .setRefSpecs(RefSpec("+refs/heads/${target.headBranch}:refs/remotes/$headRemote/${target.headBranch}"))
                    .setCredentialsProvider(credentials)
                    .setProgressMonitor(progress)
                    .call()
            }

            val headRef = "${Constants.R_REMOTES}$headRemote/${target.headBranch}"
            val baseRef = "${Constants.R_REMOTES}origin/${target.baseBranch}"
            val baseId: ObjectId = repo.resolve(baseRef) ?: throw GitException("Branch ${target.baseBranch} wasn't found")
            repo.resolve(headRef) ?: throw GitException("Branch ${target.headBranch} wasn't found")

            // Work on a detached HEAD at the pull request branch; pushing HEAD updates it.
            git.checkout().setName(headRef).setForced(true).call()
            val result = git.merge()
                .include(baseId)
                .setCommit(false)
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setMessage(defaultMessage())
                .call()
            when (result.mergeStatus) {
                MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> PrepareResult.UpToDate
                MergeResult.MergeStatus.MERGED_NOT_COMMITTED, MergeResult.MergeStatus.MERGED -> PrepareResult.Clean
                MergeResult.MergeStatus.CONFLICTING -> PrepareResult.Conflicts(readConflicts(git))
                else -> throw GitException("Merge stopped: ${result.mergeStatus.toString().lowercase().replace('_', ' ')}")
            }
        }
    }

    /** Adds the remote, or points it at [url] if it already exists (the fork may have moved). */
    private fun setRemote(git: Git, name: String, url: String) {
        if (git.repository.config.getString("remote", name, "url") == null) {
            git.remoteAdd().setName(name).setUri(URIish(url)).call()
        } else {
            git.remoteSetUrl().setRemoteName(name).setRemoteUri(URIish(url)).call()
        }
    }

    /** Reads the three versions of every unmerged path from the index (stages 1, 2 and 3). */
    private fun readConflicts(git: Git): List<ConflictFile> {
        val repo = git.repository
        val stages = linkedMapOf<String, Array<ObjectId?>>()
        val index = repo.readDirCache()
        for (i in 0 until index.entryCount) {
            val entry = index.getEntry(i)
            if (entry.stage == 0) continue
            stages.getOrPut(entry.pathString) { arrayOfNulls(4) }[entry.stage] = entry.objectId
        }
        return stages.map { (path, ids) ->
            fun load(id: ObjectId?) = id?.let { repo.open(it, Constants.OBJ_BLOB).bytes }
            val ancestor = load(ids[1])
            val head = load(ids[2])
            val base = load(ids[3])
            val binary = listOfNotNull(ancestor, head, base).any { RawText.isBinary(it) }
            val working = File(dir, path).takeIf { it.isFile }?.readText()
            ConflictFile(
                path = path,
                kind = when {
                    head == null -> ConflictKind.DELETED_IN_HEAD
                    base == null -> ConflictKind.DELETED_IN_BASE
                    binary -> ConflictKind.BINARY
                    else -> ConflictKind.CONTENT
                },
                ancestor = ancestor?.takeUnless { binary }?.toString(Charsets.UTF_8),
                head = head?.takeUnless { binary }?.toString(Charsets.UTF_8),
                base = base?.takeUnless { binary }?.toString(Charsets.UTF_8),
                withMarkers = working.takeUnless { binary },
            )
        }
    }

    /** Records how a file is resolved: [content] is the new text, or null to delete the file. */
    suspend fun resolve(path: String, content: String?): Unit = git("Couldn't save the resolution") {
        Git.open(dir).use { git ->
            val file = File(dir, path)
            if (content == null) {
                git.rm().addFilepattern(path).call()
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(content)
                git.add().addFilepattern(path).call()
            }
        }
    }

    /** Keeps one side's version of a file as-is (also works for binary files). */
    suspend fun resolveWithSide(path: String, keepHead: Boolean): Unit = git("Couldn't save the resolution") {
        Git.open(dir).use { git ->
            val stage = if (keepHead) 2 else 3
            val index = git.repository.readDirCache()
            val entry = (0 until index.entryCount).map { index.getEntry(it) }
                .firstOrNull { it.pathString == path && it.stage == stage }
            if (entry == null) {
                git.rm().addFilepattern(path).call()
                File(dir, path).delete()
            } else {
                val file = File(dir, path)
                file.parentFile?.mkdirs()
                file.writeBytes(git.repository.open(entry.objectId, Constants.OBJ_BLOB).bytes)
                git.add().addFilepattern(path).call()
            }
        }
    }

    /** Commits the merge and pushes it to the pull request branch. Returns the merge commit id. */
    suspend fun commitAndPush(message: String, author: PersonIdent, onProgress: (GitProgress) -> Unit): String =
        git("Couldn't push the merge") {
            val progress = CoroutineProgress(currentCoroutineContext(), onProgress)
            Git.open(dir).use { git ->
                val unresolved = git.status().call().conflicting
                if (unresolved.isNotEmpty()) throw GitException("Still unresolved: ${unresolved.sorted().joinToString()}")
                val commit = git.commit().setMessage(message).setAuthor(author).setCommitter(author).call()
                val results = git.push()
                    .setRemote(headRemote)
                    .setRefSpecs(RefSpec("${commit.name}:${Constants.R_HEADS}${target.headBranch}"))
                    .setCredentialsProvider(credentials)
                    .setProgressMonitor(progress)
                    .call()
                GitService.checkPushResults(results.flatMap { it.remoteUpdates })
                commit.name
            }
        }

    /** Throws away the in-progress merge. */
    suspend fun abort(): Unit = git("Couldn't cancel the merge") {
        if (!File(dir, ".git").exists()) return@git
        Git.open(dir).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).call()
            git.clean().setCleanDirectories(true).setForce(true).call()
        }
    }

    fun defaultMessage(): String = "Merge branch '${target.baseBranch}' into ${target.headBranch}"
}
