package com.repoforge.data.ai

import com.repoforge.data.git.ConflictFile
import com.repoforge.data.git.ConflictKind
import com.repoforge.data.git.ConflictMarkers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClaudeConflictResolverTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private val markers = "fun a() = 1\n<<<<<<< HEAD\nfun b() = 2\n=======\nfun b() = 3\n>>>>>>> origin/main\nfun c() = 4\n"
    private val file = ConflictFile("src/App.kt", ConflictKind.CONTENT, "fun a() = 1\nfun b() = 0\nfun c() = 4\n", null, null, markers)
    private val segments = ConflictMarkers.parse(markers)!!
    private val context = PullContext("Make b return 2", "Because two is better.", "feature/b", "main")

    private fun resolver(model: ClaudeModel = ClaudeModel.OPUS) =
        ClaudeConflictResolver("sk-test", model, baseUrl = server.url("/").toString().trimEnd('/'))

    private fun reply(text: String, stopReason: String = "end_turn", code: Int = 200) {
        val body = buildJsonObject {
            put("id", "msg_1"); put("type", "message"); put("role", "assistant"); put("model", "claude-opus-5-5")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
            put("stop_reason", stopReason)
            put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 20) })
        }
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body.toString()).build())
    }

    @Test
    fun `sends the conflict with a json schema and parses the resolution`() = runTest {
        reply("""{"resolutions":[{"conflict":1,"replacement":"fun b() = 2 // from feature, 3 on main\n"}],"explanation":"Kept the feature value."}""")

        val resolution = resolver().resolve(file, segments, context)

        assertEquals(listOf("fun b() = 2 // from feature, 3 on main\n"), resolution.hunks)
        assertEquals("Kept the feature value.", resolution.explanation)
        assertEquals(
            "fun a() = 1\nfun b() = 2 // from feature, 3 on main\nfun c() = 4\n",
            ConflictMarkers.assemble(segments, resolution.hunks),
        )

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.target)
        assertEquals("sk-test", request.headers["x-api-key"])
        assertTrue(request.headers.values("anthropic-beta").any { "server-side-fallback-2026-07-01" in it })
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("claude-opus-5-5", body.str("model"))
        assertEquals("default", body.str("fallbacks"))
        val output = body.getValue("output_config").jsonObject
        assertEquals("high", output.str("effort"))
        val format = output.getValue("format").jsonObject
        assertEquals("json_schema", format.str("type"))
        assertEquals(listOf("\"resolutions\"", "\"explanation\""), format.getValue("schema").jsonObject.getValue("required").jsonArray.map { it.toString() })
        assertFalse("thinking can't be disabled on Opus 5.5", body.containsKey("thinking"))
        val prompt = body.getValue("messages").jsonArray[0].jsonObject.getValue("content").let {
            if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.jsonArray[0].jsonObject.str("text")
        }
        assertTrue(prompt.contains("<<<<<<< CONFLICT 1: head (feature/b)"))
        assertTrue(prompt.contains("=======  base (main)"))
        assertTrue(prompt.contains("<ancestor>\nfun a() = 1\nfun b() = 0"))
        assertTrue(prompt.contains("Because two is better."))
    }

    @Test
    fun `haiku requests carry no server fallback`() = runTest {
        reply("""{"resolutions":[{"conflict":1,"replacement":"x"}],"explanation":""}""")
        resolver(ClaudeModel.HAIKU).resolve(file, segments, context)
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("claude-haiku-5-5", body.str("model"))
        assertFalse(body.containsKey("fallbacks"))
        assertTrue(request.headers.values("anthropic-beta").none { "server-side-fallback" in it })
    }

    @Test
    fun `refusals, truncation and missing conflicts become clear errors`() = runTest {
        reply("", stopReason = "refusal")
        expectError("declined")
        reply("""{"resolutions":[""", stopReason = "max_tokens")
        expectError("too large")
        reply("""{"resolutions":[],"explanation":"none"}""")
        expectError("skipped conflict 1")
    }

    @Test
    fun `a rejected api key points to settings`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                .body("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").build()
        )
        expectError("Settings")
    }

    private suspend fun expectError(fragment: String) {
        try {
            resolver().resolve(file, segments, context)
            fail("expected AiException")
        } catch (e: AiException) {
            assertTrue("'${e.message}' should mention '$fragment'", e.message!!.contains(fragment))
        }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
}
