package cn.rainnotify.app.answer

import android.content.Intent
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import cn.rainnotify.app.AppScreen

class AiSettingsActivity : ComponentActivity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = AppScreen(this, "AI 作答方式", "配置与历史题演练不需要登录雨课堂。")
        val section = ui.section("选择作答方式")
        val group = RadioGroup(this)
        section.addView(group)
        fun option(label: String, selected: Boolean, select: () -> Unit) {
            val radio = RadioButton(this).apply { id = android.view.View.generateViewId(); text = label }
            group.addView(radio); radio.isChecked = selected
            radio.setOnClickListener {
                if (AnswerState.task.value?.active == true || AutoAnswer.ticket != null) {
                    Toast.makeText(this, "请等待当前题目结束或停止任务后再切换。", Toast.LENGTH_LONG).show()
                    recreate(); return@setOnClickListener
                }
                select(); update()
            }
        }
        option("DeepSeek API · 可在锁屏时作答", SolverSettings.isApi(this)) {
            SolverSettings.select(this, SolverMode.DEEPSEEK_API)
        }
        AiProvider.entries.forEach { provider ->
            option(provider.title + " App · 需要解锁", !SolverSettings.isApi(this) && AiProvider.selected(this) == provider) {
                if (AiProvider.selected(this) != provider) SolverSettings.changed(this)
                AiProvider.select(this, provider); SolverSettings.select(this, SolverMode.APP)
            }
        }
        ui.text("切换作答方式会关闭本次课堂自动模式。配置完成后，请返回首页重新开启。", 14f, section)
        val setup = ui.section("完成所选方式的配置")
        status = ui.text("", parent = setup)
        ui.button("配置或自检所选方式", setup) {
            startActivity(Intent(this, if (SolverSettings.isApi(this)) ApiSettingsActivity::class.java else AiCompatibilityActivity::class.java))
        }
        ui.button("检查本 App 运行设置", setup) { startActivity(Intent(this, AiReadinessActivity::class.java)) }
        ui.text("支持单选、多选、填空、投票和文字主观题。主观题默认 1–3 句、最多 300 字符；涉及个人意愿的投票会跳过。AI 可能答错，建议先使用历史题演练。", 15f)
        ui.back(); update()
    }
    private fun update() {
        if (!::status.isInitialized) return
        status.text = "当前：${SolverSettings.title(this)}\n" + if (SolverSettings.isApi(this))
            (SolverSettings.apiIssue(this, true) ?: "图文自检已通过。") + "\n题干和原图将发往 DeepSeek 官方 API；无需无障碍服务，无需打开 AI App。"
        else (AiReadiness.issue(this, AiProvider.selected(this)) ?: "运行条件与图文自检已通过。") +
            "\n只需安装并登录所选 AI App，无需保持其在后台。执行时需解锁并暂不操作其他 App。"
    }
    override fun onResume() { super.onResume(); update() }
}
