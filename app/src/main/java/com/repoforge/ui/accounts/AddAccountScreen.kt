package com.repoforge.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.repoforge.R
import com.repoforge.data.account.AccountStore
import com.repoforge.data.forge.ForgeClients
import com.repoforge.data.model.Account
import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.hostLabel
import com.repoforge.ui.common.brandColor
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
            if (!firstRun) {
                TopAppBar(
                    title = { Text("Add account") },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        }
                    },
                )
            }
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
            if (firstRun) Hero()

            Text("Choose a service", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ForgeType.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { ServiceCard(it, selected = model.type == it, onClick = { model.selectType(it) }) }
                }
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
                isError = model.error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.signIn() }),
                modifier = Modifier.fillMaxWidth(),
            )

            model.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            PrimaryAction(
                text = "Sign in",
                enabled = model.canSubmit,
                busy = model.busy,
                onClick = model::signIn,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Getting a token", style = MaterialTheme.typography.titleSmall)
                    Text(ForgeClients.tokenHelp(model.type), style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = { openUrl(context, ForgeClients.tokenPageUrl(model.type, model.host.ifBlank { model.type.hostHint })) },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_open_in_browser), null, Modifier.size(18.dp))
                        Text("Create a ${model.type.displayName.substringBefore(' ')} token", Modifier.padding(start = 8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Encrypted on this device with the Android Keystore, and only sent to the server above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero() {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_foreground),
                null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(88.dp),
            )
        }
        Text("RepoForge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Your repositories on GitHub, GitLab, Bitbucket, Gitea and Forgejo — in one app.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RowScope.ServiceCard(type: ForgeType, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    OutlinedCard(
        onClick = onClick,
        border = border,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.weight(1f).semantics { this.selected = selected },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(brandColor(type)),
                contentAlignment = Alignment.Center,
            ) {
                Text(type.displayName.take(1), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(type.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    type.defaultHost?.let(::hostLabel) ?: "Self-hosted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
