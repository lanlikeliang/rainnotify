package cn.rainnotify.app.answer

import cn.rainnotify.app.auth.RainServer
import cn.rainnotify.app.monitor.ProblemTiming
import cn.rainnotify.app.question.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class ReferenceSubmissionTest {
    @get:Rule val temp = TemporaryFolder()
    private val taskId = "12345678-1234-1234-1234-123456789abc"
    private val questionId = "abcdefab-1234-1234-1234-123456789abc"
    private val key = "c".repeat(64)
    private val content = QuestionContent(1, "sample", listOf(QuestionOption("A", "1", "one"), QuestionOption("B", "2", "two")),
        listOf(QuestionAsset("cover", "https://x.yuketang.cn/synthetic.png")), 1, emptyList())
    private val answer = listOf("A")
    private val response = """{"task":"$taskId","answer":["A"],"unable":false}"""
    private inner class Fixture {
        val journal = SubmissionJournal(temp.newFolder())
        var now = 0L
        var current = true
        var deadline = 80_000L
        var answered: Boolean? = false
        var imageComparison = ImageComparison.MATCH
        var refreshFails = false
        var timingFails = false
        var posts = 0
        var refreshes = 0
        var timings = 0
        var imageChecks = 0
        var outcome = SubmitOutcome.ACCEPTED
        var published = emptyList<String>()
        val gateway = object : AnswerGateway {
            override fun current() = current
            override fun timing(): ProblemTiming { timings++; if (timingFails) error("timing"); return ProblemTiming("p", "m", deadline, now) }
            override fun refresh(): Pair<QuestionContent, Boolean?> { refreshes++; if (refreshFails) error("refresh"); return content to answered }
            override fun compareImages(current: QuestionContent, timeoutMs: Long): ImageComparison { imageChecks++; return imageComparison }
            override fun submit(answer: List<String>, timeoutMs: Long): SubmitOutcome { posts++; return outcome }
        }
        val engine = AnswerEngine({ now }, journal)
        fun automatic() = engine.run(taskId, content, 0, RunControl(), gateway, key,
            solve = { response }, progress = { phase, _ ->
                if (phase == AnswerPhase.SUBMITTING) assertEquals(answer, published)
            }, referenceReady = { published = it })
        fun manual() = engine.submitConfirmed(taskId, content, answer, RunControl(), gateway, key) { _, _ -> }
    }
    @Test fun allAutomaticEligibilityFailuresPreserveTheReference() {
        val mutations: List<(Fixture) -> Unit> = listOf({ it.current = false }, { it.deadline = 1_000 },
            { it.answered = null }, { it.answered = true }, { it.imageComparison = ImageComparison.DIFFERENT },
            { it.refreshFails = true }, { it.timingFails = true })
        for (change in mutations) {
            val f = Fixture(); change(f)
            val result = f.automatic()
            assertEquals(answer, result.answer); assertEquals(answer, f.published)
            assertEquals(0, f.posts); assertFalse(result.submissionAttempted)
        }
    }
    @Test fun failedImageComparisonFallsBackToTextAndStillGeneratesReference() {
        val f = Fixture().apply { imageComparison = ImageComparison.UNAVAILABLE }
        assertEquals(AnswerPhase.SUBMITTED, f.automatic().phase)
        assertEquals(1, f.posts)
    }
    @Test fun changedImageStopsAutoButUserConfirmationBypassesEveryRemoteCheck() {
        val f = Fixture().apply { imageComparison = ImageComparison.DIFFERENT }
        assertEquals(AnswerPhase.FAILED, f.automatic().phase)
        f.refreshFails = true; f.timingFails = true; f.answered = true; f.now = 100_000
        val reads = f.refreshes; val timings = f.timings; val images = f.imageChecks
        assertEquals(AnswerPhase.SUBMITTED, f.manual().phase)
        assertEquals(reads, f.refreshes); assertEquals(timings, f.timings); assertEquals(images, f.imageChecks)
        assertEquals(1, f.posts)
    }
    @Test fun manualRefusesWrongDestinationAndInvalidAnswer() {
        val f = Fixture().apply { current = false }
        assertEquals(AnswerPhase.FAILED, f.manual().phase)
        f.current = true
        assertEquals(AnswerPhase.FAILED, f.engine.submitConfirmed(taskId, content, listOf("Z"), RunControl(), f.gateway, key) { _, _ -> }.phase)
        assertEquals(0, f.posts)
    }
    @Test fun manualNeverRepeatsAnyAttemptIncludingUnknownAndRejectedReceipts() {
        for (receipt in SubmitOutcome.entries) {
            val f = Fixture().apply { outcome = receipt }
            assertTrue(f.manual().submissionAttempted)
            assertEquals(AnswerPhase.FAILED, f.manual().phase)
            assertEquals(AnswerPhase.FAILED, f.automatic().phase)
            assertEquals(1, f.posts)
        }
    }
    @Test fun allServersRequireTheirOwnAccountAndClassroomForManualSubmission() {
        val task = AnswerTask(taskId, questionId, true, AnswerPhase.FAILED, "reference", answer, sourceQuestion = content)
        for (server in RainServer.entries) {
            val record = QuestionRecord(questionId, QuestionRef("lesson", "presentation", "problem"), 1,
                content, loading = false, accountScope = "scope-${server.id}", server = server)
            assertNull(ManualSubmission.issue(record, task, server, record.accountScope, "lesson"))
            assertNotNull(ManualSubmission.issue(record, task, server, "other-account", "lesson"))
            assertNotNull(ManualSubmission.issue(record, task, server, record.accountScope, "other-lesson"))
            for (other in RainServer.entries.filter { it != server })
                assertNotNull(ManualSubmission.issue(record, task, other, record.accountScope, "lesson"))
        }
    }
    @Test fun snapshotAndAttemptStateSurviveSerializationAndLegacyTasksStillLoad() {
        val task = AnswerTask(taskId, questionId, true, AnswerPhase.CHECKING, "reference", answer, sourceQuestion = content)
        val stored = AnswerTask.fromJson(JSONObject(task.json().toString()))
        assertEquals(content.json().toString(), stored.sourceQuestion!!.json().toString())
        assertEquals(answer, stored.changed(AnswerPhase.FAILED, "closed").answer)
        assertFalse(stored.submissionAttempted)
        assertTrue(AnswerTask.fromJson(task.changed(AnswerPhase.SUBMITTING, "sending").json()).submissionAttempted)
        val legacy = task.json().apply { remove("source_question"); remove("submission_attempted") }
        assertNull(AnswerTask.fromJson(legacy).sourceQuestion)
        assertEquals(answer, AnswerTask.fromJson(legacy).answer)
    }

    @Test fun referenceImagesStayPairedWithTheAnswerAfterQuestionImagesRefresh() {
        val files = QuestionFiles(temp.newFolder())
        val record = QuestionRecord(questionId, QuestionRef("lesson", "presentation", "problem"), 1, content, loading = false)
        files.write(record)
        files.saveImage(questionId, 0, byteArrayOf(1, 2, 3))
        val original = temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        files.saveAnswerImages(questionId, listOf(original))
        files.removeImages(questionId)
        files.saveImage(questionId, 0, byteArrayOf(4, 5, 6))
        assertArrayEquals(byteArrayOf(1, 2, 3), files.answerImage(questionId, 0)!!.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), files.image(questionId, 0)!!.readBytes())
    }
    @Test fun separateJournalInstancesReserveOnlyOnceWhenRequestsRace() {
        val directory = temp.newFolder()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts = pool.invokeAll((1..12).map { number -> Callable {
                try { SubmissionJournal(directory).reserve(key, "task-$number"); true } catch (_: AnswerFailure) { false }
            } })
            assertEquals(1, attempts.count { it.get() })
        } finally { pool.shutdownNow() }
    }
}
