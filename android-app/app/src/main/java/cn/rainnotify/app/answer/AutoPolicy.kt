package cn.rainnotify.app.answer

/** Memory-only admission policy; neither arming nor in-flight work survives process death. */
class AutoPolicy(private val clock: () -> Long) {
    data class Ticket(val id: String, val question: String, val session: String?, val received: Long) {
        val live get() = session != null
    }
    @Volatile var session: String? = null; private set
    @Volatile var ticket: Ticket? = null; private set
    private val seen = LinkedHashSet<String>()
    fun arm(session: String) { require(session.isNotEmpty()); disarm(); this.session = session }
    fun disarm() { session = null; ticket = null; seen.clear() }
    fun offer(id: String, question: String, liveSession: String?, busy: Boolean, received: Long = clock()): Ticket? {
        if (busy || ticket != null || (liveSession != null && question in seen)) return null
        if (liveSession != null && liveSession != session) return null
        return Ticket(id, question, liveSession, received).also {
            ticket = it; if (liveSession != null) seen.add(question)
            if (seen.size > 256) seen.remove(seen.first())
        }
    }
    fun valid(id: String): Boolean = ticket?.let {
        it.id == id && clock() - it.received in 0 until 85_000 && (!it.live || it.session == session)
    } == true
    fun finish(id: String) { if (ticket?.id == id) ticket = null }
}
