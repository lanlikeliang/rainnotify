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
import java.util.UUID

class AiCompatibilityActivity : ComponentActivity() {
    private lateinit var state: TextView
    private lateinit var progress: TextView
    private lateinit var provider: AiProvider
    private val starts = mutableListOf<Button>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        provider = AiProvider.entries.firstOrNull { it.name == intent.getStringExtra("provider") } ?: AiProvider.selected(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AnswerState.restore(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (16 * resources.displayMetrics.density).toInt()
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        fun text(value: String) = TextView(this).apply {
            text = value; textSize = 17f; setPadding(0, pad, 0, pad); layout.addView(this)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; setOnClickListener { action() }; layout.addView(this)
        }
        text("${provider.title} 更新后图文自检")
        state = text("")
        progress = text("")
        text("依次完成下面两项。每次会生成新的测试图片，自动打开所选 AI、传入图文、发送并读回答案，核对图片中的已知答案。无需真实课堂或历史题；绝不会提交雨课堂。")
        text("请先登录 AI App，保持解锁。测试中不要手动发送或切换页面；结束后返回这里查看结果。通过只证明当前界面与基础图文流程可用，不保证所有题目答对或每次网络连接成功。")
        starts += button("1. 验证单选 · 1 张原图") { start(1) }
        starts += button("2. 验证多选 · 2 张原图") { start(2) }
        button("停止当前测试") {
            startService(Intent(this, AnswerService::class.java).setAction(AnswerService.STOP))
        }
        button("检查本 App 运行条件") { startActivity(Intent(this, AiReadinessActivity::class.java)) }
        text("两项通过后允许当前安装版本参与正式作答和自动模式。再次更新、重装或辅助 App 调整适配规则时需重新验证。失败时保留明确原因，可重试；历史题仍可手动演练。")
        button("返回 AI 设置") { startActivity(Intent(this, AiSettingsActivity::class.java)); finish() }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.requestApplyInsets()
        AnswerState.task.observe(this) { refresh() }
        KimiBridge.requested.observe(this) { launch() }
        // Only the signature-protected debug probe supplies this extra; production entry is manual.
        if (cn.rainnotify.app.BuildConfig.DEBUG && savedInstanceState == null) {
            val type = intent.getIntExtra("debug_verify_type", 0)
            intent.removeExtra("debug_verify_type")
            if (type in CompatibilityPolicy.requiredTypes) start(type)
        }
    }
    private fun start(type: Int) {
        if (AutoAnswer.armed || AutoAnswer.ticket != null) { progress.text = "请先关闭本次课堂自动模式，再进行自检。"; return }
        if (AnswerState.task.value?.active == true) return
        if (!AiReadiness.ensure(this, requireVerified = false, provider = provider, forceApp = true)) return
        startForegroundService(Intent(this, AnswerService::class.java).setAction(AnswerService.START)
            .putExtra(AnswerService.QUESTION, UUID.randomUUID().toString())
            .putExtra(AnswerService.VERIFY_TYPE, type).putExtra(AnswerService.PROVIDER, provider.name)
            .putExtra(AnswerService.LIVE, false))
        progress.text = "正在启动兼容性自检…"
    }
    private fun refresh() {
        state.text = AiCompatibility.status(this, provider)
        val task = AnswerState.task.value
        // Completed tasks may belong to an old build/provider. Only the keyed proof above is authoritative.
        progress.text = if (task?.active == true) "当前运行中的流程：${task.message}" else ""
        starts.forEach { it.isEnabled = task?.active != true }
    }
    private fun launch() {
        val request = KimiBridge.active ?: return
        if (request.verification && request.provider == provider && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            KimiBridge.launch(this, request)
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onPostResume() { super.onPostResume(); launch() }
}
