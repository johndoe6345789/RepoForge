package com.repoforge.data.model

import java.time.Instant

/** The hosting services RepoForge can talk to. */
enum class ForgeType(
    val displayName: String,
    /** Web host used when the user doesn't enter one; null means the user must supply a host. */
    val defaultHost: String?,
    val selfHostable: Boolean,
    /** What the provider calls a pull request. */
    val pullRequestName: String,
) {
    GITHUB("GitHub", "https://github.com", selfHostable = true, pullRequestName = "Pull requests"),
    GITLAB("GitLab", "https://gitlab.com", selfHostable = true, pullRequestName = "Merge requests"),
    BITBUCKET("Bitbucket", "https://bitbucket.org", selfHostable = false, pullRequestName = "Pull requests"),
    GITEA("Gitea / Forgejo", null, selfHostable = true, pullRequestName = "Pull requests");

    /** Suggested host for Gitea-family servers when the user hasn't entered one. */
    val hostHint: String get() = defaultHost ?: "https://codeberg.org"
}

/** A signed-in account. [host] is the web URL (e.g. https://gitlab.com), not the API URL. */
data class Account(
    val id: String,
    val type: ForgeType,
    val host: String,
    val login: String,
    val displayName: String?,
    val avatarUrl: String?,
    /** Bitbucket only: the Atlassian account e-mail paired with an API token for Basic auth. */
    val authUser: String?,
    val token: String,
) {
    val label: String get() = "$login · ${hostLabel(host)}"
}

fun hostLabel(host: String): String = host.removePrefix("https://").removePrefix("http://").trimEnd('/')

data class User(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val webUrl: String?,
)

data class Repo(
    /** Identifier the provider's API uses (owner/name, or the numeric project id on GitLab). */
    val apiId: String,
    val owner: String,
    val name: String,
    val fullName: String,
    val description: String?,
    val isPrivate: Boolean,
    val isFork: Boolean,
    val defaultBranch: String?,
    val stars: Int?,
    val forks: Int?,
    val language: String?,
    val updatedAt: Instant?,
    val webUrl: String?,
    val cloneHttps: String?,
    val cloneSsh: String?,
    val ownerAvatarUrl: String?,
)

enum class EntryType { DIR, FILE, SYMLINK, SUBMODULE }

data class TreeEntry(
    val name: String,
    val path: String,
    val type: EntryType,
    val size: Long?,
)

class FileBlob(
    val path: String,
    val bytes: ByteArray,
) {
    val name: String get() = path.substringAfterLast('/')
    val isBinary: Boolean by lazy { bytes.take(8000).any { it == 0.toByte() } }
    val text: String by lazy { bytes.toString(Charsets.UTF_8) }
    val isImage: Boolean
        get() = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    val isMarkdown: Boolean
        get() = name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd")

    companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico")
    }
}

data class Branch(val name: String, val sha: String?)

data class Commit(
    val sha: String,
    val message: String,
    val authorName: String?,
    val authorAvatarUrl: String?,
    val date: Instant?,
    val webUrl: String?,
) {
    val title: String get() = message.lineSequence().firstOrNull().orEmpty()
    val shortSha: String get() = sha.take(7)
}

enum class IssueState { OPEN, CLOSED, MERGED }

enum class StateFilter { OPEN, CLOSED, ALL }

/** An issue or a pull/merge request. */
data class Issue(
    val number: Long,
    val title: String,
    val body: String?,
    val state: IssueState,
    val author: User?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val commentCount: Int?,
    val labels: List<String>,
    val isPullRequest: Boolean,
    val isDraft: Boolean,
    val webUrl: String?,
    val sourceBranch: String? = null,
    val targetBranch: String? = null,
)

data class Comment(
    val id: String,
    val author: User?,
    val body: String,
    val createdAt: Instant?,
)

data class Page<T>(val items: List<T>, val nextPage: Int?)
