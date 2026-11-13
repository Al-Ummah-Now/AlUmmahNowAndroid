package net.alummahnow.admin.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import net.alummahnow.core.bridge.AunLog

/**
 * Device unlock (fingerprint, face or the device PIN, pattern or password). A device without a secure lock screen
 * cannot sign in to the Admin app.
 */
object DeviceAuth {
    fun isDeviceSecure(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Shows the system unlock prompt. Android 10+ uses BiometricPrompt with the device credential as a fallback;
     * Android 8–9 returns a confirm-credential [Intent] for the caller to launch instead (null means call [onResult]).
     */
    fun authenticate(activity: Activity, title: String, subtitle: String, onResult: (Boolean) -> Unit): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            return activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, subtitle)
                ?: run {
                    onResult(false)
                    null
                }
        }
        val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    AunLog.w("lock", "unlock failed", "code" to errorCode)
                    onResult(false)
                }
            }
        )
        return null
    }
}
