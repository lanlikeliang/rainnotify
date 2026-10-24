package cn.rainnotify.app.monitor

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okio.ByteString.Companion.encodeUtf8

class LessonConnectionTest {
    private val credentials = LessonCredentials("10", 42, "fixture-token")
    private val hello = """{"op":"hello","timeline":[],"presentation":20}"""
    private val question = """{"op":"unlockproblem","problem":{"pres":20,"prob":30}}"""
    private class Observer : LessonObserver {
        val ready = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val question = CountDownLatch(1)
        val duplicate = CountDownLatch(1)
        val count = AtomicInteger()
        override fun state(state: ConnectionState) {
            if (state == ConnectionState.LISTENING) ready.countDown()
            if (state == ConnectionState.ENDED) ended.countDown()
            if (state == ConnectionState.FAILED) failed.countDown()
        }
        override fun signal(signal: LessonSignal) {
            if (signal is LessonSignal.Question) { count.incrementAndGet(); question.countDown() }
            if (signal == LessonSignal.Duplicate) duplicate.countDown()
        }
        override fun diagnostic(kind: String, fields: Map<String, Any>) {}
    }

    @Test fun reconnectReusesCredentialsAndDoesNotRepeatQuestionOrCheckin() {
        MockWebServer().use { server ->
            val greetings = AtomicInteger()
            fun socket(closeAfterQuestion: Boolean) = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val greeting = JSONObject(text)
                    if (greeting.optString("op") == "hello" && greeting.optString("auth") == "fixture-token") {
                        greetings.incrementAndGet()
                        webSocket.send(hello.encodeUtf8())
                        webSocket.send(question.encodeUtf8())
                        if (closeAfterQuestion) webSocket.close(1000, null)
                        else webSocket.send("""{"op":"lessonfinished"}""")
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }
            server.enqueue(MockResponse().withWebSocketUpgrade(socket(true)))
            server.enqueue(MockResponse().withWebSocketUpgrade(socket(false)))
            server.start()
            val client = OkHttpClient()
            val observer = Observer()
            val connection = LessonConnection(client, credentials, observer, server.url("/wsapp/").toString(), 10, 1000)
            try {
                connection.start()
                assertTrue(observer.ended.await(5, TimeUnit.SECONDS))
                assertTrue(observer.duplicate.await(1, TimeUnit.SECONDS))
                assertEquals(1, observer.count.get())
                assertEquals(2, greetings.get())
                assertEquals(2, server.requestCount)
                repeat(2) {
                    val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("GET", request.method)
                    assertEquals("/wsapp/", request.path)
                }
            } finally { connection.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun stopRejectsLaterSocketEventsAndNetworkRestart() {
        MockWebServer().use { server ->
            val transport = arrayOfNulls<WebSocket>(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    transport[0] = webSocket
                    webSocket.send(hello)
                }
            }))
            server.start()
            val client = OkHttpClient()
            val observer = Observer()
            val connection = LessonConnection(client, credentials, observer, server.url("/wsapp/").toString(), 10, 1000)
            try {
                connection.start()
                assertTrue(observer.ready.await(3, TimeUnit.SECONDS))
                connection.close()
                transport[0]!!.send(question)
                connection.networkAvailable(true, true)
                connection.start()
                assertFalse(observer.question.await(250, TimeUnit.MILLISECONDS))
                assertEquals(1, server.requestCount)
                assertEquals(0, observer.count.get())
            } finally { connection.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun protocolRejectionStopsWithoutRetryOrFalseReady() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"op":"hello","code":1,"timeline":[]}""")
                }
            }))
            server.start()
            val client = OkHttpClient()
            val observer = Observer()
            val connection = LessonConnection(client, credentials, observer, server.url("/wsapp/").toString(), 10, 1000)
            try {
                connection.start()
                assertTrue(observer.failed.await(3, TimeUnit.SECONDS))
                assertEquals(1L, observer.ready.count)
                assertFalse(observer.question.await(150, TimeUnit.MILLISECONDS))
                assertEquals(1, server.requestCount)
            } finally { connection.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun timingIgnoresUncorrelatedRepliesAndClosedQuestionCannotSubmit() {
        MockWebServer().use { server ->
            val transport = arrayOfNulls<WebSocket>(1)
            val sentWrong = CountDownLatch(1)
            val allowCorrect = CountDownLatch(1)
            val closed = CountDownLatch(1)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    transport[0] = ws
                    val request = JSONObject(text)
                    if (request.optString("op") == "hello") ws.send(hello)
                    else if (request.optString("op") == "probleminfo") {
                        val response = JSONObject().put("op", "probleminfo").put("problemid", "30")
                            .put("msgid", "old").put("limit", -1)
                        ws.send(response.toString())
                        response.remove("msgid"); ws.send(response.toString())
                        sentWrong.countDown()
                        allowCorrect.await(2, TimeUnit.SECONDS)
                        response.put("msgid", request.get("msgid")); ws.send(response.toString())
                    }
                }
            }))
            server.start()
            val client = OkHttpClient()
            val ready = CountDownLatch(1)
            val observer = object : LessonObserver {
                override fun state(state: ConnectionState) { if (state == ConnectionState.LISTENING) ready.countDown() }
                override fun diagnostic(kind: String, fields: Map<String, Any>) {}
                override fun signal(signal: LessonSignal) { if (signal is LessonSignal.ProblemClosed) closed.countDown() }
            }
            val connection = LessonConnection(client, credentials, observer, server.url("/wsapp/").toString())
            try {
                connection.start(); assertTrue(ready.await(3, TimeUnit.SECONDS))
                val timing = connection.timing("30")
                assertTrue(sentWrong.await(2, TimeUnit.SECONDS)); assertFalse(timing.isDone)
                allowCorrect.countDown()
                assertNull(timing.get(3, TimeUnit.SECONDS).deadline)
                transport[0]!!.send("{\"op\":\"problemfinished\",\"prob\":30}")
                assertTrue(closed.await(2, TimeUnit.SECONDS)); assertFalse(connection.usable("30"))
                assertTrue(connection.timing("30").isCompletedExceptionally)
            } finally {
                allowCorrect.countDown(); connection.close()
                client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            }
        }
    }
}
