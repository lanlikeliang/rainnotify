package cn.rainnotify.app.answer
import org.junit.Assert.*
import org.junit.Test
class PendingAnswersTest {
    @Test fun busyArrivalsStayInOrderAndDuplicateDoesNotRestartTime() {
        val pending=PendingAnswers()
        assertTrue(pending.offer(PendingAnswers.Entry("t1","q1",100)))
        assertTrue(pending.offer(PendingAnswers.Entry("t2","q2",200)))
        assertFalse(pending.offer(PendingAnswers.Entry("other","q1",999)))
        assertEquals(100,pending.poll()!!.received)
        assertEquals("q2",pending.poll()!!.question)
        assertNull(pending.poll())
    }
    @Test fun clearingSessionCancelsAllWaitingQuestions() {
        val pending=PendingAnswers();pending.offer(PendingAnswers.Entry("t","q",100))
        assertEquals(listOf("q"),pending.clear().map { it.question })
        assertEquals(0,pending.size)
        assertTrue(pending.offer(PendingAnswers.Entry("new","q",200)))
    }
    @Test fun queuedTicketKeepsOriginalReceiptDeadline() {
        var now=100L;val policy=AutoPolicy { now };policy.arm("s")
        now=70_000
        val ticket=policy.offer("t","q","s",false,100)!!
        assertEquals(100,ticket.received);assertTrue(policy.valid("t"))
        now=85_100;assertFalse(policy.valid("t"))
    }
}
