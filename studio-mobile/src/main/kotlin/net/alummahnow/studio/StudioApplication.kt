package net.alummahnow.studio

import android.app.Application
import net.alummahnow.core.bridge.AunLog

class StudioApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AunLog.enabled = BuildConfig.DEBUG
        container = AppContainer(this)
    }
}
