package com.repoforge.ui.settings

import android.os.Build
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
)

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
