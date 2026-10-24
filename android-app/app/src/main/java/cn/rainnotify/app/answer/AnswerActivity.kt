package cn.rainnotify.app.answer

import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import cn.rainnotify.app.question.QuestionActivity
import cn.rainnotify.app.question.QuestionFiles
import cn.rainnotify.app.question.QuestionRepository

class AnswerActivity : ComponentActivity() {
    private var questionId: String? = null
    private lateinit var status: TextView
    private lateinit var providerSummary: TextView
    private lateinit var live: Button
    private lateinit var rehearsal: Button
    private lateinit var stop: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SolverSettings.isApi(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        questionId = intent.getStringExtra(AnswerService.QUESTION)?.takeIf { QuestionFiles.validId(it) }
        AnswerState.restore(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (16 * resources.displayMetrics.density).toInt()
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        fun text(value: String) = TextView(this).apply { text = value; textSize = 17f; setPadding(0, pad, 0, pad); layout.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() }; layout.addView(this) }
        text("单题 AI 作答")
        providerSummary = text("")
        status = text("准备就绪。")
        live = button("开始 AI 作答并提交本题") { start(true) }
        rehearsal = button("仅演练本题（不提交）") { start(false) }
        button("所选方式的配置与自检") { startActivity(Intent(this, if (SolverSettings.isApi(this)) ApiSettingsActivity::class.java else AiCompatibilityActivity::class.java)) }
        button("30 秒后模拟发题并自动演练（可熄屏）") {
            if (!AiReadiness.ensure(this)) return@button
            if (AnswerState.task.value?.active != true && AutoAnswer.ticket == null) {
                startForegroundService(Intent(this, AnswerService::class.java).setAction(AnswerService.SIMULATE)
                    .putExtra(AnswerService.QUESTION, questionId))
                status.text = "30 秒后自动启动本题演练，不提交雨课堂。现在可按电源键熄屏。"
            }
        }
        stop = button("停止本题") { startService(Intent(this, AnswerService::class.java).setAction(AnswerService.STOP)) }
        button("更换 AI 作答方式") { startActivity(Intent(this, AiSettingsActivity::class.java)) }
        button("查看本题") { questionId?.let {
            startActivity(Intent(this, QuestionActivity::class.java).putExtra(QuestionActivity.QUESTION_ID, it))
        } }
        button("返回") { finish() }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.requestApplyInsets()
        AnswerState.task.observe(this) { refresh() }
        QuestionRepository.get(this).records.observe(this) { refresh() }
        QuestionRepository.get(this).liveLesson.observe(this) { refresh() }
        KimiBridge.requested.observe(this) { maybeLaunch() }
        if (savedInstanceState == null && intent.getBooleanExtra(AUTO, false)) {
            intent.removeExtra(AUTO)
            start(intent.getBooleanExtra(AnswerService.LIVE, false))
        }
    }
    private fun start(live: Boolean) {
        val id = questionId ?: return
        if (!AiReadiness.ensure(this, requireVerified = live || intent.hasExtra(AnswerService.AUTO_TICKET))) {
            status.text = "请处理显示的本 App 运行条件或 AI 安装版本提示，再返回开始本题。"
            return
        }
        if (AnswerState.task.value?.active == true) return
        if (AutoAnswer.ticket != null && intent.getStringExtra(AnswerService.AUTO_TICKET) == null) {
            status.text = "自动流程正在等待，请先从通知完成或停止它。"; return
        }
        try {
            startForegroundService(Intent(this, AnswerService::class.java).setAction(AnswerService.START)
                .putExtra(AnswerService.QUESTION, id).putExtra(AnswerService.LIVE, live)
                .putExtra(AnswerService.AUTO_TICKET, intent.getStringExtra(AnswerService.AUTO_TICKET)))
            intent.removeExtra(AnswerService.AUTO_TICKET)
            status.text = "正在启动本题流程…"
        } catch (_: RuntimeException) { status.text = "系统未允许启动作答服务，请检查后台与通知设置。" }
    }
    private fun refresh() {
        if (!::status.isInitialized) return
        val task = AnswerState.task.value
        val busy = task?.active == true
        val same = task?.questionId == questionId
        status.text = when {
            task != null && same -> task.message + if (task.answer.isNotEmpty()) "\nAI 答案：\n" + task.answer.mapIndexed { index, value ->
                if (task.answer.size > 1) "${index + 1}. $value" else value }.joinToString("\n") else ""
            busy -> "另一道题正在运行，请先等待或停止当前任务。"
            else -> "历史题可演练；只有当前监听中的新题可以提交。需要配置时会显示原因与设置入口。"
        }
        val repo = QuestionRepository.get(this)
        val record = repo.records.value?.firstOrNull { it.id == questionId }
        live.isEnabled = !busy && record != null && !record.offline && repo.activeBinding(record.id) != null
        rehearsal.isEnabled = !busy && record?.content != null && !record.loading
        stop.isEnabled = busy && same
    }
    private fun maybeLaunch() {
        val request = KimiBridge.active ?: return
        if (!request.verification && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && request.prepared.record.id == questionId)
            KimiBridge.launch(this, request)
    }
    override fun onResume() {
        super.onResume()
        providerSummary.text = "本题将发送给：${SolverSettings.title(this)}。" +
            if (SolverSettings.isApi(this)) "发送题干和原图后可锁屏等待结果；请保持网络与本 App 后台运行。"
            else "AI App 只需安装并登录，无需保持后台。作答时请保持解锁，等待流程结束再操作其他 App。"
        if (SolverSettings.isApi(this)) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refresh(); maybeLaunch()
    }
    companion object { const val AUTO = "start_answer_from_user_action" }
}
