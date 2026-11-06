package cn.rainnotify.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import cn.rainnotify.app.question.*
import cn.rainnotify.app.ui.*
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.rainnotify.app.answer.*
import cn.rainnotify.app.auth.*
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
        RainServer.entries.forEach { server ->
            check(SessionVault(context, server).read().isEmpty()) { "Use an unsigned-in isolated smoke package." }
        }
        context.getSharedPreferences("rain-server-v1", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        check(!MonitorState.current().active && !AutoAnswer.armed && AnswerState.task.value?.active!=true)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n cn.rainnotify.app.smoke/cn.rainnotify.app.MainActivity"
        )).use { it.readBytes() }
    }

    @Test fun imageComparisonUsesActualBytesAndPixelsWithTextFallbackForUndecodableImages() {
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        fun encoded(): ByteArray = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        try {
            bitmap.eraseColor(Color.WHITE)
            val before = encoded()
            assertEquals(ImageComparison.MATCH, AnswerImages.compareBytes(before, before.copyOf()))
            assertEquals(ImageComparison.MATCH, AnswerImages.compareBytes(before, before + byteArrayOf(0)))
            bitmap.setPixel(5, 4, Color.BLACK)
            assertEquals(ImageComparison.DIFFERENT, AnswerImages.compareBytes(before, encoded()))
            assertEquals(ImageComparison.UNAVAILABLE, AnswerImages.compareBytes(before, byteArrayOf(1, 2, 3)))
        } finally { bitmap.recycle() }
    }

    @Test fun classroomReferenceShowsImageTimeTypeAndRequiresConfirmationBeforeManualCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val image = File(context.cacheDir, "synthetic-reference.png")
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val question = QuestionContent(1, "Synthetic reference", listOf(QuestionOption("A", "1", "one"), QuestionOption("B", "2", "two")),
            listOf(QuestionAsset("课件原图", "https://synthetic.yuketang.cn/image.png")), 1, emptyList())
        val record = QuestionRecord("abcdefab-1234-1234-1234-123456789abc", QuestionRef("10", "20", "30"), 1_800_000_000_000,
            question, loading = false, accountScope = "synthetic")
        val answer = AnswerTask("12345678-1234-1234-1234-123456789abc", record.id, true, AnswerPhase.FAILED,
            "自动提交条件未满足，参考答案已保留。", listOf("A"), sourceQuestion = question)
        val confirming = mutableStateOf(false)
        val blocked = mutableStateOf<String?>(null)
        var submissions = 0
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    RainTheme {
                        ClassroomPage(HomeState(latest = record, answer = answer), {}, {}, {}, {}, imageFile = { _, _ -> image },
                            submit = { _, _ -> confirming.value = true }, submissionIssue = { _, _ -> blocked.value })
                        if (confirming.value) ReferenceSubmissionDialog(record, answer,
                            confirm = { submissions++; confirming.value = false }, dismiss = { confirming.value = false })
                    }
                } }
                compose.onNodeWithText("提交参考答案").performScrollTo().assertIsEnabled()
                compose.onNodeWithText("参考答案").assertExists()
                compose.onNodeWithText("单选").assertExists()
                compose.onNodeWithText("发题时间", substring = true).assertExists()
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("题目原图，点击放大").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("提交参考答案").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("确认提交这道题的参考答案").assertExists()
                assertEquals(0, submissions)
                compose.onNodeWithText("返回查看").performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(0, submissions)
                compose.onNodeWithText("提交参考答案").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("已核对，直接提交").performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(1, submissions)
                scenario.onActivity { blocked.value = "本题已有提交尝试，请到雨课堂核对回执。" }
                compose.onNodeWithText("提交参考答案").assertIsNotEnabled()
                compose.onNodeWithText("A").assertExists()
            }
        } finally { image.delete() }
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

    @Test fun serverMustBeChosenBeforeAnyLoginAndSelectionSurvivesRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("当前账户").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("1. 选择服务器").assertExists()
            compose.onNodeWithText("生成微信授权二维码").assertDoesNotExist()
            for (server in RainServer.entries) compose.onNodeWithTag("server_${server.id}").assertIsNotSelected()
            scenario.onActivity { activity ->
                androidx.lifecycle.ViewModelProvider(activity)[RainLoginViewModel::class.java].startLogin()
            }
            compose.waitForIdle()
            assertNull(ServerSettings.selected(context))
            compose.onNodeWithText("微信授权二维码").assertDoesNotExist()
            compose.onNodeWithTag("server_huanghe").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("生成微信授权二维码").fetchSemanticsNodes().size == 1 }
            assertEquals(RainServer.HUANGHE, ServerSettings.selected(context))
            compose.onNodeWithTag("server_huanghe").assertIsSelected()
            scenario.recreate()
            compose.onNodeWithTag("server_huanghe").assertIsSelected()
            compose.onNodeWithTag("server_changjiang").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { ServerSettings.selected(context) == RainServer.CHANGJIANG }
            compose.onNodeWithTag("server_changjiang").assertIsSelected()
            compose.onNodeWithTag("server_huanghe").assertIsNotSelected()
        }
    }

    @Test fun encryptedServerSessionsAndLogoutAreIndependent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            RainServer.entries.forEach { server -> SessionVault(context, server).write(listOf("synthetic-${server.id}")) }
            RainServer.entries.forEach { server ->
                assertEquals(listOf("synthetic-${server.id}"), SessionVault(context, server).read())
            }
            SessionVault(context, RainServer.CHANGJIANG).clear()
            assertTrue(SessionVault(context, RainServer.CHANGJIANG).read().isEmpty())
            for (server in RainServer.entries.filter { it != RainServer.CHANGJIANG }) {
                assertEquals(listOf("synthetic-${server.id}"), SessionVault(context, server).read())
            }
        } finally { RainServer.entries.forEach { SessionVault(context, it).clear() } }
    }

    @Test fun reopeningActivityOnTheSameServerDoesNotStopAnExistingMonitor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        try {
            instrumentation.runOnMainSync {
                ServerSettings.select(context, RainServer.HUANGHE)
                MonitorState.stopRequested = false
                MonitorState.update(cn.rainnotify.app.monitor.MonitorUiState(active = true, established = true,
                    server = RainServer.HUANGHE, lessonId = "10",
                    connection = cn.rainnotify.app.monitor.ConnectionState.LISTENING))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { compose.onAllNodesWithText("黄河雨课堂").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(MonitorState.current().active)
                assertFalse(MonitorState.stopRequested)
                scenario.recreate()
                compose.waitForIdle()
                assertTrue(MonitorState.current().active)
                assertFalse(MonitorState.stopRequested)
            }
        } finally { instrumentation.runOnMainSync {
            MonitorState.update(cn.rainnotify.app.monitor.MonitorUiState())
            MonitorState.stopRequested = false
        } }
    }
}
