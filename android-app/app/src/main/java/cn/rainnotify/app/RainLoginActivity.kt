package cn.rainnotify.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import cn.rainnotify.app.auth.RainLoginViewModel
import cn.rainnotify.app.auth.LessonSummary
import cn.rainnotify.app.monitor.LessonMonitorService
import cn.rainnotify.app.monitor.MonitorState
import cn.rainnotify.app.monitor.MonitorUiState
import cn.rainnotify.app.monitor.ClassroomScanReturn

class RainLoginActivity : ComponentActivity() {
    private lateinit var model: RainLoginViewModel
    private lateinit var backgroundStatus: TextView
    private val scanReturn = ClassroomScanReturn(SystemClock::elapsedRealtime)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[RainLoginViewModel::class.java]
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        fun text(value: String): TextView = TextView(this).apply {
            text = value
            textSize = 16f
            setPadding(0, padding / 2, 0, padding / 2)
            content.addView(this)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value
            setOnClickListener { action() }
            content.addView(this)
        }
        text("课堂 · 登录与监听")
        text("登录后选择当前课堂，点击入课并监听。普通课堂直接尝试入课；若教师启用动态二维码签到，须先扫描教师当前展示的二维码。入课操作可能记录签到。")
        val status = text("正在检查本地登录…")
        val account = text("")
        backgroundStatus = text("")
        button("检查本 App 后台运行设置") { openBackgroundSettings() }
        val login = button("生成微信授权二维码") { beginLogin() }
        val qr = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            adjustViewBounds = true
            contentDescription = "雨课堂登录二维码"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (280 * resources.displayMetrics.density).toInt())
            visibility = View.GONE
        }
        content.addView(qr)
        val save = button("保存本次二维码到相册") { model.saveQr() }
        val wechat = button("打开微信，扫一扫 → 相册") { openWechat() }
        val cancel = button("取消本次操作") { model.cancelLogin() }
        val refresh = button("校验登录并刷新当前课堂") { model.refresh() }
        val lessons = text("")
        val lessonActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(lessonActions)
        val monitorStatus = text("")
        val scanInWechat = button("去微信扫码，返回后继续监听") { openClassroomScan() }
        val continueAfterScan = button("已完成课堂扫码，继续监听") {
            MonitorState.current().scanRequiredLessonId?.let { startMonitor(it) }
        }
        val stopMonitor = button("停止课堂监听") {
            stopService(Intent(this, LessonMonitorService::class.java))
        }
        button("最近题目 · 题干、选项与原图") {
            startActivity(Intent(this, cn.rainnotify.app.question.QuestionActivity::class.java))
        }
        button("打开微信查看课堂") { openWechat() }
        text("每次只监听一节课，最长 4 小时。后台状态会显示在常驻通知中。断线期间可能漏报；进程被系统结束后需手动重启。当前同一道题重复发布只提醒一次，待实课核对事件标识后改进。")
        text("二维码只用于本次授权，请勿转发。通过上方按钮保存的副本，会在本次流程结束或下次打开登录页时尝试删除。登录会话加密保存在本机，诊断报告不包含姓名、二维码票据或 Cookie。")
        val logout = button("退出辅助 App 登录") {
            AlertDialog.Builder(this).setTitle("清除本 App 登录")
                .setMessage("清除本机保存的辅助 App 会话，之后可重新授权。")
                .setPositiveButton("清除") { _, _ -> clearScanContinuation(); model.logout() }
                .setNegativeButton("取消", null).show()
        }
        button("返回首页") { finish() }
        fun renderControls() {
            val state = model.state.value ?: return
            val monitoring = MonitorState.current()
            login.isEnabled = !state.busy && !monitoring.active
            login.text = if (state.authenticated) "重新登录 / 切换账号" else "生成微信授权二维码"
            logout.isEnabled = !state.busy && !monitoring.active
            logout.visibility = if (state.authenticated) View.VISIBLE else View.GONE
            stopMonitor.isEnabled = monitoring.active
            monitorStatus.text = monitoring.message + "\n本次识别新题：" + monitoring.questionCount
            val needsScan = !monitoring.active && monitoring.scanRequiredLessonId != null && state.authenticated
            scanInWechat.visibility = if (needsScan) View.VISIBLE else View.GONE
            continueAfterScan.visibility = scanInWechat.visibility
            scanInWechat.isEnabled = !state.busy
            continueAfterScan.isEnabled = !state.busy
            lessonActions.removeAllViews()
            if (state.authenticated && !state.busy) {
                state.lessons.forEach { lesson ->
                    lessonActions.addView(Button(this).apply {
                        text = "入课并监听：" + lesson.classroomName
                        isEnabled = !monitoring.active
                        setOnClickListener { confirmEnter(lesson) }
                    })
                }
            }
        }
        model.state.observe(this) { state ->
            status.text = state.message
            account.text = if (state.authenticated) "账号：" + (state.displayName ?: "登录已验证，姓名未返回") else ""
            qr.setImageBitmap(state.qr)
            qr.visibility = if (state.qr == null) View.GONE else View.VISIBLE
            save.visibility = qr.visibility
            save.isEnabled = !state.qrSaved
            wechat.visibility = qr.visibility
            refresh.isEnabled = !state.busy
            refresh.visibility = if (state.authenticated) View.VISIBLE else View.GONE
            cancel.visibility = if (state.busy) View.VISIBLE else View.GONE
            lessons.text = if (state.lessons.isEmpty()) "" else
                "正在上课：\n" + state.lessons.joinToString("\n") { "• " + it.classroomName }
            renderControls()
        }
        MonitorState.state.observe(this) { renderControls() }
        setContentView(ScrollView(this).apply { addView(content) })
        content.requestApplyInsets()
    }

    private fun confirmEnter(lesson: LessonSummary) {
        if (MonitorState.current().active) return
        if (!NotificationReadiness.read(this).canPost) {
            AlertDialog.Builder(this).setTitle("请先开启题目通知")
                .setMessage("开始监听前，需要允许系统通知和题目提醒渠道。")
                .setPositiveButton("去设置") { _, _ -> startActivity(Intent(this, NotificationSettingsActivity::class.java)) }
                .setNegativeButton("稍后", null).show()
            return
        }
        val batteryNote = if (!isBatteryExempt())
            "\n\n当前未开启系统省电优化豁免，建议先检查本 App 后台设置；熄屏监听可能受限。" else ""
        AlertDialog.Builder(this).setTitle("进入课堂并开始监听")
            .setMessage("课堂：" + lesson.classroomName +
                "\n\n将尝试进入这节课并取得监听令牌，可能记录签到。普通课堂不要求先在微信入课；如果服务器要求动态二维码签到，会显示扫码入口。已扫码的账号可直接尝试连接。" +
                "\n\n监听会获取新题的题干、选项和图片，保留最近 20 题。AI 作答及自动提交需另外开启。" +
                "\n\n入课请求送出后，即使停止监听，也无法撤销服务端已记录的签到。" + batteryNote)
            .setPositiveButton("进入课堂并监听") { _, _ -> startMonitor(lesson.id) }
            .setNegativeButton("取消", null).show()
    }

    private fun startMonitor(lessonId: String) {
        if (MonitorState.current().active || model.state.value?.busy == true || model.state.value?.authenticated != true) return
        scanReturn.cancel()
        if (!NotificationReadiness.read(this).canPost) {
            toast("请先开启系统通知和题目提醒渠道。"); return
        }
        MonitorState.update(MonitorUiState(active = true, message = "正在启动课堂监听…"))
        try {
            startForegroundService(Intent(this, LessonMonitorService::class.java)
                .setAction(LessonMonitorService.START).putExtra(LessonMonitorService.LESSON_ID, lessonId))
        } catch (error: RuntimeException) {
            MonitorState.update(MonitorUiState(message = "系统未允许启动监听，请保持本页可见后重试。"))
            Diagnostics.record(this, "monitor_service_start_failed", mapOf("error_type" to error.javaClass.simpleName))
        }
    }

    private fun openClassroomScan() {
        val monitoring = MonitorState.current()
        val lessonId = monitoring.scanRequiredLessonId ?: return
        if (monitoring.active || model.state.value?.busy == true) return
        val launch = packageManager.getLaunchIntentForPackage("com.tencent.mm")
        if (launch == null) { toast("请手动打开微信扫描课堂二维码，完成后返回并点击继续监听。"); return }
        // This labelled user action authorizes one entry attempt on return, never a polling loop.
        scanReturn.arm(lessonId)
        try { startActivity(launch) }
        catch (_: RuntimeException) {
            scanReturn.cancel()
            toast("无法打开微信，请手动扫码后返回并点击继续监听。")
        }
    }

    private fun clearScanContinuation() {
        scanReturn.cancel()
        if (!MonitorState.current().active) MonitorState.update(MonitorUiState())
    }

    private fun openWechat() {
        val intent = packageManager.getLaunchIntentForPackage("com.tencent.mm")
        if (intent == null) { toast("请手动打开微信，再用扫一扫从相册选择二维码。"); return }
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { toast("无法直接打开微信，请手动切换。") }
    }

    private fun isBatteryExempt() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun beginLogin() {
        clearScanContinuation()
        // A verified Xiaomi failure condition, not a universal Android permission requirement.
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) && !isBatteryExempt()) {
            AlertDialog.Builder(this).setTitle("先检查后台运行条件")
                .setMessage("当前系统省电优化豁免未开启。本机实测中，这种状态下切到微信曾中断登录连接；设置为无限制后登录成功。建议先检查本 App 省电策略。")
                .setPositiveButton("打开设置") { _, _ -> openBackgroundSettings() }
                .setNeutralButton("仍继续授权") { _, _ -> model.startLogin() }
                .setNegativeButton("取消", null).show()
        } else {
            model.startLogin()
        }
    }

    private fun openBackgroundSettings() {
        AlertDialog.Builder(this).setTitle("本 App 后台运行设置")
            .setMessage("在本 App 设置中检查应用省电策略，按需设为无限制；也可在系统电池优化列表中选择本 App 不优化。设置后返回此页，状态会更新。放宽限制可能增加耗电。")
            .setPositiveButton("本 App 设置") { _, _ ->
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName)))
            }
            .setNeutralButton("系统电池优化列表") { _, _ ->
                openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            .setNegativeButton("取消", null).show()
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: RuntimeException) { toast("请手动在系统设置中查找雨课堂提醒·通知验证。") }
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onResume() {
        super.onResume()
        Diagnostics.appForeground = true
        Diagnostics.record(this, "auth_ui_resumed")
        backgroundStatus.text = "系统省电优化豁免：" + (if (isBatteryExempt()) "已开启" else "未开启") +
            "。厂商后台策略仍需手动确认；切换到微信时请保持网络可用。"
        val state = MonitorState.current()
        scanReturn.consume(state.scanRequiredLessonId, !state.active &&
            model.state.value?.authenticated == true && model.state.value?.busy == false)?.let { startMonitor(it) }
    }
    override fun onPause() {
        scanReturn.leftApp()
        Diagnostics.appForeground = false
        Diagnostics.record(this, "auth_ui_paused")
        super.onPause()
    }
}
