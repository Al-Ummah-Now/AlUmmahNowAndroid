package net.alummahnow.studio

import kotlinx.coroutines.CoroutineScope
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
    fun isTelemetryEnabled(): Boolean = false
    fun setTelemetryEnabled(enabled: Boolean): Boolean = !enabled

    /** For work that must finish although the screen that asked for it is gone (the last sign-out, a deletion). */
    val appScope: CoroutineScope

    /** Drops what is held for a signed-out account. */
    fun forget(userId: String)
}
