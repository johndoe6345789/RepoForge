package com.repoforge.data.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.repoforge.data.git.ConflictFile
import com.repoforge.data.git.ConflictMarkers
import com.repoforge.data.git.ConflictMarkers.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Duration

/** A failure from the AI step, with a message fit to show the user. */
class AiException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What the pull request is about, so resolutions keep its intent. */
data class PullContext(val title: String, val description: String?, val headBranch: String, val baseBranch: String)

/** Replacement text for each conflict hunk, in file order, plus Claude's reasoning in brief. */
data class AiResolution(val hunks: List<String>, val explanation: String)

/** Models offered in Settings. Opus is the default; the others trade quality for speed and cost. */
enum class ClaudeModel(val id: String, val label: String, val serverFallback: Boolean) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", serverFallback = true),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", serverFallback = true),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", serverFallback = false),
}

fun interface ConflictAi {
    suspend fun resolve(file: ConflictFile, segments: List<Segment>, context: PullContext): AiResolution
}

/**
 * Resolves conflict hunks with the Claude API. Only the hunks come back (not the whole file),
 * which keeps responses short; [ConflictMarkers.assemble] puts the file back together.
 */
class ClaudeConflictResolver(
    apiKey: String,
    private val model: ClaudeModel = ClaudeModel.OPUS,
    baseUrl: String? = null,
) : ConflictAi {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    override suspend fun resolve(file: ConflictFile, segments: List<Segment>, context: PullContext): AiResolution =
        withContext(Dispatchers.IO) {
            val hunkCount = ConflictMarkers.hunks(segments).size
            if (hunkCount == 0) throw AiException("${file.path} has no conflict markers to resolve")
            val message = try {
                client.messages().create(buildParams(file, segments, context))
            } catch (e: UnauthorizedException) {
                throw AiException("The Anthropic API key was rejected. Check it in Settings.", e)
            } catch (e: PermissionDeniedException) {
                throw AiException("The Anthropic API key can't use ${model.label}.", e)
            } catch (e: RateLimitException) {
                throw AiException("Rate limited by the Anthropic API. Try again in a minute.", e)
            } catch (e: AnthropicServiceException) {
                throw AiException("The Anthropic API returned an error (HTTP ${e.statusCode()}).", e)
            } catch (e: AnthropicIoException) {
                throw AiException("Couldn't reach the Anthropic API: ${e.message}", e)
            }
            parse(message, hunkCount)
        }

    internal fun buildParams(file: ConflictFile, segments: List<Segment>, context: PullContext): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.HIGH)
                    .format(JsonOutputFormat.builder().schema(RESOLUTION_SCHEMA).build())
                    .build()
            )
            .addUserMessage(userPrompt(file, segments, context))
            .apply {
                if (model.serverFallback) {
                    // If a safety classifier declines, Anthropic retries on its recommended fallback model.
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

    internal fun parse(message: Message, hunkCount: Int): AiResolution {
        when (message.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw AiException("Claude declined to resolve this file. Resolve it manually.")
            StopReason.MAX_TOKENS -> throw AiException("The conflicts in this file are too large to resolve with AI. Resolve it manually.")
            else -> Unit
        }
        val texts = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
        val json = texts.asReversed().firstNotNullOfOrNull { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        } ?: throw AiException("Claude's answer couldn't be read. Try again.")
        return toResolution(json, hunkCount)
    }

    companion object {
        internal val SYSTEM_PROMPT = """
            You resolve git merge conflicts in a pull request. The pull request's branch ("head") is being updated with
            new commits from the branch it will merge into ("base"). Each conflict block shows the head side and the
            base side of the same region.

            For every numbered conflict, write the text that should replace the whole block so that the file keeps the
            intent of both sides: usually that means combining the two changes. When they genuinely contradict, keep
            the base branch's behavior unless the pull request's purpose depends on its own version, and say which way
            you went in the explanation. Use the common ancestor (when given) to see what each side changed.

            Replacements must be exactly the lines that belong in the file: no conflict markers, no code fences, the
            surrounding indentation and line endings preserved. An empty replacement removes the block. Keep the
            explanation to two or three sentences a reviewer can check quickly.
        """.trimIndent()

        internal val RESOLUTION_SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty(
                "properties",
                JsonValue.from(
                    mapOf(
                        "resolutions" to mapOf(
                            "type" to "array",
                            "items" to mapOf(
                                "type" to "object",
                                "properties" to mapOf(
                                    "conflict" to mapOf("type" to "integer", "description" to "Conflict number, starting at 1"),
                                    "replacement" to mapOf("type" to "string", "description" to "Text replacing the whole conflict block"),
                                ),
                                "required" to listOf("conflict", "replacement"),
                                "additionalProperties" to false,
                            ),
                        ),
                        "explanation" to mapOf("type" to "string"),
                    )
                ),
            )
            .putAdditionalProperty("required", JsonValue.from(listOf("resolutions", "explanation")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()

        internal fun userPrompt(file: ConflictFile, segments: List<Segment>, context: PullContext): String = buildString {
            appendLine("File: ${file.path}")
            appendLine("Pull request: ${context.title}")
            context.description?.takeIf { it.isNotBlank() }?.let {
                appendLine("Pull request description:")
                appendLine("<description>")
                appendLine(it.trim())
                appendLine("</description>")
            }
            appendLine("Head branch (the pull request): ${context.headBranch}")
            appendLine("Base branch (being merged in): ${context.baseBranch}")
            appendLine()
            file.ancestor?.let {
                appendLine("Common ancestor version of the file, before either side changed it:")
                appendLine("<ancestor>")
                append(it)
                if (!it.endsWith("\n")) appendLine()
                appendLine("</ancestor>")
                appendLine()
            }
            appendLine("The file with its conflicts, numbered:")
            appendLine("<file>")
            append(renderNumbered(segments, context))
            appendLine("</file>")
        }

        /** The file with each conflict block labeled by number and side. */
        internal fun renderNumbered(segments: List<Segment>, context: PullContext): String = buildString {
            var n = 0
            for (segment in segments) {
                when (segment) {
                    is Segment.Text -> append(segment.text)
                    is Segment.Hunk -> {
                        n++
                        appendLine("<<<<<<< CONFLICT $n: head (${context.headBranch})")
                        append(segment.head)
                        if (segment.head.isNotEmpty() && !segment.head.endsWith("\n")) appendLine()
                        appendLine("=======  base (${context.baseBranch})")
                        append(segment.base)
                        if (segment.base.isNotEmpty() && !segment.base.endsWith("\n")) appendLine()
                        appendLine(">>>>>>> END CONFLICT $n")
                    }
                }
            }
            if (isNotEmpty() && !endsWith("\n")) appendLine()
        }

        internal fun toResolution(json: JsonObject, hunkCount: Int): AiResolution {
            val byNumber = json["resolutions"]?.jsonArray.orEmpty().associate { item ->
                val obj = item.jsonObject
                obj.getValue("conflict").jsonPrimitive.int to obj.getValue("replacement").jsonPrimitive.content
            }
            val missing = (1..hunkCount).filterNot { it in byNumber }
            if (missing.isNotEmpty()) {
                throw AiException("Claude skipped conflict ${missing.joinToString()}. Try again or resolve it manually.")
            }
            return AiResolution(
                hunks = (1..hunkCount).map { byNumber.getValue(it) },
                explanation = json["explanation"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }
}
