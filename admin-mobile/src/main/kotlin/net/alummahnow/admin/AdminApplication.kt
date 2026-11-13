package net.alummahnow.admin

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import net.alummahnow.admin.media.PrivateMedia
import net.alummahnow.admin.push.AdminNotifications
import net.alummahnow.admin.security.SecureWindows
import net.alummahnow.core.bridge.AunLog

class AdminApplication :
    Application(),
    SingletonImageLoader.Factory {
    /** Created on first use, so tests that only need the process (and no Rust core) never load it. */
    val container: AdminContainer by lazy { AdminContainer(this) }

    override fun onCreate() {
        super.onCreate()
        AunLog.enabled = BuildConfig.DEBUG
        registerActivityLifecycleCallbacks(SecureWindows)
        AdminNotifications.ensureChannel(this)
    }

    /** Any image a shared component loads: memory only (no disk cache), over the private client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = PrivateMedia.imageLoader(context)
}
