package cn.rainnotify.app.auth

import cn.rainnotify.app.answer.*
import cn.rainnotify.app.question.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.json.JSONArray
import cn.rainnotify.app.monitor.ProblemTiming
import java.util.concurrent.TimeUnit

class AnswerApiTest {
    @get:Rule val temp = TemporaryFolder()
    private val ref = QuestionRef("10", "20", "30")
    private val question = QuestionContent(2, "private-body", listOf(QuestionOption("A", "1", "one"),
        QuestionOption("B", "2", "two")), emptyList(), null, emptyList())
    @Test fun submissionUsesSortedLabelsAndOfficialFieldsOnceWithoutLoggingAnswer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"code\":0}")); server.start()
            val logs = mutableListOf<String>()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/")) { kind, fields -> logs.add(kind + fields) }
            assertEquals(SubmitOutcome.ACCEPTED, api.submitAnswer(ref, question, listOf("B", "A"), 8_000))
            val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", sent.method)
            assertEquals("/api/v3/lesson/problem/answer", sent.path)
            val body = JSONObject(sent.body.readUtf8())
            assertEquals(setOf("problemId", "problemType", "dt", "result"), body.keys().asSequence().toSet())
            assertEquals("30", body.getString("problemId")); assertEquals(2, body.getInt("problemType"))
            assertEquals("[\"A\",\"B\"]", body.getJSONArray("result").toString())
            assertFalse(logs.joinToString().contains("private-body"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun ambiguousPostFailureDoesNotRetry() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            assertEquals(SubmitOutcome.UNKNOWN, api.submitAnswer(ref, question, listOf("A"), 2_000))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun receiptClassificationDoesNotEquateAlreadyAnsweredToAccepted() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"code\":50028}"))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"code\":50001}"))
            server.enqueue(MockResponse().setResponseCode(503))
            server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            for (expected in listOf(SubmitOutcome.ALREADY_ANSWERED, SubmitOutcome.UNKNOWN, SubmitOutcome.REJECTED, SubmitOutcome.UNKNOWN))
                assertEquals(expected, api.submitAnswer(ref, question, listOf("A"), 2_000))
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun answeredStateDistinguishesMissingNullAndExistingResultWithoutReturningItsValue() {
        MockWebServer().use { server ->
            for (suffix in listOf("", ",\"result\":null", ",\"result\":[]", ",\"result\":[\"B\"]"))
                server.enqueue(MockResponse().setBody("{\"code\":0,\"data\":{\"slides\":[{\"problem\":{\"problemId\":30,\"problemType\":1,\"body\":\"x\"" + suffix + "}}]}}"))
            server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            for (expected in listOf(null, false, true, true)) {
                val fetched = api.answerableQuestion(ref)
                assertEquals(expected, fetched.alreadyAnswered)
                assertFalse(fetched.content.json().toString().contains("result"))
            }
        }
    }
    @Test fun invalidOptionRejectedBeforeNetwork() {
        MockWebServer().use { server ->
            server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            assertThrows(AnswerFailure::class.java) { api.submitAnswer(ref, question, listOf("C"), 2_000) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun imageOnlyQuestionWithRotatingCoverGrantsReachesSingleSubmissionThroughRealParserAndApi() {
        for (type in listOf(1, 2)) MockWebServer().use { server ->
            fun presentation(grant: String): JSONObject {
                val problem = JSONObject().put("problemId", 30).put("problemType", type).put("body", "")
                    .put("result", JSONObject.NULL).put("bullets", JSONArray()
                        .put(JSONObject().put("label", "A").put("key", "1"))
                        .put(JSONObject().put("label", "B").put("key", "2")))
                return JSONObject().put("code", 0).put("data", JSONObject().put("slides", JSONArray().put(
                    JSONObject().put("index", 42).put("cover", "https://rain-pri-ups.yuketang.cn/course/q.png?auth_key=$grant")
                        .put("problem", problem))))
            }
            val captured = QuestionProtocol.parse(ref, presentation("captured"))
            server.enqueue(MockResponse().setBody(presentation("before-ai").toString()))
            server.enqueue(MockResponse().setBody(presentation("before-submit").toString()))
            server.enqueue(MockResponse().setBody("{\"code\":0}"))
            server.start()
            val api = RainApi(RainCookieJar(server.url("/")), server.url("/"))
            val gateway = object : AnswerGateway {
                override fun current() = true
                override fun timing() = ProblemTiming("30", "7", 90_000, 0)
                override fun refresh() = api.answerableQuestion(ref).let { it.content to it.alreadyAnswered }
                override fun submit(answer: List<String>, timeoutMs: Long) = api.submitAnswer(ref, captured, answer, timeoutMs)
            }
            val labels = if (type == 1) listOf("B") else listOf("A", "B")
            val result = AnswerEngine({ 0L }, SubmissionJournal(temp.newFolder())).run("nonce", captured, 0,
                RunControl(), gateway, "b".repeat(64),
                { JSONObject().put("task", "nonce").put("answer", JSONArray(labels)).put("unable", false).toString() }, { _, _ -> })
            assertEquals(AnswerPhase.SUBMITTED, result.phase)
            assertEquals(3, server.requestCount)
            repeat(2) {
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("/api/v3/lesson/presentation/fetch?presentation_id=20", request.path)
            }
            val submitted = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", submitted.method)
            assertEquals("/api/v3/lesson/problem/answer", submitted.path)
            assertEquals(JSONArray(labels).toString(), JSONObject(submitted.body.readUtf8()).getJSONArray("result").toString())
        }
    }
}
