package cn.rainnotify.app.answer

import cn.rainnotify.app.question.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionIdentityTest {
    private val base = "https://rain-pri-ups.yuketang.cn/course/slide.png"
    private fun question(type: Int = 2, body: String = "body",
        options: List<QuestionOption> = listOf(QuestionOption("A", "1", "one"), QuestionOption("B", "2", "two")),
        assets: List<QuestionAsset> = listOf(QuestionAsset("cover", "$base?auth_key=old")),
        page: Int? = 1, notes: List<String> = emptyList()) = QuestionContent(type, body, options, assets, page, notes)

    private fun same(a: QuestionContent, b: QuestionContent) {
        assertTrue(QuestionIdentity.differences(a, b).isEmpty())
        assertEquals(AnswerProtocol.fingerprint(a), AnswerProtocol.fingerprint(b))
    }
    private fun different(a: QuestionContent, b: QuestionContent) {
        assertFalse(QuestionIdentity.differences(a, b).isEmpty())
        assertNotEquals(AnswerProtocol.fingerprint(a), AnswerProtocol.fingerprint(b))
    }

    @Test fun rotatingCoverAccessGrantDoesNotChangeQuestionOrMutateDownloadUrl() {
        val before = question()
        val after = question(assets = listOf(QuestionAsset("cover", "$base?auth_key=new")))
        assertNotEquals(before.json().toString(), after.json().toString()) // The former check rejected this.
        same(before, after)
        assertEquals("$base?auth_key=old", before.assets.single().url)
        assertEquals("$base?auth_key=new", after.assets.single().url)
    }

    @Test fun signaturesInStemAndOptionImageSourcesAreComparedAsResources() {
        for (quote in listOf("\"", "'", "")) {
            fun img(key: String) = "<IMG alt='diagram' src=" + quote + base + "?width=400&amp;auth_key=" + key + quote + ">"
            same(question(body = "text " + img("old"), options = listOf(QuestionOption("A", "1", img("old")))),
                question(body = "text " + img("new"), options = listOf(QuestionOption("A", "1", img("new")))))
        }
    }

    @Test fun pageAndParserNotesDoNotChangeAnswerIdentity() {
        same(question(), question(page = 42, notes = listOf("capture metadata")))
        same(question(), question(page = null))
    }

    @Test fun typeStemAndEveryOptionMappingChangeRemainBlocked() {
        val before = question()
        listOf(question(type = 1), question(body = "different"),
            question(options = before.options.reversed()), question(options = before.options.take(1)),
            question(options = listOf(QuestionOption("A", "9", "one"), before.options[1])),
            question(options = listOf(QuestionOption("C", "1", "one"), before.options[1])),
            question(options = listOf(QuestionOption("A", "1", "different"), before.options[1]))
        ).forEach { different(before, it) }
    }

    @Test fun unknownImageQueryParametersAndResourceChangesRemainBlocked() {
        val before = question()
        for (url in listOf("$base/other?auth_key=new", "$base?auth_key=new&version=2",
            "$base?auth_key=new&width=200", "https://other.yuketang.cn/course/slide.png?auth_key=new")) {
            different(before, question(assets = listOf(QuestionAsset("cover", url))))
        }
        different(question(assets = listOf(QuestionAsset("cover", "$base?width=200&auth_key=old"))),
            question(assets = listOf(QuestionAsset("cover", "$base?width=400&auth_key=new"))))
    }

    @Test fun assetRolesOrderAndCountsRemainSignificant() {
        different(question(), question(assets = emptyList()))
        different(question(), question(assets = listOf(QuestionAsset("option A", "$base?auth_key=new"))))
        val images = listOf(QuestionAsset("cover", base), QuestionAsset("option A", "$base/2"))
        different(question(assets = images), question(assets = images.reversed()))
    }

    @Test fun arbitraryHostsSchemesCredentialsAndOrdinaryTextAreNotNormalized() {
        for (url in listOf("https://example.com/a?auth_key=old", "http://rain-pri-ups.yuketang.cn/a?auth_key=old",
            "https://rain-pri-ups.yuketang.cn.evil.test/a?auth_key=old",
            "https://rain-pri-ups.yuketang.cn:8443/a?auth_key=old",
            "https://user@rain-pri-ups.yuketang.cn/a?auth_key=old", "invalid?auth_key=old")) {
            assertEquals(url, QuestionIdentity.imageReference(url))
        }
        for (body in listOf("auth_key=old", "<a href='$base?auth_key=old'>link</a>",
            "<img data-src='$base?auth_key=old'>", "<img alt='auth_key=old' src='$base'>")) {
            different(question(body = body), question(body = body.replace("old", "new")))
        }
    }

    @Test fun serializationRoundTripAndJsonKeyOrderDoNotChangeIdentity() {
        val before = question()
        val reversed = JSONObject()
        val raw = before.json()
        raw.keys().asSequence().toList().reversed().forEach { reversed.put(it, raw.get(it)) }
        same(before, QuestionContent.fromJson(JSONObject(reversed.toString())))
    }

    @Test fun sourceLikeTextInsideAnotherAttributeIsNotMistakenForImageUrl() {
        val before = "<img alt=\"src='$base?auth_key=old'\" src='$base'>"
        val after = before.replace("old", "new")
        different(question(body = before), question(body = after))
        same(question(body = "<img alt='1 > 0' src='$base?auth_key=old'>"),
            question(body = "<img alt='1 > 0' src='$base?auth_key=new'>"))
    }

    @Test fun diagnosticCategoriesDoNotContainQuestionOrCredentials() {
        val changes = QuestionIdentity.differences(question(), question(body = "private-stem",
            assets = listOf(QuestionAsset("cover", "$base/private-image?auth_key=private-signature"))))
        assertEquals(listOf("body", "asset_reference"), changes)
    }
}
