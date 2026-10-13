package com.repoforge.data.forge

import com.repoforge.data.model.DraftTitle
import com.repoforge.data.model.Mergeability
import com.repoforge.data.net.ForgeException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftApiTest : MockForge() {

    private fun bodyOf(r: mockwebserver3.RecordedRequest) = Json.parseToJsonElement(r.body!!.utf8()).jsonObject

    private fun ghPull(draft: Boolean, title: String = "Add x") =
        """{"number":9,"title":"$title","state":"open","draft":$draft,"node_id":"PR_node","html_url":"https://github.com/o/r/pull/9",
            "user":{"login":"me"},"head":{"ref":"feat"},"base":{"ref":"main"}}"""

    @Test
    fun `github creates a draft pull request`() = runTest {
        enqueue(ghPull(draft = true), code = 201)
        val pr = GitHubClient(okHttp, apiBase(), "t").createPullRequest(repo("o/r"), "Add x", "body", "feat", "main", draft = true)
        val req = take()
        assertEquals("POST", req.method)
        assertEquals("/repos/o/r/pulls", req.target)
        val json = bodyOf(req)
        assertEquals("true", json["draft"]!!.jsonPrimitive.content)
        assertEquals("feat", json["head"]!!.jsonPrimitive.content)
        assertTrue(pr.isDraft)
        assertTrue(pr.isPullRequest)
    }

    @Test
    fun `github marks a draft ready through graphql`() = runTest {
        enqueue(ghPull(draft = true))
        enqueue("""{"data":{"markPullRequestReadyForReview":{"pullRequest":{"isDraft":false}}}}""")
        enqueue(ghPull(draft = false))
        val pr = GitHubClient(okHttp, apiBase(), "t").setDraft(repo("o/r"), GitHubClient.parseIssue(Json.parseToJsonElement(ghPull(true)).jsonObject), draft = false)
        assertEquals("GET", take().method)
        val gql = take()
        assertEquals("/graphql", gql.target)
        val json = bodyOf(gql)
        assertTrue(json["query"]!!.jsonPrimitive.content.contains("markPullRequestReadyForReview"))
        assertEquals("PR_node", json["variables"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse(pr.isDraft)
    }

    @Test
    fun `github converts to draft and skips the call when nothing changes`() = runTest {
        enqueue(ghPull(draft = false)); enqueue("""{"data":{}}"""); enqueue(ghPull(draft = true))
        val client = GitHubClient(okHttp, apiBase(), "t")
        val seed = GitHubClient.parseIssue(Json.parseToJsonElement(ghPull(false)).jsonObject)
        assertTrue(client.setDraft(repo("o/r"), seed, draft = true).isDraft)
        take(); assertTrue(bodyOf(take())["query"]!!.jsonPrimitive.content.contains("convertPullRequestToDraft"))
        val before = server.requestCount
        enqueue(ghPull(draft = true))
        client.setDraft(repo("o/r"), seed, draft = true)
        assertEquals("only the read, no mutation", before + 1, server.requestCount)
    }

    @Test(expected = ForgeException::class)
    fun `github surfaces graphql errors`() = runTest {
        enqueue(ghPull(draft = true))
        enqueue("""{"errors":[{"message":"Resource not accessible by personal access token"}]}""")
        GitHubClient(okHttp, apiBase(), "t").setDraft(repo("o/r"), GitHubClient.parseIssue(Json.parseToJsonElement(ghPull(true)).jsonObject), draft = false)
    }

    @Test
    fun `github enterprise sends graphql to the api root`() = runTest {
        enqueue(ghPull(draft = true)); enqueue("""{"data":{}}"""); enqueue(ghPull(draft = false))
        val client = GitHubClient(okHttp, apiBase("/api/v3"), "t")
        client.setDraft(repo("o/r"), GitHubClient.parseIssue(Json.parseToJsonElement(ghPull(true)).jsonObject), draft = false)
        take()
        assertEquals("/api/graphql", take().target)
    }

    @Test
    fun `gitlab draft is a title prefix`() = runTest {
        val mr = """{"iid":3,"title":"%s","state":"opened","draft":%s,"web_url":"u","author":{"username":"me"}}"""
        enqueue(mr.format("Draft: Add x", "true"), code = 201)
        val created = GitLabClient(okHttp, apiBase(), "t").createPullRequest(repo("g/p", "main").copy(apiId = "42"), "Add x", "b", "feat", "main", draft = true)
        val req = take()
        assertEquals("/projects/42/merge_requests", req.target)
        assertEquals("Draft: Add x", bodyOf(req)["title"]!!.jsonPrimitive.content)
        assertTrue(created.isDraft)

        enqueue(mr.format("Draft: Add x", "true"))
        enqueue(mr.format("Add x", "false"))
        val ready = GitLabClient(okHttp, apiBase(), "t").setDraft(repo("g/p").copy(apiId = "42"), created, draft = false)
        take()
        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("Add x", bodyOf(put)["title"]!!.jsonPrimitive.content)
        assertFalse(ready.isDraft)
    }

    @Test
    fun `gitea draft is a WIP prefix and uses patch`() = runTest {
        val pr = """{"number":5,"title":"%s","state":"open","html_url":"u","user":{"login":"me"},"head":{"ref":"feat"},"base":{"ref":"main"}}"""
        enqueue(pr.format("WIP: Add x"), code = 201)
        val client = GiteaClient(okHttp, apiBase(), "t")
        val created = client.createPullRequest(repo("o/r"), "Add x", "b", "feat", "main", draft = true)
        assertEquals("WIP: Add x", bodyOf(take())["title"]!!.jsonPrimitive.content)
        assertTrue(created.isDraft)

        enqueue(pr.format("WIP: Add x")); enqueue(pr.format("Add x"))
        val ready = client.setDraft(repo("o/r"), created, draft = false)
        take()
        val patch = take()
        assertEquals("PATCH", patch.method)
        assertEquals("Add x", bodyOf(patch)["title"]!!.jsonPrimitive.content)
        assertFalse(ready.isDraft)
    }

    @Test
    fun `bitbucket sends the draft flag`() = runTest {
        val pr = """{"id":2,"title":"Add x","state":"OPEN","draft":%s,"source":{"branch":{"name":"feat"}},"destination":{"branch":{"name":"main"}},"links":{"html":{"href":"u"}}}"""
        enqueue(pr.format("true"), code = 201)
        val client = BitbucketClient(okHttp, apiBase(), null, "t")
        val created = client.createPullRequest(repo("w/r"), "Add x", "b", "feat", "main", draft = true)
        val json = bodyOf(take())
        assertEquals("true", json["draft"]!!.jsonPrimitive.content)
        assertEquals("feat", json["source"]!!.jsonObject["branch"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(created.isDraft)
        enqueue(pr.format("false"))
        val ready = client.setDraft(repo("w/r"), created, draft = false)
        val put = take()
        assertEquals("PUT", put.method)
        assertEquals("false", bodyOf(put)["draft"]!!.jsonPrimitive.content)
        assertFalse(ready.isDraft)
    }

    @Test
    fun `draft titles`() {
        assertTrue(DraftTitle.isDraft("Draft: x")); assertTrue(DraftTitle.isDraft("[WIP] x")); assertTrue(DraftTitle.isDraft("wip: x"))
        assertFalse(DraftTitle.isDraft("Drafting the docs"))
        assertEquals("x", DraftTitle.strip("[Draft] x"))
        assertEquals("Draft: x", DraftTitle.apply("Draft: x", true, "Draft:"))
        assertEquals("x", DraftTitle.apply("WIP: x", false, "WIP:"))
    }

    @Test
    fun `a draft github pull request reports why it can't merge`() {
        val detail = GitHubClient.parsePullDetail(Json.parseToJsonElement(
            """{"number":9,"title":"t","state":"open","draft":true,"mergeable":true,"mergeable_state":"draft","head":{"ref":"a"},"base":{"ref":"main"}}""").jsonObject)
        assertEquals(Mergeability.BLOCKED, detail.mergeability)
        assertTrue(detail.pull.isDraft)
    }
}
