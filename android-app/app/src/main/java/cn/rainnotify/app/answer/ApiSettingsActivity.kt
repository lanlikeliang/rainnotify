package cn.rainnotify.app.answer

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import cn.rainnotify.app.AppScreen
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class ApiSettingsActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var control: RunControl? = null
    private lateinit var status: TextView
    private lateinit var input: EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = AppScreen(this, "DeepSeek API", "手机直接调用，无需安装 DeepSeek App。")
        val config = ui.section("1 · 保存密钥")
        ui.text("官方服务：api.deepseek.com\n视觉模型：${DeepSeekApi.MODEL}\n每次作答会发送本题文字和原图，并由服务商按用量计费。雨课堂登录凭据不会发送给模型。", parent = config)
        input = EditText(this).apply {
            hint = "粘贴 API Key（保存后不回显）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            config.addView(this)
        }
        ui.button("加密保存密钥", config) {
            if (busy()) return@button
            try {
                ApiKeyVault(this).write(input.text.toString().trim()); input.text.clear()
                SolverSettings.keyChanged(this); update("已保存，请进行下方图文自检。")
            } catch (e: AnswerFailure) { update(e.display) }
        }
        ui.text("密钥只加密保存在这台手机，不进入题目导出或诊断报告。", 14f, config)
        val check = ui.section("2 · 验证图文调用")
        status = ui.text("", parent = check)
        ui.button("开始图文自检（会调用 API）", check) { verify() }
        ui.button("停止自检", check) { control?.cancel() }
        ui.text("使用随机生成的两组测试图片，核对单选、多图多选与答案格式，不提交课堂答案。通过后可在首页开启自动作答。", 14f, check)
        ui.button("删除本机密钥", config) {
            if (!busy()) { ApiKeyVault(this).clear(); SolverSettings.keyChanged(this); update("密钥已删除。") }
        }
        ui.button("使用 DeepSeek API 模式") {
            if (!busy()) { SolverSettings.select(this, SolverMode.DEEPSEEK_API); update("已选择 API 模式。"); }
        }
        ui.back(); update()
    }
    private fun busy(): Boolean {
        val busy = control != null || AnswerState.task.value?.active == true || AutoAnswer.armed || AutoAnswer.ticket != null
        if (busy) update("请先停止自检、自动模式或正在处理的题目，再修改配置。")
        return busy
    }
    private fun update(message: String = "") {
        if (::status.isInitialized) status.text = (if (!ApiKeyVault(this).exists()) "尚未保存 API Key。"
            else if (SolverSettings.apiVerified(this)) "图文自检已通过，可以使用。" else "密钥已保存，尚未通过图文自检。") +
            if (message.isBlank()) "" else "\n\n$message"
    }
    private fun verify() {
        if (busy()) return
        if (!ApiKeyVault(this).exists()) { update("请先保存密钥。"); return }
        val run = RunControl(); control = run
        val revision = SolverSettings.revision(this)
        update("正在校验单选图片与多图多选，通常需要几十秒…")
        worker.execute {
            var result: String
            var passed = false
            val dir = File(cacheDir, "api-check/" + UUID.randomUUID())
            try {
                val key = ApiKeyVault(this).read()
                for (type in listOf(1, 2)) {
                    run.check()
                    val task = UUID.randomUUID().toString()
                    val sample = CompatibilitySample.create(task, type, File(dir, "$type"))
                    val q = sample.prepared.record.content!!
                    val reply = DeepSeekApi().solve(key, AnswerProtocol.prompt(q, task), SolverSettings.images(sample.prepared), 60_000, run)
                    val answer = AnswerProtocol.parse(reply, task, q)
                    if (answer != sample.expected.sorted()) throw AnswerFailure("API 已返回答案，但图片识别结果不符，请重试图文自检。")
                }
                run.check(); passed = true
                result = "单选和多图多选均通过。可以返回 AI 设置选择 API 模式，或到题目页演练。"
            } catch (e: Exception) { result = (e as? AnswerFailure)?.display ?: "图文自检未完成，请检查网络后重试。" }
            finally { dir.deleteRecursively() }
            runOnUiThread {
                control = null
                if (!isDestroyed) {
                    if (passed && !run.cancelled && SolverSettings.revision(this) == revision) SolverSettings.markVerified(this)
                    update(result)
                }
            }
        }
    }
    override fun onDestroy() { control?.cancel(); worker.shutdown(); super.onDestroy() }
}
