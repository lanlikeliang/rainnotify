package cn.rainnotify.app.answer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import cn.rainnotify.app.Diagnostics
import cn.rainnotify.app.MainActivity
import cn.rainnotify.app.ProbeNotifications
import cn.rainnotify.app.question.QuestionFiles
import cn.rainnotify.app.question.QuestionRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Live API tasks only. A valid current-classroom ticket is required for every start. */
class AnswerService : Service() {
    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var control: RunControl? = null
    @Volatile private var current: AnswerTask? = null
    private var wake: PowerManager.WakeLock? = null
    private var busy = false
    private var autoTicket: String? = null
    override fun onCreate() {
        super.onCreate()
        ProbeNotifications.createChannels(this)
        startForeground(8, notice("正在准备本题图文…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISARM) { AutoAnswer.disarm(this); if (!busy) stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP) { control?.cancel(); if (!busy) stopSelf(); return START_NOT_STICKY }
        if (busy) return START_NOT_STICKY
        val id = intent?.getStringExtra(QUESTION)
        val ticketId = intent?.getStringExtra(AUTO_TICKET)
        if (intent?.action != START || !QuestionFiles.validId(id) || ticketId == null) { stopSelf(); return START_NOT_STICKY }
        val automatic = AutoAnswer.ticket?.takeIf {
            it.live && it.id == ticketId && it.question == id && AutoAnswer.claim(ticketId)
        } ?: run { stopSelf(); return START_NOT_STICKY }
        busy = true; autoTicket = automatic.id
        val revision = SolverSettings.revision(this)
        val run = RunControl().also { control = it }
        val task = AnswerTask(UUID.randomUUID().toString(), id!!, true, AnswerPhase.PREPARING, "正在获取本题图文…")
        current = task
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "rainnotify:api-answer").apply { acquire(95_000) }
        work.execute {
            val directory = File(cacheDir, "ai-tasks/" + task.id)
            val result = try {
                progress(AnswerPhase.PREPARING, task.message)
                AiReadiness.issue(this)?.let { throw AnswerFailure(it) }
                val repo = QuestionRepository.get(this)
                while (repo.isLoading(id) && SystemClock.elapsedRealtime() < automatic.received + 10_000) {
                    run.check(); Thread.sleep(150)
                }
                run.check()
                val prepared = repo.prepare(id, directory)
                if (prepared.record.offline) throw AnswerFailure("离线题目不能提交到课堂。")
                if (prepared.record.reference.groupWork) throw AnswerFailure("分组题需在雨课堂中手动完成，未自动提交。")
                val active = repo.activeBinding(id) ?: throw AnswerFailure("本题不属于当前监听会话。")
                val ref = prepared.record.reference
                val gateway = object : AnswerGateway {
                    override fun current() = SolverSettings.revision(this@AnswerService) == revision &&
                        repo.activeBinding(id) === active && active.connection.usable(ref.problemId) && AutoAnswer.valid(automatic.id)
                    override fun timing() = try { active.connection.timing(ref.problemId).get(6, TimeUnit.SECONDS) }
                        catch (e: Exception) { throw (e.cause as? AnswerFailure ?: AnswerFailure("无法核对剩余时间，未提交。")) }
                    override fun refresh() = active.api.answerableQuestion(ref).let { it.content to it.alreadyAnswered }
                    override fun submit(answer: List<String>, timeoutMs: Long) =
                        active.api.submitAnswer(ref, prepared.record.content!!, answer, timeoutMs)
                }
                val key = AnswerProtocol.sha256(active.accountScope + ":" + ref.lessonId + ":" + ref.presentationId + ":" + ref.problemId)
                AnswerEngine(SystemClock::elapsedRealtime, SubmissionJournal(File(noBackupFilesDir, "submissions-v1")))
                    .run(task.id, prepared.record.content!!, automatic.received, run, gateway, key,
                        solve = { timeout ->
                            if (SolverSettings.revision(this) != revision) throw AnswerFailure("API 配置已改变，未提交。")
                            try {
                                DeepSeekApi().solve(ApiKeyVault(this).read(), AnswerProtocol.prompt(prepared.record.content!!, task.id),
                                    SolverSettings.images(prepared), timeout, run)
                            } catch (e: ApiUnavailable) {
                                // AnswerEngine maps solver failures to a result; downgrade before that boundary.
                                main.post {
                                    if (SolverSettings.revision(this) == revision) {
                                        SolverSettings.keyChanged(this)
                                        AutoAnswer.block(this, e.display)
                                    }
                                }
                                throw e
                            }
                        }, progress = ::progress, comparison = { stage, differences, transportChanged ->
                            Diagnostics.record(this, "answer_content_check", mapOf("event_id" to task.id,
                                "comparison_stage" to stage, "changed_fields" to differences.joinToString(","),
                                "asset_url_changed" to transportChanged, "content_same" to differences.isEmpty()))
                        })
            } catch (e: Exception) {
                if (e is ApiUnavailable) main.post {
                    if (SolverSettings.revision(this) == revision) {
                        SolverSettings.keyChanged(this)
                        AutoAnswer.block(this, e.display)
                    }
                }
                AnswerResult(if (run.cancelled) AnswerPhase.CANCELLED else AnswerPhase.FAILED,
                    (e as? AnswerFailure)?.display ?: "准备或网络校验失败，未完成本题。")
            } finally { directory.deleteRecursively() }
            try { progress(result.phase, result.message, result.answer) } finally {
                if (wake?.isHeld == true) wake?.release()
                main.post { AutoAnswer.finish(automatic.id); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    private fun progress(phase: AnswerPhase, message: String, answer: List<String> = emptyList()) {
        val next = current?.changed(phase, message, answer) ?: return
        AnswerState.publish(this, next); current = next
        Diagnostics.record(this, "answer_" + phase.name.lowercase(), mapOf("event_id" to next.id, "source" to "real"))
        try {
            getSystemService(NotificationManager::class.java).notify(8, notice(message))
            if (!next.active) getSystemService(NotificationManager::class.java).notify("answer-" + next.id, 9,
                Notification.Builder(this, ProbeNotifications.QUESTION_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("本题处理结果").setContentText(message).setContentIntent(open())
                    .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Notification failure must not change submission outcome. */ }
    }
    private fun open() = PendingIntent.getActivity(this, 50, Intent(this, MainActivity::class.java)
        .putExtra(MainActivity.TAB, "question").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notice(message: String): Notification {
        val stop = PendingIntent.getService(this, 51, Intent(this, AnswerService::class.java).setAction(DISARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, ProbeNotifications.STATUS_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("正在自动答题").setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open()).addAction(Notification.Action.Builder(null, "切回发题通知", stop).build()).build()
    }
    override fun onDestroy() {
        control?.cancel()
        autoTicket?.let { AutoAnswer.finish(it) }
        if (wake?.isHeld == true) wake?.release()
        work.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "cn.rainnotify.app.START_ANSWER"
        const val STOP = "cn.rainnotify.app.STOP_ANSWER"
        const val QUESTION = "question_id"
        const val AUTO_TICKET = "auto_ticket"
        const val DISARM = "cn.rainnotify.app.DISARM_AUTO"
    }
}
