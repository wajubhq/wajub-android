package com.wajub.mobile.mapper

import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.ClientSession
import com.wajub.mobile.model.MobileMoneyInput
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.ProcessingContext
import com.wajub.mobile.model.SdkChannelConfig
import com.wajub.mobile.model.SessionChannel
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.UNSUPPORTED_ACTION_CODE
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubErrorType
import org.json.JSONArray
import org.json.JSONObject

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
) {
    companion object {
        fun fromJson(obj: JSONObject): RawTransaction {
            val pc = obj.optJSONObject("processing_context")
            return RawTransaction(
                id = obj.optString("id", obj.optString("reference")),
                reference = obj.optString("reference", obj.optString("id")),
                trxref = obj.optString("trxref").ifBlank { null },
                amount = obj.optDouble("amount"),
                amountTotal = obj.optDouble("amount_total").takeIf { obj.has("amount_total") },
                currency = obj.optString("currency"),
                status = obj.optString("status"),
                statusLabel = obj.optString("status_label").ifBlank { null },
                sandbox = obj.optBoolean("sandbox"),
                description = obj.optString("description").ifBlank { null },
                processingContext = pc?.let { RawProcessingContext.fromJson(it) },
            )
        }
    }
}

data class RawProcessingContext(
    val channelSlug: String? = null,
    val isMobileMoney: Boolean = false,
    val payerInstruction: String? = null,
    val actionUrl: String? = null,
) {
    companion object {
        fun fromJson(obj: JSONObject): RawProcessingContext =
            RawProcessingContext(
                channelSlug = obj.optString("channel_slug").ifBlank { null },
                isMobileMoney = obj.optBoolean("is_mobile_money"),
                payerInstruction = obj.optString("payer_instruction").ifBlank { null },
                actionUrl = obj.optString("action_url").ifBlank { null },
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
) {
    companion object {
        fun fromJson(obj: JSONObject): RawChannel {
            val countries = obj.optJSONArray("countries")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            return RawChannel(
                id = obj.optString("id"),
                slug = obj.optString("slug"),
                name = obj.optString("name"),
                nameFr = obj.optString("name_fr").ifBlank { null },
                type = obj.optString("type"),
                logo = obj.optString("logo").ifBlank { null },
                countries = countries,
                minAmount = if (obj.has("min_amount")) obj.optDouble("min_amount") else null,
                maxAmount = if (obj.has("max_amount")) obj.optDouble("max_amount") else null,
                currency = obj.optString("currency"),
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
    companion object {
        fun fromJson(obj: JSONObject): RawEchoConfig =
            RawEchoConfig(
                key = obj.getString("key"),
                wsHost = obj.getString("ws_host"),
                wsPort = obj.getInt("ws_port"),
                channelPrefix = obj.getString("channel_prefix"),
                broadcaster = obj.getString("broadcaster"),
            )
    }
}

data class RawSessionResponse(
    val transaction: RawTransaction,
    val channels: List<RawChannel>,
    val locale: String? = null,
    val branding: Map<String, String?> = emptyMap(),
    val echo: RawEchoConfig? = null,
    val callback: String? = null,
) {
    companion object {
        fun fromJson(body: JSONObject): RawSessionResponse {
            val txn = body.getJSONObject("transaction")
            val channelsArr = body.optJSONArray("channels") ?: JSONArray()
            val channels = (0 until channelsArr.length()).map {
                RawChannel.fromJson(channelsArr.getJSONObject(it))
            }
            val branding = linkedMapOf<String, String?>()
            body.optJSONObject("branding")?.keys()?.forEach { key ->
                branding[key] = body.getJSONObject("branding").optString(key).ifBlank { null }
            }
            val echo = body.optJSONObject("echo")?.let { RawEchoConfig.fromJson(it) }
            return RawSessionResponse(
                transaction = RawTransaction.fromJson(txn),
                channels = channels,
                locale = body.optString("locale").ifBlank { null },
                branding = branding,
                echo = echo,
                callback = body.optString("callback").ifBlank { null },
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
                id = obj.optString("id"),
                provider = obj.optString("provider"),
                reference = obj.optString("reference").ifBlank { null },
                amount = if (obj.has("amount")) obj.optDouble("amount") else null,
                currency = obj.optString("currency").ifBlank { null },
                hostedUrl = obj.optString("hosted_url").ifBlank { null },
                accessCode = obj.optString("access_code").ifBlank { null },
                publicKey = obj.optString("public_key").ifBlank { null },
                encryptionKey = obj.optString("encryption_key").ifBlank { null },
            )
    }
}

data class RawProcessResponse(
    val code: Int,
    val status: String,
    val message: String,
    val action: String? = null,
    val transaction: RawTransaction? = null,
    val confirmUrl: String? = null,
    val simulatorUrl: String? = null,
    val clientSession: RawClientSession? = null,
) {
    companion object {
        fun fromJson(body: JSONObject): RawProcessResponse =
            RawProcessResponse(
                code = body.optInt("code"),
                status = body.optString("status"),
                message = body.optString("message"),
                action = body.optString("action").ifBlank { null },
                transaction = body.optJSONObject("transaction")?.let { RawTransaction.fromJson(it) },
                confirmUrl = body.optString("confirm_url").ifBlank { null },
                simulatorUrl = body.optString("simulator_url").ifBlank { null },
                clientSession = body.optJSONObject("client_session")
                    ?.let { RawClientSession.fromJson(it) }
                    ?.takeIf { it.id.isNotBlank() },
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

    fun buildMobileMoneyRequest(input: MobileMoneyInput): Map<String, Any?> =
        mapOf(
            "phone" to input.phone,
            "country" to input.country.uppercase(),
        )

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

        // An action the SDK can't perform natively (confirm_otp, confirm_pin,
        // card_reauth, a client_session without its object, …) must surface as
        // a failure — mapping it to Processing would leave the payer on an
        // endless spinner with no way to supply what the PSP is asking for.
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
            processingContext = raw.processingContext?.let {
                ProcessingContext(
                    channelSlug = it.channelSlug,
                    isMobileMoney = it.isMobileMoney,
                    payerInstruction = it.payerInstruction,
                    actionUrl = it.actionUrl,
                )
            },
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
