package com.repoforge.data.forge

import com.repoforge.data.model.EntryType
import com.repoforge.data.model.Issue
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.StateFilter
import com.repoforge.data.net.ForgeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class GitHubClientTest : MockForge() {

    private fun client() = GitHubClient(okHttp, apiBase(), "ghp_secret")

    @Test
    fun `lists repos with bearer auth and follows Link pagination`() = runTest {
        enqueue(
            """[{"name":"app","full_name":"octo/app","owner":{"login":"octo","avatar_url":"https://a/o.png"},
               "private":true,"fork":false,"default_branch":"trunk","stargazers_count":5,"forks_count":2,
               "language":"Kotlin","pushed_at":"2026-01-02T03:04:05Z","html_url":"https://github.com/octo/app",
               "clone_url":"https://github.com/octo/app.git","ssh_url":"git@github.com:octo/app.git"}]""",
            headers = arrayOf("Link" to "<https://api.github.com/user/repos?page=2>; rel=\"next\""),
        )
        val page = client().listRepos(1)

        val request = take()
        assertEquals("Bearer ghp_secret", request.headers["Authorization"])
        assertTrue(request.decodedTarget.startsWith("/user/repos?sort=updated&per_page=30&page=1"))
        assertEquals(2, page.nextPage)
        val repo = page.items.single()
        assertEquals("octo/app", repo.apiId)
        assertEquals("trunk", repo.defaultBranch)
        assertTrue(repo.isPrivate)
        assertEquals(5, repo.stars)
        assertEquals("git@github.com:octo/app.git", repo.cloneSsh)
    }

    @Test
    fun `last page has no next page`() = runTest {
        enqueue("[]")
        assertNull(client().listRepos(3).nextPage)
    }

    @Test
    fun `issue list drops pull requests`() = runTest {
        enqueue(
            """[{"number":1,"title":"Bug","state":"open","user":{"login":"a"},"comments":3,"labels":[{"name":"bug"}]},
               {"number":2,"title":"PR","state":"open","pull_request":{"url":"x"}}]"""
        )
        val issues = client().listIssues(repo("octo/app"), StateFilter.OPEN, 1).items

        assertEquals("/repos/octo/app/issues?state=open&per_page=30&page=1", take().decodedTarget)
        assertEquals(listOf(1L), issues.map { it.number })
        assertEquals(listOf("bug"), issues.single().labels)
        assertEquals(3, issues.single().commentCount)
        assertFalse(issues.single().isPullRequest)
    }

    @Test
    fun `merged pull requests are reported as merged`() = runTest {
        enqueue(
            """[{"number":7,"title":"Feature","state":"closed","merged_at":"2026-01-01T00:00:00Z","draft":false,
               "head":{"ref":"feature/x"},"base":{"ref":"main"}},
               {"number":8,"title":"Rejected","state":"closed","merged_at":null,"head":{"ref":"y"},"base":{"ref":"main"}}]"""
        )
        val pulls = client().listPullRequests(repo("octo/app"), StateFilter.CLOSED, 1).items

        assertEquals(listOf(IssueState.MERGED, IssueState.CLOSED), pulls.map { it.state })
        assertEquals("feature/x", pulls[0].sourceBranch)
        assertTrue(pulls.all { it.isPullRequest })
    }

    @Test
    fun `tree lists directories first and encodes the path`() = runTest {
        enqueue(
            """[{"name":"b.txt","path":"src/b.txt","type":"file","size":4},
               {"name":"lib","path":"src/lib","type":"dir"},
               {"name":"A.kt","path":"src/A.kt","type":"file","size":10}]"""
        )
        val entries = client().listTree(repo("octo/app"), "feature/x", "src")

        assertEquals("/repos/octo/app/contents/src?ref=feature%2Fx", take().target)
        assertEquals(listOf("lib", "A.kt", "b.txt"), entries.map { it.name })
        assertEquals(EntryType.DIR, entries.first().type)
    }

    @Test
    fun `files are fetched raw`() = runTest {
        enqueue("hello world")
        val file = client().getFile(repo("octo/app"), "main", "docs/read me.md")

        val request = take()
        assertEquals("/repos/octo/app/contents/docs/read%20me.md?ref=main", request.target)
        assertEquals("application/vnd.github.raw", request.headers["Accept"])
        assertEquals("hello world", file.text)
        assertTrue(file.isMarkdown)
    }

    @Test
    fun `readme is decoded from base64 and missing readme is null`() = runTest {
        val encoded = Base64.getMimeEncoder().encodeToString("# Title\n\nBody text that wraps".toByteArray())
        enqueue("""{"path":"README.md","content":"${encoded.replace("\r\n", "\\n")}"}""")
        assertEquals("# Title\n\nBody text that wraps", client().getReadme(repo("octo/app"), "main")?.text)

        enqueue("""{"message":"Not Found"}""", code = 404)
        assertNull(client().getReadme(repo("octo/app"), "main"))
    }

    @Test
    fun `errors carry the status and api message`() = runTest {
        enqueue("""{"message":"Bad credentials"}""", code = 401)
        try {
            client().currentUser()
            fail("expected ForgeException")
        } catch (e: ForgeException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("Bad credentials"))
        }
    }

    @Test
    fun `comments are posted to the issue thread`() = runTest {
        enqueue("""{"id":99,"body":"Thanks!","user":{"login":"me"},"created_at":"2026-01-01T00:00:00Z"}""", code = 201)
        val issue = Issue(5, "t", null, IssueState.OPEN, null, null, null, 0, emptyList(), true, false, null)
        val comment = client().addComment(repo("octo/app"), issue, "Thanks!")

        val request = take()
        assertEquals("POST", request.method)
        assertEquals("/repos/octo/app/issues/5/comments", request.target)
        assertEquals("""{"body":"Thanks!"}""", request.body?.utf8())
        assertEquals("99", comment.id)
        assertEquals("me", comment.author?.login)
    }
}
