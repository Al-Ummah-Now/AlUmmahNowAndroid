package net.alummahnow.core.bridge.telemetry

import java.io.IOException
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTelemetryDiagnosticSanitizerTest {
    @Test
    fun `diagnostic sanitizer handles JOSE edge cases unicode phones credentials userinfo and checked toString`() {
        val longHeaderJson = " {\"alg\":\"HS256\",\"pad\":\"" + "a".repeat(600) + "\"}"
        val longHeaderB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(longHeaderJson.toByteArray(Charsets.UTF_8))
        val longSignedJwt = "$longHeaderB64.eyJzdWIiOiJhbGljZSJ9.sigValue123"
        val throwingProp = object {
            override fun toString(): String = throw IOException("checked failure")
        }
        val props = CoreTelemetryService.serializeProperties(
            mapOf(
                "algNone" to "IHsiYWxnIjoibm9uZSJ9.eyJzdWIiOiJhbGljZSJ9.",
                "longJwt" to longSignedJwt,
                "safeId" to "eyJunction.load.failed",
                "nbText" to "+33\u202F6\u202F12\u202F34\u202F56\u202F78",
                "detailText" to "12 34 56 78 and 2026-11-27",
                "creds" to """{"challenge.verificationCode":"482731"} {"home\u0041ddress":"12 Cedar Rd"} """ +
                    """{"userId":["alice42", "bob73"]} status="password=first;second" """ +
                    "password=\u201Corchid velvet\u201D displayName=Alice Smith username=Jane Doe C:ProjectCodename C:秘密 " +
                    """pass="correct horse battery staple" """ +
                    """{"pass":"multi word phrase"} fullName="Alice Wonderland" pwd=shortsecret""",
                "user_id" to "alice123",
                "p\\u0061ssword" to "violet-sparrow",
                "token violet7" to "ok",
                "http://u:p@localhost/x" to "https://1drv.ms/u/s!Aq82F1ZLPx0K " +
                    "https://example.test/password%3Dshortcredential " +
                    "https%3A%2F%2Fexample.test%2F%3Fcode%3Dorchid-meadow",
                "badVal" to throwingProp
            )
        )
        assertNotNull(props)
        val json = props!!
        val forbidden = listOf(
            "eyJzdWIiOiJhbGljZSJ9", "sigValue123", "56\u202F78", "12 34 56 78",
            "482731", "first;second", "second", "alice123", "alice42", "bob73", "violet-sparrow", "秘密", "12 Cedar Rd",
            "orchid", "velvet", "shortcredential", "horse battery staple", "word phrase",
            "Alice", "Wonderland", "Smith", "Jane", "Doe", "ProjectCodename", "violet7",
            "orchid-meadow", "shortsecret", "u:p@localhost", "u:[redacted-email]", "Aq82F1ZLPx0K"
        )
        for (token in forbidden) {
            assertFalse("Expected redacted token: $token", json.contains(token))
        }
        assertTrue(json.contains("eyJunction.load.failed"))
        assertTrue(json.contains("2026-11-27"))
        assertFalse(CoreTelemetryService.sanitizePropertyKey("phone_2025550123").contains("2025550123"))
        assertEquals(
            "http://[redacted]@localhost/x",
            CoreTelemetryService.sanitizeLocalPaths("http://u:p@localhost/x")
        )
    }

    @Test
    fun `diagnostic sanitizer redacts encoded URL secrets phones emails handles and invalid locale`() {
        val props = CoreTelemetryService.serializeProperties(
            mapOf(
                "urls" to "alummahnow://watch/v1 " +
                    "https://uploads.example.test/upload/853876e8-cc19-465d-bafa-7459aba00936 " +
                    "https://example.org/assets/%41bCdEfGhIjKlMnOpQrStUvWxYz0123456789 " +
                    "https://example.test/lookup/%2B%31%32%30%32%35%35%35%30%31%32%33 " +
                    "https://example.test/contact/%2B%31%20%32%30%32%20%35%35%35%20%30%31%32%33 " +
                    "https://example.test/lookup/202%C2%A0555%C2%A00123 " +
                    "https://example.test/%2572eset-password/AbCdEf123456 " +
                    "https://example.test/lookup/alice%2540example.test " +
                    "https://example.com/users/AliceSmith " +
                    "https://example.com/%75sers/BobJones " +
                    "https%25253A%25252F%25252Fexample.test%25252Flogin%25253Ftoken%25253Ds3cr3t " +
                    "ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIJ"
            )
        )
        assertNotNull(props)
        val json = props!!
        val forbidden = listOf(
            "watch/v1", "853876e8-cc19-465d-bafa-7459aba00936", "bCdEfGhIjKlMnOpQrStUvWxYz0123456789",
            "%2B%31%32%30%32", "%2B%31%20%32%30%32", "202%C2%A0555", "AbCdEf123456",
            "alice%2540example.test", "AliceSmith", "BobJones", "s3cr3t", "ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIJ"
        )
        for (token in forbidden) {
            assertFalse("Expected redacted token: $token", json.contains(token))
        }
        assertEquals("en", sanitizeLocaleTag("/Users/Ada/Private.mov"))
        assertEquals("ar-SA", sanitizeLocaleTag("ar-SA"))
    }

    @Test
    fun `tryLockUninterruptibly acquires lock and preserves interrupt status on interrupted threads`() {
        val lock = ReentrantLock()
        var acquiredUncontended = false
        var remainedInterrupted = false
        val worker = Thread {
            Thread.currentThread().interrupt()
            acquiredUncontended = CoreTelemetryService.tryLockUninterruptibly(lock, 250L)
            if (acquiredUncontended) lock.unlock()
            remainedInterrupted = Thread.currentThread().isInterrupted
        }
        worker.start()
        worker.join(5_000)
        assertTrue(acquiredUncontended)
        assertTrue(remainedInterrupted)

        lock.lock()
        var acquiredContended = false
        var contendedInterrupted = false
        val contendedWorker = Thread {
            Thread.currentThread().interrupt()
            acquiredContended = CoreTelemetryService.tryLockUninterruptibly(lock, 500L)
            if (acquiredContended) lock.unlock()
            contendedInterrupted = Thread.currentThread().isInterrupted
        }
        contendedWorker.start()
        Thread.sleep(50)
        lock.unlock()
        contendedWorker.join(5_000)
        assertTrue(acquiredContended)
        assertTrue(contendedInterrupted)
        assertEquals("IllegalStateException", CoreTelemetryService.sanitizeCrashKindIdentifier("IllegalStateException"))
        assertEquals(
            "UnhandledException",
            CoreTelemetryService.sanitizeCrashKindIdentifier("Could not load profile for Jane Doe")
        )
    }

    @Test
    fun `structured event serialization caps property count key length and redacts free-form personal names`() {
        val overLimit = (1..55).associate { "metric_$it" to true }
        val trackJson = CoreTelemetryPropertySanitizer.serializeProperties(
            properties = overLimit,
            maxProperties = CoreTelemetryPropertySanitizer.MAX_EVENT_PROPERTIES,
            structuredValuesOnly = true
        )!!
        assertEquals(50, Regex("\"metric_\\d+\"").findAll(trackJson).count())

        val errorJson = CoreTelemetryPropertySanitizer.serializeProperties(
            properties = overLimit + ("code" to "override"),
            maxProperties = CoreTelemetryPropertySanitizer.MAX_ERROR_EVENT_PROPERTIES,
            reservedKeys = setOf("code"),
            structuredValuesOnly = true
        )!!
        assertEquals(49, Regex("\"metric_\\d+\"").findAll(errorJson).count())
        assertFalse(errorJson.contains("\"code\""))

        val longKey = "k".repeat(80)
        val longKeyJson = CoreTelemetryPropertySanitizer.serializeProperties(
            properties = mapOf(longKey to "ok", "detail" to "Mary Jones", "backend" to "exoplayer"),
            structuredValuesOnly = true
        )!!
        assertTrue(longKeyJson.contains("\"${"k".repeat(64)}\":\"ok\""))
        assertFalse(longKeyJson.contains("Mary Jones"))
        assertTrue(longKeyJson.contains("\"detail\":\"[redacted]\""))
        assertTrue(longKeyJson.contains("\"backend\":\"exoplayer\""))
        assertEquals("[redacted]", CoreTelemetryPropertySanitizer.sanitizeStructuredToken("Mary Jones opened profile"))
        assertEquals("player.ready", CoreTelemetryPropertySanitizer.sanitizeStructuredToken("player.ready"))
    }
}
