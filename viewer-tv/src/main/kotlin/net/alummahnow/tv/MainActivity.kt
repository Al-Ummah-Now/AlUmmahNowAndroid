package net.alummahnow.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunClient
import net.alummahnow.core.bridge.telemetry.CoreTelemetryService
import net.alummahnow.core.bridge.telemetry.TelemetryConsent
import net.alummahnow.core.bridge.telemetry.TelemetryConsentStore
import net.alummahnow.core.bridge.telemetry.TelemetryService
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val consentStore = TelemetryConsentStore(
            prefs = prefs,
            prefKey = KEY_TELEMETRY,
            optInMarkerFile = File(applicationContext.noBackupFilesDir, "tv-telemetry-opt-in")
        )
        val telemetry = getTelemetry(applicationContext, consentStore)
        setContent {
            val telemetryEnabledPref by telemetryEnabledState.collectAsState()
            val telemetrySaveUncertain by telemetrySaveUncertainState.collectAsState()
            val telemetryWarningRes by telemetryWarningResState.collectAsState()
            val lastPurgeFailed by telemetry.lastPurgeFailedFlow.collectAsState()
            val telemetryEnabled = telemetryEnabledPref || telemetrySaveUncertain
            val telemetryWarning = buildList {
                telemetryWarningRes?.let { add(stringResource(it)) }
                if (lastPurgeFailed && telemetryWarningRes != DesignR.string.aun_diagnostics_purge_warning) {
                    add(stringResource(DesignR.string.aun_diagnostics_purge_warning))
                }
            }.takeIf { it.isNotEmpty() }?.joinToString("\n")
            val scope = rememberCoroutineScope()
            LaunchedEffect(telemetryEnabledPref) {
                if (telemetryEnabledPref) telemetry.flush()
            }
            DisposableEffect(this@MainActivity) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> scope.launch { telemetry.flush() }
                        Lifecycle.Event.ON_STOP -> telemetry.persist()
                        else -> Unit
                    }
                }
                this@MainActivity.lifecycle.addObserver(observer)
                onDispose {
                    this@MainActivity.lifecycle.removeObserver(observer)
                    telemetry.persist()
                }
            }
            TvApp(
                telemetryEnabled = telemetryEnabled,
                telemetryWarning = telemetryWarning,
                onToggleTelemetry = { toggleTelemetry(telemetry, consentStore, telemetryEnabled) }
            )
        }
    }

    private fun toggleTelemetry(telemetry: TelemetryService, consentStore: TelemetryConsentStore, currentlyEnabled: Boolean) {
        if (!currentlyEnabled) {
            if (telemetry.setConsent(TelemetryConsent.FULL)) {
                if (consentStore.setEnabled(true)) {
                    telemetryEnabledState.value = true
                    telemetrySaveUncertainState.value = false
                    telemetryWarningResState.value = null
                } else {
                    val (rolledBack, _) = consentStore.disable { telemetry.setConsent(TelemetryConsent.OFF) }
                    if (rolledBack) telemetryEnabledState.value = false
                    telemetrySaveUncertainState.value = !rolledBack
                    telemetryWarningResState.value = if (rolledBack) {
                        DesignR.string.aun_diagnostics_enable_error
                    } else {
                        DesignR.string.aun_diagnostics_disable_error
                    }
                }
            } else {
                telemetry.setConsent(TelemetryConsent.OFF)
                telemetryWarningResState.value = DesignR.string.aun_diagnostics_enable_error
            }
        } else {
            val (saved, purged) = consentStore.disable { telemetry.setConsent(TelemetryConsent.OFF) }
            if (saved) telemetryEnabledState.value = false
            telemetrySaveUncertainState.value = !saved
            telemetryWarningResState.value = when {
                !saved -> DesignR.string.aun_diagnostics_disable_error
                !purged -> DesignR.string.aun_diagnostics_purge_warning
                else -> null
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "aun_tv_prefs"
        private const val KEY_TELEMETRY = "telemetry_enabled"
        private val telemetryLock = Any()
        private val telemetryEnabledState = MutableStateFlow(false)
        private val telemetrySaveUncertainState = MutableStateFlow(false)
        private val telemetryWarningResState = MutableStateFlow<Int?>(null)

        @Volatile
        private var telemetryInstance: TelemetryService? = null

        private fun getTelemetry(appContext: Context, consentStore: TelemetryConsentStore): TelemetryService =
            telemetryInstance ?: synchronized(telemetryLock) {
                telemetryInstance ?: run {
                    val initialEnabled = consentStore.isEnabled()
                    CoreTelemetryService.createOrNull(
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceClass = "tv",
                        videosApiBaseUrl = BuildConfig.API_BASE_URL,
                        userAgent = "AlUmmahNow-TV-Android/${BuildConfig.VERSION_NAME}",
                        clientHeader = AunClient.value("tv", BuildConfig.VERSION_NAME),
                        bufferPath = File(appContext.noBackupFilesDir, "tv-telemetry-buffer.json").absolutePath,
                        initialEnabled = initialEnabled
                    ).also { service ->
                        val active = initialEnabled && service.consent != TelemetryConsent.OFF
                        val clearFailed = initialEnabled && !active && !consentStore.setEnabled(false)
                        telemetryEnabledState.value = active
                        if (clearFailed) {
                            telemetrySaveUncertainState.value = true
                            telemetryWarningResState.value = DesignR.string.aun_diagnostics_disable_error
                        }
                        telemetryInstance = service
                        CoreTelemetryService.installUncaughtExceptionHandler(service)
                    }
                }
            }
    }
}

/** The TV app's root screen, also used by the store screenshot tests. */
@Composable
fun TvApp(
    mode: ThemeMode? = null,
    telemetryEnabled: Boolean = false,
    telemetryWarning: String? = null,
    onToggleTelemetry: (() -> Unit)? = null
) {
    var screen by rememberSaveable { mutableStateOf(TvScreen.Home) }
    BackHandler(screen != TvScreen.Home) {
        screen = if (screen == TvScreen.Support) TvScreen.Settings else TvScreen.Home
    }
    AunTheme(mode = mode) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                when (screen) {
                    TvScreen.Home -> {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                        TvAction(stringResource(R.string.settings), initialFocus = true) { screen = TvScreen.Settings }
                    }
                    TvScreen.Settings -> {
                        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge)
                        TvAction(stringResource(R.string.support_links), initialFocus = true) { screen = TvScreen.Support }
                        if (onToggleTelemetry != null) {
                            val stateText = stringResource(
                                if (telemetryEnabled) DesignR.string.aun_diagnostics_on else DesignR.string.aun_diagnostics_off
                            )
                            TvAction("${stringResource(DesignR.string.aun_diagnostics_toggle)}: $stateText") {
                                onToggleTelemetry()
                            }
                            if (!telemetryWarning.isNullOrEmpty()) {
                                Text(
                                    telemetryWarning,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    TvScreen.Support -> TvSupportLinks()
                }
            }
        }
    }
}

private enum class TvScreen { Home, Settings, Support }
