package com.repoforge.ui.repo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.model.Account
import com.repoforge.data.model.Branch
import com.repoforge.data.model.CiRun
import com.repoforge.data.model.Commit
import com.repoforge.data.model.FileBlob
import com.repoforge.data.model.Issue
import com.repoforge.data.model.Repo
import com.repoforge.data.model.StateFilter
import com.repoforge.data.model.TreeEntry
import com.repoforge.ui.common.Loadable
import com.repoforge.ui.common.Paged
import kotlinx.coroutines.CoroutineScope

enum class RepoTab { CODE, COMMITS, ISSUES, PULLS, CI }

/** State for one open repository: the current branch and folder, and each tab's lists. */
class RepoModel(
    private val scope: CoroutineScope,
    val account: Account,
    val client: ForgeClient,
    val repo: Repo,
) {
    var ref by mutableStateOf(repo.defaultBranch ?: "main")
        private set
    var path by mutableStateOf("")
        private set
    var tab by mutableStateOf(RepoTab.CODE)
    var issueFilter by mutableStateOf(StateFilter.OPEN)
    var pullFilter by mutableStateOf(StateFilter.OPEN)

    val branches = Loadable<List<Branch>>(scope) { client.listBranches(repo) }

    private val trees = mutableMapOf<String, Loadable<List<TreeEntry>>>()
    private val readmes = mutableMapOf<String, Loadable<FileBlob>>()
    private val commits = mutableMapOf<String, Paged<Commit>>()
    private val issues = mutableMapOf<StateFilter, Paged<Issue>>()
    private val pulls = mutableMapOf<StateFilter, Paged<Issue>>()
    /** CI runs across all branches. */
    val ciRuns = Paged<CiRun>(scope) { client.listCiRuns(repo, it) }

    fun tree(): Loadable<List<TreeEntry>> {
        val ref = ref
        val path = path
        return trees.getOrPut("$ref:$path") { Loadable(scope) { client.listTree(repo, ref, path) } }
    }

    fun readme(entry: TreeEntry): Loadable<FileBlob> {
        val ref = ref
        return readmes.getOrPut("$ref:${entry.path}") { Loadable(scope) { client.getFile(repo, ref, entry.path) } }
    }

    fun commits(): Paged<Commit> {
        val ref = ref
        return commits.getOrPut(ref) { Paged(scope) { client.listCommits(repo, ref, it) } }
    }

    fun issues(): Paged<Issue> {
        val filter = issueFilter
        return issues.getOrPut(filter) { Paged(scope) { client.listIssues(repo, filter, it) } }
    }

    fun pulls(): Paged<Issue> {
        val filter = pullFilter
        return pulls.getOrPut(filter) { Paged(scope) { client.listPullRequests(repo, filter, it) } }
    }

    fun selectRef(newRef: String) {
        ref = newRef
        path = ""
    }

    fun openDir(dirPath: String) {
        path = dirPath
    }

    /** Goes to the parent folder; false when already at the root. */
    fun up(): Boolean {
        if (path.isEmpty()) return false
        path = path.substringBeforeLast('/', "")
        return true
    }

    fun refreshCode() = tree().refresh()

    /** Called after creating an issue so the lists include it. */
    fun issuesChanged() {
        issues.values.forEach { it.refresh() }
    }
}
