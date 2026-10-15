package com.wajub.mobile.readme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.wajub.mobile.WajubMobile
import com.wajub.mobile.checkout.Appearance
import com.wajub.mobile.checkout.AppearanceVariables
import com.wajub.mobile.checkout.CheckoutCallbacks
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.PaymentSuccess
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.ui.PaymentSheet
import com.wajub.mobile.ui.WajubFonts
import okhttp3.OkHttpClient

// The README's Kotlin samples, compiled with the tests: ReadmeSamplesTest
// checks that every line of every sample is here.

private val authorizationToken = "tok"
private val token = authorizationToken
private val okHttpClient = OkHttpClient()
private val options = PresentOptions()

private fun showReceipt(payment: PaymentSuccess) = Unit
private fun showPending() = Unit
private fun showError(message: String) = Unit
private fun handle(result: CheckoutResult) = Unit

// What an app's R.font holds.
private object R {
    object font {
        const val poppins_regular = 0
        const val poppins_semibold = 0
    }
}

@Composable
internal fun quickStart() {
    var paying by rememberSaveable { mutableStateOf(true) }

    if (paying) {
        PaymentSheet(PresentOptions(sessionToken = authorizationToken)) { result ->
            paying = false
            when (result) {
                is CheckoutResult.Complete -> showReceipt(result.payment)      // confirm on your server first
                is CheckoutResult.Processing -> showPending()                  // money may still move
                is CheckoutResult.Failed -> showError(result.error.message)
                is CheckoutResult.Cancelled -> Unit                            // result.reason, result.redirectUrl
            }
        }
    }
}

@Composable
internal fun ownHttpClient() {
    val session = remember(token) { WajubMobile.createSession(token, okHttpClient) }
    PaymentSheet(session, options) { result -> handle(result) }
}

internal fun callbacks() =
    PresentOptions(
        sessionToken = token,
        callbacks = CheckoutCallbacks(
            onReady = { event -> },            // sessionId, environment ("sandbox"/"live"), amount, currency, methods
            onStateChange = { event -> },      // event.state: CheckoutState, event.method: "mobile_money" | "card" | "wallet" | "unknown"
            onLoadError = { error -> },        // the sheet cannot offer a payment
            onPaymentStarted = { event -> },   // the payment request is accepted: from now on, closing resolves Processing
            onRedirect = { event -> },         // right before a payment provider's page opens (event.url)
            onSuccess = { payment -> },        // PaymentSuccess: id, reference, amount, currency, method, timestamp, receiptUrl
            onError = { error -> },            // every failure the payer sees (not field validation)
            onCancel = { event -> },           // reason: payer_cancelled | dismissed | host_gone, redirectUrl
            onComplete = { result -> },        // the same CheckoutResult as PaymentSheet's onComplete
        ),
    )

internal fun appearance() =
    PresentOptions(
        sessionToken = token,
        appearance = Appearance(
            theme = "stripe",                // a preset (stripe, night, flat, none) or a scheme (light, dark, auto)
            colorScheme = "auto",            // follows the phone
            variables = AppearanceVariables(
                colorPrimary = "#0F766E",
                borderRadius = "12px",
                fontFamily = "Poppins",      // a font your app registers (below), else the system's
            ),
            disableAnimations = false,
        ),
    )

internal fun fonts() {
    WajubFonts.register("Poppins", FontFamily(Font(R.font.poppins_regular), Font(R.font.poppins_semibold, FontWeight.SemiBold)))
}
