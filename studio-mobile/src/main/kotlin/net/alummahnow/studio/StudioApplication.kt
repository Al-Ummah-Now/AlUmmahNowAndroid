package net.alummahnow.studio

import android.app.Application
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.feature.upload.UploadHost
import net.alummahnow.feature.upload.UploadWorker

class StudioApplication :
    Application(),
    UploadHost {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AunLog.enabled = BuildConfig.DEBUG
        container = AppContainer(this)
        UploadWorker.ensureChannel(this)
        // Uploads left unfinished (app killed, reboot) continue from their last confirmed offset.
        if (container.session.active.value != null && runCatching { container.uploads.hasWork() }.getOrDefault(false)) {
            UploadWorker.start(this)
        }
    }

    override suspend fun processUploads(onUpdate: (List<UploadItem>) -> Unit) = container.uploads.process(onUpdate)
}
