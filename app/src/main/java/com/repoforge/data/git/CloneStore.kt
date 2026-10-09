package com.repoforge.data.git

import android.content.Context
import androidx.core.content.edit
import com.repoforge.data.net.long
import com.repoforge.data.net.objects
import com.repoforge.data.net.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** A repository cloned onto this device. */
data class LocalClone(
    val id: String,
    val accountId: String,
    val fullName: String,
    val name: String,
    val path: String,
    val cloneUrl: String,
    val webUrl: String?,
    val clonedAt: Long,
) {
    val dir: File get() = File(path)
}

/** Remembers clones made from the app, so they can be listed and reopened. */
class CloneStore(context: Context) {
    private val prefs = context.getSharedPreferences("clones", Context.MODE_PRIVATE)
    private val _clones = MutableStateFlow(load())
    val clones: StateFlow<List<LocalClone>> = _clones.asStateFlow()

    fun add(clone: LocalClone) {
        _clones.value = _clones.value.filterNot { it.path == clone.path } + clone
        persist()
    }

    fun remove(id: String) {
        _clones.value = _clones.value.filterNot { it.id == id }
        persist()
    }

    /** Drops entries whose folder was deleted outside the app. */
    fun prune() {
        val existing = _clones.value.filter { File(it.path, ".git").exists() }
        if (existing.size != _clones.value.size) {
            _clones.value = existing
            persist()
        }
    }

    private fun persist() {
        val json = JsonArray(_clones.value.map {
            buildJsonObject {
                put("id", it.id)
                put("accountId", it.accountId)
                put("fullName", it.fullName)
                put("name", it.name)
                put("path", it.path)
                put("cloneUrl", it.cloneUrl)
                put("webUrl", it.webUrl)
                put("clonedAt", it.clonedAt)
            }
        })
        prefs.edit { putString(KEY, json.toString()) }
    }

    private fun load(): List<LocalClone> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { Json.parseToJsonElement(raw).objects() }.getOrDefault(emptyList()).mapNotNull { o ->
            LocalClone(
                id = o.str("id") ?: return@mapNotNull null,
                accountId = o.str("accountId").orEmpty(),
                fullName = o.str("fullName").orEmpty(),
                name = o.str("name").orEmpty(),
                path = o.str("path") ?: return@mapNotNull null,
                cloneUrl = o.str("cloneUrl").orEmpty(),
                webUrl = o.str("webUrl"),
                clonedAt = o.long("clonedAt") ?: 0,
            )
        }
    }

    private companion object {
        const val KEY = "clones"
    }
}
