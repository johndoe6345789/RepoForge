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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.Base64

/** GitHub.com (api.github.com) and GitHub Enterprise Server (`<host>/api/v3`). */
class GitHubClient(client: OkHttpClient, apiBase: String, token: String) : ForgeClient {

    private val http = Http(client, apiBase) {
        header("X-GitHub-Api-Version", "2022-11-28")
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }

    override suspend fun currentUser(): User = parseUser(http.getJson { seg("user") }.json.asObject())

    override suspend fun listRepos(page: Int): Page<Repo> {
        val result = http.getJson {
            seg("user", "repos"); q("sort", "updated"); q("per_page", PAGE_SIZE); q("page", page)
            q("affiliation", "owner,collaborator,organization_member")
        }
        return page(result, page, result.json.objects().map(::parseRepo))
    }

    override suspend fun searchRepos(query: String, page: Int): Page<Repo> {
        val result = http.getJson {
            seg("search", "repositories"); q("q", query); q("per_page", PAGE_SIZE); q("page", page)
        }
        val items = result.json.asObject().arr("items")?.objects().orEmpty()
        return page(result, page, items.map(::parseRepo))
    }

    override suspend fun listBranches(repo: Repo): List<Branch> {
        val branches = mutableListOf<Branch>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = http.getJson { seg("repos"); path(repo.apiId); seg("branches"); q("per_page", 100); q("page", page) }
            branches += result.json.objects().map { Branch(it.str("name").orEmpty(), it.obj("commit")?.str("sha")) }
            if (!result.hasNextLink) break
            page++
        }
        return branches
    }

    override suspend fun listTree(repo: Repo, ref: String, path: String): List<TreeEntry> {
        val json = http.getJson { seg("repos"); path(repo.apiId); seg("contents"); path(path); q("ref", ref) }.json
        val entries = json.objects().map { item ->
            TreeEntry(
                name = item.str("name").orEmpty(),
                path = item.str("path").orEmpty(),
                type = when (item.str("type")) {
                    "dir" -> EntryType.DIR
                    "symlink" -> EntryType.SYMLINK
                    "submodule" -> EntryType.SUBMODULE
                    else -> EntryType.FILE
                },
                size = item.long("size"),
            )
        }
        return ForgeClient.sortEntries(entries)
    }

    override suspend fun getFile(repo: Repo, ref: String, path: String): FileBlob {
        val url = http.url { seg("repos"); path(repo.apiId); seg("contents"); path(path); q("ref", ref) }
        return FileBlob(path, http.get(url, accept = "application/vnd.github.raw").bytes)
    }

    override suspend fun getReadme(repo: Repo, ref: String): FileBlob? {
        val url = http.url { seg("repos"); path(repo.apiId); seg("readme"); q("ref", ref) }
        val json = try {
            http.get(url).json.asObject()
        } catch (e: ForgeException) {
            if (e.code == 404) return null else throw e
        }
        val content = json.str("content") ?: return null
        return FileBlob(json.str("path") ?: "README.md", Base64.getMimeDecoder().decode(content))
    }

    override suspend fun listCommits(repo: Repo, ref: String, page: Int): Page<Commit> {
        val result = http.getJson {
            seg("repos"); path(repo.apiId); seg("commits"); q("sha", ref); q("per_page", PAGE_SIZE); q("page", page)
        }
        val commits = result.json.objects().map { item ->
            val commit = item.obj("commit")
            Commit(
                sha = item.str("sha").orEmpty(),
                message = commit?.str("message").orEmpty(),
                authorName = commit?.obj("author")?.str("name") ?: item.obj("author")?.str("login"),
                authorAvatarUrl = item.obj("author")?.str("avatar_url"),
                date = commit?.obj("author")?.instant("date") ?: commit?.obj("committer")?.instant("date"),
                webUrl = item.str("html_url"),
            )
        }
        return page(result, page, commits)
    }

    override suspend fun getCommitDiff(repo: Repo, sha: String): List<FileDiff> {
        val json = http.getJson { seg("repos"); path(repo.apiId); seg("commits", sha) }.json.asObject()
        return json.arr("files")?.objects().orEmpty().map(::parseFileDiff)
    }

    override suspend fun getPullRequestDiff(repo: Repo, pull: Issue): List<FileDiff> {
        val files = mutableListOf<FileDiff>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = http.getJson {
                seg("repos"); path(repo.apiId); seg("pulls", pull.number.toString(), "files"); q("per_page", 100); q("page", page)
            }
            files += result.json.objects().map(::parseFileDiff)
            if (!result.hasNextLink) break
            page++
        }
        return files
    }

    override suspend fun listIssues(repo: Repo, state: StateFilter, page: Int): Page<Issue> {
        val result = http.getJson {
            seg("repos"); path(repo.apiId); seg("issues")
            q("state", state.name.lowercase()); q("per_page", PAGE_SIZE); q("page", page)
        }
        // The issues endpoint also returns pull requests; those are listed separately.
        val issues = result.json.objects().filter { it["pull_request"] == null }.map(::parseIssue)
        return page(result, page, issues)
    }

    override suspend fun listPullRequests(repo: Repo, state: StateFilter, page: Int): Page<Issue> {
        val result = http.getJson {
            seg("repos"); path(repo.apiId); seg("pulls")
            q("state", state.name.lowercase()); q("sort", "updated"); q("direction", "desc")
            q("per_page", PAGE_SIZE); q("page", page)
        }
        return page(result, page, result.json.objects().map(::parseIssue))
    }

    override suspend fun listComments(repo: Repo, issue: Issue): List<Comment> {
        // Pull requests share the issue comment thread (review comments are separate).
        val json = http.getJson {
            seg("repos"); path(repo.apiId); seg("issues", issue.number.toString(), "comments"); q("per_page", 100)
        }.json
        return json.objects().map(::parseComment)
    }

    override suspend fun addComment(repo: Repo, issue: Issue, body: String): Comment {
        val url = http.url { seg("repos"); path(repo.apiId); seg("issues", issue.number.toString(), "comments") }
        return parseComment(http.postJson(url, buildJsonObject { put("body", body) }).json.asObject())
    }

    override suspend fun createIssue(repo: Repo, title: String, body: String): Issue {
        val url = http.url { seg("repos"); path(repo.apiId); seg("issues") }
        val json = http.postJson(url, buildJsonObject { put("title", title); put("body", body) }).json
        return parseIssue(json.asObject())
    }

    private fun <T> page(result: HttpResult, page: Int, items: List<T>) =
        Page(items, if (result.hasNextLink) page + 1 else null)

    companion object {
        private const val MAX_PAGES = 10

        fun parseUser(json: JsonObject) = User(
            login = json.str("login").orEmpty(),
            name = json.str("name"),
            avatarUrl = json.str("avatar_url"),
            webUrl = json.str("html_url"),
        )

        fun parseRepo(json: JsonObject): Repo {
            val owner = json.obj("owner")
            return Repo(
                apiId = json.str("full_name").orEmpty(),
                owner = owner?.str("login").orEmpty(),
                name = json.str("name").orEmpty(),
                fullName = json.str("full_name").orEmpty(),
                description = json.str("description"),
                isPrivate = json.bool("private") == true,
                isFork = json.bool("fork") == true,
                defaultBranch = json.str("default_branch"),
                stars = json.int("stargazers_count"),
                forks = json.int("forks_count"),
                language = json.str("language"),
                updatedAt = json.instant("pushed_at") ?: json.instant("updated_at"),
                webUrl = json.str("html_url"),
                cloneHttps = json.str("clone_url"),
                cloneSsh = json.str("ssh_url"),
                ownerAvatarUrl = owner?.str("avatar_url"),
                isArchived = json.bool("archived") == true,
            )
        }

        /** GitHub's per-file diff, also used for Gitea-style payloads with the same fields. */
        fun parseFileDiff(json: JsonObject) = FileDiff(
            path = json.str("filename").orEmpty(),
            oldPath = json.str("previous_filename"),
            change = when (json.str("status")) {
                "added" -> ChangeType.ADDED
                "removed" -> ChangeType.DELETED
                "renamed" -> ChangeType.RENAMED
                else -> ChangeType.MODIFIED
            },
            additions = json.int("additions") ?: 0,
            deletions = json.int("deletions") ?: 0,
            patch = json.str("patch"),
        )

        fun parseIssue(json: JsonObject): Issue {
            val isPull = json["pull_request"] != null || json["head"] != null
            val merged = json.str("merged_at") != null || json.bool("merged") == true
            return Issue(
                number = json.long("number") ?: 0,
                title = json.str("title").orEmpty(),
                body = json.str("body"),
                state = when {
                    merged -> IssueState.MERGED
                    json.str("state") == "open" -> IssueState.OPEN
                    else -> IssueState.CLOSED
                },
                author = json.obj("user")?.let(::parseUser),
                createdAt = json.instant("created_at"),
                updatedAt = json.instant("updated_at"),
                commentCount = json.int("comments"),
                labels = json.arr("labels")?.objects()?.mapNotNull { it.str("name") }.orEmpty(),
                isPullRequest = isPull,
                isDraft = json.bool("draft") == true,
                webUrl = json.str("html_url"),
                sourceBranch = json.obj("head")?.str("ref"),
                targetBranch = json.obj("base")?.str("ref"),
            )
        }

        fun parseComment(json: JsonObject) = Comment(
            id = json.str("id").orEmpty(),
            author = json.obj("user")?.let(::parseUser),
            body = json.str("body").orEmpty(),
            createdAt = json.instant("created_at"),
        )
    }
}
