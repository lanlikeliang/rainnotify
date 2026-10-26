package cn.rainnotify.app.answer

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.content.edit

object SolverSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("solver-v1", Context.MODE_PRIVATE)
    fun revision(c: Context) = prefs(c).getLong("revision", 0)
    fun changed(c: Context) {
        AutoAnswer.disarm(c)
        prefs(c).edit().putLong("revision", revision(c) + 1).apply()
    }
    fun keyChanged(c: Context) {
        changed(c); prefs(c).edit().remove("verified").apply()
    }
    fun apiVerified(c: Context) = ApiKeyVault(c).exists() && prefs(c).getString("verified", null) == verificationKey && prefs(c).getLong("verified_revision", -1) == revision(c)
    private val verificationKey = DeepSeekApi.ENDPOINT.toString() + ":" + DeepSeekApi.MODEL + ":v2"
    fun markVerified(c: Context) { prefs(c).edit {
        putString("verified", verificationKey)
        putLong("verified_revision", revision(c)); putLong("verified_at", System.currentTimeMillis())
    } }
    fun apiIssue(c: Context, requireVerified: Boolean): String? = when {
        !ApiKeyVault(c).exists() -> "请在设置 → AI 服务 中保存 API Key。"
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
