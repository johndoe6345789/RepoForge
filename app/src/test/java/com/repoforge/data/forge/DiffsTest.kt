package com.repoforge.data.forge

import com.repoforge.data.forge.Diffs.LineType
import com.repoforge.data.model.ChangeType
import com.repoforge.data.model.Issue
import com.repoforge.data.model.IssueState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffsTest : MockForge() {

    private val gitDiff = """
        diff --git a/src/App.kt b/src/App.kt
        index 1111111..2222222 100644
        --- a/src/App.kt
        +++ b/src/App.kt
        @@ -1,3 +1,4 @@
         package app
        -val a = 1
        +val a = 2
        +val b = 3
         fun main() {}
        diff --git a/new file.txt b/new file.txt
        new file mode 100644
        index 0000000..3333333
        --- /dev/null
        +++ b/new file.txt
        @@ -0,0 +1 @@
        +hello
        \ No newline at end of file
        diff --git a/old.txt b/renamed.txt
        similarity index 100%
        rename from old.txt
        rename to renamed.txt
        diff --git a/logo.png b/logo.png
        deleted file mode 100644
        index 4444444..0000000
        Binary files a/logo.png and /dev/null differ
    """.trimIndent() + "\n"

    @Test
    fun `git diffs split into files with change types and counts`() {
        val files = Diffs.parseGitDiff(gitDiff)

        assertEquals(listOf("src/App.kt", "new file.txt", "renamed.txt", "logo.png"), files.map { it.path })
        assertEquals(listOf(ChangeType.MODIFIED, ChangeType.ADDED, ChangeType.RENAMED, ChangeType.DELETED), files.map { it.change })
        assertEquals(2 to 1, files[0].additions to files[0].deletions)
        assertEquals("old.txt", files[2].oldPath)
        assertNull(files[2].patch)
        assertNull(files[3].patch)
    }

    @Test
    fun `patch lines carry old and new line numbers`() {
        val lines = Diffs.lines(Diffs.parseGitDiff(gitDiff)[0].patch!!)

        assertEquals(
            listOf(LineType.HUNK, LineType.CONTEXT, LineType.REMOVED, LineType.ADDED, LineType.ADDED, LineType.CONTEXT),
            lines.map { it.type },
        )
        assertEquals(listOf(null, 1, 2, null, null, 3), lines.map { it.oldNumber })
        assertEquals(listOf(null, 1, null, 2, 3, 4), lines.map { it.newNumber })
        assertEquals("val a = 1", lines[2].text)
    }

    private val pull = Issue(4, "t", null, IssueState.OPEN, null, null, null, null, emptyList(), true, false, null)

    @Test
    fun `github commit and pull request files`() = runTest {
        val client = GitHubClient(okHttp, apiBase(), "t")
        enqueue("""{"sha":"abc","files":[{"filename":"b.kt","previous_filename":"a.kt","status":"renamed","additions":1,"deletions":0,"patch":"@@ -1 +1,2 @@\n x\n+y"}]}""")
        val files = client.getCommitDiff(repo("o/r"), "abc")
        assertEquals("/repos/o/r/commits/abc", take().target)
        assertEquals(ChangeType.RENAMED, files.single().change)
        assertEquals("a.kt", files.single().oldPath)

        enqueue("""[{"filename":"c.kt","status":"added","additions":3,"deletions":0}]""")
        assertEquals(listOf("c.kt"), client.getPullRequestDiff(repo("o/r"), pull).map { it.path })
        assertEquals("/repos/o/r/pulls/4/files?per_page=100&page=1", take().target)
    }

    @Test
    fun `gitlab diffs count lines and detect deletions`() = runTest {
        val client = GitLabClient(okHttp, apiBase("/api/v4"), "t")
        enqueue("""[{"old_path":"gone.txt","new_path":"gone.txt","deleted_file":true,"diff":"@@ -1,2 +0,0 @@\n-a\n-b\n"},
                    {"old_path":"big.bin","new_path":"big.bin","diff":""}]""")
        val files = client.getPullRequestDiff(repo("7"), pull)
        assertEquals("/api/v4/projects/7/merge_requests/4/diffs?per_page=100&page=1", take().target)
        assertEquals(ChangeType.DELETED, files[0].change)
        assertEquals(0 to 2, files[0].additions to files[0].deletions)
        assertNull(files[1].patch)
    }

    @Test
    fun `bitbucket and gitea fetch raw diffs`() = runTest {
        enqueue(gitDiff)
        val bitbucket = BitbucketClient(okHttp, apiBase("/2.0"), "me@example.com", "t").getCommitDiff(repo("ws/app"), "abc")
        assertEquals("/2.0/repositories/ws/app/diff/abc", take().target)
        assertEquals(4, bitbucket.size)

        enqueue(gitDiff)
        GiteaClient(okHttp, apiBase("/api/v1"), "t").getPullRequestDiff(repo("o/r"), pull)
        assertEquals("/api/v1/repos/o/r/pulls/4.diff", take().target)
        enqueue(gitDiff)
        GiteaClient(okHttp, apiBase("/api/v1"), "t").getCommitDiff(repo("o/r"), "abc")
        assertEquals("/api/v1/repos/o/r/git/commits/abc.diff", take().target)
    }
}
