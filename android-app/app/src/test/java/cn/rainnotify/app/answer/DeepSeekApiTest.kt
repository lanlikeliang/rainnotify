package cn.rainnotify.app.answer

import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeepSeekApiTest {
    private fun response(content: String, reason: String = "stop") = JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("finish_reason", reason).put("message", JSONObject().put("content", content)))).toString()
    @Test fun sendsAllLocalImagesAndJsonPromptWithoutExternalReferences() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response("{}"))); server.start()
            val api = DeepSeekApi(server.url("/chat/completions"))
            assertEquals("{}", api.solve("fake-test-key", "JSON prompt", listOf(ApiImage("image/png", byteArrayOf(1,2)),
                ApiImage("image/jpeg", byteArrayOf(3,4))), 2000, RunControl()))
            val request = server.takeRequest()
            assertEquals("Bearer fake-test-key", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(DeepSeekApi.MODEL, body.getString("model"))
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            val blocks = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            assertEquals(3, blocks.length())
            assertEquals("data:image/png;base64,AQI=", blocks.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertEquals("data:image/jpeg;base64,AwQ=", blocks.getJSONObject(2).getJSONObject("image_url").getString("url"))
        }
    }
    @Test fun redirectsNeverForwardKeyToAnotherHostAndErrorsDontEchoServerBody() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            other.start(); server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/")))
            val api = DeepSeekApi(server.url("/"))
            assertThrows(AnswerFailure::class.java) { api.solve("fake", "JSON", emptyList(), 1000, RunControl()) }
            assertEquals(0, other.requestCount)
            for (code in listOf(401,402,429,500)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("secret account details"))
                val failure = assertThrows(AnswerFailure::class.java) { api.solve("fake", "JSON", emptyList(), 1000, RunControl()) }
                assertFalse(failure.display.contains("secret"))
            }
            assertEquals(5, server.requestCount)
        } }
    }
    @Test fun refusesTruncationEmptyReplyAndNonFinalToolOutput() {
        for (value in listOf(response("{}", "length"), response(""), "{\"choices\":[]}", "invalid"))
            assertThrows(AnswerFailure::class.java) { DeepSeekApi.responseText(value) }
    }
    @Test fun cancellationInterruptsInFlightNetworkAndDoesNotRetry() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val worker = Executors.newSingleThreadExecutor(); val control = RunControl()
            try {
                val result = worker.submit<String> { try { DeepSeekApi(server.url("/")).solve("fake", "JSON", emptyList(), 5000, control) }
                    catch (e: AnswerFailure) { e.display } }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); control.cancel()
                assertTrue(result.get(2, TimeUnit.SECONDS).contains("已停止"))
                assertEquals(1, server.requestCount)
            } finally { worker.shutdownNow() }
        }
    }
    @Test fun preCancelledRunNeverSendsAnything() {
        MockWebServer().use { server -> server.start()
            val control = RunControl().apply { cancel() }
            assertThrows(AnswerFailure::class.java) { DeepSeekApi(server.url("/")).solve("fake", "JSON", emptyList(), 1000, control) }
            assertEquals(0, server.requestCount)
        }
    }
}
