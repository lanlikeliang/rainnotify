package cn.rainnotify.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.rainnotify.app.answer.*
import cn.rainnotify.app.monitor.MonitorState
import cn.rainnotify.app.question.*
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.allOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID

/** Real app UI and Android filesystem; synthetic content only, no cloud or classroom calls. */
@RunWith(AndroidJUnit4::class)
class ToolchainSmokeTest {
    // Exercise real View callbacks/navigation without Xiaomi's global INJECT_EVENTS privilege.
    // This suite validates control behavior; physical touch delivery remains a manual check.
    private fun click() = object : ViewAction {
        override fun getConstraints() = allOf(isDisplayed(), isEnabled())
        override fun getDescription() = "Activate an enabled application control"
        override fun perform(controller: UiController, view: android.view.View) {
            check(view.performClick()) { "Application control did not handle activation" }
            controller.loopMainThreadUntilIdle()
        }
    }
    @Before fun idleDeviceOnly() {
        check(!MonitorState.current().active && !AutoAnswer.armed && AnswerState.task.value?.active != true) {
            "Stop classroom monitoring and answer tasks before running device tests."
        }
        // Xiaomi may block startActivitySync while the fresh test package is in the background.
        // Bring only our isolated test app forward using the test runner's shell identity.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "cn.rainnotify.app.smoke")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n cn.rainnotify.app.smoke/cn.rainnotify.app.MainActivity"
        )).use { it.readBytes() }
    }
    @Test fun homeShowsPrimaryActionsAndDisablesInactiveStop() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("登录 / 选择课堂")).check(matches(isDisplayed()))
            onView(withText("停止监听")).check(matches(not(isEnabled())))
            onView(withText("新题自动作答并提交")).check(matches(not(isChecked())))
        }
    }
    @Test fun automaticModeExplainsMissingClassroomAndStaysOff() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("新题自动作答并提交")).perform(click())
            onView(withText("请先开始课堂监听")).check(matches(isDisplayed()))
            onView(withText("稍后")).perform(click())
            onView(withText("新题自动作答并提交")).check(matches(not(isChecked())))
            assertFalse(AutoAnswer.armed)
        }
    }
    @Test fun settingsAreReachableWithoutLogin() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("设置 · 通知、后台运行、AI 与诊断")).perform(scrollTo(), click())
            onView(withText("1 · 通知与声音")).check(matches(isDisplayed()))
            onView(withText("3 · AI 作答方式与配置")).check(matches(isDisplayed()))
            onView(withText("返回")).perform(scrollTo(), click())
        }
    }
    @Test fun exportedSyntheticQuestionImportsOfflineAndRehearsesWithoutSubmissionGateway() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "toolchain-test-").toFile()
        try {
            val q = QuestionContent(1, "本地合成测试：一加一等于几？", listOf(
                QuestionOption("A", "a", "一"), QuestionOption("B", "b", "二")), emptyList(), null, emptyList())
            val source = QuestionFiles(root.resolve("source"))
            val original = QuestionRecord(UUID.randomUUID().toString(), QuestionRef("fixture", "fixture", "fixture"),
                1, q, "ready", false)
            source.write(original)
            val zip = ByteArrayOutputStream().also { source.export(it, QuestionExportInfo("test", 36, "fixture", "fixture")) }
            val target = QuestionFiles(root.resolve("target"))
            assertEquals(1, QuestionImport.read(zip.toByteArray().inputStream(), root.resolve("staging"), target))
            val imported = target.records().single()
            assertTrue(imported.offline)
            assertEquals("offline", imported.reference.lessonId)
            val prepared = target.prepare(imported.id, root.resolve("prepared"))
            val journal = SubmissionJournal(root.resolve("journal"))
            val reply = JSONObject().put("task", "offline-test").put("answer", JSONArray().put("B")).put("unable", false)
            val result = AnswerEngine({ 0L }, journal).run("offline-test", prepared.record.content!!, 0,
                RunControl(), null, null, { reply.toString() }, { _, _ -> })
            assertEquals(AnswerPhase.REHEARSED, result.phase)
            assertEquals(listOf("B"), result.answer)
            assertFalse(journal.used("a".repeat(64)))
        } finally { root.deleteRecursively() }
    }
}
