package com.repoforge.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.repoforge.RepoForgeApp
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.forge.ForgeClients
import com.repoforge.data.model.Account
import com.repoforge.data.model.Commit
import com.repoforge.data.model.Issue
import com.repoforge.data.model.PullDetail
import com.repoforge.data.model.CiJob
import com.repoforge.data.model.CiRun
import com.repoforge.ui.ci.CiLogModel
import com.repoforge.ui.ci.CiRunModel
import com.repoforge.data.model.Repo
import com.repoforge.data.ai.ClaudeConflictResolver
import com.repoforge.data.git.GitCredentials
import com.repoforge.data.git.LocalClone
import com.repoforge.data.git.MergeTarget
import com.repoforge.data.git.MergeWorkspace
import com.repoforge.ui.conflicts.ConflictModel
import com.repoforge.ui.local.CloneTask
import com.repoforge.ui.local.LocalRepoModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.repoforge.ui.accounts.AddAccountModel
import com.repoforge.ui.issue.IssueModel
import com.repoforge.ui.issue.NewIssueModel
import com.repoforge.ui.repo.CommitModel
import com.repoforge.ui.repo.FileModel
import com.repoforge.ui.repo.RepoModel
import com.repoforge.ui.repos.ReposModel

/** Each entry on the back stack carries the state of its screen, so going back restores it. */
sealed interface Screen {
    class AddAccount(val model: AddAccountModel, val firstRun: Boolean) : Screen
    data object Accounts : Screen
    data object Settings : Screen
    class Repos(val model: ReposModel) : Screen
    class RepoHome(val model: RepoModel) : Screen
    class File(val model: FileModel) : Screen
    class CommitDetail(val model: CommitModel) : Screen
    class IssueDetail(val model: IssueModel) : Screen
    class NewIssue(val model: NewIssueModel) : Screen
    class Conflicts(val model: ConflictModel, val issue: IssueModel) : Screen
    class CiRunDetail(val model: CiRunModel) : Screen
    class CiLog(val model: CiLogModel) : Screen
    data object LocalRepos : Screen
    class LocalRepo(val model: LocalRepoModel) : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RepoForgeApp
    val store = app.accountStore
    val settings = app.settings
    val accounts = store.accounts
    val activeId = store.activeId
    val clones = app.clones.clones

    /** The clone in progress or just finished, shown as a dialog over every screen. */
    var cloneTask by mutableStateOf<CloneTask?>(null)
        private set

    val stack = mutableStateListOf<Screen>()
    private val clients = mutableMapOf<Account, ForgeClient>()

    init {
        resetStack()
    }

    fun clientFor(account: Account): ForgeClient = clients.getOrPut(account) { ForgeClients.create(account, app.okHttp) }

    /** Starts over at the active account's repository list, or at sign-in when there is none. */
    private fun resetStack() {
        stack.clear()
        val account = store.active()
        stack += if (account == null) {
            Screen.AddAccount(newAddAccountModel(), firstRun = true)
        } else {
            Screen.Repos(ReposModel(viewModelScope, account, clientFor(account)))
        }
    }

    private fun newAddAccountModel() = AddAccountModel(viewModelScope, app.okHttp) { account ->
        store.save(account)
        resetStack()
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun addAccount() {
        stack += Screen.AddAccount(newAddAccountModel(), firstRun = false)
    }

    fun manageAccounts() {
        stack += Screen.Accounts
    }

    fun openSettings() {
        stack += Screen.Settings
    }

    fun switchAccount(id: String) {
        store.setActive(id)
        resetStack()
    }

    fun removeAccount(account: Account) {
        clients.remove(account)
        val wasActive = store.active()?.id == account.id
        store.remove(account.id)
        if (store.accounts.value.isEmpty() || wasActive) resetStack()
    }

    fun openRepo(account: Account, repo: Repo) {
        stack += Screen.RepoHome(RepoModel(viewModelScope, account, clientFor(account), repo))
    }

    fun openFile(account: Account, repo: Repo, ref: String, path: String) {
        stack += Screen.File(FileModel.remote(viewModelScope, account, clientFor(account), repo, ref, path))
    }

    fun openCommit(account: Account, repo: Repo, commit: Commit) {
        stack += Screen.CommitDetail(CommitModel(viewModelScope, account, clientFor(account), repo, commit))
    }

    fun openIssue(account: Account, repo: Repo, issue: Issue) {
        stack += Screen.IssueDetail(IssueModel(viewModelScope, account, clientFor(account), repo, issue))
    }

    fun newIssue(repoModel: RepoModel) {
        val model = NewIssueModel(viewModelScope, repoModel.client, repoModel.repo) { created ->
            // Replace the form with the new issue and make the list show it.
            stack.removeAt(stack.lastIndex)
            repoModel.issuesChanged()
            openIssue(repoModel.account, repoModel.repo, created)
        }
        stack += Screen.NewIssue(model)
    }

    fun openConflicts(issue: IssueModel, detail: PullDetail) {
        val account = issue.account
        val baseUrl = detail.baseCloneUrl ?: issue.repo.cloneHttps ?: return
        // One workspace per repository, reused (and reset) for each pull request.
        val dir = File(app.mergeWorkspaceRoot(), safeName("${account.id}-${issue.repo.fullName}"))
        val workspace = MergeWorkspace(
            dir,
            MergeTarget(baseUrl, detail.baseBranch, detail.headBranch, detail.headCloneUrl),
            GitCredentials.forAccount(account),
        )
        val ai = settings.aiKey()?.let { ClaudeConflictResolver(it, settings.aiModel.value) }
        val model = ConflictModel(viewModelScope, detail, workspace, ai, settings.authorFor(account))
        stack += Screen.Conflicts(model, issue)
    }

    /** After pushing the resolved merge: back to the pull request, which re-checks mergeability. */
    fun conflictsDone(screen: Screen.Conflicts) {
        stack.remove(screen)
        screen.issue.pull.refresh()
    }

    fun openCiRun(repoModel: RepoModel, run: CiRun) {
        stack += Screen.CiRunDetail(CiRunModel(viewModelScope, repoModel.client, repoModel.repo, run, app.artifacts))
    }

    fun openCiLog(runModel: CiRunModel, job: CiJob) {
        stack += Screen.CiLog(CiLogModel(viewModelScope, runModel.client, runModel.repo, runModel.run, job, app.cacheDir))
    }

    fun cloneRepo(account: Account, repo: Repo) {
        if (cloneTask?.running == true) return
        val dir = freeDir(app.cloneRoot(), safeName(repo.name))
        cloneTask = CloneTask(viewModelScope, app.git, account, repo, dir, GitCredentials.forAccount(account)) { app.clones.add(it) }
    }

    fun dismissClone() {
        cloneTask = null
    }

    fun openLocalRepos() {
        app.clones.prune()
        stack += Screen.LocalRepos
    }

    fun openLocalRepo(clone: LocalClone) {
        cloneTask = null
        val model = LocalRepoModel(
            viewModelScope,
            clone,
            app.git,
            credentials = { store.accounts.value.find { it.id == clone.accountId }?.let(GitCredentials::forAccount) },
            author = { settings.authorFor(store.accounts.value.find { it.id == clone.accountId }) },
        )
        stack += Screen.LocalRepo(model)
    }

    fun openLocalFile(clone: LocalClone, path: String) {
        stack += Screen.File(FileModel.local(viewModelScope, clone.dir, clone.name, path))
    }

    fun deleteClone(clone: LocalClone) {
        app.clones.remove(clone.id)
        stack.removeAll { it is Screen.LocalRepo && it.model.clone.id == clone.id }
        viewModelScope.launch { withContext(Dispatchers.IO) { clone.dir.deleteRecursively() } }
    }

    /** The local clone of [repo] for [account], if there is one. */
    fun localCloneOf(account: Account, repo: Repo, clones: List<LocalClone>): LocalClone? =
        clones.find { it.accountId == account.id && it.fullName == repo.fullName }

    private companion object {
        fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "repo" }

        fun freeDir(root: File, name: String): File {
            var dir = File(root, name)
            var n = 2
            while (dir.exists()) dir = File(root, "$name-${n++}")
            return dir
        }
    }
}
