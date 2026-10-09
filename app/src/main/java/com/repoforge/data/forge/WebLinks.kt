package com.repoforge.data.forge

import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.Repo
import java.net.URLEncoder

/** Browser URLs for files and folders, which the APIs don't return directly. */
object WebLinks {

    fun blob(type: ForgeType, repo: Repo, ref: String, path: String, isDir: Boolean = false): String? {
        val web = repo.webUrl?.trimEnd('/') ?: return null
        val tail = "${encode(ref)}/${encodePath(path)}".trimEnd('/')
        return when (type) {
            ForgeType.GITHUB -> "$web/${if (isDir) "tree" else "blob"}/$tail"
            ForgeType.GITLAB -> "$web/-/${if (isDir) "tree" else "blob"}/$tail"
            ForgeType.BITBUCKET -> "$web/src/$tail"
            ForgeType.GITEA -> "$web/src/branch/$tail"
        }
    }

    /** Direct download URL; works for public repositories (images in READMEs). */
    fun raw(type: ForgeType, repo: Repo, ref: String, path: String): String? {
        val web = repo.webUrl?.trimEnd('/') ?: return null
        val tail = "${encode(ref)}/${encodePath(path)}"
        return when (type) {
            ForgeType.GITHUB -> "$web/raw/$tail"
            ForgeType.GITLAB -> "$web/-/raw/$tail"
            ForgeType.BITBUCKET -> "$web/raw/$tail"
            ForgeType.GITEA -> "$web/raw/branch/$tail"
        }
    }

    /**
     * Resolves a link found in a markdown file at [fromPath] against the repository:
     * "img/a.png", "./a.png", "../a.png" and "/docs/a.png" all become repository paths.
     * Returns null for absolute URLs, anchors and other schemes, which need no rewriting.
     */
    fun resolveRelative(link: String, fromPath: String): String? {
        if (link.isBlank() || link.startsWith("#") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link) || link.startsWith("//")) {
            return null
        }
        val clean = link.substringBefore('#').substringBefore('?')
        val baseDir = if (clean.startsWith("/")) emptyList() else fromPath.split('/').dropLast(1)
        val parts = ArrayDeque(baseDir.filter { it.isNotEmpty() })
        for (segment in clean.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(java.net.URLDecoder.decode(segment, "UTF-8"))
            }
        }
        return parts.joinToString("/")
    }

    private val markdownImage = Regex("""(!\[[^\]]*]\()\s*<?([^)\s>]+)>?((?:\s+"[^"]*")?\s*\))""")
    private val htmlImage = Regex("""(<img\b[^>]*?\bsrc\s*=\s*["'])([^"']+)(["'])""", RegexOption.IGNORE_CASE)

    /** Points relative image references in [markdown] at the repository's raw file URLs. */
    fun rewriteImages(markdown: String, fromPath: String, rawUrl: (String) -> String?): String {
        fun replace(match: MatchResult): String {
            val (prefix, url, suffix) = match.destructured
            val resolved = resolveRelative(url, fromPath)?.let(rawUrl) ?: return match.value
            return prefix + resolved + suffix
        }
        return htmlImage.replace(markdownImage.replace(markdown, ::replace), ::replace)
    }

    private fun encode(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20").replace("%2F", "/")

    private fun encodePath(path: String) = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encode(it) }
}
