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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import net.alummahnow.core.bridge.AunClient
import net.alummahnow.core.bridge.telemetry.CoreTelemetryService
import net.alummahnow.core.bridge.telemetry.TelemetryConsent
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialEnabled = prefs.getBoolean(KEY_TELEMETRY, false)
        val telemetry = CoreTelemetryService.createOrNull(
            appVersion = BuildConfig.VERSION_NAME,
            deviceClass = "tv",
            videosApiBaseUrl = BuildConfig.API_BASE_URL,
            userAgent = "AlUmmahNow-TV-Android/${BuildConfig.VERSION_NAME}",
            clientHeader = AunClient.value("tv", BuildConfig.VERSION_NAME),
            bufferPath = File(applicationContext.noBackupFilesDir, "tv-telemetry-buffer.json").absolutePath,
            initialEnabled = initialEnabled
        )
        CoreTelemetryService.installUncaughtExceptionHandler(telemetry)
        setContent {
            var telemetryEnabled by rememberSaveable { mutableStateOf(initialEnabled) }
            LaunchedEffect(telemetryEnabled) {
                if (telemetryEnabled) telemetry.flush()
            }
            DisposableEffect(Unit) {
                onDispose { telemetry.persist() }
            }
            TvApp(
                telemetryEnabled = telemetryEnabled,
                onToggleTelemetry = {
                    val next = !telemetryEnabled
                    if (next) {
                        if (telemetry.setConsent(TelemetryConsent.FULL) && prefs.edit().putBoolean(KEY_TELEMETRY, true).commit()) {
                            telemetryEnabled = true
                        } else {
                            telemetry.setConsent(TelemetryConsent.OFF)
                            prefs.edit().putBoolean(KEY_TELEMETRY, false).commit()
                            telemetryEnabled = false
                        }
                    } else {
                        telemetry.setConsent(TelemetryConsent.OFF)
                        if (prefs.edit().putBoolean(KEY_TELEMETRY, false).commit()) {
                            telemetryEnabled = false
                        }
                    }
                }
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "aun_tv_prefs"
        private const val KEY_TELEMETRY = "telemetry_enabled"
    }
}

/** The TV app's root screen, also used by the store screenshot tests. */
@Composable
fun TvApp(
    mode: ThemeMode? = null,
    telemetryEnabled: Boolean = false,
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
                        }
                    }
                    TvScreen.Support -> TvSupportLinks()
                }
            }
        }
    }
}

private enum class TvScreen { Home, Settings, Support }
