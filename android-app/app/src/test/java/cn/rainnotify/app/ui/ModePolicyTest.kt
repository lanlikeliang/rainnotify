package cn.rainnotify.app.ui

import org.junit.Assert.*
import org.junit.Test

class ModePolicyTest {
    private val ready = ModeRequirements(true,true,true,false,true,true)
    @Test fun closedModeNeverDemandsPermissionsOrCredentials() {
        assertTrue(ModePolicy.missing(ClassroomMode.OFF,ModeRequirements(false,false,false,true,false,false)).isEmpty())
    }
    @Test fun notificationsDoNotRequireAnApi() {
        assertTrue(ModePolicy.missing(ClassroomMode.NOTIFY,ready.copy(apiKey=false,apiChecked=false)).isEmpty())
    }
    @Test fun automaticRequiresActualCapabilityCheck() {
        assertEquals(listOf(Requirement.API_CHECK),ModePolicy.missing(ClassroomMode.AUTO,ready.copy(apiChecked=false)))
    }
    @Test fun absentKeyDoesNotProduceTwoContradictoryActions() {
        assertEquals(listOf(Requirement.API_KEY),ModePolicy.missing(ClassroomMode.AUTO,ready.copy(apiKey=false,apiChecked=false)))
    }
    @Test fun accountMustComeBeforeClassroomSelection() {
        assertEquals(listOf(Requirement.ACCOUNT),ModePolicy.missing(ClassroomMode.NOTIFY,ready.copy(account=false,classroom=false)))
    }
    @Test fun noClassDoesNotBecomeRunningJustBecauseAccountIsValid() {
        assertEquals(listOf(Requirement.CLASSROOM),ModePolicy.missing(ClassroomMode.NOTIFY,ready.copy(classroom=false)))
    }
    @Test fun knownBackgroundRestrictionBlocksBothRunningModes() {
        for(mode in listOf(ClassroomMode.NOTIFY,ClassroomMode.AUTO))
            assertTrue(ModePolicy.missing(mode,ready.copy(backgroundRestricted=true)).contains(Requirement.BACKGROUND))
    }
    @Test fun deniedNotificationsRemainAnExplicitPrerequisite() {
        assertEquals(listOf(Requirement.NOTIFICATIONS),ModePolicy.missing(ClassroomMode.NOTIFY,ready.copy(notifications=false)))
    }
    @Test fun startingServiceIsNotAConnectedClassroom() {
        assertEquals(ClassroomMode.OFF,ModePolicy.effective(true,false,false))
        assertEquals(ClassroomMode.OFF,ModePolicy.effective(true,false,true))
    }
    @Test fun stoppingOverridesAnOldAutomaticFlag() {
        assertEquals(ClassroomMode.OFF,ModePolicy.effective(false,true,true))
    }
    @Test fun switchingOffAutomaticPreservesNotificationMode() {
        assertEquals(ClassroomMode.AUTO,ModePolicy.effective(true,true,true))
        assertEquals(ClassroomMode.NOTIFY,ModePolicy.effective(true,true,false))
    }
    @Test fun lateAnswerCannotOverwriteANewerQuestion() {
        assertFalse(ModePolicy.answerBelongsToLatest("new-question","old-question"))
        assertFalse(ModePolicy.answerBelongsToLatest(null,null))
        assertTrue(ModePolicy.answerBelongsToLatest("current","current"))
    }
}
