package com.repoforge.data.forge

import com.repoforge.data.forge.ForgeClient.Companion.PAGE_SIZE
import com.repoforge.data.model.Branch
import com.repoforge.data.model.CiArtifact
import com.repoforge.data.model.CiFeatures
import com.repoforge.data.model.CiJob
import com.repoforge.data.model.CiRun
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.OutputStream
import com.repoforge.data.model.Comment
import com.repoforge.data.model.Commit
import com.repoforge.data.model.EntryType
import com.repoforge.data.model.FileBlob
import com.repoforge.data.model.FileDiff
import com.repoforge.data.model.Issue
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

    override suspend fun getCommitDiff(repo: Repo, sha: String): List<FileDiff> {
        val url = http.url { seg("repos"); path(repo.apiId); seg("git", "commits", "$sha.diff") }
        return Diffs.parseGitDiff(http.get(url, accept = "text/plain").text)
    }

    override suspend fun getPullRequestDiff(repo: Repo, pull: Issue): List<FileDiff> {
        val url = http.url { seg("repos"); path(repo.apiId); seg("pulls", "${pull.number}.diff") }
        return Diffs.parseGitDiff(http.get(url, accept = "text/plain").text)
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

    override val mergeMethods = listOf(MergeMethod.MERGE, MergeMethod.SQUASH, MergeMethod.REBASE, MergeMethod.FAST_FORWARD)

    override suspend fun getPullRequest(repo: Repo, number: Long): PullDetail {
        val json = http.getJson { seg("repos"); path(repo.apiId); seg("pulls", number.toString()) }.json.asObject()
        val detail = GitHubClient.parsePullDetail(json)
        return detail.copy(pull = detail.pull.copy(isPullRequest = true))
    }

    override suspend fun mergePullRequest(
        repo: Repo,
        detail: PullDetail,
        method: MergeMethod,
        title: String?,
        message: String?,
        deleteBranch: Boolean,
    ): MergeOutcome {
        val url = http.url { seg("repos"); path(repo.apiId); seg("pulls", detail.pull.number.toString(), "merge") }
        http.postJson(url, buildJsonObject {
            put("Do", when (method) {
                MergeMethod.SQUASH -> "squash"
                MergeMethod.REBASE -> "rebase"
                MergeMethod.FAST_FORWARD -> "fast-forward-only"
                MergeMethod.MERGE -> "merge"
            })
            if (!title.isNullOrBlank()) put("MergeTitleField", title)
            if (!message.isNullOrBlank()) put("MergeMessageField", message)
            put("delete_branch_after_merge", deleteBranch)
        })
        return MergeOutcome(sha = null, branchDeleted = deleteBranch)
    }

    override suspend fun deleteBranch(repo: Repo, detail: PullDetail) {
        val owner = detail.headRepoApiId ?: repo.apiId
        try {
            http.delete(http.url { seg("repos"); path(owner); seg("branches"); path(detail.headBranch) })
        } catch (e: ForgeException) {
            if (e.code != 404) throw e
        }
    }

    override val ci = CiFeatures("Actions")

    /**
     * Gitea 1.24+ and Forgejo list runs at actions/runs; older servers only have actions/tasks,
     * where each entry is one job. `page` must be sent, or Forgejo ignores `limit`.
     */
    override suspend fun listCiRuns(repo: Repo, page: Int): Page<CiRun> {
        val result = try {
            http.getJson { seg("repos"); path(repo.apiId); seg("actions", "runs"); q("page", page); q("limit", PAGE_SIZE) }
        } catch (e: ForgeException) {
            if (e.code != 404) throw e
            return listCiTasks(repo, page)
        }
        val json = result.json.asObject()
        val runs = json.arr("workflow_runs")?.objects().orEmpty().map(GitHubCi::run)
        return Page(runs, nextPage(result, page, json.long("total_count"), runs.size))
    }

    private suspend fun listCiTasks(repo: Repo, page: Int): Page<CiRun> {
        val result = http.getJson { seg("repos"); path(repo.apiId); seg("actions", "tasks"); q("page", page); q("limit", PAGE_SIZE) }
        val json = result.json.asObject()
        val tasks = json.arr("workflow_runs")?.objects().orEmpty().map { task ->
            GitHubCi.run(task).copy(id = TASK_PREFIX + task.long("id"), workflow = task.str("name"))
        }
        return Page(tasks, nextPage(result, page, json.long("total_count"), tasks.size))
    }

    override suspend fun getCiRun(repo: Repo, id: String): CiRun {
        if (id.startsWith(TASK_PREFIX)) {
            return listCiTasks(repo, 1).items.firstOrNull { it.id == id } ?: throw ForgeException(404, "This run is no longer listed")
        }
        return GitHubCi.run(http.getJson { seg("repos"); path(repo.apiId); seg("actions", "runs", id) }.json.asObject())
    }

    override suspend fun listCiJobs(repo: Repo, run: CiRun): List<CiJob> {
        if (run.id.startsWith(TASK_PREFIX)) {
            // A task is a single job.
            return listOf(
                CiJob(run.id.removePrefix(TASK_PREFIX), run.workflow ?: run.title, null, run.status, run.startedAt, run.finishedAt, run.webUrl)
            )
        }
        val json = http.getJson { seg("repos"); path(repo.apiId); seg("actions", "runs", run.id, "jobs"); q("page", 1); q("limit", 100) }.json
        // Gitea wraps jobs like GitHub; Forgejo returns a bare array.
        val items = (json as? JsonObject)?.arr("jobs")?.objects() ?: json.objects()
        return items.map(GitHubCi::job)
    }

    override suspend fun getCiJobLog(repo: Repo, run: CiRun, job: CiJob): String = try {
        http.getText(http.url { seg("repos"); path(repo.apiId); seg("actions", "jobs", job.id, "logs") })
    } catch (e: ForgeException) {
        // Forgejo has no log endpoint (it answers 404 or 500); point to the web page instead.
        if (e.code == 404 || e.code == 500) {
            throw ForgeException(e.code, "This server doesn't offer job logs through its API. Open the job in the browser to read it.", e)
        }
        throw e
    }

    override suspend fun listCiArtifacts(repo: Repo, run: CiRun): List<CiArtifact> {
        if (run.id.startsWith(TASK_PREFIX)) return emptyList()
        val json = try {
            http.getJson { seg("repos"); path(repo.apiId); seg("actions", "runs", run.id, "artifacts"); q("page", 1); q("limit", 100) }.json
        } catch (e: ForgeException) {
            if (e.code == 404) return emptyList() // servers without the artifacts API
            throw e
        }
        val items = (json as? JsonObject)?.arr("artifacts")?.objects() ?: json.objects()
        return items.map(GitHubCi::artifact)
    }

    override suspend fun downloadCiArtifact(artifact: CiArtifact, out: OutputStream, onProgress: (Long, Long?) -> Unit): Long =
        http.download(artifact.downloadUrl.toHttpUrl(), out, onProgress)

    private fun nextPage(result: HttpResult, page: Int, total: Long?, count: Int): Int? = when {
        result.hasNextLink -> page + 1
        total != null -> if (page.toLong() * PAGE_SIZE < total && count > 0) page + 1 else null
        else -> if (count >= PAGE_SIZE) page + 1 else null
    }

    private fun <T> page(result: HttpResult, page: Int, items: List<T>) =
        Page(items, if (result.hasNextLink) page + 1 else null)

    companion object {
        private const val MAX_PAGES = 10
        private const val TASK_PREFIX = "task-"

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
                isArchived = json.bool("archived") == true,
            )
        }
    }
}
