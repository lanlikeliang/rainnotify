package cn.rainnotify.app.monitor

import cn.rainnotify.app.auth.RainFailure
import cn.rainnotify.app.auth.RainProtocol
import cn.rainnotify.app.auth.RainSocketListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import cn.rainnotify.app.answer.AnswerFailure
import kotlin.random.Random

enum class ConnectionState { CONNECTING, LISTENING, RETRYING, OFFLINE, DEGRADED, ENDED, FAILED }

interface LessonObserver {
    fun state(state: ConnectionState)
    fun signal(signal: LessonSignal)
    fun diagnostic(kind: String, fields: Map<String, Any> = emptyMap())
}

/** All socket transitions are serialized. Credentials are obtained outside this class:
 * reconnecting cannot call checkin, persist secrets, or restore a stopped session.
 */
class LessonConnection(
    private val client: OkHttpClient,
    private val credentials: LessonCredentials,
    private val observer: LessonObserver,
    private val url: String = RainProtocol.LOGIN_SOCKET,
    private val retryBaseMs: Long = 2_000,
    private val handshakeMs: Long = 25_000,
    private val stableMs: Long = 60_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private val queue = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    private val events = LessonEvents(credentials.lessonId)
    private var epoch = 0L
    private var socket: WebSocket? = null
    private var retry: ScheduledFuture<*>? = null
    private var handshake: ScheduledFuture<*>? = null
    private var stable: ScheduledFuture<*>? = null
    @Volatile private var online = true
    private var failures = 0
    private var started = false
    @Volatile private var ready = false
    private val closedProblems = ConcurrentHashMap.newKeySet<String>()
    private class TimeRequest(val problem: String, val at: Long, val future: CompletableFuture<ProblemTiming>)
    private val timeRequests = mutableMapOf<String, TimeRequest>()
    private var timeSequence = 0L

    fun usable(problem: String) = !closed.get() && online && ready && problem !in closedProblems

    fun timing(problem: String): CompletableFuture<ProblemTiming> {
        val result = CompletableFuture<ProblemTiming>()
        if (!usable(problem)) {
            result.completeExceptionally(AnswerFailure("课堂连接不可用或本题已收题。"))
            return result
        }
        dispatch {
            if (!usable(problem)) {
                result.completeExceptionally(AnswerFailure("课堂连接不可用或本题已收题。"))
                return@dispatch
            }
            val requestId = (++timeSequence).toString()
            timeRequests[requestId] = TimeRequest(problem, clock(), result)
            val sent = socket?.send(JSONObject().put("op", "probleminfo")
                .put("lessonid", credentials.lessonId).put("problemid", problem)
                .put("msgid", requestId.toLong()).toString()) == true
            if (!sent) timeRequests.remove(requestId)?.future?.completeExceptionally(AnswerFailure("计时请求未送出。"))
            else queue.schedule({
                timeRequests.remove(requestId)?.future?.completeExceptionally(
                    AnswerFailure("未收到可对应本次请求的题目计时；本题未提交。"))
            }, 5, TimeUnit.SECONDS)
        }
        result.orTimeout(6, TimeUnit.SECONDS)
        return result
    }

    fun start() = dispatch {
        if (!started) {
            started = true
            if (online) connect() else observer.state(ConnectionState.OFFLINE)
        }
    }

    fun networkAvailable(available: Boolean, changed: Boolean = false) = dispatch {
        val wasOnline = online
        online = available
        if (!started) return@dispatch
        if (!available) {
            invalidate()
            observer.state(ConnectionState.OFFLINE)
        } else if (!wasOnline || changed) {
            invalidate()
            observer.diagnostic("monitor_network_reconnect")
            connect()
        }
    }

    private fun connect() {
        if (!online || closed.get()) return
        invalidate()
        val attempt = epoch
        observer.state(ConnectionState.CONNECTING)
        observer.diagnostic("monitor_socket_connecting")
        handshake = queue.schedule({ if (current(attempt)) disconnected(attempt, "handshake_timeout") },
            handshakeMs, TimeUnit.MILLISECONDS)
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : RainSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = dispatch {
                if (!current(attempt)) { webSocket.cancel(); return@dispatch }
                if (!webSocket.send(LessonProtocol.hello(credentials))) disconnected(attempt, "send_failed")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = dispatch {
                if (!current(attempt)) return@dispatch
                try {
                    val root = RainProtocol.parseObject(text, "lesson_socket")
                    if (root.optString("op") == "probleminfo") {
                        val id = LessonProtocol.id(root.opt("msgid"))
                        val pending = timeRequests[id]
                        if (pending != null && LessonProtocol.id(root.opt("problemid")) == pending.problem) {
                            timeRequests.remove(id)
                            try { pending.future.complete(ProblemTiming.parse(root, pending.at, clock())) }
                            catch (e: AnswerFailure) { pending.future.completeExceptionally(e) }
                        }
                        return@dispatch
                    }
                    val signal = events.receive(text)
                    when (signal) {
                        LessonSignal.Ready -> {
                            ready = true
                            handshake?.cancel(false)
                            stable?.cancel(false)
                            stable = queue.schedule({ if (current(attempt)) failures = 0 }, stableMs, TimeUnit.MILLISECONDS)
                            observer.state(ConnectionState.LISTENING)
                            observer.diagnostic("monitor_handshake_confirmed")
                        }
                        LessonSignal.Finished -> { terminate(ConnectionState.ENDED); return@dispatch }
                        LessonSignal.Unsupported -> {
                            observer.diagnostic("monitor_schema_unsupported")
                            if (!ready) { terminate(ConnectionState.FAILED); return@dispatch }
                            observer.state(ConnectionState.DEGRADED)
                        }
                        is LessonSignal.ProblemClosed -> {
                            closedProblems.add(signal.problemId)
                            timeRequests.values.filter { it.problem == signal.problemId }.forEach {
                                it.future.completeExceptionally(AnswerFailure("本题已收题；未继续提交。"))
                            }
                        }
                        else -> Unit
                    }
                    observer.signal(signal)
                    if (ready) events.takeRecovered().forEach { observer.signal(it) }
                } catch (_: RainFailure) {
                    observer.diagnostic("monitor_protocol_rejected")
                    terminate(ConnectionState.FAILED)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                dispatch {
                    if (current(attempt)) observer.diagnostic("monitor_socket_closing",
                        mapOf("websocket_close_code" to code))
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch {
                disconnected(attempt, "closed")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch {
                if (!current(attempt)) return@dispatch
                observer.diagnostic("monitor_transport_failed", mapOf("transport_error" to t.javaClass.simpleName.take(80)))
                if (response?.code in listOf(401, 403)) terminate(ConnectionState.FAILED)
                else disconnected(attempt, "network")
            }
        })
    }

    private fun disconnected(attempt: Long, reason: String) {
        if (!current(attempt)) return
        invalidate()
        observer.diagnostic("monitor_connection_interrupted", mapOf("error_type" to reason))
        if (!online) { observer.state(ConnectionState.OFFLINE); return }
        failures++
        if (failures > 8) { terminate(ConnectionState.FAILED); return }
        observer.state(ConnectionState.RETRYING)
        val base = (retryBaseMs * (1L shl (failures - 1).coerceAtMost(4))).coerceAtMost(30_000)
        val delay = base + Random.nextLong((base / 5).coerceAtLeast(1))
        observer.diagnostic("monitor_retry_scheduled", mapOf("delay_ms" to delay))
        retry = queue.schedule({ if (!closed.get()) connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun current(attempt: Long) = !closed.get() && attempt == epoch

    private fun invalidate() {
        epoch++
        ready = false
        retry?.cancel(false); retry = null
        handshake?.cancel(false); handshake = null
        stable?.cancel(false); stable = null
        socket?.cancel(); socket = null
        events.disconnected()
        timeRequests.values.forEach { it.future.completeExceptionally(AnswerFailure("课堂连接已中断。")) }
        timeRequests.clear()
    }

    private fun terminate(state: ConnectionState) {
        invalidate()
        observer.state(state)
        closed.set(true)
        queue.shutdown()
    }

    private fun dispatch(action: () -> Unit) {
        if (closed.get()) return
        try { queue.execute { if (!closed.get()) action() } }
        catch (_: RejectedExecutionException) { /* Closing wins over an in-flight socket callback. */ }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { queue.execute { invalidate(); queue.shutdown() } }
        catch (_: RejectedExecutionException) { }
    }
}
