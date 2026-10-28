package cn.rainnotify.app.answer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import cn.rainnotify.app.question.QuestionAsset
import cn.rainnotify.app.question.QuestionContent
import cn.rainnotify.app.question.QuestionOption
import cn.rainnotify.app.question.QuestionRecord
import cn.rainnotify.app.question.QuestionRef
import java.io.File
import java.security.SecureRandom

/** Synthetic, local-only samples. The randomized answer exists in pixels, never in the prompt. */
object CompatibilitySample {
    class Sample(val prepared: PreparedQuestion, val expected: List<String>)
    /** Text-only check never claims to validate vision. */
    fun createText(id: String, type: Int, directory: File): Sample {
        require(type in CompatibilityPolicy.requiredTypes)
        val labels = listOf("A", "B", "C", "D")
        val random = SecureRandom()
        val expected = if (type == 1) listOf(labels[random.nextInt(4)])
            else listOf(labels[random.nextInt(2)], labels[2 + random.nextInt(2)])
        val content = QuestionContent(type, "请选择所有写着“本次目标”的选项。",
            labels.map { QuestionOption(it, null, if (it in expected) "本次目标" else "不是目标") },
            emptyList(), null, emptyList())
        val record = QuestionRecord(id, QuestionRef("0", "0", "0"), System.currentTimeMillis(),
            content, "本地文本自检", false, offline = true)
        return Sample(PreparedQuestion(record, directory, emptyList()), expected)
    }
    fun create(id: String, type: Int, directory: File): Sample {
        require(type in CompatibilityPolicy.requiredTypes)
        directory.mkdirs()
        val random = SecureRandom()
        val labels = listOf("A", "B", "C", "D")
        val expected = if (type == 1) listOf(labels[random.nextInt(4)])
            else listOf(labels[random.nextInt(2)], labels[2 + random.nextInt(2)])
        val pages = if (type == 1) listOf(labels) else listOf(labels.take(2), labels.drop(2))
        val files = pages.mapIndexed { index, page ->
            val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f }
                canvas.drawText("RainNotify 图文自检 · ${if (type == 1) "单选" else "多选"}", 60f, 70f, paint)
                canvas.drawText("请选择所有写着“本次目标”的选项。", 60f, 145f, paint)
                canvas.drawText("选项分布在 ${pages.size} 张图中，请查看全部图片。", 60f, 215f, paint)
                page.forEachIndexed { row, label ->
                    val marker = if (label in expected) "本次目标" else "不是目标"
                    canvas.drawText("$label. $marker", 80f, 315f + row * 80f, paint)
                }
                paint.textSize = 24f
                canvas.drawText("仅测试图片读取与自动交互，不涉及真实课堂。第 ${index + 1} 张", 60f, 675f, paint)
                File(directory, "compat-$index.png").also { file ->
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            } finally { bitmap.recycle() }
        }
        val content = QuestionContent(type, "", labels.map { QuestionOption(it, null, "") },
            files.indices.map { QuestionAsset("自检图", "local-compatibility:$it") }, null, emptyList())
        val record = QuestionRecord(id, QuestionRef("0", "0", "0"), System.currentTimeMillis(),
            content, "本地兼容性自检", false, offline = true)
        return Sample(PreparedQuestion(record, directory, files), expected)
    }
}
