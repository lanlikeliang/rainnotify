package cn.rainnotify.app.answer

import android.app.ActivityManager
import android.content.Context
import cn.rainnotify.app.NotificationReadiness

/** API mode needs only our app. Unknown manufacturer switches are guidance, not fake permissions. */
object AiReadiness {
    fun appIssue(context: Context): String? = when {
        !NotificationReadiness.read(context).canPost -> "请允许通知和题目提醒渠道。"
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted -> "本 App 后台运行受限，请检查省电设置。"
        else -> null
    }
    fun issue(context: Context, requireVerified: Boolean = true): String? =
        appIssue(context) ?: SolverSettings.apiIssue(context, requireVerified)
}
