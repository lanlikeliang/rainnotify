package cn.rainnotify.app.monitor

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import cn.rainnotify.app.answer.AnswerProtocol
import cn.rainnotify.app.answer.AutoAnswer
import cn.rainnotify.app.Diagnostics
import cn.rainnotify.app.NotificationReadiness
import cn.rainnotify.app.ProbeNotifications
import cn.rainnotify.app.auth.FailureReason
import cn.rainnotify.app.auth.RainApi
import cn.rainnotify.app.auth.RainCookieJar
import cn.rainnotify.app.auth.RainFailure
import cn.rainnotify.app.auth.SessionVault
import cn.rainnotify.app.question.QuestionRepository
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LessonMonitorService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val alive = AtomicBoolean(true)
    @Volatile private var api: RainApi? = null
    private var connection: LessonConnection? = null
    private var begun = false
    private var network: Network? = null
    private var validated = false
    private var blocked = false
    private var callbackRegistered = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(next: Network) {
            network = next
            validated = false
            blocked = false
            connection?.networkAvailable(false)
        }
        override fun onCapabilitiesChanged(next: Network, capabilities: NetworkCapabilities) {
            if (network != next) return
            val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated != available) {
                validated = available
                updateNetwork()
            }
        }
        override fun onBlockedStatusChanged(next: Network, value: Boolean) {
            if (network != next) return
            if (blocked != value) { blocked = value; updateNetwork() }
        }
        override fun onLost(lost: Network) {
            if (network != lost) return
            network = null
            validated = false
            updateNetwork()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProbeNotifications.createChannels(this)
        startForeground(MonitorNotifications.STATUS_ID,
            MonitorNotifications.status(this, "正在准备课堂监听…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        MonitorState.update(MonitorUiState(active = true, message = "正在准备课堂监听…"))
        // Android delivers these callbacks on the supplied main handler, in order.
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback, main)
            callbackRegistered = true
        } catch (_: RuntimeException) {
            finish("无法读取网络状态，监听已停止。")
        }
        main.postDelayed({ if (alive.get()) finish("本次监听已达到 4 小时上限，请按需重新进入课堂。") }, 4 * 60 * 60_000L)
        Diagnostics.record(this, "monitor_service_started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish("已停止课堂监听。"); return START_NOT_STICKY }
        if (begun || !alive.get()) return START_NOT_STICKY
        val lessonId = intent?.getStringExtra(LESSON_ID)
        if (intent?.action != START || LessonProtocol.id(lessonId) == null) {
            finish("没有选择课堂，监听未启动。")
            return START_NOT_STICKY
        }
        if (!NotificationReadiness.read(this).canPost) {
            finish("请先在通知验证页开启系统通知和题目提醒渠道。")
            return START_NOT_STICKY
        }
        begun = true
        update("正在校验所选课堂并进入，可能记录签到…")
        Diagnostics.record(this, "monitor_manual_start")
        worker.execute {
            try {
                val cookies = SessionVault(this).read()
                if (cookies.isEmpty()) throw RainFailure(FailureReason.SESSION_EXPIRED, "monitor_session")
                val candidate = RainApi(RainCookieJar().apply { restore(cookies) }, report = { kind, fields ->
                    if (alive.get()) Diagnostics.record(this, kind, fields)
                })
                api = candidate
                if (!alive.get()) { candidate.cancel(); return@execute }
                candidate.validateSession()
                if (!alive.get()) return@execute
                if (candidate.classrooms().none { it.id == lessonId }) {
                    throw RainFailure(FailureReason.LESSON_ENDED, "monitor_classroom")
                }
                if (!alive.get()) return@execute
                // Exactly one explicit attempt. Cancellation cannot undo a request already received by the server.
                val credentials = candidate.enterLesson(lessonId!!)
                if (!alive.get()) return@execute
                main.post {
                    if (!alive.get()) return@post
                    Diagnostics.record(this, "monitor_credentials_received")
                    connection = LessonConnection(candidate.client, credentials, observer, clock = SystemClock::elapsedRealtime).also {
                        QuestionRepository.get(this).attach(lessonId, candidate, it,
                            AnswerProtocol.sha256(cookies.sorted().joinToString("\n")))
                        it.networkAvailable(validated && !blocked)
                        it.start()
                    }
                }
            } catch (error: Exception) {
                val failure = error as? RainFailure
                main.post {
                    if (!alive.get()) return@post
                    Diagnostics.record(this, "monitor_start_failed", mapOf(
                        "error_type" to (failure?.reason?.name ?: "STORAGE"),
                        "protocol_stage" to (failure?.stage ?: "monitor_session")) +
                        (failure?.httpStatus?.let { mapOf("http_status" to it) } ?: emptyMap()) +
                        (failure?.businessCode?.let { mapOf("business_code" to it) } ?: emptyMap()))
                    if (failure?.requiresClassroomScan == true) {
                        finish(failure.userMessage(), scanRequiredLessonId = lessonId)
                    } else {
                        finish((failure?.userMessage() ?: "无法读取本地会话，请重新检查登录。") +
                            " 已停止；若入课请求已送达，仍可能记录签到。")
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private val observer = object : LessonObserver {
        override fun state(state: ConnectionState) { main.post {
            if (!alive.get()) return@post
            when (state) {
                ConnectionState.CONNECTING -> update("正在连接课堂，等待服务器确认…")
                ConnectionState.LISTENING -> update("课堂连接已确认，正在监听新题。")
                ConnectionState.RETRYING -> update("连接中断，正在重连；中断期间可能漏报。")
                ConnectionState.OFFLINE -> update("网络不可用或后台联网受限，等待恢复；期间可能漏报。")
                ConnectionState.DEGRADED -> update("部分课堂消息格式无法识别，可能漏报；请同时留意微信。")
                ConnectionState.ENDED -> finish("已收到下课消息，课堂监听结束。")
                ConnectionState.FAILED -> finish("课堂连接未能继续或协议不匹配，监听已停止。请检查登录后手动重试。")
            }
        } }
        override fun signal(signal: LessonSignal) { main.post {
            if (!alive.get()) return@post
            when (signal) {
                is LessonSignal.Question -> {
                    val questionId = QuestionRepository.get(this@LessonMonitorService).capture(signal.reference)
                    val submitted = MonitorNotifications.question(this@LessonMonitorService, questionId)
                    AutoAnswer.onQuestion(this@LessonMonitorService, questionId)
                    val state = MonitorState.current()
                    MonitorState.update(state.copy(questionCount = state.questionCount + 1))
                    if (!submitted) update("收到新题，但通知未能提交；请检查系统通知设置。")
                }
                LessonSignal.Duplicate -> Diagnostics.record(this@LessonMonitorService, "monitor_duplicate_ignored")
                LessonSignal.Extension -> Diagnostics.record(this@LessonMonitorService, "monitor_extension_ignored")
                LessonSignal.Snapshot -> Diagnostics.record(this@LessonMonitorService, "monitor_snapshot_ignored")
                else -> Unit
            }
        } }
        override fun diagnostic(kind: String, fields: Map<String, Any>) {
            if (alive.get()) Diagnostics.record(this@LessonMonitorService, kind, fields)
        }
    }

    private fun updateNetwork() {
        val available = validated && !blocked
        Diagnostics.record(this, "monitor_network_state", mapOf("network_available" to available))
        connection?.networkAvailable(available)
    }

    private fun update(message: String) {
        MonitorState.update(MonitorState.current().copy(active = true, message = message))
        try {
            getSystemService(NotificationManager::class.java).notify(MonitorNotifications.STATUS_ID,
                MonitorNotifications.status(this, message))
        } catch (_: RuntimeException) {
            Diagnostics.record(this, "monitor_status_notification_failed")
        }
    }

    private fun finish(message: String, scanRequiredLessonId: String? = null) {
        if (!alive.compareAndSet(true, false)) return
        AutoAnswer.disarm(this)
        QuestionRepository.get(this).detach(api)
        connection?.close()
        api?.cancel()
        MonitorState.update(MonitorState.current().copy(active = false, message = message,
            scanRequiredLessonId = scanRequiredLessonId))
        stopSelf()
    }

    override fun onDestroy() {
        AutoAnswer.disarm(this)
        alive.set(false)
        QuestionRepository.get(this).detach(api)
        main.removeCallbacksAndMessages(null)
        if (callbackRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        connection?.close(); connection = null
        api?.cancel(); api = null
        worker.shutdownNow()
        if (MonitorState.current().active) {
            MonitorState.update(MonitorState.current().copy(active = false, message = "监听已停止，需要时请手动重新开始。"))
        }
        Diagnostics.record(this, "monitor_service_stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "cn.rainnotify.app.START_LESSON"
        const val STOP = "cn.rainnotify.app.STOP_LESSON"
        const val LESSON_ID = "lesson_id"
    }
}
