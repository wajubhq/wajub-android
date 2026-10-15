package com.wajub.mobile.realtime

import com.wajub.mobile.API_URL
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.WajubError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * Polling only, every [intervalMs]: emits when the result changes and ends on
 * a final one — a session that is gone (401, 403, 404, 410) included. Errors back
 * off up to a minute.
 */
@Deprecated("Use WajubSession.watch(RealtimeOptions(enabled = false)).")
class StatusPoller(
    private val client: PayClient,
    private val token: String,
    private val intervalMs: Long = 5_000L,
) {
    fun poll(): Flow<PaymentResult> = flow {
        val transaction = sessionTransaction() ?: return@flow
        val watcher = PaymentWatcher(
            client = client,
            token = token,
            transactionId = transaction.id,
            endpoint = null,
            seenProcessing = transaction.status.lowercase() in setOf("processing", "partial"),
            // 1.x watched until a final status: no cap.
            deadline = Long.MAX_VALUE,
            language = { client.language },
            schedule = PollSchedule.fixed(intervalMs),
            authUrl = "$API_URL/pay/broadcasting/auth",
            authHeaders = emptyMap(),
            legacyErrors = true,
        )
        emitAll(watcher.events().toPaymentResults(transaction))
    }

    // As in 1.x, the session is read until it answers; one that is gone ends the flow.
    private suspend fun FlowCollector<PaymentResult>.sessionTransaction(): SessionTransaction? {
        var failures = 0
        while (true) {
            try {
                return client.getSession(token).transaction
            } catch (e: WajubError) {
                if (e.httpStatus in setOf(401, 403, 404, 410)) {
                    emit(PaymentResult.Failed(e))
                    return null
                }
            } catch (_: IOException) {
                // retried below
            }
            failures++
            delay(minOf(intervalMs * failures, 60_000L))
        }
    }
}
