package com.repoforge.data.forge

import com.repoforge.data.model.Branch
import com.repoforge.data.model.CiArtifact
import com.repoforge.data.model.CiFeatures
import com.repoforge.data.model.CiJob
import com.repoforge.data.model.CiRun
import java.io.OutputStream
import com.repoforge.data.model.Comment
import com.repoforge.data.model.Commit
import com.repoforge.data.model.EntryType
import com.repoforge.data.model.FileBlob
import com.repoforge.data.model.FileDiff
import com.repoforge.data.model.Issue
import com.repoforge.data.model.MergeMethod
import com.repoforge.data.model.MergeOutcome
import com.repoforge.data.model.Page
import com.repoforge.data.model.PullDetail
import com.repoforge.data.model.Repo
import com.repoforge.data.model.StateFilter
import com.repoforge.data.model.TreeEntry
import com.repoforge.data.model.User

/** The operations RepoForge needs from a hosting service, implemented once per provider. */
interface ForgeClient {
    suspend fun currentUser(): User

    /** Repositories the signed-in user owns or belongs to, most recently updated first. */
    suspend fun listRepos(page: Int): Page<Repo>

    suspend fun searchRepos(query: String, page: Int): Page<Repo>

    suspend fun listBranches(repo: Repo): List<Branch>

    /** Entries of the directory at [path] ("" for the root), directories first. */
    suspend fun listTree(repo: Repo, ref: String, path: String): List<TreeEntry>

    suspend fun getFile(repo: Repo, ref: String, path: String): FileBlob

    suspend fun listCommits(repo: Repo, ref: String, page: Int): Page<Commit>

    /** Files changed by one commit, compared with its first parent. */
    suspend fun getCommitDiff(repo: Repo, sha: String): List<FileDiff>

    /** Files changed by a pull/merge request. */
    suspend fun getPullRequestDiff(repo: Repo, pull: Issue): List<FileDiff>

    suspend fun listIssues(repo: Repo, state: StateFilter, page: Int): Page<Issue>

    suspend fun listPullRequests(repo: Repo, state: StateFilter, page: Int): Page<Issue>

    suspend fun listComments(repo: Repo, issue: Issue): List<Comment>

    suspend fun addComment(repo: Repo, issue: Issue, body: String): Comment

    suspend fun createIssue(repo: Repo, title: String, body: String): Issue

    /** Merge methods the service offers (a repository may still disallow some). */
    val mergeMethods: List<MergeMethod>

    /** Fresh state of a pull request, including whether it can be merged. */
    suspend fun getPullRequest(repo: Repo, number: Long): PullDetail

    /** Merges the pull request, optionally deleting its branch afterwards. */
    suspend fun mergePullRequest(
        repo: Repo,
        detail: PullDetail,
        method: MergeMethod,
        title: String?,
        message: String?,
        deleteBranch: Boolean,
    ): MergeOutcome

    /** Deletes the pull request's branch, e.g. after it was merged. */
    suspend fun deleteBranch(repo: Repo, detail: PullDetail)

    /** What this service's CI API supports. */
    val ci: CiFeatures

    /** CI runs/pipelines, newest first. */
    suspend fun listCiRuns(repo: Repo, page: Int): Page<CiRun>

    /** Fresh state of one run, for following it while it's in progress. */
    suspend fun getCiRun(repo: Repo, id: String): CiRun

    suspend fun listCiJobs(repo: Repo, run: CiRun): List<CiJob>

    /** A job's full log as plain text (it may contain ANSI colour codes). */
    suspend fun getCiJobLog(repo: Repo, run: CiRun, job: CiJob): String

    suspend fun listCiArtifacts(repo: Repo, run: CiRun): List<CiArtifact>

    /** Streams an artifact's archive into [out]; returns the number of bytes written. */
    suspend fun downloadCiArtifact(artifact: CiArtifact, out: OutputStream, onProgress: (Long, Long?) -> Unit): Long

    /** The README at the repository root, or null when there isn't one. */
    suspend fun getReadme(repo: Repo, ref: String): FileBlob? {
        val readme = findReadme(listTree(repo, ref, "")) ?: return null
        return getFile(repo, ref, readme.path)
    }

    companion object {
        const val PAGE_SIZE = 30

        fun findReadme(entries: List<TreeEntry>): TreeEntry? = entries
            .filter { it.type == EntryType.FILE && it.name.lowercase().let { n -> n == "readme" || n.startsWith("readme.") } }
            .minByOrNull { if (it.name.lowercase().endsWith(".md")) 0 else 1 }

        fun sortEntries(entries: List<TreeEntry>): List<TreeEntry> =
            entries.sortedWith(compareBy<TreeEntry> { it.type != EntryType.DIR }.thenBy { it.name.lowercase() })
    }
}
