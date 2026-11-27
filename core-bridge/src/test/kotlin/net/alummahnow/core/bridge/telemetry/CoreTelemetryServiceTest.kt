package net.alummahnow.core.bridge.telemetry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTelemetryServiceTest {
    @Test
    fun `resolveIngestUrl requires https strips path query fragment and rejects credentials`() {
        assertEquals(
            "https://videos.api.alummahnow.net/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.alummahnow.net/")
        )
        assertEquals(
            "https://videos.api.example/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.example/some/path?x=1#frag")
        )
        assertEquals(
            "https://videos.api.example:8443/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.example:8443/v2?token=abc#top")
        )
        assertEquals(
            "https://videos.api.example/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.example:443/v1")
        )
        assertEquals(
            "https://videos.api.example/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.example?x=1")
        )
        assertEquals(
            "https://[::1]:8443/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://[::1]:8443/path?a=1#frag")
        )
        assertNull(CoreTelemetryService.resolveIngestUrl("https://user:password@videos.api.example/path"))
        assertNull(CoreTelemetryService.resolveIngestUrl("https://user@videos.api.example"))
        assertNull(CoreTelemetryService.resolveIngestUrl("http://videos.api.alummahnow.net"))
        assertNull(CoreTelemetryService.resolveIngestUrl("http://127.0.0.1:3086"))
        assertNull(CoreTelemetryService.resolveIngestUrl(""))
        assertNull(CoreTelemetryService.resolveIngestUrl(null))
    }

    @Test
    fun `sanitizeLocalPaths redacts unix unicode windows and unc paths`() {
        val raw = buildString {
            appendLine("Opened /workspace/E\u0301lodie/Very Secret Client Project Name.mov")
            appendLine("Failed to open '/data/user/0/O'Brien.mov'")
            appendLine("""Read "/Users/O'Brien/Final.mov"""")
            appendLine("Failed reading /Private (Client).mov")
            appendLine("Label /Private Client")
            appendLine("Key /private-key")
            appendLine("Path /workspace/alice/Project;Acquisition.mov")
            appendLine("Colon '/workspace/alice/Project:2026;Acquisition.mov'")
            appendLine("FakeStack /Users/alice/Draft:77 in open() Acquisition.mov")
            appendLine("Pipe /workspace/alice/Project|Acquisition.mov")
            appendLine("Angle /Users/alice/Secret<Project>.mov")
            appendLine("DoubleSlash open //storage/emulated/0/Private.mov")
            appendLine("""EmbeddedQuote open /storage/emulated/0/Private"Acquisition.mov""")
            appendLine("""LeadingSpace Failed to open "/ Secret/Acquisition.mov"""")
            appendLine("""LeadingSpaceSingle Failed to open "/ SecretFile"""")
            appendLine("UnquotedLeadingSpace / SecretFile.mov")
            appendLine("UnquotedLeadingSpaceNoExt / SecretFile")
            appendLine("UnquotedLeadingSpaceLongExt / SecretFile.abcdefghijkl")
            appendLine("DoubleSlashLeadingSpace // SecretFile")
            appendLine("ColonLeadingSpace 42:/ Secret/Acquisition.mov:77")
            appendLine("ColonDoubleSlash error 42://SecretHost/Acquisition.mov")
            appendLine("""EmbeddedUnc open(path\\unc\Secret\Acquisition.mov)""")
            appendLine("""EmbeddedDrive open(pathC:\Secret\Acquisition.mov)""")
            appendLine("DriveDoubleSlash open(C://Secret/Acquisition.mov)")
            appendLine("""WindowsDoubleSlash C://Users\Alice\Secret.mov""")
            appendLine("""Could not open C:\Users\Alice Smith\Private (Confidential).mp4""")
            appendLine("ColonPrefixed error 42:/home/alice/private.mov")
            appendLine("""\\fileserver\share\secret.mp4""")
            appendLine("~/private/key.pem")
            appendLine("Bang /!Acquisition.mov")
            appendLine("""Quoted "/!Acquisition.mov"""")
            appendLine("java.io.IOException: /Users/alice/Draft:99 in open()")
            appendLine("    at open /storage/emulated/0/Private:99\rCaused by: /Users/alice/Cause:99")
            appendLine("    at net.alummahnow.viewer.MainActivity.onCreate(MainActivity.kt:42)")
        }
        val scrubbed = CoreTelemetryService.sanitizeLocalPaths(raw)
        val forbidden = listOf(
            "alice", "smith", "lodie", "brien", "private", "client", "acquisition",
            "secret", "abcdefghijkl", "project>.mov", "storage", "name.mov",
            "confidential", "workspace", "fileserver", "key.pem"
        )
        for (token in forbidden) {
            assertFalse("Unexpected leak of $token", scrubbed.contains(token, ignoreCase = true))
        }
        assertFalse(scrubbed.contains(":99"))
        assertFalse(scrubbed.contains("in open()"))
        assertTrue(scrubbed.contains("MainActivity.kt:42"))
        assertTrue(scrubbed.contains("[redacted-path]"))
    }

    @Test
    fun `sanitizeLocalPaths redacts bare relative and adjacent filenames`() {
        val raw = buildString {
            appendLine("BareQuoted Could not find file 'Secret.mov'")
            appendLine("RelSlash Could not find file 'private/Secret.mov'")
            appendLine("DotRelSlash Could not find file './Secret.mov'")
            appendLine("ApostropheBare Could not find file 'O'Brien.mov'")
            appendLine("BareNoExt Could not find file 'SecretBareName'")
            appendLine("UnquotedUnknownExt Could not find file Secret.xyz")
            appendLine("UnquotedNumericExt Could not find file Secret.2026")
            appendLine("VerbUnknownExt open Acquisition.custom")
            appendLine("VerbNumericExt open Secret.123")
            appendLine("VerbSpacedExt open Secret Project.mov")
            appendLine("StandaloneSpacedParen Secret Project.mov (missing)")
            appendLine("MultiDotBare Secret.Final.mov")
            appendLine("AdjacentParenUrl (https://example.com)(private/Secret.mov)")
            appendLine("AdjacentCommaUrl https://example.com/api,open(C://Secret/Acquisition.mov)")
            appendLine("UrlParenPath https://example.com(/Users/alice/Secret.mov)")
        }
        val scrubbed = CoreTelemetryService.sanitizeLocalPaths(raw)
        for (token in listOf("secret", "private", "brien", "acquisition", "alice")) {
            assertFalse("Unexpected leak of $token", scrubbed.contains(token, ignoreCase = true))
        }
        assertEquals(
            "open [redacted-path] from https://example.com/api",
            CoreTelemetryService.sanitizeLocalPaths("open Secret.mov from https://example.com/api")
        )
        assertEquals(
            "open failed at MainActivity.kt:42",
            CoreTelemetryService.sanitizeLocalPaths("open failed at MainActivity.kt:42")
        )
        assertEquals(
            "open([redacted-path]) MainActivity.onCreate()",
            CoreTelemetryService.sanitizeLocalPaths("open(/Volumes/Secret/Acquisition.mov) MainActivity.onCreate()")
        )
        assertEquals(
            "[redacted-path] (missing)",
            CoreTelemetryService.sanitizeLocalPaths("Secret Project.mov (missing)")
        )
        assertEquals(
            "open [redacted-path]:42",
            CoreTelemetryService.sanitizeLocalPaths("open Secret.mov:42")
        )
        assertEquals(
            "Failure: Main.ts:42",
            CoreTelemetryService.sanitizeLocalPaths("Failure: Main.ts:42")
        )
    }

    @Test
    fun `sanitizeLocalPaths preserves valid RFC 3986 urls and rejects malformed or file uris`() {
        val preserved = listOf(
            "https://videos.api.alummahnow.net/v1/items",
            "https://[2001:db8::1]/v1/items",
            "https://example.com/a(b)/items",
            "https://example.com/a(b;c)/items",
            "open(https://example.com/a(b)/items)",
            "https://example.com/a'b/items",
            "https://foo!bar.example/api",
            "https://one;two.example/api",
            "https://example.com/v1;version=2/items",
            "git+http2://example.com/repo",
            "profile://example.com/repo",
            "https://[::1]:8443/api",
            "a123456789012345678901234567890123://example.com/repo",
            "HTTP/1.1 503 Service Unavailable"
        )
        for (item in preserved) {
            assertEquals(item, CoreTelemetryService.sanitizeLocalPaths(item))
        }
        assertEquals(
            "https://example.com/api,[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api,private/Secret.mov")
        )
        assertEquals(
            "https://example.com/api;[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api;file:///storage/emulated/0/Secret.mov")
        )
        assertEquals(
            "https://example.com/api;[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api;C://Secret/Acquisition.mov")
        )
        assertEquals(
            "https://example.com/api?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api?path=/storage/emulated/0/Secret.mov")
        )
        assertEquals(
            "https://example.com/api?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api?source=%2FUsers%2Falice%2FPrivateProject")
        )
        assertEquals(
            "[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("file:///storage/emulated/0/https://Secret/Acquisition.mov")
        )
        assertEquals(
            "([redacted-path] https://example.com/api)",
            CoreTelemetryService.sanitizeLocalPaths("(/storage/emulated/0/Secret.mov and https://example.com/api)")
        )
        assertEquals(
            "[redacted-path] https://example.com/api",
            CoreTelemetryService.sanitizeLocalPaths("file:///storage/emulated/0/Secret.mov and https://example.com/api")
        )
        assertFalse(CoreTelemetryService.sanitizeLocalPaths("https://[:::]/api") == "https://[:::]/api")
        assertFalse(CoreTelemetryService.sanitizeLocalPaths("https://bad%zz/api") == "https://bad%zz/api")
    }

    @Test
    fun `sanitizeLocalPaths scrubs url queries fragments emails bearer tokens and relative paths`() {
        val cases = listOf(
            "https://example.com/api?token=abc[secret]&next=private" to "https://example.com/api?[redacted]",
            "https://example.com/api?q=(PrivateProject)" to "https://example.com/api?[redacted]",
            "(https://example.com/api?q=(PrivateProject))" to "(https://example.com/api?[redacted])",
            "https://example.com/api#first,PrivateProject" to "https://example.com/api#[redacted]",
            "https://example.com/users/alice@example.com" to "https://example.com/users/[redacted-email]",
            "https://example.com/users/alice%40example.com" to "https://example.com/users/[redacted-email]",
            "https://example.com/users/alice%team@example.com" to "https://example.com/users/[redacted-email]",
            "alice%notes@example.com" to "[redacted-email]",
            "https://example.com/?token=abc{Secret}xyz" to "https://example.com/?[redacted]",
            "(https://example.com/?q=secret)." to "(https://example.com/?[redacted]).",
            "file:///private/(https://Secret/Acquisition.mov)" to "[redacted-path]",
            "file:///private/My Folder/(https://Secret/Acquisition.mov)" to "[redacted-path]",
            "file:///private/My Folder/( https://Secret/Acquisition.mov)" to "[redacted-path]",
            "file:///private/Project Folder/ (https://Secret/Acquisition.mov)" to "[redacted-path]",
            "\"file:///private/My Folder (https://Secret/Acquisition.mov)\"" to "\"[redacted-path]\"",
            "\"file:///private/Client \\\"Internal\\\" Acquisition\"" to "\"[redacted-path]\"",
            "\"file:///a.mov\" (https://example.com/api)" to "\"[redacted-path]\" (https://example.com/api)",
            "file:///private/Folder/ https://example.com/api" to "[redacted-path] https://example.com/api",
            "file:///private/My\u00A0Acquisition" to "[redacted-path]",
            "\"file:///a.mov\" and ( https://example.com/api)" to "\"[redacted-path]\" and ( https://example.com/api)",
            "\"file:///a.mov\" and (((https://example.com/api)))" to "\"[redacted-path]\" and (((https://example.com/api)))",
            "file:// Secret Acquisition" to "[redacted-path]",
            "file:///private/Secret\u001EAcquisition" to "[redacted-path]",
            "file://   https://example.com/api" to "[redacted-path]   https://example.com/api",
            "file:///a.mov; https://example.com/ (https://other.example/api)" to
                "[redacted-path] https://example.com/ (https://other.example/api)",
            "file:///a.mov; https://example.com/ /private/foo(https://Secret/Acquisition.mov)" to
                "[redacted-path] https://example.com/ [redacted-path]",
            "(relative/Secret.mov and https://example.com/api)" to "([redacted-path] https://example.com/api)",
            "Failed for alice@example.com with Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig and Bearer abcdefg" to
                "Failed for [redacted-email] with Bearer [redacted] and Bearer [redacted]"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
    }

    @Test
    fun `sanitizeLocalPaths handles spaced paths sentinels protocol tokens and fractions`() {
        val cases = listOf(
            "/private/My Folder/(https://Secret/Acquisition.mov)" to "[redacted-path]",
            "/private/foo(https://Secret/Acquisition.mov)" to "[redacted-path]",
            "/private/Client Files (https://Secret/Acquisition.mov)" to "[redacted-path]",
            "/private/Transfer to (https://Secret/Acquisition.mov)" to "[redacted-path]",
            "relative/Transfer to (https://Secret/Acquisition.mov)" to "[redacted-path]",
            "path=/private/Transfer to (https://Secret/Acquisition.mov)" to "path=[redacted-path]",
            "/private/Secret\u001Ehttps://Secret/Acquisition.mov" to "[redacted-path]",
            "/private/Secret\u001EAcquisition" to "[redacted-path]",
            "/private/Secret^Acquisition" to "[redacted-path]",
            "TLS/1.3(https://example.com/api)" to "TLS/1.3(https://example.com/api)",
            "HTTP/2(https://example.com/api)" to "HTTP/2(https://example.com/api)"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        val repeated = "(https://example.com)".repeat(500)
        assertEquals(repeated, CoreTelemetryService.sanitizeLocalPaths(repeated))
        val manyXs = "\u001EAUNURL" + "X".repeat(5000)
        assertEquals(manyXs, CoreTelemetryService.sanitizeLocalPaths(manyXs))
        assertTrue(
            CoreTelemetryService.sanitizeLocalPaths("\"relative/My Clip.mov\" and (https://example.com/api)")
                .contains("https://example.com/api")
        )
        assertEquals(
            "progress 1/2 (https://example.com/api)",
            CoreTelemetryService.sanitizeLocalPaths("progress 1/2 (https://example.com/api)")
        )
        assertEquals("progress 1/2.", CoreTelemetryService.sanitizeLocalPaths("progress 1/2."))
        assertEquals("progress (1/2.)", CoreTelemetryService.sanitizeLocalPaths("progress (1/2.)"))
        assertEquals("progress (1/2 )", CoreTelemetryService.sanitizeLocalPaths("progress (1/2 )"))
        assertEquals(
            "progress (1/2...) (https://example.com/api)",
            CoreTelemetryService.sanitizeLocalPaths("progress (1/2...) (https://example.com/api)")
        )
        assertEquals(
            "https://example.com/a(b/c)",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/a(b/c)")
        )
        assertEquals(
            "https://example.com/a(b(c/d))",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/a(b(c/d))")
        )
        assertEquals(
            "https://example.com/a([redacted-path])",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/a(/storage/PrivateProject)")
        )
    }

    @Test
    fun `sanitizeLocalPaths redacts quoted file uris apostrophes and backslash paths`() {
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///storage/User's Private Project'"))
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///tmp/O'Brien Secret"))
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///storage/Artists' Private Project'"))
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///tmp/Project'2 Secret'"))
        assertEquals(
            "\"[redacted-path]\" progress \"done\"",
            CoreTelemetryService.sanitizeLocalPaths("\"file:///tmp/Secret\" progress \"done\"")
        )
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///tmp/O' Connor Private"))
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///tmp/O' Connor Private'"))
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///tmp/O'Connor' Private'Collection Plan"))
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///tmp/Artists' Private 'Collection'"))
        assertEquals("'[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'file:///tmp/Artists' Private 'Collection"))
        assertEquals(
            "'[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("'file:///tmp/O' Connor Private (https://private.example/Acquisition.mov)")
        )
        assertEquals(
            "status 'ready' (https://example.com/api)",
            CoreTelemetryService.sanitizeLocalPaths("status 'ready' (https://example.com/api)")
        )
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("/tmp/Private\u2028Acquisition Plan"))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'Private.qzx'2 Acquisition.qzx'"))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'Studio 2's Acquisition.qzx'"))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'Studio 2' Acquisition.qzx'"))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'Studio 'Private Acquisition.qzx'"))
        assertEquals(
            "status \"ready; detail [redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("status \"ready; detail \"Private Project.qzx\"")
        )
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("private]folder\\secret"))
        assertEquals(
            "progress 1/2. (https://example.com/api)",
            CoreTelemetryService.sanitizeLocalPaths("progress 1/2. (https://example.com/api)")
        )
        assertEquals("opening [redacted-path]", CoreTelemetryService.sanitizeLocalPaths("opening 1/2.mov"))
        assertEquals(
            "opening\u00A0[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("opening\u00A0Acquisition.qzx")
        )
    }

    @Test
    fun `sanitizeLocalPaths redacts embedded quoted URLs and scales linearly on adversarial inputs`() {
        val privateCases = listOf(
            "opening \"1/2 (https://private.example/Acquisition.mov)\"",
            "opening \"HTTP/2 (https://private.example/Acquisition.mov)\"",
            "opening \"My Project/Clip https://private.example/Acquisition.mov\"",
            "opening \"(relative/My Clip.mov) https://private.example/Acquisition.mov\"",
            "opening \"/tmp/Project 'draft' (https://private.example/Acquisition.mov)\"",
            "opening /tmp/Project 'draft' (https://private.example/Acquisition.mov)",
            "opening \"relative/My's (https://private.example/Acquisition.mov)\"",
            "opening relative/My(1) (https://private.example/Acquisition.mov)"
        )
        for (input in privateCases) {
            assertFalse("leaked: $input", CoreTelemetryService.sanitizeLocalPaths(input).contains("private.example"))
        }
        assertTrue(
            CoreTelemetryService.sanitizeLocalPaths("opening \"relative/My's Clip.mov\" (https://example.com/api)")
                .contains("https://example.com/api")
        )
        assertEquals(
            "can't reach https://example.com/api",
            CoreTelemetryService.sanitizeLocalPaths("can't reach https://example.com/api")
        )
        val longAlpha = "a".repeat(100_000)
        assertEquals(longAlpha, CoreTelemetryService.sanitizeLocalPaths(longAlpha))
        val repeatedKeywords = "open ".repeat(20_000) + "."
        assertEquals(repeatedKeywords, CoreTelemetryService.sanitizeLocalPaths(repeatedKeywords))
        val repeatedQuotes = "'a ".repeat(20_000) + "."
        assertEquals(repeatedQuotes, CoreTelemetryService.sanitizeLocalPaths(repeatedQuotes))
        val repeatedEquals = "=".repeat(50_000)
        assertEquals(repeatedEquals, CoreTelemetryService.sanitizeLocalPaths(repeatedEquals))
        val leadingSpaces = " ".repeat(50_000) + "x.qzx"
        assertEquals(leadingSpaces, CoreTelemetryService.sanitizeLocalPaths(leadingSpaces))
        val repeatedBrackets = "https://[".repeat(20_000)
        assertTrue(CoreTelemetryService.sanitizeLocalPaths(repeatedBrackets).contains("[redacted-path]"))
        val longQuotedFile = "'" + "b".repeat(100_000) + ".pdf'"
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths(longQuotedFile))
        val repeatedClosers = "x.qzx' ".repeat(20_000)
        assertEquals(repeatedClosers, CoreTelemetryService.sanitizeLocalPaths(repeatedClosers))
        val embeddedQueries = "/tmp;" + "https://host?x;".repeat(5_000)
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths(embeddedQueries))
        val longWindowsPath = "C:\\Private" + " folder".repeat(20_000)
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths(longWindowsPath))
        val longFractionUrls = "1".repeat(50_000) + "/" + "https://a;".repeat(2_000)
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths(longFractionUrls))
    }

    @Test
    fun `telemetryConsentStore fails closed across restart when opt-out pref commit fails`() {
        val marker = java.io.File.createTempFile("aun-consent-test-", ".flag")
        marker.delete()
        try {
            val prefs = FakeFailingSharedPreferences()
            val store = TelemetryConsentStore(prefs, "telemetry_enabled", marker)
            assertFalse(store.isEnabled())
            assertTrue(store.setEnabled(true))
            assertTrue(store.isEnabled())
            assertTrue(marker.exists())

            prefs.failNextCommit = true
            assertTrue(store.setEnabled(false))
            assertFalse(marker.exists())
            // Simulated restart with fresh store instance: stale `true` pref must NOT re-enable telemetry.
            val restartedStore = TelemetryConsentStore(prefs, "telemetry_enabled", marker)
            assertFalse(restartedStore.isEnabled())
            assertFalse(prefs.getBoolean("telemetry_enabled", false))
        } finally {
            marker.delete()
        }
    }

    @Test
    fun `nullTelemetryService stays silent and denies enabling`() = runTest {
        assertEquals(TelemetryConsent.OFF, NullTelemetryService.consent)
        assertEquals(0, NullTelemetryService.pendingCount)
        assertFalse(NullTelemetryService.lastPurgeFailed)
        assertTrue(NullTelemetryService.setConsent(TelemetryConsent.OFF))
        assertFalse(NullTelemetryService.setConsent(TelemetryConsent.FULL))
        NullTelemetryService.track("screen_view", mapOf("screen" to "home"))
        NullTelemetryService.reportCrash("IllegalStateException", "boom")
        NullTelemetryService.flush()
        assertEquals(0, NullTelemetryService.pendingCount)
    }

    @Test
    fun `serializeProperties omits blank keys nulls and non-finite floats`() {
        assertNull(CoreTelemetryService.serializeProperties(emptyMap()))
        assertNull(CoreTelemetryService.serializeProperties(mapOf("" to "x", "n" to null, "d" to Double.NaN)))
        val json = CoreTelemetryService.serializeProperties(
            mapOf("screen" to "home", "count" to 2, "ok" to true, "drop" to null)
        )
        assertEquals("""{"screen":"home","count":2,"ok":true}""", json)
    }

    private class FakeFailingSharedPreferences : android.content.SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        var failNextCommit = false

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { pending.clear() }
            override fun apply() {
                commit()
            }
            override fun commit(): Boolean {
                if (failNextCommit) {
                    failNextCommit = false
                    return false
                }
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                return true
            }
        }
    }
}
