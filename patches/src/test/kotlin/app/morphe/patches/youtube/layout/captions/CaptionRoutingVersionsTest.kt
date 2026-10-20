package app.morphe.patches.youtube.layout.captions

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptionRoutingVersionsTest {
    @Test
    fun nativeRoutingRunsOnlyOnVerifiedLayouts() {
        assertTrue(usesNativeCaptions("21.20.405"))
        assertTrue(usesNativeCaptions("21.41.525"))
        for (version in listOf(
            "20.21.37", "20.51.39", "21.16.256", "21.23.492",
            "21.28.208", "21.42.241", "21.41.524"
        )) {
            assertFalse(usesNativeCaptions(version), version)
        }
    }
}
