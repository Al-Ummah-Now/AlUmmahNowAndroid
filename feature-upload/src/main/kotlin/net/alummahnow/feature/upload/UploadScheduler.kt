package net.alummahnow.feature.upload

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import net.alummahnow.core.bridge.AunLog

/** The durable queue owns the uploads. Each work request tells the platform to drain that queue. */
object UploadScheduler {
    private const val WORK_NAME = "studio-uploads"
    private const val JOB_ID = 4100

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            enqueueTransfer(context)
        } else {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    /** A visible Activity can recover saved uploads if their job was lost or scheduling was deferred. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun recover(context: Context) {
        if (requireNotNull(context.getSystemService(JobScheduler::class.java)).getPendingJob(JOB_ID) == null) enqueueTransfer(context)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun enqueueTransfer(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UploadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        // Appending work preserves a running job and wakes it again if a batch arrives as it finishes.
        val scheduler = requireNotNull(context.getSystemService(JobScheduler::class.java))
        if (scheduler.enqueue(job, JobWorkItem.Builder().build()) == JobScheduler.RESULT_FAILURE) {
            AunLog.w("upload", "transfer scheduling deferred until Studio is visible")
        }
    }
}
