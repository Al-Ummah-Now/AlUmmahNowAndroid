package net.alummahnow.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSupportLinkResolutionTest {
    @Test
    fun resolvesAllLinksInTheBuildEnvironment() {
        val domain = if (BuildConfig.ENV_NAME == "qa") "qa.alummahnow.net" else "alummahnow.net"
        assertEquals(
            listOf(
                "https://videos.$domain/ar/help",
                "https://videos.$domain/ar/contact",
                "https://www.$domain/ar/privacy/",
                "https://www.$domain/ar/terms/"
            ),
            TvSupportLink.entries.map { it.resolveUrl("ar-SA", BuildConfig.WEB_HOST) }
        )
    }
}
