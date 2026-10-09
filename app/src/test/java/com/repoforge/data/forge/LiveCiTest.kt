package com.repoforge.data.forge

import com.repoforge.data.ci.CiLogParser
import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.Repo
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import com.repoforge.data.net.ForgeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.OutputStream
import kotlin.time.Duration.Companion.minutes

/**
 * CI against the real services, anonymously, on public repositories: runs, jobs, a log and
 * artifacts where the service allows it without signing in.
 * Excluded by default; run with `./gradlew :app:testDebugUnitTest -Plive`.
 */
class LiveCiTest {
    private val http = OkHttpClient()

    private fun repo(apiId: String) = Repo(
        apiId, apiId.substringBeforeLast('/'), apiId.substringAfterLast('/'), apiId, null, false, false,
        "main", null, null, null, null, null, null, null, null,
    )

    @Test
    fun github() = runTest(timeout = 2.minutes) {
        val client = ForgeClients.create(ForgeType.GITHUB, "https://github.com", null, "", http)
        val repo = repo("johndoe6345789/RepoForge")
        val run = client.listCiRuns(repo, 1).items.first { it.status.isFinished }
        val jobs = client.listCiJobs(repo, run)
        assertTrue("jobs with steps", jobs.isNotEmpty() && jobs.first().steps.isNotEmpty())
        val artifacts = client.listCiArtifacts(repo, run)
        println("GitHub: run #${run.number} ${run.status}, ${jobs.size} jobs, artifacts ${artifacts.map { it.name }}")
        // Logs and artifact downloads need a signed-in user on GitHub, so they're not checked here.
    }

    @Test
    fun gitlab() = runTest(timeout = 2.minutes) {
        val client = ForgeClients.create(ForgeType.GITLAB, "https://gitlab.com", null, "", http)
        val repo = repo("gitlab-org/gitlab-runner")
        val run = client.listCiRuns(repo, 1).items.first { it.status.isFinished }
        val jobs = client.listCiJobs(repo, run)
        assertTrue("jobs", jobs.isNotEmpty() && jobs.all { it.stage != null })
        // GitLab serves job logs and artifact archives through its API only to signed-in users.
        try {
            client.getCiJobLog(repo, run, jobs.first { it.status.isFinished })
            fail("expected GitLab to require a token for job logs")
        } catch (e: ForgeException) {
            assertEquals(401, e.code)
        }
        val artifacts = client.listCiArtifacts(repo, run)
        println("GitLab: pipeline #${run.number} ${run.status}, ${jobs.size} jobs in ${jobs.map { it.stage }.distinct().size} stages, ${artifacts.size} artifacts")
    }

    @Test
    fun bitbucket() = runTest(timeout = 2.minutes) {
        val client = ForgeClients.create(ForgeType.BITBUCKET, "https://bitbucket.org", null, "", http)
        val repo = repo("atlassian/atlassian-connect-express")
        val run = client.listCiRuns(repo, 1).items.first { it.status.isFinished }
        val steps = client.listCiJobs(repo, run)
        assertTrue("steps", steps.isNotEmpty())
        val log = client.getCiJobLog(repo, run, steps.first())
        assertTrue("log text", log.isNotBlank())
        assertTrue("parsed", CiLogParser.parse(log).lines.isNotEmpty())
        println("Bitbucket: pipeline #${run.number} ${run.status} \"${run.title}\", ${steps.size} steps, log ${log.length} chars")
    }

    @Test
    fun forgejo() = runTest(timeout = 3.minutes) {
        val client = ForgeClients.create(ForgeType.GITEA, "https://code.forgejo.org", null, "", http)
        val repo = repo("forgejo/runner")
        val runs = client.listCiRuns(repo, 1)
        assertTrue("one page, not the whole history", runs.items.size <= ForgeClient.PAGE_SIZE && runs.nextPage == 2)
        val run = runs.items.first { it.status.isFinished }
        val jobs = client.listCiJobs(repo, run)
        assertTrue("jobs", jobs.isNotEmpty())
        val artifacts = client.listCiArtifacts(repo, run)
        // Forgejo serves public artifacts without signing in: fetch the start of one to prove the URL works.
        artifacts.firstOrNull { !it.expired }?.let { artifact ->
            val head = java.io.ByteArrayOutputStream()
            try {
                client.downloadCiArtifact(artifact, object : OutputStream() {
                    override fun write(b: Int) = head.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        head.write(b, off, len)
                        if (head.size() >= 4) throw java.io.IOException("enough")
                    }
                }) { _, _ -> }
            } catch (_: java.io.IOException) {
            }
            assertEquals("zip signature", "PK", head.toByteArray().take(2).map { it.toInt().toChar() }.joinToString(""))
        }
        println("Forgejo: run #${run.number} ${run.status} \"${run.title}\", ${jobs.size} jobs, artifacts ${artifacts.map { "${it.name} ${it.sizeBytes}" }}")
    }
}
