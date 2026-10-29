package cn.rainnotify.app.answer

import cn.rainnotify.app.question.*
import cn.rainnotify.app.auth.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class FiveQuestionTypesTest {
    // Test local HTTP semantics, not runner speed or localhost DNS/IPv6 fallback.
    // Use a generous transport timeout; production deadlines are tested separately.
    @get:Rule val temporary = TemporaryFolder()
    private val ref = QuestionRef("10", "20", "30")
    private fun parse(type: Int, rules: String = "") = QuestionProtocol.parse(ref, JSONObject("""{
        "code":0,"data":{"slides":[{"problem":{"problemId":30,"problemType":$type,"body":"题目",
        "options":[{"key":"A","value":"甲"},{"key":"B","value":"乙"},{"key":"C","value":"丙"}]$rules}}]}}"""))
    private fun reply(vararg values: String) = JSONObject().put("task", "task")
        .put("answer", JSONArray(values.toList())).put("unable", false).toString()

    @Test fun fiveTypesSerializeExactlyAsOfficialStudentPages() {
        val samples = listOf(
            Triple(parse(1), listOf("B"), "[\"B\"]"),
            Triple(parse(2), listOf("B", "A"), "[\"A\",\"B\"]"),
            Triple(parse(3, ",\"pollingCount\":2"), listOf("C", "A"), "[\"A\",\"C\"]"),
            Triple(parse(4, ",\"blanks\":[{}, {}, {}]"), listOf("乙", "甲", "乙"), "[\"乙\",\"甲\",\"乙\"]"),
            Triple(parse(5), listOf("重视实践，结合史料分析。"), null)
        )
        samples.forEach { (question, answer, expected) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{\"code\":0}")); server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val parsed = AnswerProtocol.parse(reply(*answer.toTypedArray()), "task", question)
                assertEquals(SubmitOutcome.ACCEPTED, RainApi(RainCookieJar(server.url("/")), server.url("/"))
                    .submitAnswer(ref, question, parsed, 8_000))
                val body = JSONObject(requireNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)).body.readUtf8())
                assertEquals(question.type, body.getInt("problemType"))
                if (expected != null) assertEquals(expected, body.getJSONArray("result").toString())
                else {
                    assertEquals(answer.single(), body.getJSONObject("result").getString("content"))
                    assertEquals("", body.getJSONObject("result").getJSONArray("pics").getJSONObject(0).getString("pic"))
                }
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun blankCountIsPreservedWithoutLeakingTeacherAnswers() {
        val q = parse(4, ",\"blanks\":[{\"answer\":\"secret\"},{\"answer\":\"hidden\"}]")
        val saved = q.json().toString()
        assertFalse(saved.contains("secret")); assertFalse(saved.contains("hidden"))
        assertEquals(2, QuestionContent.fromJson(JSONObject(saved)).blankCount)
        assertThrows(AnswerFailure::class.java) { AnswerProtocol.parse(reply("一"), "task", q) }
        assertEquals(listOf("同", "同"), AnswerProtocol.parse(reply("同", "同"), "task", q))
    }
    @Test fun voteCannotExceedDeclaredCountOrUseDuplicateOptions() {
        val q = parse(3, ",\"pollingCount\":1")
        assertThrows(AnswerFailure::class.java) { AnswerProtocol.parse(reply("A", "B"), "task", q) }
        assertThrows(AnswerFailure::class.java) { AnswerProtocol.parse(reply("A", "A"), "task", q) }
        assertThrows(AnswerFailure::class.java) { AnswerProtocol.validateQuestion(parse(3), true) }
        assertThrows(AnswerFailure::class.java) { AnswerProtocol.validateQuestion(parse(4), true) }
    }
    @Test fun subjectiveIsConciseAndCannotSilentlyBeTruncated() {
        val q = parse(5)
        assertEquals(listOf("重点。"), AnswerProtocol.parse(reply(" 重点。 "), "task", q))
        for (answer in listOf(reply(""), reply("一", "二"), reply("文".repeat(301))))
            assertThrows(AnswerFailure::class.java) { AnswerProtocol.parse(answer, "task", q) }
        assertTrue(AnswerProtocol.prompt(q, "12345678-1234-1234-1234-123456789abc").contains("最多 300"))
    }
    @Test fun changingAnswerRulesInvalidatesSnapshot() {
        assertTrue(QuestionIdentity.differences(parse(3, ",\"pollingCount\":1"), parse(3, ",\"pollingCount\":2")).contains("answer_rules"))
        assertTrue(QuestionIdentity.differences(parse(4, ",\"blanks\":[{}]"), parse(4, ",\"blanks\":[{},{}]")).contains("answer_rules"))
    }
    @Test fun groupSubmissionIsNeverMistakenForPersonalSubmission() {
        val group = QuestionRef("10", "20", "30", groupWork = true)
        assertTrue(QuestionRef.fromJson(group.json()).groupWork)
        MockWebServer().use { server -> server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            assertThrows(AnswerFailure::class.java) {
                RainApi(RainCookieJar(server.url("/")), server.url("/")).submitAnswer(group, parse(5), listOf("文字"), 8_000)
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun exportedFillVoteAndSubjectiveCanBeImportedAndRehearsedWithoutLogin() {
        val original = QuestionFiles(temporary.newFolder())
        val questions = listOf(parse(3, ",\"pollingCount\":2"),
            QuestionContent(4, "填两个空", emptyList(), emptyList(), null, emptyList(), blankCount = 2),
            QuestionContent(5, "简述", emptyList(), emptyList(), null, emptyList()))
        questions.forEachIndexed { i, q -> original.write(QuestionRecord(java.util.UUID.randomUUID().toString(), ref,
            i.toLong(), q, "完成", false)) }
        val output = ByteArrayOutputStream()
        original.export(output, QuestionExportInfo("test", 36, "test", "test"))
        val restored = QuestionFiles(temporary.newFolder())
        assertEquals(3, QuestionImport.read(output.toByteArray().inputStream(), temporary.newFolder(), restored))
        val records = restored.records()
        assertTrue(records.all { it.offline })
        assertEquals(2, records.single { it.content?.type == 3 }.content!!.pollingCount)
        assertEquals(2, records.single { it.content?.type == 4 }.content!!.blankCount)
        records.forEach { AnswerProtocol.validateQuestion(it.content!!, false) }
    }
}
