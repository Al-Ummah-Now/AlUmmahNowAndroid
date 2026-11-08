package net.alummahnow.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.RailKind

/**
 * Browse tree (Android Auto, other media controllers) and late resolution of play URLs:
 * root → one folder per home rail → playable videos. Items without a URI are resolved through the catalogue.
 */
internal class LibraryCallback(
    private val context: Context,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope
) : MediaLibrarySession.Callback {

    private val tree =
        BrowseTree(catalog, context.getString(R.string.aun_browse_root)) { kind -> context.getString(BrowseTree.titleRes(kind)) }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future { LibraryResult.ofItem(tree.root(), params) }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        runCatching { tree.children(parentId, locale()) }
            .fold(
                onSuccess = { LibraryResult.ofItemList(ImmutableList.copyOf(it), params) },
                onFailure = {
                    AunLog.w("player", "browse failed", "parent" to parentId, "error" to it.message)
                    LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_IO)
                }
            )
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        runCatching { MediaItems.playable(catalog.video(mediaId), locale()) }
            .fold({ LibraryResult.ofItem(it, null) }, { LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE) })
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> = scope.future {
        val trusted = acceptsPrebuiltItems(controller.packageName, context.packageName)
        mediaItems.map { item ->
            if (trusted && item.localConfiguration != null) item else MediaItems.playable(catalog.video(item.mediaId), locale())
        }.toMutableList()
    }

    private fun locale(): Locale = context.resources.configuration.locales[0]
}

/**
 * The session service is exported (Android Auto, system media controls), so a foreign controller could
 * otherwise hand us an item pointing at any URI. Only our own process may pass a ready-made item; every
 * other caller gets the item rebuilt from the catalog by `mediaId`.
 */
internal fun acceptsPrebuiltItems(callerPackage: String, ownPackage: String): Boolean = callerPackage == ownPackage

/** Pure browse-tree logic, unit-tested without a session. */
internal class BrowseTree(
    private val catalog: CatalogRepository,
    private val rootTitle: String,
    private val title: (RailKind) -> String
) {
    fun root(): MediaItem = MediaItems.folder(ROOT_ID, rootTitle)

    suspend fun children(parentId: String, locale: Locale): List<MediaItem> {
        val rails = catalog.homeRails()
        if (parentId == ROOT_ID) return rails.map { MediaItems.folder(FOLDER_PREFIX + it.kind.name, title(it.kind)) }
        val kind =
            parentId.removePrefix(FOLDER_PREFIX).let { name -> RailKind.entries.firstOrNull { it.name == name } } ?: return emptyList()
        return rails.firstOrNull { it.kind == kind }?.videos.orEmpty().map { MediaItems.browsableVideo(it, locale) }
    }

    companion object {
        const val ROOT_ID = "aun:root"
        const val FOLDER_PREFIX = "aun:rail:"

        fun titleRes(kind: RailKind): Int = when (kind) {
            RailKind.CONTINUE_WATCHING -> net.alummahnow.design.R.string.aun_rail_continue
            RailKind.TRENDING -> net.alummahnow.design.R.string.aun_rail_trending
            RailKind.RECOMMENDED -> net.alummahnow.design.R.string.aun_rail_recommended
            RailKind.POPULAR -> net.alummahnow.design.R.string.aun_rail_popular
            RailKind.RECENT -> net.alummahnow.design.R.string.aun_rail_recent
            RailKind.CATEGORY -> net.alummahnow.design.R.string.aun_rail_category
        }
    }
}
