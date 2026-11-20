package net.alummahnow.core.bridge.studio

import net.alummahnow.core.AunException
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.core.StudioMappers
import net.alummahnow.core.bridge.studio.core.isFeatureOff
import net.alummahnow.core.bridge.studio.core.mapCoreError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFeaturesTest {
    @Test fun `only JSON true turns a key on`() {
        val f = StudioMappers.features(
            """{"success":true,"version":1,"features":{"upload":true,"live":false,""" +
                """"captionsTranscribe":"true","quranText":1,"futureThing":true}}"""
        )
        assertTrue(f.upload)
        assertTrue("futureThing" in f)
        assertFalse("live" in f)
        assertFalse("captionsTranscribe" in f)
        assertFalse("quranText" in f)
    }

    @Test fun `a missing key is hidden`() {
        val f = StudioMappers.features("""{"version":1,"features":{"live":true}}""")
        assertFalse(f.upload)
        assertTrue("live" in f)
    }

    @Test fun `an unreadable answer hides everything`() {
        assertEquals(UserFeatures.NONE, StudioMappers.features("not json"))
        assertEquals(UserFeatures.NONE, StudioMappers.features("""{"version":1}"""))
        assertEquals(UserFeatures.NONE, StudioMappers.features("[]"))
    }

    @Test fun `a wrapped answer is read too`() {
        assertTrue(StudioMappers.features("""{"data":{"features":{"upload":true}}}""").upload)
    }

    @Test fun `404 feature_off maps to FeatureOff, any other 404 stays NotFound`() {
        val off = AunException.NotFound("""{"success":false,"code":"feature_off"}""")
        assertTrue(isFeatureOff(off))
        assertTrue(mapCoreError(off) is StudioError.FeatureOff)
        val missing = AunException.NotFound("Video not found")
        assertFalse(isFeatureOff(missing))
        assertTrue(mapCoreError(missing) is CoreError.NotFound)
    }

    @Test fun `402 maps to UpgradeRequired, which hides`() {
        val e = mapCoreError(AunException.Http(status = 402u, code = "upgrade_required", detail = "Upgrade"))
        assertTrue(e is StudioError.UpgradeRequired)
        assertTrue(e.hidesFeature())
    }
}
