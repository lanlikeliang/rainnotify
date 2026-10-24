package cn.rainnotify.app

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class NotificationSettingsActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private val testButtons = mutableListOf<Button>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshAfterCooldown = Runnable { refreshStatus() }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Diagnostics.record(this, "notification_permission_result",
            mapOf("runtime_notification_permission" to granted))
        refreshStatus()
        toast(if (granted) "通知已授权；请继续检查题目渠道和系统提醒设置"
            else "尚未授权通知；你仍可查看诊断或打开系统设置")
    }
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    Diagnostics.reportFile(this).inputStream().use { it.copyTo(output) }
                } ?: error("Cannot open export destination")
                toast("诊断报告已导出")
            } catch (error: Exception) {
                Diagnostics.record(this, "export_failed", mapOf("error_type" to error.javaClass.simpleName))
                toast("导出失败，请重试")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeNotifications.createChannels(this)
        Diagnostics.recordDevice(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val padding = (16 * resources.displayMetrics.density).toInt()
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        fun text(value: String) = content.addView(TextView(this).apply {
            text = value
            textSize = 16f
            setPadding(0, padding, 0, padding)
        })
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
            content.addView(this)
        }
        text("通知、声音与测试")
        button("返回设置") { finish() }
        statusText = TextView(this).apply { textSize = 16f }
        content.addView(statusText)
        button("1. 启用系统通知") { requestNotificationPermission() }
        button("2. 题目提醒设置（声音、振动、提醒级别）") { openChannelSettings() }
        button("3. 本 App 通知设置（悬浮与锁屏）") { openAppNotificationSettings() }
        text("题目渠道已创建后，声音、振动等行为由系统设置控制。横幅、锁屏展示和自动亮屏仍由手机系统决定，授权成功不能代替实际观察。")
        button("4. 本 App 后台与省电设置") {
            AlertDialog.Builder(this).setTitle("检查锁屏运行条件")
                .setMessage("请允许本 App 后台自启动，并将应用省电策略设为无限制。小米/澎湃 OS 未允许自启动时，退出后台可能导致 AI 作答辅助断开或需要重新开启。AI App 无需配置自启动；本 App 如有启动其他应用、后台弹出界面等选项，请允许。放宽后台限制可能增加耗电，设置后仍需实测。")
                .setPositiveButton("打开本 App 设置") { _, _ -> openAppDetails() }
                .setNeutralButton("系统电池优化列表") { _, _ ->
                    openSettings("battery_optimization", Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                .setNegativeButton("稍后", null).show()
        }
        testButtons += button("立即发送一次模拟通知") { startTest(false) }
        testButtons += button("30 秒后测试（随后切换 App 或锁屏）") { startTest(true) }
        text("两次测试至少间隔 2 分钟，避免重复提醒受到系统通知冷却影响。延时测试短暂申请保持 CPU 运行，最长 75 秒；系统可能限制它。这不能证明长期休眠时的网络监听可靠。")
        button("停止通知验证") { stopService(Intent(this, NotificationProbeService::class.java)) }
        button("导出本 App 诊断报告") { exportLauncher.launch("rainnotify-diagnostics.jsonl") }
        setContentView(ScrollView(this).apply { addView(content) })
        content.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        Diagnostics.appForeground = true
        Diagnostics.record(this, "ui_resumed")
        Diagnostics.record(this, "notification_configuration", NotificationReadiness.read(this).diagnosticFields())
        refreshStatus()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(refreshAfterCooldown)
        Diagnostics.appForeground = false
        Diagnostics.record(this, "ui_paused")
        super.onPause()
    }

    private fun requestNotificationPermission() {
        val state = NotificationReadiness.read(this)
        if (state.runtimeGranted) {
            if (!state.appEnabled) openAppNotificationSettings()
            else { refreshStatus(); toast("通知已授权；请继续检查渠道和悬浮／锁屏设置") }
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            AlertDialog.Builder(this).setTitle("允许课堂题目提醒")
                .setMessage("系统通知用于提示新题，并显示监听状态。你可取消授权，继续查看诊断；以后也可在系统设置开启。")
                .setPositiveButton("继续授权") { _, _ -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .setNegativeButton("暂不授权", null).show()
        } else {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startTest(delayed: Boolean) {
        val state = NotificationReadiness.read(this)
        if (!state.runtimeGranted) { requestNotificationPermission(); return }
        if (!state.appEnabled) { openAppNotificationSettings(); return }
        if (!state.canPost) { openChannelSettings(); return }
        if (!SimulationGate.reserve(this)) {
            Diagnostics.record(this, "simulation_test_throttled", mapOf("remaining_ms" to SimulationGate.remaining(this)))
            refreshStatus()
            return
        }
        if (delayed) {
            try {
                startForegroundService(Intent(this, NotificationProbeService::class.java)
                    .setAction(NotificationProbeService.ACTION_DELAYED))
                toast("已安排一次测试，请锁屏或切换 App，50 秒后再查看")
            } catch (error: RuntimeException) {
                Diagnostics.record(this, "service_start_failed", mapOf("error_type" to error.javaClass.simpleName))
                toast("系统未允许启动测试服务，请检查后台设置")
            }
        } else { ProbeNotifications.simulate(this) }
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        uiHandler.removeCallbacks(refreshAfterCooldown)
        val state = NotificationReadiness.read(this)
        val importance = when {
            !state.channelExists -> "未创建"
            state.importance >= NotificationManager.IMPORTANCE_HIGH -> "高（横幅仍由系统控制）"
            state.importance == NotificationManager.IMPORTANCE_DEFAULT -> "默认"
            state.importance > 0 -> "低"
            else -> "关闭"
        }
        val ringer = when (state.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "正常"
            AudioManager.RINGER_MODE_VIBRATE -> "振动"
            else -> "静音"
        }
        val interruption = when (state.interruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "允许全部提醒"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅优先提醒"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "禁止提醒"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "仅闹钟"
            else -> "未知"
        }
        val wait = SimulationGate.remaining(this)
        val lines = listOf(
            "通知运行时授权：${if (state.runtimeGranted) "已允许" else "未允许"}",
            "App 通知总开关：${if (state.appEnabled) "已开启" else "已关闭"}",
            "题目渠道：$importance；声音：${if (state.soundConfigured) "已配置" else "未配置"}；振动：${if (state.vibrationEnabled) "已配置" else "未配置"}",
            "响铃模式：$ringer；通知音量：${state.volume}；勿扰：$interruption",
            "系统省电优化豁免：${if (state.batteryExempt) "已开启" else "未开启"}",
            "系统后台限制：${if (state.backgroundRestricted) "已检测到" else "未检测到"}；厂商后台策略需手动确认",
            if (wait > 0) "下一次测试：${(wait + 999) / 1000} 秒后可用" else "可开始一次通知测试",
        )
        statusText.text = lines.joinToString("\n")
        testButtons.forEach { it.isEnabled = wait == 0L }
        if (wait > 0) uiHandler.postDelayed(refreshAfterCooldown, minOf(wait, 1_000L))
    }

    private fun openAppNotificationSettings() = openSettings("app_notifications",
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

    private fun openChannelSettings() = openSettings("question_channel",
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ProbeNotifications.QUESTION_CHANNEL))

    private fun openAppDetails() = openSettings("app_details",
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun openSettings(target: String, intent: Intent) {
        try {
            startActivity(intent)
            Diagnostics.record(this, "settings_opened", mapOf("settings_target" to target))
        } catch (error: RuntimeException) {
            Diagnostics.record(this, "settings_open_failed",
                mapOf("settings_target" to target, "error_type" to error.javaClass.simpleName))
            if (target != "app_details") openAppDetails()
            else toast("无法打开设置，请在系统设置中查找本 App")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
