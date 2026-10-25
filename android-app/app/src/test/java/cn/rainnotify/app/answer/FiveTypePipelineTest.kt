package cn.rainnotify.app.answer

import cn.rainnotify.app.auth.RainApi
import cn.rainnotify.app.auth.RainCookieJar
import cn.rainnotify.app.monitor.ProblemTiming
import cn.rainnotify.app.question.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class FiveTypePipelineTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun allFiveTypesUseFreshCopiesModelHttpAndOneSubmission() {
        for (type in 1..5) MockWebServer().use { rain -> MockWebServer().use { model ->
            val task = "12345678-1234-1234-1234-123456789abc"
            val ref = QuestionRef("10", "20", "30")
            fun presentation(): JSONObject {
                val bullets = JSONArray().put(JSONObject().put("label", "A").put("key", "a").put("value", "one"))
                    .put(JSONObject().put("label", "B").put("key", "b").put("value", "two"))
                val problem = JSONObject().put("problemId", 30).put("problemType", type)
                    .put("body", "Synthetic pipeline test").put("result", JSONObject.NULL).put("bullets", bullets)
                    .put("pollingCount", 2).put("blanks", JSONArray().put(JSONObject()).put(JSONObject()))
                val slide = JSONObject().put("index", 1).put("problem", problem)
                return JSONObject().put("code", 0).put("data", JSONObject().put("slides", JSONArray().put(slide)))
            }
            val captured = QuestionProtocol.parse(ref, presentation())
            val expected = when(type) { 1 -> listOf("B"); 2, 3 -> listOf("A", "B"); 4 -> listOf("first", "first"); else -> listOf("A brief synthetic answer.") }
            rain.enqueue(MockResponse().setBody(presentation().toString()))
            rain.enqueue(MockResponse().setBody(presentation().toString()))
            rain.enqueue(MockResponse().setBody("{\"code\":0}"))
            val answer = JSONObject().put("task", task).put("answer", JSONArray(expected)).put("unable", false)
            model.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("finish_reason", "stop").put("message", JSONObject().put("content", answer.toString())))).toString()))
            rain.start(); model.start()
            val api = RainApi(RainCookieJar(rain.url("/")), rain.url("/"))
            val gateway = object : AnswerGateway {
                override fun current() = true
                override fun timing() = ProblemTiming("30", "7", 90_000, 0)
                override fun refresh() = api.answerableQuestion(ref).let { it.content to it.alreadyAnswered }
                override fun submit(answer: List<String>, timeoutMs: Long) = api.submitAnswer(ref, captured, answer, timeoutMs)
            }
            val journal = SubmissionJournal(temp.newFolder())
            val control = RunControl()
            val engine = AnswerEngine({ 0L }, journal)
            fun run() = engine.run(task, captured, 0, control, gateway, "a".repeat(64),
                { budget -> DeepSeekApi(model.url("/chat/completions")).solve("synthetic-not-a-real-key",
                    AnswerProtocol.prompt(captured, task), emptyList(), budget, control) }, { _, _ -> })
            val outcome = run()
            assertEquals("type $type: ${outcome.message}", AnswerPhase.SUBMITTED, outcome.phase)
            assertEquals(3, rain.requestCount); assertEquals(1, model.requestCount)
            repeat(2) { assertEquals("GET", rain.takeRequest(1, TimeUnit.SECONDS)!!.method) }
            val post = rain.takeRequest(1, TimeUnit.SECONDS)!!
            val body = JSONObject(post.body.readUtf8())
            assertEquals("POST", post.method); assertEquals(type, body.getInt("problemType"))
            if (type == 5) assertEquals(expected.single(), body.getJSONObject("result").getString("content"))
            else assertEquals(JSONArray(expected).toString(), body.getJSONArray("result").toString())
            assertEquals(AnswerPhase.FAILED, run().phase)
            assertEquals(3, rain.requestCount); assertEquals(1, model.requestCount)
        } }
    }
}
