package com.repoforge.data.forge

import com.repoforge.data.forge.ForgeClient.Companion.PAGE_SIZE
import com.repoforge.data.model.Branch
import com.repoforge.data.model.Comment
import com.repoforge.data.model.Commit
import com.repoforge.data.model.EntryType
import com.repoforge.data.model.ChangeType
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
import com.repoforge.data.net.HttpResult
import com.repoforge.data.net.arr
import com.repoforge.data.net.asObject
import com.repoforge.data.net.bool
import com.repoforge.data.net.instant
import com.repoforge.data.net.int
import com.repoforge.data.net.long
import com.repoforge.data.net.obj
import com.repoforge.data.net.objects
import com.repoforge.data.net.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** GitLab.com and self-managed GitLab (`<host>/api/v4`). */
class GitLabClient(client: OkHttpClient, apiBase: String, token: String) : ForgeClient {

    private val http = Http(client, apiBase) {
        if (token.isNotEmpty()) header("PRIVATE-TOKEN", token)
    }

    override suspend fun currentUser(): User = parseUser(http.getJson { seg("user") }.json.asObject())

    override suspend fun listRepos(page: Int): Page<Repo> {
        val result = http.getJson {
            seg("projects"); q("membership", true); q("order_by", "last_activity_at"); q("sort", "desc")
            q("per_page", PAGE_SIZE); q("page", page)
        }
        return page(result, page, result.json.objects().map(::parseRepo))
    }

    override suspend fun searchRepos(query: String, page: Int): Page<Repo> {
        val result = http.getJson {
            seg("projects"); q("search", query); q("order_by", "last_activity_at"); q("sort", "desc")
            q("per_page", PAGE_SIZE); q("page", page)
        }
        return page(result, page, result.json.objects().map(::parseRepo))
    }

    override suspend fun listBranches(repo: Repo): List<Branch> = collectPages { page ->
        http.getJson { seg("projects", repo.apiId, "repository", "branches"); q("per_page", 100); q("page", page) }
    }.map { Branch(it.str("name").orEmpty(), it.obj("commit")?.str("id")) }

    override suspend fun listTree(repo: Repo, ref: String, path: String): List<TreeEntry> {
        val items = collectPages { page ->
            http.getJson {
                seg("projects", repo.apiId, "repository", "tree")
                if (path.isNotEmpty()) q("path", path)
                q("ref", ref); q("per_page", 100); q("page", page)
            }
        }
        return ForgeClient.sortEntries(items.map { item ->
            TreeEntry(
                name = item.str("name").orEmpty(),
                path = item.str("path").orEmpty(),
                type = when (item.str("type")) {
                    "tree" -> EntryType.DIR
                    "commit" -> EntryType.SUBMODULE
                    else -> if (item.str("mode") == "120000") EntryType.SYMLINK else EntryType.FILE
                },
                size = null,
            )
        })
    }

    override suspend fun getFile(repo: Repo, ref: String, path: String): FileBlob {
        // The file path is a single URL-encoded segment in this endpoint.
        val url = http.url { seg("projects", repo.apiId, "repository", "files", path, "raw"); q("ref", ref) }
        return FileBlob(path, http.get(url, accept = "*/*").bytes)
    }

    override suspend fun listCommits(repo: Repo, ref: String, page: Int): Page<Commit> {
        val result = http.getJson {
            seg("projects", repo.apiId, "repository", "commits"); q("ref_name", ref)
            q("per_page", PAGE_SIZE); q("page", page)
        }
        val commits = result.json.objects().map { item ->
            Commit(
                sha = item.str("id").orEmpty(),
                message = item.str("message") ?: item.str("title").orEmpty(),
                authorName = item.str("author_name"),
                authorAvatarUrl = null,
                date = item.instant("authored_date") ?: item.instant("created_at"),
                webUrl = item.str("web_url"),
            )
        }
        return page(result, page, commits)
    }

    override suspend fun getCommitDiff(repo: Repo, sha: String): List<FileDiff> = collectPages { page ->
        http.getJson { seg("projects", repo.apiId, "repository", "commits", sha, "diff"); q("per_page", 100); q("page", page) }
    }.map(::parseFileDiff)

    override suspend fun getPullRequestDiff(repo: Repo, pull: Issue): List<FileDiff> = collectPages { page ->
        http.getJson { seg("projects", repo.apiId, "merge_requests", pull.number.toString(), "diffs"); q("per_page", 100); q("page", page) }
    }.map(::parseFileDiff)

    override suspend fun listIssues(repo: Repo, state: StateFilter, page: Int): Page<Issue> {
        val result = http.getJson {
            seg("projects", repo.apiId, "issues"); stateParam(state)
            q("order_by", "updated_at"); q("per_page", PAGE_SIZE); q("page", page)
        }
        return page(result, page, result.json.objects().map { parseIssue(it, isPullRequest = false) })
    }

    override suspend fun listPullRequests(repo: Repo, state: StateFilter, page: Int): Page<Issue> {
        val result = http.getJson {
            seg("projects", repo.apiId, "merge_requests"); stateParam(state)
            q("order_by", "updated_at"); q("per_page", PAGE_SIZE); q("page", page)
        }
        return page(result, page, result.json.objects().map { parseIssue(it, isPullRequest = true) })
    }

    override suspend fun listComments(repo: Repo, issue: Issue): List<Comment> {
        val json = http.getJson {
            seg("projects", repo.apiId, kind(issue), issue.number.toString(), "notes")
            q("sort", "asc"); q("order_by", "created_at"); q("per_page", 100)
        }.json
        // System notes are activity entries ("changed the description"), not comments.
        return json.objects().filter { it.bool("system") != true }.map(::parseComment)
    }

    override suspend fun addComment(repo: Repo, issue: Issue, body: String): Comment {
        val url = http.url { seg("projects", repo.apiId, kind(issue), issue.number.toString(), "notes") }
        return parseComment(http.postJson(url, buildJsonObject { put("body", body) }).json.asObject())
    }

    override suspend fun createIssue(repo: Repo, title: String, body: String): Issue {
        val url = http.url { seg("projects", repo.apiId, "issues") }
        val json = http.postJson(url, buildJsonObject { put("title", title); put("description", body) }).json
        return parseIssue(json.asObject(), isPullRequest = false)
    }

    override val mergeMethods = listOf(MergeMethod.MERGE, MergeMethod.SQUASH)

    override suspend fun getPullRequest(repo: Repo, number: Long): PullDetail {
        val json = http.getJson { seg("projects", repo.apiId, "merge_requests", number.toString()) }.json.asObject()
        val sourceId = (json["source_project_id"] as? JsonPrimitive)?.content
        val targetId = (json["target_project_id"] as? JsonPrimitive)?.content
        val headCloneUrl = if (sourceId == null || sourceId == targetId) {
            repo.cloneHttps
        } else {
            runCatching { http.getJson { seg("projects", sourceId) }.json.asObject().str("http_url_to_repo") }.getOrNull()
        }
        return parsePullDetail(json, repo.cloneHttps, headCloneUrl, sourceId ?: repo.apiId)
    }

    override suspend fun mergePullRequest(
        repo: Repo,
        detail: PullDetail,
        method: MergeMethod,
        title: String?,
        message: String?,
        deleteBranch: Boolean,
    ): MergeOutcome {
        val url = http.url { seg("projects", repo.apiId, "merge_requests", detail.pull.number.toString(), "merge") }
        val commitMessage = listOfNotNull(title?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() })
            .joinToString("\n\n").takeIf { it.isNotEmpty() }
        val json = http.putJson(url, buildJsonObject {
            put("squash", method == MergeMethod.SQUASH)
            put("should_remove_source_branch", deleteBranch)
            if (commitMessage != null) {
                put(if (method == MergeMethod.SQUASH) "squash_commit_message" else "merge_commit_message", commitMessage)
            }
        }).json.asObject()
        val sha = json.str("merge_commit_sha") ?: json.str("squash_commit_sha") ?: json.str("sha")
        return MergeOutcome(sha, branchDeleted = deleteBranch)
    }

    override suspend fun deleteBranch(repo: Repo, detail: PullDetail) {
        val project = detail.headRepoApiId ?: repo.apiId
        try {
            http.delete(http.url { seg("projects", project, "repository", "branches", detail.headBranch) })
        } catch (e: ForgeException) {
            if (e.code != 404) throw e // already gone
        }
    }

    private fun com.repoforge.data.net.UrlSpec.stateParam(state: StateFilter) {
        when (state) {
            StateFilter.OPEN -> q("state", "opened")
            StateFilter.CLOSED -> q("state", "closed")
            StateFilter.ALL -> Unit
        }
    }

    private fun kind(issue: Issue) = if (issue.isPullRequest) "merge_requests" else "issues"

    private suspend fun collectPages(fetch: suspend (Int) -> HttpResult): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = fetch(page)
            all += result.json.objects()
            if (nextPage(result, page) == null) break
            page++
        }
        return all
    }

    private fun <T> page(result: HttpResult, page: Int, items: List<T>) = Page(items, nextPage(result, page))

    private fun nextPage(result: HttpResult, page: Int): Int? {
        val header = result.headers["X-Next-Page"]
        return when {
            !header.isNullOrBlank() -> header.toIntOrNull()
            header == null && result.hasNextLink -> page + 1
            else -> null
        }
    }

    companion object {
        private const val MAX_PAGES = 20

        fun parseUser(json: JsonObject) = User(
            login = json.str("username").orEmpty(),
            name = json.str("name"),
            avatarUrl = json.str("avatar_url"),
            webUrl = json.str("web_url"),
        )

        fun parseRepo(json: JsonObject): Repo {
            val namespace = json.obj("namespace")
            val fullPath = json.str("path_with_namespace").orEmpty()
            return Repo(
                apiId = (json["id"] as? JsonPrimitive)?.content ?: fullPath,
                owner = namespace?.str("full_path") ?: fullPath.substringBeforeLast('/'),
                name = json.str("name").orEmpty(),
                fullName = fullPath,
                description = json.str("description")?.takeIf { it.isNotBlank() },
                isPrivate = json.str("visibility")?.let { it != "public" } ?: false,
                isFork = json["forked_from_project"] != null,
                defaultBranch = json.str("default_branch"),
                stars = json.int("star_count"),
                forks = json.int("forks_count"),
                language = null,
                updatedAt = json.instant("last_activity_at"),
                webUrl = json.str("web_url"),
                cloneHttps = json.str("http_url_to_repo"),
                cloneSsh = json.str("ssh_url_to_repo"),
                ownerAvatarUrl = json.str("avatar_url") ?: namespace?.str("avatar_url"),
                isArchived = json.bool("archived") == true,
            )
        }

        fun parseFileDiff(json: JsonObject): FileDiff {
            // GitLab omits the patch (empty "diff") for binary files and diffs over its size limit.
            val patch = json.str("diff")?.takeIf { it.isNotBlank() }?.trimEnd('\n')
            val (added, removed) = Diffs.countChanges(patch)
            val change = when {
                json.bool("new_file") == true -> ChangeType.ADDED
                json.bool("deleted_file") == true -> ChangeType.DELETED
                json.bool("renamed_file") == true -> ChangeType.RENAMED
                else -> ChangeType.MODIFIED
            }
            return FileDiff(
                path = (if (change == ChangeType.DELETED) json.str("old_path") else json.str("new_path")).orEmpty(),
                oldPath = json.str("old_path").takeIf { change == ChangeType.RENAMED },
                change = change,
                additions = added,
                deletions = removed,
                patch = patch,
            )
        }

        fun parseIssue(json: JsonObject, isPullRequest: Boolean) = Issue(
            number = json.long("iid") ?: 0,
            title = json.str("title").orEmpty(),
            body = json.str("description"),
            state = when (json.str("state")) {
                "opened" -> IssueState.OPEN
                "merged" -> IssueState.MERGED
                else -> IssueState.CLOSED
            },
            author = json.obj("author")?.let(::parseUser),
            createdAt = json.instant("created_at"),
            updatedAt = json.instant("updated_at"),
            commentCount = json.int("user_notes_count"),
            labels = json.arr("labels")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            isPullRequest = isPullRequest,
            isDraft = json.bool("draft") == true || json.bool("work_in_progress") == true,
            webUrl = json.str("web_url"),
            sourceBranch = json.str("source_branch"),
            targetBranch = json.str("target_branch"),
        )

        fun parsePullDetail(json: JsonObject, baseCloneUrl: String?, headCloneUrl: String?, headProject: String?): PullDetail {
            val pull = parseIssue(json, isPullRequest = true)
            val status = json.str("detailed_merge_status")
            val (mergeability, note) = when {
                pull.state != IssueState.OPEN -> Mergeability.UNKNOWN to null
                json.bool("has_conflicts") == true || status == "conflict" -> Mergeability.CONFLICTS to null
                status == "mergeable" -> Mergeability.MERGEABLE to null
                status in setOf("checking", "unchecked", "preparing", "approvals_syncing") ->
                    Mergeability.CHECKING to "GitLab is checking whether this can be merged"
                status == "draft_status" -> Mergeability.BLOCKED to "Draft merge requests can't be merged"
                status in setOf("ci_must_pass", "ci_still_running") -> Mergeability.BLOCKED to "The pipeline must succeed first"
                status == "not_approved" -> Mergeability.BLOCKED to "Approval is required"
                status == "discussions_not_resolved" -> Mergeability.BLOCKED to "All threads must be resolved"
                status == "need_rebase" -> Mergeability.BLOCKED to "The branch must be rebased first"
                status != null -> Mergeability.BLOCKED to "Can't be merged yet (${status.replace('_', ' ')})"
                // GitLab before 15.6 only has merge_status.
                json.str("merge_status") == "cannot_be_merged" -> Mergeability.CONFLICTS to null
                json.str("merge_status") == "can_be_merged" -> Mergeability.MERGEABLE to null
                else -> Mergeability.CHECKING to null
            }
            return PullDetail(
                pull = pull,
                mergeability = mergeability,
                mergeNote = note,
                headBranch = json.str("source_branch").orEmpty(),
                baseBranch = json.str("target_branch").orEmpty(),
                headSha = json.str("sha"),
                headCloneUrl = headCloneUrl,
                baseCloneUrl = baseCloneUrl,
                headRepoApiId = headProject,
            )
        }

        fun parseComment(json: JsonObject) = Comment(
            id = (json["id"] as? JsonPrimitive)?.content.orEmpty(),
            author = json.obj("author")?.let(::parseUser),
            body = json.str("body").orEmpty(),
            createdAt = json.instant("created_at"),
        )
    }
}
