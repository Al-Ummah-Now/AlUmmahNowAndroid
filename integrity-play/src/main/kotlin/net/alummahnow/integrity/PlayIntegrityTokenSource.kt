package net.alummahnow.integrity

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.alummahnow.core.bridge.attestation.IntegrityTokenSource

/**
 * [IntegrityTokenSource] over Play Integrity's `StandardIntegrityManager`. The token provider is prepared once per
 * Cloud project number (preparing is the slow step) and prepared again after any failure, as Google recommends for
 * an expired or invalid provider.
 */
class PlayIntegrityTokenSource(context: Context) : IntegrityTokenSource {
    private val app = context.applicationContext
    private val manager: StandardIntegrityManager by lazy { IntegrityManagerFactory.createStandard(app) }
    private val lock = Mutex()
    private var prepared: Pair<Long, StandardIntegrityTokenProvider>? = null

    override val available: Boolean by lazy { hasPlayStore(app.packageManager) }

    override suspend fun token(cloudProjectNumber: Long, requestHash: String): String {
        val provider = provider(cloudProjectNumber)
        val result = runCatching {
            provider.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await().token()
        }
        if (result.isFailure) lock.withLock { prepared = null }
        return result.getOrThrow()
    }

    private suspend fun provider(project: Long): StandardIntegrityTokenProvider = lock.withLock {
        prepared?.takeIf { it.first == project }?.second
            ?: manager.prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(project).build())
                .await()
                .also { prepared = project to it }
    }

    companion object {
        private const val PLAY_STORE = "com.android.vending"

        /** Amazon devices and other phones without Google Play have no Play Store: attestation stays off there. */
        fun hasPlayStore(pm: PackageManager): Boolean = try {
            pm.getPackageInfo(PLAY_STORE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
