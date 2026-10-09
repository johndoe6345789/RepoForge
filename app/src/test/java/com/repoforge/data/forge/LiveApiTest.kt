package com.repoforge.data.forge

import com.repoforge.data.model.Repo
import com.repoforge.data.model.StateFilter
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Smoke tests against the real services, anonymously, using public repositories. They check that
 * each client's requests are accepted and its parsers understand live payloads.
 * Excluded by default; run with `./gradlew :app:testDebugUnitTest -Plive`.
 */
class LiveApiTest {
    private val http = OkHttpClient()

    private fun repo(apiId: String, fullName: String = apiId, branch: String) = Repo(
        apiId, fullName.substringBeforeLast('/'), fullName.substringAfterLast('/'), fullName, null, false, false,
        branch, null, null, null, null, null, null, null, null,
    )

    /** [anonymousComments] is false where the service requires sign-in to read comments (GitLab notes). */
    private suspend fun exercise(client: ForgeClient, repo: Repo, expectIssues: Boolean = true, anonymousComments: Boolean = true) {
        val ref = repo.defaultBranch!!
        val branches = client.listBranches(repo)
        assertTrue("branches", branches.any { it.name == ref })

        val root = client.listTree(repo, ref, "")
        assertTrue("root entries", root.isNotEmpty())
        val readme = client.getReadme(repo, ref)
        assertNotNull("readme", readme)
        assertTrue("readme text", readme!!.text.isNotBlank())

        root.firstOrNull { it.type == com.repoforge.data.model.EntryType.DIR }?.let { dir ->
            assertTrue("subdirectory", client.listTree(repo, ref, dir.path).isNotEmpty())
        }

        val commits = client.listCommits(repo, ref, 1)
        assertTrue("commits", commits.items.isNotEmpty())
        assertTrue("commit fields", commits.items.first().sha.length >= 7 && commits.items.first().date != null)

        val diff = client.getCommitDiff(repo, commits.items.first().sha)
        assertTrue("commit diff", diff.isNotEmpty() && diff.all { it.path.isNotEmpty() })

        val pulls = client.listPullRequests(repo, StateFilter.ALL, 1)
        assertTrue("pull requests", pulls.items.isNotEmpty())
        val pullDiff = client.getPullRequestDiff(repo, pulls.items.first())
        if (anonymousComments) client.listComments(repo, pulls.items.first())

        if (expectIssues) {
            val issues = client.listIssues(repo, StateFilter.ALL, 1)
            assertTrue("issues", issues.items.isNotEmpty())
            if (anonymousComments) client.listComments(repo, issues.items.first())
        }
        println("${client.javaClass.simpleName}: ${repo.fullName} — ${branches.size} branches, ${root.size} root entries, " +
            "readme ${readme.path}, ${commits.items.size} commits (latest changes ${diff.size} files), ${pulls.items.size} PRs (first changes ${pullDiff.size} files)")
    }

    @Test
    fun github() = runTest(timeout = 2.minutes) {
        val client = ForgeClients.create(com.repoforge.data.model.ForgeType.GITHUB, "https://github.com", null, "", http)
        // This repository, since sandboxed CI sessions may only reach the API for it.
        exercise(client, repo("johndoe6345789/RepoForge", branch = "main"), expectIssues = false)
    }

    @Test
    fun gitlab() = runTest(timeout = 2.minutes) {
        val client = ForgeClients.create(com.repoforge.data.model.ForgeType.GITLAB, "https://gitlab.com", null, "", http)
        exercise(client, repo("gitlab-org/cli", branch = "main"), anonymousComments = false)
        assertTrue(client.searchRepos("gitlab-runner", 1).items.isNotEmpty())
    }

    @Test
    fun bitbucket() = runTest(timeout = 2.minutes) {
        val client = ForgeClients.create(com.repoforge.data.model.ForgeType.BITBUCKET, "https://bitbucket.org", null, "", http)
        exercise(client, repo("atlassian/atlassian-connect-express", branch = "master"), expectIssues = false)
    }

    @Test
    fun codeberg() = runTest(timeout = 2.minutes) {
        val client = ForgeClients.create(com.repoforge.data.model.ForgeType.GITEA, "https://codeberg.org", null, "", http)
        exercise(client, repo("forgejo/forgejo", branch = "forgejo"))
        assertTrue(client.searchRepos("forgejo", 1).items.isNotEmpty())
    }
}
