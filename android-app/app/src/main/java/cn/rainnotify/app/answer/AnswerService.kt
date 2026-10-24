package cn.rainnotify.app.answer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import cn.rainnotify.app.Diagnostics
import cn.rainnotify.app.ProbeNotifications
import cn.rainnotify.app.question.QuestionFiles
import cn.rainnotify.app.question.QuestionRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AnswerService : Service() {
    private val work = Executors.newSingleThreadExecutor()
    private var control: RunControl? = null
    @Volatile private var current: AnswerTask? = null
    private var wake: PowerManager.WakeLock? = null
    private var busy = false
    private val main = Handler(Looper.getMainLooper())
    private var autoTicket: String? = null
    private var verificationType = 0
    private var verificationProvider: AiProvider? = null

    override fun onCreate() {
        super.onCreate()
        ProbeNotifications.createChannels(this)
        startForeground(8, notice("正在准备本题 AI 流程…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISARM) { AutoAnswer.disarm(this); if (!busy) stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP) {
            if (!busy) { stopSelf(); return START_NOT_STICKY }
            if (control?.cancel() == true) KimiBridge.cancel()
            return START_NOT_STICKY
        }
        if (busy) return START_NOT_STICKY
        val id = intent?.getStringExtra(QUESTION)
        if (intent?.action == SIMULATE && QuestionFiles.validId(id)) {
            if (AutoAnswer.ticket != null) { stopSelf(); return START_NOT_STICKY }
            main.removeCallbacksAndMessages(null)
            if (wake?.isHeld == true) wake?.release()
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "rainnotify:simulation-timer").apply { acquire(40_000) }
            getSystemService(NotificationManager::class.java).notify(8, notice("30 秒后模拟发题；可熄屏。仅演练，不提交雨课堂。"))
            main.postDelayed({ AutoAnswer.simulate(this, id!!); stopSelf(startId) }, 30_000)
            return START_NOT_STICKY
        }
        if (intent?.action != START || !QuestionFiles.validId(id)) { stopSelf(); return START_NOT_STICKY }
        val verifyType = intent.getIntExtra(VERIFY_TYPE, 0)
        // The self-test can never acquire a live gateway or an automatic ticket.
        if (verifyType != 0 && (verifyType !in CompatibilityPolicy.requiredTypes ||
            intent.getBooleanExtra(LIVE, false) || intent.hasExtra(AUTO_TICKET) || AutoAnswer.armed || AutoAnswer.ticket != null)) {
            stopSelf(); return START_NOT_STICKY
        }
        val ticketId = intent.getStringExtra(AUTO_TICKET)
        val automatic = if (ticketId != null) AutoAnswer.ticket?.takeIf {
            it.id == ticketId && it.question == id && AutoAnswer.claim(ticketId)
        } ?: run { stopSelf(); return START_NOT_STICKY } else null
        busy = true
        autoTicket = automatic?.id
        main.removeCallbacksAndMessages(null)
        val live = automatic?.live ?: intent.getBooleanExtra(LIVE, false)
        val apiMode = verifyType == 0 && SolverSettings.isApi(this)
        val solverRevision = SolverSettings.revision(this)
        val provider = if (verifyType != 0) AiProvider.entries.firstOrNull { it.name == intent.getStringExtra(PROVIDER) }
            ?: run { stopSelf(); return START_NOT_STICKY }
            else if (automatic != null) AutoAnswer.provider else AiProvider.selected(this)
        verificationType = verifyType
        verificationProvider = if (verifyType != 0) provider else null
        val run = RunControl().also { control = it }
        val task = AnswerTask(UUID.randomUUID().toString(), id!!, live, AnswerPhase.PREPARING, "正在准备本题图文…")
        current = task
        val started = automatic?.received ?: SystemClock.elapsedRealtime()
        if (wake?.isHeld == true) wake?.release()
        // A bounded cross-app display hold; the wake gate has already completed system unlock.
        @Suppress("DEPRECATION")
        val displayLock = if (apiMode) PowerManager.PARTIAL_WAKE_LOCK else PowerManager.SCREEN_DIM_WAKE_LOCK
        wake = getSystemService(PowerManager::class.java).newWakeLock(displayLock, "rainnotify:answer").apply {
            acquire(95_000)
        }
        work.execute {
            val directory = File(cacheDir, "ai-tasks/" + task.id)
            var result: AnswerResult
            var installed: AiCompatibility.Installed? = null
            var expected = emptyList<String>()
            try {
                progress(AnswerPhase.PREPARING, task.message)
                if (verifyType == 0) AiReadiness.issue(this, provider, requireVerified = live || automatic != null)?.let { throw AnswerFailure(it) }
                else AiReadiness.appIssue(this, needsAccessibility = true)?.let { throw AnswerFailure(it) }
                val build = if (apiMode) null else (AiCompatibility.installed(this, provider)
                    ?: throw AnswerFailure("所选 AI App 未安装。"))
                installed = build
                // Bind the permission to this exact snapshot; an update between UI check and start must not inherit it.
                if (!apiMode && (live || automatic != null) && !AiCompatibility.verified(this, provider, build!!))
                    throw AnswerFailure("当前安装版本需先完成图文自检，未启动自动作答或提交。")
                if (verifyType != 0) AiCompatibility.begin(this, provider, build!!, verifyType)
                val repository = QuestionRepository.get(this)
                // A notification can be tapped before the capture worker has downloaded its images.
                val preparationLimit = started + 10_000
                while (verifyType == 0 && repository.isLoading(id) && SystemClock.elapsedRealtime() < preparationLimit) {
                    run.check()
                    Thread.sleep(150)
                }
                run.check()
                val prepared = if (verifyType == 0) repository.prepare(id, directory) else
                    CompatibilitySample.create(id, verifyType, directory).let { expected = it.expected; it.prepared }
                if (live && prepared.record.offline) throw AnswerFailure("导入的离线样本不能提交到真实课堂。")
                if (live && prepared.record.reference.groupWork) throw AnswerFailure("本题是分组作答，暂不自动代小组提交；请到微信完成。可使用历史演练获取参考。")
                val binding = if (live) repository.activeBinding(id) ?: throw AnswerFailure("本题不属于当前监听会话；可使用演练模式。") else null
                val ref = prepared.record.reference
                val gateway = binding?.let { active -> object : AnswerGateway {
                    override fun current() = SolverSettings.revision(this@AnswerService) == solverRevision &&
                        (apiMode || AiCompatibility.installed(this@AnswerService, provider)?.key == build!!.key) &&
                        repository.activeBinding(id) === active && active.connection.usable(ref.problemId) &&
                        (automatic == null || AutoAnswer.valid(automatic.id))
                    override fun timing() = try { active.connection.timing(ref.problemId).get(6, TimeUnit.SECONDS) }
                        catch (e: Exception) { throw (e.cause as? AnswerFailure ?: AnswerFailure("未能核对题目剩余时间，未提交。")) }
                    override fun refresh() = active.api.answerableQuestion(ref).let { it.content to it.alreadyAnswered }
                    override fun submit(answer: List<String>, timeoutMs: Long) =
                        active.api.submitAnswer(ref, prepared.record.content!!, answer, timeoutMs)
                } }
                val key = binding?.let { AnswerProtocol.sha256(it.accountScope + ":" + ref.lessonId + ":" +
                    ref.presentationId + ":" + ref.problemId) }
                result = AnswerEngine(SystemClock::elapsedRealtime, SubmissionJournal(File(noBackupFilesDir, "submissions-v1")))
                    .run(task.id, prepared.record.content!!, started, run, gateway, key,
                        solve = { timeout ->
                            if (SolverSettings.revision(this) != solverRevision) throw AnswerFailure("作答配置已改变，请重新开始本题。")
                            if (apiMode) DeepSeekApi().solve(ApiKeyVault(this).read(),
                                AnswerProtocol.prompt(prepared.record.content!!, task.id), SolverSettings.images(prepared), timeout, run)
                            else KimiBridge.solve(task.id, prepared, timeout, provider, build!!.key, verifyType != 0)
                        }, progress = ::progress,
                        comparison = { stage, differences, transportChanged ->
                            Diagnostics.record(this, "answer_content_check", mapOf("event_id" to task.id,
                                "comparison_stage" to stage, "changed_fields" to differences.joinToString(","),
                                "asset_url_changed" to transportChanged, "content_same" to differences.isEmpty()))
                        })
                if (verifyType != 0 && result.phase == AnswerPhase.REHEARSED &&
                    !CompatibilityPolicy.passes(verifyType, result.phase, result.answer, expected, build!!.key,
                        AiCompatibility.installed(this, provider)?.key)) {
                    result = AnswerResult(AnswerPhase.FAILED, "已读回答案，但未通过测试图片的答案核对或安装版本已变化；请重试图文自检。")
                }
            } catch (e: Exception) {
                result = AnswerResult(if (run.cancelled) AnswerPhase.CANCELLED else AnswerPhase.FAILED,
                    (e as? AnswerFailure)?.display ?: "准备或网络校验失败，未完成本题流程。")
            } finally {
                // Only our task's generated share directory is removed, after all readers finish.
                directory.deleteRecursively()
            }
            if (verifyType != 0 && installed != null) {
                try {
                    if (AiCompatibility.finish(this, provider, installed, verifyType, result, expected)) {
                        result = AnswerResult(AnswerPhase.REHEARSED, provider.title +
                            (if (verifyType == 1) " 单选 / 1 图自检通过。" else " 多选 / 2 图自检通过。") +
                            (if (AiCompatibility.ready(this, provider)) "当前安装版本图文自检已全部通过。"
                            else "请返回图文自检页完成另一项。") + "未提交雨课堂。", result.answer)
                    }
                } catch (_: Exception) { result = AnswerResult(AnswerPhase.FAILED, "无法保存兼容性结果，请返回图文自检页重试。") }
            }
            try { progress(result.phase, result.message, result.answer) } finally {
                if (wake?.isHeld == true) wake?.release()
                autoTicket?.let { token -> main.post { AutoAnswer.finish(token) } }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun progress(phase: AnswerPhase, message: String, answer: List<String> = emptyList()) {
        val previous = current ?: return
        val next = previous.changed(phase, message, answer)
        AnswerState.publish(this, next)
        current = next
        Diagnostics.record(this, "answer_" + phase.name.lowercase(), mapOf("event_id" to next.id,
            "source" to if (next.live) "real" else "simulation"))
        // Notification permission can change during a run; it must not alter submission state.
        try { getSystemService(NotificationManager::class.java).notify(8, notice(message)) } catch (_: SecurityException) { }
        if (!next.active) {
            val fields = cn.rainnotify.app.NotificationReadiness.read(this).diagnosticFields() +
                mapOf("event_id" to next.id, "source" to if (next.live) "real" else "simulation")
            try { getSystemService(NotificationManager::class.java).notify("answer-" + next.id, 9,
                Notification.Builder(this, ProbeNotifications.QUESTION_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if (next.live) "本题 AI 流程结果" else "AI 演练结果")
                    .setContentText(message).setContentIntent(open(next.questionId))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
                Diagnostics.record(this, "answer_result_notification_submitted", fields)
            } catch (_: SecurityException) { Diagnostics.record(this, "answer_result_notification_blocked", fields) }
        }
    }
    private fun open(id: String?) = PendingIntent.getActivity(this, 50,
        (if (verificationType != 0) Intent(this, AiCompatibilityActivity::class.java).putExtra("provider", verificationProvider?.name)
        else Intent(this, AnswerActivity::class.java).putExtra(QUESTION, id))
            .setData(android.net.Uri.parse("rainnotify://answer/" + (id ?: "current"))),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notice(message: String): Notification {
        val stop = PendingIntent.getService(this, 51, Intent(this, AnswerService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, ProbeNotifications.STATUS_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("单题 AI 作答").setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open(current?.questionId))
            .addAction(Notification.Action.Builder(null, "停止本题", stop).build()).build()
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        autoTicket?.let { AutoAnswer.finish(it) }
        if (control?.cancel() == true) KimiBridge.cancel()
        if (wake?.isHeld == true) wake?.release()
        work.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "cn.rainnotify.app.START_ANSWER"
        const val STOP = "cn.rainnotify.app.STOP_ANSWER"
        const val QUESTION = "question_id"
        const val LIVE = "live_answer"
        const val AUTO_TICKET = "auto_ticket"
        const val SIMULATE = "cn.rainnotify.app.SIMULATE_AUTO"
        const val DISARM = "cn.rainnotify.app.DISARM_AUTO"
        const val VERIFY_TYPE = "verify_type"
        const val PROVIDER = "verification_provider"
    }
}
