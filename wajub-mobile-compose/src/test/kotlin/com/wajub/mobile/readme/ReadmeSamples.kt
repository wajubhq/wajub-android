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
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessRequest
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
            onLoadError = { error -> },        // session_not_found, session_terminal_expired/_cancelled/_failed, load_error, ready_timeout, no_payment_method
            onPaymentStarted = { event -> },   // first 2xx of an attempt: from now on a dismiss resolves "processing"
            onRedirect = { event -> },         // right before a provider page opens (event.url)
            onSuccess = { payment -> },        // PaymentSuccess: id, reference, amount (amount_total), currency, method, timestamp, receiptUrl
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
            colorScheme = "auto",            // follows the phone, live
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

internal suspend fun ownUi() {
    val session = WajubMobile.createSession(authorizationToken)
    val data = session.loadSession()                    // amount, channels, branding…
    val config = session.getSdkConfig()

    val outcome = session.process(
        ProcessRequest("cm.mobile", mapOf("phone" to "+237677123456", "country" to "CM", "operator" to "mtn")),
    )
    when (outcome) {
        is ProcessOutcome.Complete -> Unit
        is ProcessOutcome.Processing -> Unit                                  // kind: Approval, Ussd, Pending — watch
        is ProcessOutcome.RequiresAction -> Unit                              // a page (url) or a step (step, stepId)
        is ProcessOutcome.Failed -> showError(outcome.error.message)
    }

    session.watch().events().collect { event ->                              // StatusChanged, Succeeded, Failed, Cancelled, Expired, StillProcessing
    }
}
