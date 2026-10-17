package com.wajub.mobile.mapper

import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.AltchaChallenge
import com.wajub.mobile.model.Branding
import com.wajub.mobile.model.ChannelOperator
import com.wajub.mobile.model.ClientSession
import com.wajub.mobile.model.Customer
import com.wajub.mobile.model.EchoConfig
import com.wajub.mobile.model.MobileMoneyInput
import com.wajub.mobile.model.PayerOtpInstructions
import com.wajub.mobile.model.PayerOtpStep
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.ProcessingContext
import com.wajub.mobile.model.SavedMethod
import com.wajub.mobile.model.SdkChannelConfig
import com.wajub.mobile.model.SessionChannel
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.TransactionFee
import com.wajub.mobile.model.UNSUPPORTED_ACTION_CODE
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubErrorType
import org.json.JSONException
import org.json.JSONObject

// Wire models of the API's answers. Every key is optional except
// `transaction.amount`; numbers and booleans are read in every encoding the
// API has used (spec PR-01…PR-08).

data class RawTransaction(
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
    val processingContext: RawProcessingContext? = null,
    val amountCollected: Double? = null,
    val amountPaid: Double? = null,
    val remainingAmount: Double? = null,
    val bearer: String? = null,
    val fee: TransactionFee? = null,
    val customer: Customer? = null,
    val receiptUrl: String? = null,
    val expiresAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val terminalAt: String? = null,
    val errorCode: String? = null,
) {
    // False when the answer left `error_code` out (an older API).
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
        processingContext: RawProcessingContext? = null,
    ) : this(
        id, reference, trxref, amount, amountTotal, currency, status, statusLabel, sandbox, description, processingContext,
        amountCollected = null,
    )

    fun toModel(): SessionTransaction = PaymentMapper.mapTransaction(this)

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
        processingContext: RawProcessingContext?,
    ): RawTransaction = copy(id = id, reference = reference, trxref = trxref, amount = amount, amountTotal = amountTotal, currency = currency, status = status, statusLabel = statusLabel, sandbox = sandbox, description = description, processingContext = processingContext, amountCollected = amountCollected)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: RawTransaction,
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
            processingContext: RawProcessingContext?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): RawTransaction = self.copy(
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

        fun fromJson(obj: JSONObject): RawTransaction {
            val id = obj.str("id") ?: obj.str("reference").orEmpty()
            return RawTransaction(
                id = id,
                reference = obj.str("reference") ?: id,
                trxref = obj.str("trxref"),
                amount = obj.num("amount") ?: throw JSONException("transaction.amount is not a number"),
                amountTotal = obj.num("amount_total"),
                currency = obj.str("currency").orEmpty(),
                status = obj.str("status").orEmpty(),
                statusLabel = obj.str("status_label"),
                sandbox = obj.bool("sandbox") ?: false,
                description = obj.str("description"),
                processingContext = obj.obj("processing_context")?.let { RawProcessingContext.fromJson(it) },
                amountCollected = obj.num("amount_collected"),
                amountPaid = obj.num("amount_paid"),
                remainingAmount = obj.num("remaining_amount"),
                bearer = obj.str("bearer"),
                fee = obj.obj("fee")?.let { fee ->
                    fee.num("amount")?.let { TransactionFee(it, fee.num("rate"), fee.str("description")) }
                },
                customer = obj.obj("customer")?.let { Customer(it.str("name"), it.str("email")) },
                receiptUrl = obj.str("receipt_url"),
                expiresAt = obj.str("expires_at"),
                createdAt = obj.str("created_at"),
                updatedAt = obj.str("updated_at"),
                terminalAt = obj.str("terminal_at"),
                errorCode = obj.str("error_code"),
            ).also { it.errorCodeReported = obj.has("error_code") }
        }
    }
}

data class RawProcessingContext(
    val channelSlug: String? = null,
    val isMobileMoney: Boolean = false,
    val payerInstruction: String? = null,
    val actionUrl: String? = null,
    val isUssd: Boolean = false,
    val isBankTransfer: Boolean = false,
    val threeDsId: String? = null,
    val threeDsMethod: String? = null,
    val maskedEmail: String? = null,
    val threeDsTimeout: Int? = null,
) {
    /** The earlier constructor, kept for Java and compiled code. */
    constructor(
        channelSlug: String? = null,
        isMobileMoney: Boolean = false,
        payerInstruction: String? = null,
        actionUrl: String? = null,
    ) : this(channelSlug, isMobileMoney, payerInstruction, actionUrl, isUssd = false)

    fun toModel(): ProcessingContext =
        ProcessingContext(
            channelSlug, isMobileMoney, payerInstruction, actionUrl, isUssd, isBankTransfer,
            threeDsId, threeDsMethod, maskedEmail, threeDsTimeout,
        )

    /**
     * The earlier `copy`, kept for Java and compiled code. It keeps the
     * newer fields; Kotlin code names the arguments it changes instead.
     */
    fun copy(
        channelSlug: String?,
        isMobileMoney: Boolean,
        payerInstruction: String?,
        actionUrl: String?,
    ): RawProcessingContext = copy(channelSlug = channelSlug, isMobileMoney = isMobileMoney, payerInstruction = payerInstruction, actionUrl = actionUrl, isUssd = isUssd)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: RawProcessingContext,
            channelSlug: String?,
            isMobileMoney: Boolean,
            payerInstruction: String?,
            actionUrl: String?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): RawProcessingContext = self.copy(
                channelSlug = if (mask and 1 != 0) self.channelSlug else channelSlug,
                isMobileMoney = if (mask and 2 != 0) self.isMobileMoney else isMobileMoney,
                payerInstruction = if (mask and 4 != 0) self.payerInstruction else payerInstruction,
                actionUrl = if (mask and 8 != 0) self.actionUrl else actionUrl,
            )

        fun fromJson(obj: JSONObject): RawProcessingContext =
            RawProcessingContext(
                channelSlug = obj.str("channel_slug"),
                isMobileMoney = obj.bool("is_mobile_money") ?: false,
                payerInstruction = obj.str("payer_instruction"),
                actionUrl = obj.str("action_url"),
                isUssd = obj.bool("is_ussd") ?: false,
                isBankTransfer = obj.bool("is_bank_transfer") ?: false,
                threeDsId = obj.str("three_ds_id"),
                threeDsMethod = obj.str("three_ds_method"),
                maskedEmail = obj.str("masked_email"),
                threeDsTimeout = obj.int("three_ds_timeout"),
            )
    }
}

data class RawChannel(
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
    val operators: List<ChannelOperator> = emptyList(),
    val logoPng: String? = null,
    val feeAmount: Double? = null,
    val amountTotal: Double? = null,
    val payerOtp: PayerOtpInstructions? = null,
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

    fun toModel(): SessionChannel =
        SessionChannel(id, slug, name, nameFr, type, logo, countries, minAmount, maxAmount, currency, operators, logoPng, feeAmount, amountTotal, payerOtp)

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
    ): RawChannel = copy(id = id, slug = slug, name = name, nameFr = nameFr, type = type, logo = logo, countries = countries, minAmount = minAmount, maxAmount = maxAmount, currency = currency, operators = operators)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: RawChannel,
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
        ): RawChannel = self.copy(
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

        fun fromJson(obj: JSONObject): RawChannel {
            val slug = obj.str("slug") ?: obj.str("id").orEmpty()
            return RawChannel(
                id = obj.str("id") ?: slug,
                slug = slug,
                name = obj.str("name") ?: slug,
                // A literal key with a dot, not a path.
                nameFr = obj.str("name.fr"),
                type = obj.str("type").orEmpty(),
                logo = obj.str("logo"),
                countries = obj.strings("countries").map { it.uppercase() },
                minAmount = obj.num("min_amount"),
                maxAmount = obj.num("max_amount"),
                currency = obj.str("currency").orEmpty(),
                operators = obj.objects("operators").mapNotNull { op ->
                    val operator = op.str("operator") ?: return@mapNotNull null
                    ChannelOperator(
                        slug = op.str("slug").orEmpty(),
                        operator = operator.lowercase(),
                        name = op.str("name") ?: operator,
                        logo = op.str("logo"),
                        logoPng = op.str("logo_png"),
                        feeAmount = op.num("fee_amount"),
                        amountTotal = op.num("amount_total"),
                    )
                },
                logoPng = obj.str("logo_png"),
                feeAmount = obj.num("fee_amount"),
                amountTotal = obj.num("amount_total"),
                payerOtp = payerOtp(obj.obj("payer_otp")),
            )
        }
    }
}

data class RawEchoConfig(
    val key: String,
    val wsHost: String,
    val wsPort: Int,
    val channelPrefix: String,
    val broadcaster: String,
) {
    fun toModel(): EchoConfig = EchoConfig(key, wsHost, wsPort, channelPrefix, broadcaster)

    companion object {
        fun fromJson(obj: JSONObject): RawEchoConfig {
            val host = obj.str("ws_host").orEmpty()
            return RawEchoConfig(
                key = obj.str("key").orEmpty(),
                wsHost = host,
                wsPort = obj.int("ws_port") ?: if (host.startsWith("ws://")) 80 else 443,
                channelPrefix = obj.str("channel_prefix") ?: "private-payment.",
                broadcaster = obj.str("broadcaster") ?: "pusher",
            )
        }
    }
}

data class RawSessionResponse(
    val transaction: RawTransaction,
    val channels: List<RawChannel>,
    val locale: String? = null,
    val branding: Map<String, String?> = emptyMap(),
    val echo: RawEchoConfig? = null,
    val callback: String? = null,
    val brand: Branding = Branding(),
    val altcha: AltchaChallenge? = null,
    val geoCountry: String? = null,
    val returnUrl: String? = null,
    val cancelUrl: String? = null,
    val savedMethods: List<SavedMethod> = emptyList(),
    val features: Map<String, Boolean> = emptyMap(),
) {
    /** The earlier constructor, kept for Java and compiled code. */
    constructor(
        transaction: RawTransaction,
        channels: List<RawChannel>,
        locale: String? = null,
        branding: Map<String, String?> = emptyMap(),
        echo: RawEchoConfig? = null,
        callback: String? = null,
    ) : this(transaction, channels, locale, branding, echo, callback, brand = Branding())

    @Suppress("DEPRECATION")
    fun toModel(): SessionData =
        SessionData(
            transaction = transaction.toModel(),
            channels = channels.map { it.toModel() },
            locale = locale ?: "en",
            branding = branding,
            echo = echo?.toModel(),
            callback = callback,
            sessionLocale = locale,
            brand = brand,
            altcha = altcha,
            geoCountry = geoCountry,
            returnUrl = returnUrl,
            cancelUrl = cancelUrl,
            savedMethods = savedMethods,
            features = features,
        )

    /**
     * The earlier `copy`, kept for Java and compiled code. It keeps the
     * newer fields; Kotlin code names the arguments it changes instead.
     */
    fun copy(
        transaction: RawTransaction,
        channels: List<RawChannel>,
        locale: String?,
        branding: Map<String, String?>,
        echo: RawEchoConfig?,
        callback: String?,
    ): RawSessionResponse = copy(transaction = transaction, channels = channels, locale = locale, branding = branding, echo = echo, callback = callback, brand = brand)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: RawSessionResponse,
            transaction: RawTransaction?,
            channels: List<RawChannel>?,
            locale: String?,
            branding: Map<String, String?>?,
            echo: RawEchoConfig?,
            callback: String?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): RawSessionResponse = self.copy(
                transaction = if (mask and 1 != 0) self.transaction else transaction!!,
                channels = if (mask and 2 != 0) self.channels else channels!!,
                locale = if (mask and 4 != 0) self.locale else locale,
                branding = if (mask and 8 != 0) self.branding else branding!!,
                echo = if (mask and 16 != 0) self.echo else echo,
                callback = if (mask and 32 != 0) self.callback else callback,
            )

        fun fromJson(body: JSONObject): RawSessionResponse {
            val txn = body.obj("transaction") ?: throw JSONException("session without a transaction")
            val brandingJson = body.obj("branding") ?: JSONObject()
            val branding = linkedMapOf<String, String?>()
            brandingJson.keys().forEach { key -> branding[key] = brandingJson.str(key) }
            val features = linkedMapOf<String, Boolean>()
            body.obj("features")?.let { f -> f.keys().forEach { key -> f.bool(key)?.let { features[key] = it } } }
            return RawSessionResponse(
                transaction = RawTransaction.fromJson(txn),
                channels = body.objects("channels").map { RawChannel.fromJson(it) },
                locale = body.str("locale")?.lowercase(),
                branding = branding,
                echo = body.obj("echo")?.let { RawEchoConfig.fromJson(it) },
                callback = body.str("callback"),
                brand = parseBranding(brandingJson),
                altcha = body.obj("altcha")?.let { a ->
                    val challenge = a.str("challenge") ?: return@let null
                    AltchaChallenge(
                        algorithm = a.str("algorithm") ?: "SHA-256",
                        challenge = challenge,
                        salt = a.str("salt").orEmpty(),
                        signature = a.str("signature").orEmpty(),
                        maxNumber = a.num("maxnumber")?.toLong() ?: 1_000_000L,
                    )
                },
                geoCountry = body.str("geo_country")?.uppercase(),
                returnUrl = body.str("return_url"),
                cancelUrl = body.str("cancel_url"),
                savedMethods = body.objects("saved_methods").mapNotNull { m ->
                    val id = m.str("id") ?: return@mapNotNull null
                    SavedMethod(
                        id = id,
                        type = m.str("type").orEmpty(),
                        isDefault = m.bool("is_default") ?: false,
                        label = m.str("label"),
                        provider = m.str("provider"),
                        brand = m.str("brand"),
                        last4 = m.str("last4"),
                        phoneCountry = m.str("phone_country"),
                    )
                },
                features = features,
            )
        }

        private fun parseBranding(b: JSONObject): Branding {
            // `white_label` is a boolean from the API; the web also accepts
            // {enabled, brand_name, …}.
            val whiteLabel = b.bool("white_label") ?: b.obj("white_label")?.bool("enabled") ?: false
            return Branding(
                primaryColor = b.str("primary_color"),
                secondaryColor = b.str("secondary_color"),
                backgroundColor = b.str("background_color"),
                logoUrl = b.str("logo_url"),
                logoSquareUrl = b.str("logo_square_url"),
                iconUrl = b.str("icon_url"),
                businessName = b.str("business_name"),
                borderRadius = b.str("border_radius"),
                font = b.str("font"),
                appearanceMode = b.str("appearance_mode")?.lowercase(),
                density = b.str("density")?.lowercase(),
                whiteLabel = whiteLabel,
                inputBackgroundColor = b.str("input_background_color"),
                inputBorderColor = b.str("input_border_color"),
                textMutedColor = b.str("text_muted_color"),
                buttonTextColor = b.str("button_text_color"),
                successColor = b.str("success_color"),
                errorColor = b.str("error_color"),
                shadow = b.str("shadow")?.lowercase(),
                overlayIntensity = b.int("overlay_intensity"),
                fontWeightHeading = b.str("font_weight_heading"),
                fontWeightBody = b.str("font_weight_body"),
                separatorColor = b.str("separator_color"),
            )
        }
    }
}

data class RawClientSession(
    val id: String,
    val provider: String,
    val reference: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val hostedUrl: String? = null,
    val accessCode: String? = null,
    val publicKey: String? = null,
    val encryptionKey: String? = null,
) {
    fun toModel(): ClientSession =
        ClientSession(id, provider, reference, amount, currency, hostedUrl, accessCode, publicKey, encryptionKey)

    companion object {
        fun fromJson(obj: JSONObject): RawClientSession =
            RawClientSession(
                id = obj.str("id").orEmpty(),
                provider = obj.str("provider").orEmpty(),
                reference = obj.str("reference"),
                amount = obj.num("amount"),
                currency = obj.str("currency"),
                hostedUrl = obj.str("hosted_url"),
                accessCode = obj.str("access_code"),
                publicKey = obj.str("public_key"),
                encryptionKey = obj.str("encryption_key"),
            )
    }
}

/** The answer to a payment request. */
data class RawProcessResponse(
    val code: Int,
    val status: String,
    val message: String,
    val action: String? = null,
    val transaction: RawTransaction? = null,
    val confirmUrl: String? = null,
    val simulatorUrl: String? = null,
    val clientSession: RawClientSession? = null,
    val threeDsId: String? = null,
    val threeDsMethod: String? = null,
    val maskedEmail: String? = null,
    val threeDsTimeout: Int? = null,
    val otpId: String? = null,
    val otpMaskedContact: String? = null,
    val pinId: String? = null,
    val pinInstruction: String? = null,
    val phoneId: String? = null,
    val phoneInstruction: String? = null,
    val birthdayId: String? = null,
    val birthdayInstruction: String? = null,
    val addressId: String? = null,
    val addressInstruction: String? = null,
    /** With `action: payer_otp`: how to get the code the operator asks for. */
    val payerOtp: PayerOtpInstructions? = null,
) {
    /** The earlier constructor, kept for Java and compiled code. */
    constructor(
        code: Int,
        status: String,
        message: String,
        action: String? = null,
        transaction: RawTransaction? = null,
        confirmUrl: String? = null,
        simulatorUrl: String? = null,
        clientSession: RawClientSession? = null,
    ) : this(code, status, message, action, transaction, confirmUrl, simulatorUrl, clientSession, threeDsId = null)

    /**
     * The earlier `copy`, kept for Java and compiled code. It keeps the
     * newer fields; Kotlin code names the arguments it changes instead.
     */
    fun copy(
        code: Int,
        status: String,
        message: String,
        action: String?,
        transaction: RawTransaction?,
        confirmUrl: String?,
        simulatorUrl: String?,
        clientSession: RawClientSession?,
    ): RawProcessResponse = copy(code = code, status = status, message = message, action = action, transaction = transaction, confirmUrl = confirmUrl, simulatorUrl = simulatorUrl, clientSession = clientSession, threeDsId = threeDsId)

    companion object {
        // What code compiled against 1.2 calls for `copy(name = …)`: the 1.2 `copy$default`.
        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("Binary compatibility with 1.2.", level = DeprecationLevel.HIDDEN)
        fun copy12(
            self: RawProcessResponse,
            code: Int,
            status: String?,
            message: String?,
            action: String?,
            transaction: RawTransaction?,
            confirmUrl: String?,
            simulatorUrl: String?,
            clientSession: RawClientSession?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): RawProcessResponse = self.copy(
                code = if (mask and 1 != 0) self.code else code,
                status = if (mask and 2 != 0) self.status else status!!,
                message = if (mask and 4 != 0) self.message else message!!,
                action = if (mask and 8 != 0) self.action else action,
                transaction = if (mask and 16 != 0) self.transaction else transaction,
                confirmUrl = if (mask and 32 != 0) self.confirmUrl else confirmUrl,
                simulatorUrl = if (mask and 64 != 0) self.simulatorUrl else simulatorUrl,
                clientSession = if (mask and 128 != 0) self.clientSession else clientSession,
            )

        fun fromJson(body: JSONObject): RawProcessResponse =
            RawProcessResponse(
                code = body.int("code") ?: 0,
                status = body.str("status").orEmpty(),
                message = body.str("message").orEmpty(),
                action = body.str("action"),
                transaction = body.obj("transaction")?.let { RawTransaction.fromJson(it) },
                confirmUrl = body.str("confirm_url"),
                simulatorUrl = body.str("simulator_url"),
                clientSession = body.obj("client_session")
                    ?.let { RawClientSession.fromJson(it) }
                    ?.takeIf { it.id.isNotBlank() },
                threeDsId = body.str("three_ds_id"),
                threeDsMethod = body.str("three_ds_method"),
                maskedEmail = body.str("masked_email"),
                threeDsTimeout = body.int("three_ds_timeout"),
                otpId = body.str("otp_id"),
                otpMaskedContact = body.str("otp_masked_contact"),
                pinId = body.str("pin_id"),
                pinInstruction = body.str("pin_instruction"),
                phoneId = body.str("phone_id"),
                phoneInstruction = body.str("phone_instruction"),
                birthdayId = body.str("birthday_id"),
                birthdayInstruction = body.str("birthday_instruction"),
                addressId = body.str("address_id"),
                addressInstruction = body.str("address_instruction"),
                payerOtp = payerOtp(body.obj("payer_otp")),
            )
    }
}

/** A `payer_otp` object: how the payer gets the code their operator asks for. */
internal fun payerOtp(obj: JSONObject?): PayerOtpInstructions? {
    if (obj == null) return null
    val steps = obj.obj("steps")?.let { all ->
        all.keys().asSequence().associate { language ->
            val list = all.arr(language)
            language.lowercase() to (0 until (list?.length() ?: 0)).mapNotNull { i ->
                when (val step = list!!.opt(i)) {
                    // A step given as a plain string is its text.
                    is String -> step.takeIf { it.isNotBlank() }?.let { PayerOtpStep(it) }
                    is JSONObject -> step.str("text")?.let { PayerOtpStep(it, step.str("template")) }
                    else -> null
                }
            }
        }
    }.orEmpty()
    return PayerOtpInstructions(
        channel = obj.str("channel").orEmpty(),
        ussdCode = obj.str("ussd_code"),
        // Outside 1 to 12: not a length to ask for.
        otpLength = obj.int("otp_length")?.takeIf { it in PayerOtpInstructions.LENGTHS },
        // Missing: shown as nothing.
        amount = obj.str("amount").orEmpty(),
        currency = obj.str("currency").orEmpty(),
        steps = steps,
        reason = obj.str("reason"),
        // A negative count is none left.
        attemptsRemaining = obj.int("attempts_remaining")?.coerceAtLeast(0),
    )
}

object PaymentMapper {
    private val successStatuses = setOf("success", "successful", "succeeded", "paid", "complete")

    /** A wallet paid in one tap: nothing to collect, the payer goes on to the wallet's page or app. */
    fun isRedirectWalletChannel(channel: SessionChannel, config: SdkChannelConfig?): Boolean =
        channel.type.equals("wallet", ignoreCase = true) &&
            config != null &&
            config.available &&
            config.sdk == "form" &&
            config.requiredFields.isEmpty()

    /** The contact and billing fields a card method may ask for before its payment page opens. */
    val hostedCardFields: List<String> =
        listOf("first_name", "last_name", "email", "phone", "address", "city", "country", "state", "zip_code")

    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val countryPattern = Regex("^[A-Za-z]{2}$")

    /** Why a hosted-redirect billing value can't be sent yet, or null. */
    fun hostedCardFieldError(field: String, value: String?): String? {
        val v = value?.trim().orEmpty()
        return when {
            v.isEmpty() -> "Required"
            field == "email" && !emailPattern.matches(v) -> "Invalid email"
            // CinetPay's customer_country is a 2-letter ISO code.
            field == "country" && !countryPattern.matches(v) -> "Use the 2-letter country code"
            else -> null
        }
    }

    /** The data of a card payment on the provider's page, from the fields the payer entered. */
    fun buildHostedCardRequest(billing: Map<String, String>): Map<String, Any?> =
        hostedCardFields.mapNotNull { field ->
            val v = billing[field]?.trim().orEmpty()
            if (v.isEmpty()) null else field to (if (field == "country") v.uppercase() else v)
        }.toMap()

    /**
     * The data of a Mobile Money payment, with the number as given.
     * [com.wajub.mobile.mobilemoney.MobileMoneyRules.requestData] builds it
     * from the form, with the number in international format.
     */
    fun buildMobileMoneyRequest(input: MobileMoneyInput): Map<String, Any?> =
        buildMap {
            put("phone", input.phone)
            put("country", input.country.uppercase())
            input.operator?.takeIf { it.isNotBlank() }?.let { put("operator", it) }
        }

    fun mapProcessResponse(body: RawProcessResponse, methodType: String): PaymentResult {
        val txn = body.transaction ?: return PaymentResult.Failed(
            WajubError(WajubErrorType.PaymentError, "Missing transaction", "invalid_response"),
            null,
        )
        val mapped = mapTransaction(txn)

        if (body.action == null && successStatuses.contains(txn.status.lowercase())) {
            return PaymentResult.Complete(mapped)
        }

        when (body.action) {
            "redirect" -> {
                val url = body.confirmUrl ?: body.simulatorUrl
                return PaymentResult.RequiresAction(ActionKind.Redirect, url, mapped)
            }
            "confirm", "confirm_3ds" -> {
                val kind = if (body.action == "confirm_3ds") ActionKind.Confirm3ds else ActionKind.Confirm
                return PaymentResult.RequiresAction(kind, body.confirmUrl ?: body.simulatorUrl, mapped)
            }
            "client_session" -> body.clientSession?.let { session ->
                return PaymentResult.RequiresAction(
                    ActionKind.ClientSession,
                    session.hostedUrl,
                    mapped,
                    clientSession = session.toModel(),
                )
            }
        }

        // An action this 1.x mapping can't express (confirm_otp, confirm_pin,
        // a client_session without its object, …) must surface as a failure.
        // WajubSession.process(ProcessRequest) maps the confirmation steps.
        if (body.action != null) {
            return PaymentResult.Failed(
                WajubError(
                    WajubErrorType.PaymentError,
                    "This payment requires a step the SDK can't perform (${body.action}).",
                    UNSUPPORTED_ACTION_CODE,
                ),
                mapped,
            )
        }

        if (methodType == "mobile_money") {
            val instruction = txn.processingContext?.payerInstruction
            return PaymentResult.RequiresAction(
                ActionKind.PushApproval,
                body.confirmUrl,
                mapped.copy(processingContext = ProcessingContext(payerInstruction = instruction)),
            )
        }

        return PaymentResult.Processing(
            mapped,
            txn.processingContext?.payerInstruction,
        )
    }

    fun mapTransaction(raw: RawTransaction): SessionTransaction =
        SessionTransaction(
            id = raw.id,
            reference = raw.reference,
            trxref = raw.trxref,
            amount = raw.amount,
            amountTotal = raw.amountTotal,
            currency = raw.currency,
            status = raw.status,
            statusLabel = raw.statusLabel,
            sandbox = raw.sandbox,
            description = raw.description,
            processingContext = raw.processingContext?.toModel(),
            amountCollected = raw.amountCollected,
            amountPaid = raw.amountPaid,
            remainingAmount = raw.remainingAmount,
            bearer = raw.bearer,
            fee = raw.fee,
            customer = raw.customer,
            receiptUrl = raw.receiptUrl,
            expiresAt = raw.expiresAt,
            createdAt = raw.createdAt,
            updatedAt = raw.updatedAt,
            terminalAt = raw.terminalAt,
            errorCode = raw.errorCode,
        ).also { it.errorCodeReported = raw.errorCodeReported }

    fun mapStatusToResult(status: String, transaction: SessionTransaction): PaymentResult {
        val normalized = status.lowercase()
        return when {
            successStatuses.contains(normalized) -> PaymentResult.Complete(transaction)
            normalized in setOf("failed", "cancelled", "expired") ->
                PaymentResult.Failed(
                    WajubError(
                        WajubErrorType.PaymentError,
                        status,
                        normalized,
                    ),
                    transaction,
                )
            else -> PaymentResult.Processing(transaction)
        }
    }
}
