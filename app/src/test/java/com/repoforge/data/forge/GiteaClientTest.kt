package com.repoforge.data.forge

import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.StateFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GiteaClientTest : MockForge() {

    private fun client() = GiteaClient(okHttp, apiBase("/api/v1"), "gitea-token")

    @Test
    fun `uses token auth and parses repos`() = runTest {
        enqueue(
            """[{"name":"old","full_name":"me/old","owner":{"login":"me"},"stars_count":1,"updated_at":"2025-01-01T00:00:00Z"},
               {"name":"new","full_name":"me/new","owner":{"login":"me"},"private":true,"stars_count":3,
                "description":"","default_branch":"dev","updated_at":"2026-01-01T00:00:00+01:00"}]""",
            headers = arrayOf("Link" to "<https://codeberg.org/api/v1/user/repos?page=2&limit=30>; rel=\"next\""),
        )
        val page = client().listRepos(1)

        val request = take()
        assertEquals("token gitea-token", request.headers["Authorization"])
        assertEquals("/api/v1/user/repos?page=1&limit=30", request.target)
        assertEquals(listOf("me/new", "me/old"), page.items.map { it.fullName })
        assertEquals(3, page.items.first().stars)
        assertEquals(2, page.nextPage)
    }

    @Test
    fun `search reads the data envelope`() = runTest {
        enqueue("""{"ok":true,"data":[{"name":"forgejo","full_name":"forgejo/forgejo","owner":{"login":"forgejo"}}]}""")
        val repos = client().searchRepos("forgejo", 1).items
        assertTrue(take().target.startsWith("/api/v1/repos/search?q=forgejo"))
        assertEquals("forgejo/forgejo", repos.single().apiId)
    }

    @Test
    fun `raw files and filtered issues`() = runTest {
        enqueue("data")
        client().getFile(repo("me/app"), "main", "a b/c.txt")
        assertEquals("/api/v1/repos/me/app/raw/a%20b/c.txt?ref=main", take().target)

        enqueue("""[{"number":3,"title":"Bug","state":"open","pull_request":null,"comments":1,"user":{"login":"u"}}]""")
        val issues = client().listIssues(repo("me/app"), StateFilter.OPEN, 1).items
        assertEquals("/api/v1/repos/me/app/issues?type=issues&state=open&page=1&limit=30", take().target)
        assertFalse(issues.single().isPullRequest)
        assertEquals(IssueState.OPEN, issues.single().state)
    }

    @Test
    fun `merged pull requests`() = runTest {
        enqueue("""[{"number":4,"title":"PR","state":"closed","merged":true,"head":{"ref":"x"},"base":{"ref":"main"}}]""")
        val pr = client().listPullRequests(repo("me/app"), StateFilter.ALL, 1).items.single()
        assertEquals(IssueState.MERGED, pr.state)
        assertTrue(pr.isPullRequest)
    }

    @Test
    fun `api base urls per provider`() {
        assertEquals("https://api.github.com", ForgeClients.apiBase(ForgeType.GITHUB, "github.com"))
        assertEquals("https://ghe.corp/api/v3", ForgeClients.apiBase(ForgeType.GITHUB, "https://ghe.corp/"))
        assertEquals("https://gitlab.com/api/v4", ForgeClients.apiBase(ForgeType.GITLAB, "https://gitlab.com"))
        assertEquals("https://api.bitbucket.org/2.0", ForgeClients.apiBase(ForgeType.BITBUCKET, "https://bitbucket.org"))
        assertEquals("https://codeberg.org/api/v1", ForgeClients.apiBase(ForgeType.GITEA, "codeberg.org/"))
        assertEquals("http://nas.local:3000/api/v1", ForgeClients.apiBase(ForgeType.GITEA, "http://nas.local:3000"))
    }
}
