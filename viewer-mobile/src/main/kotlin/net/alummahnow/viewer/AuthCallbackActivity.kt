package net.alummahnow.viewer

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives `alummahnow://auth/callback?code=…&state=…` from the Custom Tab, hands it to the auth bridge
 * (which checks `state` against the sign-in it started, so a forged redirect is ignored), and returns to
 * the app. Has no UI of its own.
 */
class AuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.toString()?.let { (application as AunViewerApp).container.completeSignIn(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
