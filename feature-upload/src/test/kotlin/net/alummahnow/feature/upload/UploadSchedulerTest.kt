package net.alummahnow.feature.upload

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import net.alummahnow.core.bridge.studio.UploadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(AndroidJUnit4::class)
class UploadSchedulerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val jobs get() = requireNotNull(context.getSystemService(JobScheduler::class.java))

    @Test
    @Config(sdk = [34, 35])
    fun `Android 14 and later enqueue a network UIDT job without WorkManager`() {
        UploadScheduler.start(context)
        UploadScheduler.start(context)
        UploadScheduler.recover(context)

        val job = jobs.allPendingJobs.single()
        assertEquals(UploadJobService::class.java.name, job.service.className)
        assertTrue(job.isUserInitiated)
        assertTrue(job.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)

        jobs.cancel(job.id)
        assertNull(jobs.getPendingJob(job.id))
    }

    @Test
    @Config(sdk = [34], application = UploadTestApplication::class)
    fun `UIDT retries a constraint stop but respects app and task manager cancellation`() {
        val service = Robolectric.buildService(UploadJobService::class.java).create().get()
        fun stopsWithRetry(reason: Int): Boolean {
            val params = ReflectionHelpers.callConstructor(JobParameters::class.java)
            ReflectionHelpers.setField(params, "mStopReason", reason)
            val host = context as UploadTestApplication
            assertTrue(service.onStartJob(params))
            assertTrue(host.running)
            val retry = service.onStopJob(params)
            assertFalse(host.running)
            return retry
        }

        assertTrue(stopsWithRetry(JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY))
        assertFalse(stopsWithRetry(JobParameters.STOP_REASON_CANCELLED_BY_APP))
        assertFalse(stopsWithRetry(JobParameters.STOP_REASON_USER))
    }

    @Test
    @Config(sdk = [33])
    fun `Android 13 schedules and cancels upload work through WorkManager`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)
        UploadScheduler.start(context)

        val name = "studio-uploads"
        val work = workManager.getWorkInfosForUniqueWork(name).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(UploadWorker::class.java.name in work.tags)
        assertTrue(jobs.allPendingJobs.isEmpty())

        workManager.cancelUniqueWork(name).result.get()
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(work.id).get()?.state)
    }
}

class UploadTestApplication : Application(), UploadHost {
    var running = false
        private set

    override fun setTransferRunning(running: Boolean) {
        this.running = running
    }

    override suspend fun processUploads(onUpdate: (List<UploadItem>) -> Unit) = error("stopped before processing")
}
