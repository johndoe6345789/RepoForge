package com.repoforge.ui.issue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.model.Account
import com.repoforge.data.model.Issue
import com.repoforge.data.model.Repo
import com.repoforge.ui.common.Loadable
import com.repoforge.ui.common.PrimaryAction
import com.repoforge.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NewPullRequestModel(
    private val scope: CoroutineScope,
    val account: Account,
    private val client: ForgeClient,
    val repo: Repo,
    /** Branch the form starts on as the source branch (the one being viewed in the repository). */
    initialHead: String,
    private val onCreated: (Issue) -> Unit,
) {
    val branches = Loadable(scope) { client.listBranches(repo).map { it.name } }
    var head by mutableStateOf(initialHead)
    var base by mutableStateOf(repo.defaultBranch ?: "main")
    var title by mutableStateOf("")
    var body by mutableStateOf("")
    /** Open as a draft: not ready for review, and can't be merged until marked ready. */
    var draft by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val canSubmit: Boolean get() = title.isNotBlank() && head.isNotBlank() && base.isNotBlank() && head != base

    fun submit() {
        if (!canSubmit || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onCreated(client.createPullRequest(repo, title.trim(), body.trim(), head, base, draft))
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
fun NewPullRequestScreen(model: NewPullRequestModel, onBack: () -> Unit) {
    LaunchedEffect(model) { model.branches.ensureLoaded() }
    val noun = model.account.type.pullRequestName.removeSuffix("s").lowercase()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.Close, "Cancel") } },
                title = {
                    Column {
                        Text("New $noun")
                        Text(model.repo.fullName, style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val names = model.branches.value.orEmpty()
            BranchField("From branch", model.head, names) { model.head = it }
            BranchField("Into branch", model.base, names) { model.base = it }
            if (model.head == model.base) {
                Text("Pick two different branches.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = model.title,
                onValueChange = { model.title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model.body,
                onValueChange = { model.body = it },
                label = { Text("Description (Markdown)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            Row(
                Modifier.fillMaxWidth().clickable { model.draft = !model.draft },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = model.draft, onCheckedChange = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Create as draft")
                    Text(
                        "A draft can't be merged until you mark it ready for review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryAction(
                if (model.draft) "Create draft $noun" else "Create $noun",
                enabled = model.canSubmit,
                busy = model.busy,
                onClick = model::submit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchField(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(name); open = false })
            }
        }
    }
}
