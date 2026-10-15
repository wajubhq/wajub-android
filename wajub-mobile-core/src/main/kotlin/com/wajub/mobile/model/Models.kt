package com.wajub.mobile.model

/**
 * The payment. Amounts are major units (25000 for 25 000 XAF).
 */
data class SessionTransaction(
    /** Wajub's payment id (`trx_…`), as in [com.wajub.mobile.checkout.PaymentSuccess.id]. */
    val id: String,
    /** The merchant's own reference. */
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
    /** The last refusal of an attempt (`insufficient_funds`…) while the payment is back to `pending`. */
    val errorCode: String? = null,
) {
    // False when the answer left `error_code` out (an older API): the refusal
    // left on the payment, if any, is then unknown.
    internal var errorCodeReported: Boolean = true

    /** The earlier constructor, kept for Java and compiled code. */
    constructor(
        id: String,
        reference: String,
        trxref: String? = null,
        amount: Double,
        amountTotal: Double? = null,
        currency: String,
        status: String,
        statusLabel: String? = null,
        sandbox: Boolean = false,
        description: String? = null,
        processingContext: ProcessingContext? = null,
    ) : this(
        id, reference, trxref, amount, amountTotal, currency, status, statusLabel, sandbox, description, processingContext,
        amountCollected = null,
    )

    /** The amount to show and charge: [amountTotal], else [amount]. */
    val payableAmount: Double get() = amountTotal ?: amount

    /**
     * The earlier `copy`, kept for Java and compiled code. It keeps the
     * newer fields; Kotlin code names the arguments it changes instead.
     */
    fun copy(
        id: String,
        reference: String,
        trxref: String?,
        amount: Double,
        amountTotal: Double?,
        currency: String,
        status: String,
        statusLabel: String?,
        sandbox: Boolean,
        description: String?,
        processingContext: ProcessingContext?,
    ): SessionTransaction = copy(id = id, reference = reference, trxref = trxref, amount = amount, amountTotal = amountTotal, currency = currency, status = status, statusLabel = statusLabel, sandbox = sandbox, description = description, processingContext = processingContext, amountCollected = amountCollected)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: SessionTransaction,
            id: String?,
            reference: String?,
            trxref: String?,
            amount: Double,
            amountTotal: Double?,
            currency: String?,
            status: String?,
            statusLabel: String?,
            sandbox: Boolean,
            description: String?,
            processingContext: ProcessingContext?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): SessionTransaction = self.copy(
                id = if (mask and 1 != 0) self.id else id!!,
                reference = if (mask and 2 != 0) self.reference else reference!!,
                trxref = if (mask and 4 != 0) self.trxref else trxref,
                amount = if (mask and 8 != 0) self.amount else amount,
                amountTotal = if (mask and 16 != 0) self.amountTotal else amountTotal,
                currency = if (mask and 32 != 0) self.currency else currency!!,
                status = if (mask and 64 != 0) self.status else status!!,
                statusLabel = if (mask and 128 != 0) self.statusLabel else statusLabel,
                sandbox = if (mask and 256 != 0) self.sandbox else sandbox,
                description = if (mask and 512 != 0) self.description else description,
                processingContext = if (mask and 1024 != 0) self.processingContext else processingContext,
            )
    }
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
) {
    /** The earlier constructor, kept for Java and compiled code. */
    constructor(
        channelSlug: String? = null,
        isMobileMoney: Boolean = false,
        payerInstruction: String? = null,
        actionUrl: String? = null,
    ) : this(channelSlug, isMobileMoney, payerInstruction, actionUrl, isUssd = false)

    /**
     * The earlier `copy`, kept for Java and compiled code. It keeps the
     * newer fields; Kotlin code names the arguments it changes instead.
     */
    fun copy(
        channelSlug: String?,
        isMobileMoney: Boolean,
        payerInstruction: String?,
        actionUrl: String?,
    ): ProcessingContext = copy(channelSlug = channelSlug, isMobileMoney = isMobileMoney, payerInstruction = payerInstruction, actionUrl = actionUrl, isUssd = isUssd)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: ProcessingContext,
            channelSlug: String?,
            isMobileMoney: Boolean,
            payerInstruction: String?,
            actionUrl: String?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): ProcessingContext = self.copy(
                channelSlug = if (mask and 1 != 0) self.channelSlug else channelSlug,
                isMobileMoney = if (mask and 2 != 0) self.isMobileMoney else isMobileMoney,
                payerInstruction = if (mask and 4 != 0) self.payerInstruction else payerInstruction,
                actionUrl = if (mask and 8 != 0) self.actionUrl else actionUrl,
            )
    }
}

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
    /** The logo as a PNG, when there is one. */
    val logoPng: String? = null,
) {
    /** The earlier constructor, kept for Java and compiled code. */
    constructor(
        id: String,
        slug: String,
        name: String,
        nameFr: String? = null,
        type: String,
        logo: String? = null,
        countries: List<String> = emptyList(),
        minAmount: Double? = null,
        maxAmount: Double? = null,
        currency: String,
    ) : this(id, slug, name, nameFr, type, logo, countries, minAmount, maxAmount, currency, operators = emptyList())

    /**
     * The earlier `copy`, kept for Java and compiled code. It keeps the
     * newer fields; Kotlin code names the arguments it changes instead.
     */
    fun copy(
        id: String,
        slug: String,
        name: String,
        nameFr: String?,
        type: String,
        logo: String?,
        countries: List<String>,
        minAmount: Double?,
        maxAmount: Double?,
        currency: String,
    ): SessionChannel = copy(id = id, slug = slug, name = name, nameFr = nameFr, type = type, logo = logo, countries = countries, minAmount = minAmount, maxAmount = maxAmount, currency = currency, operators = operators)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: SessionChannel,
            id: String?,
            slug: String?,
            name: String?,
            nameFr: String?,
            type: String?,
            logo: String?,
            countries: List<String>?,
            minAmount: Double?,
            maxAmount: Double?,
            currency: String?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): SessionChannel = self.copy(
                id = if (mask and 1 != 0) self.id else id!!,
                slug = if (mask and 2 != 0) self.slug else slug!!,
                name = if (mask and 4 != 0) self.name else name!!,
                nameFr = if (mask and 8 != 0) self.nameFr else nameFr,
                type = if (mask and 16 != 0) self.type else type!!,
                logo = if (mask and 32 != 0) self.logo else logo,
                countries = if (mask and 64 != 0) self.countries else countries!!,
                minAmount = if (mask and 128 != 0) self.minAmount else minAmount,
                maxAmount = if (mask and 256 != 0) self.maxAmount else maxAmount,
                currency = if (mask and 512 != 0) self.currency else currency!!,
            )
    }
}

/** One member of an aggregate channel. */
data class ChannelOperator(
    /** The operator's own channel, e.g. `cm.orange`. */
    val slug: String,
    /** `mtn`, `orange`. */
    val operator: String,
    val name: String,
    val logo: String? = null,
    /** The logo as a PNG, when there is one. */
    val logoPng: String? = null,
)

data class EchoConfig(
    /** Empty when there is none. */
    val key: String,
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
) {
    /** The earlier constructor, kept for Java and compiled code. */
    @Suppress("DEPRECATION")
    constructor(
        transaction: SessionTransaction,
        channels: List<SessionChannel>,
        locale: String = "en",
        branding: Map<String, String?> = emptyMap(),
        echo: EchoConfig? = null,
        callback: String? = null,
    ) : this(transaction, channels, locale, branding, echo, callback, sessionLocale = null)

    /**
     * The earlier `copy`, kept for Java and compiled code. It keeps the
     * newer fields; Kotlin code names the arguments it changes instead.
     */
    fun copy(
        transaction: SessionTransaction,
        channels: List<SessionChannel>,
        locale: String,
        branding: Map<String, String?>,
        echo: EchoConfig?,
        callback: String?,
    ): SessionData = copy(transaction = transaction, channels = channels, locale = locale, branding = branding, echo = echo, callback = callback, sessionLocale = sessionLocale)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: SessionData,
            transaction: SessionTransaction?,
            channels: List<SessionChannel>?,
            locale: String?,
            branding: Map<String, String?>?,
            echo: EchoConfig?,
            callback: String?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): SessionData = self.copy(
                transaction = if (mask and 1 != 0) self.transaction else transaction!!,
                channels = if (mask and 2 != 0) self.channels else channels!!,
                locale = if (mask and 4 != 0) self.locale else locale!!,
                branding = if (mask and 8 != 0) self.branding else branding!!,
                echo = if (mask and 16 != 0) self.echo else echo,
                callback = if (mask and 32 != 0) self.callback else callback,
            )
    }
}

/**
 * The merchant's branding from the Dashboard. Every value may be missing;
 * colours are as the Dashboard stored them (`#0F766E`).
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
    /** The merchant is white-labelled: the sheet hides "Secured by Wajub". */
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
    /** A font weight: `600`, `400`. */
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
 * How one payment method is paid: whether it is [available], the fields it
 * asks for, and, for a card in Stripe's form, its [publishableKey].
 */
data class SdkChannelConfig(
    val available: Boolean,
    val provider: String? = null,
    val sdk: String? = null,
    val publishableKey: String? = null,
    val requiredFields: List<String> = emptyList(),
    /** The card is paid on the payment provider's own page. */
    val clientSession: Boolean = false,
)

/**
 * A card payment on the payment provider's page, from
 * [com.wajub.mobile.WajubSession.openClientSession]: open [hostedUrl] in Custom
 * Tabs, then call [com.wajub.mobile.WajubSession.confirmClientSession] with
 * [id] when the payer returns.
 */
data class ClientSession(
    val id: String,
    val provider: String,
    /** The provider's reference. */
    val reference: String? = null,
    /** In the provider's own units. */
    val amount: Double? = null,
    val currency: String? = null,
    val hostedUrl: String? = null,
    val accessCode: String? = null,
    val publicKey: String? = null,
    val encryptionKey: String? = null,
)

/** How each payment method of a payment is paid, by channel. */
data class SdkConfig(
    val channels: Map<String, SdkChannelConfig>,
)

data class MobileMoneyInput(
    val channelSlug: String,
    /** Sent as given. */
    val phone: String,
    val country: String,
    /** Aggregate channels only (`cm.mobile`): the operator the payer picked (`mtn`). */
    val operator: String? = null,
) {
    /** The earlier constructor, kept for Java and compiled code. */
    constructor(channelSlug: String, phone: String, country: String) : this(channelSlug, phone, country, operator = null)

    /**
     * The earlier `copy`, kept for Java and compiled code. It keeps the
     * newer fields; Kotlin code names the arguments it changes instead.
     */
    fun copy(
        channelSlug: String,
        phone: String,
        country: String,
    ): MobileMoneyInput = copy(channelSlug = channelSlug, phone = phone, country = country, operator = operator)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: MobileMoneyInput,
            channelSlug: String?,
            phone: String?,
            country: String?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): MobileMoneyInput = self.copy(
                channelSlug = if (mask and 1 != 0) self.channelSlug else channelSlug!!,
                phone = if (mask and 2 != 0) self.phone else phone!!,
                country = if (mask and 4 != 0) self.country else country!!,
            )
    }
}

/** The payment's status. */
data class PaymentStatus(
    /** Wajub's payment id — not the merchant's reference. */
    val reference: String?,
    val status: String,
    /** Set when an attempt was refused and the payment is back to `pending`. */
    val errorCode: String? = null,
    /** Why, in English. */
    val payerMessage: String? = null,
)

/**
 * The answer to a payment, as the deprecated calls (`payMobileMoney`,
 * `payCard`…) report it. `WajubSession.process(ProcessRequest)` answers with a
 * [ProcessOutcome].
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
