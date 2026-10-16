package com.wajub.mobile.checkout

import com.wajub.mobile.mobilemoney.MobileMoney
import com.wajub.mobile.mobilemoney.MobileMoneyCountry
import com.wajub.mobile.mobilemoney.OperatorChoice
import com.wajub.mobile.model.StepKind
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubLanguage
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** What the sheet shows; each screen reports a [CheckoutState]. */
sealed class SheetScreen {
    /** No state yet: the sheet is not ready. */
    data object Loading : SheetScreen()

    /** The sheet could not offer a payment; Retry when [WajubError.retryable]. */
    data class LoadError(val error: WajubError) : SheetScreen()

    data class Methods(val methods: List<PaymentMethod>) : SheetScreen()

    /** A method's form; [busy] while its request runs (`PROCESSING`). */
    data class Form(
        val method: PaymentMethod,
        val mobileMoney: MobileMoneyForm? = null,
        val card: CardForm? = null,
        val busy: Boolean = false,
        /** An error tied to no field. */
        val banner: String? = null,
        /** Another method can be chosen ("Change"). */
        val canChangeMethod: Boolean = false,
    ) : SheetScreen()

    /** A push was sent to the phone. */
    data class Approval(val instruction: String?, val phone: String?) : SheetScreen()

    data class Ussd(val instruction: String?) : SheetScreen()

    /** A confirmation step, or the sandbox 3-D Secure code; [busy] while verifying. */
    data class Step(
        val kind: StepKind,
        val stepId: String,
        /** Where the code was sent, the provider's instruction (in English), or the masked email. */
        val hint: String?,
        val values: Map<String, String> = emptyMap(),
        val fieldErrors: Map<String, String> = emptyMap(),
        val error: String? = null,
        val attemptsRemaining: Int? = null,
        val busy: Boolean = false,
        /** Clock time from which "Resend" is offered. */
        val resendAt: Long,
        /** Clock time the 3-D Secure code expires. */
        val expiresAt: Long? = null,
    ) : SheetScreen() {
        /**
         * For a code (OTP, sandbox 3-D Secure): the masked number or address it
         * was sent to, or null when the provider names none.
         */
        val sentTo: String?
            get() {
                if (kind != StepKind.Otp && kind != StepKind.ThreeDs) return null
                val text = hint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                if (text.none { it.isWhitespace() }) return text
                // An older answer gave the provider's sentence: keep its masked contact only.
                return text.split(Regex("\\s+")).lastOrNull { '*' in it || '@' in it }?.trim('.', ',', ':', ';', '(', ')')
            }
    }

    /** Waiting for the outcome. [url]: a page to reopen ("Continue payment"). */
    data class Pending(
        val url: String? = null,
        /** 3-D Secure in the browser. */
        val threeDs: Boolean = false,
        /** No outcome after a long wait: "you will be notified". */
        val capped: Boolean = false,
    ) : SheetScreen()

    data class Success(val payment: PaymentSuccess) : SheetScreen()

    /** [retryAt]: Try again waits until then (a rate limit). */
    data class Failed(val error: WajubError, val retryAt: Long? = null) : SheetScreen()

    data class Expired(val error: WajubError) : SheetScreen()

    data class Cancelled(val error: WajubError?) : SheetScreen()

    /** Already failed, cancelled or expired when the sheet opened. */
    data class Ended(val error: WajubError, val state: CheckoutState) : SheetScreen()
}

/** The Mobile Money form. */
data class MobileMoneyForm(
    /** The countries the selector lists (ISO-2). */
    val countries: List<String>,
    val country: String?,
    /** The selector is hidden. */
    val countryLocked: Boolean,
    val choices: List<OperatorChoice>,
    val selected: String?,
    /** The payer picked [selected] by hand: detection no longer changes it. */
    val pickedByHand: Boolean = false,
    /** As typed, without the dial code. */
    val phone: String = "",
    val detected: String? = null,
    val operatorMode: OperatorMode = OperatorMode.Choose,
    val listOpened: Boolean = false,
    val phoneError: String? = null,
    val operatorError: String? = null,
) {
    val countryInfo: MobileMoneyCountry? get() = MobileMoney.data().country(country)

    val selectedChoice: OperatorChoice? get() = choices.firstOrNull { it.id == selected }

    /** `operatorMode: detect` keeps the list closed until it is needed. */
    val listVisible: Boolean
        get() {
            if (operatorMode == OperatorMode.Choose || listOpened) return true
            val digits = countryInfo?.let { MobileMoney.nationalDigits(phone, it).length } ?: 0
            val independent = MobileMoney.data().operatorIndependentWallets
            val detectedOffered = detected != null && choices.any { it.id == detected }
            return (digits >= 6 && !detectedOffered) || (detected != null && choices.any { it.id in independent })
        }

    /** The operator shown inside the field in `detect` mode: the detected one, or the only one. */
    val badge: OperatorChoice?
        get() = choices.firstOrNull { it.id == detected } ?: choices.singleOrNull()
}

/** A card form: the fields its flavour asks, and their errors. */
data class CardForm(
    val flavor: CardFlavor,
    val fields: List<String>,
    val values: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
) {
    companion object {
        /** The sandbox test-card fields. */
        val SANDBOX_FIELDS = listOf("card.number", "card_holder", "expiry_month", "expiry_year", "cvv")
    }
}

/** `25 000 FCFA`, `₦1,000.00`: the currency's usual decimals (XAF, XOF: none). */
fun formatAmount(amount: Double, currency: String, language: WajubLanguage): String {
    val locale = if (language == WajubLanguage.Fr) Locale.FRANCE else Locale.US
    return try {
        val format = NumberFormat.getCurrencyInstance(locale)
        val c = Currency.getInstance(currency.uppercase())
        format.currency = c
        format.minimumFractionDigits = c.defaultFractionDigits.coerceAtLeast(0)
        format.maximumFractionDigits = c.defaultFractionDigits.coerceAtLeast(0)
        format.format(amount)
    } catch (_: IllegalArgumentException) {
        "${NumberFormat.getNumberInstance(locale).format(amount)} $currency"
    }
}
