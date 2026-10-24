package cn.rainnotify.app.answer

import cn.rainnotify.app.question.QuestionRecord
import java.io.File

class PreparedQuestion(val record: QuestionRecord, val directory: File, val images: List<File>) {
    override fun toString() = "PreparedQuestion(redacted)"
}
