package com.repoforge.ui.issue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.repoforge.data.forge.ForgeClient
import com.repoforge.data.model.Issue
import com.repoforge.data.model.Repo
import com.repoforge.ui.common.PrimaryAction
import com.repoforge.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NewIssueModel(
    private val scope: CoroutineScope,
    private val client: ForgeClient,
    val repo: Repo,
    private val onCreated: (Issue) -> Unit,
) {
    var title by mutableStateOf("")
    var body by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun submit() {
        if (title.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onCreated(client.createIssue(repo, title.trim(), body.trim()))
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
fun NewIssueScreen(model: NewIssueModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.Close, "Cancel") } },
                title = {
                    Column {
                        Text("New issue")
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
            )
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryAction("Create issue", enabled = model.title.isNotBlank(), busy = model.busy, onClick = model::submit, modifier = Modifier.fillMaxWidth())
        }
    }
}
