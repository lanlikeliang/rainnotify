package cn.rainnotify.app.answer

import org.junit.Assert.*
import org.junit.Test
import cn.rainnotify.app.question.QuestionContent
import cn.rainnotify.app.question.QuestionOption

class AiReplyTextTest {
    private val nonce = "12345678-1234-1234-1234-123456789abc"
    private val json = "{\"task\":\"$nonce\",\"answer\":[\"B\"],\"unable\":false}"
    private val question = QuestionContent(1, "sample", listOf(QuestionOption("A", "1", "one"),
        QuestionOption("B", "2", "two")), emptyList(), 1, emptyList())
    @Test fun observedDoubaoHeadingDoesNotInvalidateStructuredAnswer() {
        assertEquals(listOf("B"), AnswerProtocol.parse(AiReplyText.normalize("DOUBAO", "💡解答\n \n$json"), nonce, question))
    }
    @Test fun normalizationCannotHideExtraTextOrInvalidAnswers() {
        for (value in listOf("说明\n$json", "💡解答\n$json\n解释", "💡解答\n" + json.replace("false", "true"),
            "💡解答\n" + json.replace(nonce, "old"), "💡解答\n" + json.replace("\"B\"", "\"Z\""))) {
            assertThrows(AnswerFailure::class.java) { AnswerProtocol.parse(AiReplyText.normalize("DOUBAO", value), nonce, question) }
        }
        assertEquals("💡解答\n$json", AiReplyText.normalize("KIMI", "💡解答\n$json"))
    }
}
