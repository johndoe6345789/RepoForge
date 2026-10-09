package com.repoforge.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.repoforge.RepoForgeApp
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.forge.ForgeClients
import com.repoforge.data.model.Account
import com.repoforge.data.model.Issue
import com.repoforge.data.model.Repo
import com.repoforge.ui.accounts.AddAccountModel
import com.repoforge.ui.issue.IssueModel
import com.repoforge.ui.issue.NewIssueModel
import com.repoforge.ui.repo.FileModel
import com.repoforge.ui.repo.RepoModel
import com.repoforge.ui.repos.ReposModel

/** Each entry on the back stack carries the state of its screen, so going back restores it. */
sealed interface Screen {
    class AddAccount(val model: AddAccountModel, val firstRun: Boolean) : Screen
    data object Accounts : Screen
    class Repos(val model: ReposModel) : Screen
    class RepoHome(val model: RepoModel) : Screen
    class File(val model: FileModel) : Screen
    class IssueDetail(val model: IssueModel) : Screen
    class NewIssue(val model: NewIssueModel) : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RepoForgeApp
    val store = app.accountStore
    val accounts = store.accounts
    val activeId = store.activeId

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
        stack += Screen.File(FileModel(viewModelScope, account, clientFor(account), repo, ref, path))
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
}
