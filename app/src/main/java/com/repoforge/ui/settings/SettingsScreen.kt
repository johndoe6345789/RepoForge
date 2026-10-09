package com.repoforge.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.repoforge.data.ai.ClaudeModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.repoforge.BuildConfig
import com.repoforge.R
import com.repoforge.data.AppSettings
import com.repoforge.data.ThemeMode
import com.repoforge.ui.common.SectionHeader
import com.repoforge.ui.common.openUrl

private const val SOURCE_URL = "https://github.com/johndoe6345789/RepoForge"

private val LICENSES = listOf(
    "AndroidX, Jetpack Compose, Material 3" to "Apache 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache 2.0",
    "OkHttp" to "Apache 2.0",
    "Coil" to "Apache 2.0",
    "Multiplatform Markdown Renderer (Mike Penz)" to "Apache 2.0",
    "JetBrains Markdown" to "Apache 2.0",
    "Highlights (SnipMe)" to "Apache 2.0",
    "Eclipse JGit" to "Eclipse Distribution License 1.0",
    "Anthropic Java SDK" to "MIT",
)

private const val ANTHROPIC_KEYS_URL = "https://console.anthropic.com/settings/keys"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit, onManageAccounts: () -> Unit) {
    val context = LocalContext.current
    val themeMode by settings.themeMode.collectAsState()
    val dynamicColor by settings.dynamicColor.collectAsState()
    var showLicenses by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Appearance")
            ListItem(
                leadingContent = { Icon(painterResource(R.drawable.ic_palette), null) },
                headlineContent = { Text("Theme") },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { settings.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                            ) {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.LIGHT -> "Light"
                                        ThemeMode.DARK -> "Dark"
                                    }
                                )
                            }
                        }
                    }
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    modifier = Modifier.clickable { settings.setDynamicColor(!dynamicColor) },
                    headlineContent = { Text("Dynamic colour") },
                    supportingContent = { Text("Match the app's colours to your wallpaper") },
                    trailingContent = { Switch(checked = dynamicColor, onCheckedChange = settings::setDynamicColor) },
                )
            }

            SectionHeader("Accounts")
            ListItem(
                modifier = Modifier.clickable(onClick = onManageAccounts),
                leadingContent = { Icon(Icons.Filled.AccountCircle, null) },
                headlineContent = { Text("Manage accounts") },
                supportingContent = { Text("Add, switch or sign out of accounts") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )

            AiSection(settings)
            GitSection(settings)

            SectionHeader("About")
            ListItem(
                leadingContent = { Icon(Icons.Filled.Info, null) },
                headlineContent = { Text("RepoForge ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text("GitHub, GitLab, Bitbucket, Gitea and Forgejo in one app") },
            )
            ListItem(
                modifier = Modifier.clickable { openUrl(context, SOURCE_URL) },
                leadingContent = { Icon(painterResource(R.drawable.ic_open_in_browser), null) },
                headlineContent = { Text("Source code") },
                supportingContent = { Text(SOURCE_URL.removePrefix("https://")) },
            )
            ListItem(
                modifier = Modifier.clickable { showLicenses = true },
                leadingContent = { Icon(painterResource(R.drawable.ic_file), null) },
                headlineContent = { Text("Open-source licences") },
            )
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open-source licences") },
            text = {
                Column {
                    LICENSES.forEach { (name, license) ->
                        ListItem(headlineContent = { Text(name) }, supportingContent = { Text(license) })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun AiSection(settings: AppSettings) {
    val context = LocalContext.current
    val hasKey by settings.hasAiKey.collectAsState()
    val model by settings.aiModel.collectAsState()
    var editKey by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }

    SectionHeader("Conflict resolution with Claude")
    ListItem(
        modifier = Modifier.clickable { editKey = true },
        leadingContent = { Icon(painterResource(R.drawable.ic_key), null) },
        headlineContent = { Text("Anthropic API key") },
        supportingContent = {
            Text(if (hasKey) "Saved, encrypted on this device" else "Needed to let Claude propose merge-conflict resolutions")
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    ListItem(
        modifier = Modifier.clickable { pickModel = true },
        leadingContent = { Icon(painterResource(R.drawable.ic_sparkle), null) },
        headlineContent = { Text("Model") },
        supportingContent = { Text(model.label) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )

    if (editKey) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editKey = false },
            title = { Text("Anthropic API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "RepoForge sends the conflicting parts of a file, with the pull request's title and description, " +
                            "to the Claude API using your key. Usage is billed to your Anthropic account.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.trim() },
                        label = { Text(if (hasKey) "Replace key" else "Key") },
                        placeholder = { Text("sk-ant-…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { openUrl(context, ANTHROPIC_KEYS_URL) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Create a key in the Claude Console")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { settings.setAiKey(key); editKey = false }, enabled = key.isNotEmpty()) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (hasKey) TextButton(onClick = { settings.setAiKey(null); editKey = false }) { Text("Remove") }
                    TextButton(onClick = { editKey = false }) { Text("Cancel") }
                }
            },
        )
    }
    if (pickModel) {
        AlertDialog(
            onDismissRequest = { pickModel = false },
            title = { Text("Model") },
            text = {
                Column {
                    ClaudeModel.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable { settings.setAiModel(option); pickModel = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == model, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(option.label)
                                Text(
                                    when (option) {
                                        ClaudeModel.OPUS -> "Most capable; best for tricky conflicts"
                                        ClaudeModel.SONNET -> "Fast and capable"
                                        ClaudeModel.HAIKU -> "Fastest and cheapest, for simple conflicts"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickModel = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun GitSection(settings: AppSettings) {
    val context = LocalContext.current
    val name by settings.gitName.collectAsState()
    val email by settings.gitEmail.collectAsState()
    val shared by settings.sharedClones.collectAsState()
    var editIdentity by remember { mutableStateOf(false) }
    // Re-checked when returning from the system settings page.
    var hasAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    LifecycleResumeEffect(Unit) {
        hasAccess = hasAllFilesAccess()
        onPauseOrDispose { }
    }

    SectionHeader("Git")
    ListItem(
        modifier = Modifier.clickable { editIdentity = true },
        leadingContent = { Icon(painterResource(R.drawable.ic_commit), null) },
        headlineContent = { Text("Commit author") },
        supportingContent = {
            Text(if (name.isBlank() && email.isBlank()) "Your account's name and no-reply address" else "$name <$email>")
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ListItem(
            modifier = Modifier.clickable { settings.setSharedClones(!shared) },
            leadingContent = { Icon(painterResource(R.drawable.ic_folder), null) },
            headlineContent = { Text("Clone into Documents/RepoForge") },
            supportingContent = {
                Text(
                    when {
                        !shared -> "Clones are kept in the app's own storage"
                        hasAccess -> "New clones are visible to file managers and editors"
                        else -> "Needs \"All files access\" — tap Allow below"
                    }
                )
            },
            trailingContent = { Switch(checked = shared, onCheckedChange = settings::setSharedClones) },
        )
        if (shared && !hasAccess) {
            ListItem(
                headlineContent = { Text("Allow all files access") },
                supportingContent = { Text("Until then, clones stay in app storage.") },
                trailingContent = {
                    FilledTonalButton(onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
                        runCatching { context.startActivity(intent) }
                            .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    }) { Text("Allow") }
                },
            )
        }
    }

    if (editIdentity) {
        var newName by remember { mutableStateOf(name) }
        var newEmail by remember { mutableStateOf(email) }
        AlertDialog(
            onDismissRequest = { editIdentity = false },
            title = { Text("Commit author") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Used for commits made on this device. Leave empty to use each account's name and no-reply address.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(
                        value = newEmail,
                        onValueChange = { newEmail = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { settings.setGitIdentity(newName, newEmail); editIdentity = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editIdentity = false }) { Text("Cancel") } },
        )
    }
}

// runCatching: some environments (e.g. Robolectric) have no external storage volumes to ask about.
private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
