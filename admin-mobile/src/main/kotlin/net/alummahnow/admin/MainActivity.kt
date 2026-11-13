package net.alummahnow.admin

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import net.alummahnow.admin.push.AdminNotifications
import net.alummahnow.admin.security.DeviceAuth
import net.alummahnow.admin.security.SecureWindows
import net.alummahnow.admin.ui.AdminApp
import net.alummahnow.admin.ui.OpenTarget
import net.alummahnow.core.bridge.AunLog

class MainActivity : ComponentActivity() {
    /** OAuth redirects (`alummahnow-admin://auth/callback?...`), delivered once each. */
    private val redirects = Channel<String>(Channel.BUFFERED)

    /** An item to open from a notification (after the device unlock). */
    private val openTargets = MutableStateFlow<OpenTarget?>(null)

    /** Screenshot attempts (Android 14+). FLAG_SECURE makes them black; the app re-blurs and audits. */
    private val captures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private var captureCallback: Any? = null

    private val container get() = (application as AdminApplication).container

    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) container.lock.unlocked()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AdminLocale.wrap(newBase, AdminPrefs(newBase).language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SecureWindows.secure(window)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            AdminApp(
                services = container,
                redirects = redirects.receiveAsFlow(),
                openTargets = openTargets,
                captures = captures,
                deviceSecure = { DeviceAuth.isDeviceSecure(this) },
                openInBrowser = ::openInCustomTab,
                requestUnlock = ::requestUnlock,
                onLanguageChanged = ::recreate
            )
        }
    }

    override fun onStart() {
        super.onStart()
        container.lock.onForeground()
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            captureCallback = CaptureWatch.start(this) { captures.tryEmit(Unit) }
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CaptureWatch.stop(this, captureCallback)
        captureCallback = null
        container.lock.onBackground()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val data = intent.data
        if (data != null && data.scheme == AdminContainer.SCHEME && data.host == "auth") {
            redirects.trySend(data.toString())
            return
        }
        intent.getStringExtra(AdminNotifications.EXTRA_REVIEW_ID)?.let { id ->
            openTargets.value = OpenTarget(id, claim = intent.getBooleanExtra(AdminNotifications.EXTRA_CLAIM, false))
        }
    }

    private fun requestUnlock() {
        val launch = DeviceAuth.authenticate(
            this,
            getString(R.string.lock_prompt_title),
            getString(R.string.lock_prompt_subtitle)
        ) { ok -> if (ok) container.lock.unlocked() }
        if (launch != null) confirmCredential.launch(launch)
    }

    /** Opens [url] in a Custom Tab (the browser's Al Ummah Now session). False when there is no browser. */
    private fun openInCustomTab(url: String): Boolean = try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, Uri.parse(url))
        true
    } catch (e: ActivityNotFoundException) {
        AunLog.w("auth", "no browser", "type" to e.javaClass.simpleName)
        false
    }
}

/** Android 14+ screenshot detection, kept apart so older devices never load the API 34 types. */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private object CaptureWatch {
    fun start(activity: android.app.Activity, onCapture: () -> Unit): Any {
        val callback = android.app.Activity.ScreenCaptureCallback { onCapture() }
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        return callback
    }

    fun stop(activity: android.app.Activity, callback: Any?) {
        (callback as? android.app.Activity.ScreenCaptureCallback)?.let { activity.unregisterScreenCaptureCallback(it) }
    }
}
