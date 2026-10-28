package cn.rainnotify.app.question

data class StudyClassroom(val key: String, val title: String, val records: List<QuestionRecord>) {
    val firstReceived get() = records.minOf { it.receivedAt }
}
object StudyClassrooms {
    fun group(records: List<QuestionRecord>): List<StudyClassroom> = records.filter { !it.offline }
        .groupBy { it.classroomKey }.map { (key, items) ->
            StudyClassroom(key, items.firstNotNullOfOrNull { it.lessonName.takeIf(String::isNotBlank) }
                ?: "历史课堂 · ${items.first().reference.lessonId.takeLast(6)}", items.sortedBy { it.receivedAt })
        }.sortedByDescending { it.records.maxOf { record -> record.receivedAt } }
}
