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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Automatic generation uses a classroom ticket. Manual submission requires a confirmed saved answer. */
class AnswerService : Service() {
    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var control: RunControl? = null
    @Volatile private var current: AnswerTask? = null
    private var wake: PowerManager.WakeLock? = null
    private var busy = false
        set(value) { field = value; running = value }
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
        if (intent?.action == MANUAL_SUBMIT && QuestionFiles.validId(id)) {
            startManual(id!!, intent.getStringExtra(TASK))
            return START_NOT_STICKY
        }
        val ticketId = intent?.getStringExtra(AUTO_TICKET)
        if (intent?.action != START || !QuestionFiles.validId(id) || ticketId == null) { stopSelf(); return START_NOT_STICKY }
        val automatic = AutoAnswer.ticket?.takeIf {
            it.live && it.id == ticketId && it.question == id && AutoAnswer.claim(ticketId)
        } ?: run { stopSelf(); return START_NOT_STICKY }
        busy = true; autoTicket = automatic.id
        val generationStarted = SystemClock.elapsedRealtime()
        val revision = SolverSettings.revision(this)
        val model = SolverSettings.model(this)
        val run = RunControl().also { control = it }
        val task = AnswerTask(automatic.id, id!!, true, AnswerPhase.PREPARING, "正在获取本题图文…", model = model)
        current = task
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "rainnotify:api-answer").apply { acquire(95_000) }
        work.execute {
            val directory = File(cacheDir, "ai-tasks/" + task.id)
            val result = try {
                progress(AnswerPhase.PREPARING, task.message)
                AiReadiness.issue(this)?.let { throw AnswerFailure(it) }
                val repo = QuestionRepository.get(this)
                val contentWaitUntil = SystemClock.elapsedRealtime() + 10_000
                while (repo.isLoading(id) && SystemClock.elapsedRealtime() < contentWaitUntil) {
                    run.check(); Thread.sleep(150)
                }
                run.check()
                val prepared = repo.prepare(id, directory)
                val content = prepared.record.content ?: throw AnswerFailure("题目内容尚未完整获取，未提交。")
                model.requireSupportedImages(content.assets.isNotEmpty() || prepared.images.isNotEmpty())
                repo.saveAnswerImages(id, prepared.images)
                current = current?.changed(AnswerPhase.PREPARING, task.message, sourceQuestion = content)
                val active = repo.activeBinding(id)
                val ref = prepared.record.reference
                val gateway = object : AnswerGateway {
                    override fun current() = active != null && !prepared.record.offline && !ref.groupWork &&
                        active.api.server == prepared.record.server && active.accountScope == prepared.record.accountScope &&
                        SolverSettings.revision(this@AnswerService) == revision &&
                        repo.activeBinding(id) === active && active.connection.usable(ref.problemId) && AutoAnswer.valid(automatic.id)
                    override fun timing() = try { requireNotNull(active).connection.timing(ref.problemId).get(6, TimeUnit.SECONDS) }
                        catch (e: Exception) { throw (e.cause as? AnswerFailure ?: AnswerFailure("无法核对剩余时间，未提交。")) }
                    override fun refresh() = requireNotNull(active).api.answerableQuestion(ref).let { it.content to it.alreadyAnswered }
                    override fun compareImages(current: cn.rainnotify.app.question.QuestionContent, timeoutMs: Long): ImageComparison {
                        val result = AnswerImages.compare(prepared, current, timeoutMs, run)
                        try { Diagnostics.record(this@AnswerService, "answer_image_check", mapOf(
                            "event_id" to task.id, "image_comparison" to result.name.lowercase())) } catch (_: Exception) { }
                        return result
                    }
                    override fun submit(answer: List<String>, timeoutMs: Long) =
                        requireNotNull(active).api.submitAnswer(ref, content, answer, timeoutMs)
                }
                val key = AnswerProtocol.sha256(prepared.record.accountScope + ":" + ref.lessonId + ":" + ref.presentationId + ":" + ref.problemId)
                AnswerEngine(SystemClock::elapsedRealtime, SubmissionJournal(File(noBackupFilesDir, "submissions-v1")))
                    .run(task.id, content, generationStarted, run, gateway, key,
                        solve = { timeout ->
                            if (SolverSettings.revision(this) != revision) throw AnswerFailure("API 配置已改变，未提交。")
                            try {
                                DeepSeekApi().solve(ApiKeyVault(this).read(), AnswerProtocol.prompt(content, task.id),
                                    SolverSettings.images(prepared), timeout, run, model)
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
                        }, progress = { phase, message -> progress(phase, message) },
                        referenceReady = { answer -> current = current?.changed(AnswerPhase.CHECKING, "参考答案已生成，正在检查自动提交条件…", answer) },
                        comparison = { stage, differences, transportChanged ->
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
            try { progress(result.phase, result.message, result.answer, result.submissionAttempted) } finally {
                if (wake?.isHeld == true) wake?.release()
                main.post { busy = false; AnswerState.task.value = current; stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    private fun progress(phase: AnswerPhase, message: String, answer: List<String> = current?.answer.orEmpty(),
        attempted: Boolean = current?.submissionAttempted == true || phase == AnswerPhase.SUBMITTING) {
        val next = current?.changed(phase, message, answer, submissionAttempted = attempted) ?: return
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

    private fun startManual(questionId: String, taskId: String?) {
        if (AutoAnswer.ticket != null || AnswerState.task.value?.active == true) {
            android.widget.Toast.makeText(this, "已有作答流程开始，请等待结束后再次确认。", android.widget.Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        val repo = QuestionRepository.get(this)
        val record = repo.records.value.orEmpty().firstOrNull { it.id == questionId }
            ?: repo.latest.value?.takeIf { it.id == questionId }
        val saved = AnswerState.task.value?.takeIf { it.questionId == questionId }
            ?: repo.answers.value?.get(questionId)
        val active = repo.currentBinding()
        val selectedServer = cn.rainnotify.app.auth.ServerSettings.selected(this)
        if (record == null || saved == null || saved.id != taskId || active == null ||
            selectedServer != active.api.server ||
            ManualSubmission.issue(record, saved, active.api.server, active.accountScope, active.lesson) != null) {
            stopSelf()
            return
        }
        val content = saved.sourceQuestion ?: record.content ?: run { stopSelf(); return }
        busy = true
        current = saved
        val run = RunControl().also { control = it }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "rainnotify:manual-submit").apply { acquire(15_000) }
        work.execute {
            val ref = record.reference
            val gateway = object : AnswerGateway {
                override fun current() = repo.currentBinding() === active &&
                    cn.rainnotify.app.auth.ServerSettings.selected(this@AnswerService) == record.server &&
                    active.api.server == record.server && active.accountScope == record.accountScope && active.lesson == ref.lessonId
                override fun refresh(): Pair<cn.rainnotify.app.question.QuestionContent, Boolean?> = error("Manual submission skips refresh")
                override fun timing(): cn.rainnotify.app.monitor.ProblemTiming = error("Manual submission skips timing")
                override fun submit(answer: List<String>, timeoutMs: Long) = active.api.submitAnswer(ref, content, answer, timeoutMs)
            }
            val key = AnswerProtocol.sha256(active.accountScope + ":" + ref.lessonId + ":" + ref.presentationId + ":" + ref.problemId)
            val result = AnswerEngine(SystemClock::elapsedRealtime, SubmissionJournal(File(noBackupFilesDir, "submissions-v1")))
                .submitConfirmed(saved.id, content, saved.answer, run, gateway, key) { phase, message -> progress(phase, message) }
            try { progress(result.phase, result.message, result.answer, result.submissionAttempted) } finally {
                if (wake?.isHeld == true) wake?.release()
                main.post { busy = false; AnswerState.task.value = current; stopSelf() }
            }
        }
    }

    private fun open() = PendingIntent.getActivity(this, 50, Intent(this, MainActivity::class.java)
        .putExtra(MainActivity.TAB, "question").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notice(message: String): Notification {
        val stop = PendingIntent.getService(this, 51, Intent(this, AnswerService::class.java).setAction(DISARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, ProbeNotifications.STATUS_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (autoTicket == null && busy) "正在提交参考答案" else "正在自动答题").setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open()).addAction(Notification.Action.Builder(null, "切回发题通知", stop).build()).build()
    }
    override fun onDestroy() {
        busy = false
        control?.cancel()
        autoTicket?.let { AutoAnswer.finish(it) }
        if (wake?.isHeld == true) wake?.release()
        work.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile var running = false
            private set
        const val START = "cn.rainnotify.app.START_ANSWER"
        const val STOP = "cn.rainnotify.app.STOP_ANSWER"
        const val QUESTION = "question_id"
        const val AUTO_TICKET = "auto_ticket"
        const val MANUAL_SUBMIT = "cn.rainnotify.app.MANUAL_SUBMIT"
        const val TASK = "answer_task"
        const val DISARM = "cn.rainnotify.app.DISARM_AUTO"
    }
}
