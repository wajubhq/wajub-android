package com.wajub.mobile.mobilemoney

import com.wajub.mobile.model.PayerOtpInstructions
import com.wajub.mobile.model.SessionChannel
import com.wajub.mobile.model.WajubLanguage

/** One operator the payer can pick in the Mobile Money form. */
data class OperatorChoice(
    /** `mtn`, `orange`, `wave`, `eu`. */
    val id: String,
    /** The brand label (`MTN MoMo`), else the channel's name. */
    val label: String,
    /** An `https` logo, or null: draw [badge]. */
    val logo: String?,
    /** The slug to pay through: the aggregate's (`cm.mobile`) for one of its members. */
    val channelSlug: String,
    /** Paid through an aggregate channel, which takes an `operator` hint. */
    val viaAggregate: Boolean,
    val badge: BrandBadge,
    /** The channel's `min_amount` / `max_amount`. */
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    /** The logo as a PNG, when there is one. */
    val logoPng: String? = null,
    /** What the payer pays on this operator, when its price is its own (a fee that depends on it). */
    val amountTotal: Double? = null,
    /** Set when paying on this operator takes a code the payer generates in its menu first. */
    val payerOtp: PayerOtpInstructions? = null,
)

/** A form problem found before submitting. */
data class FormIssue(
    /** `phone_required`, `invalid_phone`, `operator_mismatch`, `operator_required`. */
    val code: String,
    /** Placeholders of the message (`detected`, `selected`). */
    val args: Map<String, String> = emptyMap(),
    /** Whether it stops the payment. */
    val blocking: Boolean = true,
)

/** The Mobile Money form's rules: countries, operators, selection and checks. */
object MobileMoneyRules {
    fun isMobileMoney(channel: SessionChannel): Boolean =
        channel.type.equals("mobile_money", true) || channel.type.equals("mobile", true)

    /** The countries of the Mobile Money channels, in API order. */
    fun countries(channels: List<SessionChannel>): List<String> =
        channels.filter { isMobileMoney(it) }.flatMap { it.countries }.map { it.uppercase() }.distinct()

    /** The requested country, else [geoCountry] when a channel covers it, else the first channel's first country. */
    fun defaultCountry(channels: List<SessionChannel>, requested: String?, geoCountry: String?): String? {
        requested?.takeIf { it.isNotBlank() }?.let { return it.uppercase() }
        val covered = countries(channels)
        geoCountry?.uppercase()?.takeIf { it in covered }?.let { return it }
        return covered.firstOrNull()
    }

    /**
     * The choices for [country]: one per member of an aggregate channel, one
     * per other Mobile Money channel, in API order; a repeated id keeps the first.
     */
    fun choices(channels: List<SessionChannel>, country: String?, language: WajubLanguage): List<OperatorChoice> {
        val data = MobileMoney.data()
        val out = linkedMapOf<String, OperatorChoice>()
        for (channel in channels) {
            if (!isMobileMoney(channel)) continue
            if (channel.countries.isNotEmpty() && channel.countries.none { it.equals(country, true) }) continue
            if (channel.operators.isNotEmpty()) {
                for (op in channel.operators) {
                    if (op.operator in out) continue
                    val label = data.brandLabel(op.operator) ?: op.name
                    out[op.operator] = OperatorChoice(
                        id = op.operator,
                        label = label,
                        logo = op.logo?.takeIf { it.startsWith("https://") },
                        channelSlug = channel.slug,
                        viaAggregate = true,
                        badge = data.badge(op.operator, op.name),
                        minAmount = channel.minAmount,
                        maxAmount = channel.maxAmount,
                        logoPng = op.logoPng?.takeIf { it.startsWith("https://") },
                        // The operator's own price, else the aggregate's (all its operators agree).
                        amountTotal = op.amountTotal ?: channel.amountTotal,
                    )
                }
            } else {
                val id = channel.slug.substringAfter('.').lowercase()
                if (id in out) continue
                val name = channelName(channel, language)
                out[id] = OperatorChoice(
                    id = id,
                    label = data.brandLabel(id) ?: name,
                    logo = channel.logo?.takeIf { it.startsWith("https://") },
                    channelSlug = channel.slug,
                    viaAggregate = false,
                    badge = data.badge(id, name),
                    minAmount = channel.minAmount,
                    maxAmount = channel.maxAmount,
                    logoPng = channel.logoPng?.takeIf { it.startsWith("https://") },
                    amountTotal = channel.amountTotal,
                    // Never announced on an aggregate: only a channel of its own asks for it.
                    payerOtp = channel.payerOtp,
                )
            }
        }
        return out.values.toList()
    }

    /** A channel's name in the sheet's language. */
    fun channelName(channel: SessionChannel, language: WajubLanguage): String =
        if (language == WajubLanguage.Fr) channel.nameFr ?: channel.name else channel.name

    /**
     * The selection after the number changed: a payer's own pick stays; else a
     * detected operator among [choiceIds] is selected; else [current], else the
     * first operator that is not a wallet held whatever the operator (Wave, Express
     * Union…), else the first.
     */
    fun selection(choiceIds: List<String>, current: String?, pickedByHand: Boolean, detected: String?): String? {
        if (pickedByHand && current != null) return current
        if (detected != null && detected in choiceIds) return detected
        current?.takeIf { it in choiceIds }?.let { return it }
        val independent = MobileMoney.data().operatorIndependentWallets
        return choiceIds.firstOrNull { it !in independent } ?: choiceIds.firstOrNull()
    }

    /**
     * Checks the form before submitting, in this order:
     * `phone_required`, `invalid_phone`, `operator_mismatch`, `operator_required`.
     * In a [sandbox] session a test number is valid and tests its own operator:
     * picked on another operator, it is a mismatch, as in live.
     */
    fun validate(
        phone: String,
        country: MobileMoneyCountry,
        choiceIds: List<String>,
        selected: String?,
        label: (String) -> String,
        sandbox: Boolean,
    ): FormIssue? {
        if (MobileMoney.sanitize(phone).isEmpty()) return FormIssue("phone_required")
        if (!MobileMoney.isValid(phone, country, sandbox)) return FormIssue("invalid_phone")
        val detected = MobileMoney.detectOperator(phone, country, sandbox)
        val independent = MobileMoney.data().operatorIndependentWallets
        if (detected != null && detected in choiceIds && selected != null && detected != selected && selected !in independent) {
            return FormIssue("operator_mismatch", mapOf("detected" to label(detected), "selected" to label(selected)))
        }
        if (selected == null) return FormIssue("operator_required")
        return null
    }

    /**
     * The `operator` sent with an aggregate charge: the selection, when the
     * payer picked it by hand or the number names it. A default never touched
     * is not sent: the API would route to it instead of refusing the number.
     */
    fun operatorHint(viaAggregate: Boolean, selected: String?, pickedByHand: Boolean, detected: String?): String? {
        if (!viaAggregate || selected == null) return null
        return if (pickedByHand || detected == selected) selected else null
    }

    /** The data of a Mobile Money payment, from the form; in a [sandbox] session a test number is sent as typed. */
    fun requestData(
        choice: OperatorChoice,
        phone: String,
        country: MobileMoneyCountry,
        pickedByHand: Boolean,
        sandbox: Boolean = false,
    ): Map<String, Any?> {
        val detected = MobileMoney.detectOperator(phone, country, sandbox)
        return buildMap {
            put("phone", MobileMoney.e164(phone, country, sandbox))
            put("country", country.code)
            operatorHint(choice.viaAggregate, choice.id, pickedByHand, detected)?.let { put("operator", it) }
        }
    }
}
