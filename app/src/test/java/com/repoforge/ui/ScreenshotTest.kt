package com.repoforge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.model.Account
import com.repoforge.data.model.Branch
import com.repoforge.data.model.Comment
import com.repoforge.data.model.Commit
import com.repoforge.data.model.EntryType
import com.repoforge.data.model.FileBlob
import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.Issue
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.Page
import com.repoforge.data.model.Repo
import com.repoforge.data.model.StateFilter
import com.repoforge.data.model.TreeEntry
import com.repoforge.data.model.User
import com.repoforge.ui.accounts.AddAccountModel
import com.repoforge.ui.accounts.AddAccountScreen
import com.repoforge.ui.issue.IssueModel
import com.repoforge.ui.issue.IssueScreen
import com.repoforge.ui.repo.FileModel
import com.repoforge.ui.repo.FileScreen
import com.repoforge.ui.repo.RepoModel
import com.repoforge.ui.repo.RepoScreen
import com.repoforge.ui.repo.RepoTab
import com.repoforge.ui.repos.ReposModel
import com.repoforge.ui.repos.ReposScreen
import com.repoforge.ui.theme.RepoForgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Renders each screen with sample data and saves it under app/screenshots. This proves the
 * screens compose without crashing and gives a visual check without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val account = Account("id", ForgeType.GITLAB, "https://gitlab.com", "ada", "Ada Lovelace", null, null, "t")
    private val client = SampleClient()
    private val repo = SampleClient.repo

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { RepoForgeTheme { content() } }
        compose.waitForIdle()
        Thread.sleep(500) // markdown parses off the main thread
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test
    fun signIn() = shoot("1-sign-in") {
        AddAccountScreen(AddAccountModel(scope, OkHttpClient()) {}.apply { selectType(ForgeType.GITEA) }, firstRun = true, onBack = null)
    }

    @Test
    fun repositories() = shoot("2-repositories") {
        val accounts = listOf(account, account.copy(id = "2", type = ForgeType.GITHUB, host = "https://github.com", login = "ada-gh"))
        ReposScreen(ReposModel(scope, account, client), accounts, {}, {}, {}, {})
    }

    @Test
    fun code() = shoot("3-code") {
        RepoScreen(RepoModel(scope, account, client, repo), {}, {}, {}, {})
    }

    @Test
    fun mergeRequests() = shoot("4-merge-requests") {
        RepoScreen(RepoModel(scope, account, client, repo).apply { tab = RepoTab.PULLS }, {}, {}, {}, {})
    }

    @Test
    fun file() = shoot("5-file") {
        FileScreen(FileModel(scope, account, client, repo, "main", "src/main.kt"), {})
    }

    @Test
    fun issue() = shoot("6-issue") {
        IssueScreen(IssueModel(scope, account, client, repo, SampleClient.issues.first()), {})
    }
}

private class SampleClient : ForgeClient {
    companion object {
        private val now: Instant = Instant.now()
        private fun ago(hours: Long): Instant = now.minus(hours, ChronoUnit.HOURS)
        private val ada = User("ada", "Ada Lovelace", null, null)
        private val grace = User("grace", "Grace Hopper", null, null)

        val repo = Repo(
            "1", "analytical", "engine", "analytical/engine", "Notes on the Analytical Engine, now in Kotlin.",
            false, false, "main", 1843, 42, "Kotlin", ago(3), "https://gitlab.com/analytical/engine",
            "https://gitlab.com/analytical/engine.git", "git@gitlab.com:analytical/engine.git", null,
        )
        val repos = listOf(
            repo,
            repo.copy(apiId = "2", name = "difference-engine", fullName = "analytical/difference-engine", description = "Tabulating polynomials since 1822", isPrivate = true, stars = 12, language = "Rust", updatedAt = ago(30)),
            repo.copy(apiId = "3", owner = "ada", name = "notes", fullName = "ada/notes", description = null, isFork = true, stars = 3, language = "Markdown", updatedAt = ago(200)),
            repo.copy(apiId = "4", owner = "ada", name = "bernoulli", fullName = "ada/bernoulli", description = "Computing Bernoulli numbers, note G", stars = 128, language = "Python", updatedAt = ago(900)),
        )
        val issues = listOf(
            Issue(7, "Loop construct for the mill", "We need a way to **repeat** operation cards.\n\n- [x] design\n- [ ] punch cards", IssueState.OPEN, ada, ago(50), ago(2), 3, listOf("enhancement", "mill"), true, false, null, "feature/loops", "main"),
            Issue(6, "Draft: store results in the store", null, IssueState.OPEN, grace, ago(80), ago(20), 0, emptyList(), true, true, null, "store", "main"),
            Issue(5, "Bernoulli numbers example", "Note G", IssueState.MERGED, ada, ago(400), ago(300), 12, listOf("docs"), true, false, null, "note-g", "main"),
            Issue(4, "Rename variable cards", null, IssueState.CLOSED, grace, ago(700), ago(650), 1, emptyList(), true, false, null, "rename", "main"),
        )
    }

    override suspend fun currentUser() = ada
    override suspend fun listRepos(page: Int) = Page(repos, null)
    override suspend fun searchRepos(query: String, page: Int) = Page(repos.take(1), null)
    override suspend fun listBranches(repo: Repo) = listOf(Branch("main", "a"), Branch("feature/loops", "b"))
    override suspend fun listTree(repo: Repo, ref: String, path: String) = ForgeClient.sortEntries(
        listOf(
            TreeEntry("src", "src", EntryType.DIR, null),
            TreeEntry("docs", "docs", EntryType.DIR, null),
            TreeEntry("build.gradle.kts", "build.gradle.kts", EntryType.FILE, 1200),
            TreeEntry("LICENSE", "LICENSE", EntryType.FILE, 1071),
            TreeEntry("README.md", "README.md", EntryType.FILE, 480),
        )
    )

    override suspend fun getFile(repo: Repo, ref: String, path: String) = FileBlob(
        path,
        if (path.endsWith(".md")) {
            "# Analytical Engine\n\nThe engine weaves **algebraic patterns** just as the Jacquard loom weaves flowers and leaves.\n\n## Building\n\n```\n./gradlew build\n```\n\n- Mill: arithmetic\n- Store: 1,000 numbers of 50 digits\n".toByteArray()
        } else {
            (1..60).joinToString("\n") { i ->
                when (i) {
                    1 -> "package engine"
                    3 -> "/** Computes Bernoulli numbers, as in Note G. */"
                    4 -> "fun bernoulli(n: Int): List<Rational> {"
                    5 -> "    val numbers = mutableListOf<Rational>()"
                    6 -> "    for (m in 0..n) {"
                    7 -> "        numbers += Rational(1, m + 1)"
                    8 -> "    }"
                    9 -> "    return numbers"
                    10 -> "}"
                    else -> ""
                }
            }.toByteArray()
        },
    )

    override suspend fun listCommits(repo: Repo, ref: String, page: Int) =
        Page(listOf(Commit("0123456789", "Add loop cards", "Ada", null, ago(2), null)), null)

    override suspend fun listIssues(repo: Repo, state: StateFilter, page: Int) = Page(issues.map { it.copy(isPullRequest = false) }, null)
    override suspend fun listPullRequests(repo: Repo, state: StateFilter, page: Int) = Page(issues, null)
    override suspend fun listComments(repo: Repo, issue: Issue) = listOf(
        Comment("1", grace, "Could the loop counter live in the store?", ago(30)),
        Comment("2", ada, "Yes, a *variable card* can hold it.", ago(10)),
    )

    override suspend fun addComment(repo: Repo, issue: Issue, body: String) = Comment("3", ada, body, now)
    override suspend fun createIssue(repo: Repo, title: String, body: String) = issues.first()
}
