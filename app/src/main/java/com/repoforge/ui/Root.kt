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
import com.repoforge.ui.repo.FileScreen
import com.repoforge.ui.repo.RepoScreen
import com.repoforge.ui.repos.ReposScreen

@Composable
fun RepoForgeRoot(vm: AppViewModel) {
    val accounts by vm.accounts.collectAsState()
    val activeId by vm.activeId.collectAsState()
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
            is Screen.Repos -> ReposScreen(
                model = screen.model,
                accounts = accounts,
                onOpenRepo = { vm.openRepo(screen.model.account, it) },
                onSwitchAccount = { vm.switchAccount(it.id) },
                onManageAccounts = vm::manageAccounts,
                onAddAccount = vm::addAccount,
            )
            is Screen.RepoHome -> RepoScreen(
                model = screen.model,
                onBack = { vm.back() },
                onOpenFile = { path -> vm.openFile(screen.model.account, screen.model.repo, screen.model.ref, path) },
                onOpenIssue = { vm.openIssue(screen.model.account, screen.model.repo, it) },
                onNewIssue = { vm.newIssue(screen.model) },
            )
            is Screen.File -> FileScreen(model = screen.model, onBack = { vm.back() })
            is Screen.IssueDetail -> IssueScreen(model = screen.model, onBack = { vm.back() })
            is Screen.NewIssue -> NewIssueScreen(model = screen.model, onBack = { vm.back() })
        }
    }
}
