package com.repoforge.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** An HTTP error from a forge API, with a message fit to show the user. */
class ForgeException(val code: Int, message: String, cause: Throwable? = null) : IOException(message, cause) {
    companion object {
        /** Prefix of messages for failures before any HTTP response (offline, DNS, TLS). */
        const val NETWORK_PREFIX = "Network error"
    }
}

class HttpResult(val code: Int, val headers: Headers, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8)
    val json: JsonElement get() = Json.parseToJsonElement(text)

    /** True when the response's RFC 5988 `Link` header advertises a next page. */
    val hasNextLink: Boolean
        get() = headers.values("Link").any { link -> link.split(',').any { it.contains("rel=\"next\"") } }
}

/** Builds an API URL; path segments are percent-encoded so user-supplied names are safe. */
class UrlSpec(base: HttpUrl) {
    private val builder = base.newBuilder()

    /** Appends each argument as a single encoded segment (a `/` inside one becomes `%2F`). */
    fun seg(vararg segments: String) {
        segments.forEach { builder.addPathSegment(it) }
    }

    /** Appends a slash-separated path, one segment per component. */
    fun path(path: String) {
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
    }

    fun q(name: String, value: Any?) {
        if (value != null) builder.addQueryParameter(name, value.toString())
    }

    fun build(): HttpUrl = builder.build()
}

/**
 * Thin coroutine wrapper over OkHttp for one API base URL. [authorize] adds the provider's
 * authentication headers to every request.
 */
class Http(
    private val client: OkHttpClient,
    apiBase: String,
    private val authorize: Request.Builder.() -> Unit,
) {
    val base: HttpUrl = apiBase.trimEnd('/').toHttpUrl()

    fun url(spec: UrlSpec.() -> Unit): HttpUrl = UrlSpec(base).apply(spec).build()

    suspend fun get(url: HttpUrl, accept: String = "application/json"): HttpResult =
        execute(Request.Builder().url(url).header("Accept", accept))

    suspend fun getJson(spec: UrlSpec.() -> Unit): HttpResult = get(url(spec))

    suspend fun postJson(url: HttpUrl, body: JsonObject): HttpResult =
        execute(
            Request.Builder().url(url)
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        )

    private suspend fun execute(builder: Request.Builder): HttpResult = withContext(Dispatchers.IO) {
        val request = builder.apply(authorize).build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ForgeException(0, "${ForgeException.NETWORK_PREFIX}: ${e.message ?: e.javaClass.simpleName}", e)
        }
        response.use {
            val bytes = it.body.bytes()
            if (!it.isSuccessful) throw ForgeException(it.code, describeError(it.code, bytes))
            HttpResult(it.code, it.headers, bytes)
        }
    }

    private fun describeError(code: Int, body: ByteArray): String {
        val detail = runCatching { extractMessage(Json.parseToJsonElement(body.toString(Charsets.UTF_8))) }.getOrNull()
        val summary = when (code) {
            401 -> "Authentication failed — check the token"
            403 -> "Access denied — the token may be missing a scope"
            404 -> "Not found"
            409 -> "Conflict"
            410 -> "No longer available"
            422 -> "Request rejected"
            429 -> "Rate limited — try again later"
            in 500..599 -> "Server error"
            else -> "Request failed"
        }
        return if (detail.isNullOrBlank()) "$summary (HTTP $code)" else "$summary (HTTP $code): $detail"
    }

    private fun extractMessage(json: JsonElement): String? {
        val obj = json as? JsonObject ?: return null
        // GitHub/Gitea: {"message"}, GitLab: {"message"} or {"error"}, Bitbucket: {"error":{"message"}}
        obj.str("message")?.let { return it }
        obj.obj("error")?.str("message")?.let { return it }
        obj.str("error_description")?.let { return it }
        return obj.str("error")
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
