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

/**
 * `PresentOptions.realtime`: another host for the socket (a self-hosted Reverb
 * or Soketi behind your own hostname), or no socket at all.
 */
data class RealtimeOptions(
    /** `false`: polling only. */
    val enabled: Boolean = true,
    val host: String? = null,
    val port: Int? = null,
    val tls: Boolean? = null,
)

/** Where the socket connects: `echo` of the session, with [RealtimeOptions] on top. */
data class RealtimeEndpoint(
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
         * the port. Null — polling only — without `echo`, without a key, or
         * when [options] turn realtime off.
         */
        fun resolve(echo: EchoConfig?, options: RealtimeOptions? = null): RealtimeEndpoint? {
            if (options?.enabled == false) return null
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
                host = options?.host?.takeIf { it.isNotBlank() } ?: host,
                port = options?.port ?: echo.wsPort,
                tls = options?.tls ?: tls,
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
        // A private channel only takes a PrivateChannelEventListener: with a
        // plain SubscriptionEventListener `bind` throws.
        pusher.subscribePrivate(channel).bind(EVENT, object : PrivateChannelEventListener {
            override fun onEvent(event: PusherEvent) {
                event.data?.let { listener.onEvent(it) }
            }

            override fun onSubscriptionSucceeded(channelName: String) = listener.onConnected()

            override fun onAuthenticationFailure(message: String?, e: Exception?) = listener.onDisconnected()
        })
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
