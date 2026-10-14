package com.wajub.mobile.model

/**
 * The payment, as `GET /pay/session` and the `/pay/process` answers give it.
 * Amounts are major units (25000 for 25 000 XAF).
 */
data class SessionTransaction(
    /** Wajub's payment id (`trx_…`): the realtime channel and [com.wajub.mobile.checkout.PaymentSuccess.id]. */
    val id: String,
    /** The merchant's own reference. Not the `reference` of `GET /pay/status`, which is [id]. */
    val reference: String,
    val trxref: String? = null,
    val amount: Double,
    /** What the payer pays: [amount] plus the fee when the payer bears it. */
    val amountTotal: Double? = null,
    val currency: String,
    val status: String,
    val statusLabel: String? = null,
    val sandbox: Boolean = false,
    val description: String? = null,
    /** Set while the payment is `processing` or `partial`. */
    val processingContext: ProcessingContext? = null,
    val amountCollected: Double? = null,
    val amountPaid: Double? = null,
    val remainingAmount: Double? = null,
    /** `merchant` or `customer`: who bears the fee. */
    val bearer: String? = null,
    val fee: TransactionFee? = null,
    val customer: Customer? = null,
    val receiptUrl: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val terminalAt: String? = null,
) {
    /** The amount to show and charge: [amountTotal], else [amount]. */
    val payableAmount: Double get() = amountTotal ?: amount
}

data class TransactionFee(
    val amount: Double,
    /** A decimal: 0.035 for 3.5 %. */
    val rate: Double? = null,
    val description: String? = null,
)

data class Customer(
    val name: String? = null,
    val email: String? = null,
)

/** Where an attempt in flight stands; present only while `processing` or `partial`. */
data class ProcessingContext(
    val channelSlug: String? = null,
    val isMobileMoney: Boolean = false,
    /** English; may carry a USSD fallback ("dial *126#"). */
    val payerInstruction: String? = null,
    /** A provider page to reopen. */
    val actionUrl: String? = null,
    val isUssd: Boolean = false,
    val isBankTransfer: Boolean = false,
    /** Sandbox 3-D Secure email challenge. */
    val threeDsId: String? = null,
    val threeDsMethod: String? = null,
    val maskedEmail: String? = null,
    /** Seconds. */
    val threeDsTimeout: Int? = null,
)

data class SessionChannel(
    val id: String,
    val slug: String,
    /** In the session's locale. */
    val name: String,
    /** Always French (the API's literal `name.fr` key). */
    val nameFr: String? = null,
    val type: String,
    val logo: String? = null,
    val countries: List<String> = emptyList(),
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val currency: String,
    /** The operators an aggregate channel (`cm.mobile`) folds; empty otherwise. */
    val operators: List<ChannelOperator> = emptyList(),
)

/** One member of an aggregate channel. */
data class ChannelOperator(
    /** The operator's own channel, e.g. `cm.orange`. */
    val slug: String,
    /** `mtn`, `orange`. */
    val operator: String,
    val name: String,
    val logo: String? = null,
)

data class EchoConfig(
    /** Empty when the API has no broadcasting key: polling only. */
    val key: String,
    /** With its scheme: `wss://ws.wajub.com` (TLS) or `ws://…` (plain). */
    val wsHost: String,
    val wsPort: Int,
    val channelPrefix: String,
    val broadcaster: String,
)

data class SessionData(
    val transaction: SessionTransaction,
    val channels: List<SessionChannel>,
    /** The session's language, `en` when it has none — see [sessionLocale]. */
    val locale: String = "en",
    @Deprecated("Every value is text here; read the typed brand instead.", ReplaceWith("brand"))
    val branding: Map<String, String?> = emptyMap(),
    val echo: EchoConfig? = null,
    val callback: String? = null,
    /** The session's own language (`fr`, `en`), or null when it has none. */
    val sessionLocale: String? = null,
    /** The merchant's look, every key optional. */
    val brand: Branding = Branding(),
    /** A proof-of-work challenge to solve before paying, when the API asks for one. */
    val altcha: AltchaChallenge? = null,
    /** The payer's country as the API sees it (ISO-2), when known. */
    val geoCountry: String? = null,
    /** The merchant's callback with `status=succeeded`. Informational. */
    val returnUrl: String? = null,
    /** The merchant's callback with `status=cancelled`. Informational. */
    val cancelUrl: String? = null,
    val savedMethods: List<SavedMethod> = emptyList(),
    val features: Map<String, Boolean> = emptyMap(),
)

/**
 * The merchant's branding (`branding` of `GET /pay/session`). Every key may be
 * missing; colours are as the dashboard stored them (`#0F766E`).
 */
data class Branding(
    val primaryColor: String? = null,
    val secondaryColor: String? = null,
    val backgroundColor: String? = null,
    val logoUrl: String? = null,
    val logoSquareUrl: String? = null,
    val iconUrl: String? = null,
    val businessName: String? = null,
    /** `"12px"`, `"12"`, or a token: `none`, `sm`, `md`, `lg`, `xl`. */
    val borderRadius: String? = null,
    val font: String? = null,
    /** `light`, `dark`, `system`, or null. */
    val appearanceMode: String? = null,
    val density: String? = null,
    /** Hides "Secured by Wajub". The API sends a boolean, the web also an object. */
    val whiteLabel: Boolean = false,
    val inputBackgroundColor: String? = null,
    val inputBorderColor: String? = null,
    val textMutedColor: String? = null,
    val buttonTextColor: String? = null,
    val successColor: String? = null,
    val errorColor: String? = null,
    val shadow: String? = null,
    /** 0–100. */
    val overlayIntensity: Int? = null,
    /** Given as an integer or a string by the API (`600`, `"400"`). */
    val fontWeightHeading: String? = null,
    val fontWeightBody: String? = null,
    val separatorColor: String? = null,
) {
    /** The header logo: [logoUrl], else [logoSquareUrl], else [iconUrl]. */
    val headerLogo: String? get() = logoUrl ?: logoSquareUrl ?: iconUrl
}

data class AltchaChallenge(
    val algorithm: String,
    val challenge: String,
    val salt: String,
    val signature: String,
    val maxNumber: Long,
)

/** A payment method the customer saved (`saved_methods`). */
data class SavedMethod(
    val id: String,
    /** `card` or `mobile_money`. */
    val type: String,
    val isDefault: Boolean = false,
    val label: String? = null,
    val provider: String? = null,
    val brand: String? = null,
    val last4: String? = null,
    /** Lower-case ISO-2 for Mobile Money. */
    val phoneCountry: String? = null,
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

/** `GET /pay/sdk-config`: keyed by raw channel slug (`cm.mtn`, never `cm.mobile`). */
data class SdkConfig(
    val channels: Map<String, SdkChannelConfig>,
)

data class MobileMoneyInput(
    val channelSlug: String,
    /** As typed, or E.164: sent as E.164 when [country] is a known Mobile Money country. */
    val phone: String,
    val country: String,
    /** Aggregate channels only (`cm.mobile`): the operator the payer picked (`mtn`). */
    val operator: String? = null,
)

/** `GET /pay/status`, and the data of the realtime `PaymentStatusUpdated` event. */
data class PaymentStatus(
    /** Wajub's payment id — not the merchant's reference. */
    val reference: String?,
    val status: String,
    /** Set when an attempt was refused and the payment is back to `pending`. */
    val errorCode: String? = null,
    /** English. */
    val payerMessage: String? = null,
)

/**
 * One `/pay/process` answer, as the 1.x methods (`payMobileMoney`, `payCard`…)
 * report it. [ProcessOutcome] is the v2 mapping, from
 * `WajubSession.process(ProcessRequest)`.
 */
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
