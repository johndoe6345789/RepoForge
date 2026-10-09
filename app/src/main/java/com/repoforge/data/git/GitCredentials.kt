package com.repoforge.data.git

import com.repoforge.data.model.Account
import com.repoforge.data.model.ForgeType
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/** HTTPS credentials for git operations: each service accepts the API token as the password. */
object GitCredentials {

    fun forAccount(account: Account): CredentialsProvider =
        UsernamePasswordCredentialsProvider(username(account), account.token)

    fun username(account: Account): String = when (account.type) {
        // Atlassian API tokens and repository/workspace access tokens each use a fixed username.
        ForgeType.BITBUCKET -> if (account.authUser != null) "x-bitbucket-api-token-auth" else "x-token-auth"
        // GitHub and GitLab accept any username with a token; Gitea wants the token owner.
        ForgeType.GITHUB, ForgeType.GITLAB, ForgeType.GITEA -> account.login
    }
}
