package com.repoforge.data.forge

import com.repoforge.data.forge.ForgeClient.Companion.PAGE_SIZE
import com.repoforge.data.model.Branch
import com.repoforge.data.model.Comment
import com.repoforge.data.model.Commit
import com.repoforge.data.model.EntryType
import com.repoforge.data.model.FileBlob
import com.repoforge.data.model.FileDiff
import com.repoforge.data.model.Issue
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.MergeMethod
import com.repoforge.data.model.MergeOutcome
import com.repoforge.data.model.Mergeability
import com.repoforge.data.model.PullDetail
import com.repoforge.data.model.Page
import com.repoforge.data.model.Repo
import com.repoforge.data.model.StateFilter
import com.repoforge.data.model.TreeEntry
import com.repoforge.data.model.User
import com.repoforge.data.net.ForgeException
import com.repoforge.data.net.Http
import com.repoforge.data.net.UrlSpec
import com.repoforge.data.net.arr
import com.repoforge.data.net.asObject
import com.repoforge.data.net.bool
import com.repoforge.data.net.instant
import com.repoforge.data.net.int
import com.repoforge.data.net.long
import com.repoforge.data.net.obj
import com.repoforge.data.net.objects
import com.repoforge.data.net.path
import com.repoforge.data.net.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Bitbucket Cloud (api.bitbucket.org/2.0). Authenticates with an Atlassian API token plus the
 * account e-mail (Basic auth), or with a repository/workspace access token (Bearer) when no
 * e-mail is given.
 */
class BitbucketClient(client: OkHttpClient, apiBase: String, email: String?, token: String) : ForgeClient {

    private val http = Http(client, apiBase) {
        when {
            token.isEmpty() -> Unit
            !email.isNullOrBlank() -> header("Authorization", Credentials.basic(email, token))
            else -> header("Authorization", "Bearer $token")
        }
    }

    /**
     * Bitbucket has no cross-workspace repository listing, so repositories are read one
     * workspace at a time. [cursors] maps each page number handed out to the
     * (workspace index, workspace page) it continues from.
     */
    private class WorkspaceCursor(val query: String?) {
        var workspaces: List<String>? = null
        val cursors = mutableMapOf(1 to (0 to 1))
    }

    private val cursorLock = Mutex()
    private var cursor = WorkspaceCursor(null)
    private val refHashes = mutableMapOf<String, String>()

    override suspend fun currentUser(): User = parseUser(http.getJson { seg("user") }.json.asObject())

    override suspend fun listRepos(page: Int): Page<Repo> = listAcrossWorkspaces(null, page)

    override suspend fun searchRepos(query: String, page: Int): Page<Repo> = listAcrossWorkspaces(query, page)

    private suspend fun listAcrossWorkspaces(query: String?, page: Int): Page<Repo> = cursorLock.withLock {
        if (page == 1 || cursor.query != query) cursor = WorkspaceCursor(query)
        val workspaces = cursor.workspaces ?: loadWorkspaces().also { cursor.workspaces = it }
        val (wsIndex, wsPage) = cursor.cursors[page] ?: return@withLock Page(emptyList(), null)
        if (wsIndex >= workspaces.size) return@withLock Page(emptyList(), null)

        val json = http.getJson {
            seg("repositories", workspaces[wsIndex]); q("role", "member"); q("sort", "-updated_on")
            if (query != null) q("q", "name ~ \"${query.replace("\"", "")}\"")
            q("pagelen", PAGE_SIZE); q("page", wsPage)
        }.json.asObject()

        val next = if (json.str("next") != null) wsIndex to wsPage + 1 else wsIndex + 1 to 1
        val hasMore = next.first < workspaces.size
        if (hasMore) cursor.cursors[page + 1] = next
        Page(json.arr("values")?.objects().orEmpty().map(::parseRepo), if (hasMore) page + 1 else null)
    }

    private suspend fun loadWorkspaces(): List<String> {
        val slugs = mutableListOf<String>()
        var url: HttpUrl? = http.url { seg("user", "workspaces"); q("pagelen", 100) }
        var pages = 0
        while (url != null && pages++ < MAX_PAGES) {
            val json = http.get(url).json.asObject()
            json.arr("values")?.objects().orEmpty().mapNotNullTo(slugs) { it.obj("workspace")?.str("slug") }
            url = json.str("next")?.toHttpUrl()
        }
        return slugs
    }

    override suspend fun listBranches(repo: Repo): List<Branch> {
        val values = collectPages(http.url { repoPath(repo); seg("refs", "branches"); q("pagelen", 100) })
        return values.map { Branch(it.str("name").orEmpty(), it.obj("target")?.str("hash")) }
    }

    /** Bitbucket can't address branch names containing `/` in paths, so resolve those to a hash. */
    private suspend fun resolveRef(repo: Repo, ref: String): String {
        if ('/' !in ref) return ref
        val key = "${repo.apiId}@$ref"
        refHashes[key]?.let { return it }
        val json = http.getJson { repoPath(repo); seg("refs", "branches", ref) }.json.asObject()
        val hash = json.obj("target")?.str("hash") ?: return ref
        refHashes[key] = hash
        return hash
    }

    override suspend fun listTree(repo: Repo, ref: String, path: String): List<TreeEntry> {
        val commit = resolveRef(repo, ref)
        // A trailing slash asks for the directory listing rather than file contents.
        val values = collectPages(http.url { repoPath(repo); seg("src", commit); path(path); seg(""); q("pagelen", 100) })
        return ForgeClient.sortEntries(values.map { item ->
            val fullPath = item.str("path").orEmpty()
            val attributes = item.arr("attributes")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            TreeEntry(
                name = fullPath.substringAfterLast('/'),
                path = fullPath,
                type = when {
                    item.str("type") == "commit_directory" -> EntryType.DIR
                    "link" in attributes -> EntryType.SYMLINK
                    "subrepository" in attributes -> EntryType.SUBMODULE
                    else -> EntryType.FILE
                },
                size = item.long("size"),
            )
        })
    }

    override suspend fun getFile(repo: Repo, ref: String, path: String): FileBlob {
        val commit = resolveRef(repo, ref)
        val url = http.url { repoPath(repo); seg("src", commit); path(path) }
        return FileBlob(path, http.get(url, accept = "*/*").bytes)
    }

    override suspend fun listCommits(repo: Repo, ref: String, page: Int): Page<Commit> {
        val commit = resolveRef(repo, ref)
        val json = http.getJson {
            repoPath(repo); seg("commits", commit); q("pagelen", PAGE_SIZE); q("page", page)
        }.json.asObject()
        val commits = json.arr("values")?.objects().orEmpty().map { item ->
            val author = item.obj("author")
            val user = author?.obj("user")
            Commit(
                sha = item.str("hash").orEmpty(),
                message = item.str("message").orEmpty(),
                authorName = user?.str("display_name") ?: author?.str("raw")?.substringBefore(" <"),
                authorAvatarUrl = user?.path("links", "avatar")?.str("href"),
                date = item.instant("date"),
                webUrl = item.path("links", "html")?.str("href"),
            )
        }
        return Page(commits, if (json.str("next") != null) page + 1 else null)
    }

    override suspend fun getCommitDiff(repo: Repo, sha: String): List<FileDiff> {
        val url = http.url { repoPath(repo); seg("diff", sha) }
        return Diffs.parseGitDiff(http.get(url, accept = "text/plain").text)
    }

    override suspend fun getPullRequestDiff(repo: Repo, pull: Issue): List<FileDiff> {
        val url = http.url { repoPath(repo); seg("pullrequests", pull.number.toString(), "diff") }
        return Diffs.parseGitDiff(http.get(url, accept = "text/plain").text)
    }

    override suspend fun listIssues(repo: Repo, state: StateFilter, page: Int): Page<Issue> {
        val json = try {
            http.getJson {
                repoPath(repo); seg("issues"); q("sort", "-updated_on")
                when (state) {
                    StateFilter.OPEN -> q("q", OPEN_ISSUE_STATES.joinToString(" OR ") { "state = \"$it\"" })
                    StateFilter.CLOSED -> q("q", CLOSED_ISSUE_STATES.joinToString(" OR ") { "state = \"$it\"" })
                    StateFilter.ALL -> Unit
                }
                q("pagelen", PAGE_SIZE); q("page", page)
            }.json.asObject()
        } catch (e: ForgeException) {
            if (e.code == 404) throw ForgeException(404, "This repository doesn't have Bitbucket's issue tracker enabled", e)
            throw e
        }
        val issues = json.arr("values")?.objects().orEmpty().map(::parseIssue)
        return Page(issues, if (json.str("next") != null) page + 1 else null)
    }

    override suspend fun listPullRequests(repo: Repo, state: StateFilter, page: Int): Page<Issue> {
        val states = when (state) {
            StateFilter.OPEN -> listOf("OPEN")
            StateFilter.CLOSED -> listOf("MERGED", "DECLINED", "SUPERSEDED")
            StateFilter.ALL -> listOf("OPEN", "MERGED", "DECLINED", "SUPERSEDED")
        }
        val json = http.getJson {
            repoPath(repo); seg("pullrequests")
            states.forEach { q("state", it) }
            q("pagelen", PAGE_SIZE); q("page", page)
        }.json.asObject()
        val pulls = json.arr("values")?.objects().orEmpty().map(::parsePullRequest)
        return Page(pulls, if (json.str("next") != null) page + 1 else null)
    }

    override suspend fun listComments(repo: Repo, issue: Issue): List<Comment> {
        val values = collectPages(http.url {
            repoPath(repo); seg(kind(issue), issue.number.toString(), "comments")
            q("sort", "created_on"); q("pagelen", 100)
        })
        return values
            .filter { it.bool("deleted") != true }
            .map(::parseComment)
            .filter { it.body.isNotBlank() } // issue state changes appear as empty comments
    }

    override suspend fun addComment(repo: Repo, issue: Issue, body: String): Comment {
        val url = http.url { repoPath(repo); seg(kind(issue), issue.number.toString(), "comments") }
        val json = http.postJson(url, buildJsonObject { putJsonObject("content") { put("raw", body) } }).json
        return parseComment(json.asObject())
    }

    override suspend fun createIssue(repo: Repo, title: String, body: String): Issue {
        val url = http.url { repoPath(repo); seg("issues") }
        val json = http.postJson(url, buildJsonObject {
            put("title", title)
            putJsonObject("content") { put("raw", body) }
        }).json
        return parseIssue(json.asObject())
    }

    override val mergeMethods = listOf(MergeMethod.MERGE, MergeMethod.SQUASH, MergeMethod.FAST_FORWARD)

    override suspend fun getPullRequest(repo: Repo, number: Long): PullDetail {
        val json = http.getJson { repoPath(repo); seg("pullrequests", number.toString()) }.json.asObject()
        val pull = parsePullRequest(json)
        // Bitbucket reports conflicts per file in the diffstat rather than on the pull request.
        val (mergeability, note) = if (pull.state != IssueState.OPEN) {
            Mergeability.UNKNOWN to null
        } else {
            val stats = collectPages(http.url { repoPath(repo); seg("pullrequests", number.toString(), "diffstat"); q("pagelen", 100) })
            if (stats.any { it.str("status") in CONFLICT_STATUSES }) {
                Mergeability.CONFLICTS to null
            } else {
                Mergeability.MERGEABLE to "Bitbucket checks merge restrictions (approvals, builds) when you merge"
            }
        }
        val headFull = json.path("source", "repository")?.str("full_name")
        val baseFull = json.path("destination", "repository")?.str("full_name") ?: repo.fullName
        return PullDetail(
            pull = pull,
            mergeability = mergeability,
            mergeNote = note,
            headBranch = pull.sourceBranch.orEmpty(),
            baseBranch = pull.targetBranch.orEmpty(),
            headSha = json.path("source", "commit")?.str("hash"),
            headCloneUrl = headFull?.let { "$WEB/$it.git" },
            baseCloneUrl = "$WEB/$baseFull.git",
            headRepoApiId = headFull,
        )
    }

    override suspend fun mergePullRequest(
        repo: Repo,
        detail: PullDetail,
        method: MergeMethod,
        title: String?,
        message: String?,
        deleteBranch: Boolean,
    ): MergeOutcome {
        val url = http.url { repoPath(repo); seg("pullrequests", detail.pull.number.toString(), "merge") }
        val commitMessage = listOfNotNull(title?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() })
            .joinToString("\n\n").takeIf { it.isNotEmpty() }
        val result = http.postJson(url, buildJsonObject {
            put("type", "pullrequest_merge_parameters")
            put("merge_strategy", when (method) {
                MergeMethod.SQUASH -> "squash"
                MergeMethod.FAST_FORWARD -> "fast_forward"
                else -> "merge_commit"
            })
            put("close_source_branch", deleteBranch)
            if (commitMessage != null) put("message", commitMessage)
        })
        // 202: large merges finish asynchronously.
        if (result.code == 202) return MergeOutcome(null, branchDeleted = deleteBranch, pending = true)
        val json = result.json.asObject()
        return MergeOutcome(json.obj("merge_commit")?.str("hash"), branchDeleted = deleteBranch)
    }

    override suspend fun deleteBranch(repo: Repo, detail: PullDetail) {
        val owner = detail.headRepoApiId ?: repo.apiId
        try {
            http.delete(http.url { seg("repositories"); path(owner); seg("refs", "branches", detail.headBranch) })
        } catch (e: ForgeException) {
            if (e.code != 404) throw e
        }
    }

    private fun UrlSpec.repoPath(repo: Repo) {
        seg("repositories"); path(repo.apiId)
    }

    private fun kind(issue: Issue) = if (issue.isPullRequest) "pullrequests" else "issues"

    private suspend fun collectPages(first: HttpUrl): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var url: HttpUrl? = first
        var pages = 0
        while (url != null && pages++ < MAX_PAGES) {
            val json = http.get(url).json.asObject()
            all += json.arr("values")?.objects().orEmpty()
            url = json.str("next")?.toHttpUrl()
        }
        return all
    }

    companion object {
        private const val MAX_PAGES = 20
        private val OPEN_ISSUE_STATES = listOf("new", "open", "on hold")
        private val CONFLICT_STATUSES = setOf("merge conflict", "local deleted", "remote deleted", "local and remote deleted")
        private const val WEB = "https://bitbucket.org"
        private val CLOSED_ISSUE_STATES = listOf("resolved", "invalid", "duplicate", "wontfix", "closed")

        fun parseUser(json: JsonObject) = User(
            login = json.str("username") ?: json.str("nickname") ?: json.str("display_name").orEmpty(),
            name = json.str("display_name"),
            avatarUrl = json.path("links", "avatar")?.str("href"),
            webUrl = json.path("links", "html")?.str("href"),
        )

        fun parseRepo(json: JsonObject): Repo {
            val fullName = json.str("full_name").orEmpty()
            val clones = json.obj("links")?.arr("clone")?.objects().orEmpty()
            fun clone(name: String) = clones.firstOrNull { it.str("name") == name }?.str("href")
            return Repo(
                apiId = fullName,
                owner = json.obj("workspace")?.str("slug") ?: fullName.substringBefore('/'),
                name = json.str("name").orEmpty(),
                fullName = fullName,
                description = json.str("description")?.takeIf { it.isNotBlank() },
                isPrivate = json.bool("is_private") == true,
                isFork = json["parent"] is JsonObject,
                defaultBranch = json.obj("mainbranch")?.str("name"),
                stars = null,
                forks = null,
                language = json.str("language")?.takeIf { it.isNotBlank() },
                updatedAt = json.instant("updated_on"),
                webUrl = json.path("links", "html")?.str("href"),
                // Clone links embed the requesting user ("https://user@bitbucket.org/…"); drop it.
                cloneHttps = clone("https")?.replace(Regex("^https://[^@/]+@"), "https://"),
                cloneSsh = clone("ssh"),
                ownerAvatarUrl = json.path("links", "avatar")?.str("href"),
            )
        }

        fun parseIssue(json: JsonObject) = Issue(
            number = json.long("id") ?: 0,
            title = json.str("title").orEmpty(),
            body = json.obj("content")?.str("raw"),
            state = if (json.str("state") in OPEN_ISSUE_STATES) IssueState.OPEN else IssueState.CLOSED,
            author = json.obj("reporter")?.let(::parseUser),
            createdAt = json.instant("created_on"),
            updatedAt = json.instant("updated_on"),
            commentCount = null,
            labels = listOfNotNull(json.str("kind"), json.str("priority")),
            isPullRequest = false,
            isDraft = false,
            webUrl = json.path("links", "html")?.str("href"),
        )

        fun parsePullRequest(json: JsonObject) = Issue(
            number = json.long("id") ?: 0,
            title = json.str("title").orEmpty(),
            body = json.str("description") ?: json.obj("summary")?.str("raw"),
            state = when (json.str("state")) {
                "OPEN" -> IssueState.OPEN
                "MERGED" -> IssueState.MERGED
                else -> IssueState.CLOSED
            },
            author = json.obj("author")?.let(::parseUser),
            createdAt = json.instant("created_on"),
            updatedAt = json.instant("updated_on"),
            commentCount = json.int("comment_count"),
            labels = emptyList(),
            isPullRequest = true,
            isDraft = json.bool("draft") == true,
            webUrl = json.path("links", "html")?.str("href"),
            sourceBranch = json.path("source", "branch")?.str("name"),
            targetBranch = json.path("destination", "branch")?.str("name"),
        )

        fun parseComment(json: JsonObject) = Comment(
            id = (json["id"] as? JsonPrimitive)?.content.orEmpty(),
            author = json.obj("user")?.let(::parseUser),
            body = json.obj("content")?.str("raw").orEmpty(),
            createdAt = json.instant("created_on"),
        )
    }
}
