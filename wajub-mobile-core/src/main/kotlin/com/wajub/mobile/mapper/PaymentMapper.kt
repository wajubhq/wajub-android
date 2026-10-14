package com.wajub.mobile.mapper

import com.wajub.mobile.mobilemoney.MobileMoney
import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.AltchaChallenge
import com.wajub.mobile.model.Branding
import com.wajub.mobile.model.ChannelOperator
import com.wajub.mobile.model.ClientSession
import com.wajub.mobile.model.Customer
import com.wajub.mobile.model.EchoConfig
import com.wajub.mobile.model.MobileMoneyInput
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
) {
    fun toModel(): SessionTransaction = PaymentMapper.mapTransaction(this)

    companion object {
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
            )
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
    fun toModel(): ProcessingContext =
        ProcessingContext(
            channelSlug, isMobileMoney, payerInstruction, actionUrl, isUssd, isBankTransfer,
            threeDsId, threeDsMethod, maskedEmail, threeDsTimeout,
        )

    companion object {
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
) {
    fun toModel(): SessionChannel =
        SessionChannel(id, slug, name, nameFr, type, logo, countries, minAmount, maxAmount, currency, operators)

    companion object {
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
                    )
                },
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

    companion object {
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

/** A 2xx body of `/pay/process`, `/pay/client-session` or `/pay/client-session/complete`. */
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
) {
    companion object {
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
            )
    }
}

object PaymentMapper {
    private val successStatuses = setOf("success", "successful", "succeeded", "paid", "complete")

    /**
     * A `wallet` channel payable with a single tap — nothing to collect, `/pay/process`
     * answers with a redirect to the wallet's own page/app (e.g. Djamo). Apple Pay /
     * Google Pay / PayPal-via-Stripe need their own SDK flavor and are excluded by
     * `sdk == "form"` + no `requiredFields`.
     */
    fun isRedirectWalletChannel(channel: SessionChannel, config: SdkChannelConfig?): Boolean =
        channel.type.equals("wallet", ignoreCase = true) &&
            config != null &&
            config.available &&
            config.sdk == "form" &&
            config.requiredFields.isEmpty()

    /**
     * Contact/billing fields a `hosted_redirect` card PSP may still need before it
     * opens its page — `sdk-config` lists the missing ones in `requiredFields`
     * (Kkiapay/FedaPay/PayDunya: `email`; CinetPay: `first_name` … `zip_code`).
     */
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

    /** `data` for a hosted-redirect card charge — flat keys, as `sdk-config` names them. */
    fun buildHostedCardRequest(billing: Map<String, String>): Map<String, Any?> =
        hostedCardFields.mapNotNull { field ->
            val v = billing[field]?.trim().orEmpty()
            if (v.isEmpty()) null else field to (if (field == "country") v.uppercase() else v)
        }.toMap()

    /**
     * `data` for a Mobile Money charge: the number in E.164 (`+237677123456`)
     * when [MobileMoneyInput.country] is a Mobile Money country the SDK knows,
     * the ISO-2 country, and the operator an aggregate channel was given.
     */
    fun buildMobileMoneyRequest(input: MobileMoneyInput): Map<String, Any?> {
        val country = input.country.uppercase()
        val phone = MobileMoney.data().country(country)?.let { MobileMoney.e164(input.phone, it) } ?: input.phone
        return buildMap {
            put("phone", phone)
            put("country", country)
            input.operator?.takeIf { it.isNotBlank() }?.let { put("operator", it) }
        }
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
        )

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
