package com.repoforge.data.forge

import com.repoforge.data.model.Repo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before

/** Shared MockWebServer setup for the per-provider client tests. */
abstract class MockForge {
    protected val server = MockWebServer()
    protected val okHttp = OkHttpClient()

    @Before
    fun startServer() = server.start()

    @After
    fun stopServer() = server.close()

    /** API base on the mock server, e.g. `http://localhost:1234/api/v4`. */
    protected fun apiBase(path: String = "") = server.url(path).toString().trimEnd('/')

    protected fun enqueue(body: String, code: Int = 200, vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        server.enqueue(builder.build())
    }

    protected fun take(): RecordedRequest = server.takeRequest()

    /** Path plus query, decoded, for readable assertions. */
    protected val RecordedRequest.decodedTarget: String
        get() = java.net.URLDecoder.decode(target, "UTF-8")

    protected fun repo(apiId: String, defaultBranch: String = "main") = Repo(
        apiId = apiId, owner = apiId.substringBefore('/'), name = apiId.substringAfterLast('/'), fullName = apiId,
        description = null, isPrivate = false, isFork = false, defaultBranch = defaultBranch, stars = null,
        forks = null, language = null, updatedAt = null, webUrl = null, cloneHttps = null, cloneSsh = null,
        ownerAvatarUrl = null,
    )
}
