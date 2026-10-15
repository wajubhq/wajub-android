package com.wajub.mobile.checkout

import com.wajub.mobile.Iso8601
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.WajubError

/** The sheet's 17 states; [value] is the state's name as a string (`3DS_REQUIRED`). */
enum class CheckoutState(val value: String) {
    INITIATED("INITIATED"),
    COLLECTING_DETAILS("COLLECTING_DETAILS"),
    PROCESSING("PROCESSING"),
    OTP_REQUIRED("OTP_REQUIRED"),
    PIN_REQUIRED("PIN_REQUIRED"),
    PHONE_REQUIRED("PHONE_REQUIRED"),
    BIRTHDAY_REQUIRED("BIRTHDAY_REQUIRED"),
    ADDRESS_REQUIRED("ADDRESS_REQUIRED"),
    THREE_DS_REQUIRED("3DS_REQUIRED"),
    USSD_REQUIRED("USSD_REQUIRED"),
    APPROVAL_REQUIRED("APPROVAL_REQUIRED"),
    VERIFYING("VERIFYING"),
    PENDING("PENDING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    EXPIRED("EXPIRED"),
    CANCELLED("CANCELLED"),
}

/** How the sheet looks while it loads. */
enum class LoaderStyle {
    /** Placeholder blocks in the sheet's colours (default). */
    Skeleton,

    /** A centred activity indicator. */
    Spinner,
}

/** How the operator list shows. */
enum class OperatorMode {
    /** The list is always shown (default). */
    Choose,

    /** The detected operator shows inside the field; the list opens when needed. */
    Detect,
}

/** The Mobile Money form's options. */
data class MobileMoneyOptions(
    /** ISO-2 country to start on. */
    val country: String? = null,
    /** Hides the country selector when [country] is set. */
    val countryLocked: Boolean = false,
    val operatorMode: OperatorMode = OperatorMode.Choose,
)

/** Everything `PaymentSheet` (and its `present`) takes. */
data class PresentOptions(
    /** The `authorization_token` your server got when it created the payment. */
    val sessionToken: String = "",
    /** `fr` or `en`; else the session's language, else the device's. */
    val locale: String? = null,
    val appearance: Appearance? = null,
    val loader: LoaderStyle = LoaderStyle.Skeleton,
    /** Milliseconds a sheet may load before it shows an error with Retry (`ready_timeout`); 0 disables it. */
    val readyTimeout: Long = 15_000L,
    /** An https App Link back into your app, where the payer lands after a payment provider's page. */
    val returnUrl: String? = null,
    val mobileMoney: MobileMoneyOptions = MobileMoneyOptions(),
    /** Closing the sheet before any payment attempt cancels the payment. */
    val cancelOnDismiss: Boolean = true,
    /** Milliseconds before the success screen closes itself; 0 keeps it. */
    val autoCloseSuccess: Long = 3_000L,
    val callbacks: CheckoutCallbacks = CheckoutCallbacks(),
) {
    // The token stays out of logs.
    override fun toString(): String =
        "PresentOptions(sessionToken=<redacted>, locale=$locale, appearance=$appearance, loader=$loader, " +
            "readyTimeout=$readyTimeout, returnUrl=$returnUrl, mobileMoney=$mobileMoney, " +
            "cancelOnDismiss=$cancelOnDismiss, autoCloseSuccess=$autoCloseSuccess)"
}

/**
 * The sheet's callbacks, all optional, all on the main thread, in this order:
 * `onReady` → `onStateChange`… → `onPaymentStarted`, `onRedirect` →
 * `onSuccess` / `onError` / `onCancel` → `onComplete` (exactly once).
 * A callback that throws does not break the sheet.
 */
class CheckoutCallbacks(
    /** The first real screen is drawn (also for an already-paid or ended session). */
    val onReady: ((ReadyEvent) -> Unit)? = null,
    /** After `onReady` with the first state, then on every change. */
    val onStateChange: ((StateChangeEvent) -> Unit)? = null,
    /** The sheet could not offer a payment: `session_not_found`, `load_error`, `ready_timeout`… */
    val onLoadError: ((WajubError) -> Unit)? = null,
    /** The payment request is accepted: from now on, closing the sheet resolves `processing`. */
    val onPaymentStarted: ((PaymentStartedEvent) -> Unit)? = null,
    /** Right before the SDK opens a page in the browser. */
    val onRedirect: ((RedirectEvent) -> Unit)? = null,
    /** Paid. Confirm on your server before delivering. */
    val onSuccess: ((PaymentSuccess) -> Unit)? = null,
    /** Every failure the payer sees, retryable ones included (not field validation). */
    val onError: ((WajubError) -> Unit)? = null,
    /** The sheet closes as cancelled. */
    val onCancel: ((CancelEvent) -> Unit)? = null,
    /** Exactly once, when the sheet closes: what `PaymentSheet`'s `onComplete` receives. */
    val onComplete: ((CheckoutResult) -> Unit)? = null,
)

data class ReadyEvent(
    /** Wajub's payment id. */
    val sessionId: String,
    /** `sandbox` or `live`. */
    val environment: String,
    /** What the payer pays, fee included, in major units. */
    val amount: Double,
    val currency: String,
    /** The payment methods shown. */
    val methods: List<String>,
)

data class StateChangeEvent(
    val state: CheckoutState,
    /** `mobile_money`, `card`, `wallet`, or `unknown` before one is chosen. */
    val method: String? = null,
)

data class PaymentStartedEvent(
    val method: String,
    val channel: String,
)

/** A payment provider's page is about to open. */
data class RedirectEvent(
    val url: String,
    val method: String = "GET",
)

enum class CancelReason(val value: String) {
    PayerCancelled("payer_cancelled"),
    Dismissed("dismissed"),
    HostGone("host_gone"),
}

data class CancelEvent(
    val reason: CancelReason,
    /** The merchant's redirect URL, after a cancel. */
    val redirectUrl: String?,
)

/** A paid payment, as the payer saw it. Confirm on your server before delivering. */
data class PaymentSuccess(
    /** Wajub's payment id: what `GET /payments/{id}` takes. */
    val id: String,
    /** The merchant's reference. */
    val reference: String?,
    /** What the payer paid, fee included, in major units. */
    val amount: Double,
    val currency: String,
    /** `mobile_money`, `card`, `wallet`, or `unknown` when it was paid before the sheet opened. */
    val method: String,
    /** When it was paid, ISO 8601. */
    val timestamp: String,
    val receiptUrl: String? = null,
) {
    val status: String get() = "success"

    companion object {
        fun from(transaction: SessionTransaction, method: String, now: Long = System.currentTimeMillis()): PaymentSuccess =
            PaymentSuccess(
                id = transaction.id,
                reference = transaction.reference.takeIf { it.isNotBlank() },
                amount = transaction.payableAmount,
                currency = transaction.currency,
                method = method,
                timestamp = transaction.terminalAt ?: transaction.updatedAt ?: Iso8601.format(now),
                receiptUrl = transaction.receiptUrl,
            )
    }
}

/** What the sheet resolves with, exactly once, when it closes. */
sealed class CheckoutResult {
    /** `complete`, `processing`, `failed` or `cancelled`. */
    abstract val status: String

    /** Closed after a success. */
    data class Complete(val payment: PaymentSuccess, val transaction: SessionTransaction?) : CheckoutResult() {
        override val status: String get() = "complete"
    }

    /** Closed while an attempt runs, or after watching for 15 minutes: money may still move — confirm server-side. */
    data class Processing(val transaction: SessionTransaction?) : CheckoutResult() {
        override val status: String get() = "processing"
    }

    /** Closed on a failure, an expiry, a load error, or a payment cancelled on the phone. */
    data class Failed(val error: WajubError, val transaction: SessionTransaction?) : CheckoutResult() {
        override val status: String get() = "failed"
    }

    /** Cancelled by the payer, dismissed before any attempt, or the host went away first. */
    data class Cancelled(val reason: CancelReason, val redirectUrl: String?) : CheckoutResult() {
        override val status: String get() = "cancelled"
    }
}
