package cn.rainnotify.app.answer

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object KimiBridge {
    const val PACKAGE = "com.moonshot.kimichat"
    class Request(val task: String, val prepared: PreparedQuestion, val prompt: String, val deadline: Long,
        val provider: AiProvider, val installedKey: String, val verification: Boolean) {
        val result = CompletableFuture<String>()
        val launched = AtomicBoolean(false)
        @Volatile var sent = false
        var sentAt = 0L
        var editorVerified = false
        var sendAttemptAt = 0L
        @Volatile var lastHint = "等待 AI 图文输入界面"
        val preparation = ChatPreparation()
        var launchedAt = 0L
        var providerSeenAt = 0L
        var shared = false
        var sharedAt = 0L
        var readySince = 0L
        var candidate = ""
        var candidateSince = 0L
        @Volatile var shareContext: Context? = null
        @Volatile var shareUris: List<android.net.Uri> = emptyList()
        override fun toString() = "KimiRequest(redacted)"
    }
    private val main = Handler(Looper.getMainLooper())
    val requested = MutableLiveData<Request?>(null)
    @Volatile var active: Request? = null; private set

    fun solve(task: String, prepared: PreparedQuestion, timeout: Long, provider: AiProvider,
        installedKey: String, verification: Boolean = false): String {
        if (!KimiAccessibilityService.connected) throw AnswerFailure("请先在 AI 设置中启用 AI 作答辅助服务。")
        val request = Request(task, prepared, AnswerProtocol.prompt(prepared.record.content!!, task),
            SystemClock.elapsedRealtime() + timeout, provider, installedKey, verification)
        active = request
        main.post { requested.value = request }
        return try { request.result.get(timeout, TimeUnit.MILLISECONDS) }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? AnswerFailure ?: AnswerFailure("AI 作答未完成。")) }
        catch (_: Exception) { throw AnswerFailure(provider.title + " 超时：" + request.lastHint + "；未提交。") }
        finally {
            if (active === request) active = null
            request.shareUris.forEach { uri ->
                try { request.shareContext?.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: RuntimeException) { }
            }
            main.post { if (requested.value === request) requested.value = null }
        }
    }

    fun launch(activity: Activity, request: Request) {
        if (active !== request || !request.launched.compareAndSet(false, true)) return
        try {
            if (activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                throw AnswerFailure("请先解锁手机再开始 AI 作答。")
            val provider = request.provider
            request.launchedAt = SystemClock.elapsedRealtime()
            checkInstalled(activity, request)
            if (provider == AiProvider.KIMI) share(activity, request)
            else activity.startActivity(activity.packageManager.getLaunchIntentForPackage(provider.packageName)
                ?: throw AnswerFailure("无法打开所选 AI App。"))
        } catch (e: Exception) {
            request.result.completeExceptionally((e as? AnswerFailure) ?: AnswerFailure("无法打开所选 AI App，请检查安装与版本。"))
        }
    }

    fun share(context: Context, request: Request) {
        if (active !== request || request.shared || request.result.isDone) return
        try {
            checkInstalled(context, request)
            request.shared = true
            request.sharedAt = SystemClock.elapsedRealtime()
            val provider = request.provider
            val files = request.prepared.images
            val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".answerfiles", it) }
            request.shareContext = context.applicationContext
            request.shareUris = uris
            val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_TEXT, request.prompt).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.setPackage(provider.packageName)
            if (uris.isEmpty()) intent.type = "text/plain"
            else {
                intent.type = if (files.size == 1) ImageMime.mime(files.single()) else "image/*"
                if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.single())
                else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                intent.clipData = ClipData.newUri(context.contentResolver, "本题原图", uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
            val receivers = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo }.filter {
                    it.packageName == provider.packageName && it.exported && it.enabled && it.applicationInfo.enabled
                }.map { it.name }
            val receiver = ShareTargetPolicy.choose(provider.shareActivity, receivers)
                ?: throw AnswerFailure("所选 AI 的图文分享入口缺失或存在无法确认的多个入口；请反馈以更新适配。")
            intent.component = ComponentName(provider.packageName, receiver)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            request.result.completeExceptionally((e as? AnswerFailure) ?: AnswerFailure("无法打开 AI App 的图文分享入口。"))
        }
    }

    fun checkInstalled(context: Context, request: Request) {
        if (AiCompatibility.installed(context, request.provider)?.key != request.installedKey)
            throw AnswerFailure("任务期间 AI App 发生更新或被卸载，已停止；请重新进行图文自检。")
    }

    fun cancel() { active?.result?.completeExceptionally(AnswerFailure("已停止本题，未提交答案。")) }
}

