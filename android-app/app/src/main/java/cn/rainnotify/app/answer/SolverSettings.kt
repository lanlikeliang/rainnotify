package cn.rainnotify.app.answer

import android.content.Context
import android.graphics.BitmapFactory

enum class SolverMode { APP, DEEPSEEK_API }

object SolverSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("solver-v1", Context.MODE_PRIVATE)
    fun mode(c: Context) = if (prefs(c).getBoolean("api", false)) SolverMode.DEEPSEEK_API else SolverMode.APP
    fun isApi(c: Context) = mode(c) == SolverMode.DEEPSEEK_API
    fun select(c: Context, choice: SolverMode) {
        if (mode(c) == choice) return
        changed(c)
        prefs(c).edit().putBoolean("api", choice == SolverMode.DEEPSEEK_API).apply()
    }
    fun title(c: Context) = if (isApi(c)) "DeepSeek API" else AiProvider.selected(c).title + " App"
    fun revision(c: Context) = prefs(c).getLong("revision", 0)
    fun changed(c: Context) {
        AutoAnswer.disarm(c)
        prefs(c).edit().putLong("revision", revision(c) + 1).apply()
    }
    fun keyChanged(c: Context) {
        changed(c); prefs(c).edit().remove("verified").apply()
    }
    fun apiVerified(c: Context) = ApiKeyVault(c).exists() && prefs(c).getString("verified", null) == DeepSeekApi.MODEL + ":v1"
    fun markVerified(c: Context) { prefs(c).edit().putString("verified", DeepSeekApi.MODEL + ":v1").apply() }
    fun apiIssue(c: Context, requireVerified: Boolean): String? = when {
        !ApiKeyVault(c).exists() -> "请在设置 → AI 作答方式 → DeepSeek API 中保存 API Key。"
        requireVerified && !apiVerified(c) -> "请先在 DeepSeek API 设置中通过图文自检，再开启自动作答或提交。"
        else -> null
    }
    fun images(prepared: PreparedQuestion): List<ApiImage> {
        if (prepared.images.sumOf { it.length() } > DeepSeekApi.MAX_IMAGE_BYTES)
            throw AnswerFailure("本题图片合计超过 24 MB，未发送不完整题目。")
        return prepared.images.map { file ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192)
                throw AnswerFailure("本题图片尺寸不符合 API 要求，未发送不完整题目。")
            ApiImage(ImageMime.mime(file), file.readBytes())
        }
    }
}
