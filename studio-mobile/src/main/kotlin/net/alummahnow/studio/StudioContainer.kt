package net.alummahnow.studio

import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.UploadQueue

/** What [StudioApp] needs. [AppContainer] is the real one; tests hand in fakes. */
interface StudioContainer {
    val session: AccountSession

    /** Acts as whichever account is active when it is called. */
    val repository: StudioRepository
    val uploads: UploadQueue
    val exports: PendingExports

    /** Acts as [userId] only, whoever is active: for a record that must never land on another account. */
    fun repositoryFor(userId: String): StudioRepository

    /** Drops what is held for a signed-out account. */
    fun forget(userId: String)
}
