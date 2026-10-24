package cn.rainnotify.app.monitor

import org.junit.Assert.*
import org.junit.Test

class ClassroomScanReturnTest {
    @Test fun returnIsOneShotAndRequiresAnExplicitDeparture() {
        var now = 100L
        val gate = ClassroomScanReturn { now }
        assertNull(gate.consume("lesson", true))
        gate.arm("lesson")
        assertNull(gate.consume("lesson", true))
        gate.leftApp()
        now += 20_000
        assertEquals("lesson", gate.consume("lesson", true))
        gate.leftApp()
        assertNull(gate.consume("lesson", true))
    }

    @Test fun switchingLessonOrLosingReadinessConsumesTheAttemptWithoutEntering() {
        for ((lesson, available) in listOf("other" to true, null to true, "lesson" to false)) {
            val gate = ClassroomScanReturn { 1_000L }
            gate.arm("lesson"); gate.leftApp()
            assertNull(gate.consume(lesson, available))
            // A later lifecycle callback cannot silently retry the discarded attempt.
            assertNull(gate.consume("lesson", true))
        }
    }

    @Test fun staleOrInvalidClockCannotResumeAnOldEntryRequest() {
        for (elapsed in listOf(-1L, 300_001L)) {
            var now = 100L
            val gate = ClassroomScanReturn { now }
            gate.arm("lesson"); gate.leftApp()
            now += elapsed
            assertNull(gate.consume("lesson", true))
        }
    }

    @Test fun cancellationOrActivityRecreationNeverRestoresAnEntryRequest() {
        val gate = ClassroomScanReturn { 1_000L }
        gate.arm("lesson"); gate.leftApp(); gate.cancel()
        assertNull(gate.consume("lesson", true))
        val recreated = ClassroomScanReturn { 2_000L }
        recreated.leftApp()
        assertNull(recreated.consume("lesson", true))
    }

    @Test fun newActionReplacesOldActionAndRequiresAnotherDeparture() {
        val gate = ClassroomScanReturn { 1_000L }
        gate.arm("first"); gate.leftApp(); gate.arm("second")
        assertNull(gate.consume("second", true))
        gate.leftApp()
        assertEquals("second", gate.consume("second", true))
        assertThrows(IllegalArgumentException::class.java) { gate.arm("../invalid") }
    }
}
