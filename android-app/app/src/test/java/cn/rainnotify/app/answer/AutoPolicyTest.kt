package cn.rainnotify.app.answer

import org.junit.Assert.*
import org.junit.Test

class AutoPolicyTest {
    @Test fun onlyCurrentArmedSessionCanAdmitLiveEvents() {
        val policy = AutoPolicy { 100 }
        assertNull(policy.offer("t", "q", "lesson1", false))
        policy.arm("lesson1")
        assertNull(policy.offer("t", "q", "lesson2", false))
        assertNotNull(policy.offer("t", "q", "lesson1", false))
        assertTrue(policy.valid("t"))
        policy.arm("lesson2")
        assertFalse(policy.valid("t"))
    }
    @Test fun rehearsalHasNoLiveSessionAndExpiresFromEventTime() {
        var now = 100L
        val policy = AutoPolicy { now }
        val ticket = policy.offer("t", "q", null, false)!!
        assertFalse(ticket.live)
        now += 84_999; assertTrue(policy.valid("t"))
        now++; assertFalse(policy.valid("t"))
    }
    @Test fun noConcurrentOrDuplicateJobsAndStaleCompletionDoesNotClearNewJob() {
        val policy = AutoPolicy { 100 }
        policy.arm("s")
        assertNull(policy.offer("t", "q", "s", true))
        assertNotNull(policy.offer("t", "q", "s", false))
        assertNull(policy.offer("t2", "q2", "s", false))
        policy.finish("t")
        assertNull(policy.offer("t2", "q", "s", false))
        assertNotNull(policy.offer("t2", "q2", "s", false))
        policy.finish("t")
        assertTrue(policy.valid("t2"))
        policy.disarm(); assertFalse(policy.valid("t2"))
        assertNull(AutoPolicy { 100 }.offer("t", "q", "s", false))
    }
}
