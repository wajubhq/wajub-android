package com.wajub.mobile

import com.wajub.mobile.client.PayClient
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.model.MobileMoneyInput
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.realtime.RealtimeSubscriber
import com.wajub.mobile.realtime.StatusPoller
import kotlinx.coroutines.flow.Flow

/**
 * Session-scoped checkout client — mirrors the hosted checkout `/pay` flow without WebView.
 */
class WajubSession internal constructor(
    private val token: String,
    private val client: PayClient = PayClient(),
) {
    private var cachedSession: SessionData? = null

    /** Loads (or returns cached) checkout session data. */
    suspend fun loadSession(forceRefresh: Boolean = false): SessionData {
        if (!forceRefresh && cachedSession != null) {
            return cachedSession!!
        }
        val session = client.getSession(token)
        cachedSession = session
        return session
    }

    /** Per-channel PSP tokenizer configuration. */
    suspend fun getSdkConfig(): SdkConfig = client.getSdkConfig(token)

    /**
     * Submit a Mobile Money payment using the native `form` flavor.
     * Channel slug must match a channel from [loadSession].
     */
    suspend fun payMobileMoney(input: MobileMoneyInput): PaymentResult {
        val session = loadSession()
        val channel = input.channelSlug
        val body = PaymentMapper.buildMobileMoneyRequest(input)
        val raw = client.process(token, channel, body)
        return PaymentMapper.mapProcessResponse(raw, "mobile_money")
    }

    /** Stripe tokenized card — `payment_method_id` from [StripeTokenizer]. */
    suspend fun payCard(channelSlug: String, paymentMethodId: String, cardholderName: String? = null): PaymentResult {
        loadSession()
        val body = linkedMapOf<String, Any?>("payment_method_id" to paymentMethodId)
        if (!cardholderName.isNullOrBlank()) {
            body["card_holder"] = cardholderName
        }
        val raw = client.process(token, channelSlug, body)
        return PaymentMapper.mapProcessResponse(raw, "card")
    }

    /** Generic process call for advanced integrations (Stripe adapter, etc.). */
    suspend fun process(channel: String, data: Map<String, Any?>): PaymentResult {
        val raw = client.process(token, channel, data)
        val methodType = sessionMethodType(channel)
        return PaymentMapper.mapProcessResponse(raw, methodType)
    }

    /** Cancels the checkout session. */
    suspend fun cancel(): String? = client.cancel(token).redirectUrl

    /** Pusher/Reverb when `echo` is configured, otherwise polls `GET /pay/session`. */
    suspend fun watchStatus(intervalMs: Long = 5_000L): Flow<PaymentResult> {
        val session = loadSession()
        return RealtimeSubscriber(client, token, session, intervalMs).subscribe()
    }

    /** Legacy polling-only API. */
    fun watchStatusPolling(intervalMs: Long = 5_000L): Flow<PaymentResult> =
        StatusPoller(client, token, intervalMs).poll()

    fun cardChannelSlug(): String? =
        cachedSession?.channels?.firstOrNull { it.type.equals("card", ignoreCase = true) }?.slug

    private fun sessionMethodType(channel: String): String {
        val channels = cachedSession?.channels.orEmpty()
        val match = channels.firstOrNull { it.slug == channel }
        return when (match?.type?.lowercase()) {
            "mobile_money", "mobile" -> "mobile_money"
            "card" -> "card"
            else -> "unknown"
        }
    }
}
