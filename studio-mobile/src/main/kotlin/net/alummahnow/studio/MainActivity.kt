package net.alummahnow.studio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.design.theme.AunTheme

class MainActivity : ComponentActivity() {
    /** OAuth redirects (`alummahnow-studio://auth/callback?...`), delivered once each. */
    private val redirects = Channel<String>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        val container = (application as StudioApplication).container
        setContent {
            // Fanous brand colours only: no Material You dynamic colour.
            AunTheme {
                StudioApp(container = container, redirects = redirects.receiveAsFlow(), openInBrowser = ::openInCustomTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "alummahnow-studio" && data.host == "auth") redirects.trySend(data.toString())
    }

    /** Opens [url] in a Custom Tab (shares the browser's Al Ummah Now session). False when no browser. */
    private fun openInCustomTab(url: String): Boolean = try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url))
        true
    } catch (e: ActivityNotFoundException) {
        AunLog.w("auth", "no browser", "type" to e.javaClass.simpleName)
        false
    }
}
