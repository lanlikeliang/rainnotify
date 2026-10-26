package cn.rainnotify.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.rainnotify.app.answer.*
import cn.rainnotify.app.auth.SessionVault
import cn.rainnotify.app.monitor.MonitorState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated local app UI only. No test enters a class, calls a model or submits an answer. */
@RunWith(AndroidJUnit4::class)
class ToolchainSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun isolatedIdleDeviceOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName=="cn.rainnotify.app.smoke")
        check(SessionVault(context).read().isEmpty()) { "Use an unsigned-in isolated smoke package." }
        check(!MonitorState.current().active && !AutoAnswer.armed && AnswerState.task.value?.active!=true)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n cn.rainnotify.app.smoke/cn.rainnotify.app.MainActivity"
        )).use { it.readBytes() }
    }
    @Test fun modesAreMutuallyExclusiveAndInitiallyOff() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("mode_OFF").assertIsSelected()
            compose.onNodeWithTag("mode_NOTIFY").assertIsNotSelected()
            compose.onNodeWithTag("mode_AUTO").assertIsNotSelected()
            compose.onNodeWithText("当前账户").assertExists()
            compose.onNodeWithText("当前课堂").assertExists()
            compose.onNodeWithText("当前模型").assertExists()
        }
    }
    @Test fun missingSetupDoesNotOptimisticallyEnableAutomaticSubmission() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("mode_AUTO").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("开始前还需要").assertExists()
            assertFalse(AutoAnswer.armed)
            assertFalse(MonitorState.current().active)
        }
    }
    @Test fun settingsRemainAvailableWithoutRainLogin() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("设置").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("通知与声音").assertExists()
            compose.onNodeWithText("配置并自检").assertExists()
            compose.onNodeWithText("历史演练").assertDoesNotExist()
        }
    }
    @Test fun latestQuestionPageDoesNotExposeOldExercises() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("题目").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("等待课堂发题").assertExists()
            compose.onNodeWithText("导入题目").assertDoesNotExist()
            compose.onNodeWithText("仅演练本题（不提交）").assertDoesNotExist()
        }
    }
}
