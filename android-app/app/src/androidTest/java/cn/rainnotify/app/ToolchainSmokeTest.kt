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
    @Test fun accountClassroomAndModelReturnToTheirEntryPage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            for (entry in listOf("当前账户","当前课堂","当前模型")) {
                compose.onNodeWithText(entry).performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("‹").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithTag("mode-selector").assertExists()
            }
            compose.onNodeWithText("设置").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("服务商与模型").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("‹").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("运行保障").assertExists()
        }
    }
    @Test fun verificationCannotSilentlyDowngradeAnAutomaticRequest() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        try {
            instrumentation.runOnMainSync {
                ApiKeyVault(context).write("synthetic-key-for-local-test")
                SolverSettings.selectModel(context,DeepSeekModel.FLASH)
                SolverSettings.markVerified(context)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { MonitorState.automaticRequested=true }
                compose.onNodeWithText("当前模型").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("重新自检").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
                compose.waitForIdle()
                assertTrue(MonitorState.automaticRequested)
                assertTrue(SolverSettings.apiVerified(context))
                compose.onNodeWithText("自动答题运行中，当前模式保持不变。请先在课堂切回发题通知，再进行自检或修改 API。").assertExists()
            }
        } finally { instrumentation.runOnMainSync {
            MonitorState.automaticRequested=false;ApiKeyVault(context).clear();SolverSettings.keyChanged(context)
        } }
    }

    @Test fun modelSelectionPersistsAndCannotReuseAnotherModelsCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val vault = ApiKeyVault(context)
        try {
            instrumentation.runOnMainSync {
                SolverSettings.selectModel(context, DeepSeekModel.FLASH)
                vault.write("synthetic-key-for-local-test") // No network requests in this test.
                SolverSettings.markVerified(context)
            }
            assertTrue(SolverSettings.apiVerified(context))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithText("设置").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("服务商与模型").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("Flash").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("V4 Pro").performSemanticsAction(SemanticsActions.OnClick)
                compose.waitForIdle()
                assertEquals(DeepSeekModel.PRO, SolverSettings.model(context))
                assertFalse(SolverSettings.apiVerified(context))
                scenario.recreate()
                compose.onNodeWithText("V4 Pro").assertExists()
                compose.onNodeWithText("当前模型文本自检").assertExists()
                compose.onNodeWithText("图文自检通过").assertDoesNotExist()
            }
            instrumentation.runOnMainSync { SolverSettings.selectModel(context, DeepSeekModel.FLASH) }
            assertFalse(SolverSettings.apiVerified(context))
        } finally { instrumentation.runOnMainSync {
            vault.clear(); SolverSettings.keyChanged(context); SolverSettings.selectModel(context, DeepSeekModel.FLASH)
        } }
    }
    @Test fun classroomArchiveDoesNotExposeDeveloperExercises() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("题目").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("暂无课堂题目").assertExists()
            compose.onNodeWithText("导入题目").assertDoesNotExist()
            compose.onNodeWithText("仅演练本题（不提交）").assertDoesNotExist()
        }
    }
}
