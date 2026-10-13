package com.repoforge.data.model

/**
 * GitLab and Gitea/Forgejo mark a pull request as a draft by a title prefix rather than a flag
 * (GitLab: `Draft:`; Gitea: `WIP:` by default), so toggling means rewriting the title.
 */
object DraftTitle {
    private val PREFIX = Regex("^\\s*(\\[(draft|wip)]|\\((draft|wip)\\)|(draft|wip):)\\s*", RegexOption.IGNORE_CASE)

    fun isDraft(title: String) = PREFIX.containsMatchIn(title)

    fun strip(title: String) = title.replace(PREFIX, "")

    fun apply(title: String, draft: Boolean, prefix: String): String =
        if (draft) "$prefix ${strip(title)}" else strip(title)
}
