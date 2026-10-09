package com.repoforge.data

import android.content.Context
import androidx.core.content.edit
import com.repoforge.data.account.TokenCipher
import com.repoforge.data.ai.ClaudeModel
import com.repoforge.data.model.Account
import com.repoforge.data.model.hostLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.jgit.lib.PersonIdent

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User preferences that apply app-wide. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Created on first use: the Android Keystore isn't needed just to read display settings.
    private val cipher by lazy { TokenCipher("repoforge-settings-key") }

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _aiModel = MutableStateFlow(
        runCatching { ClaudeModel.valueOf(prefs.getString(KEY_AI_MODEL, null)!!) }.getOrDefault(ClaudeModel.OPUS)
    )
    val aiModel: StateFlow<ClaudeModel> = _aiModel.asStateFlow()

    private val _hasAiKey = MutableStateFlow(prefs.contains(KEY_AI_KEY))
    val hasAiKey: StateFlow<Boolean> = _hasAiKey.asStateFlow()

    private val _gitName = MutableStateFlow(prefs.getString(KEY_GIT_NAME, "").orEmpty())
    val gitName: StateFlow<String> = _gitName.asStateFlow()

    private val _gitEmail = MutableStateFlow(prefs.getString(KEY_GIT_EMAIL, "").orEmpty())
    val gitEmail: StateFlow<String> = _gitEmail.asStateFlow()

    private val _sharedClones = MutableStateFlow(prefs.getBoolean(KEY_SHARED_CLONES, false))
    /** Clone into Documents/RepoForge (needs "All files access") instead of app storage. */
    val sharedClones: StateFlow<Boolean> = _sharedClones.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        prefs.edit { putBoolean(KEY_DYNAMIC, enabled) }
    }

    fun setAiModel(model: ClaudeModel) {
        _aiModel.value = model
        prefs.edit { putString(KEY_AI_MODEL, model.name) }
    }

    /** The Anthropic API key, decrypted, or null when none is saved or it can't be read. */
    fun aiKey(): String? = prefs.getString(KEY_AI_KEY, null)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    fun setAiKey(key: String?) {
        val trimmed = key?.trim().orEmpty()
        prefs.edit {
            if (trimmed.isEmpty()) remove(KEY_AI_KEY) else putString(KEY_AI_KEY, cipher.encrypt(trimmed))
        }
        _hasAiKey.value = trimmed.isNotEmpty()
    }

    fun setGitIdentity(name: String, email: String) {
        _gitName.value = name.trim()
        _gitEmail.value = email.trim()
        prefs.edit { putString(KEY_GIT_NAME, name.trim()); putString(KEY_GIT_EMAIL, email.trim()) }
    }

    fun setSharedClones(enabled: Boolean) {
        _sharedClones.value = enabled
        prefs.edit { putBoolean(KEY_SHARED_CLONES, enabled) }
    }

    /** Commit author: the identity from Settings, else the account's name and a no-reply address. */
    fun authorFor(account: Account?): PersonIdent {
        val name = _gitName.value.ifBlank { account?.displayName ?: account?.login ?: "RepoForge" }
        val email = _gitEmail.value.ifBlank {
            account?.let { "${it.login}@users.noreply.${hostLabel(it.host)}" } ?: "repoforge@localhost"
        }
        return PersonIdent(name, email)
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_AI_MODEL = "ai_model"
        const val KEY_AI_KEY = "ai_key"
        const val KEY_GIT_NAME = "git_name"
        const val KEY_GIT_EMAIL = "git_email"
        const val KEY_SHARED_CLONES = "shared_clones"
    }
}
