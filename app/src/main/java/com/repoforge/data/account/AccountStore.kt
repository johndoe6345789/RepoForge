package com.repoforge.data.account

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.repoforge.data.model.Account
import com.repoforge.data.model.ForgeType
import com.repoforge.data.net.objects
import com.repoforge.data.net.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persists signed-in accounts in SharedPreferences. Tokens are stored encrypted by
 * [TokenCipher]; everything else (host, login, avatar) is plain metadata.
 */
class AccountStore(context: Context, private val cipher: TokenCipher = TokenCipher()) {

    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    fun active(): Account? = _accounts.value.firstOrNull { it.id == _activeId.value } ?: _accounts.value.firstOrNull()

    /** Adds the account, replacing an existing one for the same user on the same host. */
    fun save(account: Account) {
        val others = _accounts.value.filterNot { it.id == account.id }
        _accounts.value = others + account
        persist()
        setActive(account.id)
    }

    fun remove(id: String) {
        _accounts.value = _accounts.value.filterNot { it.id == id }
        persist()
        if (_activeId.value == id) setActive(_accounts.value.firstOrNull()?.id)
    }

    fun setActive(id: String?) {
        _activeId.value = id
        prefs.edit { putString(KEY_ACTIVE, id) }
    }

    private fun persist() {
        val json = JsonArray(_accounts.value.map { account ->
            buildJsonObject {
                put("id", account.id)
                put("type", account.type.name)
                put("host", account.host)
                put("login", account.login)
                put("displayName", account.displayName)
                put("avatarUrl", account.avatarUrl)
                put("authUser", account.authUser)
                put("token", cipher.encrypt(account.token))
            }
        })
        prefs.edit { putString(KEY_ACCOUNTS, json.toString()) }
    }

    private fun load(): List<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching { Json.parseToJsonElement(raw).objects() }.getOrDefault(emptyList()).mapNotNull { obj ->
            try {
                Account(
                    id = obj.str("id")!!,
                    type = ForgeType.valueOf(obj.str("type")!!),
                    host = obj.str("host")!!,
                    login = obj.str("login")!!,
                    displayName = obj.str("displayName"),
                    avatarUrl = obj.str("avatarUrl"),
                    authUser = obj.str("authUser"),
                    token = cipher.decrypt(obj.str("token")!!),
                )
            } catch (e: Exception) {
                // A token that can't be decrypted (e.g. Keystore reset) means the user signs in again.
                Log.w("AccountStore", "Dropping unreadable account", e)
                null
            }
        }
    }

    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active"

        fun accountId(type: ForgeType, host: String, login: String) = "${type.name}:${host.lowercase()}:$login"
    }
}
