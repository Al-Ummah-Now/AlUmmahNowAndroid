package net.alummahnow.core.bridge.links

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * An in-app destination for a link to the videos site (Android App Links), `alummahnow.link` short links, or a
 * custom-scheme link.
 */
sealed interface AppLink {
    /** `en` or `ar` when the link carried a locale prefix or `?lang=`, else null. */
    val lang: String?

    data class Video(val id: String, override val lang: String? = null) : AppLink
    data class Short(val id: String, override val lang: String? = null) : AppLink
    data class Live(val id: String, override val lang: String? = null) : AppLink
    data class Playlist(val id: String, override val lang: String? = null) : AppLink
    data class Post(val id: String, override val lang: String? = null) : AppLink

    /** A channel page, `/@handle` (the handle without `@`, percent-decoded: handles may be Arabic). */
    data class Channel(val handle: String, override val lang: String? = null) : AppLink

    /** A channel page addressed by channel id (`/c/{id}` or `/channel/{id}`). */
    data class ChannelId(val id: String, override val lang: String? = null) : AppLink

    /** `/app/auth/callback?…`: the sign-in redirect over https. [query] is the raw query string. */
    data class AuthCallback(val query: String?) : AppLink {
        override val lang: String? get() = null
    }

    /** A Studio page: optional channel, section and video (to edit), mirroring the web Studio's routes. */
    data class Studio(
        val channelId: String? = null,
        val section: StudioSection? = null,
        val videoId: String? = null,
        override val lang: String? = null
    ) : AppLink
}

enum class StudioSection { DASHBOARD, CONTENT, ANALYTICS }

/** Parsed link together with optional playback query parameters (`?t=`, `?list=`, `?index=`, `?lang=`). */
data class AppLinkInvocation(
    val link: AppLink,
    val startSeconds: Long? = null,
    val playlistId: String? = null,
    val playlistIndex: Int? = null,
    val lang: String? = link.lang
)

/**
 * Canonical `alummahnow.link` short-link builder (Epic 22 / #20), matching `aun-common::short_links` and
 * `packages/shared-types/src/short-links.ts`.
 */
object ShortLinks {
    const val PROD_HOST = "alummahnow.link"
    const val QA_HOST = "qa.alummahnow.link"
    private const val MAX_TIMESTAMP_SECONDS = 86_400L

    fun video(
        id: String,
        shortLinkHost: String = PROD_HOST,
        startSeconds: Long? = null,
        playlistId: String? = null,
        playlistIndex: Int? = null,
        lang: String? = null
    ): String? {
        if (!AppLinkParser.isSafeId(id)) return null
        val query = mutableListOf<Pair<String, String>>()
        if (startSeconds != null && startSeconds in 1..MAX_TIMESTAMP_SECONDS) {
            query += "t" to startSeconds.toString()
        }
        if (playlistId != null && AppLinkParser.isSafeId(playlistId)) {
            query += "list" to playlistId
            if (playlistIndex != null && playlistIndex >= 0) {
                query += "index" to playlistIndex.toString()
            }
        }
        if (lang == "ar") query += "lang" to "ar"
        return buildUrl(shortLinkHost, "/v/$id", query)
    }

    fun short(id: String, shortLinkHost: String = PROD_HOST, lang: String? = null): String? {
        if (!AppLinkParser.isSafeId(id)) return null
        return buildUrl(shortLinkHost, "/s/$id", langQuery(lang))
    }

    fun live(
        streamId: String,
        shortLinkHost: String = PROD_HOST,
        startSeconds: Long? = null,
        lang: String? = null
    ): String? {
        if (!AppLinkParser.isSafeId(streamId)) return null
        val query = mutableListOf<Pair<String, String>>()
        if (startSeconds != null && startSeconds in 1..MAX_TIMESTAMP_SECONDS) {
            query += "t" to startSeconds.toString()
        }
        if (lang == "ar") query += "lang" to "ar"
        return buildUrl(shortLinkHost, "/e/$streamId", query)
    }

    fun channelHandle(handle: String, shortLinkHost: String = PROD_HOST, lang: String? = null): String? {
        val clean = handle.trim().removePrefix("@")
        if (!AppLinkParser.isSafeHandle(clean)) return null
        val encoded = URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        return buildUrl(shortLinkHost, "/@$encoded", langQuery(lang))
    }

    fun channelId(id: String, shortLinkHost: String = PROD_HOST, lang: String? = null): String? {
        if (!AppLinkParser.isSafeId(id)) return null
        return buildUrl(shortLinkHost, "/c/$id", langQuery(lang))
    }

    fun post(id: String, shortLinkHost: String = PROD_HOST, lang: String? = null): String? {
        if (!AppLinkParser.isSafeId(id)) return null
        return buildUrl(shortLinkHost, "/p/$id", langQuery(lang))
    }

    fun playlist(id: String, shortLinkHost: String = PROD_HOST, lang: String? = null): String? {
        if (!AppLinkParser.isSafeId(id)) return null
        return buildUrl(shortLinkHost, "/l/$id", langQuery(lang))
    }

    /** Parses `120`, `120s`, `2m30s`, or `1h2m3s` (1..86400 seconds). */
    fun parseTimestampSeconds(raw: String?): Long? {
        val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val plain = trimmed.removeSuffix("s")
        plain.toLongOrNull()?.let { secs ->
            return secs.takeIf { it in 1..MAX_TIMESTAMP_SECONDS }
        }
        val match = HMS_REGEX.matchEntire(trimmed) ?: return null
        val h = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        val m = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        val s = match.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        val total = h * 3600L + m * 60L + s
        return total.takeIf { it in 1..MAX_TIMESTAMP_SECONDS }
    }

    private fun langQuery(lang: String?): List<Pair<String, String>> =
        if (lang == "ar") listOf("lang" to "ar") else emptyList()

    private fun buildUrl(host: String, path: String, query: List<Pair<String, String>>): String {
        val cleanHost = host.trim().trimEnd('/')
        if (query.isEmpty()) return "https://$cleanHost$path"
        val qs = query.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "https://$cleanHost$path?$qs"
    }

    private val HMS_REGEX = Regex("^(?:(\\d{1,2})h)?(?:(\\d{1,2})m)?(?:(\\d{1,2})s?)?$")
}

/** Parses links into [AppLink]s. Pure JVM (no android.net.Uri), so it is unit-tested directly. */
object AppLinkParser {
    private val LOCALES = setOf("en", "ar")

    /**
     * Parses [link] if it is `https://[webHost]/…`, `https://[shortLinkHost]/…` (case-insensitive host), or one of
     * the app's custom schemes (`alummahnow://watch/{id}`). Anything else, including other hosts and plain http, is null.
     */
    fun parse(link: String, webHost: String, shortLinkHost: String? = null): AppLink? =
        parseInvocation(link, webHost, shortLinkHost)?.link

    fun parseInvocation(link: String, webHost: String, shortLinkHost: String? = null): AppLinkInvocation? {
        val uri = try {
            URI(link.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        val queryMap = parseQuery(uri.rawQuery)
        val queryLang = queryMap["lang"]?.takeIf { it in LOCALES }
        val appLink = when (uri.scheme?.lowercase()) {
            "https" -> when {
                uri.host.equals(webHost, ignoreCase = true) -> parsePath(uri.rawPath.orEmpty(), uri.rawQuery, queryLang)
                shortLinkHost != null && uri.host.equals(shortLinkHost, ignoreCase = true) ->
                    parseShortPath(uri.rawPath.orEmpty(), queryLang)
                else -> null
            }
            CUSTOM_SCHEME -> parseCustom(uri)
            else -> null
        } ?: return null

        val startSeconds = ShortLinks.parseTimestampSeconds(queryMap["t"])
        val playlistId = queryMap["list"]?.takeIf(::isSafeId)
        val playlistIndex = queryMap["index"]?.toIntOrNull()?.takeIf { it >= 0 }
        return AppLinkInvocation(
            link = appLink,
            startSeconds = startSeconds,
            playlistId = playlistId,
            playlistIndex = if (playlistId != null) playlistIndex else null,
            lang = appLink.lang ?: queryLang
        )
    }

    private fun parseCustom(uri: URI): AppLink? {
        if (uri.host != "watch") return null
        val id = segments(uri.rawPath.orEmpty()).firstOrNull()?.takeIf(::isSafeId) ?: return null
        return AppLink.Video(id)
    }

    private fun parseShortPath(rawPath: String, queryLang: String?): AppLink? {
        val all = segments(rawPath)
        val head = all.firstOrNull()
        val id = all.getOrNull(1)?.takeIf { all.size == 2 && isSafeId(it) }
        return when {
            head == null -> null
            head.startsWith("@") && all.size == 1 ->
                head.substring(1).takeIf(::isSafeHandle)?.let { AppLink.Channel(it, queryLang) }
            id == null -> null
            head == "v" -> AppLink.Video(id, queryLang)
            head == "s" -> AppLink.Short(id, queryLang)
            head == "e" -> AppLink.Live(id, queryLang)
            head == "c" -> AppLink.ChannelId(id, queryLang)
            head == "p" -> AppLink.Post(id, queryLang)
            head == "l" -> AppLink.Playlist(id, queryLang)
            else -> null
        }
    }

    private fun parsePath(rawPath: String, rawQuery: String?, queryLang: String?): AppLink? {
        val all = segments(rawPath)
        if (all.size >= 2 && all[0] == "app") {
            return when {
                all[1] == "auth" && all.getOrNull(2) == "callback" && all.size == 3 -> AppLink.AuthCallback(rawQuery)
                all[1] == "studio" -> studio(all.drop(2), lang = null)
                else -> null
            }
        }
        val pathLang = all.firstOrNull()?.takeIf { it in LOCALES }
        val rest = if (pathLang != null) all.drop(1) else all
        val lang = pathLang ?: queryLang
        val head = rest.firstOrNull() ?: return null
        val id = rest.getOrNull(1)?.takeIf(::isSafeId)
        return when {
            head.startsWith("@") -> head.substring(1).takeIf(::isSafeHandle)?.let { AppLink.Channel(it, lang) }
            head == "studio" && pathLang != null -> studio(rest.drop(1), pathLang)
            id == null -> null
            head == "video" -> AppLink.Video(id, lang)
            head == "shorts" -> AppLink.Short(id, lang)
            head == "live" -> AppLink.Live(id, lang)
            head == "playlist" -> AppLink.Playlist(id, lang)
            head == "post" -> AppLink.Post(id, lang)
            head == "channel" -> AppLink.ChannelId(id, lang)
            else -> null
        }
    }

    /**
     * The web Studio's routes: `/studio`, `/studio/video/{id}/{action}`, `/studio/go/…`, `/studio/{channel}`,
     * `/studio/{channel}/{dashboard|analytics}`, `/studio/{channel}/content/videos[/{id}/edit]`.
     */
    private fun studio(s: List<String>, lang: String?): AppLink.Studio {
        val first = s.firstOrNull()?.takeIf(::isSafeId) ?: return AppLink.Studio(lang = lang)
        return when (first) {
            "video" -> AppLink.Studio(videoId = s.getOrNull(1)?.takeIf(::isSafeId), lang = lang)
            "go" -> AppLink.Studio(lang = lang)
            else -> when (s.getOrNull(1)) {
                null, "dashboard" -> AppLink.Studio(first, StudioSection.DASHBOARD, lang = lang)
                "analytics" -> AppLink.Studio(first, StudioSection.ANALYTICS, lang = lang)
                "content" -> {
                    val video = if (s.getOrNull(2) == "videos") s.getOrNull(3)?.takeIf(::isSafeId) else null
                    AppLink.Studio(first, StudioSection.CONTENT, video, lang)
                }
                else -> AppLink.Studio(first, lang = lang)
            }
        }
    }

    /** Ids go into in-app routes: only URL-safe id characters, so a decoded `/`, `?` or `%` can't forge a route. */
    internal fun isSafeId(s: String): Boolean = ID.matches(s)

    /** Handles may be Arabic; they only ever leave the app as the original URL, but still no separators. */
    internal fun isSafeHandle(s: String): Boolean =
        s.length in 1..MAX_HANDLE && s.none { it in HANDLE_STOPS || it.isWhitespace() }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = decodeSegment(rawKey)
            if (key.isNotEmpty() && key !in out) {
                out[key] = decodeSegment(rawVal)
            }
        }
        return out
    }

    private fun decodeSegment(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private const val HANDLE_STOPS = "/?#"

    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private const val MAX_HANDLE = 100

    private fun segments(rawPath: String): List<String> =
        rawPath.split('/').filter { it.isNotEmpty() }.map(::decodeSegment)

    const val CUSTOM_SCHEME = "alummahnow"
}
