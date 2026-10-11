package com.wajub.mobile.realtime

import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.PrivateChannelEventListener
import com.pusher.client.channel.PusherEvent
import com.pusher.client.util.HttpAuthorizer
import com.wajub.mobile.API_URL
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.model.EchoConfig
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SessionData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.reflect.KClass

/**
 * Pusher/Reverb for speed, with polling running next to it for the whole watch:
 * a socket only carries what happens after it subscribed, and a connected one
 * can still miss an event.
 */
class RealtimeSubscriber(
    private val client: PayClient,
    private val token: String,
    private val session: SessionData,
    private val intervalMs: Long = 5_000L,
) {
    /** Signs the private channel. Tests point it at a local server. */
    internal var authEndpoint: String = "$API_URL/pay/broadcasting/auth"

    fun subscribe(): Flow<PaymentResult> {
        val echo = session.echo ?: return StatusPoller(client, token, intervalMs).poll()
        return subscribePusher(echo)
    }

    private fun subscribePusher(echo: EchoConfig): Flow<PaymentResult> = callbackFlow {
        var pusher: Pusher? = null

        // Two sources for one status: a result goes out when it changes, and
        // nothing follows a final one, which ends the watch.
        val lock = Any()
        var lastKind: KClass<out PaymentResult>? = null
        var settled = false

        fun deliver(result: PaymentResult) {
            val final = result is PaymentResult.Complete || result is PaymentResult.Failed
            synchronized(lock) {
                if (settled || result::class == lastKind) {
                    return
                }
                lastKind = result::class
                settled = final
                trySend(result)
            }
            if (final) {
                close()
            }
        }

        // Not stopped when the socket connects.
        val pollingJob = launch {
            StatusPoller(client, token, intervalMs).poll().collect { deliver(it) }
        }

        try {
            val wsHost = echo.wsHost.removePrefix("wss://").removePrefix("ws://")
            // `channel_prefix` is `private-payment.` — this status channel is
            // private, so the Pusher client must authorize it by proving
            // possession of this session's own bearer token, via the
            // session-scoped authorizer endpoint (mirrors checkout `realtime.ts`
            // and the React Native SDK's `statusSubscriber.ts`).
            val authorizer = HttpAuthorizer(authEndpoint).apply {
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
            // The API publishes on the payment's Wajub id and its authorizer
            // signs that channel only. `reference` is the merchant's own:
            // nothing is published there.
            val channelName = "${echo.channelPrefix}${session.transaction.id}"
            val channel = pusher!!.subscribePrivate(channelName)

            // A private channel only takes a PrivateChannelEventListener: with a
            // plain SubscriptionEventListener `bind` throws, and the socket was
            // never connected.
            channel.bind("PaymentStatusUpdated", object : PrivateChannelEventListener {
                override fun onEvent(event: PusherEvent) {
                    val data = JSONObject(event.data)
                    if (data.has("status")) {
                        deliver(PaymentMapper.mapStatusToResult(data.getString("status"), session.transaction))
                    }
                }

                override fun onSubscriptionSucceeded(channelName: String) = Unit

                override fun onAuthenticationFailure(message: String?, e: Exception?) = Unit
            })

            pusher!!.connect()
        } catch (_: Exception) {
            // No socket: the polling above carries the watch on its own.
        }

        awaitClose {
            pollingJob.cancel()
            try {
                pusher?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
