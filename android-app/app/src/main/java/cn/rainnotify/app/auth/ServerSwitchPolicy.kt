package cn.rainnotify.app.auth

/** A server switch must never carry a live classroom or submission into another environment. */
object ServerSwitchPolicy {
    fun allowed(monitorActive: Boolean, automaticRequested: Boolean, answerActive: Boolean, pendingAnswer: Boolean) =
        !monitorActive && !automaticRequested && !answerActive && !pendingAnswer
}
