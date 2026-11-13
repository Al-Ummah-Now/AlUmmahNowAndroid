package net.alummahnow.admin.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Window
import android.view.WindowManager

/**
 * `FLAG_SECURE` on every window of the app: screenshots and recordings come out black and the recents thumbnail is
 * blank. Activities get it before their content is set; Compose dialogs, sheets and popups inherit it
 * (`SecureFlagPolicy.Inherit`) and the app's own sheets set `SecureOn` as well.
 */
object SecureWindows : Application.ActivityLifecycleCallbacks {
    fun secure(window: Window) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun isSecure(window: Window): Boolean = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = secure(activity.window)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = secure(activity.window)

    override fun onActivityStarted(activity: Activity) = secure(activity.window)

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
