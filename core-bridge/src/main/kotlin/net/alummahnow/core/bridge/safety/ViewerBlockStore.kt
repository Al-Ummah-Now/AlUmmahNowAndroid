package net.alummahnow.core.bridge.safety

import android.content.SharedPreferences
import java.net.URLEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.VideoSummary

/**
 * Web URLs for reporting videos, channels, live streams, and live chat messages from guest viewers
 * (Google Play UGC policy, AlUmmahNowAndroid#61). Points at the public guest `/[locale]/report` page.
 */
object ViewerReportUrls {
    private fun locale(language: String): String = if (language.lowercase().startsWith("ar")) "ar" else "en"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun buildReportUrl(webHost: String, language: String, params: List<Pair<String, String>>): String {
        // No default host and no report without its target: either would be sent somewhere nobody looks.
        require(webHost.isNotBlank()) { "no web host" }
        require(params.all { it.second.isNotBlank() }) { "no report target" }
        return "https://$webHost/${locale(language)}/report?" + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    fun videoUrl(videoId: String, language: String, webHost: String): String =
        buildReportUrl(webHost, language, listOf("type" to "video", "id" to videoId))

    fun channelUrl(channelId: String, language: String, webHost: String): String =
        buildReportUrl(webHost, language, listOf("type" to "channel", "id" to channelId.trim()))

    fun liveStreamUrl(streamId: String, language: String, webHost: String): String =
        buildReportUrl(webHost, language, listOf("type" to "livestream", "id" to streamId))

    fun liveChatUrl(streamId: String, messageId: String, language: String, webHost: String): String =
        buildReportUrl(
            webHost,
            language,
            listOf("type" to "live_chat", "stream" to streamId, "message" to messageId)
        )
}

@Serializable
enum class BlockedKind { CHANNEL, CHAT_USER }

@Serializable
data class BlockedEntry(val id: String, val kind: BlockedKind, val name: String)

/**
 * Device-local block list for the guest Viewer app (Google Play UGC policy, AlUmmahNowAndroid#61).
 * Hides videos and live streams from blocked channels and messages from blocked live-chat users immediately.
 */
class ViewerBlockStore(private val prefs: SharedPreferences? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableEntries = MutableStateFlow(load())
    val entries: StateFlow<List<BlockedEntry>> = mutableEntries.asStateFlow()

    /** A channel with neither an ID nor a name can't be told from any other, so there is nothing to block. */
    fun blockChannel(channelId: String?, channelName: String?) {
        if (channelId.isNullOrBlank() && channelName.isNullOrBlank()) return
        val key = channelKey(channelId, channelName.orEmpty())
        if (mutableEntries.value.any { it.id == key }) return
        update(listOf(BlockedEntry(key, BlockedKind.CHANNEL, channelName.orEmpty().trim())) + mutableEntries.value)
    }

    /** Removes every entry that blocks this channel, whichever way it was stored (by ID, or by name without one). */
    fun unblockChannel(channelId: String?, channelName: String?) {
        update(mutableEntries.value.filterNot { it.blocksChannel(channelId, channelName) })
    }

    fun blockChatUser(userId: String? = null, authorName: String) {
        val key = chatUserKey(userId, authorName)
        if (mutableEntries.value.any { it.id == key }) return
        update(listOf(BlockedEntry(key, BlockedKind.CHAT_USER, authorName)) + mutableEntries.value)
    }

    fun unblock(entryId: String) {
        update(mutableEntries.value.filterNot { it.id == entryId })
    }

    fun isChannelBlocked(channelId: String?, channelName: String?): Boolean =
        mutableEntries.value.any { it.blocksChannel(channelId, channelName) }

    private fun BlockedEntry.blocksChannel(channelId: String?, channelName: String?): Boolean {
        if (kind != BlockedKind.CHANNEL) return false
        val byId = channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { channelKey(it, "") }
        // Two IDs are compared as IDs, never by name.
        if (byId != null && id.startsWith("channel:id:")) return id == byId
        val cleanName = channelName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return id == channelKey(null, cleanName) || name.equals(cleanName, ignoreCase = true)
    }

    fun isChatUserBlocked(userId: String? = null, authorName: String?): Boolean {
        val cleanId = userId?.trim()?.takeIf { it.isNotEmpty() }
        val byId = cleanId?.let { chatUserKey(it, authorName.orEmpty()) }
        val cleanName = authorName?.trim()?.takeIf { it.isNotEmpty() }
        val byName = cleanName?.let { chatUserKey(null, it) }
        val legacy = cleanName?.let { "user:${it.lowercase()}" }
        return mutableEntries.value.any { entry ->
            if (entry.kind != BlockedKind.CHAT_USER) return@any false
            if (byId != null && entry.id == byId) return@any true
            if (byId != null && entry.id.startsWith("user:id:")) return@any false
            (byName != null && entry.id == byName) || (legacy != null && entry.id == legacy)
        }
    }

    fun filterVideos(videos: List<VideoSummary>): List<VideoSummary> =
        videos.filterNot { isChannelBlocked(it.channelId, it.channelName) }

    fun filterRails(rails: List<Rail>): List<Rail> =
        rails.mapNotNull { rail ->
            val kept = filterVideos(rail.videos)
            if (kept.isEmpty()) null else rail.copy(videos = kept)
        }

    fun filterLiveStreams(streams: List<LiveStream>): List<LiveStream> =
        streams.filterNot { isChannelBlocked(it.channelId, it.channelName) }

    fun filterMessages(messages: List<LiveChatMessage>): List<LiveChatMessage> =
        messages.filterNot { isChatUserBlocked(it.userId, it.authorName) }

    private fun load(): List<BlockedEntry> {
        val raw = prefs?.all?.get(KEY) as? String ?: return emptyList()
        return runCatching { json.decodeFromString<List<BlockedEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun update(next: List<BlockedEntry>) {
        mutableEntries.value = next
        prefs?.edit()?.putString(KEY, json.encodeToString(next))?.apply()
    }

    companion object {
        private const val KEY = "blocked_entries"

        fun channelKey(channelId: String?, channelName: String): String {
            val cleanId = channelId?.trim()
            return if (!cleanId.isNullOrEmpty()) {
                "channel:id:$cleanId"
            } else {
                "channel:name:${channelName.trim().lowercase()}"
            }
        }

        fun chatUserKey(userId: String? = null, authorName: String): String {
            val cleanId = userId?.trim()
            return if (!cleanId.isNullOrEmpty()) {
                "user:id:$cleanId"
            } else {
                "user:name:${authorName.trim().lowercase()}"
            }
        }
    }
}
