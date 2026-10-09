package com.repoforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import com.repoforge.ui.accounts.AccountsScreen
import com.repoforge.ui.accounts.AddAccountScreen
import com.repoforge.ui.issue.IssueScreen
import com.repoforge.ui.issue.NewIssueScreen
import com.repoforge.ui.repo.CommitScreen
import com.repoforge.ui.repo.FileScreen
import com.repoforge.ui.repo.RepoScreen
import com.repoforge.ui.repos.ReposScreen
import com.repoforge.ui.settings.SettingsScreen
import com.repoforge.ui.conflicts.ConflictScreen
import com.repoforge.ui.local.CloneDialog
import com.repoforge.ui.local.LocalRepoScreen
import com.repoforge.ui.local.LocalReposScreen

@Composable
fun RepoForgeRoot(vm: AppViewModel) {
    val accounts by vm.accounts.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val clones by vm.clones.collectAsState()
    // Slide forward when the stack grows and backward when it shrinks; a plain holder because
    // this only records the previous depth and must not trigger recomposition.
    val lastDepth = remember { intArrayOf(vm.stack.size) }
    val forward = vm.stack.size >= lastDepth[0]
    lastDepth[0] = vm.stack.size

    BackHandler(enabled = vm.stack.size > 1) { vm.back() }

    AnimatedContent(
        targetState = vm.stack.last(),
        transitionSpec = {
            val direction = if (forward) 1 else -1
            (slideInHorizontally { it / 4 * direction } + fadeIn()) togetherWith
                (slideOutHorizontally { -it / 4 * direction } + fadeOut())
        },
        label = "screen",
    ) { screen ->
        when (screen) {
            is Screen.AddAccount -> AddAccountScreen(
                model = screen.model,
                firstRun = screen.firstRun,
                onBack = if (screen.firstRun) null else ({ vm.back() }),
            )
            Screen.Accounts -> AccountsScreen(
                accounts = accounts,
                activeId = activeId,
                onBack = { vm.back() },
                onSelect = { vm.switchAccount(it.id) },
                onRemove = vm::removeAccount,
                onAdd = vm::addAccount,
            )
            Screen.Settings -> SettingsScreen(vm.settings, onBack = { vm.back() }, onManageAccounts = vm::manageAccounts)
            is Screen.Repos -> ReposScreen(
                model = screen.model,
                accounts = accounts,
                onOpenRepo = { vm.openRepo(screen.model.account, it) },
                onSwitchAccount = { vm.switchAccount(it.id) },
                onManageAccounts = vm::manageAccounts,
                onAddAccount = vm::addAccount,
                onOpenSettings = vm::openSettings,
                onOpenLocal = vm::openLocalRepos,
            )
            is Screen.RepoHome -> RepoScreen(
                model = screen.model,
                onBack = { vm.back() },
                onOpenFile = { path -> vm.openFile(screen.model.account, screen.model.repo, screen.model.ref, path) },
                onOpenIssue = { vm.openIssue(screen.model.account, screen.model.repo, it) },
                onOpenCommit = { vm.openCommit(screen.model.account, screen.model.repo, it) },
                onNewIssue = { vm.newIssue(screen.model) },
                localClone = vm.localCloneOf(screen.model.account, screen.model.repo, clones),
                onClone = { vm.cloneRepo(screen.model.account, screen.model.repo) },
                onOpenLocal = vm::openLocalRepo,
            )
            is Screen.File -> FileScreen(model = screen.model, onBack = { vm.back() })
            is Screen.CommitDetail -> CommitScreen(model = screen.model, onBack = { vm.back() })
            is Screen.IssueDetail -> IssueScreen(
                model = screen.model,
                onBack = { vm.back() },
                onResolveConflicts = { vm.openConflicts(screen.model, it) },
            )
            is Screen.NewIssue -> NewIssueScreen(model = screen.model, onBack = { vm.back() })
            is Screen.Conflicts -> ConflictScreen(
                model = screen.model,
                onBack = { vm.back() },
                onOpenSettings = vm::openSettings,
                onDone = { vm.conflictsDone(screen) },
            )
            Screen.LocalRepos -> LocalReposScreen(clones = clones, onBack = { vm.back() }, onOpen = vm::openLocalRepo)
            is Screen.LocalRepo -> LocalRepoScreen(
                model = screen.model,
                onBack = { vm.back() },
                onOpenFile = { vm.openLocalFile(screen.model.clone, it) },
                onDelete = { vm.deleteClone(screen.model.clone) },
            )
        }
    }

    vm.cloneTask?.let { task ->
        CloneDialog(task, onDismiss = vm::dismissClone, onOpen = vm::openLocalRepo)
    }
}
