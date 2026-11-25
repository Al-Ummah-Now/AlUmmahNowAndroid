package net.alummahnow.studio

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.deletion.DeletionStage
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
        // A deletion that succeeded just before the app was killed: its uploads, session and record go first.
        if (container.deletionRecords.records.value.any { it.stage == DeletionStage.DELETED }) {
            runBlocking(Dispatchers.IO) { container.deletion.finishCleanups() }
        }
        // A deletion that was cut off had held its account's uploads.
        container.appScope.launch(Dispatchers.IO) { container.uploads.releaseLeft() }
        // Uploads left unfinished (app killed, reboot) continue from their last confirmed offset.
        if (container.session.active.value != null && runCatching { container.uploads.hasWork() }.getOrDefault(false)) {
            UploadWorker.start(this)
        }
    }

    override suspend fun processUploads(onUpdate: (List<UploadItem>) -> Unit) = container.uploads.process(onUpdate)
}
