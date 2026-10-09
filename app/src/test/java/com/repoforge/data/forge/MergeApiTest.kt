package com.repoforge.data.forge

import com.repoforge.data.model.MergeMethod
import com.repoforge.data.model.Mergeability
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeApiTest : MockForge() {

    private fun body() = Json.parseToJsonElement(take().body!!.utf8()).jsonObject

    private fun JsonObject.s(key: String) = this[key].toString().trim('"')

    private val githubPull = """{"number":4,"title":"Feature","state":"open","mergeable":%s,"mergeable_state":"%s",
        "head":{"ref":"feature/x","sha":"abc","repo":{"full_name":"fork/app","clone_url":"https://github.com/fork/app.git"}},
        "base":{"ref":"main","repo":{"full_name":"octo/app","clone_url":"https://github.com/octo/app.git"}}}"""

    @Test
    fun `github mergeability states`() = runTest {
        val client = GitHubClient(okHttp, apiBase(), "t")
        val cases = listOf(
            Triple("true", "clean", Mergeability.MERGEABLE),
            Triple("false", "dirty", Mergeability.CONFLICTS),
            Triple("null", "unknown", Mergeability.CHECKING),
            Triple("true", "blocked", Mergeability.BLOCKED),
            Triple("true", "unstable", Mergeability.MERGEABLE),
        )
        for ((mergeable, state, expected) in cases) {
            enqueue(githubPull.format(mergeable, state))
            val detail = client.getPullRequest(repo("octo/app"), 4)
            assertEquals("$mergeable/$state", expected, detail.mergeability)
        }
        enqueue(githubPull.format("true", "clean"))
        val detail = client.getPullRequest(repo("octo/app"), 4)
        assertEquals("feature/x", detail.headBranch)
        assertEquals("main", detail.baseBranch)
        assertTrue(detail.isFork)
        assertEquals("fork/app", detail.headRepoApiId)
    }

    @Test
    fun `github squash merge then deletes the head branch in the fork`() = runTest {
        val client = GitHubClient(okHttp, apiBase(), "t")
        enqueue(githubPull.format("true", "clean"))
        val detail = client.getPullRequest(repo("octo/app"), 4)
        take()

        enqueue("""{"sha":"merged123","merged":true}""")
        enqueue("", code = 204)
        val outcome = client.mergePullRequest(repo("octo/app"), detail, MergeMethod.SQUASH, "Title", "Body", deleteBranch = true)

        val merge = take()
        assertEquals("PUT", merge.method)
        assertEquals("/repos/octo/app/pulls/4/merge", merge.target)
        val json = Json.parseToJsonElement(merge.body!!.utf8()).jsonObject
        assertEquals("squash", json.s("merge_method"))
        assertEquals("Title", json.s("commit_title"))
        val delete = take()
        assertEquals("DELETE", delete.method)
        assertEquals("/repos/fork/app/git/refs/heads/feature/x", delete.target)
        assertEquals("merged123", outcome.sha)
        assertTrue(outcome.branchDeleted)
    }

    @Test
    fun `github reports a failed branch delete without failing the merge`() = runTest {
        val client = GitHubClient(okHttp, apiBase(), "t")
        enqueue(githubPull.format("true", "clean"))
        val detail = client.getPullRequest(repo("octo/app"), 4)
        enqueue("""{"sha":"m","merged":true}""")
        enqueue("""{"message":"Must have admin rights"}""", code = 403)
        val outcome = client.mergePullRequest(repo("octo/app"), detail, MergeMethod.MERGE, null, null, deleteBranch = true)
        assertFalse(outcome.branchDeleted)
        assertTrue(outcome.branchError!!.contains("admin rights"))
    }

    @Test
    fun `gitlab merge request status and merge`() = runTest {
        val client = GitLabClient(okHttp, apiBase("/api/v4"), "t")
        enqueue(
            """{"iid":9,"title":"MR","state":"opened","detailed_merge_status":"conflict","has_conflicts":true,
               "source_branch":"feat","target_branch":"main","sha":"s1","source_project_id":5,"target_project_id":5}"""
        )
        val detail = client.getPullRequest(repo("5").copy(cloneHttps = "https://gitlab.com/g/app.git"), 9)
        assertEquals(Mergeability.CONFLICTS, detail.mergeability)
        assertEquals("https://gitlab.com/g/app.git", detail.headCloneUrl)
        assertFalse(detail.isFork)
        take()

        enqueue("""{"iid":9,"state":"opened","detailed_merge_status":"ci_must_pass","source_project_id":5,"target_project_id":5}""")
        assertEquals(Mergeability.BLOCKED, client.getPullRequest(repo("5"), 9).mergeability)
        take()

        enqueue("""{"iid":9,"state":"merged","merge_commit_sha":"mc1"}""")
        val outcome = client.mergePullRequest(repo("5"), detail, MergeMethod.SQUASH, "Squashed", null, deleteBranch = true)
        val request = take()
        assertEquals("PUT", request.method)
        assertEquals("/api/v4/projects/5/merge_requests/9/merge", request.target)
        val json = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("true", json.s("squash"))
        assertEquals("true", json.s("should_remove_source_branch"))
        assertEquals("Squashed", json.s("squash_commit_message"))
        assertEquals("mc1", outcome.sha)
    }

    @Test
    fun `gitlab fork merge requests look up the source project`() = runTest {
        val client = GitLabClient(okHttp, apiBase("/api/v4"), "t")
        enqueue("""{"iid":9,"state":"opened","detailed_merge_status":"mergeable","source_branch":"f","target_branch":"main","source_project_id":7,"target_project_id":5}""")
        enqueue("""{"id":7,"http_url_to_repo":"https://gitlab.com/me/app.git"}""")
        val detail = client.getPullRequest(repo("5").copy(cloneHttps = "https://gitlab.com/g/app.git"), 9)
        take()
        assertEquals("/api/v4/projects/7", take().target)
        assertEquals("https://gitlab.com/me/app.git", detail.headCloneUrl)
        assertEquals("7", detail.headRepoApiId)
        assertTrue(detail.isFork)
        assertEquals(Mergeability.MERGEABLE, detail.mergeability)
    }

    @Test
    fun `bitbucket conflicts come from the diffstat and merge closes the branch`() = runTest {
        val client = BitbucketClient(okHttp, apiBase("/2.0"), "me@example.com", "t")
        enqueue(
            """{"id":3,"title":"PR","state":"OPEN","source":{"branch":{"name":"feat"},"commit":{"hash":"h1"},"repository":{"full_name":"ws/app"}},
               "destination":{"branch":{"name":"main"},"repository":{"full_name":"ws/app"}}}"""
        )
        enqueue("""{"values":[{"status":"modified"},{"status":"merge conflict"}]}""")
        val detail = client.getPullRequest(repo("ws/app"), 3)
        assertEquals(Mergeability.CONFLICTS, detail.mergeability)
        assertEquals("https://bitbucket.org/ws/app.git", detail.headCloneUrl)
        take()
        assertTrue(take().target.startsWith("/2.0/repositories/ws/app/pullrequests/3/diffstat"))

        enqueue("""{"id":3,"state":"MERGED","merge_commit":{"hash":"mh"}}""")
        val outcome = client.mergePullRequest(repo("ws/app"), detail, MergeMethod.FAST_FORWARD, null, null, deleteBranch = true)
        val json = body()
        assertEquals("fast_forward", json.s("merge_strategy"))
        assertEquals("true", json.s("close_source_branch"))
        assertEquals("mh", outcome.sha)

        enqueue("", code = 202)
        assertTrue(client.mergePullRequest(repo("ws/app"), detail, MergeMethod.MERGE, null, null, false).pending)
    }

    @Test
    fun `gitea merge and branch delete`() = runTest {
        val client = GiteaClient(okHttp, apiBase("/api/v1"), "t")
        enqueue(
            """{"number":2,"title":"PR","state":"open","mergeable":false,"head":{"ref":"fix/a","sha":"x","repo":{"full_name":"me/app","clone_url":"https://codeberg.org/me/app.git"}},
               "base":{"ref":"main","repo":{"full_name":"me/app","clone_url":"https://codeberg.org/me/app.git"}}}"""
        )
        val detail = client.getPullRequest(repo("me/app"), 2)
        assertEquals(Mergeability.CONFLICTS, detail.mergeability)
        assertTrue(detail.pull.isPullRequest)
        assertFalse(detail.isFork)
        take()

        enqueue("", code = 200)
        val outcome = client.mergePullRequest(repo("me/app"), detail, MergeMethod.REBASE, "T", null, deleteBranch = true)
        val json = body()
        assertEquals("rebase", json.s("Do"))
        assertEquals("true", json.s("delete_branch_after_merge"))
        assertNull(outcome.sha)

        enqueue("", code = 404) // already deleted is fine
        client.deleteBranch(repo("me/app"), detail)
        assertEquals("/api/v1/repos/me/app/branches/fix/a", take().target)
    }
}
