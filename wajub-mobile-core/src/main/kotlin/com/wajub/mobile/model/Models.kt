package com.wajub.mobile.model

data class SessionTransaction(
    val id: String,
    val reference: String,
    val trxref: String? = null,
    val amount: Double,
    val amountTotal: Double? = null,
    val currency: String,
    val status: String,
    val statusLabel: String? = null,
    val sandbox: Boolean = false,
    val description: String? = null,
    val processingContext: ProcessingContext? = null,
)

data class ProcessingContext(
    val channelSlug: String? = null,
    val isMobileMoney: Boolean = false,
    val payerInstruction: String? = null,
    val actionUrl: String? = null,
)

data class SessionChannel(
    val id: String,
    val slug: String,
    val name: String,
    val nameFr: String? = null,
    val type: String,
    val logo: String? = null,
    val countries: List<String> = emptyList(),
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val currency: String,
)

data class EchoConfig(
    val key: String,
    val wsHost: String,
    val wsPort: Int,
    val channelPrefix: String,
    val broadcaster: String,
)

data class SessionData(
    val transaction: SessionTransaction,
    val channels: List<SessionChannel>,
    val locale: String = "en",
    val branding: Map<String, String?> = emptyMap(),
    val echo: EchoConfig? = null,
    val callback: String? = null,
)

/**
 * `sdk` flavors: `form`, `stripe_elements`, `hosted_redirect`, `adyen_custom_card`
 * (not supported natively — needs Adyen's client-side encryption).
 * `provider` is only a PREDICTION of who takes the channel; the pinned,
 * authoritative provider is [ClientSession.provider].
 */
data class SdkChannelConfig(
    val available: Boolean,
    val provider: String? = null,
    val sdk: String? = null,
    val publishableKey: String? = null,
    val requiredFields: List<String> = emptyList(),
    /** JSON `client_session` — POST /pay/client-session can hand this channel to the PSP's own hosted checkout. */
    val clientSession: Boolean = false,
)

/**
 * PSP-hosted checkout session (JSON `client_session` of POST /pay/client-session).
 *
 * Default flow: open [hostedUrl] in Custom Tabs, then call
 * [com.wajub.mobile.WajubSession.completeClientSession] with [id] when the payer returns.
 * Apps may instead launch the PSP's native SDK themselves — Paystack
 * ([publicKey] + [accessCode]) or Flutterwave Android ([publicKey] +
 * [encryptionKey], [reference] as tx_ref, [amount], [currency]) — and call
 * completeClientSession the same way.
 */
data class ClientSession(
    val id: String,
    val provider: String,
    /** PSP-side reference (Paystack `reference`, Flutterwave `tx_ref`). */
    val reference: String? = null,
    /** In the PSP's own units (Paystack: subunit; Flutterwave: major). */
    val amount: Double? = null,
    val currency: String? = null,
    val hostedUrl: String? = null,
    val accessCode: String? = null,
    val publicKey: String? = null,
    val encryptionKey: String? = null,
)

data class SdkConfig(
    val channels: Map<String, SdkChannelConfig>,
)

data class MobileMoneyInput(
    val channelSlug: String,
    val phone: String,
    val country: String,
)

sealed class PaymentResult {
    data class Complete(val transaction: SessionTransaction) : PaymentResult()

    data class Processing(
        val transaction: SessionTransaction,
        val instruction: String? = null,
    ) : PaymentResult()

    data class RequiresAction(
        val action: ActionKind,
        val actionUrl: String? = null,
        val transaction: SessionTransaction,
        /** Set when [action] is [ActionKind.ClientSession]; [actionUrl] is then its hosted URL. */
        val clientSession: ClientSession? = null,
    ) : PaymentResult()

    data class Failed(
        val error: WajubError,
        val transaction: SessionTransaction? = null,
    ) : PaymentResult()
}

enum class ActionKind {
    Redirect,
    Confirm,
    Confirm3ds,
    PushApproval,
    ClientSession,
}

/** Error code for a response carrying an `action` the SDK can't perform natively. */
const val UNSUPPORTED_ACTION_CODE: String = "unsupported_action"

data class CancelResult(
    val redirectUrl: String? = null,
)
