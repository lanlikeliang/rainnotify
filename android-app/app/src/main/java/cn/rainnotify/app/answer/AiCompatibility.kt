package cn.rainnotify.app.answer

import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date

object AiCompatibility {
    // Bump when selectors, sharing, or reply interpretation change in an incompatible way.
    private const val ADAPTER_REVISION = 3
    data class Installed(val key: String, val version: String)
    private fun prefs(context: Context) = context.getSharedPreferences("ai-compatibility-v1", Context.MODE_PRIVATE)
    fun installed(context: Context, provider: AiProvider): Installed? = try {
        val info = context.packageManager.getPackageInfo(provider.packageName, 0)
        Installed(AnswerProtocol.sha256("${provider.packageName}:${info.versionName}:${info.longVersionCode}:" +
            "${info.lastUpdateTime}:$ADAPTER_REVISION:${Build.FINGERPRINT}"), info.versionName ?: "未知版本")
    } catch (_: Exception) { null }

    private fun prefix(provider: AiProvider, installed: Installed) = provider.name + ":" + installed.key
    fun ready(context: Context, provider: AiProvider): Boolean {
        val build = installed(context, provider) ?: return false
        return verified(context, provider, build)
    }
    fun verified(context: Context, provider: AiProvider, build: Installed): Boolean {
        val store = prefs(context)
        val prefix = prefix(provider, build)
        val passed = CompatibilityPolicy.requiredTypes.filter { store.getLong("$prefix:pass:$it", 0) > 0 }.toSet()
        return CompatibilityPolicy.ready(build.key, build.key, passed)
    }
    fun status(context: Context, provider: AiProvider): String {
        val build = installed(context, provider) ?: return "${provider.title}：未安装"
        val store = prefs(context)
        val prefix = prefix(provider, build)
        val results = CompatibilityPolicy.requiredTypes.joinToString("；") { type ->
            val time = store.getLong("$prefix:pass:$type", 0)
            (if (type == 1) "单选 / 1 图" else "多选 / 2 图") + if (time > 0)
                "已通过（${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))}）" else "待通过"
        }
        return "${provider.title} ${build.version}：" + (if (ready(context, provider)) "图文自检通过" else "需完成图文自检") +
            "\n$results\n" + store.getString("$prefix:last", "更新、重装 AI App 或更换适配规则后，需重新验证。")
    }
    /** Re-testing invalidates that part immediately, including interruption or process death. */
    fun begin(context: Context, provider: AiProvider, build: Installed, type: Int) {
        require(type in CompatibilityPolicy.requiredTypes)
        check(prefs(context).edit().remove("${prefix(provider, build)}:pass:$type")
            .putString("${prefix(provider, build)}:last", "本次${if (type == 1) "单选" else "多选"}自检尚未完成；中断后请重试。").commit())
    }
    fun finish(context: Context, provider: AiProvider, build: Installed, type: Int, result: AnswerResult,
        expected: List<String>): Boolean {
        val pass = CompatibilityPolicy.passes(type, result.phase, result.answer, expected, build.key, installed(context, provider)?.key)
        val prefix = prefix(provider, build)
        val update = prefs(context).edit().putString("$prefix:last", if (pass) "最近一次自检已完成。"
            else "最近自检未通过：${result.message} 可处理网络、登录或界面问题后重试。")
        if (pass) update.putLong("$prefix:pass:$type", System.currentTimeMillis()) else update.remove("$prefix:pass:$type")
        check(update.commit())
        return pass
    }
}
