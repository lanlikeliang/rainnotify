package cn.rainnotify.app.answer

/** Remove only the observed Doubao image-answer heading, not arbitrary prose around a JSON object. */
object AiReplyText {
    fun normalize(provider: String, text: String): String {
        val trimmed = text.trim()
        return if (provider == "DOUBAO" && (trimmed.startsWith("💡解答\n") || trimmed.startsWith("💡解答\r\n")))
            trimmed.removePrefix("💡解答").trimStart() else text
    }
}
