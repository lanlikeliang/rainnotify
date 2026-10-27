package cn.rainnotify.app.ui

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.*
import cn.rainnotify.app.NotificationReadiness
import cn.rainnotify.app.answer.*
import cn.rainnotify.app.auth.*
import cn.rainnotify.app.monitor.*
import cn.rainnotify.app.question.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

data class HomeState(
    val login: LoginUiState = LoginUiState(), val lesson: LessonSummary? = null,
    val monitor: MonitorUiState = MonitorUiState(), val mode: ClassroomMode = ClassroomMode.OFF,
    val apiSaved: Boolean = false, val apiVerified: Boolean = false, val apiBusy: Boolean = false,
    val apiMessage: String = "", val notification: NotificationReadiness? = null,
    val latest: QuestionRecord? = null, val answer: AnswerTask? = null, val message: String = "", val stopping: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    val repository = QuestionRepository.get(app)
    private val mutable = MutableLiveData(HomeState())
    val state: LiveData<HomeState> = mutable
    private val removers = mutableListOf<() -> Unit>()
    private var login = LoginUiState()
    private var selected: LessonSummary? = null
    private var message = ""
    private var apiMessage = ""
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var apiRun: RunControl? = null
    private var disposed = false
    private var boundLogin: LiveData<LoginUiState>? = null
    init {
        observe(MonitorState.state) { refresh() }
        observe(AutoAnswer.status) { refresh() }
        observe(repository.latest) { refresh() }
        observe(AnswerState.task) { refresh() }
    }
    private fun <T> observe(source: LiveData<T>, block: (T) -> Unit) {
        val observer = Observer<T> { block(it) }
        source.observeForever(observer)
        removers += { source.removeObserver(observer) }
    }
    fun bindLogin(source: LiveData<LoginUiState>) {
        if (boundLogin === source) return
        boundLogin = source
        observe(source) {
            login = it
            if (it.sessionExpired && MonitorState.current().active) {
                stop()
                message = "雨课堂授权已失效，监听与自动答题已关闭，请重新授权。"
            }
            if (!it.busy && it.authenticated && !MonitorState.current().active) {
                selected = it.lessons.firstOrNull { candidate -> candidate.id == selected?.id }
                    ?: it.lessons.singleOrNull()
            }
            refresh()
        }
    }
    fun selectLesson(lesson: LessonSummary) {
        if (MonitorState.current().active) { explain("请先切到关闭，再更换课堂。"); return }
        selected = lesson; message = ""; refresh()
    }
    fun refresh() {
        if (disposed) return
        val monitor = MonitorState.current()
        val latest = repository.latest.value
        val answer = AnswerState.task.value?.takeIf { ModePolicy.answerBelongsToLatest(latest?.id, it.questionId) }
        mutable.value = HomeState(login, selected, monitor,
            ModePolicy.effective(monitor.active && !MonitorState.stopRequested, monitor.established, AutoAnswer.armed),
            ApiKeyVault(app).exists(), SolverSettings.apiVerified(app), apiRun != null, apiMessage,
            NotificationReadiness.read(app), latest, answer, message.ifBlank { AutoAnswer.status.value.orEmpty() },
            MonitorState.stopRequested && monitor.active)
    }
    fun requirements(target: ClassroomMode): List<Requirement> {
        refresh()
        val s = mutable.value!!
        return ModePolicy.missing(target, ModeRequirements(s.login.authenticated || s.monitor.established,
            s.lesson != null || s.monitor.established, s.notification?.canPost == true,
            s.notification?.backgroundRestricted == true, s.apiSaved, s.apiVerified))
    }
    fun request(target: ClassroomMode) {
        message = ""
        if (target == ClassroomMode.OFF) { stop(); return }
        if (MonitorState.stopRequested && MonitorState.current().active) { explain("正在停止，请稍候。"); return }
        // Downgrading must remain possible even if a permission was just revoked.
        if (target == ClassroomMode.NOTIFY && MonitorState.current().active) {
            MonitorState.automaticRequested = false
            AutoAnswer.disarm(app)
            app.startService(Intent(app, LessonMonitorService::class.java).setAction(LessonMonitorService.NOTIFY_ONLY))
            refresh(); return
        }
        val missing = requirements(target)
        if (missing.isNotEmpty()) { explain(missing.joinToString("；") { it.label }); return }
        if (target == ClassroomMode.AUTO && (apiRun != null || AnswerState.task.value?.active == true)) {
            explain("请等待当前任务结束，再开启自动答题。"); return
        }
        val monitor = MonitorState.current()
        if (monitor.active) {
            if (!monitor.established || monitor.connection != ConnectionState.LISTENING) {
                explain("课堂连接尚未确认，请连接成功后再开启自动答题。")
            } else if (!AutoAnswer.arm(app)) explain("自动答题未开启，请检查配置或等待当前题目结束。")
            refresh(); return
        }
        val lesson = selected ?: return
        // Do not optimistically paint the requested mode as running.
        MonitorState.stopRequested = false
        MonitorState.automaticRequested = target == ClassroomMode.AUTO
        MonitorState.update(MonitorUiState(active = true, message = "正在准备课堂连接…",
            lessonId = lesson.id, lessonName = lesson.classroomName))
        try {
            app.startForegroundService(Intent(app, LessonMonitorService::class.java).setAction(LessonMonitorService.START)
                .putExtra(LessonMonitorService.LESSON_ID, lesson.id).putExtra(LessonMonitorService.LESSON_NAME, lesson.classroomName)
                .putExtra(LessonMonitorService.AUTOMATIC, target == ClassroomMode.AUTO))
        } catch (_: RuntimeException) {
            MonitorState.update(MonitorUiState(message = "系统未允许启动，请检查后台运行设置。"))
        }
        refresh()
    }
    fun stop() {
        MonitorState.stopRequested = true
        MonitorState.automaticRequested = false
        AutoAnswer.disarm(app)
        if (!app.stopService(Intent(app, LessonMonitorService::class.java))) {
            MonitorState.update(MonitorState.current().copy(active = false, established = false, message = "监听已关闭。"))
        }
        message = ""; refresh()
    }
    fun beforeLogout() { stop(); selected = null; repository.hideLatest(); message = "" }
    fun explain(value: String) { message = value; refresh() }
    private fun canChangeApi(): Boolean {
        if (apiRun != null || AnswerState.task.value?.active == true || AutoAnswer.ticket != null) {
            apiMessage = "请先结束当前自检或作答任务，再修改 API。"; refresh(); return false
        }
        return true
    }
    fun saveAndCheck(value: String) {
        if (!canChangeApi()) return
        try {
            ApiKeyVault(app).write(value.trim())
            SolverSettings.keyChanged(app)
            verifyApi()
        } catch (e: AnswerFailure) { apiMessage = e.display; refresh() }
    }
    fun deleteKey() {
        if (!canChangeApi()) return
        ApiKeyVault(app).clear(); SolverSettings.keyChanged(app)
        apiMessage = "密钥已删除，仍可使用发题通知。"; refresh()
    }
    fun verifyApi() {
        if (!canChangeApi()) return
        if (!ApiKeyVault(app).exists()) { apiMessage = "请先保存 API Key。"; refresh(); return }
        SolverSettings.keyChanged(app)
        val revision = SolverSettings.revision(app)
        val run = RunControl().also { apiRun = it }
        apiMessage = "正在校验单图、多图与答案格式…"; refresh()
        worker.execute {
            var passed = false
            val directory = File(app.cacheDir, "api-check/" + UUID.randomUUID())
            val result = try {
                val key = ApiKeyVault(app).read()
                for (type in listOf(1, 2)) {
                    run.check()
                    val taskId = UUID.randomUUID().toString()
                    val sample = CompatibilitySample.create(taskId, type, File(directory, "$type"))
                    val q = sample.prepared.record.content!!
                    val reply = DeepSeekApi().solve(key, AnswerProtocol.prompt(q, taskId), SolverSettings.images(sample.prepared), 60_000, run)
                    if (AnswerProtocol.parse(reply, taskId, q) != sample.expected.sorted())
                        throw AnswerFailure("模型已响应，但图片识别未通过，请重新自检。")
                }
                run.check(); passed = true
                "单图、多图与答案格式均通过。"
            } catch (e: Exception) { (e as? AnswerFailure)?.display ?: "自检未完成，请检查网络后重试。" }
            finally { directory.deleteRecursively() }
            main.post {
                if (!disposed && apiRun === run) {
                    if (passed && !run.cancelled && SolverSettings.revision(app) == revision) SolverSettings.markVerified(app)
                    apiRun = null; apiMessage = result; refresh()
                }
            }
        }
    }
    fun cancelApiCheck() { apiRun?.cancel() }
    override fun onCleared() {
        disposed = true; apiRun?.cancel(); worker.shutdown(); removers.forEach { it() }; super.onCleared()
    }
}
