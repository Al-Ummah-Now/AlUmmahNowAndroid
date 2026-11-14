package net.alummahnow.feature.passkey

import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.auth.passkey.PasskeyAuthenticator
import net.alummahnow.core.bridge.auth.passkey.PasskeyError

/**
 * [PasskeyAuthenticator] over androidx Credential Manager: the OS passkey sheet (Google Password Manager, 1Password
 * and other providers, a security key, or a phone by QR). [context] must be an Activity so the sheet can show.
 * The app's package and signing key must be listed for the RP domain in `/.well-known/assetlinks.json`
 * (`get_login_creds`); see docs/PASSKEYS.md.
 */
class CredentialManagerAuthenticator(private val context: Context) : PasskeyAuthenticator {
    private val manager by lazy { CredentialManager.create(context) }

    // Credential Manager passkeys need Android 9+ (Play services below 14, the platform from 14).
    override val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override suspend fun signIn(requestJson: String): String {
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        val credential = try {
            manager.getCredential(context, request).credential
        } catch (e: GetCredentialException) {
            throw mapGet(e)
        }
        return (credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw PasskeyError.Failed(IllegalStateException("not a passkey: ${credential.type}"))
    }

    override suspend fun create(requestJson: String): String {
        val response = try {
            manager.createCredential(context, CreatePublicKeyCredentialRequest(requestJson))
        } catch (e: CreateCredentialException) {
            throw mapCreate(e)
        }
        return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw PasskeyError.Failed(IllegalStateException("not a passkey: ${response.type}"))
    }

    companion object {
        /** Platform failures → [PasskeyError]. Only the exception type is logged, never the message (it can carry ids). */
        fun mapGet(e: GetCredentialException): PasskeyError = when {
            e is GetCredentialCancellationException -> PasskeyError.Cancelled()
            e is NoCredentialException -> PasskeyError.NoCredential()
            e is GetCredentialUnsupportedException || e is GetCredentialProviderConfigurationException -> PasskeyError.Unsupported(e)
            // Providers report "the person dismissed it" or a timeout as NotAllowedError.
            e is GetPublicKeyCredentialDomException && e.domError is NotAllowedError -> PasskeyError.Cancelled()
            else -> PasskeyError.Failed(e)
        }.also { AunLog.w("passkey", "get failed", "type" to e.javaClass.simpleName) }

        fun mapCreate(e: CreateCredentialException): PasskeyError = when {
            e is CreateCredentialCancellationException -> PasskeyError.Cancelled()
            e is CreatePublicKeyCredentialDomException && e.domError is InvalidStateError -> PasskeyError.AlreadyRegistered()
            e is CreatePublicKeyCredentialDomException && e.domError is NotAllowedError -> PasskeyError.Cancelled()
            e is CreateCredentialUnsupportedException ||
                e is CreateCredentialProviderConfigurationException ||
                e is CreateCredentialNoCreateOptionException -> PasskeyError.Unsupported(e)
            else -> PasskeyError.Failed(e)
        }.also { AunLog.w("passkey", "create failed", "type" to e.javaClass.simpleName) }
    }
}
