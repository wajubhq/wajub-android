package com.wajub.mobile.ui

import android.content.Context
import com.stripe.android.PaymentConfiguration
import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.PaymentMethodCreateParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Stripe `stripe_elements` adapter — native tokenization (no WebView).
 *
 * Raw card details (PAN/expiry/CVC) never pass through this SDK's own code or app-owned state:
 * callers must obtain [PaymentMethodCreateParams.Card] directly from Stripe's own secure card
 * input widget (`com.stripe.android.view.CardInputWidget`), which builds it internally from its
 * own protected input fields. This keeps raw card data out of PCI-DSS SAQ scope for this app.
 */
object StripeTokenizer {
    suspend fun ensureInitialized(context: Context, publishableKey: String) {
        PaymentConfiguration.init(context, publishableKey)
    }

    /**
     * @param card Card params produced directly by Stripe's `CardInputWidget`
     *   (`CardInputWidget.paymentMethodCreateParams?.card`) — never build this from raw
     *   strings captured in app-owned state.
     */
    suspend fun createPaymentMethod(
        context: Context,
        publishableKey: String,
        card: PaymentMethodCreateParams.Card,
        cardholderName: String? = null,
    ): String {
        ensureInitialized(context, publishableKey)
        val billingDetails = cardholderName?.let { PaymentMethod.BillingDetails(name = it) }
        val params = PaymentMethodCreateParams.create(card, billingDetails)

        return suspendCancellableCoroutine { cont ->
            val stripe = com.stripe.android.Stripe(context, publishableKey)
            stripe.createPaymentMethod(
                params,
                callback = object : com.stripe.android.ApiResultCallback<com.stripe.android.model.PaymentMethod> {
                    override fun onSuccess(result: com.stripe.android.model.PaymentMethod) {
                        cont.resume(result.id ?: error("Missing payment method id"))
                    }

                    override fun onError(e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
            )
        }
    }
}

fun buildStripeCardRequest(paymentMethodId: String, cardholderName: String? = null): Map<String, Any?> {
    val data = linkedMapOf<String, Any?>("payment_method_id" to paymentMethodId)
    if (!cardholderName.isNullOrBlank()) {
        data["card_holder"] = cardholderName
    }
    return data
}
