package com.wajub.mobile.model

/**
 * How the payer generates the code their operator asks for before a Mobile
 * Money payment (Orange in Burkina Faso), for the exact amount.
 */
data class PayerOtpInstructions(
    /** The channel the code is for (`bf.orange`). */
    val channel: String,
    /** The code to dial in the operator's menu (`*144*4*6#`), or null. */
    val ussdCode: String?,
    /** The code's number of digits; null: 4 to 8. */
    val otpLength: Int?,
    /** The exact amount to enter, as the payer types it (`1000`). */
    val amount: String,
    val currency: String,
    /** The steps to get the code, by language (`en`, `fr`). */
    val steps: Map<String, List<PayerOtpStep>> = emptyMap(),
    /** Why the code is asked: `required`, `invalid` (malformed) or `refused` (the operator refused it); null before any payment request. */
    val reason: String? = null,
    /** The codes the operator may still refuse for this payment, when known. */
    val attemptsRemaining: Int? = null,
) {
    /** The lengths the code may have. */
    val codeLengths: IntRange
        get() = otpLength?.takeIf { it > 0 }?.let { it..it } ?: MIN_LENGTH..MAX_LENGTH

    /**
     * The steps in [language], else in English, else in French, each in parts;
     * empty when there are none.
     */
    fun stepsIn(language: WajubLanguage): List<List<PayerOtpPart>> {
        val chosen = listOf(language.code, "en", "fr").firstNotNullOfOrNull { code -> steps[code]?.takeIf { it.isNotEmpty() } }
            ?: return emptyList()
        return chosen.map { parts(it) }
    }

    // The template with its values, or the plain text when the template names
    // something else or a value is missing.
    private fun parts(step: PayerOtpStep): List<PayerOtpPart> {
        val plain = listOf(PayerOtpPart(step.text, PayerOtpPart.Kind.Text))
        val template = step.template ?: return plain
        val values = mapOf(
            "ussd_code" to ussdCode?.takeIf { it.isNotBlank() }?.let { PayerOtpPart(it, PayerOtpPart.Kind.UssdCode) },
            "amount" to amount.takeIf { it.isNotBlank() }?.let { PayerOtpPart(it, PayerOtpPart.Kind.Amount) },
            "currency" to currency.takeIf { it.isNotBlank() }?.let { PayerOtpPart(it, PayerOtpPart.Kind.Text) },
        )
        val out = mutableListOf<PayerOtpPart>()
        var last = 0
        for (match in PLACEHOLDER.findAll(template)) {
            val value = values[match.groupValues[1]] ?: return plain
            if (match.range.first > last) out += PayerOtpPart(template.substring(last, match.range.first), PayerOtpPart.Kind.Text)
            out += value
            last = match.range.last + 1
        }
        if (last < template.length) out += PayerOtpPart(template.substring(last), PayerOtpPart.Kind.Text)
        return out
    }

    private companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        val PLACEHOLDER = Regex("""\{([A-Za-z0-9_]+)\}""")
    }
}

/** One step to get the payer's code: [text] as written, [template] with `{ussd_code}`, `{amount}`, `{currency}`. */
data class PayerOtpStep(
    val text: String,
    val template: String? = null,
)

/** A piece of a step: plain text, the code to dial, or the amount to enter. */
data class PayerOtpPart(
    val text: String,
    val kind: Kind,
) {
    enum class Kind {
        Text,

        /** The code to dial: a link to the phone's dialer. */
        UssdCode,

        /** The exact amount: shown in bold. */
        Amount,
    }
}
