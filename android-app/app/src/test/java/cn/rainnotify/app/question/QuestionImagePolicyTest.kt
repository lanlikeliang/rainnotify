package cn.rainnotify.app.question

import cn.rainnotify.app.auth.RainFailure
import org.junit.Assert.*
import org.junit.Test

class QuestionImagePolicyTest {
    @Test fun normalizesKnownCdnToTlsAndPreservesSignedQuery() {
        val url = QuestionImagePolicy.url("http://qn-scd1.yuketang.cn/q.png?token=fixture#fragment")
        assertEquals("https://qn-scd1.yuketang.cn/q.png?token=fixture", url.toString())
        assertEquals("www.yuketang.cn", QuestionImagePolicy.url("/q.png").host)
    }

    @Test fun rejectsHostLookalikesCredentialsPrivateTargetsAndUnsupportedSchemes() {
        for (url in listOf("https://yuketang.cn.evil.invalid/q.png", "https://127.0.0.1/q.png",
            "https://user:secret@qn-scd1.yuketang.cn/q.png", "https://qn-scd1.yuketang.cn:8443/q.png",
            "file:///data/private", "data:image/png;base64,abcd", "rain://error/upload-error",
            "https://evil-yuketang.cn/q.png")) {
            assertThrows(url, RainFailure::class.java) { QuestionImagePolicy.url(url) }
        }
    }
}
