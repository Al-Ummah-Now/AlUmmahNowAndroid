package net.alummahnow.viewer

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.player.PlayerDependencies

class AunViewerApp : Application(), PlayerDependencies {
    lateinit var container: AppContainer
        private set

    override val catalog: CatalogRepository get() = container.catalog
    override val sessionActivityClass: Class<*> = MainActivity::class.java

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        AunLog.enabled = BuildConfig.DEBUG || BuildConfig.ENV_NAME == "qa"
        container = AppContainer(this)
        // Chromecast is optional: devices without Google Play services simply don't show the cast button.
        runCatching { Cast.getSingletonInstance(this).initialize() }
            .onFailure { AunLog.w("app", "cast init failed", "error" to it.message) }
        AunLog.i("app", "started", "env" to BuildConfig.ENV_NAME)
    }
}
