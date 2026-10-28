package cn.rainnotify.app.answer

/** Curated official models, checked against DeepSeek documentation on 2026-10-27. */
enum class DeepSeekModel(val id: String, val label: String, val supportsImages: Boolean) {
    FLASH("deepseek-flash", "Flash", true),
    PRO("deepseek-v4-pro", "V4 Pro", false);

    val displayName get() = "DeepSeek · $label"
    val capability get() = if (supportsImages) "支持图文" else "仅支持文字"
    val checkName get() = if (supportsImages) "图文自检" else "文本自检"
    // Preserve the existing Flash check on upgrade, but never reuse it for Pro.
    val verificationKey get() = "https://api.deepseek.com/chat/completions:$id:v2"
    fun requireSupportedImages(hasImages: Boolean) {
        if (hasImages && !supportsImages)
            throw AnswerFailure("本题含图片，V4 Pro 仅支持文字。请在设置 → AI 服务切换 Flash 并完成图文自检；本题未发送、未提交。")
    }
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: FLASH
    }
}
