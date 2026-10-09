package com.repoforge.data.git

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.minutes

/** Clones real repositories over HTTPS with JGit. Excluded by default; run with `-Plive`. */
class LiveGitTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @org.junit.Before
    fun isolateConfig() = JGitAndroid.install(File(System.getProperty("java.io.tmpdir"), "repoforge-test-gitconfig"))

    private suspend fun cloneAndCheck(url: String) {
        val dir = File(tmp.root, url.substringAfterLast('/'))
        val tasks = mutableSetOf<String>()
        GitService().clone(url, dir, null, null) { tasks += it.task }
        val status = GitService().status(dir)
        assertTrue("clean checkout", status.isClean)
        assertTrue("files checked out", dir.list()!!.size > 1)
        println("$url -> ${status.branch}, progress tasks: $tasks")
    }

    @Test
    fun github() = runTest(timeout = 3.minutes) { cloneAndCheck("https://github.com/johndoe6345789/RepoForge.git") }

    @Test
    fun codeberg() = runTest(timeout = 3.minutes) { cloneAndCheck("https://codeberg.org/forgejo/docs.git") }
}
