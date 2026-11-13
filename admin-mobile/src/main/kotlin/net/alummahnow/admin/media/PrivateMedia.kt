package net.alummahnow.admin.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import net.alummahnow.core.bridge.admin.PrivateHttp

/**
 * Owner rule, absolute: "never cache or download on phone or native app ever, only encrypted stream."
 *
 * - The player streams over [PrivateHttp] (TLS, no-store) with no `SimpleCache`, no `CacheDataSource` and no
 *   downloads. Media3 buffers in memory only.
 * - The image loader has no disk cache and no network cache; review frames are decoded from bytes in memory.
 * - Nothing here writes to `MediaStore`, offers share or save, or keeps a signed URL past the open item.
 * `MediaNeverOnDiskTest` (unit) and `MediaNeverOnDiskDeviceTest` (instrumented) fail if any media reaches disk.
 */
@OptIn(UnstableApi::class)
object PrivateMedia {
    /** The only data source the player uses. */
    fun dataSourceFactory(): DataSource.Factory = OkHttpDataSource.Factory(PrivateHttp.client)

    fun newPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
        .build()

    /** Coil, for any image the shared design components load: memory only, over the private client. */
    fun imageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .diskCache(null)
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { PrivateHttp.client })) }
        .build()

    /** Decodes a frame in memory. Blurred frames keep only a tiny copy, so the sharp pixels are dropped at once. */
    fun decode(bytes: ByteArray, blurred: Boolean): Bitmap? {
        val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (!blurred) return full
        val w = BLUR_WIDTH
        val h = (full.height * w / full.width.coerceAtLeast(1)).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
    }

    private const val BLUR_WIDTH = 6
}
