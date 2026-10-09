package com.repoforge.data.forge

import com.repoforge.data.forge.ForgeClient.Companion.PAGE_SIZE
import com.repoforge.data.model.Branch
import com.repoforge.data.model.Comment
import com.repoforge.data.model.Commit
import com.repoforge.data.model.EntryType
import com.repoforge.data.model.FileBlob
import com.repoforge.data.model.Issue
import com.repoforge.data.model.Page
import com.repoforge.data.model.Repo
import com.repoforge.data.model.StateFilter
import com.repoforge.data.model.TreeEntry
import com.repoforge.data.model.User
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

/**
 * Gitea and its fork Forgejo (including Codeberg), at `<host>/api/v1`. Their API mirrors
 * GitHub's closely, so the GitHub parsers handle most payloads.
 */
class GiteaClient(client: OkHttpClient, apiBase: String, token: String) : ForgeClient {

    private val http = Http(client, apiBase) {
        if (token.isNotEmpty()) header("Authorization", "token $token")
    }

    override suspend fun currentUser(): User = parseUser(http.getJson { seg("user") }.json.asObject())

    override suspend fun listRepos(page: Int): Page<Repo> {
        val result = http.getJson { seg("user", "repos"); q("page", page); q("limit", PAGE_SIZE) }
        val repos = result.json.objects().map(::parseRepo).sortedByDescending { it.updatedAt }
        return page(result, page, repos)
    }

    override suspend fun searchRepos(query: String, page: Int): Page<Repo> {
        val result = http.getJson {
            seg("repos", "search"); q("q", query); q("sort", "updated"); q("order", "desc")
            q("page", page); q("limit", PAGE_SIZE)
        }
        val items = result.json.asObject().arr("data")?.objects().orEmpty()
        return page(result, page, items.map(::parseRepo))
    }

    override suspend fun listBranches(repo: Repo): List<Branch> {
        val branches = mutableListOf<Branch>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = http.getJson { seg("repos"); path(repo.apiId); seg("branches"); q("page", page); q("limit", 50) }
            branches += result.json.objects().map { Branch(it.str("name").orEmpty(), it.obj("commit")?.str("id")) }
            if (!result.hasNextLink) break
            page++
        }
        return branches
    }

    override suspend fun listTree(repo: Repo, ref: String, path: String): List<TreeEntry> {
        val json = http.getJson { seg("repos"); path(repo.apiId); seg("contents"); path(path); q("ref", ref) }.json
        return ForgeClient.sortEntries(json.objects().map { item ->
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
        })
    }

    override suspend fun getFile(repo: Repo, ref: String, path: String): FileBlob {
        val url = http.url { seg("repos"); path(repo.apiId); seg("raw"); path(path); q("ref", ref) }
        return FileBlob(path, http.get(url, accept = "*/*").bytes)
    }

    override suspend fun listCommits(repo: Repo, ref: String, page: Int): Page<Commit> {
        val result = http.getJson {
            seg("repos"); path(repo.apiId); seg("commits"); q("sha", ref)
            q("stat", false); q("verification", false); q("files", false)
            q("page", page); q("limit", PAGE_SIZE)
        }
        val commits = result.json.objects().map { item ->
            val commit = item.obj("commit")
            Commit(
                sha = item.str("sha").orEmpty(),
                message = commit?.str("message").orEmpty(),
                authorName = commit?.obj("author")?.str("name") ?: item.obj("author")?.str("login"),
                authorAvatarUrl = item.obj("author")?.str("avatar_url"),
                date = commit?.obj("author")?.instant("date") ?: item.instant("created"),
                webUrl = item.str("html_url"),
            )
        }
        return page(result, page, commits)
    }

    override suspend fun listIssues(repo: Repo, state: StateFilter, page: Int): Page<Issue> {
        val result = http.getJson {
            seg("repos"); path(repo.apiId); seg("issues"); q("type", "issues")
            q("state", state.name.lowercase()); q("page", page); q("limit", PAGE_SIZE)
        }
        val issues = result.json.objects().map { GitHubClient.parseIssue(it).copy(isPullRequest = false) }
        return page(result, page, issues)
    }

    override suspend fun listPullRequests(repo: Repo, state: StateFilter, page: Int): Page<Issue> {
        val result = http.getJson {
            seg("repos"); path(repo.apiId); seg("pulls"); q("state", state.name.lowercase())
            q("sort", "recentupdate"); q("page", page); q("limit", PAGE_SIZE)
        }
        return page(result, page, result.json.objects().map { GitHubClient.parseIssue(it).copy(isPullRequest = true) })
    }

    override suspend fun listComments(repo: Repo, issue: Issue): List<Comment> {
        val json = http.getJson {
            seg("repos"); path(repo.apiId); seg("issues", issue.number.toString(), "comments")
        }.json
        return json.objects().map(GitHubClient::parseComment)
    }

    override suspend fun addComment(repo: Repo, issue: Issue, body: String): Comment {
        val url = http.url { seg("repos"); path(repo.apiId); seg("issues", issue.number.toString(), "comments") }
        return GitHubClient.parseComment(http.postJson(url, buildJsonObject { put("body", body) }).json.asObject())
    }

    override suspend fun createIssue(repo: Repo, title: String, body: String): Issue {
        val url = http.url { seg("repos"); path(repo.apiId); seg("issues") }
        val json = http.postJson(url, buildJsonObject { put("title", title); put("body", body) }).json
        return GitHubClient.parseIssue(json.asObject()).copy(isPullRequest = false)
    }

    private fun <T> page(result: HttpResult, page: Int, items: List<T>) =
        Page(items, if (result.hasNextLink) page + 1 else null)

    companion object {
        private const val MAX_PAGES = 10

        fun parseUser(json: JsonObject) = User(
            login = json.str("login") ?: json.str("username").orEmpty(),
            name = json.str("full_name")?.takeIf { it.isNotBlank() },
            avatarUrl = json.str("avatar_url"),
            webUrl = json.str("html_url"),
        )

        fun parseRepo(json: JsonObject): Repo {
            val owner = json.obj("owner")
            return Repo(
                apiId = json.str("full_name").orEmpty(),
                owner = owner?.str("login") ?: owner?.str("username").orEmpty(),
                name = json.str("name").orEmpty(),
                fullName = json.str("full_name").orEmpty(),
                description = json.str("description")?.takeIf { it.isNotBlank() },
                isPrivate = json.bool("private") == true,
                isFork = json.bool("fork") == true,
                defaultBranch = json.str("default_branch"),
                stars = json.int("stars_count"),
                forks = json.int("forks_count"),
                language = json.str("language")?.takeIf { it.isNotBlank() },
                updatedAt = json.instant("updated_at"),
                webUrl = json.str("html_url"),
                cloneHttps = json.str("clone_url"),
                cloneSsh = json.str("ssh_url"),
                ownerAvatarUrl = owner?.str("avatar_url"),
            )
        }
    }
}
