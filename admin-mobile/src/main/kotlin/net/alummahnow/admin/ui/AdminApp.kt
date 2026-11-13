package net.alummahnow.admin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.alummahnow.admin.AdminServices
import net.alummahnow.admin.R
import net.alummahnow.admin.push.AdminNotifications
import net.alummahnow.admin.push.AdminPush
import net.alummahnow.admin.push.PushState
import net.alummahnow.admin.queue.QueueScreen
import net.alummahnow.admin.queue.QueueViewModel
import net.alummahnow.admin.review.DecisionRequest
import net.alummahnow.admin.review.ReviewScreen
import net.alummahnow.admin.review.ReviewViewModel
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.admin.CaptureEvent
import net.alummahnow.core.bridge.admin.ReviewDecision
import net.alummahnow.core.bridge.admin.ReviewError
import net.alummahnow.core.bridge.admin.ReviewItem
import net.alummahnow.core.bridge.admin.StaffProfile
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode

/** An item to open from a notification; [claim] when the reviewer tapped Claim. */
data class OpenTarget(val reviewId: String, val claim: Boolean)

/** How long the undo toast stays (AD8). After that, History has "Reverse decision" while the server allows it. */
const val UNDO_TOAST_MS = 10_000L

/** A revealed item blurs again after this long without interaction. */
const val REVEAL_IDLE_MS = 60_000L

/**
 * The Admin app: sign-in (staff only), device unlock, the review queue and the review screen. Light or Dark only: the
 * first launch follows the device, then the reviewer's explicit choice.
 */
@Composable
fun AdminApp(
    services: AdminServices,
    redirects: Flow<String>,
    openTargets: MutableStateFlow<OpenTarget?>,
    captures: Flow<Unit>,
    deviceSecure: () -> Boolean,
    openInBrowser: (String) -> Boolean,
    requestUnlock: () -> Unit,
    onLanguageChanged: () -> Unit,
    clock: () -> Instant = Instant::now
) {
    var dark by remember { mutableStateOf(services.prefs.dark) }
    val systemDark = isSystemInDarkTheme()
    val isDark = dark ?: systemDark
    AunTheme(mode = if (isDark) ThemeMode.DARK else ThemeMode.LIGHT) {
        AdminContent(
            services, redirects, openTargets, captures, deviceSecure, openInBrowser, requestUnlock, clock,
            dark = isDark,
            onDark = {
                services.prefs.dark = it
                dark = it
            },
            onLanguage = {
                if (it != services.prefs.language) {
                    services.prefs.language = it
                    onLanguageChanged()
                }
            }
        )
    }
}

@Composable
private fun rememberNow(clock: () -> Instant): Instant {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = clock()
        }
    }
    return now
}

private const val TICK_MS = 60_000L

@Composable
private fun AdminContent(
    services: AdminServices,
    redirects: Flow<String>,
    openTargets: MutableStateFlow<OpenTarget?>,
    captures: Flow<Unit>,
    deviceSecure: () -> Boolean,
    openInBrowser: (String) -> Boolean,
    requestUnlock: () -> Unit,
    clock: () -> Instant,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    onLanguage: (String) -> Unit
) {
    val session: SessionViewModel = viewModel { SessionViewModel(services) }
    val signIn by session.signIn.collectAsStateWithLifecycle()
    val profile by session.profile.collectAsStateWithLifecycle()
    val active by services.session.active.collectAsStateWithLifecycle()
    val locked by services.lock.locked.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var secure by remember { mutableStateOf(deviceSecure()) }

    LaunchedEffect(Unit) { redirects.collect { session.complete(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        session.browserClosed()
        secure = deviceSecure()
    }

    fun open(url: String?) {
        if (url != null && !openInBrowser(url)) session.noBrowser()
    }

    val appContext = LocalContext.current.applicationContext
    val signOut: () -> Unit = {
        scope.launch {
            // The push device first (it needs the token), then the session.
            AdminPush.unregister(appContext, services.push, services.prefs.installId)
            session.signOut()?.let { openInBrowser(it) }
        }
    }

    val account = active
    when {
        account == null || !secure -> {
            AdminSignInScreen(signIn, deviceSecure = secure, onSignIn = { open(session.beginSignIn()) })
            return
        }
        locked -> {
            LockScreen(onUnlock = requestUnlock)
            return
        }
    }
    LaunchedEffect(account.userId) { session.loadProfile() }
    when (val p = profile) {
        ProfileState.Loading -> LoadingScreen()
        ProfileState.NotStaff -> NotStaffScreen(onSignOut = signOut)
        ProfileState.Offline, ProfileState.Failed -> ProfileErrorScreen(p == ProfileState.Offline, session::loadProfile, signOut)
        is ProfileState.Ready -> StaffHome(
            services, session, p.profile, openTargets, captures, clock, dark, onDark, onLanguage, signOut,
            requireStepUp = { force, action -> open(session.requireStepUp(force, action)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StaffHome(
    services: AdminServices,
    session: SessionViewModel,
    profile: StaffProfile,
    openTargets: MutableStateFlow<OpenTarget?>,
    captures: Flow<Unit>,
    clock: () -> Instant,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    onLanguage: (String) -> Unit,
    onSignOut: () -> Unit,
    requireStepUp: (Boolean, () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNow(clock)
    val online = rememberOnline()
    val push by session.push.collectAsStateWithLifecycle()
    val notice by session.notice.collectAsStateWithLifecycle()
    val account by services.session.active.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var reblurTick by remember { mutableLongStateOf(0L) }
    val queue: QueueViewModel =
        viewModel(key = "queue-${profile.userId}") { QueueViewModel(services.review, profile.isSeniorReviewer, clock) }
    val queueState by queue.state.collectAsStateWithLifecycle()

    // Alerts: ask for notification permission (Android 13+), then register this install with app "admin".
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch {
            session.push.value = if (granted) {
                AdminPush.register(context, services.push, services.prefs.installId, services.prefs.language)
            } else {
                PushState.BLOCKED
            }
        }
    }
    LaunchedEffect(profile.userId) {
        when {
            !AdminPush.available(context) -> session.push.value = PushState.NOT_CONFIGURED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> session.push.value = AdminPush.register(context, services.push, services.prefs.installId, services.prefs.language)
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(resources.getString(it))
            session.notice.value = null
        }
    }
    // A notification's Open or Claim (the device unlock has already happened to get here).
    val target by openTargets.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        target?.let {
            AdminNotifications.clear(context, it.reviewId)
            openId = it.reviewId
            openTargets.value = null
        }
    }
    // Screenshot attempts (Android 14+): already black thanks to FLAG_SECURE; the item blurs and the attempt is audited.
    LaunchedEffect(Unit) {
        captures.collect {
            reblurTick++
            runCatching { services.review.recordCapture(CaptureEvent.SCREENSHOT, openId, services.prefs.installId) }
                .onFailure { AunLog.w("audit", "capture not recorded", "type" to it.javaClass.simpleName) }
        }
    }

    fun undoToast(item: ReviewItem, decision: ReviewDecision) {
        scope.launch {
            val message = resources.getString(
                when (decision) {
                    ReviewDecision.APPROVE -> R.string.toast_approved
                    ReviewDecision.REJECT -> R.string.toast_rejected
                    ReviewDecision.REQUEST_CHANGES -> R.string.toast_changes
                    else -> R.string.toast_escalated
                }
            )
            val result = withTimeoutOrNull(UNDO_TOAST_MS) {
                snackbar.showSnackbar(message, actionLabel = resources.getString(R.string.undo), duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) undo(services, queue, item, snackbar, resources)
        }
    }

    BackHandler(enabled = openId != null) { openId = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (openId == null) R.string.queue_title else R.string.review_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (openId != null) {
                        IconButton(onClick = { openId = null }, modifier = Modifier.testTag("back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (openId == null) {
                        IconButton(onClick = queue::refresh, modifier = Modifier.testTag("refresh")) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    }
                    IconButton(onClick = { settingsOpen = true }, modifier = Modifier.testTag("settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(padding)
        val id = openId
        if (id == null) {
            QueueScreen(
                queueState, profile.userId, now,
                onTab = queue::setTab,
                onQuery = queue::setQuery,
                onOpen = { openId = it.id },
                onReverse = { item -> scope.launch { undo(services, queue, item, snackbar, resources) } },
                onRefresh = queue::refresh,
                onLoadMore = queue::loadMore,
                modifier = body
            )
        } else {
            ReviewRoute(services, id, profile, now, online, reblurTick, requireStepUp, body) { item, decision ->
                openId = null
                queue.replace(item)
                if (decision != ReviewDecision.ESCALATE || profile.isSeniorReviewer) undoToast(item, decision)
            }
        }
    }
    if (settingsOpen) {
        SettingsSheet(
            name = profile.displayName ?: account?.displayName,
            dark = dark,
            language = services.prefs.language,
            push = push,
            onDark = onDark,
            onLanguage = onLanguage,
            onSignOut = {
                settingsOpen = false
                onSignOut()
            },
            onDismiss = { settingsOpen = false }
        )
    }
}

private suspend fun undo(
    services: AdminServices,
    queue: QueueViewModel,
    item: ReviewItem,
    snackbar: SnackbarHostState,
    resources: android.content.res.Resources
) {
    val message = try {
        queue.replace(services.review.decide(item.id, ReviewDecision.UNDO, null, null, item.version))
        R.string.toast_undone
    } catch (_: ReviewError.UndoExpired) {
        R.string.undo_expired
    } catch (_: ReviewError.Offline) {
        R.string.error_offline
    } catch (e: ReviewError) {
        AunLog.e("review", "undo refused", e)
        R.string.error_generic
    } catch (e: net.alummahnow.core.bridge.CoreError) {
        AunLog.e("review", "undo failed", e)
        if (e is net.alummahnow.core.bridge.CoreError.Network) R.string.error_offline else R.string.error_generic
    }
    snackbar.showSnackbar(resources.getString(message))
}

@Composable
private fun ReviewRoute(
    services: AdminServices,
    id: String,
    profile: StaffProfile,
    now: Instant,
    online: Boolean,
    reblurTick: Long,
    requireStepUp: (Boolean, () -> Unit) -> Unit,
    modifier: Modifier,
    onDecided: (ReviewItem, ReviewDecision) -> Unit
) {
    val context = LocalContext.current
    val vm: ReviewViewModel = viewModel(key = "review-$id") {
        ReviewViewModel(services.review, id, profile.userId, onClaimed = { AdminNotifications.clear(context.applicationContext, it) })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Blur again: on leaving the item, in the background, after a capture attempt and after 60 s idle.
    DisposableEffect(id) { onDispose { vm.reblur() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.reblur() }
    LaunchedEffect(reblurTick) { if (reblurTick > 0) vm.reblur() }
    LaunchedEffect(state.revealed, lastTouch) {
        if (state.revealed) {
            delay(REVEAL_IDLE_MS)
            vm.reblur()
        }
    }
    val item = state.detail?.item
    ReviewScreen(
        state = state,
        now = now,
        online = online,
        onShow = {
            // Minor items: a sign-in within 15 minutes before every reveal (AD13).
            if (item?.minor == true) requireStepUp(false) { vm.reveal() } else vm.reveal()
        },
        onDecide = { request ->
            val reason = (request as? DecisionRequest.Reject)?.reason
            val note = when (request) {
                is DecisionRequest.Reject -> request.note
                is DecisionRequest.RequestChanges -> request.note
                else -> null
            }
            val done: (ReviewItem) -> Unit = { onDecided(it, request.decision) }
            val run = {
                vm.decide(
                    request.decision,
                    reason,
                    note,
                    // The server asked for a fresh sign-in: sign in again, then send the same decision once.
                    onStepUp = { requireStepUp(true) { vm.decide(request.decision, reason, note, onStepUp = {}, onDone = done) } },
                    onDone = done
                )
            }
            // Approving an escalated item needs a recent sign-in (AD5).
            if (request == DecisionRequest.Approve && item?.escalate == true) requireStepUp(false, run) else run()
        },
        onDismissMessage = vm::dismissMessage,
        onRetry = vm::load,
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch = System.currentTimeMillis()
                }
            }
        }
    )
}
