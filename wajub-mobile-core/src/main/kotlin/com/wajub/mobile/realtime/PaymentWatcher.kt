package com.wajub.mobile.realtime

import com.wajub.mobile.client.ApiCall
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.client.TransportException
import com.wajub.mobile.mapper.OutcomeMapper
import com.wajub.mobile.model.ErrorCatalog
import com.wajub.mobile.model.PaymentStatus
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubErrorType
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

/** What watching a payment reports (spec §13). */
sealed class WatchEvent {
    /** A status that decides nothing yet: `processing`, `partial`, or a `pending` left by an earlier attempt. */
    data class StatusChanged(val status: PaymentStatus) : WatchEvent()

    /** Paid (`succeeded`, `refunded`, `partially_refunded`). */
    data class Succeeded(val status: PaymentStatus) : WatchEvent()

    /**
     * Not paid: the payment failed (`payment_failed`, final), this attempt
     * ended without charging (retryable — choose a method again), or the
     * session is gone (`session_not_found`).
     */
    data class Failed(val error: WajubError, val status: PaymentStatus? = null) : WatchEvent()

    /** Cancelled: refused on the phone, or cancelled elsewhere (`payment_cancelled`). */
    data class Cancelled(val error: WajubError) : WatchEvent()

    /** Expired: an approval nobody answered (`session_expired`), or a 410 (`session_terminal_expired`). */
    data class Expired(val error: WajubError) : WatchEvent()

    /** The watch reached its cap (15 minutes, or `expires_at`) without an outcome: still processing. */
    data object StillProcessing : WatchEvent()

    val isFinal: Boolean get() = this !is StatusChanged
}

/** Polling intervals of a watch (spec §13.2, all in milliseconds). */
internal data class PollSchedule(
    val intervalMs: Long = 5_000L,
    val slowAfterMs: Long = 120_000L,
    val slowFactor: Double = 1.5,
    val maxIntervalMs: Long = 15_000L,
    val withSocketMs: Long = 15_000L,
    val errorBaseMs: Long = 5_000L,
    val errorMaxMs: Long = 60_000L,
    val errorJitter: Double = 0.2,
) {
    companion object {
        /** The 1.x `watchStatus(intervalMs)`: one fixed interval, socket or not. */
        fun fixed(intervalMs: Long) = PollSchedule(
            intervalMs = intervalMs,
            maxIntervalMs = intervalMs,
            withSocketMs = intervalMs,
        )
    }
}

/**
 * Watches a payment until it decides: `GET /pay/status` on a schedule, and
 * the Pusher/Reverb socket next to it when the session has one — a socket
 * only carries what happens after it subscribed, and can drop an event.
 * Collect [events] to start; stop collecting to stop (the socket closes).
 */
class PaymentWatcher internal constructor(
    private val client: PayClient,
    private val token: String,
    private val transactionId: String,
    private val endpoint: RealtimeEndpoint?,
    seenProcessing: Boolean,
    private val deadline: Long,
    private val language: () -> WajubLanguage,
    private val schedule: PollSchedule = PollSchedule(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val connector: RealtimeConnector = PusherConnector,
    private val authUrl: String,
    private val authHeaders: Map<String, String>,
) {
    private sealed class Signal {
        data object Wake : Signal()

        data class Event(val status: PaymentStatus) : Signal()

        data class Socket(val connected: Boolean) : Signal()
    }

    private val signals = Channel<Signal>(Channel.UNLIMITED)

    @Volatile
    private var seenProcessing: Boolean = seenProcessing

    /** A new attempt of this payer is under way: a `pending` from now on means it ended. */
    internal fun markSeenProcessing() {
        seenProcessing = true
    }

    /** Polls at once (the payer came back from a browser, or the app to the foreground). */
    fun pollNow() {
        signals.trySend(Signal.Wake)
    }

    /** The watch's events; the flow ends after a final one. */
    fun events(): Flow<WatchEvent> = channelFlow {
        val connection = endpoint?.let { ep ->
            runCatching {
                connector.open(
                    ep,
                    ep.channelPrefix + transactionId,
                    authUrl,
                    authHeaders,
                    object : RealtimeListener {
                        override fun onConnected() {
                            signals.trySend(Signal.Socket(true))
                        }

                        override fun onDisconnected() {
                            signals.trySend(Signal.Socket(false))
                        }

                        override fun onEvent(data: String) {
                            OutcomeMapper.event(data)?.let { signals.trySend(Signal.Event(it)) }
                        }
                    },
                )
            }.getOrNull()
        }
        try {
            var socketConnected = false
            var lastStatus: String? = null
            var lastChangeAt = clock()
            var interval = schedule.intervalMs
            var failures = 0
            var nextPollAt = clock()

            // Sends the event of [status]; true when it ends the watch.
            suspend fun handle(status: PaymentStatus): Boolean {
                val event = decide(status)
                if (event != null) {
                    send(event)
                    return true
                }
                if (status.status != lastStatus) {
                    lastStatus = status.status
                    lastChangeAt = clock()
                    interval = schedule.intervalMs
                    send(WatchEvent.StatusChanged(status))
                }
                return false
            }

            while (true) {
                val now = clock()
                if (now >= deadline) {
                    send(WatchEvent.StillProcessing)
                    return@channelFlow
                }
                if (now >= nextPollAt) {
                    var wait: Long
                    try {
                        val response = client.sendRaw(token, ApiCall.StatusPoll)
                        when {
                            response.isSuccess -> {
                                failures = 0
                                if (handle(OutcomeMapper.status(response.body))) return@channelFlow
                                wait = if (socketConnected) {
                                    schedule.withSocketMs
                                } else {
                                    if (clock() - lastChangeAt >= schedule.slowAfterMs) {
                                        interval = min((interval * schedule.slowFactor).toLong(), schedule.maxIntervalMs)
                                    }
                                    interval
                                }
                            }
                            response.status == 401 || response.status == 403 || response.status == 404 -> {
                                send(WatchEvent.Failed(WajubError.of("session_not_found", language())))
                                return@channelFlow
                            }
                            response.status == 410 -> {
                                send(WatchEvent.Expired(client.errorOf(response, loading = false)))
                                return@channelFlow
                            }
                            response.status == 429 -> {
                                wait = response.retryAfter?.trim()?.toLongOrNull()?.times(1000) ?: errorDelay(++failures)
                            }
                            else -> wait = errorDelay(++failures)
                        }
                    } catch (_: TransportException) {
                        wait = errorDelay(++failures)
                    }
                    nextPollAt = clock() + wait
                    continue
                }
                when (val signal = withTimeoutOrNull(min(nextPollAt, deadline) - now) { signals.receive() }) {
                    null -> Unit
                    Signal.Wake -> nextPollAt = clock()
                    is Signal.Socket -> {
                        socketConnected = signal.connected
                        // Connected: the next poll can wait longer; lost: back to the base interval.
                        if (!signal.connected) nextPollAt = min(nextPollAt, clock() + interval)
                    }
                    is Signal.Event -> if (handle(signal.status)) return@channelFlow
                }
            }
        } finally {
            connection?.close()
        }
    }

    // min(5 s × 2^n, 60 s) ± 20 %, n consecutive failures.
    private fun errorDelay(failures: Int): Long {
        val base = min(schedule.errorBaseMs shl min(failures, 16), schedule.errorMaxMs).toDouble()
        return (base * (1.0 + (random.nextDouble() * 2 - 1) * schedule.errorJitter)).toLong()
    }

    // The final event of [status], or null to keep watching.
    private fun decide(status: PaymentStatus): WatchEvent? {
        val lang = language()
        return when (status.status) {
            in OutcomeMapper.SUCCESS_STATUSES -> WatchEvent.Succeeded(status)
            "failed" -> WatchEvent.Failed(WajubError.of("payment_failed", lang), status)
            "cancelled" -> WatchEvent.Cancelled(WajubError.of("payment_cancelled", lang))
            "expired" -> WatchEvent.Expired(WajubError.of("session_expired", lang))
            "processing", "partial" -> {
                seenProcessing = true
                null
            }
            // Pending after this attempt was seen processing: it ended without
            // charging. Before that, it is the previous attempt's leftover.
            "pending" -> if (seenProcessing) WatchEvent.Failed(attemptEnded(status, lang), status) else null
            else -> null
        }
    }

    private fun attemptEnded(status: PaymentStatus, language: WajubLanguage): WajubError {
        val code = status.errorCode ?: return WajubError.of("payment_attempt_ended", language)
        val known = ErrorCatalog.entry(code) != null
        val message = when {
            known -> ErrorCatalog.message(code, language, null)!!
            language == WajubLanguage.En && status.payerMessage != null -> status.payerMessage
            else -> ErrorCatalog.genericDecline(language)
        }
        return WajubError(
            type = WajubErrorType.PaymentError,
            message = message,
            code = code,
            declineCode = code,
            retryable = true,
            apiMessage = status.payerMessage,
        )
    }
}
