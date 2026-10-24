package cn.rainnotify.app.answer

/** Evidence belongs to one installed build and adapter, never to a marketing version range. */
object CompatibilityPolicy {
    val requiredTypes = setOf(1, 2)
    fun ready(currentKey: String, proofKey: String?, passed: Set<Int>) =
        currentKey == proofKey && passed.containsAll(requiredTypes)

    fun passes(type: Int, phase: AnswerPhase, answer: List<String>, expected: List<String>,
        originalKey: String, currentKey: String?) =
        type in requiredTypes && phase == AnswerPhase.REHEARSED &&
            expected.isNotEmpty() && answer.sorted() == expected.sorted() && originalKey == currentKey
}
