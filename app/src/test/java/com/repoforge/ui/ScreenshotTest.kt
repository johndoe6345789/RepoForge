package com.repoforge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.repoforge.data.forge.Diffs
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
import com.repoforge.data.model.MergeMethod
import com.repoforge.data.model.MergeOutcome
import com.repoforge.data.model.Mergeability
import com.repoforge.data.model.Page
import com.repoforge.data.model.PullDetail
import com.repoforge.data.model.Repo
import com.repoforge.data.model.StateFilter
import com.repoforge.data.model.TreeEntry
import com.repoforge.data.model.User
import com.repoforge.ui.accounts.AddAccountModel
import com.repoforge.ui.accounts.AddAccountScreen
import com.repoforge.ui.issue.IssueModel
import com.repoforge.ui.issue.IssueScreen
import com.repoforge.data.AppSettings
import com.repoforge.ui.repo.CommitModel
import com.repoforge.ui.repo.CommitScreen
import com.repoforge.ui.repo.FileModel
import com.repoforge.ui.repo.FileScreen
import com.repoforge.ui.repo.RepoModel
import com.repoforge.ui.repo.RepoScreen
import com.repoforge.ui.repo.RepoTab
import com.repoforge.ui.repos.ReposModel
import com.repoforge.ui.repos.ReposScreen
import com.repoforge.ui.settings.SettingsScreen
import org.robolectric.RuntimeEnvironment
import com.repoforge.ui.theme.RepoForgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.repoforge.data.ai.AiResolution
import com.repoforge.data.ai.ConflictAi
import com.repoforge.data.git.ConflictMarkers
import com.repoforge.data.git.GitService
import com.repoforge.data.git.JGitAndroid
import com.repoforge.data.git.LocalClone
import com.repoforge.data.git.MergeTarget
import com.repoforge.data.git.MergeWorkspace
import com.repoforge.ui.conflicts.ConflictModel
import com.repoforge.ui.conflicts.ConflictScreen
import com.repoforge.ui.conflicts.Phase
import com.repoforge.ui.conflicts.Resolution
import com.repoforge.ui.local.LocalRepoModel
import com.repoforge.ui.local.LocalRepoScreen
import com.repoforge.ui.local.LocalReposScreen
import com.repoforge.ui.local.LocalTab
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.URIish
import java.io.File
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

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shoot(name: String, dark: Boolean = false, ready: () -> Boolean = { true }, content: @Composable () -> Unit) {
        compose.setContent { RepoForgeTheme(dark = dark, dynamicColor = false) { content() } }
        compose.waitForIdle()
        Thread.sleep(1500) // markdown parsing and highlighting run off the main thread
        // Git work runs on the IO dispatcher; wait for it rather than guessing.
        val deadline = System.currentTimeMillis() + 20_000
        while (!ready() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test
    fun signIn() = shoot("01-sign-in") {
        AddAccountScreen(AddAccountModel(scope, OkHttpClient()) {}.apply { selectType(ForgeType.GITEA) }, firstRun = true, onBack = null)
    }

    @Test
    fun repositories() = shoot("02-repositories") { ReposContent() }

    @Test
    fun repositoriesDark() = shoot("02-repositories-dark", dark = true) { ReposContent() }

    @Composable
    private fun ReposContent() {
        val accounts = listOf(account, account.copy(id = "2", type = ForgeType.GITHUB, host = "https://github.com", login = "ada-gh"))
        ReposScreen(ReposModel(scope, account, client), accounts, {}, {}, {}, {}, {})
    }

    @Test
    fun code() = shoot("03-code") { RepoScreen(RepoModel(scope, account, client, repo), {}, {}, {}, {}, {}) }

    @Test
    fun codeDark() = shoot("03-code-dark", dark = true) { RepoScreen(RepoModel(scope, account, client, repo), {}, {}, {}, {}, {}) }

    @Test
    fun commits() = shoot("04-commits") {
        RepoScreen(RepoModel(scope, account, client, repo).apply { tab = RepoTab.COMMITS }, {}, {}, {}, {}, {})
    }

    @Test
    fun commit() = shoot("05-commit") { CommitScreen(CommitModel(scope, account, client, repo, SampleClient.commits.first()), {}) }

    @Test
    fun commitDark() = shoot("05-commit-dark", dark = true) {
        CommitScreen(CommitModel(scope, account, client, repo, SampleClient.commits.first()), {})
    }

    @Test
    fun mergeRequests() = shoot("06-merge-requests") {
        RepoScreen(RepoModel(scope, account, client, repo).apply { tab = RepoTab.PULLS }, {}, {}, {}, {}, {})
    }

    @Test
    fun issue() = shoot("07-merge-request") {
        IssueScreen(IssueModel(scope, account, client, repo, SampleClient.issues.first()), {})
    }

    @Test
    fun pullFiles() = shoot("08-merge-request-files") {
        IssueScreen(IssueModel(scope, account, client, repo, SampleClient.issues.first()).apply { showFiles = true }, {})
    }

    @Test
    fun file() = shoot("09-file") { FileScreen(FileModel.remote(scope, account, client, repo, "main", "src/Mill.kt"), {}) }

    @Test
    fun fileDark() = shoot("09-file-dark", dark = true) { FileScreen(FileModel.remote(scope, account, client, repo, "main", "src/Mill.kt"), {}) }

    @Test
    fun settings() = shoot("10-settings") { SettingsScreen(AppSettings(RuntimeEnvironment.getApplication()), {}, {}) }

    private val author = PersonIdent("Ada Lovelace", "ada@example.com")

    /** A bare "server" where main and feature/loops both changed Mill.kt and README.md. */
    private fun conflictingServer(): String {
        JGitAndroid.install(tmp.newFolder("gitconfig"))
        val bare = tmp.newFolder("server.git")
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        val url = bare.toURI().toString()
        Git.init().setDirectory(tmp.newFolder("seed")).setInitialBranch("main").call().use { g ->
            g.write("src/Mill.kt", MILL_BASE)
            g.write("README.md", "# Analytical Engine\n\nThe engine has a mill and a store.\n")
            g.commit().setMessage("Initial").setAuthor(author).setCommitter(author).call()
            g.remoteAdd().setName("origin").setUri(URIish(url)).call()
            g.push().setRemote("origin").add("main").call()
            g.checkout().setCreateBranch(true).setName("feature/loops").call()
            g.write("src/Mill.kt", MILL_BASE.replace("fun run(cards: List<OperationCard>) {", "fun run(cards: List<OperationCard>, repeat: Int = 1) {"))
            g.write("README.md", "# Analytical Engine\n\nThe engine has a mill, a store and loops.\n")
            g.commit().setMessage("Loops").setAuthor(author).setCommitter(author).call()
            g.push().setRemote("origin").add("feature/loops").call()
            g.checkout().setName("main").call()
            g.write("src/Mill.kt", MILL_BASE.replace("fun run(cards: List<OperationCard>) {", "fun run(cards: List<OperationCard>, trace: Boolean = false) {"))
            g.write("README.md", "# Analytical Engine\n\nThe engine has a mill and a store of 1,000 numbers.\n")
            g.commit().setMessage("Tracing").setAuthor(author).setCommitter(author).call()
            g.push().setRemote("origin").add("main").call()
        }
        return url
    }

    private fun Git.write(path: String, text: String) {
        File(repository.workTree, path).apply { parentFile!!.mkdirs(); writeText(text) }
        add().addFilepattern(path).call()
    }

    private fun conflictModel(): ConflictModel {
        val url = conflictingServer()
        val detail = runBlocking { client.getPullRequest(repo, 7) }.copy(headCloneUrl = url, baseCloneUrl = url)
        val workspace = MergeWorkspace(File(tmp.root, "workspace"), MergeTarget(url, "main", "feature/loops", url), null)
        val ai = ConflictAi { file, segments, _ ->
            val hunks = ConflictMarkers.hunks(segments).map {
                if (file.path.endsWith(".kt")) "    fun run(cards: List<OperationCard>, repeat: Int = 1, trace: Boolean = false) {\n"
                else "The engine has a mill, a store of 1,000 numbers and loops.\n"
            }
            AiResolution(hunks, "Both branches added a parameter to run(); kept both with their defaults so existing callers still compile.")
        }
        return ConflictModel(scope, detail, workspace, ai, author)
    }

    @Test
    fun conflicts() {
        val model = conflictModel()
        var asked = false
        shoot("11-resolve-conflicts", ready = {
            if (model.phase == Phase.CONFLICTS && !asked) {
                asked = true
                model.files.firstOrNull { it.file.path.endsWith(".kt") }?.let(model::resolveWithAi)
            }
            model.phase == Phase.CONFLICTS && model.files.any { it.resolution is Resolution.Text }
        }) { ConflictScreen(model, {}, {}, {}) }
    }

    @Test
    fun conflictsDark() {
        val model = conflictModel()
        shoot("11-resolve-conflicts-dark", dark = true, ready = { model.phase == Phase.CONFLICTS }) { ConflictScreen(model, {}, {}, {}) }
    }

    private fun localModel(): LocalRepoModel {
        val url = conflictingServer()
        val dir = File(tmp.root, "clone")
        runBlocking { GitService().clone(url, dir, "feature/loops", null) {} }
        File(dir, "README.md").appendText("\nLoops repeat a run of cards.\n")
        File(dir, "src/Loop.kt").writeText("class Loop(val times: Int)\n")
        val clone = LocalClone("c1", account.id, repo.fullName, repo.name, dir.path, url, null, System.currentTimeMillis() - 3_600_000)
        return LocalRepoModel(scope, clone, GitService(), { null }, { author }).apply { status.refresh() }
    }

    @Test
    fun localRepo() {
        val model = localModel()
        shoot("12-on-device", ready = { model.status.value != null }) { LocalRepoScreen(model, {}, {}, {}) }
    }

    @Test
    fun localFiles() {
        val model = localModel().apply { tab = LocalTab.FILES; open("src") }
        shoot("13-on-device-files", ready = { model.status.value != null }) { LocalRepoScreen(model, {}, {}, {}) }
    }

    @Test
    fun localRepos() {
        val clones = listOf(
            LocalClone("1", "id", "ada/analytical-engine", "analytical-engine", "/storage/emulated/0/Documents/RepoForge/analytical-engine", "", null, System.currentTimeMillis() - 7_200_000),
            LocalClone("2", "id", "ada/note-g", "note-g", "/storage/emulated/0/Android/data/com.repoforge/files/repos/note-g", "", null, System.currentTimeMillis() - 86_400_000 * 3),
        )
        shoot("14-on-this-device") { LocalReposScreen(clones, {}, {}) }
    }
}

private val MILL_BASE = """
    |class Mill(private val store: Store) {
    |    fun run(cards: List<OperationCard>) {
    |        cards.forEachIndexed { index, card -> execute(card, index) }
    |    }
    |}
    |""".trimMargin()

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
        val commits = listOf(
            Commit("3f2a9c1d7e", "Add loop construct to the mill\n\nBacking cards let the mill repeat a sequence of operations\nuntil a condition on the store is met.", "Ada Lovelace", null, ago(2), null),
            Commit("8b41e07c2a", "Document variable cards", "Grace Hopper", null, ago(26), null),
            Commit("c09d5f3b18", "Compute Bernoulli numbers (Note G)", "Ada Lovelace", null, ago(75), null),
            Commit("51e6a2d9f0", "Initial commit", "Charles Babbage", null, ago(400), null),
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
            "# Analytical Engine\n\nThe engine weaves **algebraic patterns** just as the Jacquard loom weaves flowers and leaves.\n\n## Building\n\n```kotlin\nfun main() = Engine().run(cards)\n```\n\n- Mill: arithmetic\n- Store: 1,000 numbers of 50 digits\n".toByteArray()
        } else {
            SAMPLE_KOTLIN.toByteArray()
        },
    )

    override suspend fun listCommits(repo: Repo, ref: String, page: Int) = Page(commits, null)

    override suspend fun getCommitDiff(repo: Repo, sha: String) = Diffs.parseGitDiff(SAMPLE_DIFF)
    override suspend fun getPullRequestDiff(repo: Repo, pull: Issue) = Diffs.parseGitDiff(SAMPLE_DIFF)

    override suspend fun listIssues(repo: Repo, state: StateFilter, page: Int) = Page(issues.map { it.copy(isPullRequest = false) }, null)
    override suspend fun listPullRequests(repo: Repo, state: StateFilter, page: Int) = Page(issues, null)
    override suspend fun listComments(repo: Repo, issue: Issue) = listOf(
        Comment("1", grace, "Could the loop counter live in the store?", ago(30)),
        Comment("2", ada, "Yes, a *variable card* can hold it.", ago(10)),
    )

    override val mergeMethods = listOf(MergeMethod.MERGE, MergeMethod.SQUASH)

    override suspend fun getPullRequest(repo: Repo, number: Long) = PullDetail(
        pull = issues.first { it.number == number },
        mergeability = if (number == 7L) Mergeability.CONFLICTS else Mergeability.MERGEABLE,
        mergeNote = null,
        headBranch = "feature/loops",
        baseBranch = "main",
        headSha = "3f2a9c1d7e",
        headCloneUrl = repo.cloneHttps,
        baseCloneUrl = repo.cloneHttps,
        headRepoApiId = repo.apiId,
    )

    override suspend fun mergePullRequest(
        repo: Repo, detail: PullDetail, method: MergeMethod, title: String?, message: String?, deleteBranch: Boolean,
    ) = MergeOutcome("abc", deleteBranch)

    override suspend fun deleteBranch(repo: Repo, detail: PullDetail) = Unit

    override suspend fun addComment(repo: Repo, issue: Issue, body: String) = Comment("3", ada, body, now)
    override suspend fun createIssue(repo: Repo, title: String, body: String) = issues.first()
}

private val SAMPLE_DIFF = """
diff --git a/src/Mill.kt b/src/Mill.kt
--- a/src/Mill.kt
+++ b/src/Mill.kt
@@ -10,7 +10,9 @@ class Mill(private val store: Store) {
     fun run(cards: List<OperationCard>) {
-        for (card in cards) execute(card)
+        var index = 0
+        while (index < cards.size) {
+            index = execute(cards[index], index)
+        }
     }
 
     private fun execute(card: OperationCard, index: Int): Int {
diff --git a/docs/loops.md b/docs/loops.md
new file mode 100644
--- /dev/null
+++ b/docs/loops.md
@@ -0,0 +1,3 @@
+# Loops
+
+Backing cards repeat a sequence of operations.
diff --git a/punch.png b/punch.png
new file mode 100644
Binary files /dev/null and b/punch.png differ
""".trimIndent()

private val SAMPLE_KOTLIN = """
package engine

import kotlin.math.max

/** The mill executes operation cards against the store. */
class Mill(private val store: Store) {
    private var cycles = 0L

    fun run(cards: List<OperationCard>) {
        var index = 0
        while (index < cards.size) {
            index = execute(cards[index], index)
            cycles++
        }
    }

    private fun execute(card: OperationCard, index: Int): Int = when (card) {
        is OperationCard.Add -> { store[card.target] = store[card.a] + store[card.b]; index + 1 }
        is OperationCard.Loop -> if (store[card.counter] > 0) max(0, index - card.back) else index + 1
        else -> error("Unknown card: ${'$'}card")
    }
}
""".trimIndent()
