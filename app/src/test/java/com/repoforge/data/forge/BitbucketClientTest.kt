package com.repoforge.data.forge

import com.repoforge.data.model.EntryType
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.StateFilter
import com.repoforge.data.net.ForgeException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BitbucketClientTest : MockForge() {

    private fun client(email: String? = "me@example.com") = BitbucketClient(okHttp, apiBase("/2.0"), email, "ATATT-token")

    private fun repoJson(fullName: String) =
        """{"full_name":"$fullName","name":"${fullName.substringAfter('/')}","workspace":{"slug":"${fullName.substringBefore('/')}"},
           "is_private":true,"mainbranch":{"name":"main"},"language":"","updated_on":"2026-03-04T05:06:07.123456+00:00",
           "links":{"html":{"href":"https://bitbucket.org/$fullName"},
                    "clone":[{"name":"https","href":"https://me@bitbucket.org/$fullName.git"},{"name":"ssh","href":"git@bitbucket.org:$fullName.git"}]}}"""

    @Test
    fun `api token uses basic auth and access token uses bearer`() = runTest {
        enqueue("""{"username":"me","display_name":"Me","links":{"avatar":{"href":"https://a/me.png"}}}""")
        val user = client().currentUser()
        assertEquals(Credentials.basic("me@example.com", "ATATT-token"), take().headers["Authorization"])
        assertEquals("me", user.login)
        assertEquals("https://a/me.png", user.avatarUrl)

        enqueue("""{"nickname":"bot"}""")
        assertEquals("bot", client(email = null).currentUser().login)
        assertEquals("Bearer ATATT-token", take().headers["Authorization"])
    }

    @Test
    fun `repositories are listed workspace by workspace`() = runTest {
        val client = client()
        enqueue("""{"values":[{"workspace":{"slug":"alpha"}},{"workspace":{"slug":"beta"}}]}""")
        enqueue("""{"values":[${repoJson("alpha/one")}],"next":"https://api.bitbucket.org/2.0/repositories/alpha?page=2"}""")
        val first = client.listRepos(1)
        assertEquals("/2.0/user/workspaces?pagelen=100", take().target)
        assertTrue(take().target.startsWith("/2.0/repositories/alpha?role=member"))
        assertEquals(listOf("alpha/one"), first.items.map { it.fullName })
        assertEquals(2, first.nextPage)

        enqueue("""{"values":[${repoJson("alpha/two")}]}""")
        val second = client.listRepos(2)
        val secondTarget = take().target
        assertTrue(secondTarget.startsWith("/2.0/repositories/alpha?") && secondTarget.endsWith("page=2"))
        assertEquals(3, second.nextPage) // beta is still to come

        enqueue("""{"values":[${repoJson("beta/three")}]}""")
        val third = client.listRepos(3)
        assertTrue(take().target.startsWith("/2.0/repositories/beta?"))
        assertEquals(listOf("beta/three"), third.items.map { it.fullName })
        assertNull(third.nextPage)
    }

    @Test
    fun `repo parsing strips the user from clone urls`() {
        val repo = BitbucketClient.parseRepo(Json.parseToJsonElement(repoJson("ws/app")).jsonObject)
        assertEquals("https://bitbucket.org/ws/app.git", repo.cloneHttps)
        assertEquals("ws", repo.owner)
        assertEquals("main", repo.defaultBranch)
        assertNull(repo.language)
        assertTrue(repo.isPrivate)
        assertEquals(java.time.Instant.parse("2026-03-04T05:06:07.123456Z"), repo.updatedAt)
    }

    @Test
    fun `branch names with slashes are resolved to commit hashes`() = runTest {
        enqueue("""{"name":"feature/x","target":{"hash":"abc123"}}""")
        enqueue(
            """{"values":[{"path":"src/lib","type":"commit_directory"},{"path":"src/a.txt","type":"commit_file","size":3,"attributes":[]},
               {"path":"src/ln","type":"commit_file","attributes":["link"]}]}"""
        )
        val entries = client().listTree(repo("ws/app"), "feature/x", "src")

        assertEquals("/2.0/repositories/ws/app/refs/branches/feature%2Fx", take().target)
        assertEquals("/2.0/repositories/ws/app/src/abc123/src/?pagelen=100", take().target)
        assertEquals(listOf("lib" to EntryType.DIR, "a.txt" to EntryType.FILE, "ln" to EntryType.SYMLINK), entries.map { it.name to it.type })
    }

    @Test
    fun `root directory listing keeps the trailing slash`() = runTest {
        enqueue("""{"values":[]}""")
        client().listTree(repo("ws/app"), "main", "")
        assertEquals("/2.0/repositories/ws/app/src/main/?pagelen=100", take().target)
    }

    @Test
    fun `pull request states are requested and mapped`() = runTest {
        enqueue(
            """{"values":[{"id":5,"title":"Merge me","state":"MERGED","comment_count":4,
               "author":{"display_name":"Dev","nickname":"dev"},"source":{"branch":{"name":"x"}},
               "destination":{"branch":{"name":"main"}},"links":{"html":{"href":"https://bitbucket.org/ws/app/pull-requests/5"}}},
               {"id":6,"title":"No","state":"DECLINED"}]}"""
        )
        val pulls = client().listPullRequests(repo("ws/app"), StateFilter.CLOSED, 1).items

        assertEquals(
            "/2.0/repositories/ws/app/pullrequests?state=MERGED&state=DECLINED&state=SUPERSEDED&pagelen=30&page=1",
            take().target,
        )
        assertEquals(listOf(IssueState.MERGED, IssueState.CLOSED), pulls.map { it.state })
        assertEquals("x", pulls[0].sourceBranch)
        assertEquals(4, pulls[0].commentCount)
    }

    @Test
    fun `disabled issue tracker gives a clear message`() = runTest {
        enqueue("""{"type":"error","error":{"message":"Repository has no issue tracker."}}""", code = 404)
        try {
            client().listIssues(repo("ws/app"), StateFilter.OPEN, 1)
            fail("expected ForgeException")
        } catch (e: ForgeException) {
            assertTrue(e.message!!.contains("issue tracker"))
        }
        assertTrue(take().decodedTarget.contains("""q=state = "new" OR state = "open" OR state = "on hold""""))
    }
}
