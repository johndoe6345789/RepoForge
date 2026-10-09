package com.repoforge.data.forge

import com.repoforge.data.model.Branch
import com.repoforge.data.model.Comment
import com.repoforge.data.model.Commit
import com.repoforge.data.model.EntryType
import com.repoforge.data.model.FileBlob
import com.repoforge.data.model.FileDiff
import com.repoforge.data.model.Issue
import com.repoforge.data.model.Page
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
