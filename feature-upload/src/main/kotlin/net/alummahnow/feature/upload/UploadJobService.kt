package net.alummahnow.feature.upload

import android.annotation.SuppressLint
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.UploadItem

// On API 34+ WorkManager uses its own JobScheduler namespace.
@SuppressLint("SpecifyJobSchedulerIdRange")
class UploadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val host get() = application as UploadHost
    private var transfer: Job? = null

    @Suppress("TooGenericExceptionCaught") // A failed run leaves the saved queue available for JobScheduler to retry.
    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        stopTransfer()
        UploadNotifications.ensureChannel(this)
        showProgress(params, emptyList())
        host.setTransferRunning(true)
        transfer = scope.launch {
            val current = coroutineContext[Job]
            try {
                var lastNotified = SystemClock.elapsedRealtime()
                var lastItems = emptyList<UploadItem>()
                // Dequeuing an empty queue tells JobScheduler this job is finished. Keep the
                // work item until the saved queue has drained so an interrupted job can retry it.
                while (true) {
                    val work = params.dequeueWork() ?: break
                    withContext(Dispatchers.IO) {
                        host.processUploads { items ->
                            lastItems = items
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastNotified >= UploadNotifications.NOTIFY_EVERY_MS) {
                                lastNotified = now
                                scope.launch {
                                    if (transfer === current) showProgress(params, items)
                                }
                            }
                        }
                    }
                    params.completeWork(work)
                }
                UploadNotifications.notifyFinished(this@UploadJobService, lastItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AunLog.e("upload", "transfer job failed", e)
                jobFinished(params, true)
            } finally {
                // onStopJob can cancel this coroutine before a new run takes ownership.
                if (transfer === current) stopTransfer()
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        stopTransfer()
        return params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP &&
            params.stopReason != JobParameters.STOP_REASON_USER
    }

    override fun onDestroy() {
        stopTransfer()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopTransfer() {
        if (transfer == null) return
        host.setTransferRunning(false)
        transfer?.cancel()
        transfer = null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun showProgress(params: JobParameters, items: List<UploadItem>) {
        setNotification(
            params,
            UploadNotifications.NOTIFICATION_ID,
            UploadNotifications.progressNotification(this, items),
            JOB_END_NOTIFICATION_POLICY_REMOVE
        )
    }
}
