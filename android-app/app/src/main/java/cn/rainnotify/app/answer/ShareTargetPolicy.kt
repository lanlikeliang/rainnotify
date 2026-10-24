package cn.rainnotify.app.answer

object ShareTargetPolicy {
    /** Prefer the known receiver; a renamed receiver is safe to try only when unambiguous. */
    fun choose(preferred: String, exportedCandidates: List<String>): String? {
        val candidates = exportedCandidates.distinct()
        return preferred.takeIf { it in candidates } ?: candidates.singleOrNull()
    }
}
