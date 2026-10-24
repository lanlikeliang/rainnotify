package cn.rainnotify.app.monitor

/** In-memory, one-shot continuation for an explicit "scan then return" action. */
class ClassroomScanReturn(private val clock: () -> Long) {
    private var lesson: String? = null
    private var started = 0L
    private var left = false

    fun arm(lessonId: String) {
        require(LessonProtocol.id(lessonId) != null)
        lesson = lessonId; started = clock(); left = false
    }
    fun leftApp() { if (lesson != null) left = true }
    fun consume(requiredLesson: String?, available: Boolean): String? {
        if (!left) return null
        val pending = lesson
        val age = clock() - started
        cancel()
        return pending?.takeIf { available && it == requiredLesson && age in 0..300_000 }
    }
    fun cancel() { lesson = null; left = false }
}
