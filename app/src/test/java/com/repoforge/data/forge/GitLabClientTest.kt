package com.repoforge.data.forge

import com.repoforge.data.model.EntryType
import com.repoforge.data.model.Issue
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.StateFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLabClientTest : MockForge() {

    private fun client() = GitLabClient(okHttp, apiBase("/api/v4"), "glpat-secret")

    @Test
    fun `lists member projects with private token and X-Next-Page`() = runTest {
        enqueue(
            """[{"id":42,"name":"Tool","path_with_namespace":"group/sub/tool","namespace":{"full_path":"group/sub"},
               "description":"","visibility":"internal","default_branch":"main","star_count":1,"forks_count":0,
               "last_activity_at":"2026-02-03T04:05:06.000Z","web_url":"https://gitlab.com/group/sub/tool",
               "http_url_to_repo":"https://gitlab.com/group/sub/tool.git","ssh_url_to_repo":"git@gitlab.com:group/sub/tool.git",
               "forked_from_project":{"id":1}}]""",
            headers = arrayOf("X-Next-Page" to "2"),
        )
        val page = client().listRepos(1)

        val request = take()
        assertEquals("glpat-secret", request.headers["PRIVATE-TOKEN"])
        assertTrue(request.target.startsWith("/api/v4/projects?membership=true"))
        assertEquals(2, page.nextPage)
        val repo = page.items.single()
        assertEquals("42", repo.apiId)
        assertEquals("group/sub/tool", repo.fullName)
        assertEquals("group/sub", repo.owner)
        assertNull(repo.description)
        assertTrue(repo.isPrivate)
        assertTrue(repo.isFork)
    }

    @Test
    fun `empty X-Next-Page ends pagination`() = runTest {
        enqueue("[]", headers = arrayOf("X-Next-Page" to ""))
        assertNull(client().listRepos(4).nextPage)
    }

    @Test
    fun `file path is a single encoded segment`() = runTest {
        enqueue("content")
        val file = client().getFile(repo("42"), "feature/x", "src/main/App.kt")

        assertEquals("/api/v4/projects/42/repository/files/src%2Fmain%2FApp.kt/raw?ref=feature%2Fx", take().target)
        assertEquals("content", file.text)
    }

    @Test
    fun `tree follows pages and maps types`() = runTest {
        enqueue(
            """[{"name":"src","path":"src","type":"tree","mode":"040000"},{"name":"link","path":"link","type":"blob","mode":"120000"}]""",
            headers = arrayOf("X-Next-Page" to "2"),
        )
        enqueue("""[{"name":"lib","path":"lib","type":"commit","mode":"160000"},{"name":"a.txt","path":"a.txt","type":"blob","mode":"100644"}]""")
        val entries = client().listTree(repo("42"), "main", "")

        assertEquals("/api/v4/projects/42/repository/tree?ref=main&per_page=100&page=1", take().target)
        assertEquals("/api/v4/projects/42/repository/tree?ref=main&per_page=100&page=2", take().target)
        assertEquals(
            listOf("src" to EntryType.DIR, "a.txt" to EntryType.FILE, "lib" to EntryType.SUBMODULE, "link" to EntryType.SYMLINK),
            entries.map { it.name to it.type },
        )
    }

    @Test
    fun `merge requests map opened and merged states`() = runTest {
        enqueue(
            """[{"iid":3,"title":"Draft: x","state":"opened","draft":true,"source_branch":"x","target_branch":"main",
               "author":{"username":"dev","name":"Dev"},"user_notes_count":2,"labels":["ui"]},
               {"iid":4,"title":"y","state":"merged"}]"""
        )
        val mrs = client().listPullRequests(repo("42"), StateFilter.OPEN, 1).items

        assertEquals("/api/v4/projects/42/merge_requests?state=opened&order_by=updated_at&per_page=30&page=1", take().target)
        assertEquals(listOf(IssueState.OPEN, IssueState.MERGED), mrs.map { it.state })
        assertTrue(mrs[0].isDraft)
        assertEquals(listOf("ui"), mrs[0].labels)
        assertEquals("dev", mrs[0].author?.login)
    }

    @Test
    fun `system notes are hidden from comments`() = runTest {
        enqueue(
            """[{"id":1,"body":"changed the description","system":true},
               {"id":2,"body":"Looks good","system":false,"author":{"username":"rev"},"created_at":"2026-01-01T00:00:00Z"}]"""
        )
        val mr = Issue(9, "t", null, IssueState.OPEN, null, null, null, null, emptyList(), true, false, null)
        val comments = client().listComments(repo("42"), mr)

        assertTrue(take().target.startsWith("/api/v4/projects/42/merge_requests/9/notes"))
        assertEquals(listOf("Looks good"), comments.map { it.body })
    }

    @Test
    fun `new issues send title and description`() = runTest {
        enqueue("""{"iid":12,"title":"Crash","description":"Steps","state":"opened"}""", code = 201)
        val issue = client().createIssue(repo("42"), "Crash", "Steps")

        val request = take()
        assertEquals("POST", request.method)
        assertEquals("""{"title":"Crash","description":"Steps"}""", request.body?.utf8())
        assertEquals(12L, issue.number)
    }
}
