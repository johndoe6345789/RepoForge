package com.repoforge.ui.conflicts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.repoforge.data.ai.ConflictAi
import com.repoforge.data.ai.PullContext
import com.repoforge.data.git.ConflictFile
import com.repoforge.data.git.ConflictKind
import com.repoforge.data.git.ConflictMarkers
import com.repoforge.data.git.GitProgress
import com.repoforge.data.git.MergeWorkspace
import com.repoforge.data.git.PrepareResult
import com.repoforge.data.model.PullDetail
import com.repoforge.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.eclipse.jgit.lib.PersonIdent

enum class Phase { PREPARING, CONFLICTS, CLEAN, UP_TO_DATE, PUSHING, DONE, FAILED }

/** How a conflicted file will be resolved. */
sealed interface Resolution {
    data object Unresolved : Resolution
    data object Working : Resolution
    /** New file text, from Claude or typed in. */
    data class Text(val content: String, val byAi: Boolean, val hunks: List<String> = emptyList(), val explanation: String? = null) : Resolution
    /** Keep one branch's version as-is (or its deletion). */
    data class KeepSide(val head: Boolean) : Resolution
}

class FileState(val file: ConflictFile, val segments: List<ConflictMarkers.Segment>?) {
    var resolution by mutableStateOf<Resolution>(Resolution.Unresolved)
    var error by mutableStateOf<String?>(null)
    val canUseAi: Boolean get() = file.kind == ConflictKind.CONTENT && !segments.isNullOrEmpty()
    val isResolved: Boolean get() = resolution is Resolution.Text || resolution is Resolution.KeepSide

    /** Text for the editor: the current resolution, or the file with its markers. */
    val editableText: String
        get() = (resolution as? Resolution.Text)?.content ?: file.withMarkers ?: file.head ?: file.base.orEmpty()
}

/**
 * Updates a pull request branch with its base branch on the device, lets Claude propose
 * resolutions for the conflicts, and pushes the result once every file is resolved.
 */
class ConflictModel(
    private val scope: CoroutineScope,
    val detail: PullDetail,
    private val workspace: MergeWorkspace,
    /** Null when no Anthropic API key is configured. */
    private val ai: ConflictAi?,
    private val author: PersonIdent,
) {
    var phase by mutableStateOf(Phase.PREPARING)
        private set
    var progress by mutableStateOf<GitProgress?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val files = mutableStateListOf<FileState>()
    var commitMessage by mutableStateOf(workspace.defaultMessage())
    var pushedCommit by mutableStateOf<String?>(null)
        private set
    val hasAi: Boolean get() = ai != null
    val allResolved: Boolean get() = files.all { it.isResolved }
    private var job: Job? = null

    private val context = PullContext(detail.pull.title, detail.pull.body, detail.headBranch, detail.baseBranch)

    init {
        prepare()
    }

    fun prepare() {
        job?.cancel()
        phase = Phase.PREPARING
        error = null
        files.clear()
        job = scope.launch {
            try {
                when (val result = workspace.prepare { progress = it }) {
                    PrepareResult.UpToDate -> phase = Phase.UP_TO_DATE
                    PrepareResult.Clean -> phase = Phase.CLEAN
                    is PrepareResult.Conflicts -> {
                        files += result.files.map { FileState(it, it.withMarkers?.let(ConflictMarkers::parse)) }
                        phase = Phase.CONFLICTS
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
                phase = Phase.FAILED
            }
        }
    }

    fun resolveWithAi(state: FileState) {
        val resolver = ai ?: return
        val segments = state.segments ?: return
        if (state.resolution == Resolution.Working) return
        state.resolution = Resolution.Working
        state.error = null
        scope.launch { runAi(resolver, state, segments) }
    }

    /** Resolves every file Claude can handle that isn't resolved yet, a few at a time. */
    fun resolveAllWithAi() {
        val resolver = ai ?: return
        val pending = files.filter { it.canUseAi && !it.isResolved && it.resolution != Resolution.Working }
        pending.forEach { it.resolution = Resolution.Working; it.error = null }
        val limit = Semaphore(3)
        pending.forEach { state -> scope.launch { limit.withPermit { runAi(resolver, state, state.segments!!) } } }
    }

    private suspend fun runAi(resolver: ConflictAi, state: FileState, segments: List<ConflictMarkers.Segment>) {
        try {
            val result = resolver.resolve(state.file, segments, context)
            state.resolution = Resolution.Text(
                content = ConflictMarkers.assemble(segments, result.hunks),
                byAi = true,
                hunks = result.hunks,
                explanation = result.explanation,
            )
        } catch (e: CancellationException) {
            state.resolution = Resolution.Unresolved
            throw e
        } catch (e: Exception) {
            state.resolution = Resolution.Unresolved
            state.error = e.userMessage()
        }
    }

    fun keepSide(state: FileState, head: Boolean) {
        state.resolution = Resolution.KeepSide(head)
        state.error = null
    }

    fun setText(state: FileState, text: String) {
        state.resolution = Resolution.Text(text, byAi = false)
        state.error = null
    }

    fun reset(state: FileState) {
        state.resolution = Resolution.Unresolved
    }

    fun commitAndPush() {
        if (phase != Phase.CONFLICTS && phase != Phase.CLEAN) return
        if (!allResolved) return
        val previous = phase
        phase = Phase.PUSHING
        error = null
        job = scope.launch {
            try {
                for (state in files) {
                    when (val r = state.resolution) {
                        is Resolution.Text -> workspace.resolve(state.file.path, r.content)
                        is Resolution.KeepSide -> workspace.resolveWithSide(state.file.path, keepHead = r.head)
                        else -> Unit
                    }
                }
                pushedCommit = workspace.commitAndPush(commitMessage.ifBlank { workspace.defaultMessage() }, author) { progress = it }
                phase = Phase.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
                phase = previous
            }
        }
    }

    /** Called when the screen closes without pushing, so the workspace is left clean. */
    fun discard() {
        if (phase == Phase.DONE) return
        job?.cancel()
        scope.launch { runCatching { workspace.abort() } }
    }
}

/** Text with conflict markers still in it shouldn't be committed by accident. */
fun hasConflictMarkers(text: String): Boolean =
    text.lineSequence().any { it.startsWith("<<<<<<<") || it.startsWith(">>>>>>>") || it == "=======" }
