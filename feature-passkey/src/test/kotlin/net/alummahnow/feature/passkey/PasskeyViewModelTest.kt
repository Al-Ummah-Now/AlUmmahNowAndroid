package net.alummahnow.feature.passkey

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.auth.passkey.PasskeyError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PasskeyViewModelTest {
    @Before fun setUp() {
        AunLog.enabled = false
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun problem(p: PasskeyProblem) = PasskeyUiState.Problem(p)

    @Test fun `sign-in succeeds and reports the account`() {
        val session = FakeSession()
        val vm = PasskeyViewModel(session)
        var signedIn: String? = null
        vm.signIn(FakeAuthenticator()) { signedIn = it.userId }
        assertEquals("u1", signedIn)
        assertEquals(PasskeyUiState.Idle, vm.state.value)
    }

    @Test fun `cancel in the OS sheet is silent`() {
        val vm = PasskeyViewModel(FakeSession())
        val auth = FakeAuthenticator().apply { onSignIn = { throw PasskeyError.Cancelled() } }
        vm.signIn(auth)
        assertEquals(PasskeyUiState.Idle, vm.state.value)
        auth.onCreate = { throw PasskeyError.Cancelled() }
        vm.add(auth)
        assertEquals(PasskeyUiState.Idle, vm.state.value)
    }

    @Test fun `sign-in problems map to dialogs that offer the browser sign-in`() {
        val cases = listOf(
            PasskeyError.NoCredential() to PasskeyProblem.NO_PASSKEY,
            PasskeyError.Unsupported() to PasskeyProblem.SIGN_IN_UNSUPPORTED,
            PasskeyError.RateLimited(30) to PasskeyProblem.SIGN_IN_RATE_LIMITED,
            PasskeyError.Rejected("invalid_credential", 400) to PasskeyProblem.SIGN_IN_FAILED,
            PasskeyError.Failed() to PasskeyProblem.SIGN_IN_FAILED,
            CoreError.Network(java.io.IOException()) to PasskeyProblem.SIGN_IN_OFFLINE,
            CoreError.Unavailable("native passkeys") to PasskeyProblem.SIGN_IN_FAILED
        )
        cases.forEach { (error, expected) ->
            val session = FakeSession().apply { serverErrors += error }
            val vm = PasskeyViewModel(session)
            vm.signIn(FakeAuthenticator())
            assertEquals(error.javaClass.simpleName, problem(expected), vm.state.value)
            assertEquals(true, expected.offerOtherWay)
        }
    }

    @Test fun `add succeeds`() {
        val session = FakeSession(signedIn = true)
        val vm = PasskeyViewModel(session)
        vm.add(FakeAuthenticator())
        assertEquals(PasskeyUiState.Added, vm.state.value)
        assertEquals(1, session.added)
        vm.dismiss()
        assertEquals(PasskeyUiState.Idle, vm.state.value)
    }

    @Test fun `add problems map to dialogs`() {
        val cases = listOf(
            PasskeyError.AlreadyRegistered() to problem(PasskeyProblem.ALREADY_ADDED),
            PasskeyError.Rejected("exists", 409) to problem(PasskeyProblem.ALREADY_ADDED),
            PasskeyError.Rejected("limit", 409) to problem(PasskeyProblem.LIMIT),
            PasskeyError.Unsupported() to problem(PasskeyProblem.ADD_UNSUPPORTED),
            PasskeyError.RateLimited(null) to problem(PasskeyProblem.ADD_RATE_LIMITED),
            PasskeyError.Rejected("invalid_registration", 400) to problem(PasskeyProblem.ADD_FAILED),
            CoreError.Network(java.io.IOException()) to problem(PasskeyProblem.ADD_OFFLINE),
            CoreError.NotSignedIn() to problem(PasskeyProblem.ADD_FAILED),
            PasskeyError.StepUpRequired() to PasskeyUiState.NeedsSignInAgain,
            PasskeyError.Rejected("stale_sign_in", 401) to PasskeyUiState.NeedsSignInAgain
        )
        cases.forEach { (error, expected) ->
            val vm = PasskeyViewModel(FakeSession(signedIn = true).apply { serverErrors += error })
            vm.add(FakeAuthenticator())
            assertEquals(error.javaClass.simpleName, expected, vm.state.value)
        }
    }

    @Test fun `step-up required, sign in again as the same person, then the add is retried`() {
        val session = FakeSession(signedIn = true).apply { serverErrors += PasskeyError.StepUpRequired() }
        val vm = PasskeyViewModel(session)
        val auth = FakeAuthenticator()
        vm.add(auth)
        assertEquals(PasskeyUiState.NeedsSignInAgain, vm.state.value)
        assertEquals(0, auth.creates)
        vm.signInAgainStarted()
        assertEquals(PasskeyUiState.AwaitingSignIn("u1"), vm.state.value)
        vm.signedIn(account("u1"))
        assertEquals(PasskeyUiState.RetryAdd, vm.state.value)
        vm.add(auth)
        assertEquals(PasskeyUiState.Added, vm.state.value)
        assertEquals(1, auth.creates)
    }

    @Test fun `signing in again as someone else does not add the passkey there`() {
        val vm = PasskeyViewModel(FakeSession(signedIn = true).apply { serverErrors += PasskeyError.StepUpRequired() })
        vm.add(FakeAuthenticator())
        vm.signInAgainStarted()
        vm.signedIn(account("u2"))
        assertEquals(problem(PasskeyProblem.OTHER_ACCOUNT), vm.state.value)
    }

    @Test fun `an ordinary sign-in does not trigger a retry`() {
        val vm = PasskeyViewModel(FakeSession(signedIn = true))
        vm.signedIn(account("u1"))
        assertEquals(PasskeyUiState.Idle, vm.state.value)
        assertNull((vm.state.value as? PasskeyUiState.AwaitingSignIn)?.userId)
    }
}
