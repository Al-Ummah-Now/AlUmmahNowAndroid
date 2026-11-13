package net.alummahnow.admin.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.admin.AdminServices
import net.alummahnow.admin.R
import net.alummahnow.admin.push.PushState
import net.alummahnow.admin.security.StepUp
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.admin.ReviewError
import net.alummahnow.core.bridge.admin.StaffProfile
import net.alummahnow.core.bridge.studio.SignInMode

sealed interface SignInState {
    data object Idle : SignInState
    data object InBrowser : SignInState
    data object Completing : SignInState
    data class Error(@param:StringRes val message: Int) : SignInState
}

sealed interface ProfileState {
    data object Loading : ProfileState
    data class Ready(val profile: StaffProfile) : ProfileState
    data object NotStaff : ProfileState
    data object Offline : ProfileState
    data object Failed : ProfileState
}

/** Sign-in, the staff check, step-up and sign-out (AUN-ADMIN-NATIVE, Auth). */
class SessionViewModel(private val services: AdminServices) : ViewModel() {
    private val mutableSignIn = MutableStateFlow<SignInState>(SignInState.Idle)
    val signIn: StateFlow<SignInState> = mutableSignIn.asStateFlow()
    private val mutableProfile = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val profile: StateFlow<ProfileState> = mutableProfile.asStateFlow()

    /** A one-off message for the snackbar (string resource). */
    val notice = MutableStateFlow<Int?>(null)
    val push = MutableStateFlow<PushState?>(null)

    /** The action waiting for a fresh sign-in, and who must sign in. */
    private var pendingStepUp: (() -> Unit)? = null
    private var stepUpUser: String? = null

    fun beginSignIn(): String? = try {
        services.session.beginSignIn(SignInMode.DEFAULT).also { mutableSignIn.value = SignInState.InBrowser }
    } catch (e: CoreError) {
        AunLog.e("auth", "sign-in could not start", e)
        mutableSignIn.value = SignInState.Error(R.string.signin_failed)
        null
    }

    /**
     * Runs [action] now when the last sign-in is under 15 minutes old; otherwise returns the step-up URL
     * (`prompt=login&max_age=900`) to open, and runs [action] after the redirect comes back as the same person.
     */
    fun requireStepUp(force: Boolean = false, action: () -> Unit): String? {
        if (!force && services.stepUp.isFresh) {
            action()
            return null
        }
        return try {
            services.session.beginStepUp(StepUp.MAX_AGE_SECONDS).also {
                pendingStepUp = action
                stepUpUser = services.session.active.value?.userId
                mutableSignIn.value = SignInState.InBrowser
            }
        } catch (e: CoreError) {
            AunLog.e("auth", "step-up could not start", e)
            notice.value = R.string.stepup_failed
            null
        }
    }

    fun complete(redirectUrl: String) {
        mutableSignIn.value = SignInState.Completing
        viewModelScope.launch {
            runCatching { services.session.completeSignIn(redirectUrl) }.fold(
                onSuccess = { account ->
                    mutableSignIn.value = SignInState.Idle
                    val action = pendingStepUp
                    val expected = stepUpUser
                    pendingStepUp = null
                    stepUpUser = null
                    if (action != null && expected != null && account.userId != expected) {
                        notice.value = R.string.stepup_other_account
                        return@fold
                    }
                    services.stepUp.signedIn()
                    if (action != null) action() else loadProfile()
                },
                onFailure = { e ->
                    AunLog.e("auth", "sign-in failed", e)
                    val wasStepUp = pendingStepUp != null
                    pendingStepUp = null
                    stepUpUser = null
                    mutableSignIn.value = SignInState.Error(
                        when {
                            redirectUrl.contains("error=access_denied") -> R.string.signin_cancelled
                            e is CoreError.Network -> R.string.error_offline
                            else -> R.string.signin_failed
                        }
                    )
                    if (wasStepUp) notice.value = R.string.stepup_failed
                }
            )
        }
    }

    fun browserClosed() {
        if (mutableSignIn.value == SignInState.InBrowser) mutableSignIn.value = SignInState.Idle
        pendingStepUp = null
    }

    fun noBrowser() {
        mutableSignIn.value = SignInState.Error(R.string.signin_no_browser)
    }

    fun loadProfile() {
        mutableProfile.value = ProfileState.Loading
        viewModelScope.launch {
            mutableProfile.value = try {
                ProfileState.Ready(services.review.profile())
            } catch (_: ReviewError.NotStaff) {
                ProfileState.NotStaff
            } catch (_: ReviewError.Offline) {
                ProfileState.Offline
            } catch (e: CoreError) {
                AunLog.e("admin", "profile failed", e)
                if (e is CoreError.Network) ProfileState.Offline else ProfileState.Failed
            }
        }
    }

    /** Signs this account out here and at the auth server; returns the logout page to open (or null). */
    suspend fun signOut(): String? {
        val userId = services.session.active.value?.userId ?: return null
        return runCatching { services.session.signOut(userId) }
            .onFailure { AunLog.e("auth", "sign-out failed", it) }
            .getOrNull()
            .also {
                services.stepUp.forget()
                services.lock.lockNow()
                mutableProfile.value = ProfileState.Loading
            }
    }
}
