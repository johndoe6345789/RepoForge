package com.repoforge.data.templates

import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.Issue
import com.repoforge.data.model.IssueState
import com.repoforge.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTemplatesTest {
    private fun pr(author: String, isPull: Boolean = true) = Issue(
        number = 1, title = "t", body = null, state = IssueState.OPEN, author = User(author, null, null, null),
        createdAt = null, updatedAt = null, commentCount = null, labels = emptyList(), isPullRequest = isPull, isDraft = false, webUrl = null,
    )

    private fun ids(forge: ForgeType, issue: Issue) = CommentTemplates.forPullRequest(forge, issue).map { it.id }

    @Test
    fun `dependabot commands only appear on dependabot pull requests on github`() {
        assertTrue("dependabot-rebase" in ids(ForgeType.GITHUB, pr("dependabot[bot]")))
        assertFalse(ids(ForgeType.GITHUB, pr("alice")).any { it.startsWith("dependabot") })
        assertFalse(ids(ForgeType.GITLAB, pr("dependabot[bot]")).any { it.startsWith("dependabot") })
    }

    @Test
    fun `copilot templates appear on copilot pull requests`() {
        assertTrue("copilot-fix-checks" in ids(ForgeType.GITHUB, pr("Copilot")))
        assertTrue("copilot-fix-checks" in ids(ForgeType.GITHUB, pr("copilot-swe-agent[bot]")))
        assertFalse(ids(ForgeType.GITHUB, pr("alice")).any { it.startsWith("copilot") })
    }

    @Test
    fun `issues get no templates and every pull request gets the general ones`() {
        assertTrue(CommentTemplates.forPullRequest(ForgeType.GITHUB, pr("dependabot[bot]", isPull = false)).isEmpty())
        assertTrue(ids(ForgeType.BITBUCKET, pr("alice")).containsAll(CommentTemplates.general.map { it.id }))
    }

    @Test
    fun `placeholders are required and filled`() {
        val show = CommentTemplates.dependabot.first { it.id == "dependabot-show-ignores" }
        assertEquals(listOf("dependency"), show.params)
        assertEquals("@dependabot show lodash ignore conditions", show.render(mapOf("dependency" to " lodash ")))
        assertThrows(IllegalArgumentException::class.java) { show.render() }
        assertEquals("@dependabot rebase", CommentTemplates.dependabot.first().render())
    }

    @Test
    fun `ids are unique`() {
        val all = CommentTemplates.dependabot + CommentTemplates.copilot + CommentTemplates.general
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }
}
