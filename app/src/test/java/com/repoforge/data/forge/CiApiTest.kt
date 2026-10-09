package com.repoforge.data.forge

import com.repoforge.data.model.CiStatus
import com.repoforge.data.net.ForgeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class CiApiTest : MockForge() {

    @Test
    fun `github runs, jobs with steps, logs behind a redirect and artifacts`() = runTest {
        val client = GitHubClient(okHttp, apiBase(), "t")
        enqueue(
            """{"total_count":2,"workflow_runs":[
              {"id":37941268077,"name":"Android CI","display_title":"Add CI","run_number":13,"event":"pull_request",
               "status":"completed","conclusion":"failure","head_branch":"feature","head_sha":"dc164a33",
               "html_url":"https://github.com/o/r/actions/runs/37941268077","created_at":"2026-10-09T14:02:49Z",
               "updated_at":"2026-10-09T14:12:49Z","run_started_at":"2026-10-09T14:02:49Z","run_attempt":2,
               "actor":{"login":"ada","avatar_url":"https://a/ada.png"}},
              {"id":2,"name":"Lint","display_title":"Add CI","run_number":12,"event":"push","status":"in_progress","conclusion":null}
            ]}""",
            headers = arrayOf("Link" to "<${apiBase()}/x?page=2>; rel=\"next\""),
        )
        val page = client.listCiRuns(repo("o/r"), 1)
        assertEquals("/repos/o/r/actions/runs?per_page=30&page=1", take().decodedTarget)
        assertEquals(2, page.nextPage)
        val run = page.items[0]
        assertEquals("37941268077", run.id)
        assertEquals(13L, run.number)
        assertEquals("Add CI", run.title)
        assertEquals("Android CI", run.workflow)
        assertEquals(CiStatus.FAILURE, run.status)
        assertEquals("ada", run.actor?.login)
        assertEquals(2, run.attempt)
        assertEquals("2026-10-09T14:12:49Z", run.finishedAt.toString())
        assertEquals(CiStatus.RUNNING, page.items[1].status)
        assertNull(page.items[1].finishedAt)

        enqueue(
            """{"total_count":1,"jobs":[{"id":11,"name":"build","status":"completed","conclusion":"failure",
               "started_at":"2026-10-09T14:02:51Z","completed_at":"2026-10-09T14:12:00Z","html_url":"https://github.com/j/11",
               "steps":[{"name":"Set up job","status":"completed","conclusion":"success","number":1},
                        {"name":"Test","status":"completed","conclusion":"failure","number":2},
                        {"name":"Upload","status":"completed","conclusion":"skipped","number":3}]}]}"""
        )
        val jobs = client.listCiJobs(repo("o/r"), run)
        assertEquals("/repos/o/r/actions/runs/37941268077/jobs?per_page=100&page=1", take().decodedTarget)
        assertEquals(listOf(CiStatus.SUCCESS, CiStatus.FAILURE, CiStatus.SKIPPED), jobs.single().steps.map { it.status })

        // GitHub answers the log request with a redirect to blob storage.
        enqueue("", code = 302, "Location" to server.url("/blob/log.txt").toString())
        enqueue("2026-10-09T14:02:51.0000000Z ##[group]Run tests\nok\n")
        val log = client.getCiJobLog(repo("o/r"), run, jobs.single())
        assertEquals("/repos/o/r/actions/jobs/11/logs", take().decodedTarget)
        val blob = take()
        assertEquals("/blob/log.txt", blob.target)
        assertTrue(log.contains("##[group]Run tests"))

        enqueue(
            """{"total_count":1,"artifacts":[{"id":99,"name":"app-debug","size_in_bytes":2048,"expired":false,
               "archive_download_url":"${apiBase()}/repos/o/r/actions/artifacts/99/zip",
               "created_at":"2026-10-09T14:10:00Z","expires_at":"2027-01-07T14:10:00Z"}]}"""
        )
        val artifact = client.listCiArtifacts(repo("o/r"), run).single()
        assertEquals("/repos/o/r/actions/runs/37941268077/artifacts?per_page=100", take().decodedTarget)
        assertEquals("app-debug.zip", artifact.fileName)
        assertEquals(2048L, artifact.sizeBytes)

        enqueue("", code = 302, "Location" to server.url("/storage/app-debug.zip").toString())
        enqueue("PK-zip-bytes")
        val out = ByteArrayOutputStream()
        var reported = 0L
        val written = client.downloadCiArtifact(artifact, out) { done, _ -> reported = done }
        assertEquals("Bearer t", take().headers["Authorization"])
        assertEquals("/storage/app-debug.zip", take().target)
        assertEquals("PK-zip-bytes", out.toString())
        assertEquals(12L, written)
        assertEquals(12L, reported)
    }

    @Test
    fun `github explains a missing log while the job runs`() = runTest {
        val client = GitHubClient(okHttp, apiBase(), "t")
        enqueue("""{"message":"Not Found"}""", code = 404)
        val job = com.repoforge.data.model.CiJob("5", "build", null, CiStatus.RUNNING, null, null, null)
        val run = GitHubCi.run(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":1,"status":"in_progress"}""") as kotlinx.serialization.json.JsonObject)
        try {
            client.getCiJobLog(repo("o/r"), run, job)
            fail()
        } catch (e: ForgeException) {
            assertTrue(e.message!!.contains("when the job finishes"))
        }
    }

    @Test
    fun `gitlab pipelines, jobs in stage order, traces and per-job artifacts`() = runTest {
        val client = GitLabClient(okHttp, apiBase("/api/v4"), "t")
        enqueue(
            """[{"id":2930647261,"iid":52991,"sha":"f083","ref":"main","status":"success","source":"merge_request_event",
               "created_at":"2026-10-09T13:17:38Z","updated_at":"2026-10-09T13:20:23Z","web_url":"https://gitlab.com/p/-/pipelines/1","name":null}]""",
            headers = arrayOf("X-Next-Page" to ""),
        )
        val page = client.listCiRuns(repo("42"), 1)
        assertEquals("/api/v4/projects/42/pipelines?per_page=30&page=1", take().decodedTarget)
        assertNull(page.nextPage)
        val run = page.items.single()
        assertEquals(52991L, run.number)
        assertEquals("main", run.title)
        assertEquals("merge request event", run.event)
        assertEquals(CiStatus.SUCCESS, run.status)

        val jobsJson = """[
            {"id":3,"name":"deploy","stage":"deploy","status":"manual","web_url":"w3"},
            {"id":2,"name":"test","stage":"test","status":"failed","started_at":"2026-10-09T13:18:00Z","finished_at":"2026-10-09T13:19:00Z",
             "artifacts_file":{"filename":"artifacts.zip","size":222},"artifacts_expire_at":"2099-01-01T00:00:00Z"},
            {"id":1,"name":"build","stage":"build","status":"success","artifacts_file":{"filename":"artifacts.zip","size":4096},
             "artifacts_expire_at":"2000-01-01T00:00:00Z"}]"""
        enqueue(jobsJson)
        val jobs = client.listCiJobs(repo("42"), run)
        assertEquals("/api/v4/projects/42/pipelines/2930647261/jobs?per_page=100&page=1", take().decodedTarget)
        assertEquals(listOf("build", "test", "deploy"), jobs.map { it.name })
        assertEquals(listOf("build", "test", "deploy"), jobs.map { it.stage })
        assertEquals(listOf(CiStatus.SUCCESS, CiStatus.FAILURE, CiStatus.ACTION_REQUIRED), jobs.map { it.status })

        enqueue("\u001B[0Ksection_start:1:step_script\r\u001B[0KRunning\n")
        client.getCiJobLog(repo("42"), run, jobs[1])
        assertEquals("/api/v4/projects/42/jobs/2/trace", take().decodedTarget)

        enqueue(jobsJson)
        val artifacts = client.listCiArtifacts(repo("42"), run)
        take()
        assertEquals(listOf("build", "test"), artifacts.map { it.name })
        assertTrue("past expiry date", artifacts[0].expired)
        assertFalse(artifacts[1].expired)
        assertEquals("test-artifacts.zip", artifacts[1].fileName)
        assertTrue(artifacts[1].downloadUrl.endsWith("/api/v4/projects/42/jobs/2/artifacts"))
    }

    @Test
    fun `bitbucket pipelines with steps as jobs and step logs`() = runTest {
        val client = BitbucketClient(okHttp, apiBase("/2.0"), null, "t")
        enqueue(
            """{"page":1,"values":[{"uuid":"{c377793e}","build_number":3027,
               "state":{"name":"COMPLETED","result":{"name":"FAILED"}},
               "target":{"type":"pipeline_pullrequest_target","source":"renovate/eslint","destination":"master",
                 "commit":{"hash":"c595a7e2"},"selector":{"type":"pull-requests","pattern":"**"},
                 "pullrequest":{"id":513,"title":"Update eslint"}},
               "trigger":{"name":"PUSH"},"creator":{"display_name":"Renovate Bot","links":{"avatar":{"href":"a"}}},
               "created_on":"2026-10-08T21:03:46.632613148Z","completed_on":"2026-10-08T21:14:31.423341222Z"}],
               "next":"https://api.bitbucket.org/2.0/x?page=2"}"""
        )
        val page = client.listCiRuns(repo("atlassian/ace"), 1)
        assertEquals("/2.0/repositories/atlassian/ace/pipelines/?sort=-created_on&page=1&pagelen=30", take().decodedTarget)
        assertEquals(2, page.nextPage)
        val run = page.items.single()
        assertEquals("{c377793e}", run.id)
        assertEquals("Update eslint", run.title)
        assertEquals("renovate/eslint", run.branch)
        assertEquals("push", run.event)
        assertEquals(CiStatus.FAILURE, run.status)
        assertEquals("https://bitbucket.org/atlassian/ace/pipelines/results/3027", run.webUrl)

        enqueue(
            """{"values":[{"uuid":"{s1}","name":"Scan","state":{"name":"COMPLETED","result":{"name":"FAILED"}},
               "started_on":"2026-10-08T21:04:12Z","completed_on":"2026-10-08T21:09:35Z"},
               {"uuid":"{s2}","name":"Deploy","state":{"name":"IN_PROGRESS","stage":{"name":"PAUSED"}}}]}"""
        )
        val steps = client.listCiJobs(repo("atlassian/ace"), run)
        assertEquals("/2.0/repositories/atlassian/ace/pipelines/{c377793e}/steps/", take().decodedTarget)
        assertEquals(listOf(CiStatus.FAILURE, CiStatus.ACTION_REQUIRED), steps.map { it.status })

        enqueue("+ npm test\nfailed\n")
        assertTrue(client.getCiJobLog(repo("atlassian/ace"), run, steps[0]).contains("npm test"))
        assertEquals("/2.0/repositories/atlassian/ace/pipelines/{c377793e}/steps/{s1}/log", take().decodedTarget)

        assertFalse(client.ci.artifacts)
        assertTrue(client.listCiArtifacts(repo("atlassian/ace"), run).isEmpty())
    }

    @Test
    fun `forgejo runs, bare job and artifact arrays, and no log endpoint`() = runTest {
        val client = GiteaClient(okHttp, apiBase("/api/v1"), "t")
        enqueue(
            """{"workflow_runs":[{"id":196211,"title":"test-multi-platform","workflow_id":"test-multi-platform.yml",
               "index_in_repo":23966,"prettyref":"main","commit_sha":"c4a92ffa","event":"push","trigger_event":"schedule",
               "status":"failure","started":"1970-01-01T00:00:00Z","stopped":"2026-10-09T13:20:24Z","created":"2026-10-09T12:00:15Z",
               "html_url":"https://code.forgejo.org/forgejo/runner/actions/runs/23966",
               "trigger_user":{"login":"forgejo-actions","avatar_url":"a"}}],"total_count":45}"""
        )
        val page = client.listCiRuns(repo("forgejo/runner"), 1)
        assertEquals("/api/v1/repos/forgejo/runner/actions/runs?page=1&limit=30", take().decodedTarget)
        assertEquals(2, page.nextPage)
        val run = page.items.single()
        assertEquals("test-multi-platform", run.title)
        assertEquals("test-multi-platform.yml", run.workflow)
        assertEquals(23966L, run.number)
        assertEquals("main", run.branch)
        assertEquals("schedule", run.event)
        assertEquals(CiStatus.FAILURE, run.status)
        assertNull("1970 means not started", run.startedAt)
        assertEquals("2026-10-09T13:20:24Z", run.finishedAt.toString())
        assertEquals("forgejo-actions", run.actor?.login)

        enqueue("""[{"id":407308,"run_id":196211,"name":"arm64","html_url":"h","status":"failure"},{"id":407309,"name":"amd64","status":"success"}]""")
        val jobs = client.listCiJobs(repo("forgejo/runner"), run)
        assertEquals("/api/v1/repos/forgejo/runner/actions/runs/196211/jobs?page=1&limit=100", take().decodedTarget)
        assertEquals(listOf(CiStatus.FAILURE, CiStatus.SUCCESS), jobs.map { it.status })

        enqueue("""{"message":"internal"}""", code = 500)
        try {
            client.getCiJobLog(repo("forgejo/runner"), run, jobs[0])
            fail()
        } catch (e: ForgeException) {
            assertTrue(e.message!!.contains("browser"))
        }
        take()

        enqueue("""[{"id":43824,"name":"forgejo-runner","size_in_bytes":20840610,"archive_download_url":"${apiBase()}/zip","expired":false}]""")
        val artifact = client.listCiArtifacts(repo("forgejo/runner"), run).single()
        assertEquals("forgejo-runner.zip", artifact.fileName)
        assertEquals("/api/v1/repos/forgejo/runner/actions/runs/196211/artifacts?page=1&limit=100", take().decodedTarget)
    }

    @Test
    fun `gitea without the runs api falls back to tasks`() = runTest {
        val client = GiteaClient(okHttp, apiBase("/api/v1"), "t")
        enqueue("""{"message":"Not Found"}""", code = 404)
        enqueue(
            """{"workflow_runs":[{"id":329332,"name":"arm64","head_branch":"main","head_sha":"c4a9","run_number":23966,
               "event":"schedule","display_title":"test-multi-platform","status":"running","workflow_id":"t.yml",
               "url":"https://gitea.example/o/r/actions/runs/23966","created_at":"2026-10-09T13:20:24Z"}],"total_count":1}"""
        )
        val page = client.listCiRuns(repo("o/r"), 1)
        take()
        assertEquals("/api/v1/repos/o/r/actions/tasks?page=1&limit=30", take().decodedTarget)
        assertNull(page.nextPage)
        val run = page.items.single()
        assertEquals("task-329332", run.id)
        assertEquals("arm64", run.workflow)
        assertEquals(CiStatus.RUNNING, run.status)
        assertEquals("https://gitea.example/o/r/actions/runs/23966", run.webUrl)
        // A task is a single job, and there's nothing to ask the server for.
        assertEquals(listOf("329332"), client.listCiJobs(repo("o/r"), run).map { it.id })
        assertTrue(client.listCiArtifacts(repo("o/r"), run).isEmpty())
    }
}
