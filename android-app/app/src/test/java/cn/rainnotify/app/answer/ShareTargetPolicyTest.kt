package cn.rainnotify.app.answer

import org.junit.Assert.*
import org.junit.Test

class ShareTargetPolicyTest {
    @Test fun preservesKnownImageReceiverWhenAppHasSeveralTargets() {
        assertEquals("image", ShareTargetPolicy.choose("image", listOf("external", "image")))
    }
    @Test fun acceptsUniqueRenamedPublicReceiverWithoutGuessingAmbiguousOnes() {
        assertEquals("new-image", ShareTargetPolicy.choose("image", listOf("new-image", "new-image")))
        assertNull(ShareTargetPolicy.choose("image", listOf("unknown-a", "unknown-b")))
        assertNull(ShareTargetPolicy.choose("image", emptyList()))
    }
}
