package net.alummahnow.feature.passkey

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.auth.passkey.PasskeyError
import net.alummahnow.feature.passkey.CredentialManagerAuthenticator.Companion.mapCreate
import net.alummahnow.feature.passkey.CredentialManagerAuthenticator.Companion.mapGet
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Credential Manager exceptions → what the app shows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CredentialManagerMappingTest {
    @Before fun quiet() {
        AunLog.enabled = false
    }

    @Test fun `get failures`() {
        assertTrue(mapGet(GetCredentialCancellationException()) is PasskeyError.Cancelled)
        assertTrue(mapGet(NoCredentialException()) is PasskeyError.NoCredential)
        assertTrue(mapGet(GetCredentialUnsupportedException()) is PasskeyError.Unsupported)
        assertTrue(mapGet(GetCredentialProviderConfigurationException()) is PasskeyError.Unsupported)
        assertTrue(mapGet(GetPublicKeyCredentialDomException(NotAllowedError())) is PasskeyError.Cancelled)
        // A SecurityError is what a missing or wrong assetlinks.json looks like: a real failure, not a cancel.
        assertTrue(mapGet(GetPublicKeyCredentialDomException(SecurityError())) is PasskeyError.Failed)
        assertTrue(mapGet(GetCredentialUnknownException()) is PasskeyError.Failed)
    }

    @Test fun `create failures`() {
        assertTrue(mapCreate(CreateCredentialCancellationException()) is PasskeyError.Cancelled)
        assertTrue(mapCreate(CreatePublicKeyCredentialDomException(InvalidStateError())) is PasskeyError.AlreadyRegistered)
        assertTrue(mapCreate(CreatePublicKeyCredentialDomException(NotAllowedError())) is PasskeyError.Cancelled)
        assertTrue(mapCreate(CreateCredentialNoCreateOptionException()) is PasskeyError.Unsupported)
        assertTrue(mapCreate(CreateCredentialUnknownException()) is PasskeyError.Failed)
    }

    @Test fun `supported from Android 9`() {
        assertTrue(CredentialManagerAuthenticator(RuntimeEnvironment.getApplication()).isSupported)
    }
}
