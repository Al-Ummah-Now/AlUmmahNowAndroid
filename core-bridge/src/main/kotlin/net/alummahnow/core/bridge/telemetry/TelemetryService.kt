package net.alummahnow.core.bridge.telemetry

/** How much diagnostic telemetry the person has opted into. Defaults to [OFF]. */
enum class TelemetryConsent {
    OFF,
    CRASH_ONLY,
    FULL
}

/**
 * Opt-in diagnostics and crash reports, shared by the Android apps. Off until the person turns it on.
 * aun-core's telemetry does the scrubbing, the on-disk buffer, the purge on opt-out and the delivery;
 * the apps only pass consent and crashes and ask for a flush. Never throws.
 */
interface TelemetryService {
    val consent: TelemetryConsent

    /** Applies [consent]; turning it off deletes what was kept. Returns `false` when the core refused. */
    fun setConsent(consent: TelemetryConsent): Boolean

    /** Records a crash and writes it to disk at once, so it survives the process ending. */
    fun reportCrash(kind: String, message: String, stack: String? = null)

    /** Writes the buffer to disk (call when the app goes to the background). */
    fun persist()

    /** Sends what is buffered; does nothing while off or offline. */
    suspend fun flush()

    fun close()
}

/** Used in tests, previews and where the native library cannot load. */
object NullTelemetryService : TelemetryService {
    override val consent: TelemetryConsent = TelemetryConsent.OFF
    override fun setConsent(consent: TelemetryConsent): Boolean = consent == TelemetryConsent.OFF
    override fun reportCrash(kind: String, message: String, stack: String?) = Unit
    override fun persist() = Unit
    override suspend fun flush() = Unit
    override fun close() = Unit
}
