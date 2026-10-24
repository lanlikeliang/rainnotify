package cn.rainnotify.app.answer

import cn.rainnotify.app.monitor.ProblemTiming
import cn.rainnotify.app.question.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnswerWorkflowTest {
    @get:Rule val temp = TemporaryFolder()
    private val task = "12345678-1234-1234-1234-123456789abc"
    private val key = "a".repeat(64)
    private fun question(type: Int = 2, body: String = "sample") = QuestionContent(type, body,
        listOf(QuestionOption("A", "1", "one"), QuestionOption("B", "2", "two")), emptyList(), 1, emptyList())
    private fun reply(answer: String = "[\"B\",\"A\"]", nonce: String = task) =
        "{\"task\":\"" + nonce + "\",\"answer\":" + answer + ",\"unable\":false}"

    @Test fun finalAnswerRequiresExactTaskLabelsAndCardinality() {
        assertEquals(listOf("A", "B"), AnswerProtocol.parse(reply(), task, question()))
        for (bad in listOf(reply(nonce = "old"), reply("[]"), reply("[\"C\"]"), reply("[\"A\",\"A\"]"),
            reply("[1]"), reply() + " extra", reply().replace("false", "true"), reply().replace("false", "\"false\""))) {
            assertThrows(AnswerFailure::class.java) { AnswerProtocol.parse(bad, task, question()) }
        }
        assertThrows(AnswerFailure::class.java) { AnswerProtocol.parse(reply(), task, question(1)) }
        assertThrows(AnswerFailure::class.java) { AnswerProtocol.parse(AnswerProtocol.prompt(question(), task), task, question()) }
        assertThrows(AnswerFailure::class.java) { AnswerProtocol.labels(question(6), true) }
    }

    @Test fun journalSurvivesRestartAndBlocksEveryPreviouslyAttemptedState() {
        val root = temp.newFolder()
        SubmissionJournal(root).reserve(key, task)
        assertTrue(SubmissionJournal(root).used(key))
        assertThrows(AnswerFailure::class.java) { SubmissionJournal(root).reserve(key, "another") }
        SubmitOutcome.entries.forEach { state ->
            SubmissionJournal(root).complete(key, state)
            assertThrows(AnswerFailure::class.java) { SubmissionJournal(root).reserve(key, task) }
        }
        assertThrows(IllegalArgumentException::class.java) { SubmissionJournal(root).reserve("../bad", task) }
    }

    private inner class Fixture(val baseline: QuestionContent = question()) {
        var now = 0L
        var current = true
        var answered: Boolean? = false
        var content = baseline
        var end: Long? = 90_000
        var posts = 0
        var reads = 0
        var outcome = SubmitOutcome.ACCEPTED
        var throwOnPost = false
        val comparisons = mutableListOf<Pair<String, List<String>>>()
        val control = RunControl()
        val journal = SubmissionJournal(temp.newFolder())
        val gateway = object : AnswerGateway {
            override fun current() = current
            override fun timing() = ProblemTiming("p", "m", end, now)
            override fun refresh(): Pair<QuestionContent, Boolean?> { reads++; return content to answered }
            override fun submit(answer: List<String>, timeoutMs: Long): SubmitOutcome {
                posts++
                assertTrue(journal.used(key))
                assertEquals(listOf("A", "B"), answer)
                assertTrue(timeoutMs in 1_000..8_000)
                assertFalse(control.cancel()) // Once sent, cancellation must not claim to withdraw it.
                if (throwOnPost) error("ambiguous transport failure")
                return outcome
            }
        }
        fun run(live: Boolean = true, solve: (Long) -> String = { reply() }) =
            AnswerEngine({ now }, journal).run(task, baseline, 0, control,
                if (live) gateway else null, if (live) key else null, solve, { _, _ -> },
                { stage, fields, _ -> comparisons.add(stage to fields) })
    }

    @Test fun rehearsalCannotReachAnyRainEndpointOrJournalReservation() {
        val f = Fixture()
        assertEquals(AnswerPhase.REHEARSED, f.run(live = false).phase)
        assertEquals(0, f.reads); assertEquals(0, f.posts); assertFalse(f.journal.used(key))
    }
    @Test fun successfulLiveFlowRefreshesAfterSolveAndSendsOnce() {
        val f = Fixture()
        assertEquals(AnswerPhase.SUBMITTED, f.run { budget -> assertEquals(60_000L, budget); f.now += 20_000; reply() }.phase)
        assertEquals(2, f.reads); assertEquals(1, f.posts)
        assertEquals(AnswerPhase.FAILED, f.run().phase)
        assertEquals(1, f.posts)
    }
    private fun signedQuestion(grant: String, path: String = "slide.png", options: List<QuestionOption> = question().options) =
        QuestionContent(2, "sample", options,
            listOf(QuestionAsset("cover", "https://rain-pri-ups.yuketang.cn/$path?auth_key=$grant")), 1, emptyList())

    @Test fun rotatingImageGrantBeforeAiAndBeforeSubmitStillSubmitsExactlyOnce() {
        val f = Fixture(signedQuestion("captured")).apply { content = signedQuestion("before-ai") }
        assertEquals(AnswerPhase.SUBMITTED, f.run {
            f.now += 20_000; f.content = signedQuestion("before-submit"); reply()
        }.phase)
        assertEquals(listOf("before_solve" to emptyList<String>(), "before_submit" to emptyList<String>()), f.comparisons)
        assertEquals(2, f.reads); assertEquals(1, f.posts)
        assertEquals(AnswerPhase.FAILED, f.run().phase)
        assertEquals(1, f.posts)
    }

    @Test fun changedImageOrReorderedOptionsStillBlocksBothValidationStages() {
        for (afterSolve in listOf(false, true)) for (changed in listOf(
            signedQuestion("new", path = "replacement.png"),
            signedQuestion("new", options = question().options.reversed()))) {
            val f = Fixture(signedQuestion("old"))
            var solved = false
            if (!afterSolve) f.content = changed
            assertEquals(AnswerPhase.FAILED, f.run {
                solved = true; f.content = changed; reply()
            }.phase)
            assertEquals(afterSolve, solved)
            assertEquals(0, f.posts)
            assertFalse(f.journal.used(key))
            assertFalse(f.comparisons.last().second.isEmpty())
        }
    }
    @Test fun cancelledDisconnectedChangedExpiredOrAnsweredDuringSolveNeverSubmit() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.control.cancel() }, { it.current = false }, { it.content = question(body = "changed") },
            { it.now = 86_000 }, { it.end = 2_000 }, { it.answered = true }, { it.answered = null })
        changes.forEach { change ->
            val f = Fixture()
            assertTrue(f.run { change(f); reply() }.phase in setOf(AnswerPhase.CANCELLED, AnswerPhase.FAILED, AnswerPhase.ALREADY_ANSWERED))
            assertEquals(0, f.posts)
        }
    }
    @Test fun unknownReceiptKeepsReservationAndNeverAutomaticallyRetries() {
        val f = Fixture().apply { throwOnPost = true }
        assertEquals(AnswerPhase.UNCERTAIN, f.run().phase)
        assertEquals(AnswerPhase.FAILED, f.run().phase)
        assertEquals(1, f.posts)
    }
    @Test fun alreadyAnsweredOrUnknownPresenceStopsBeforeSendingAnythingToAi() {
        for (flag in listOf(true, null)) {
            val f = Fixture().apply { answered = flag }
            var solved = false
            f.run { solved = true; reply() }
            assertFalse(solved); assertEquals(0, f.posts)
        }
    }
    @Test fun unsupportedAnswerAndLowTimeNeverSubmit() {
        val f = Fixture()
        assertEquals(AnswerPhase.FAILED, f.run { reply("[\"Z\"]") }.phase)
        assertEquals(0, f.posts)
        f.end = 10_000
        var solved = false
        assertEquals(AnswerPhase.FAILED, f.run { solved = true; reply() }.phase)
        assertFalse(solved)
    }
    @Test fun timingUsesServerElapsedAndDeductsRoundTripInsteadOfPhoneWallClock() {
        val start = 1_700_000_000_000L
        val json = JSONObject().put("problemid", "p").put("msgid", 7).put("limit", 90)
            .put("now", start + 20_000).put("dt", start)
        val time = ProblemTiming.parse(json, 100, 1_100)
        assertEquals(70_100L, time.deadline)
        assertEquals(68_000L, time.remaining(2_100))
        assertEquals(0L, time.remaining(100_000))
        assertNull(ProblemTiming.parse(JSONObject().put("problemid", "p").put("msgid", 7).put("limit", -1), 1, 2).deadline)
        json.remove("msgid")
        assertThrows(AnswerFailure::class.java) { ProblemTiming.parse(json, 1, 2) }
        json.put("msgid", 7).put("dt", start + 30_000)
        assertThrows(AnswerFailure::class.java) { ProblemTiming.parse(json, 1, 2) }
    }
}
