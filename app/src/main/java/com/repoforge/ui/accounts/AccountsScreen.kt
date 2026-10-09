package com.repoforge.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.repoforge.data.model.Account
import com.repoforge.data.model.hostLabel
import com.repoforge.ui.common.Avatar
import com.repoforge.ui.common.ListContentPadding
import com.repoforge.ui.common.ProviderBadge
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    accounts: List<Account>,
    activeId: String?,
    onBack: () -> Unit,
    onSelect: (Account) -> Unit,
    onRemove: (Account) -> Unit,
    onAdd: () -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<Account?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add account") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ListContentPadding) {
            items(accounts, key = { it.id }) { account ->
                val active = account.id == activeId || (activeId == null && account == accounts.first())
                ListItem(
                    leadingContent = { Avatar(account.avatarUrl, account.login) },
                    headlineContent = { Text(account.displayName ?: account.login) },
                    supportingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProviderBadge(account.type)
                            Text("${account.login} · ${hostLabel(account.host)}", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (active) Icon(Icons.Filled.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary)
                            IconButton(onClick = { pendingRemoval = account }) { Icon(Icons.Filled.Delete, "Sign out") }
                        }
                    },
                    modifier = Modifier.clickable { onSelect(account) },
                )
            }
        }
    }

    pendingRemoval?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Sign out?") },
            text = { Text("Remove ${account.label} from RepoForge. The token stays valid on the server until you revoke it there.") },
            confirmButton = {
                TextButton(onClick = { onRemove(account); pendingRemoval = null }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}
