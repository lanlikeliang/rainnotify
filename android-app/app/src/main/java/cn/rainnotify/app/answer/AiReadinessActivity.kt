package cn.rainnotify.app.answer

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class AiReadinessActivity : ComponentActivity() {
    private val needsAccessibility get() = intent.getBooleanExtra("force_app", false) ||
        (!intent.getBooleanExtra("background_only", false) && !SolverSettings.isApi(this))
    private val main = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private val refresh = object : Runnable {
        override fun run() { update(); main.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (16 * resources.displayMetrics.density).toInt()
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        fun text(value: String) = TextView(this).apply { text = value; textSize = 17f; setPadding(0,pad,0,pad); layout.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() }; layout.addView(this) }
        text("本 App 后台运行" + if (needsAccessibility) "与作答辅助" else "设置")
        text("允许本 App 后台自启动，省电策略设为无限制。小米/澎湃 OS 未允许自启动时，退出后台可能导致 AI 作答辅助断开或需要重新开启。若本 App 设置中有启动其他应用、后台弹出界面等选项，请允许。")
        button("本 App 设置 · 自启动与省电") { details() }
        if (needsAccessibility) button("系统无障碍设置 · AI 作答辅助") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("本 App 通知设置") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        text(if (needsAccessibility) "AI App 只需安装、登录并通过图文自检，无需为它开启自启动或保持后台。侧载安装若提示受限设置，请在本 App 应用信息里允许，再开启无障碍服务。"
            else "只使用提醒或 API 作答时，不需要开启无障碍服务，也不需要安装 AI App。")
        layout.addView(CheckBox(this).apply {
            text = "我已检查雨课堂辅助 App 的自启动与省电设置"
            isChecked = AiReadiness.confirmed(this@AiReadinessActivity)
            setOnCheckedChangeListener { _, checked -> AiReadiness.confirm(this@AiReadinessActivity, checked); update() }
        })
        state = text("")
        button("重新检查本 App") { update() }
        button("返回本题／设置页") { finish() }
        setContentView(ScrollView(this).apply { addView(layout) }); layout.requestApplyInsets()
    }
    private fun details() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: RuntimeException) { state.text = "无法打开应用信息，请在系统设置中搜索雨课堂辅助 App。" }
    }
    private fun update() {
        if (!::state.isInitialized) return
        val issue = AiReadiness.appIssue(this, needsAccessibility)
        state.text = (issue ?: "本 App 检查通过，可以返回开始作答。") + "\n" +
            (if (needsAccessibility) "辅助服务：${if (KimiAccessibilityService.connected) "已连接" else "未连接"}\n" else "") +
            "通知：${if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "已允许" else "未允许"}\n" +
            "系统后台限制：${if (getSystemService(ActivityManager::class.java).isBackgroundRestricted) "已检测到" else "未检测到"}\n" +
            "厂商自启动与省电：${if (AiReadiness.confirmed(this)) "用户已确认" else "待用户确认"}"
    }
    override fun onResume() { super.onResume(); main.post(refresh) }
    override fun onPause() { main.removeCallbacks(refresh); super.onPause() }
}
