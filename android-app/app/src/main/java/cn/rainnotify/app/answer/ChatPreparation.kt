package cn.rainnotify.app.answer

/** Wait for observed UI state, not for an assumed result of a single click. */
class ChatPreparation {
    enum class Step { WAIT, CREATE, SHARE, BLOCK_DRAFT, FAILED }
    private var stableAt: Long? = null
    private var clickedAt: Long? = null
    private var clicks = 0
    fun next(now: Long, blank: Boolean, draft: Boolean, canCreate: Boolean): Step {
        if (draft) return Step.BLOCK_DRAFT
        if (blank) {
            if (stableAt == null) stableAt = now
            return if (now - stableAt!! >= 1_200) Step.SHARE else Step.WAIT
        }
        stableAt = null
        val last = clickedAt
        if (last != null && now - last < 3_000) return Step.WAIT
        if (clicks >= 2) return if (last != null && now - last >= 6_000) Step.FAILED else Step.WAIT
        if (!canCreate) return Step.WAIT
        clickedAt = now
        clicks++
        return Step.CREATE
    }
}
