package com.repoforge.data.forge

import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebLinksTest {

    private val repo = Repo(
        "o/r", "o", "r", "o/r", null, false, false, "main", null, null, null, null,
        "https://example.com/o/r", null, null, null,
    )

    @Test
    fun `relative links resolve against the file's folder`() {
        assertEquals("docs/img/a.png", WebLinks.resolveRelative("img/a.png", "docs/README.md"))
        assertEquals("docs/a.png", WebLinks.resolveRelative("./a.png", "docs/README.md"))
        assertEquals("a.png", WebLinks.resolveRelative("../a.png", "docs/README.md"))
        assertEquals("assets/logo.svg", WebLinks.resolveRelative("/assets/logo.svg", "docs/README.md"))
        assertEquals("my file.md", WebLinks.resolveRelative("my%20file.md#intro", "README.md"))
        assertNull(WebLinks.resolveRelative("https://x.org/a.png", "README.md"))
        assertNull(WebLinks.resolveRelative("#section", "README.md"))
        assertNull(WebLinks.resolveRelative("mailto:a@b.c", "README.md"))
    }

    @Test
    fun `relative images are rewritten to raw urls`() {
        val markdown = """
            ![logo](docs/logo.png "Logo")
            ![remote](https://img.shields.io/badge.svg)
            <img src="./shot.png" width="200">
        """.trimIndent()
        val rewritten = WebLinks.rewriteImages(markdown, "README.md") { WebLinks.raw(ForgeType.GITLAB, repo, "main", it) }
        assertEquals(
            """
            ![logo](https://example.com/o/r/-/raw/main/docs/logo.png "Logo")
            ![remote](https://img.shields.io/badge.svg)
            <img src="https://example.com/o/r/-/raw/main/shot.png" width="200">
            """.trimIndent(),
            rewritten,
        )
    }

    @Test
    fun `blob urls per provider`() {
        assertEquals("https://example.com/o/r/blob/feature/x/src/A.kt", WebLinks.blob(ForgeType.GITHUB, repo, "feature/x", "src/A.kt"))
        assertEquals("https://example.com/o/r/-/tree/main/src", WebLinks.blob(ForgeType.GITLAB, repo, "main", "src", isDir = true))
        assertEquals("https://example.com/o/r/src/main/a%20b.txt", WebLinks.blob(ForgeType.BITBUCKET, repo, "main", "a b.txt"))
        assertEquals("https://example.com/o/r/src/branch/main/x.md", WebLinks.blob(ForgeType.GITEA, repo, "main", "x.md"))
    }
}
