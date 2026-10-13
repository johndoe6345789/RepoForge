package com.repoforge.data.templates

import com.repoforge.data.model.ForgeType
import com.repoforge.data.model.Issue

enum class TemplateGroup(val label: String) { DEPENDABOT("Dependabot"), COPILOT("GitHub Copilot"), GENERAL("General") }

/** A canned comment. `{name}` placeholders in [body] are filled in by [render]. */
data class CommentTemplate(
    val id: String,
    val label: String,
    val body: String,
    val group: TemplateGroup,
) {
    /** Placeholders the user must fill in before the comment can be posted. */
    val params: List<String> = PLACEHOLDER.findAll(body).map { it.groupValues[1] }.distinct().toList()

    fun render(values: Map<String, String> = emptyMap()): String {
        val missing = params.filter { values[it].isNullOrBlank() }
        require(missing.isEmpty()) { "Missing value for: ${missing.joinToString()}" }
        return PLACEHOLDER.replace(body) { values.getValue(it.groupValues[1]).trim() }
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{([a-z_]+)}")
    }
}

/**
 * Canned comments for pull requests. The Dependabot commands are the ones in GitHub's "Dependabot pull request
 * comment commands" reference. `@copilot` mentions are how GitHub says to ask Copilot to change its own pull request.
 * Bot commands are only offered on that bot's own pull requests, where they work.
 */
object CommentTemplates {
    val dependabot = listOf(
        CommentTemplate("dependabot-rebase", "Rebase", "@dependabot rebase", TemplateGroup.DEPENDABOT),
        CommentTemplate("dependabot-recreate", "Recreate (discards edits)", "@dependabot recreate", TemplateGroup.DEPENDABOT),
        CommentTemplate("dependabot-ignore-major", "Ignore this major version", "@dependabot ignore this major version", TemplateGroup.DEPENDABOT),
        CommentTemplate("dependabot-ignore-minor", "Ignore this minor version", "@dependabot ignore this minor version", TemplateGroup.DEPENDABOT),
        CommentTemplate("dependabot-ignore-patch", "Ignore this patch version", "@dependabot ignore this patch version", TemplateGroup.DEPENDABOT),
        CommentTemplate("dependabot-ignore-dependency", "Ignore this dependency (closes the PR)", "@dependabot ignore this dependency", TemplateGroup.DEPENDABOT),
        CommentTemplate("dependabot-show-ignores", "Show ignore conditions", "@dependabot show {dependency} ignore conditions", TemplateGroup.DEPENDABOT),
    )

    val copilot = listOf(
        CommentTemplate("copilot-fix-checks", "Fix failing checks", "@copilot please fix the failing checks on this pull request.", TemplateGroup.COPILOT),
        CommentTemplate("copilot-conflicts", "Resolve merge conflicts", "@copilot please resolve the merge conflicts with the base branch.", TemplateGroup.COPILOT),
        CommentTemplate("copilot-review", "Address review comments", "@copilot please address the review comments.", TemplateGroup.COPILOT),
        CommentTemplate("copilot-tests", "Add tests", "@copilot please add tests for the changes in this pull request.", TemplateGroup.COPILOT),
        CommentTemplate("copilot-custom", "Ask Copilot to…", "@copilot {request}", TemplateGroup.COPILOT),
    )

    val general = listOf(
        CommentTemplate("general-ready", "Ready for review", "This is ready for review.", TemplateGroup.GENERAL),
        CommentTemplate("general-conflicts", "Please resolve conflicts", "This has merge conflicts with the base branch; please rebase or merge it and push again.", TemplateGroup.GENERAL),
        CommentTemplate("general-checks", "Please fix checks", "Some checks are failing; please take a look.", TemplateGroup.GENERAL),
    )

    private val DEPENDABOT_AUTHORS = setOf("dependabot[bot]", "dependabot-preview[bot]", "dependabot")
    private val COPILOT_AUTHORS = setOf("copilot", "copilot-swe-agent[bot]", "copilot-swe-agent")

    /** Templates for a pull request on [forge]: bot commands first when it was opened by that bot, then the general ones. */
    fun forPullRequest(forge: ForgeType, pull: Issue): List<CommentTemplate> {
        if (!pull.isPullRequest) return emptyList()
        val author = pull.author?.login?.lowercase().orEmpty()
        val bot = when {
            forge != ForgeType.GITHUB -> emptyList()
            author in DEPENDABOT_AUTHORS -> dependabot
            author in COPILOT_AUTHORS -> copilot
            else -> emptyList()
        }
        return bot + general
    }
}
