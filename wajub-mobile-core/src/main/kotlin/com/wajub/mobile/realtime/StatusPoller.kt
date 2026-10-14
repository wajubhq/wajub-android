package com.wajub.mobile.realtime

import com.wajub.mobile.API_URL
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.model.PaymentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Polling only, every [intervalMs]: emits when the result changes and ends on
 * a final one — a session that is gone (401, 404, 410) included. Errors back
 * off up to a minute.
 */
@Deprecated("Use WajubSession.watch(RealtimeOptions(enabled = false)).")
class StatusPoller(
    private val client: PayClient,
    private val token: String,
    private val intervalMs: Long = 5_000L,
) {
    fun poll(): Flow<PaymentResult> = flow {
        val transaction = client.getSession(token).transaction
        val watcher = PaymentWatcher(
            client = client,
            token = token,
            transactionId = transaction.id,
            endpoint = null,
            seenProcessing = transaction.status.lowercase() in setOf("processing", "partial"),
            deadline = System.currentTimeMillis() + WATCH_CAP_MS,
            language = { client.language },
            schedule = PollSchedule.fixed(intervalMs),
            authUrl = "$API_URL/pay/broadcasting/auth",
            authHeaders = emptyMap(),
        )
        emitAll(watcher.events().toPaymentResults(transaction))
    }
}
