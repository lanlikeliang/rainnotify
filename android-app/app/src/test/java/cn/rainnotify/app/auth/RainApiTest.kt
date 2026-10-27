package cn.rainnotify.app.auth

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import cn.rainnotify.app.question.QuestionRef

class RainApiTest {
    @Test fun acceptsLargePresentationButOnlyReturnsRequestedQuestion() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"slides":[
              {"problem":{"problemId":99,"body":"""" + "x".repeat(1_100_000) + """"}},
              {"problem":{"problemId":30,"problemType":1,"body":"target"}}]}}"""))
            server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            val question = api.question(QuestionRef("10", "20", "30"))
            assertEquals("target", question.bodyHtml)
            assertTrue(question.json().toString().length < 1000)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun questionFetchUsesExistingAuthAndOnlyReadOnlyPresentationEndpoint() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Set-Auth", "fixture-content-auth").setBody("""{"code":0,"data":{}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"slides":[{"id":40,
              "problem":{"problemId":30,"problemType":1,"body":"PRIVATE_QUESTION",
              "options":[{"key":"A","value":"option"}]}}]}}"""))
            server.start()
            val logs = mutableListOf<String>()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/")) { kind, fields ->
                logs.add(kind + fields.toString())
            }
            api.validateSession()
            assertEquals("PRIVATE_QUESTION", api.question(QuestionRef("10", "20", "30")).bodyHtml)
            server.takeRequest(1, TimeUnit.SECONDS)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/api/v3/lesson/presentation/fetch?presentation_id=20", request.path)
            assertEquals("Bearer fixture-content-auth", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Referer")!!.endsWith("/lesson/fullscreen/v3/10?source=5"))
            assertFalse(logs.joinToString().contains("PRIVATE_QUESTION"))
            assertFalse(logs.joinToString().contains("fixture-content-auth"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun questionFetchRejectsMalformedReferenceBeforeNetwork() {
        val api = RainApi(RainCookieJar())
        assertThrows(RainFailure::class.java) { api.question(QuestionRef("10", "../wrong", "30")) }
    }

    @Test fun officialV3HeadersStringIdAndReturnedAuthorizationArePreserved() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Set-Auth", "fixture-server-auth")
                .addHeader("Set-Cookie", "csrftoken=fixture-csrf; Path=/")
                .setBody("""{"code":0,"data":{}}"""))
            server.enqueue(MockResponse().setBody(
                """{"code":0,"data":{"identityId":42,"lessonToken":"fixture-lesson-token"}}"""))
            server.start()
            val records = mutableListOf<String>()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/")) { kind, fields ->
                records.add(kind + fields.toString())
            }
            api.validateSession()
            api.enterLesson("123")
            val validation = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("h5", validation.getHeader("X-Client"))
            assertNull(validation.getHeader("Authorization"))
            val entry = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer fixture-server-auth", entry.getHeader("Authorization"))
            assertEquals("fixture-csrf", entry.getHeader("X-CSRFToken"))
            assertEquals("ykt", entry.getHeader("xtbz"))
            assertEquals("h5", entry.getHeader("X-Client"))
            assertEquals("123", JSONObject(entry.body.readUtf8()).get("lessonId"))
            for (secret in listOf("fixture-server-auth", "fixture-csrf", "fixture-lesson-token")) {
                assertFalse(records.joinToString().contains(secret))
            }
        }
    }

    @Test fun httpFailureKeepsBusinessCodeButNeverLogsResponseText() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody(
                """{"code":50001,"msg":"private response fixture","data":{"token":"private-token"}}"""))
            server.start()
            val records = mutableListOf<String>()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/")) { kind, fields ->
                records.add(kind + fields.toString())
            }
            val error = assertThrows(RainFailure::class.java) { api.enterLesson("123") }
            assertEquals(400, error.httpStatus)
            assertEquals(50001, error.businessCode)
            assertTrue(error.userMessage().contains("50001"))
            assertFalse(records.joinToString().contains("private"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun explicitEntryUsesOnlySelectedLessonAndKeepsCredentialsOutOfDiagnostics() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"code":0,"msg":"OK","data":{"identityId":42,"lessonToken":"fixture-lesson-token"}}"""))
            server.start()
            val records = mutableListOf<String>()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/")) { kind, fields ->
                records.add(kind + fields.toString())
            }
            val credentials = api.enterLesson("123")
            assertEquals("fixture-lesson-token", credentials.token)
            val entry = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", entry.method)
            assertEquals("/api/v3/lesson/checkin", entry.path)
            assertTrue(entry.getHeader("Referer")!!.endsWith("/lesson/fullscreen/v3/123?source=5"))
            val body = JSONObject(entry.body.readUtf8())
            assertEquals(2, body.length())
            assertEquals(5, body.getInt("source"))
            assertEquals("123", body.get("lessonId"))
            assertFalse(records.joinToString().contains("fixture-lesson-token"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun ambiguousCheckinFailureDoesNotRetryPost() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            assertEquals(FailureReason.NETWORK, assertThrows(RainFailure::class.java) { api.enterLesson("123") }.reason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun dynamicQrRestrictionGetsActionableMessageForBothHttpAndBusinessRejectionWithoutRetry() {
        for (status in listOf(200, 400)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setBody(
                """{"code":50070,"msg":"private server text","data":{"secret":"not-for-logs"}}"""))
            server.start()
            val logs = mutableListOf<String>()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/")) { kind, fields -> logs.add(kind + fields) }
            val failure = assertThrows(RainFailure::class.java) { api.enterLesson("123") }
            assertTrue(failure.requiresClassroomScan)
            assertEquals(50070, failure.businessCode)
            assertTrue(failure.userMessage().contains("动态二维码"))
            assertTrue(failure.userMessage().contains("无需重新登录"))
            assertEquals(1, server.requestCount)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals(5, JSONObject(request.body.readUtf8()).getInt("source"))
            assertFalse(logs.joinToString().contains("private server text"))
            assertFalse(logs.joinToString().contains("not-for-logs"))
        }
    }

    @Test fun otherErrorsDoNotBecomeScanPromptsAndAuthorizedEntryStillReturnsCredentials() {
        assertFalse(RainFailure(FailureReason.RESPONSE_REJECTED, "question_content", businessCode = 50070).requiresClassroomScan)
        assertFalse(RainFailure(FailureReason.RESPONSE_REJECTED, "lesson_checkin", businessCode = 50019).requiresClassroomScan)
        assertFalse(RainFailure(FailureReason.NETWORK, "lesson_checkin").requiresClassroomScan)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"identityId":42,"lessonToken":"fixture-token"}}"""))
            server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            assertEquals("123", api.enterLesson("123").lessonId)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun acceptsBinaryLoginGrantFromWebSocket() {
        MockWebServer().use { server ->
            val serverClosed = CountDownLatch(1)
            val clientClosed = CountDownLatch(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"UserID":42,"Auth":"binary-fixture"}""".encodeUtf8())
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { serverClosed.countDown() }
            }))
            server.start()
            val received = CountDownLatch(1)
            var grant: LoginMessage? = null
            val client = OkHttpClient()
            val socket = client.newWebSocket(Request.Builder().url(server.url("/")).build(),
                object : RainSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        grant = RainProtocol.loginMessage(text)
                        received.countDown()
                        webSocket.close(1000, null)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { clientClosed.countDown() }
                })
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS))
                assertTrue(grant is LoginMessage.Grant)
                // Finish both halves of the handshake before MockWebServer shuts down its task queue.
                assertTrue(clientClosed.await(5, TimeUnit.SECONDS))
                assertTrue(serverClosed.await(5, TimeUnit.SECONDS))
            } finally {
                socket.cancel()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun loginAndDiscoveryOnlyCallExpectedEndpointsAndKeepSecretsOutOfDiagnostics() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Set-Cookie", "sessionid=fixture-cookie; Path=/; HttpOnly").setBody("OK"))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"onLessonClassrooms":[]}}"""))
            server.start()
            val origin = server.url("/")
            val records = mutableListOf<String>()
            val api = RainApi(RainCookieJar(origin), origin) { kind, fields -> records.add(kind + fields.toString()) }
            api.exchange(LoginMessage.Grant(123, "fixture-grant"))
            api.validateSession()
            assertTrue(api.classrooms().isEmpty())
            val exchange = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/pc/web_login", exchange.path)
            assertEquals("POST", exchange.method)
            assertEquals("fixture-grant", JSONObject(exchange.body.readUtf8()).getString("Auth"))
            val validation = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v3/user/basic-info", validation.path)
            assertEquals("sessionid=fixture-cookie", validation.getHeader("Cookie"))
            assertEquals("/api/v3/classroom/on-lesson-upcoming-exam", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
            assertEquals(3, server.requestCount)
            assertFalse(records.joinToString().contains("fixture-cookie"))
            assertFalse(records.joinToString().contains("fixture-grant"))
        }
    }

    @Test fun redirectsAndUnauthorizedResponsesDoNotBecomeSuccessfulSessions() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.invalid/"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            assertEquals(FailureReason.HTTP, assertThrows(RainFailure::class.java) { api.validateSession() }.reason)
            assertEquals(FailureReason.SESSION_EXPIRED, assertThrows(RainFailure::class.java) { api.validateSession() }.reason)
            assertEquals(2, server.requestCount)
        }
    }
}
