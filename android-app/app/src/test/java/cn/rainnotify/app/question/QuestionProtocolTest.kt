package cn.rainnotify.app.question

import cn.rainnotify.app.auth.RainFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionProtocolTest {
    private val ref = QuestionRef("10", "20", "30", "40", 2)
    private fun parse(slides: String) = QuestionProtocol.parse(ref,
        JSONObject("""{"code":0,"data":{"slides":""" + slides + "}}"))

    @Test fun selectsCorrectProblemAndKeepsOriginalTextOptionsAndImage() {
        val question = parse("""[
          {"id":39,"index":1,"problem":{"problemId":29,"body":"OTHER"}},
          {"id":40,"index":2,"cover":"https://qn-scd1.yuketang.cn/slide.png",
           "problem":{"problemId":30,"problemType":2,"body":"<p>请选择正确项</p>",
            "options":[{"key":"A","value":"<b>甲</b>"},{"key":"B","value":"乙"}],
            "answer":["B"],"result":["A"],"analysis":"PRIVATE_ANSWER"}}
        ]""")
        assertEquals(2, question.type)
        assertEquals("多选", question.typeName)
        assertEquals("<p>请选择正确项</p>", question.bodyHtml)
        assertEquals(listOf("A", "B"), question.options.map { it.label })
        assertEquals("<b>甲</b>", question.options.first().html)
        assertEquals("https://qn-scd1.yuketang.cn/slide.png", question.assets.single().url)
        assertEquals(2, question.pageIndex)
        assertFalse(question.json().toString().contains("PRIVATE_ANSWER"))
        assertFalse(question.json().has("answer"))
        assertFalse(question.json().has("result"))
        assertFalse(question.toString().contains("请选择"))
    }

    @Test fun imageOnlyStemAndBulletLabelsRemainVisibleWithoutInventedOcr() {
        val question = parse("""[{"id":40,"cover":"https://qn-scd1.yuketang.cn/q.jpg",
          "problem":{"problemId":30,"problemType":"1","body":"",
          "bullets":[{"label":"甲","key":"a"},{"label":"乙","key":"b"}],
          "options":[{"key":"SHOULD_NOT_REPLACE_BULLETS"}]}}]""")
        assertEquals("", question.bodyHtml)
        assertEquals(listOf("甲", "乙"), question.options.map { it.label })
        assertEquals(listOf("a", "b"), question.options.map { it.key })
        assertTrue(question.options.all { it.html.isEmpty() })
        assertTrue(question.notes.any { it.contains("独立文字题干") })
    }

    @Test fun extractsStemAndOptionImagesAndDeduplicatesCover() {
        val question = parse("""[{"id":40,"cover":"https://qn-scd1.yuketang.cn/a.png",
          "problem":{"problemId":"30","problemType":6,
          "body":"题干<img src='https://qn-scd1.yuketang.cn/a.png'><img src=\"https://qn-scd1.yuketang.cn/b.png?a=1&amp;b=2\">",
          "options":[{"key":"T","value":"正确<img src=https://qn-scd1.yuketang.cn/c.png>"},
                     {"key":"F","value":"错误"}]}}]""")
        assertEquals("判断", question.typeName)
        assertEquals(3, question.assets.size)
        assertTrue(question.assets[1].url.endsWith("?a=1&b=2"))
        assertEquals("选项 T 附图", question.assets[2].label)
        assertEquals(listOf("T", "F"), question.options.map { it.key })
    }

    @Test fun rejectsMissingAmbiguousOrMismatchedProblemInsteadOfChoosingByPage() {
        for (slides in listOf(
            """[{"id":40,"index":2,"problem":{"problemId":99}}]""",
            """[{"id":40,"problem":{"problemId":30}},{"id":41,"problem":{"problemId":30}}]""",
            """[{"id":99,"problem":{"problemId":30}}]""")) {
            assertThrows(RainFailure::class.java) { parse(slides) }
        }
    }

    @Test fun partialAndUnknownStructuresProduceNotesNotFabricatedData() {
        val question = parse("""[{"id":40,"problem":{"problemId":30,"problemType":999,
          "body":{"unexpected":"text"},"options":[]}}]""")
        assertTrue(question.typeName.contains("未知"))
        assertEquals("", question.bodyHtml)
        assertTrue(question.notes.any { it.contains("结构暂无法识别") })
        assertTrue(question.notes.any { it.contains("结构化选项") })
    }

    @Test fun numericOptionKeysAndAbsentSlideIdArePreserved() {
        val question = parse("""[{"problem":{"problemId":"30","problemType":6,"body":"判断",
          "options":[{"key":1,"label":"正确","value":""},{"key":0,"label":"错误","value":""}]}}]""")
        assertEquals(listOf("1", "0"), question.options.map { it.key })
        assertEquals(listOf("正确", "错误"), question.options.map { it.label })
    }

    @Test fun rejectsBusinessErrorsAndOversizedContent() {
        assertThrows(RainFailure::class.java) {
            QuestionProtocol.parse(ref, JSONObject("""{"code":403,"data":{"slides":[]}}"""))
        }
        val slide = JSONObject().put("id", 40).put("problem", JSONObject().put("problemId", 30)
            .put("body", "x".repeat(65_537)))
        assertThrows(RainFailure::class.java) { parse("[" + slide + "]") }
    }

    @Test fun contentAndReferencesRoundTripWithoutSensitiveToString() {
        val parsed = parse("""[{"id":40,"problem":{"problemId":30,"problemType":1,
          "body":"PRIVATE_STEM","options":[{"key":"A","value":"PRIVATE_OPTION"}]}}]""")
        val restored = QuestionContent.fromJson(parsed.json())
        assertEquals(parsed.json().toString(), restored.json().toString())
        assertFalse(restored.options.first().toString().contains("PRIVATE"))
        assertEquals(ref.json().toString(), QuestionRef.fromJson(ref.json()).json().toString())
    }
}
