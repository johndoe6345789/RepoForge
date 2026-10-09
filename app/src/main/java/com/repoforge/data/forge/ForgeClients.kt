package com.repoforge.data.forge

import com.repoforge.data.model.Account
import com.repoforge.data.model.ForgeType
import okhttp3.OkHttpClient

object ForgeClients {

    fun create(account: Account, http: OkHttpClient): ForgeClient =
        create(account.type, account.host, account.authUser, account.token, http)

    fun create(type: ForgeType, host: String, authUser: String?, token: String, http: OkHttpClient): ForgeClient {
        val api = apiBase(type, host)
        return when (type) {
            ForgeType.GITHUB -> GitHubClient(http, api, token)
            ForgeType.GITLAB -> GitLabClient(http, api, token)
            ForgeType.BITBUCKET -> BitbucketClient(http, api, authUser, token)
            ForgeType.GITEA -> GiteaClient(http, api, token)
        }
    }

    fun apiBase(type: ForgeType, host: String): String {
        val web = normalizeHost(host)
        return when (type) {
            ForgeType.GITHUB -> if (web == "https://github.com") "https://api.github.com" else "$web/api/v3"
            ForgeType.GITLAB -> "$web/api/v4"
            ForgeType.BITBUCKET -> "https://api.bitbucket.org/2.0"
            ForgeType.GITEA -> "$web/api/v1"
        }
    }

    /** Where the user creates a token, with the scopes RepoForge needs pre-selected where possible. */
    fun tokenPageUrl(type: ForgeType, host: String): String {
        val web = normalizeHost(host)
        return when (type) {
            ForgeType.GITHUB -> "$web/settings/tokens/new?description=RepoForge&scopes=repo,read:org,read:user"
            ForgeType.GITLAB -> "$web/-/user_settings/personal_access_tokens?name=RepoForge&scopes=api,read_user"
            ForgeType.BITBUCKET -> "https://id.atlassian.com/manage-profile/security/api-tokens"
            ForgeType.GITEA -> "$web/user/settings/applications"
        }
    }

    fun tokenHelp(type: ForgeType): String = when (type) {
        ForgeType.GITHUB -> "Create a personal access token (classic) with the repo, read:org and read:user scopes, or a fine-grained token with read access to contents, issues and pull requests (write access to comment)."
        ForgeType.GITLAB -> "Create a personal access token with the api scope (read_api is enough for browsing only)."
        ForgeType.BITBUCKET -> "Create an Atlassian API token with Bitbucket scopes (read:user, read:workspace, read:repository, read:pullrequest, read:issue, plus write:issue / write:pullrequest to comment), then sign in with your Atlassian account e-mail. Leave the e-mail empty to use a workspace or repository access token."
        ForgeType.GITEA -> "Create an access token under Settings → Applications with read access to repository, issue and user (write: issue to comment)."
    }

    /** Adds a scheme if missing and strips trailing slashes, e.g. "codeberg.org/" → "https://codeberg.org". */
    fun normalizeHost(host: String): String {
        val trimmed = host.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
}
