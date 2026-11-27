package net.alummahnow.studio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.UploadQueue
import net.alummahnow.core.bridge.studio.deletion.AccountDeletion
import net.alummahnow.core.bridge.studio.deletion.DeletionRecords
import net.alummahnow.core.bridge.telemetry.NullTelemetryService
import net.alummahnow.core.bridge.telemetry.TelemetryService

/** What [StudioApp] needs. [AppContainer] is the real one; tests hand in fakes. */
interface StudioContainer {
    val session: AccountSession

    /** Acts as whichever account is active when it is called. */
    val repository: StudioRepository
    val uploads: UploadQueue
    val exports: PendingExports

    /** Acts as [userId] only, whoever is active: for a record that must never land on another account. */
    fun repositoryFor(userId: String): StudioRepository

    /** Deletions that started on this device, and the one way to run them. */
    val deletionRecords: DeletionRecords
    val deletion: AccountDeletion

    /** Opt-in diagnostics & crash reports (off by default). */
    val telemetry: TelemetryService get() = NullTelemetryService
    val telemetryEnabledFlow: StateFlow<Boolean> get() = DEFAULT_TELEMETRY_FALSE
    val telemetrySaveFailedFlow: StateFlow<Boolean> get() = DEFAULT_TELEMETRY_FALSE
    val telemetryWarningResFlow: StateFlow<Int?> get() = DEFAULT_TELEMETRY_WARNING
    fun isTelemetryEnabled(): Boolean = false
    fun setTelemetryEnabled(enabled: Boolean): Boolean = !enabled
    fun isTelemetryPreferenceSaveFailed(): Boolean = false

    /** For work that must finish although the screen that asked for it is gone (the last sign-out, a deletion). */
    val appScope: CoroutineScope

    /** Drops what is held for a signed-out account. */
    fun forget(userId: String)

    companion object {
        private val DEFAULT_TELEMETRY_FALSE: StateFlow<Boolean> = MutableStateFlow(false)
        private val DEFAULT_TELEMETRY_WARNING: StateFlow<Int?> = MutableStateFlow(null)
    }
}
