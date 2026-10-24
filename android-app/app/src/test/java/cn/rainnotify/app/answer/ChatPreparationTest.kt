package cn.rainnotify.app.answer

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPreparationTest {
    @Test fun alreadyBlankDoesNotClickCreateEvenWhenButtonExists() {
        val flow = ChatPreparation()
        assertEquals(ChatPreparation.Step.WAIT, flow.next(0, true, false, true))
        assertEquals(ChatPreparation.Step.SHARE, flow.next(1200, true, false, true))
    }
    @Test fun coldStartWaitsAndRetriesOnlyBeforeAnyShare() {
        val flow = ChatPreparation()
        assertEquals(ChatPreparation.Step.WAIT, flow.next(0, false, false, false))
        assertEquals(ChatPreparation.Step.CREATE, flow.next(1000, false, false, true))
        assertEquals(ChatPreparation.Step.WAIT, flow.next(2000, false, false, true))
        assertEquals(ChatPreparation.Step.CREATE, flow.next(4000, false, false, true))
        assertEquals(ChatPreparation.Step.FAILED, flow.next(10000, false, false, true))
    }
    @Test fun draftNeverReplacedAndBlankMustStayStable() {
        val flow = ChatPreparation()
        assertEquals(ChatPreparation.Step.BLOCK_DRAFT, flow.next(0, true, true, true))
        assertEquals(ChatPreparation.Step.WAIT, flow.next(100, true, false, false))
        assertEquals(ChatPreparation.Step.WAIT, flow.next(500, false, false, false))
        assertEquals(ChatPreparation.Step.WAIT, flow.next(1400, true, false, false))
        assertEquals(ChatPreparation.Step.SHARE, flow.next(2600, true, false, false))
    }
}
