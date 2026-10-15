package com.wajub.mobile.realtime

import com.wajub.mobile.API_URL
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.SessionTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

/**
 * Pusher/Reverb for speed, with polling running next to it for the whole watch:
 * a socket only carries what happens after it subscribed, and a connected one
 * can still miss an event. Emits when the result changes; ends on a final one.
 */
@Deprecated("Use WajubSession.watch(), which reports every decision of the spec.")
class RealtimeSubscriber(
    private val client: PayClient,
    private val token: String,
    private val session: SessionData,
    private val intervalMs: Long = 5_000L,
) {
    /** Signs the private channel. Tests point it at a local server. */
    internal var authEndpoint: String = "$API_URL/pay/broadcasting/auth"

    fun subscribe(): Flow<PaymentResult> {
        val transaction = session.transaction
        val watcher = PaymentWatcher(
            client = client,
            token = token,
            transactionId = transaction.id,
            endpoint = RealtimeEndpoint.resolve(session.echo),
            seenProcessing = transaction.status.lowercase() in setOf("processing", "partial"),
            // 1.x watched until a final status: no cap.
            deadline = Long.MAX_VALUE,
            language = { client.language },
            schedule = PollSchedule.fixed(intervalMs),
            authUrl = authEndpoint,
            authHeaders = mapOf("Authorization" to "Bearer $token"),
            legacyErrors = true,
        )
        return watcher.events().toPaymentResults(transaction)
    }
}

/**
 * The 1.x view of a watch: one [PaymentResult] per change, the last one final.
 * A `failed`, `cancelled` or `expired` status gives the 1.x error (its code is
 * the status); the endings 1.x never reached (an attempt that ended without
 * charging, a session gone) give the v2 error.
 */
internal fun Flow<WatchEvent>.toPaymentResults(transaction: SessionTransaction): Flow<PaymentResult> =
    mapNotNull { event ->
        fun status(value: String) = PaymentMapper.mapStatusToResult(value, transaction.copy(status = value))
        when (event) {
            is WatchEvent.StatusChanged -> PaymentResult.Processing(transaction.copy(status = event.status.status))
            is WatchEvent.Succeeded -> PaymentResult.Complete(transaction.copy(status = event.status.status))
            is WatchEvent.Failed ->
                if (event.status?.status == "failed") status("failed") else PaymentResult.Failed(event.error, transaction)
            is WatchEvent.Cancelled -> status("cancelled")
            is WatchEvent.Expired ->
                if (event.error.code == "session_expired") status("expired") else PaymentResult.Failed(event.error, transaction)
            WatchEvent.StillProcessing -> null
        }
    }.distinctUntilChanged { old, new -> old::class == new::class }
