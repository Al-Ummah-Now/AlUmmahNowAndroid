package net.alummahnow.core.bridge

import org.junit.Test
import org.junit.Assert.assertEquals

class AunClientTest {
    @Test fun namesTheAppVersionAndOs() {
        assertEquals("aun-studio/1.4.0 (Android 15)", AunClient.value("studio", "1.4.0", "15"))
        assertEquals("aun-admin/2.0 (Android 14)", AunClient.value("admin", "2.0", "14"))
    }

    @Test fun keepsOnlyHeaderSafeCharacters() {
        assertEquals("aun-studio/1.4.0-beta1 (Android 15)", AunClient.value("studio", "1.4.0-beta (1)", "15"))
        assertEquals("aun-studio/0 (Android)", AunClient.value("studio", "", null))
    }
}
