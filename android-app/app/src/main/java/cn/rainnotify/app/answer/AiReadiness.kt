package cn.rainnotify.app.answer

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent

/** Only our app's configuration is a prerequisite. AI apps need no autostart grant. */
object AiReadiness {
    private fun prefs(context: Context) = context.getSharedPreferences("app-startup-v2", Context.MODE_PRIVATE)
    fun confirmed(context: Context): Boolean {
        val store = prefs(context)
        if (!store.contains("confirmed")) {
            // The old checkbox also confirmed this app's settings. Preserve that user confirmation.
            val old = context.getSharedPreferences("ai-startup-v1", Context.MODE_PRIVATE)
            val prior = AiProvider.entries.any { old.getBoolean("confirmed:${it.name}:${it.version}", false) }
            store.edit().putBoolean("confirmed", prior).apply()
        }
        return store.getBoolean("confirmed", false)
    }
    fun confirm(context: Context, checked: Boolean) { prefs(context).edit().putBoolean("confirmed", checked).apply() }
    fun appIssue(context: Context, needsAccessibility: Boolean = !SolverSettings.isApi(context)): String? {
        if (needsAccessibility && !KimiAccessibilityService.connected) return "AI 作答辅助尚未连接。请先允许本 App 后台自启动，再启用无障碍服务。"
        if (!cn.rainnotify.app.NotificationReadiness.read(context).canPost)
            return "请允许本 App 发送通知，并开启题目提醒渠道，以接收题目和流程结果。"
        if (context.getSystemService(ActivityManager::class.java).isBackgroundRestricted)
            return "系统检测到本 App 后台受限，请调整本 App 后台与省电设置。"
        if (!confirmed(context)) return "请先确认雨课堂辅助 App 的后台自启动与省电设置。"
        return null
    }
    private fun providerIssue(context: Context, provider: AiProvider, requireVerified: Boolean): String? {
        val installed = AiCompatibility.installed(context, provider)
            ?: return "请先安装并登录 ${provider.title}。无需配置该 AI App 的自启动权限。"
        return if (requireVerified && !AiCompatibility.ready(context, provider))
            "${provider.title} ${installed.version} 尚未完成图文自检。请完成单选与多选自检，再用于正式作答或自动模式；也可先手动演练历史题。" else null
    }
    fun issue(context: Context, provider: AiProvider, requireVerified: Boolean = true): String? =
        appIssue(context) ?: if (SolverSettings.isApi(context)) SolverSettings.apiIssue(context, requireVerified)
            else providerIssue(context, provider, requireVerified)
    fun ensure(activity: Activity, requireVerified: Boolean = true, provider: AiProvider = AiProvider.selected(activity), forceApp: Boolean = false): Boolean {
        val appIssue = appIssue(activity, forceApp || !SolverSettings.isApi(activity))
        if (appIssue != null) {
            AlertDialog.Builder(activity).setTitle("还需要完成设置").setMessage(appIssue)
                .setPositiveButton("去设置") { _, _ -> activity.startActivity(Intent(activity, AiReadinessActivity::class.java).putExtra("force_app", forceApp)) }
                .setNegativeButton("暂不开始", null).show()
            return false
        }
        if (!forceApp && SolverSettings.isApi(activity)) {
            SolverSettings.apiIssue(activity, requireVerified)?.let {
                AlertDialog.Builder(activity).setTitle("DeepSeek API 尚未就绪").setMessage(it)
                    .setPositiveButton("去配置") { _, _ -> activity.startActivity(Intent(activity, ApiSettingsActivity::class.java)) }
                    .setNegativeButton("暂不开始", null).show()
                return false
            }
            return true
        }
        providerIssue(activity, provider, requireVerified)?.let {
            AlertDialog.Builder(activity).setTitle("所选 AI 需要检查").setMessage(it)
                .setPositiveButton("前往图文自检") { _, _ ->
                    activity.startActivity(Intent(activity, AiCompatibilityActivity::class.java).putExtra("provider", provider.name))
                }.setNegativeButton("返回", null).show()
            return false
        }
        return true
    }
}
