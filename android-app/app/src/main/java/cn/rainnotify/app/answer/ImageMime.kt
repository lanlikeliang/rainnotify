package cn.rainnotify.app.answer

import java.io.File

object ImageMime {
    fun mime(file: File): String {
        val bytes = ByteArray(12)
        val count = file.inputStream().use { it.read(bytes) }
        fun ascii(start: Int, length: Int) = String(bytes, start, length, Charsets.US_ASCII)
        return when {
            count >= 3 && bytes[0] == 255.toByte() && bytes[1] == 216.toByte() && bytes[2] == 255.toByte() -> "image/jpeg"
            count >= 8 && bytes[0] == 137.toByte() && ascii(1, 3) == "PNG" -> "image/png"
            count >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "image/webp"
            count >= 6 && ascii(0, 6) in setOf("GIF87a", "GIF89a") -> "image/gif"
            else -> throw AnswerFailure("该图片格式尚未接入 AI，未发送不完整题目。")
        }
    }
    fun extension(file: File) = when (mime(file)) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "gif"
    }
}
