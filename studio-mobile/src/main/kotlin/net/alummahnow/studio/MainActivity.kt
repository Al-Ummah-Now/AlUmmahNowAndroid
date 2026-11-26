package net.alummahnow.studio

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.links.AppLink
import net.alummahnow.core.bridge.links.AppLinkParser
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.feature.upload.UploadScheduler

class MainActivity : ComponentActivity() {
    /** OAuth redirects (`alummahnow-studio://auth/callback?...`), delivered once each. */
    private val redirects = Channel<String>(Channel.BUFFERED)

    /** Studio pages opened from an App Link (`/app/studio/…`, `/{en,ar}/studio/…`), delivered once each. */
    private val links = Channel<AppLink.Studio>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        val container = (application as StudioApplication).container
        setContent {
            // Fanous brand colours only: no Material You dynamic colour.
            AunTheme {
                StudioApp(
                    container = container,
                    redirects = redirects.receiveAsFlow(),
                    links = links.receiveAsFlow(),
                    openInBrowser = ::openInCustomTab
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lifecycleScope.launch {
                val app = application as StudioApplication
                val saved = withContext(Dispatchers.IO) { app.hasSavedUploads() }
                if (saved && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    UploadScheduler.recover(this@MainActivity)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "alummahnow-studio" && data.host == "auth") {
            redirects.trySend(data.toString())
            return
        }
        (AppLinkParser.parse(data.toString(), BuildConfig.WEB_HOST) as? AppLink.Studio)?.let { links.trySend(it) }
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
