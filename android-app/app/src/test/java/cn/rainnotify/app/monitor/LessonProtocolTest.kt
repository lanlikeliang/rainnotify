package cn.rainnotify.app.monitor

import cn.rainnotify.app.auth.FailureReason
import cn.rainnotify.app.auth.RainFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LessonProtocolTest {
    @Test fun liveQuestionPreservesExactContentLocatorWithoutNewNetworkActions() {
        val events = LessonEvents("10")
        events.receive("""{"op":"hello","timeline":[],"presentation":"20"}""")
        val signal = events.receive("""{"op":"unlockproblem","problem":{"prob":"30","sid":40,"si":2}}""")
            as LessonSignal.Question
        assertEquals("10", signal.reference.lessonId)
        assertEquals("20", signal.reference.presentationId)
        assertEquals("30", signal.reference.problemId)
        assertEquals("40", signal.reference.slideId)
        assertEquals(2, signal.reference.slideIndex)
    }
    private val hello = """{"op":"hello","timeline":[],"presentation":20}"""
    private val question = """{"op":"unlockproblem","problem":{"pres":20,"si":0,"prob":30}}"""

    @Test fun separatesLiveQuestionsFromHistoryExtensionsAndRepeats() {
        val events = LessonEvents("10")
        assertEquals(LessonSignal.Ready, events.receive(hello))
        assertEquals(LessonSignal.Extension, events.receive("""{"op":"extendtime","problem":{"pres":20,"prob":30}}"""))
        assertTrue(events.receive(question) is LessonSignal.Question)
        assertEquals(LessonSignal.Duplicate, events.receive(question))
        assertEquals(LessonSignal.Snapshot, events.receive(
            """{"op":"fetchtimeline","timeline":[{"op":"unlockproblem","problem":{"pres":20,"prob":31}}]}"""))
        assertEquals(LessonSignal.Duplicate, events.receive("""{"op":"unlockproblem","problem":{"prob":31}}"""))
        assertEquals(LessonSignal.Finished, events.receive("""{"op":"lessonfinished"}"""))
    }

    @Test fun deduplicationSurvivesReconnectAndAllowsAnotherPresentation() {
        val events = LessonEvents("10")
        events.receive(hello)
        events.receive(question)
        events.disconnected()
        assertEquals(LessonSignal.Unsupported, events.receive(question))
        events.receive(hello)
        assertEquals(LessonSignal.Duplicate, events.receive(question))
        assertTrue(events.receive("""{"op":"unlockproblem","problem":{"pres":21,"prob":30}}""") is LessonSignal.Question)
    }

    @Test fun missingFieldsAndRejectedHelloCannotLookLikeWorkingListener() {
        val events = LessonEvents("10")
        assertEquals(LessonSignal.Unsupported, events.receive("""{"op":"hello"}"""))
        assertEquals(LessonSignal.Unsupported, events.receive(question))
        assertThrows(RainFailure::class.java) { events.receive("""{"op":"hello","code":1,"timeline":[]}""") }
        events.receive(hello)
        assertEquals(LessonSignal.Unsupported, events.receive("""{"op":"unlockproblem","problem":{}}"""))
        assertThrows(RainFailure::class.java) { events.receive("not json") }
    }

    @Test fun restoresPresentationFromSnapshotWithoutEmittingHistoricalQuestions() {
        val events = LessonEvents("10")
        assertEquals(LessonSignal.Ready, events.receive(
            """{"op":"hello","timeline":[{"pres":20,"si":0},{"op":"unlockproblem","problem":{"prob":30}}]}"""))
        assertEquals(LessonSignal.Duplicate, events.receive(question))
        assertTrue(events.receive("""{"op":"unlockproblem","problem":{"prob":32}}""") is LessonSignal.Question)
    }

    @Test fun credentialsRequireSuccessAndStayRedacted() {
        val credentials = LessonProtocol.credentials(JSONObject(
            """{"code":0,"msg":"OK","data":{"identityId":42,"lessonToken":"secret-fixture"}}"""), "10")
        val greeting = JSONObject(LessonProtocol.hello(credentials))
        assertEquals("student", greeting.getString("role"))
        assertEquals(42, greeting.getInt("userid"))
        assertEquals("secret-fixture", greeting.getString("auth"))
        assertEquals("10", greeting.get("lessonid"))
        assertFalse(credentials.toString().contains("secret-fixture"))
        assertEquals(FailureReason.LESSON_ENDED, assertThrows(RainFailure::class.java) {
            LessonProtocol.credentials(JSONObject("""{"msg":"LESSON_END"}"""), "10")
        }.reason)
        assertThrows(RainFailure::class.java) {
            LessonProtocol.credentials(JSONObject("""{"msg":"OK","data":{"identityId":42,"lessonToken":""}}"""), "10")
        }
        assertThrows(RainFailure::class.java) { LessonProtocol.checkinBody("../other") }
    }
}
