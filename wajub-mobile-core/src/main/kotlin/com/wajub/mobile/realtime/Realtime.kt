package com.wajub.mobile.realtime

import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.PrivateChannelEventListener
import com.pusher.client.channel.PusherEvent
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import com.pusher.client.util.HttpChannelAuthorizer
import com.wajub.mobile.model.EchoConfig

/** Where the socket connects: the session's `echo`. */
internal data class RealtimeEndpoint(
    val key: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    /** `private-payment.` */
    val channelPrefix: String,
) {
    companion object {
        /**
         * `ws_host` carries the scheme (`wss://` TLS, `ws://` plain), `ws_port`
         * the port. Null — polling only — without `echo` or without a key.
         */
        fun resolve(echo: EchoConfig?): RealtimeEndpoint? {
            if (echo == null || echo.key.isBlank()) return null
            val raw = echo.wsHost.trim()
            val scheme = raw.substringBefore("://", "").lowercase()
            val host = raw.substringAfter("://").substringBefore('/').substringBefore(':')
            val tls = when (scheme) {
                "wss", "https" -> true
                "ws", "http" -> false
                else -> echo.wsPort == 443
            }
            val endpoint = RealtimeEndpoint(
                key = echo.key,
                host = host,
                port = echo.wsPort,
                tls = tls,
                channelPrefix = echo.channelPrefix,
            )
            return endpoint.takeIf { it.host.isNotBlank() }
        }
    }
}

internal interface RealtimeListener {
    /** Subscribed to the payment's channel. */
    fun onConnected()

    fun onDisconnected()

    /** The data of a `PaymentStatusUpdated` event. */
    fun onEvent(data: String)
}

internal fun interface RealtimeConnection {
    fun close()
}

/** Opens the socket of a watch; tests swap in a fake. */
internal fun interface RealtimeConnector {
    fun open(
        endpoint: RealtimeEndpoint,
        channel: String,
        authUrl: String,
        authHeaders: Map<String, String>,
        listener: RealtimeListener,
    ): RealtimeConnection
}

/** The Pusher protocol, spoken by Pusher, Reverb and Soketi alike. */
internal object PusherConnector : RealtimeConnector {
    const val EVENT = "PaymentStatusUpdated"

    override fun open(
        endpoint: RealtimeEndpoint,
        channel: String,
        authUrl: String,
        authHeaders: Map<String, String>,
        listener: RealtimeListener,
    ): RealtimeConnection {
        // The status channel is private: the authorizer proves possession of
        // this session's bearer token (POST /pay/broadcasting/auth).
        val authorizer = HttpChannelAuthorizer(authUrl).apply { setHeaders(authHeaders) }
        val options = PusherOptions().apply {
            setHost(endpoint.host)
            setWsPort(endpoint.port)
            setWssPort(endpoint.port)
            isUseTLS = endpoint.tls
            setChannelAuthorizer(authorizer)
        }
        val pusher = Pusher(endpoint.key, options)
        // A private channel only takes a PrivateChannelEventListener, and the
        // client tells subscription success or failure only to the listener
        // given when subscribing.
        pusher.subscribePrivate(
            channel,
            object : PrivateChannelEventListener {
                override fun onEvent(event: PusherEvent) {
                    event.data?.let { listener.onEvent(it) }
                }

                override fun onSubscriptionSucceeded(channelName: String) = listener.onConnected()

                override fun onAuthenticationFailure(message: String?, e: Exception?) = listener.onDisconnected()
            },
            EVENT,
        )
        pusher.connect(object : ConnectionEventListener {
            override fun onConnectionStateChange(change: ConnectionStateChange) {
                if (change.currentState == ConnectionState.DISCONNECTED) listener.onDisconnected()
            }

            override fun onError(message: String?, code: String?, e: Exception?) = Unit
        }, ConnectionState.ALL)
        return RealtimeConnection {
            runCatching { pusher.disconnect() }
        }
    }
}
