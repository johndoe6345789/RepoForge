package com.repoforge.data.forge

import com.repoforge.data.model.CiArtifact
import com.repoforge.data.model.CiJob
import com.repoforge.data.model.CiRun
import com.repoforge.data.model.CiStatus
import com.repoforge.data.model.CiStep
import com.repoforge.data.net.arr
import com.repoforge.data.net.bool
import com.repoforge.data.net.instant
import com.repoforge.data.net.int
import com.repoforge.data.net.long
import com.repoforge.data.net.obj
import com.repoforge.data.net.objects
import com.repoforge.data.net.str
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Parsers for GitHub Actions payloads. Gitea's API copies them, and Forgejo's own run format
 * (title, index_in_repo, prettyref, created/stopped…) is accepted too.
 */
object GitHubCi {

    /** GitHub splits state into status + conclusion; Forgejo reports the outcome in status. */
    fun status(status: String?, conclusion: String?): CiStatus = when (status?.lowercase()) {
        "queued", "waiting", "requested", "pending", "blocked" -> CiStatus.QUEUED
        "in_progress", "running" -> CiStatus.RUNNING
        "success" -> CiStatus.SUCCESS
        "failure" -> CiStatus.FAILURE
        "cancelled" -> CiStatus.CANCELLED
        "skipped" -> CiStatus.SKIPPED
        else -> conclusion(conclusion)
    }

    private fun conclusion(value: String?): CiStatus = when (value?.lowercase()) {
        "success" -> CiStatus.SUCCESS
        "failure", "timed_out", "startup_failure" -> CiStatus.FAILURE
        "cancelled" -> CiStatus.CANCELLED
        "skipped" -> CiStatus.SKIPPED
        "neutral", "stale" -> CiStatus.NEUTRAL
        "action_required" -> CiStatus.ACTION_REQUIRED
        else -> CiStatus.UNKNOWN
    }

    fun run(json: JsonObject): CiRun {
        val status = status(json.str("status"), json.str("conclusion"))
        val hasDisplayTitle = json.str("display_title") != null
        return CiRun(
            id = json.long("id")?.toString().orEmpty(),
            number = json.long("run_number") ?: json.long("index_in_repo"),
            title = json.str("display_title") ?: json.str("title") ?: json.str("name") ?: "Workflow run",
            // On GitHub "name" is the workflow's name; Gitea/Forgejo give the workflow file instead.
            workflow = (if (hasDisplayTitle) json.str("name") else null)
                ?: json.str("path")?.substringAfterLast('/')?.substringBefore('@')
                ?: json.str("workflow_id"),
            branch = json.str("head_branch") ?: json.str("prettyref"),
            sha = json.str("head_sha") ?: json.str("commit_sha"),
            event = json.str("trigger_event") ?: json.str("event"),
            status = status,
            actor = (json.obj("triggering_actor") ?: json.obj("actor") ?: json.obj("trigger_user"))?.let(GitHubClient::parseUser),
            createdAt = valid(json.instant("created_at") ?: json.instant("created")),
            startedAt = valid(json.instant("run_started_at") ?: json.instant("started_at") ?: json.instant("started")),
            // GitHub has no completion time on runs; the last update is when it finished.
            finishedAt = if (status.isFinished) {
                valid(json.instant("completed_at") ?: json.instant("stopped") ?: json.instant("updated_at") ?: json.instant("updated"))
            } else {
                null
            },
            webUrl = json.str("html_url") ?: json.str("url")?.takeIf { "/api/" !in it },
            attempt = json.int("run_attempt"),
        )
    }

    fun job(json: JsonObject): CiJob = CiJob(
        id = json.long("id")?.toString().orEmpty(),
        name = json.str("name") ?: "Job",
        stage = null,
        status = status(json.str("status"), json.str("conclusion")),
        startedAt = valid(json.instant("started_at") ?: json.instant("started")),
        finishedAt = valid(json.instant("completed_at") ?: json.instant("stopped")),
        webUrl = json.str("html_url"),
        steps = json.arr("steps")?.objects().orEmpty().map { step ->
            CiStep(
                name = step.str("name").orEmpty(),
                status = status(step.str("status"), step.str("conclusion")),
                number = step.int("number"),
                startedAt = valid(step.instant("started_at")),
                finishedAt = valid(step.instant("completed_at")),
            )
        },
    )

    fun artifact(json: JsonObject): CiArtifact {
        val id = json.long("id")?.toString().orEmpty()
        val name = json.str("name") ?: "artifact-$id"
        return CiArtifact(
            id = id,
            name = name,
            sizeBytes = json.long("size_in_bytes"),
            expired = json.bool("expired") == true,
            createdAt = valid(json.instant("created_at")),
            expiresAt = valid(json.instant("expires_at")),
            downloadUrl = json.str("archive_download_url").orEmpty(),
            fileName = "${safeFileName(name)}.zip",
        )
    }

    /** Zero dates ("0001-01-01", "1970-01-01") mean "not set". */
    fun valid(instant: Instant?): Instant? = instant?.takeIf { it.isAfter(Instant.parse("2000-01-01T00:00:00Z")) }
}

/** A name that is safe as a file name on every filesystem Android writes to. */
fun safeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "artifact" }
