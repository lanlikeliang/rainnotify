package cn.rainnotify.app.answer

import cn.rainnotify.app.question.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionIdentityTest {
    private fun question(type: Int = 2, body: String = "body",
        options: List<QuestionOption> = listOf(QuestionOption("A", "1", "one"), QuestionOption("B", "2", "two")),
        assets: List<QuestionAsset> = listOf(QuestionAsset("cover", "https://rain-pri-ups.yuketang.cn/a?auth_key=old")),
        page: Int? = 1, notes: List<String> = emptyList()) = QuestionContent(type, body, options, assets, page, notes)
    private fun same(a: QuestionContent, b: QuestionContent) {
        assertTrue(QuestionIdentity.differences(a, b).isEmpty())
        assertEquals(AnswerProtocol.fingerprint(a), AnswerProtocol.fingerprint(b))
    }
    private fun different(a: QuestionContent, b: QuestionContent) {
        assertFalse(QuestionIdentity.differences(a, b).isEmpty())
        assertNotEquals(AnswerProtocol.fingerprint(a), AnswerProtocol.fingerprint(b))
    }
    @Test fun allImageUrlsAreExcludedFromTextIdentityAndRemainAvailableForDownload() {
        for (url in listOf("https://new.yuketang.cn/changed.png?signature=new", "https://other.xuetangx.com/a?token=2",
            "https://rain-pri-ups.yuketang.cn/a?width=2&auth_key=new")) {
            val after = question(assets = listOf(QuestionAsset("cover", url)))
            same(question(), after)
            assertEquals(url, after.assets.single().url)
        }
    }
    @Test fun sourcesInsideStemAndOptionsAreExcludedRegardlessOfHostOrQuote() {
        for (quote in listOf("\"", "'", "")) {
            fun img(host: String) = "<IMG alt='diagram' src=" + quote + "https://$host/a?token=1" + quote + ">"
            same(question(body = "text " + img("old.yuketang.cn"), options = listOf(QuestionOption("A", "1", img("old.yuketang.cn")))),
                question(body = "text " + img("new.yuketang.cn"), options = listOf(QuestionOption("A", "1", img("new.yuketang.cn")))))
        }
    }
    @Test fun pageAndParserNotesDoNotChangeAnswerIdentity() {
        same(question(), question(page = 42, notes = listOf("capture metadata")))
        same(question(), question(page = null))
    }
    @Test fun typeTextAndOptionMappingChangesAreSignificant() {
        val before = question()
        listOf(question(type = 1), question(body = "different"), question(options = before.options.reversed()),
            question(options = before.options.take(1)), question(options = listOf(QuestionOption("A", "9", "one"), before.options[1])),
            question(options = listOf(QuestionOption("C", "1", "one"), before.options[1])),
            question(options = listOf(QuestionOption("A", "1", "different"), before.options[1]))).forEach { different(before, it) }
    }
    @Test fun assetRolesOrderAndCountsStillDescribeTheQuestionStructure() {
        different(question(), question(assets = emptyList()))
        different(question(), question(assets = listOf(QuestionAsset("option A", "https://x.yuketang.cn/a"))))
        val images = listOf(QuestionAsset("cover", "a"), QuestionAsset("option A", "b"))
        different(question(assets = images), question(assets = images.reversed()))
    }
    @Test fun serializedQuestionAndFieldOrderKeepTheSameIdentity() {
        val before = question()
        val raw = before.json()
        val reversed = JSONObject()
        raw.keys().asSequence().toList().reversed().forEach { reversed.put(it, raw.get(it)) }
        same(before, QuestionContent.fromJson(JSONObject(reversed.toString())))
    }
    @Test fun textEntitiesAndPresentationFormattingAreNormalized() {
        same(question(body = "<p style='color:red'>one&nbsp; &amp; two</p>"), question(body = "one &#38; two"))
        same(question(body = "<b>one</b>"), question(body = "one"))
        same(question(body = "o<b>n</b>e"), question(body = "one"))
        different(question(body = "x<sup>2</sup>"), question(body = "x2"))
        different(question(body = "x<sub>2</sub>"), question(body = "x<sup>2</sup>"))
    }
    @Test fun visibleUrlTextIsStillCompared() {
        different(question(body = "https://x.yuketang.cn/a?token=old"), question(body = "https://x.yuketang.cn/a?token=new"))
    }
    @Test fun sourceLikeAttributeTextDoesNotBecomeQuestionText() {
        same(question(body = "<img alt=\"src='old'\" src='https://x.yuketang.cn/a'>"),
            question(body = "<img alt=\"src='new'\" src='https://x.yuketang.cn/b'>"))
    }
    @Test fun diagnosticCategoriesContainOnlyFixedFieldNames() {
        assertEquals(listOf("body"), QuestionIdentity.differences(question(), question(body = "private-stem",
            assets = listOf(QuestionAsset("cover", "https://x.yuketang.cn/private?signature=private")))))
    }
}
