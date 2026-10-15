package com.wajub.mobile.model

import java.util.Locale

/** The sheet's language: French or English. */
enum class WajubLanguage(val code: String) {
    En("en"),
    Fr("fr"),
    ;

    companion object {
        /** `fr…` is French; anything else, or nothing, is English. */
        fun of(tag: String?): WajubLanguage? {
            val language = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (language.lowercase(Locale.ROOT).startsWith("fr")) Fr else En
        }

        /** The device's language, reduced to French or English. */
        fun device(): WajubLanguage = of(Locale.getDefault().toLanguageTag()) ?: En

        /** The first of [requested] (PresentOptions.locale), [session] (the session's), [device]. */
        fun resolve(requested: String?, session: String?, device: String? = Locale.getDefault().toLanguageTag()): WajubLanguage =
            of(requested) ?: of(session) ?: of(device) ?: En
    }
}

/**
 * Every error code: its type, whether the payer can try again, and its message
 * in English and French.
 */
object ErrorCatalog {
    class Entry internal constructor(
        val type: WajubErrorType,
        val retryable: Boolean,
        val en: String,
        val fr: String,
    )

    private val api = WajubErrorType.ApiError
    private val auth = WajubErrorType.AuthenticationError
    private val invalid = WajubErrorType.InvalidRequestError
    private val payment = WajubErrorType.PaymentError

    private const val LOAD_EN = "We couldn't load the payment. Check your connection and try again."
    private const val LOAD_FR = "Impossible de charger le paiement. Vérifiez votre connexion et réessayez."
    private const val EXPIRED_STEP_EN = "This verification has expired."
    private const val EXPIRED_STEP_FR = "Cette vérification a expiré."
    private const val DECLINE_EN = "This payment could not be completed. Try again or use another payment method."
    private const val DECLINE_FR = "Ce paiement n'a pas abouti. Réessayez ou utilisez un autre moyen de paiement."

    private val entries: Map<String, Entry> = mapOf(
        // Load
        "session_not_found" to Entry(
            auth, false,
            "We couldn't find this payment session. Check the link you received or ask the merchant for a new one.",
            "Nous n'avons pas pu retrouver cette session de paiement. Vérifiez le lien reçu ou demandez-en un nouveau au marchand.",
        ),
        "session_terminal_expired" to Entry(invalid, false, "This payment session has expired.", "Cette session de paiement a expiré."),
        "session_terminal_cancelled" to Entry(invalid, false, "This payment was cancelled.", "Ce paiement a été annulé."),
        "session_terminal_failed" to Entry(
            invalid, false,
            "This payment has ended without being paid. Ask the merchant for a new payment link.",
            "Ce paiement s'est terminé sans être réglé. Demandez un nouveau lien de paiement au marchand.",
        ),
        "load_error" to Entry(api, true, LOAD_EN, LOAD_FR),
        "ready_timeout" to Entry(api, true, LOAD_EN, LOAD_FR),
        "no_payment_method" to Entry(
            invalid, false,
            "No payment method is available for this payment.",
            "Aucun moyen de paiement n'est disponible pour ce paiement.",
        ),
        // Transport
        "network_error" to Entry(api, true, "Connection error. Check your network.", "Erreur de connexion. Vérifiez votre réseau."),
        "timeout" to Entry(api, true, "The request timed out.", "Le délai d'attente a été dépassé."),
        "server_error" to Entry(api, true, "Service temporarily unavailable.", "Service temporairement indisponible."),
        "rate_limited" to Entry(
            WajubErrorType.RateLimitError, true,
            "Too many attempts. Please wait {seconds} s before trying again.",
            "Trop de tentatives. Merci de patienter {seconds} s avant de réessayer.",
        ),
        "request_failed" to Entry(invalid, false, "An error occurred. Please try again.", "Une erreur est survenue. Veuillez réessayer."),
        // Form
        "phone_required" to Entry(invalid, true, "Enter the Mobile Money number to pay with.", "Saisissez le numéro Mobile Money qui va payer."),
        "invalid_phone" to Entry(invalid, true, "Invalid phone number", "Numéro de téléphone invalide"),
        "operator_mismatch" to Entry(
            invalid, true,
            "This number belongs to {detected}. Pick {detected} in the list, or enter a {selected} number.",
            "Ce numéro est un numéro {detected}. Choisissez {detected} dans la liste, ou saisissez un numéro {selected}.",
        ),
        "operator_required" to Entry(invalid, true, "Choose your Mobile Money operator.", "Choisissez votre opérateur Mobile Money."),
        "operator_unavailable" to Entry(
            invalid, true,
            "No Mobile Money operator is available for this country. Try another payment method.",
            "Aucun opérateur Mobile Money n'est disponible pour ce pays. Essayez un autre moyen de paiement.",
        ),
        "field_required" to Entry(invalid, true, "This field is required", "Ce champ est requis"),
        "invalid_email" to Entry(invalid, true, "Enter a valid email address.", "Saisissez une adresse e-mail valide."),
        "validation_error" to Entry(invalid, true, "Check the information entered.", "Vérifiez les informations saisies."),
        // Payment
        "payment_declined" to Entry(payment, true, "Payment declined by the issuer.", "Paiement refusé par l'émetteur."),
        "insufficient_funds" to Entry(payment, true, "Insufficient funds.", "Fonds insuffisants."),
        "merchant_not_verified" to Entry(
            payment, false,
            "This merchant can't accept payments yet. Please contact them.",
            "Ce marchand ne peut pas encore accepter de paiements. Veuillez le contacter.",
        ),
        "limit_exceeded" to Entry(
            payment, true,
            "This payment exceeds this merchant's allowed limit. Try a smaller amount or contact the merchant.",
            "Ce paiement dépasse la limite autorisée pour ce marchand. Essayez un montant plus faible ou contactez le marchand.",
        ),
        "shield_blocked" to Entry(
            payment, false,
            "Payment blocked for security. Reference: {block_reference}.",
            "Paiement bloqué pour sécurité. Référence : {block_reference}.",
        ),
        "merchant_restricted" to Entry(
            payment, false,
            "Payments are currently restricted for this merchant.",
            "Les paiements sont actuellement restreints pour ce marchand.",
        ),
        "payment_method_restricted" to Entry(
            payment, true,
            "This payment method is not available for this merchant.",
            "Ce moyen de paiement n'est pas disponible pour ce marchand.",
        ),
        "country_restricted" to Entry(
            payment, false,
            "This merchant does not accept payments from your country.",
            "Ce marchand n'accepte pas les paiements depuis votre pays.",
        ),
        "currency_restricted" to Entry(
            payment, false,
            "This merchant does not accept payments in this currency.",
            "Ce marchand n'accepte pas les paiements dans cette devise.",
        ),
        "channel_not_accepted" to Entry(
            payment, true,
            "This payment method is not available for this payment.",
            "Ce moyen de paiement n'est pas disponible pour ce paiement.",
        ),
        "payment_failed" to Entry(payment, false, "Your payment could not be processed.", "Votre paiement n'a pas pu être traité."),
        "payment_attempt_ended" to Entry(
            payment, true,
            "Your payment was not completed, and nothing was charged. Choose a payment method to try again.",
            "Votre paiement n'a pas abouti et rien n'a été débité. Choisissez un moyen de paiement pour réessayer.",
        ),
        "payment_cancelled" to Entry(payment, false, "This payment was cancelled.", "Ce paiement a été annulé."),
        "session_expired" to Entry(invalid, false, "This payment has expired.", "Ce paiement a expiré."),
        "not_processable" to Entry(invalid, false, "This payment can no longer be processed.", "Ce paiement ne peut plus être traité."),
        "order_not_validated" to Entry(
            invalid, false,
            "The merchant has not confirmed this order yet. Try again in a moment.",
            "Le marchand n'a pas encore confirmé cette commande. Réessayez dans un instant.",
        ),
        "raw_card_data_not_accepted" to Entry(
            invalid, false,
            "This payment method can't be used here.",
            "Ce moyen de paiement ne peut pas être utilisé ici.",
        ),
        "security_check_failed" to Entry(
            invalid, true,
            "The security check failed. Try again in a moment.",
            "La vérification de sécurité a échoué. Réessayez dans un instant.",
        ),
        "unsupported_action" to Entry(
            api, false,
            "This payment step isn't supported in the app. Try another payment method.",
            "Cette étape de paiement n'est pas prise en charge dans l'application. Essayez un autre moyen de paiement.",
        ),
        "redirect_unavailable" to Entry(
            api, true,
            "We couldn't open the payment page. Check that a web browser is enabled, then try again.",
            "Impossible d'ouvrir la page de paiement. Vérifiez qu'un navigateur est activé, puis réessayez.",
        ),
        // Confirmation steps
        "invalid_otp" to Entry(invalid, true, "Incorrect code. {n} attempts left.", "Code incorrect. Encore {n} essais."),
        "otp_expired" to Entry(invalid, true, "The code has expired.", "Le code a expiré."),
        "invalid_pin" to Entry(invalid, true, "Incorrect PIN. {n} attempts left.", "Code PIN incorrect. Encore {n} essais."),
        "pin_expired" to Entry(invalid, true, EXPIRED_STEP_EN, EXPIRED_STEP_FR),
        "phone_expired" to Entry(invalid, true, EXPIRED_STEP_EN, EXPIRED_STEP_FR),
        "invalid_birthday" to Entry(
            invalid, true,
            "This date of birth was not accepted. {n} attempts left.",
            "Cette date de naissance n'a pas été acceptée. Encore {n} essais.",
        ),
        "birthday_expired" to Entry(invalid, true, EXPIRED_STEP_EN, EXPIRED_STEP_FR),
        "invalid_address" to Entry(
            invalid, true,
            "This address was not accepted. {n} attempts left.",
            "Cette adresse n'a pas été acceptée. Encore {n} essais.",
        ),
        "address_expired" to Entry(invalid, true, EXPIRED_STEP_EN, EXPIRED_STEP_FR),
        "max_attempts_exceeded" to Entry(payment, true, "Too many incorrect attempts.", "Trop de tentatives incorrectes."),
        "invalid_code" to Entry(payment, true, "Invalid verification code.", "Code de vérification invalide."),
        "3ds_failed" to Entry(payment, true, "3D Secure verification failed.", "La vérification 3D Secure a échoué."),
        "3ds_rejected" to Entry(
            payment, true,
            "Your bank declined the payment during 3D Secure.",
            "Votre banque a refusé le paiement lors du 3D Secure.",
        ),
        "3ds_cancelled" to Entry(payment, true, "3D Secure verification was cancelled.", "La vérification 3D Secure a été annulée."),
        "3ds_timeout" to Entry(payment, true, "3D Secure verification has expired.", "La vérification 3D Secure a expiré."),
        // Client sessions
        "client_session_unavailable" to Entry(
            invalid, false,
            "Card payment is not available in test mode for this provider.",
            "Le paiement par carte n'est pas disponible en mode test pour ce prestataire.",
        ),
        "client_session_expired" to Entry(
            invalid, true,
            "This card payment session has expired. Start again.",
            "Cette session de paiement par carte a expiré. Recommencez.",
        ),
        // SDK use
        "sheet_already_presented" to Entry(
            invalid, false,
            "A Wajub payment sheet is already presented.",
            "A Wajub payment sheet is already presented.",
        ),
    )

    /** Every code of the catalogue. */
    val codes: Set<String> get() = entries.keys

    fun entry(code: String): Entry? = entries[code]

    /**
     * [code]'s message in [language], placeholders filled from [error]
     * (`{seconds}`, `{block_reference}`) and [args] (`{n}`, `{detected}`,
     * `{selected}`); null for a code outside the catalogue.
     */
    fun message(code: String, language: WajubLanguage, error: WajubError?, args: Map<String, String> = emptyMap()): String? {
        val entry = entries[code] ?: return null
        var text = if (language == WajubLanguage.Fr) entry.fr else entry.en
        if (code == "validation_error" && language == WajubLanguage.En) {
            // The API's 422 texts are English: an English sheet shows them.
            error?.apiMessage?.let { return it }
        }
        val values = buildMap {
            put("seconds", error?.retryAfterSeconds?.toString() ?: "…")
            put("block_reference", error?.blockReference ?: "—")
            putAll(args)
        }
        values.forEach { (key, value) -> text = text.replace("{$key}", value) }
        return text
    }

    /** The text of a provider's decline code the catalogue does not name (`failed`, `stolen_card`…). */
    fun genericDecline(language: WajubLanguage): String = if (language == WajubLanguage.Fr) DECLINE_FR else DECLINE_EN
}
