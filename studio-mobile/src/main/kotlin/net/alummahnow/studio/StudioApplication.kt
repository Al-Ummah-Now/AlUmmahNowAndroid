package net.alummahnow.studio

import android.app.Application
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.deletion.DeletionStage
import net.alummahnow.feature.upload.UploadHost
import net.alummahnow.feature.upload.UploadNotifications
import net.alummahnow.feature.upload.UploadScheduler

class StudioApplication :
    Application(),
    UploadHost {
    lateinit var container: AppContainer
        private set

    private val processing = Mutex()

    override fun onCreate() {
        super.onCreate()
        AunLog.enabled = BuildConfig.DEBUG
        container = AppContainer(this)
        UploadNotifications.ensureChannel(this)
        // A deletion that succeeded just before the app was killed: its uploads, session and record go first.
        if (container.deletionRecords.records.value.any { it.stage == DeletionStage.DELETED }) {
            runBlocking(Dispatchers.IO) { container.deletion.finishCleanups() }
        }
        // A deletion that was cut off had held its account's uploads.
        container.appScope.launch(Dispatchers.IO) { container.uploads.releaseLeft() }
        // Uploads left unfinished (app killed, reboot) continue from their last confirmed offset.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasSavedUploads()) {
            UploadScheduler.start(this)
        }
    }

    fun hasSavedUploads(): Boolean =
        container.session.active.value != null && runCatching { container.uploads.hasWork() }.getOrDefault(false)

    override fun setTransferRunning(running: Boolean) {
        container.transferRunning = running
    }

    override suspend fun processUploads(onUpdate: (List<UploadItem>) -> Unit) =
        processing.withLock { container.uploads.process(onUpdate) }
}
