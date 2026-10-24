package cn.rainnotify.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import cn.rainnotify.app.answer.*
import cn.rainnotify.app.monitor.LessonMonitorService
import cn.rainnotify.app.monitor.MonitorState
import cn.rainnotify.app.question.QuestionActivity
import cn.rainnotify.app.question.QuestionRepository

class MainActivity : ComponentActivity() {
    private lateinit var classroom: TextView
    private lateinit var configuration: TextView
    private lateinit var automatic: Switch
    private lateinit var autoStatus: TextView
    private lateinit var latest: TextView
    private lateinit var stopListening: android.widget.Button
    private val main = Handler(Looper.getMainLooper())
    private var rendering = false
    private val refresh = object : Runnable { override fun run() { update(); main.postDelayed(this, 1000) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeNotifications.createChannels(this); Diagnostics.recordDevice(this); AnswerState.restore(this)
        val ui = AppScreen(this, "雨课堂助手", "登录课堂 → 开始监听 → 按需开启 AI 作答")
        val lesson = ui.section("课堂")
        classroom = ui.text("", parent = lesson)
        ui.button("登录 / 选择课堂", lesson) { startActivity(Intent(this, RainLoginActivity::class.java)) }
        stopListening = ui.button("停止监听", lesson) { stopService(Intent(this, LessonMonitorService::class.java)) }
        val ai = ui.section("自动作答")
        automatic = Switch(this).apply { text = "新题自动作答并提交"; textSize = 17f; ai.addView(this) }
        automatic.setOnCheckedChangeListener { _, enabled ->
            if (!rendering) {
                if (!enabled) AutoAnswer.disarm(this) else { update(); enableAutomatic() }
                update()
            }
        }
        autoStatus = ui.text("", parent = ai)
        configuration = ui.text("", 14f, ai)
        ui.button("设置作答方式", ai) { startActivity(Intent(this, AiSettingsActivity::class.java)) }
        val questions = ui.section("题目")
        latest = ui.text("", parent = questions)
        ui.button("查看题目 / 历史演练", questions) { startActivity(Intent(this, QuestionActivity::class.java)) }
        ui.text("历史题演练不提交、不需要登录。监听只负责提醒和保存题目；自动作答需要单独开启，每次课堂结束后关闭。", 14f, questions)
        ui.button("设置 · 通知、后台运行、AI 与诊断") { startActivity(Intent(this, SettingsActivity::class.java)) }
        if (BuildConfig.DEBUG) {
            val target = when {
                intent.getBooleanExtra("open_login_probe", false) -> RainLoginActivity::class.java
                intent.getBooleanExtra("open_questions_probe", false) -> QuestionActivity::class.java
                intent.getBooleanExtra("open_api_probe", false) -> ApiSettingsActivity::class.java
                else -> null
            }
            intent.removeExtra("open_login_probe"); intent.removeExtra("open_questions_probe"); intent.removeExtra("open_api_probe")
            if (target != null && savedInstanceState == null) startActivity(Intent(this, target))
        }
        update()
    }
    private fun enableAutomatic() {
        if (QuestionRepository.get(this).currentBinding() == null) {
            AlertDialog.Builder(this).setTitle("请先开始课堂监听")
                .setMessage("登录雨课堂，选择正在上的课并开始监听后，才能开启本次课堂的自动作答。")
                .setPositiveButton("去课堂") { _, _ -> startActivity(Intent(this, RainLoginActivity::class.java)) }
                .setNegativeButton("稍后", null).show(); return
        }
        if (!AiReadiness.ensure(this)) return
        if (AnswerState.task.value?.active == true || AutoAnswer.ticket != null) {
            AlertDialog.Builder(this).setMessage("请等待当前题目结束或停止任务后再开启。").setPositiveButton("知道了", null).show(); return
        }
        AlertDialog.Builder(this).setTitle("开启本次课堂自动作答？")
            .setMessage("接下来收到的新题会发送给 ${SolverSettings.title(this)}，得到有效答案后提交到雨课堂。AI 可能答错。\n\n" +
                (if (SolverSettings.isApi(this)) "API 可以在锁屏时运行，仍需允许本 App 后台自启动并保持网络可用。" else "AI App 路线需要解锁，作答期间请不要操作其他 App。") +
                "\n\n停止监听或切换作答方式会关闭自动模式；已发出的提交无法撤回。")
            .setPositiveButton("开启") { _, _ ->
                if (!AutoAnswer.arm(this)) autoStatus.text = "未能开启，请检查监听与配置。"
                update()
            }.setNegativeButton("取消", null).show()
    }
    private fun update() {
        if (!::classroom.isInitialized) return
        val repo = QuestionRepository.get(this)
        classroom.text = MonitorState.current().message
        stopListening.isEnabled = MonitorState.current().active
        rendering = true; automatic.isChecked = AutoAnswer.armed; rendering = false
        autoStatus.text = AutoAnswer.status.value.orEmpty()
        configuration.text = "作答方式：${SolverSettings.title(this)}\n" +
            (AiReadiness.issue(this, AiProvider.selected(this)) ?: "配置已就绪，可在监听后开启。")
        val record = repo.records.value?.firstOrNull()
        latest.text = if (record == null) "还没有题目。监听后会自动保存新题，也可导入志愿者导出的题目包。"
            else "已保存 ${repo.records.value?.size ?: 0} 题 · 最近一题：${record.content?.typeName ?: "获取中"}\n${record.message}"
    }
    override fun onResume() { super.onResume(); Diagnostics.appForeground = true; main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); Diagnostics.appForeground = false; super.onPause() }
}
