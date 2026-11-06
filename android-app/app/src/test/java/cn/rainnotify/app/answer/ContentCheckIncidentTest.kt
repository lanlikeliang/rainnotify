package cn.rainnotify.app.answer

import cn.rainnotify.app.monitor.LessonEvents
import cn.rainnotify.app.monitor.LessonSignal
import cn.rainnotify.app.monitor.ProblemTiming
import cn.rainnotify.app.question.QuestionContent
import cn.rainnotify.app.question.QuestionProtocol
import cn.rainnotify.app.question.QuestionRef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic reproduction of the 2026-11-06 v0.1.3 diagnostic path.
 * These hosts/parameters are hypotheses, NOT URLs extracted from the user's classroom.
 * No real network, account, AI call or classroom submission occurs.
 */
class ContentCheckIncidentTest {
    @get:Rule val temp = TemporaryFolder()
    private val task = "12345678-1234-1234-1234-123456789abc"
    private val key = "b".repeat(64)
    private val ref = QuestionRef("lesson", "presentation", "problem")
    private val known = "https://rain-pri-ups.yuketang.cn/synthetic.png"
    private val other = "https://synthetic-content.yuketang.cn/synthetic.png"
    private fun response(type: Int, url: String, limit: Int = 60, now: Long = 1_800_000_000_000): JSONObject {
        val problem = JSONObject().put("problemId", "problem").put("problemType", type)
            .put("body", "Synthetic unchanged question").put("result", JSONObject.NULL)
            .put("limit", limit).put("dt", 1_800_000_000_000).put("now", now)
        if (type != 5) problem.put("bullets", JSONArray().put(JSONObject().put("key", "A").put("value", "one"))
            .put(JSONObject().put("key", "B").put("value", "two")))
        return JSONObject().put("code", 0).put("data", JSONObject().put("slides", JSONArray()
            .put(JSONObject().put("id", "slide").put("index", 1).put("cover", url).put("problem", problem))))
    }
    private fun parse(type: Int, url: String) = QuestionProtocol.parse(ref, response(type, url))
    private inner class Fixture(val baseline: QuestionContent) {
        var refreshed = baseline
        var now = 0L
        var deadline = 60_000L
        var solves = 0
        var reads = 0
        var timingReads = 0
        var posts = 0
        val phases = mutableListOf<AnswerPhase>()
        val comparisons = mutableListOf<Triple<String, List<String>, Boolean>>()
        val journal = SubmissionJournal(temp.newFolder())
        val gateway = object : AnswerGateway {
            override fun current() = true
            override fun refresh(): Pair<QuestionContent, Boolean?> { reads++; return refreshed to false }
            override fun timing(): ProblemTiming { timingReads++; return ProblemTiming("problem", "request", deadline, now) }
            override fun submit(answer: List<String>, timeoutMs: Long): SubmitOutcome { posts++; return SubmitOutcome.ACCEPTED }
        }
        fun run(duringSolve: () -> Unit = {}): AnswerResult = AnswerEngine({ now }, journal).run(
            task, baseline, 0, RunControl(), gateway, key,
            solve = {
                solves++; duringSolve()
                JSONObject().put("task", task).put("unable", false)
                    .put("answer", JSONArray().put(if (baseline.type == 5) "Synthetic explanation" else "A")).toString()
            }, progress = { phase, _ -> phases.add(phase) },
            comparison = { stage, fields, transport -> comparisons.add(Triple(stage, fields, transport)) })
    }

    private fun assertReferenceAndSubmit(f: Fixture) {
        val result = f.run()
        assertEquals(AnswerPhase.SUBMITTED, result.phase)
        assertEquals(listOf(Triple("before_submit", emptyList<String>(), true)), f.comparisons)
        assertEquals(1, f.solves); assertEquals(1, f.posts); assertEquals(1, f.timingReads)
        assertEquals(1, f.reads); assertTrue(result.answer.isNotEmpty())
        assertTrue(f.phases.contains(AnswerPhase.SOLVING)); assertTrue(f.journal.used(key))
    }
    @Test fun arbitraryHostGrantRotationNowGeneratesReferencesForAllThreeObservedTypes() {
        for (type in listOf(1, 2, 5)) {
            val baseline = QuestionContent.fromJson(JSONObject(parse(type, "$other?auth_key=old").json().toString()))
            assertReferenceAndSubmit(Fixture(baseline).apply { refreshed = parse(type, "$other?auth_key=new") })
        }
    }
    @Test fun unrecognizedQueryOnKnownHostDoesNotBlockGenerationOrTextFallback() {
        assertReferenceAndSubmit(Fixture(parse(1, "$known?auth_key=old&token=old")).apply {
            refreshed = parse(1, "$known?auth_key=new&token=new")
        })
    }
    @Test fun changingKnownSignatureDuringSolveStillSubmitsOnce() {
        val f = Fixture(parse(1, "$known?auth_key=old"))
        val result = f.run { f.refreshed = parse(1, "$known?auth_key=new") }
        assertEquals(AnswerPhase.SUBMITTED, result.phase)
        assertEquals(1, f.solves); assertEquals(1, f.posts)
        assertEquals(listOf("before_submit"), f.comparisons.map { it.first })
    }
    @Test fun changedTextAfterAiPreservesReferenceAndStopsOnlyAutomaticSubmission() {
        val f = Fixture(parse(1, "$other?auth_key=old"))
        val result = f.run {
            val changed = response(1, "$other?auth_key=new")
            changed.getJSONObject("data").getJSONArray("slides").getJSONObject(0).getJSONObject("problem").put("body", "changed text")
            f.refreshed = QuestionProtocol.parse(ref, changed)
        }
        assertEquals(AnswerPhase.FAILED, result.phase)
        assertEquals(1, f.solves); assertEquals(0, f.posts)
        assertEquals(listOf("A"), result.answer)
        assertEquals(Triple("before_submit", listOf("body"), true), f.comparisons.last())
    }
    @Test fun teacherExtensionChangesTimingButDoesNotProduceContentMismatch() {
        val original = QuestionProtocol.parse(ref, response(1, "$known?auth_key=old", 60))
        val delayed = QuestionProtocol.parse(ref, response(1, "$known?auth_key=new", 120, 1_800_000_020_000))
        val events = LessonEvents("lesson")
        assertEquals(LessonSignal.Ready, events.receive("""{"op":"hello","presentation":"presentation","timeline":[]}"""))
        assertTrue(events.receive("""{"op":"extendtime","problem":{"prob":"problem","limit":120}}""") is LessonSignal.TimeChanged)
        val f = Fixture(original)
        val result = f.run { f.now = 20_000; f.deadline = 120_000; f.refreshed = delayed }
        assertEquals(AnswerPhase.SUBMITTED, result.phase)
        assertEquals(1, f.solves); assertEquals(1, f.posts)
        assertTrue(f.comparisons.all { it.second.isEmpty() })
    }
    @Test fun changedCoverPathUsesContentOrAuthorizedTextFallback() {
        assertReferenceAndSubmit(Fixture(parse(1, "$known?auth_key=old")).apply {
            refreshed = parse(1, "https://rain-pri-ups.yuketang.cn/replacement.png?auth_key=new")
        })
    }
}
