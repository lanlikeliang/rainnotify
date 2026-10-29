package cn.rainnotify.app.answer

import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeepSeekApiTest {
    // Test local HTTP semantics, not runner speed or localhost DNS/IPv6 fallback.
    // Use a generous transport timeout; production deadlines are tested separately.
    private fun response(content: String, reason: String = "stop") = JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("finish_reason", reason).put("message", JSONObject().put("content", content)))).toString()
    @Test fun sendsAllLocalImagesAndJsonPromptWithoutExternalReferences() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response("{}"))); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val api = DeepSeekApi(server.url("/chat/completions"))
            assertEquals("{}", api.solve("fake-test-key", "JSON prompt", listOf(ApiImage("image/png", byteArrayOf(1,2)),
                ApiImage("image/jpeg", byteArrayOf(3,4))), 10_000, RunControl()))
            val request = requireNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("Bearer fake-test-key", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(DeepSeekModel.FLASH.id, body.getString("model"))
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
            assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            val blocks = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            assertEquals(3, blocks.length())
            assertEquals("data:image/png;base64,AQI=", blocks.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertEquals("data:image/jpeg;base64,AwQ=", blocks.getJSONObject(2).getJSONObject("image_url").getString("url"))
        }
    }
    @Test fun proUsesSelectedModelAndPlainTextContent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response("{}"))); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            assertEquals("{}", DeepSeekApi(server.url("/")).solve("fake", "JSON text question", emptyList(), 10_000, RunControl(), DeepSeekModel.PRO))
            val body = JSONObject(requireNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)).body.readUtf8())
            assertEquals("deepseek-v4-pro", body.getString("model"))
            assertEquals("JSON text question", body.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        }
    }
    @Test fun proRejectsImagesBeforeSendingOrDroppingThem() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val failure = assertThrows(AnswerFailure::class.java) {
                DeepSeekApi(server.url("/")).solve("fake", "JSON", listOf(ApiImage("image/png", byteArrayOf(1))), 10_000, RunControl(), DeepSeekModel.PRO)
            }
            assertTrue(failure.display.contains("切换 Flash"))
            assertEquals(0, server.requestCount)
            assertFalse(failure is ApiUnavailable) // An image question does not invalidate a text-capable model's key.
        }
    }
    @Test fun modelVerificationAndHistoricalAnswerStayBoundToTheModel() {
        assertNotEquals(DeepSeekModel.FLASH.verificationKey, DeepSeekModel.PRO.verificationKey)
        assertEquals("https://api.deepseek.com/chat/completions:deepseek-flash:v2", DeepSeekModel.FLASH.verificationKey)
        val task = AnswerTask("task", "q", true, AnswerPhase.SOLVING, "", model = DeepSeekModel.PRO)
        assertEquals(DeepSeekModel.PRO, task.changed(AnswerPhase.SUBMITTED, "", listOf("A")).model)
    }
    @Test fun textChecksUseActualTextQuestionsWithNoHiddenImageDependency() {
        for (type in listOf(1, 2)) {
            val id = java.util.UUID.randomUUID().toString()
            val sample = CompatibilitySample.createText(id, type, java.io.File("unused-test-directory"))
            val q = sample.prepared.record.content!!
            assertTrue(q.assets.isEmpty()); assertTrue(sample.prepared.images.isEmpty())
            assertEquals(sample.expected.sorted(), q.options.filter { it.html == "本次目标" }.map { it.label })
            val prompt = AnswerProtocol.prompt(q, id)
            assertTrue(prompt.contains("纯文字")); assertFalse(prompt.contains("必须结合所有附图"))
            val reply = JSONObject().put("task", id).put("answer", org.json.JSONArray(sample.expected)).put("unable", false).toString()
            assertEquals(sample.expected.sorted(), AnswerProtocol.parse(reply, id, q))
        }
    }
    @Test fun redirectsNeverForwardKeyToAnotherHostAndErrorsDontEchoServerBody() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            other.start(java.net.InetAddress.getByName("127.0.0.1"), 0); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/")))
            val api = DeepSeekApi(server.url("/"))
            assertThrows(AnswerFailure::class.java) { api.solve("fake", "JSON", emptyList(), 10_000, RunControl()) }
            assertEquals(0, other.requestCount)
            for (code in listOf(401,402,429,500)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("secret account details"))
                val failure = assertThrows(AnswerFailure::class.java) { api.solve("fake", "JSON", emptyList(), 10_000, RunControl()) }
                assertFalse(failure.display.contains("secret"))
                assertEquals(code in listOf(401, 402), failure is ApiUnavailable)
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
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val worker = Executors.newSingleThreadExecutor(); val control = RunControl()
            try {
                val result = worker.submit<String> { try { DeepSeekApi(server.url("/")).solve("fake", "JSON", emptyList(), 30_000, control) }
                    catch (e: AnswerFailure) { e.display } }
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS)); control.cancel()
                assertTrue(result.get(10, TimeUnit.SECONDS).contains("已停止"))
                assertEquals(1, server.requestCount)
            } finally { worker.shutdownNow() }
        }
    }
    @Test fun preCancelledRunNeverSendsAnything() {
        MockWebServer().use { server -> server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val control = RunControl().apply { cancel() }
            assertThrows(AnswerFailure::class.java) { DeepSeekApi(server.url("/")).solve("fake", "JSON", emptyList(), 10_000, control) }
            assertEquals(0, server.requestCount)
        }
    }
}
