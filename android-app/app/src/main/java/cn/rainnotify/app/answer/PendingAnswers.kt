package cn.rainnotify.app.answer

/** Ordered and deduplicated within one explicitly armed session. Deadlines never restart. */
class PendingAnswers {
    data class Entry(val taskId: String, val question: String, val received: Long)
    private val queue = ArrayDeque<Entry>()
    private val seen = mutableSetOf<String>()
    val size get() = queue.size
    fun offer(entry: Entry): Boolean {
        if (!seen.add(entry.question)) return false
        queue.addLast(entry); return true
    }
    fun poll(): Entry? = queue.removeFirstOrNull()
    fun clear(): List<Entry> = queue.toList().also { queue.clear(); seen.clear() }
}
