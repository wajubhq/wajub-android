package com.wajub.mobile.realtime

import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.SubscriptionEventListener
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import com.pusher.client.util.HttpAuthorizer
import com.wajub.mobile.API_URL
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.model.EchoConfig
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SessionData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

private val terminalStatuses = setOf(
    "success", "successful", "succeeded", "paid", "complete",
    "failed", "cancelled", "expired",
)

/**
 * Pusher/Reverb subscription with polling fallback — mirrors checkout `realtime.ts`.
 */
class RealtimeSubscriber(
    private val client: PayClient,
    private val token: String,
    private val session: SessionData,
    private val intervalMs: Long = 5_000L,
) {
    fun subscribe(): Flow<PaymentResult> {
        val echo = session.echo ?: return StatusPoller(client, token, intervalMs).poll()
        return subscribePusher(echo)
    }

    private fun subscribePusher(echo: EchoConfig): Flow<PaymentResult> = callbackFlow {
        val reference = session.transaction.reference
        var pollingJob: kotlinx.coroutines.Job? = null
        var pusher: Pusher? = null

        fun emitStatus(status: String) {
            val result = PaymentMapper.mapStatusToResult(status, session.transaction)
            trySend(result)
            if (status.lowercase() in terminalStatuses) {
                close()
            }
        }

        suspend fun startPolling() {
            if (pollingJob?.isActive == true) {
                return
            }
            pollingJob = launch {
                StatusPoller(client, token, intervalMs).poll().collect { result ->
                    trySend(result)
                    if (result is PaymentResult.Complete || result is PaymentResult.Failed) {
                        close()
                    }
                }
            }
        }

        try {
            val wsHost = echo.wsHost.removePrefix("wss://").removePrefix("ws://")
            // `channel_prefix` is `private-payment.` — this status channel is
            // private, so the Pusher client must authorize it by proving
            // possession of this session's own bearer token, via the
            // session-scoped authorizer endpoint (mirrors checkout `realtime.ts`
            // and the React Native SDK's `statusSubscriber.ts`).
            val authorizer = HttpAuthorizer("$API_URL/pay/broadcasting/auth").apply {
                setHeaders(mapOf("Authorization" to "Bearer $token"))
            }
            val options = PusherOptions().apply {
                setHost(wsHost)
                setWsPort(echo.wsPort)
                setWssPort(echo.wsPort)
                isUseTLS = echo.wsHost.startsWith("wss://") || echo.wsPort == 443
                setAuthorizer(authorizer)
            }
            pusher = Pusher(echo.key, options)
            val channelName = "${echo.channelPrefix}$reference"
            val channel = pusher!!.subscribePrivate(channelName)

            channel.bind("PaymentStatusUpdated", SubscriptionEventListener { event ->
                val data = JSONObject(event.data)
                if (data.has("status")) {
                    emitStatus(data.getString("status"))
                }
            })

            pusher!!.connect(object : ConnectionEventListener {
                override fun onConnectionStateChange(change: ConnectionStateChange) {
                    if (change.currentState == ConnectionState.CONNECTED) {
                        pollingJob?.cancel()
                        pollingJob = null
                    }
                }

                override fun onError(message: String?, code: String?, e: Exception?) = Unit
            }, ConnectionState.ALL)

            launch {
                delay(10_000)
                if (pusher?.connection?.state != ConnectionState.CONNECTED) {
                    startPolling()
                }
            }
        } catch (_: Exception) {
            launch { startPolling() }
        }

        awaitClose {
            pollingJob?.cancel()
            try {
                pusher?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
