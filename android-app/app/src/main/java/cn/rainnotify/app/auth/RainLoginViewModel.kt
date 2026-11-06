package cn.rainnotify.app.auth

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.rainnotify.app.Diagnostics
import cn.rainnotify.app.answer.AnswerState
import cn.rainnotify.app.answer.AutoAnswer
import cn.rainnotify.app.monitor.MonitorState
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class LoginUiState(
    val message: String = "正在检查本地登录…",
    val busy: Boolean = true,
    val hasSession: Boolean = false,
    val authenticated: Boolean = false,
    val displayName: String? = null,
    val lessons: List<LessonSummary> = emptyList(),
    val qr: Bitmap? = null,
    val qrSaved: Boolean = false,
    val sessionExpired: Boolean = false,
    val server: RainServer? = null,
)

class RainLoginViewModel(application: Application) : AndroidViewModel(application) {
    private val gate = AttemptGate()
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var selectedServer = ServerSettings.selected(application)
    private val gallery = QrGallery(application)
    private val mutableState = MutableLiveData(LoginUiState())
    val state: LiveData<LoginUiState> = mutableState
    @Volatile private var latest = LoginUiState()
    @Volatile private var api: RainApi? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var timeout: ScheduledFuture<*>? = null
    @Volatile private var disposed = false

    init { refresh() }

    private fun publish(token: Long, value: LoginUiState) {
        gate.runIfCurrent(token) {
            latest = value.copy(server = selectedServer)
            mutableState.postValue(latest)
        }
    }

    private fun begin(value: LoginUiState): Long {
        val token = gate.begin()
        stopNetwork()
        publish(token, value)
        return token
    }

    private fun stopNetwork() {
        timeout?.cancel(false)
        timeout = null
        socket?.cancel()
        socket = null
        api?.cancel()
    }

    private fun createApi(token: Long, server: RainServer, cookies: RainCookieJar): RainApi =
        RainApi(server, cookies, report = { kind, fields ->
            if (gate.isCurrent(token)) Diagnostics.record(getApplication(), kind, fields + ("rain_server" to server.id))
        }).also { candidate ->
            if (!gate.runIfCurrent(token) { api = candidate }) candidate.cancel()
        }

    private fun cleanupQr() {
        if (!gallery.removeOwnedCopy()) {
            Diagnostics.record(getApplication(), "qr_cleanup_failed", mapOf("protocol_stage" to "qr_cleanup"))
        }
    }

    fun selectServer(server: RainServer): Boolean {
        if (selectedServer == server) return false
        if (!ServerSwitchPolicy.allowed(MonitorState.current().active, MonitorState.automaticRequested,
                cn.rainnotify.app.answer.AnswerService.running || AnswerState.task.value?.active == true, AutoAnswer.ticket != null)) {
            publish(gate.current(), latest.copy(message = "请先关闭课堂监听，并等待当前作答结束，再切换服务器。"))
            return false
        }
        gate.begin() // Invalidate old QR callbacks before changing the credential destination.
        stopNetwork()
        selectedServer = server
        ServerSettings.select(getApplication(), server)
        Diagnostics.record(getApplication(), "server_selected", mapOf("rain_server" to server.id))
        refresh()
        return true
    }

    fun refresh() {
        val server = selectedServer
        if (server == null) {
            val token = begin(LoginUiState(message = "请先选择学校使用的服务器，再登录账号。", busy = false))
            worker.execute { if (gate.isCurrent(token)) cleanupQr() }
            return
        }
        val token = begin(LoginUiState(message = "正在校验登录并读取当前课堂…", hasSession = latest.hasSession))
        worker.execute {
            cleanupQr()
            if (!gate.isCurrent(token)) return@execute
            try {
                val cookies = SessionVault(getApplication(), server).read()
                if (cookies.isEmpty()) {
                    publish(token, LoginUiState(message = "尚未登录${server.label}。点击下方按钮生成授权二维码。", busy = false))
                    return@execute
                }
                publish(token, latest.copy(hasSession = true))
                val jar = RainCookieJar(server.origin).apply { restore(cookies) }
                val client = createApi(token, server, jar)
                completeSession(token, client, false)
            } catch (error: Exception) { fail(token, safeFailure(error, "session_restore")) }
        }
    }

    fun startLogin() {
        val server = selectedServer ?: run { refresh(); return }
        val token = begin(LoginUiState(message = "正在连接${server.label}，获取授权二维码…"))
        worker.execute {
            cleanupQr()
            if (!gate.isCurrent(token)) return@execute
            val client = createApi(token, server, RainCookieJar(server.origin))
            val grantClaimed = AtomicBoolean(false)
            val qrClaimed = AtomicBoolean(false)
            gate.runIfCurrent(token) {
                timeout = timer.schedule({
                    fail(token, RainFailure(FailureReason.TIMEOUT, "login_socket"))
                }, 120, TimeUnit.SECONDS)
                socket = client.client.newWebSocket(Request.Builder().url(server.socket).build(),
                    object : RainSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (!gate.isCurrent(token)) { webSocket.cancel(); return }
                            Diagnostics.record(getApplication(), "login_socket_open")
                            webSocket.send(RainProtocol.loginRequest())
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (!gate.isCurrent(token) || grantClaimed.get()) return
                            try {
                                when (val message = RainProtocol.loginMessage(text, server)) {
                                    is LoginMessage.Qr -> if (qrClaimed.compareAndSet(false, true)) {
                                        Diagnostics.record(getApplication(), "login_message", mapOf("login_message_kind" to "qr"))
                                        worker.execute {
                                            if (!gate.isCurrent(token) || grantClaimed.get()) return@execute
                                            try {
                                                val bytes = client.image(message.imageUrl)
                                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                                if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) {
                                                    throw RainFailure(FailureReason.QR_IMAGE, "qr_image")
                                                }
                                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                    ?: throw RainFailure(FailureReason.QR_IMAGE, "qr_image")
                                                if (!grantClaimed.get()) publish(token, LoginUiState(
                                                    message = "请保存二维码到相册，再打开微信扫一扫，从相册选择二维码并确认授权。本次最多等待 2 分钟。",
                                                    qr = bitmap))
                                            } catch (error: Exception) { fail(token, safeFailure(error, "qr_image")) }
                                        }
                                    }
                                    is LoginMessage.Grant -> if (grantClaimed.compareAndSet(false, true)) {
                                        timeout?.cancel(false)
                                        webSocket.close(1000, "Authorization received")
                                        publish(token, LoginUiState(message = "微信授权已返回，正在验证并保存登录…"))
                                        worker.execute {
                                            if (!gate.isCurrent(token)) return@execute
                                            try {
                                                client.exchange(message)
                                                completeSession(token, client, true)
                                            } catch (error: Exception) { fail(token, safeFailure(error, "login_exchange")) }
                                        }
                                    }
                                    LoginMessage.Waiting -> Diagnostics.record(getApplication(), "login_message",
                                        mapOf("login_message_kind" to "waiting"))
                                }
                            } catch (error: Exception) { fail(token, safeFailure(error, "login_socket")) }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            if (gate.isCurrent(token)) Diagnostics.record(getApplication(), "login_socket_closing",
                                mapOf("websocket_close_code" to code))
                            webSocket.close(code, null)
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (!grantClaimed.get()) fail(token, RainFailure(FailureReason.CONNECTION_CLOSED, "login_socket"))
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (gate.isCurrent(token)) Diagnostics.record(getApplication(), "login_transport_failed",
                                mapOf("transport_error" to t.javaClass.simpleName.take(80)))
                            if (!grantClaimed.get()) fail(token,
                                RainFailure(FailureReason.NETWORK, "login_socket", response?.code))
                        }
                    })
            }
        }
    }

    private fun completeSession(token: Long, client: RainApi, newLogin: Boolean) {
        if (!gate.isCurrent(token)) return
        client.validateSession()
        if (!gate.runIfCurrent(token) {
                SessionVault(getApplication(), client.server).write(client.cookies.snapshot())
                Diagnostics.record(getApplication(), if (newLogin) "login_verified" else "session_restored",
                    mapOf("authenticated" to true, "session_saved" to true))
                publish(token, LoginUiState(message = "登录有效，正在读取当前课堂…",
                    hasSession = true, authenticated = true))
            }) return
        cleanupQr()
        val name = client.displayName()
        if (!gate.isCurrent(token)) return
        publish(token, latest.copy(displayName = name))
        val lessons = client.classrooms()
        gate.runIfCurrent(token) {
            SessionVault(getApplication(), client.server).write(client.cookies.snapshot())
            Diagnostics.record(getApplication(), "classrooms_loaded", mapOf("lesson_count" to lessons.size))
            publish(token, LoginUiState(
                message = if (lessons.isEmpty()) "登录有效，当前没有正在上课的课堂。可在上课时刷新。"
                    else "已读取正在上课的课堂。可手动选择一节课进入监听。",
                busy = false, hasSession = true, authenticated = true, displayName = name, lessons = lessons))
        }
    }

    fun saveQr() {
        val token = gate.current()
        val bitmap = latest.qr ?: return
        worker.execute {
            try {
                gate.runIfCurrent(token) {
                    if (latest.qr !== bitmap) return@runIfCurrent
                    gallery.save(bitmap)
                    publish(token, latest.copy(qrSaved = true, message = "二维码已保存。请打开微信扫一扫，从相册选择最新二维码并确认授权，然后返回本 App。"))
                }
            } catch (error: Exception) { fail(token, safeFailure(error, "qr_save")) }
        }
    }

    fun cancelLogin() {
        val token = begin(LoginUiState(message = "本次操作已取消。可重新登录或检查已保存会话。", busy = false,
            hasSession = latest.hasSession))
        Diagnostics.record(getApplication(), "login_cancelled")
        worker.execute { if (gate.isCurrent(token)) cleanupQr() }
    }

    fun logout() {
        val server = selectedServer ?: return
        val token = begin(LoginUiState(message = "正在清除本 App 登录…"))
        worker.execute {
            try {
                // Logout targets the captured server even if the user switches while this worker is queued.
                // This worker also serializes login persistence, so no later login can be erased here.
                SessionVault(getApplication(), server).clear()
                gate.runIfCurrent(token) {
                    cleanupQr()
                    api = null
                    Diagnostics.record(getApplication(), "session_cleared")
                    publish(token, LoginUiState(message = "本 App 登录已清除。", busy = false))
                }
            } catch (error: Exception) { fail(token, safeFailure(error, "session_clear")) }
        }
    }

    private fun safeFailure(error: Exception, stage: String) =
        error as? RainFailure ?: RainFailure(FailureReason.RESPONSE_FORMAT, stage)

    private fun fail(token: Long, failure: RainFailure) {
        gate.runIfCurrent(token) {
            val next = gate.begin() // Reject credentials arriving after cancellation, timeout or failure.
            stopNetwork()
            val fields = mutableMapOf<String, Any>("protocol_stage" to failure.stage, "error_type" to failure.reason.name)
            failure.httpStatus?.let { fields["http_status"] = it }
            failure.businessCode?.let { fields["business_code"] = it }
            Diagnostics.record(getApplication(), "auth_operation_failed", fields)
            publish(next, latest.copy(message = failure.userMessage(), busy = false, qr = null, qrSaved = false,
                sessionExpired = failure.reason == FailureReason.SESSION_EXPIRED,
                authenticated = latest.authenticated && failure.reason != FailureReason.SESSION_EXPIRED))
            if (!disposed) worker.execute { if (gate.isCurrent(next)) cleanupQr() }
        }
    }

    override fun onCleared() {
        disposed = true
        gate.begin()
        stopNetwork()
        timer.shutdownNow()
        worker.execute { cleanupQr() }
        worker.shutdown()
        super.onCleared()
    }
}
