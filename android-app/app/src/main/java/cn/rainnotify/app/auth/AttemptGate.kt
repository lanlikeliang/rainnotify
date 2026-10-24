package cn.rainnotify.app.auth

/** Serializes cancellation with session persistence so a late callback cannot log in again. */
class AttemptGate {
    private var generation = 0L
    @Synchronized fun begin(): Long = ++generation
    @Synchronized fun current(): Long = generation
    @Synchronized fun isCurrent(token: Long): Boolean = generation == token
    @Synchronized fun runIfCurrent(token: Long, action: () -> Unit): Boolean {
        if (generation != token) return false
        action()
        return true
    }
}
