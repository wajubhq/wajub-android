package com.wajub.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wajub.mobile.WajubMobile
import com.wajub.mobile.WajubSession
import com.wajub.mobile.checkout.CancelReason
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessingContext
import com.wajub.mobile.model.ProcessingKind
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.UNSUPPORTED_ACTION_CODE
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Wajub's native payment sheet — Mobile Money, card and wallets, no WebView.
 *
 * Show it while you want the sheet up; [onComplete] receives the result
 * exactly once, when it closes (also when the screen that showed it goes away
 * for good). The sheet survives configuration changes.
 *
 * ```kotlin
 * if (paying) {
 *     PaymentSheet(PresentOptions(sessionToken = authorizationToken)) { result ->
 *         paying = false
 *         // CheckoutResult.Complete / Processing / Failed / Cancelled
 *     }
 * }
 * ```
 */
@Composable
fun PaymentSheet(options: PresentOptions, onComplete: (CheckoutResult) -> Unit) {
    SheetHost(options.sessionToken, { WajubMobile.createSession(options.sessionToken) }, options, onComplete = onComplete)
}

/**
 * The same, on a session you created (`WajubMobile.createSession`);
 * [PresentOptions.sessionToken] is not read. The sheet is tied to the
 * session's payment, not to the object: a session created again on
 * recomposition is the same sheet.
 */
@Composable
fun PaymentSheet(session: WajubSession, options: PresentOptions = PresentOptions(), onComplete: (CheckoutResult) -> Unit) {
    SheetHost(session.sessionKey, { session }, options, onComplete = onComplete)
}

/**
 * The sheet with its earlier callbacks: [onResult] receives each answer to a
 * payment request (`RequiresAction` for a push approval or a provider's page,
 * `Complete`, `Processing`, `Failed` for a step it cannot take, the outcome of
 * a card paid on the provider's page); [onDismiss] fires when the payer closes
 * it, and the payment is cancelled then only when nothing was submitted.
 */
@Deprecated("Use PaymentSheet(session, PresentOptions(), onComplete): one CheckoutResult when the sheet closes, saying why.")
@Composable
fun PaymentSheet(
    wajubSession: WajubSession,
    onDismiss: () -> Unit,
    onResult: (PaymentResult) -> Unit,
) {
    SheetHost(wajubSession.sessionKey, { wajubSession }, LEGACY_OPTIONS, LegacyCallbacks(onResult, onDismiss)) { }
}

// As in 1.x: the success screen stays until the app hides the sheet, and a
// dismiss never waits for a cancel (the view model sends 1.x's own).
private val LEGACY_OPTIONS = PresentOptions(autoCloseSuccess = 0, cancelOnDismiss = false)

/** The 1.x sheet's callbacks, kept current across recompositions by the view model. */
internal class LegacyCallbacks(val onResult: (PaymentResult) -> Unit, val onDismiss: () -> Unit)

// What 1.x's onResult received for an answer; null for what 1.x never reported
// (refusals were shown in the form; the sheet opened provider pages itself).
private fun legacyResult(outcome: ProcessOutcome, completion: Boolean, last: SessionTransaction?): PaymentResult? {
    if (completion) {
        // 1.x, back from the provider's page: paid, a decline, or still processing.
        return when (outcome) {
            is ProcessOutcome.Complete -> PaymentResult.Complete(outcome.transaction)
            is ProcessOutcome.Failed ->
                if (outcome.error.declineCode != null) PaymentResult.Failed(outcome.error, outcome.transaction ?: last) else last?.let { PaymentResult.Processing(it) }
            is ProcessOutcome.Processing -> (outcome.transaction ?: last)?.let { PaymentResult.Processing(it, outcome.instruction) }
            is ProcessOutcome.RequiresAction -> null
        }
    }
    return when (outcome) {
        is ProcessOutcome.Complete -> PaymentResult.Complete(outcome.transaction)
        is ProcessOutcome.Processing -> outcome.transaction?.let { txn ->
            if (outcome.kind == ProcessingKind.Pending) {
                PaymentResult.Processing(txn, outcome.instruction)
            } else {
                PaymentResult.RequiresAction(
                    ActionKind.PushApproval,
                    null,
                    txn.copy(processingContext = ProcessingContext(payerInstruction = outcome.instruction)),
                )
            }
        }
        is ProcessOutcome.RequiresAction -> outcome.transaction?.let { txn ->
            when {
                outcome.url == null || outcome.action == "client_session" -> null
                outcome.action == "redirect" -> PaymentResult.RequiresAction(ActionKind.Redirect, outcome.url, txn)
                outcome.action == "confirm" -> PaymentResult.RequiresAction(ActionKind.Confirm, outcome.url, txn)
                outcome.action == "confirm_3ds" -> PaymentResult.RequiresAction(ActionKind.Confirm3ds, outcome.url, txn)
                else -> null
            }
        }
        is ProcessOutcome.Failed ->
            if (outcome.error.code == UNSUPPORTED_ACTION_CODE) PaymentResult.Failed(outcome.error, outcome.transaction) else null
    }
}

/**
 * Keeps the sheet's engine across configuration changes (and its client
 * session across process death); a host gone for good settles it.
 */
internal class CheckoutViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private var controller: CheckoutController? = null
    private var session: WajubSession? = null
    private var watching: Job? = null

    // A result that lands while no host is attached (mid configuration change) waits for the next one.
    private var undelivered: CheckoutResult? = null

    // Set when the host itself goes away: 1.x had no callback for that.
    private var hostLeaving = false

    // 1.x: a payment is cancelled on a dismiss only when nothing was ever submitted.
    private var submitted = false

    var legacy: LegacyCallbacks? = null

    // The host's onComplete while one is attached; null between a configuration
    // change's old and new screens.
    private var onComplete: ((CheckoutResult) -> Unit)? = null
    private var delivered = false

    /**
     * Attaches the showing screen's [host]. A result that settled while no
     * screen was attached (mid configuration change) is delivered now.
     */
    fun attach(host: (CheckoutResult) -> Unit) {
        if (delivered) return
        val waiting = undelivered
        if (waiting != null) {
            undelivered = null
            delivered = true
            onComplete = null
            host(waiting)
        } else {
            onComplete = host
        }
    }

    /** The screen goes away for a configuration change: a result now waits for the next one. */
    fun detach() {
        onComplete = null
    }

    val settled: Boolean get() = controller?.result?.value != null

    fun controllerFor(session: () -> WajubSession, options: PresentOptions): CheckoutController =
        controller ?: session().let { owned ->
            this.session = owned
            CheckoutController(owned, options, viewModelScope).also { created ->
                controller = created
                saved.get<String>(CLIENT_SESSION)?.let { created.restoreClientSession(it) }
                created.onFinished = { result -> settle(created, result) }
                watching = viewModelScope.launch {
                    launch { created.pendingClientSession.collect { saved[CLIENT_SESSION] = it } }
                    if (legacy != null) {
                        created.onAnswer = { outcome, completion -> legacyResult(outcome, completion, created.transaction)?.let { legacy?.onResult?.invoke(it) } }
                        created.screen.collect { screen -> if (screen is SheetScreen.Form && screen.busy) submitted = true }
                    }
                }
                created.start()
            }
        }

    private fun settle(created: CheckoutController, result: CheckoutResult) {
        legacy?.let { callbacks ->
            // 1.x: onDismiss when the payer closed the sheet (never when the app removed it),
            // with 1.x's fire-and-forget cancel when nothing was submitted.
            if (!hostLeaving) {
                if (result is CheckoutResult.Cancelled && result.reason == CancelReason.Dismissed) {
                    session?.cancelIfNothingSubmitted(submitted)
                }
                callbacks.onDismiss()
            }
        }
        val host = onComplete
        if (host != null) {
            onComplete = null
            delivered = true
            host(result)
        } else {
            undelivered = result
        }
        // Done: let go of the host (its lambdas, the activity behind the browser opener).
        created.urlOpener = null
        legacy = null
        saved.remove<String>(CLIENT_SESSION)
        watching?.cancel()
    }

    fun hostGone() {
        hostLeaving = true
        controller?.hostGone()
    }

    override fun onCleared() {
        hostGone()
    }

    private companion object {
        const val CLIENT_SESSION = "wajub.client_session"
    }
}

@Composable
private fun SheetHost(
    key: Any,
    session: () -> WajubSession,
    options: PresentOptions,
    legacy: LegacyCallbacks? = null,
    onComplete: (CheckoutResult) -> Unit,
) {
    val context = LocalContext.current
    // One presentation: kept across configuration changes, new each time the sheet is shown
    // (or shown for another payment).
    val presentation = rememberSaveable(key) { UUID.randomUUID().toString() }
    val model: CheckoutViewModel = if (LocalViewModelStoreOwner.current != null) {
        viewModel(key = "wajub-checkout-$presentation") { CheckoutViewModel(createSavedStateHandle()) }
    } else {
        // No ViewModelStoreOwner (1.x did not need one): the sheet lives with this composition.
        val store = remember(presentation) { ViewModelStore() }
        DisposableEffect(store) { onDispose { store.clear() } }
        remember(store) { ViewModelProvider(store, viewModelFactory { initializer { CheckoutViewModel(SavedStateHandle()) } })[CheckoutViewModel::class.java] }
    }
    val latest by rememberUpdatedState(onComplete)
    // Before the controller starts: a sheet rejected at once still reports it.
    if (!model.settled) {
        model.legacy = legacy
        model.attach { latest(it) }
    }
    val controller = remember(model) {
        // The device's details before the first request (the init provider may be disabled).
        WajubAndroid.install(context)
        model.controllerFor(session, options)
    }
    SideEffect {
        // Also after the sheet settled: a result that landed while no screen was
        // attached (a configuration change) reaches this one.
        model.attach { latest(it) }
        if (!model.settled) {
            model.legacy = legacy
            // The callbacks of the screen showing the sheet now (after a rotation, the new one's).
            controller.callbacks = options.callbacks
            controller.urlOpener = { url -> HostedRedirectLauncher.open(context, url) }
        }
    }

    // Back in the app (browser closed, app foregrounded): read the status at once.
    // The controller completes a client session only when the payer had left for its page.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.onReturn()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Left composition for good (not for a configuration change): the host went away.
    DisposableEffect(model) {
        onDispose {
            if (context.findActivity()?.isChangingConfigurations == true) {
                model.detach()
            } else {
                model.hostGone()
            }
        }
    }

    // Dismissed or done: the sheet hides at once, even while a cancel settles the result.
    val closing by controller.closing.collectAsState()
    if (closing) return
    val data by controller.sessionData.collectAsState()
    val look = rememberSheetLook(options.appearance, data?.brand)
    BottomSheet(
        onDismissRequest = controller::dismiss,
        containerColor = look.colors.background.color(),
        elevation = look.elevation,
        scrimAlpha = look.scrimAlpha,
        radius = look.radius,
    ) {
        PaymentSheetContent(controller)
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
