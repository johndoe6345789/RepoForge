package com.repoforge.data.git

import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercises the git layer against real repositories on disk, with a bare repo as the "server". */
class GitServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val git = GitService()
    private val author = PersonIdent("Ada", "ada@example.com")

    // Same isolation as the app: no system/user gitconfig from the machine running the tests.
    @org.junit.Before
    fun isolateConfig() = JGitAndroid.install(File(System.getProperty("java.io.tmpdir"), "repoforge-test-gitconfig"))

    /** Creates a bare "server" repository with one commit on main, and returns its file URL. */
    private fun server(files: Map<String, String> = mapOf("README.md" to "# Hello\n")): Pair<File, String> {
        val bare = tmp.newFolder("server.git")
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        val seed = tmp.newFolder("seed")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { g ->
            files.forEach { (path, text) -> File(seed, path).apply { parentFile.mkdirs(); writeText(text) } }
            g.add().addFilepattern(".").call()
            g.commit().setMessage("Initial").setAuthor(author).setCommitter(author).call()
            g.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toString())).call()
            g.push().setRemote("origin").add("main").call()
        }
        return bare to bare.toURI().toString()
    }

    private fun Git.commitFile(path: String, text: String?, message: String) {
        val file = File(repository.workTree, path)
        if (text == null) {
            rm().addFilepattern(path).call()
        } else {
            file.parentFile.mkdirs()
            file.writeText(text)
            add().addFilepattern(path).call()
        }
        commit().setMessage(message).setAuthor(author).setCommitter(author).call()
    }

    @Test
    fun `clone, commit, push and pull round trip`() = runTest {
        val (_, url) = server()
        val a = File(tmp.root, "a")
        val b = File(tmp.root, "b")
        val progress = mutableListOf<GitProgress>()
        git.clone(url, a, null, null) { progress += it }
        git.clone(url, b, "main", null) {}
        assertTrue(File(a, "README.md").exists())

        File(a, "README.md").writeText("# Hello, phone\n")
        File(a, "notes/new.txt").apply { parentFile.mkdirs(); writeText("new\n") }
        val status = git.status(a)
        assertEquals("main", status.branch)
        assertEquals(
            listOf("README.md" to ChangeKind.MODIFIED, "notes/new.txt" to ChangeKind.UNTRACKED),
            status.changes.map { it.path to it.kind },
        )

        git.commitAll(a, "Update from phone", author)
        assertEquals(1, git.status(a).ahead)
        git.push(a, null) {}
        assertEquals(0, git.status(a).ahead)

        val pulled = git.pull(b, null) {}
        assertFalse(pulled.upToDate)
        assertEquals("# Hello, phone\n", File(b, "README.md").readText())
        assertEquals("new\n", File(b, "notes/new.txt").readText())
        assertTrue(git.pull(b, null) {}.upToDate)
        Git.open(a).use { assertEquals("Update from phone", it.log().call().first().fullMessage) }
    }

    @Test
    fun `deleted files are committed too`() = runTest {
        val (_, url) = server(mapOf("a.txt" to "a\n", "b.txt" to "b\n"))
        val dir = File(tmp.root, "work")
        git.clone(url, dir, null, null) {}
        File(dir, "b.txt").delete()
        git.commitAll(dir, "Remove b", author)
        Git.open(dir).use { g ->
            val tree = g.repository.resolve("HEAD^{tree}")
            val walk = org.eclipse.jgit.treewalk.TreeWalk(g.repository).apply { addTree(tree) }
            val paths = generateSequence { if (walk.next()) walk.pathString else null }.toList()
            assertEquals(listOf("a.txt"), paths)
        }
    }

    @Test
    fun `push is rejected when the remote moved on`() = runTest {
        val (_, url) = server()
        val a = File(tmp.root, "a")
        val b = File(tmp.root, "b")
        git.clone(url, a, null, null) {}
        git.clone(url, b, null, null) {}
        File(a, "README.md").writeText("from a\n")
        git.commitAll(a, "a", author)
        git.push(a, null) {}
        File(b, "README.md").writeText("from b\n")
        git.commitAll(b, "b", author)
        try {
            git.push(b, null) {}
            fail("expected rejection")
        } catch (e: GitException) {
            assertTrue(e.message, e.message!!.contains("pull first"))
        }
    }

    @Test
    fun `clone refuses a non-empty folder`() = runTest {
        val (_, url) = server()
        val dir = tmp.newFolder("busy").apply { File(this, "keep.txt").writeText("x") }
        try {
            git.clone(url, dir, null, null) {}
            fail("expected failure")
        } catch (e: GitException) {
            assertTrue(File(dir, "keep.txt").exists())
        }
    }

    @Test
    fun `branches lists remote branches and checkout tracks them`() = runTest {
        val (bare, url) = server()
        Git.cloneRepository().setURI(url).setDirectory(tmp.newFolder("other")).call().use { g ->
            g.checkout().setCreateBranch(true).setName("feature/x").call()
            g.commitFile("x.txt", "x\n", "x")
            g.push().add("feature/x").call()
        }
        val dir = File(tmp.root, "work")
        git.clone(url, dir, null, null) {}
        assertEquals(listOf("main", "feature/x"), git.branches(dir))
        git.checkout(dir, "feature/x")
        assertEquals("feature/x", git.status(dir).branch)
        assertTrue(File(dir, "x.txt").exists())
        assertTrue(bare.exists())
    }

    /** main and feature both edit `app.txt`; feature deletes `gone.txt`, which main edits. */
    private fun conflictingServer(): String {
        val (_, url) = server(mapOf("app.txt" to "one\nshared\nthree\n", "gone.txt" to "old\n", "same.txt" to "same\n"))
        Git.cloneRepository().setURI(url).setDirectory(tmp.newFolder("dev")).call().use { g ->
            g.checkout().setCreateBranch(true).setName("feature").call()
            g.commitFile("app.txt", "one\nfeature\nthree\n", "Feature change")
            g.commitFile("gone.txt", null, "Remove gone.txt")
            g.push().add("feature").call()
            g.checkout().setName("main").call()
            g.commitFile("app.txt", "one\nmain\nthree\n", "Main change")
            g.commitFile("gone.txt", "updated\n", "Update gone.txt")
            g.commitFile("same.txt", "same, updated on main\n", "Clean change")
            g.push().add("main").call()
        }
        return url
    }

    @Test
    fun `merge workspace resolves conflicts and pushes the pull request branch`() = runTest {
        val url = conflictingServer()
        val workspace = MergeWorkspace(File(tmp.root, "merge"), MergeTarget(url, "main", "feature"), null)

        val prepared = workspace.prepare {}
        assertTrue(prepared is PrepareResult.Conflicts)
        val files = (prepared as PrepareResult.Conflicts).files.associateBy { it.path }
        assertEquals(setOf("app.txt", "gone.txt"), files.keys)

        val app = files.getValue("app.txt")
        assertEquals(ConflictKind.CONTENT, app.kind)
        assertEquals("one\nshared\nthree\n", app.ancestor)
        assertEquals("one\nfeature\nthree\n", app.head)
        assertEquals("one\nmain\nthree\n", app.base)
        val segments = ConflictMarkers.parse(app.withMarkers!!)
        assertNotNull(segments)
        val hunk = ConflictMarkers.hunks(segments!!).single()
        assertEquals("feature\n", hunk.head)
        assertEquals("main\n", hunk.base)
        assertEquals(ConflictKind.DELETED_IN_HEAD, files.getValue("gone.txt").kind)

        workspace.resolve("app.txt", ConflictMarkers.assemble(segments, listOf("feature and main\n")))
        workspace.resolveWithSide("gone.txt", keepHead = true) // keep the deletion
        val commitId = workspace.commitAndPush(workspace.defaultMessage(), author) {}

        // The server's feature branch now has the merge commit, with both parents.
        val check = File(tmp.root, "check")
        Git.cloneRepository().setURI(url).setDirectory(check).setBranch("feature").call().use { g ->
            val head = g.log().call().first()
            assertEquals(commitId, head.name)
            assertEquals(2, head.parentCount)
            assertEquals("Merge branch 'main' into feature", head.fullMessage)
        }
        assertEquals("one\nfeature and main\nthree\n", File(check, "app.txt").readText())
        assertFalse(File(check, "gone.txt").exists())
        assertEquals("same, updated on main\n", File(check, "same.txt").readText())

        // Running it again finds nothing left to merge.
        assertEquals(PrepareResult.UpToDate, workspace.prepare {})
    }

    @Test
    fun `merge workspace pushes to the fork for cross-repository pull requests`() = runTest {
        val url = conflictingServer()
        val fork = tmp.newFolder("fork.git")
        Git.cloneRepository().setURI(url).setDirectory(fork).setBare(true).setCloneAllBranches(true).call().close()
        val forkUrl = fork.toURI().toString()
        val workspace = MergeWorkspace(File(tmp.root, "merge"), MergeTarget(url, "main", "feature", forkUrl), null)

        val files = (workspace.prepare {} as PrepareResult.Conflicts).files
        files.forEach { workspace.resolveWithSide(it.path, keepHead = false) } // take main's versions
        workspace.commitAndPush("Update from main", author) {}

        Git.open(fork).use { g -> assertEquals("Update from main", g.log().add(g.repository.resolve("feature")).call().first().fullMessage) }
        Git.open(File(url.removePrefix("file:"))).use { g ->
            // The upstream feature branch is untouched.
            assertEquals("Remove gone.txt", g.log().add(g.repository.resolve("feature")).call().first().fullMessage)
        }
    }

    @Test
    fun `clean merges are reported without conflicts`() = runTest {
        val (_, url) = server(mapOf("a.txt" to "a\n", "b.txt" to "b\n"))
        Git.cloneRepository().setURI(url).setDirectory(tmp.newFolder("dev")).call().use { g ->
            g.checkout().setCreateBranch(true).setName("feature").call()
            g.commitFile("a.txt", "a2\n", "a")
            g.push().add("feature").call()
            g.checkout().setName("main").call()
            g.commitFile("b.txt", "b2\n", "b")
            g.push().add("main").call()
        }
        val workspace = MergeWorkspace(File(tmp.root, "merge"), MergeTarget(url, "main", "feature"), null)
        assertEquals(PrepareResult.Clean, workspace.prepare {})
        workspace.commitAndPush(workspace.defaultMessage(), author) {}
        assertEquals(PrepareResult.UpToDate, workspace.prepare {})
    }
}
