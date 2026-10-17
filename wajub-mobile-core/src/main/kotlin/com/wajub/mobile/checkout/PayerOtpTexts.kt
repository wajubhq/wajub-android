package com.wajub.mobile.checkout

import com.wajub.mobile.model.PayerOtpInstructions
import com.wajub.mobile.model.WajubLanguage

// The payer's own code refused, as the web checkout words it (errors.md §3.8).
// Shown on the code screen, never sent to onError.
internal object PayerOtpTexts {
    /** A malformed code, also the check before sending it. */
    fun length(instructions: PayerOtpInstructions, language: WajubLanguage): String {
        val lengths = instructions.codeLengths
        val fr = language == WajubLanguage.Fr
        return if (lengths.first == lengths.last) {
            (if (fr) "Le code doit comporter {length} chiffres." else "The code must have {length} digits.")
                .replace("{length}", lengths.first.toString())
        } else {
            (if (fr) "Le code doit comporter entre {min} et {max} chiffres." else "The code must have between {min} and {max} digits.")
                .replace("{min}", lengths.first.toString())
                .replace("{max}", lengths.last.toString())
        }
    }

    /** A code the operator did not accept, at once or later. */
    fun refused(language: WajubLanguage): String =
        if (language == WajubLanguage.Fr) {
            "Votre opérateur n'a pas accepté ce code. Générez-en un nouveau et réessayez."
        } else {
            "Your operator did not accept this code. Generate a new one and try again."
        }

    /** The code a test payment takes. */
    const val SANDBOX_CODE = "123456"
}
