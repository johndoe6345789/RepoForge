package com.repoforge.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.repoforge.data.account.AccountStore
import com.repoforge.data.forge.ForgeClients
import com.repoforge.data.model.Account
import com.repoforge.data.model.ForgeType
import com.repoforge.ui.common.PrimaryAction
import com.repoforge.ui.common.openUrl
import com.repoforge.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID

class AddAccountModel(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val onSignedIn: (Account) -> Unit,
) {
    var type by mutableStateOf(ForgeType.GITHUB)
        private set
    var host by mutableStateOf(ForgeType.GITHUB.hostHint)
    var email by mutableStateOf("")
    var token by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun selectType(newType: ForgeType) {
        type = newType
        host = newType.hostHint
        error = null
    }

    val isInsecure: Boolean get() = host.trim().startsWith("http://")

    val canSubmit: Boolean get() = token.isNotBlank() && host.isNotBlank()

    fun signIn() {
        if (!canSubmit || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val normalizedHost = ForgeClients.normalizeHost(host)
                val authUser = email.trim().takeIf { type == ForgeType.BITBUCKET && it.isNotEmpty() }
                val client = ForgeClients.create(type, normalizedHost, authUser, token.trim(), http)
                val user = client.currentUser()
                val login = user.login.ifBlank { authUser ?: UUID.randomUUID().toString().take(8) }
                onSignedIn(
                    Account(
                        id = AccountStore.accountId(type, normalizedHost, login),
                        type = type,
                        host = normalizedHost,
                        login = login,
                        displayName = user.name,
                        avatarUrl = user.avatarUrl,
                        authUser = authUser,
                        token = token.trim(),
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(model: AddAccountModel, firstRun: Boolean, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    var showToken by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (firstRun) "Welcome to RepoForge" else "Add account") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (firstRun) {
                Text(
                    "Browse code, commits, issues and pull requests on GitHub, GitLab, Bitbucket, Gitea and Forgejo. " +
                        "Sign in to as many accounts as you like and switch between them.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Text("Service", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ForgeType.entries.take(2).forEach { ServiceChip(it, model) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ForgeType.entries.drop(2).forEach { ServiceChip(it, model) }
            }

            if (model.type.selfHostable) {
                OutlinedTextField(
                    value = model.host,
                    onValueChange = { model.host = it },
                    label = { Text("Server") },
                    supportingText = {
                        Text(
                            when (model.type) {
                                ForgeType.GITEA -> "Your Gitea or Forgejo server, e.g. https://codeberg.org"
                                ForgeType.GITHUB -> "github.com, or your GitHub Enterprise Server"
                                else -> "gitlab.com, or your self-managed GitLab"
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (model.isInsecure) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        "This server uses plain HTTP, so your token will be sent unencrypted.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (model.type == ForgeType.BITBUCKET) {
                OutlinedTextField(
                    value = model.email,
                    onValueChange = { model.email = it },
                    label = { Text("Atlassian account e-mail") },
                    supportingText = { Text("Leave empty for a workspace or repository access token") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = model.token,
                onValueChange = { model.token = it },
                label = { Text(if (model.type == ForgeType.BITBUCKET) "API token" else "Access token") },
                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
                },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ForgeClients.tokenHelp(model.type), style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = { openUrl(context, ForgeClients.tokenPageUrl(model.type, model.host.ifBlank { model.type.hostHint })) },
                        modifier = Modifier.padding(start = 0.dp),
                    ) { Text("Create a token") }
                    Text(
                        "Tokens are encrypted on this device with the Android Keystore and only sent to the server above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            PrimaryAction(
                text = "Sign in",
                enabled = model.canSubmit,
                busy = model.busy,
                onClick = model::signIn,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ServiceChip(type: ForgeType, model: AddAccountModel) {
    FilterChip(
        selected = model.type == type,
        onClick = { model.selectType(type) },
        label = { Text(type.displayName) },
        modifier = Modifier.weight(1f),
    )
}
