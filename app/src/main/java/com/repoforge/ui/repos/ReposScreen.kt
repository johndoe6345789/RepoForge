package com.repoforge.ui.repos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.repoforge.R
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.model.Account
import com.repoforge.data.model.Repo
import com.repoforge.data.model.hostLabel
import com.repoforge.ui.common.Avatar
import com.repoforge.ui.common.ListContentPadding
import com.repoforge.ui.common.MetaRow
import com.repoforge.ui.common.Paged
import com.repoforge.ui.common.ProviderBadge
import com.repoforge.ui.common.pagedFooter
import com.repoforge.ui.common.relativeTime
import kotlinx.coroutines.CoroutineScope

class ReposModel(private val scope: CoroutineScope, val account: Account, private val client: ForgeClient) {
    val repos = Paged(scope) { client.listRepos(it) }
    var query by mutableStateOf("")
    /** Server-wide search results for [searchedFor], once the user asks for them. */
    var search by mutableStateOf<Paged<Repo>?>(null)
        private set
    var searchedFor by mutableStateOf("")
        private set

    fun onQueryChange(value: String) {
        query = value
        if (value.isBlank()) search = null
    }

    fun searchServer() {
        val q = query.trim()
        if (q.isEmpty()) return
        searchedFor = q
        search = Paged(scope) { client.searchRepos(q, it) }
    }

    fun refresh() {
        search?.refresh() ?: repos.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(
    model: ReposModel,
    accounts: List<Account>,
    onOpenRepo: (Repo) -> Unit,
    onSwitchAccount: (Account) -> Unit,
    onManageAccounts: () -> Unit,
    onAddAccount: () -> Unit,
) {
    val account = model.account
    var menuOpen by remember { mutableStateOf(false) }
    val search = model.search
    val list = search ?: model.repos
    val query = model.query.trim()
    val visible = if (search == null && query.isNotEmpty()) {
        model.repos.items.filter { it.fullName.contains(query, ignoreCase = true) || it.description?.contains(query, true) == true }
    } else {
        list.items
    }

    LaunchedEffect(model) { if (model.repos.items.isEmpty()) model.repos.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Avatar(account.avatarUrl, account.login, size = 32.dp) }
                        AccountMenu(menuOpen, accounts, account, { menuOpen = false }, onSwitchAccount, onManageAccounts, onAddAccount)
                    }
                },
                title = {
                    Column {
                        Text("Repositories")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProviderBadge(account.type)
                            Text(
                                "${account.login} · ${hostLabel(account.host)}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = list.refreshing,
            onRefresh = model::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = ListContentPadding) {
                item(key = "search") {
                    OutlinedTextField(
                        value = model.query,
                        onValueChange = model::onQueryChange,
                        placeholder = { Text("Filter your repositories") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (model.query.isNotEmpty()) {
                                IconButton(onClick = { model.onQueryChange("") }) { Icon(Icons.Filled.Clear, "Clear") }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (query.isNotEmpty() && model.searchedFor != query) {
                    item(key = "search-server") {
                        TextButton(onClick = model::searchServer, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text("Search all of ${hostLabel(account.host)} for “$query”")
                        }
                    }
                }
                if (search != null) {
                    item(key = "search-header") {
                        Text(
                            "Results for “${model.searchedFor}”",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(visible, key = { "repo:" + it.apiId }) { repo ->
                    RepoRow(repo, onClick = { onOpenRepo(repo) })
                    HorizontalDivider()
                }
                if (search != null || query.isEmpty()) {
                    pagedFooter(list, emptyMessage = if (search != null) "No repositories found" else "No repositories yet")
                } else if (visible.isEmpty() && model.repos.endReached) {
                    item(key = "no-match") {
                        Text(
                            "None of your repositories match. Try searching the whole server.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // Keep loading the user's repositories so the filter covers all of them.
                    pagedFooter(model.repos, emptyMessage = "")
                }
            }
        }
    }
}

@Composable
private fun AccountMenu(
    expanded: Boolean,
    accounts: List<Account>,
    current: Account,
    onDismiss: () -> Unit,
    onSwitch: (Account) -> Unit,
    onManage: () -> Unit,
    onAdd: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        accounts.forEach { account ->
            DropdownMenuItem(
                leadingIcon = { Avatar(account.avatarUrl, account.login, size = 28.dp) },
                text = {
                    Column {
                        Text(account.login, fontWeight = if (account.id == current.id) FontWeight.Bold else null)
                        Text("${account.type.displayName} · ${hostLabel(account.host)}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                onClick = { onDismiss(); if (account.id != current.id) onSwitch(account) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add account") }, onClick = { onDismiss(); onAdd() })
        DropdownMenuItem(leadingIcon = { Icon(Icons.Filled.Settings, null) }, text = { Text("Manage accounts") }, onClick = { onDismiss(); onManage() })
    }
}

@Composable
fun RepoRow(repo: Repo, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(repo.ownerAvatarUrl, repo.owner, size = 40.dp) },
        overlineContent = { Text(repo.owner, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(repo.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (repo.isPrivate) Icon(Icons.Filled.Lock, "Private", Modifier.size(14.dp))
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repo.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                MetaRow {
                    if (repo.isFork) {
                        Icon(painterResource(R.drawable.ic_branch), "Fork", Modifier.size(14.dp))
                    }
                    repo.language?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    repo.stars?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, null, Modifier.size(14.dp))
                            Text(" $it", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    repo.updatedAt?.let { Text("Updated ${relativeTime(it)}", style = MaterialTheme.typography.labelSmall) }
                }
            }
        },
    )
}
