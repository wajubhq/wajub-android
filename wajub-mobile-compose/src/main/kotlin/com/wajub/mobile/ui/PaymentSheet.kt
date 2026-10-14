package com.wajub.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wajub.mobile.WajubMobile
import com.wajub.mobile.WajubSession
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.PaymentSuccess
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessingContext
import com.wajub.mobile.model.ProcessingKind
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.UNSUPPORTED_ACTION_CODE
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

/** The same, on a session you created (`WajubMobile.createSession`); [PresentOptions.sessionToken] is not read. */
@Composable
fun PaymentSheet(session: WajubSession, options: PresentOptions = PresentOptions(), onComplete: (CheckoutResult) -> Unit) {
    SheetHost(System.identityHashCode(session), { session }, options, onComplete = onComplete)
}

/**
 * The 1.x sheet. [onResult] is called as in 1.x, with each answer to a
 * payment request (`RequiresAction` for a push approval or a provider page,
 * `Complete`, `Processing`, `Failed` for a step it cannot take, the outcome of
 * a card paid on the provider's page) — and with `Complete` when the payment
 * succeeds; [onDismiss] when the payer closes it. Redirects, approvals,
 * confirmation steps and watching happen in the sheet.
 */
@Deprecated(
    "Use PaymentSheet(session, PresentOptions(), onComplete): its CheckoutResult also says why a sheet closed.",
    ReplaceWith("PaymentSheet(wajubSession, PresentOptions()) { result -> }", "com.wajub.mobile.checkout.PresentOptions"),
)
@Composable
fun PaymentSheet(
    wajubSession: WajubSession,
    onDismiss: () -> Unit,
    onResult: (PaymentResult) -> Unit,
) {
    // As in 1.x, the success screen stays until the app hides the sheet.
    SheetHost(System.identityHashCode(wajubSession), { wajubSession }, LEGACY_OPTIONS, LegacyCallbacks(onResult, onDismiss)) { }
}

private val LEGACY_OPTIONS = PresentOptions(autoCloseSuccess = 0)

/** The 1.x sheet's callbacks, kept current across recompositions by the view model. */
internal class LegacyCallbacks(val onResult: (PaymentResult) -> Unit, val onDismiss: () -> Unit)

private fun paidTransaction(payment: PaymentSuccess) =
    SessionTransaction(
        id = payment.id,
        reference = payment.reference.orEmpty(),
        amount = payment.amount,
        amountTotal = payment.amount,
        currency = payment.currency,
        status = "succeeded",
        receiptUrl = payment.receiptUrl,
    )

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

/** Keeps the sheet's engine across configuration changes; a host gone for good settles it. */
internal class CheckoutViewModel : ViewModel() {
    private var controller: CheckoutController? = null
    private var completeReported = false

    // A result that lands while no host is attached (mid configuration change) waits for the next one.
    private var undelivered: CheckoutResult? = null

    /** Set when the host itself goes away: 1.x had no callback for that. */
    var hostLeaving = false

    var legacy: LegacyCallbacks? = null

    var onComplete: ((CheckoutResult) -> Unit)? = null
        set(value) {
            field = value
            val waiting = undelivered
            if (value != null && waiting != null) {
                undelivered = null
                value(waiting)
            }
        }

    fun controllerFor(session: () -> WajubSession, options: PresentOptions): CheckoutController =
        controller ?: CheckoutController(session(), options, viewModelScope).also { created ->
            controller = created
            created.onFinished = { result ->
                legacy?.let { closed(it, result) }
                val host = onComplete
                if (host != null) host(result) else undelivered = result
                // Done: let go of the host (its lambdas, the activity behind the browser opener).
                created.urlOpener = null
                legacy = null
            }
            if (legacy != null) {
                created.onAnswer = { outcome, completion -> legacyResult(outcome, completion, created.transaction)?.let(::report) }
                viewModelScope.launch {
                    created.screen.collect { screen ->
                        if (screen is SheetScreen.Success && !completeReported) {
                            report(PaymentResult.Complete(created.transaction ?: paidTransaction(screen.payment)))
                        }
                    }
                }
            }
            created.start()
        }

    private fun report(result: PaymentResult) {
        if (result is PaymentResult.Complete) completeReported = true
        legacy?.onResult?.invoke(result)
    }

    private fun closed(callbacks: LegacyCallbacks, result: CheckoutResult) {
        if (result is CheckoutResult.Complete && !completeReported) {
            report(PaymentResult.Complete(result.transaction ?: paidTransaction(result.payment)))
        }
        // 1.x called onDismiss when the payer closed the sheet, never when the app removed it.
        if (!hostLeaving) callbacks.onDismiss()
    }

    fun hostGone() {
        hostLeaving = true
        controller?.hostGone()
    }

    override fun onCleared() {
        hostGone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
        viewModel(key = "wajub-checkout-$presentation") { CheckoutViewModel() }
    } else {
        // No ViewModelStoreOwner (1.x did not need one): the sheet lives with this composition.
        val store = remember(presentation) { ViewModelStore() }
        DisposableEffect(store) { onDispose { store.clear() } }
        remember(store) { ViewModelProvider(store, viewModelFactory { initializer { CheckoutViewModel() } })[CheckoutViewModel::class.java] }
    }
    val latest by rememberUpdatedState(onComplete)
    // Before the controller starts: a sheet rejected at once still reports it.
    model.legacy = legacy
    if (model.onComplete == null) model.onComplete = { latest(it) }
    val controller = remember(model) {
        // The device's details before the first request (the init provider may be disabled).
        WajubAndroid.install(context)
        model.controllerFor(session, options)
    }
    SideEffect {
        model.onComplete = { latest(it) }
        controller.urlOpener = { url -> HostedRedirectLauncher.open(context, url) }
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
                model.onComplete = null
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
    ModalBottomSheet(
        onDismissRequest = controller::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = look.colors.background.color(),
        tonalElevation = look.elevation.dp,
        scrimColor = look.scrimAlpha?.let { Color.Black.copy(alpha = it) } ?: BottomSheetDefaults.ScrimColor,
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
