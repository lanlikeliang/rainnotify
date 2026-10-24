package cn.rainnotify.app.answer

import org.junit.Assert.*
import org.junit.Test

class CompatibilityPolicyTest {
    @Test fun needsBothProvenImagePathsForThisInstallation() {
        assertFalse(CompatibilityPolicy.ready("new-build", "old-build", setOf(1, 2)))
        assertFalse(CompatibilityPolicy.ready("new-build", null, setOf(1, 2)))
        assertFalse(CompatibilityPolicy.ready("build", "build", setOf(1)))
        assertTrue(CompatibilityPolicy.ready("build", "build", setOf(1, 2)))
    }
    @Test fun wrongImageAnswerOrInterruptedRunCannotBecomeProof() {
        assertFalse(CompatibilityPolicy.passes(1, AnswerPhase.REHEARSED, listOf("A"), listOf("B"), "v1", "v1"))
        for (phase in AnswerPhase.entries.filter { it != AnswerPhase.REHEARSED })
            assertFalse(CompatibilityPolicy.passes(1, phase, listOf("B"), listOf("B"), "v1", "v1"))
        assertFalse(CompatibilityPolicy.passes(1, AnswerPhase.REHEARSED, emptyList(), emptyList(), "v1", "v1"))
    }
    @Test fun updateDuringRunOrMissingAppCannotBecomeProof() {
        assertFalse(CompatibilityPolicy.passes(1, AnswerPhase.REHEARSED, listOf("B"), listOf("B"), "v1", "v2"))
        assertFalse(CompatibilityPolicy.passes(1, AnswerPhase.REHEARSED, listOf("B"), listOf("B"), "v1", null))
    }
    @Test fun successfulMultipleChoiceIsOrderIndependentAndRestrictedToTestTypes() {
        assertTrue(CompatibilityPolicy.passes(2, AnswerPhase.REHEARSED, listOf("D", "B"), listOf("B", "D"), "v1", "v1"))
        assertFalse(CompatibilityPolicy.passes(6, AnswerPhase.REHEARSED, listOf("A"), listOf("A"), "v1", "v1"))
    }
}
