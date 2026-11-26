package net.alummahnow.tv

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TvApp() }
    }
}

/** The TV app's root screen, also used by the store screenshot tests. */
@Composable
fun TvApp(mode: ThemeMode? = null) {
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
                    }
                    TvScreen.Support -> TvSupportLinks()
                }
            }
        }
    }
}

private enum class TvScreen { Home, Settings, Support }
