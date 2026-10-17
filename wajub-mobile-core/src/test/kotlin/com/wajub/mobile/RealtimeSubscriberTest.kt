package com.wajub.mobile

import com.wajub.mobile.client.PayClient
import com.wajub.mobile.model.EchoConfig
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.realtime.RealtimeSubscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The payment's Wajub id and the merchant's reference: two different values on
// every real session, so a test that mixes them up fails.
private const val PAYMENT_ID = "pay_9f3c"
private const val MERCHANT_REFERENCE = "ORDER-1042"
private const val CHANNEL = "private-payment.$PAYMENT_ID"

/**
 * The real Pusher client against a local server that speaks its protocol: the
 * socket connects and the private channel is authorized, as against production
 * Reverb. `/pay/session` answers the polling with [status].
 */
class RealtimeSubscriberTest {
    private lateinit var server: MockWebServer
    private lateinit var subscriber: RealtimeSubscriber

    private val status = AtomicReference("processing")
    private val socket = AtomicReference<WebSocket?>(null)
    private val subscribedChannels = LinkedBlockingQueue<String>()
    private val authRequests = LinkedBlockingQueue<RecordedRequest>()

    @BeforeTest
    fun setUp() {
        val reverb = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket.set(webSocket)
                val data = JSONObject().put("socket_id", "1.1").put("activity_timeout", 120).toString()
                webSocket.send(JSONObject().put("event", "pusher:connection_established").put("data", data).toString())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("event") == "pusher:subscribe") {
                    val channel = frame.getJSONObject("data").getString("channel")
                    webSocket.send(
                        JSONObject()
                            .put("event", "pusher_internal:subscription_succeeded")
                            .put("channel", channel)
                            .put("data", "{}")
                            .toString(),
                    )
                    subscribedChannels.put(channel)
                }
            }
        }

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/app/") -> MockResponse().withWebSocketUpgrade(reverb)
                    path == "/pay/broadcasting/auth" -> {
                        authRequests.put(request)
                        MockResponse().setResponseCode(200).setBody("""{"auth":"app_key:signature"}""")
                    }
                    else -> MockResponse().setResponseCode(200).setBody(
                        """{"transaction":{"id":"$PAYMENT_ID","reference":"$MERCHANT_REFERENCE","amount":1000,"currency":"XAF","status":"${status.get()}"},"channels":[]}""",
                    )
                }
            }
        }
        server.start()

        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(request.newBuilder().url(url).build())
            }
            .build()
        val session = SessionData(
            transaction = SessionTransaction(
                id = PAYMENT_ID,
                reference = MERCHANT_REFERENCE,
                amount = 1000.0,
                currency = "XAF",
                status = "processing",
            ),
            channels = emptyList(),
            echo = EchoConfig(
                key = "app_key",
                wsHost = "ws://${server.hostName}",
                wsPort = server.port,
                channelPrefix = "private-payment.",
                broadcaster = "pusher",
            ),
        )
        subscriber = RealtimeSubscriber(PayClient(http), "tok", session, intervalMs = 50L)
        subscriber.authEndpoint = server.url("/pay/broadcasting/auth").toString()
    }

    @AfterTest
    fun tearDown() {
        // An open socket keeps the server from shutting down.
        socket.get()?.close(1000, null)
        server.shutdown()
    }

    /** Collects the watch on another thread; the channel closes when the watch ends. */
    private fun kotlinx.coroutines.CoroutineScope.watch(): Channel<PaymentResult> {
        val results = Channel<PaymentResult>(Channel.UNLIMITED)
        launch(Dispatchers.Default) {
            subscriber.subscribe().collect { results.send(it) }
            results.close()
        }
        return results
    }

    private fun awaitSubscription(): String =
        assertNotNull(subscribedChannels.poll(10, TimeUnit.SECONDS), "the socket never subscribed")

    // Sends [statusOnTheWire] until the watch ends on it; returns every result seen
    // meanwhile. On a loaded machine the local server's first frame can reach the
    // client before it has finished subscribing.
    private suspend fun broadcastUntilAnswered(results: Channel<PaymentResult>, statusOnTheWire: String): List<PaymentResult> {
        val seen = mutableListOf<PaymentResult>()
        repeat(10) {
            broadcast(statusOnTheWire)
            val ended = kotlinx.coroutines.withTimeoutOrNull(1_000) {
                while (true) {
                    val result = results.receive()
                    seen += result
                    if (result !is PaymentResult.Processing) break
                }
                true
            }
            if (ended == true) return seen
        }
        return seen
    }

    private fun broadcast(statusOnTheWire: String) {
        val data = JSONObject().put("status", statusOnTheWire).put("uid", PAYMENT_ID).toString()
        socket.get()!!.send(
            JSONObject().put("event", "PaymentStatusUpdated").put("channel", CHANNEL).put("data", data).toString(),
        )
    }

    @Test
    fun theRealPusherClientReportsTheSubscriptionAndTheEvents() {
        val connected = java.util.concurrent.CountDownLatch(1)
        val events = LinkedBlockingQueue<String>()
        val connection = com.wajub.mobile.realtime.PusherConnector.open(
            com.wajub.mobile.realtime.RealtimeEndpoint("app_key", server.hostName, server.port, tls = false, channelPrefix = "private-payment."),
            CHANNEL,
            server.url("/pay/broadcasting/auth").toString(),
            mapOf("Authorization" to "Bearer tok"),
            object : com.wajub.mobile.realtime.RealtimeListener {
                override fun onConnected() = connected.countDown()

                override fun onDisconnected() = Unit

                override fun onEvent(data: String) {
                    events.put(data)
                }
            },
        )
        try {
            // Told to the listener given when subscribing (the client tells no one else).
            assertTrue(connected.await(10, TimeUnit.SECONDS), "subscription success reached the listener")
            broadcast("succeeded")
            assertTrue(assertNotNull(events.poll(10, TimeUnit.SECONDS)).contains("succeeded"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun subscribesOnThePaymentIdNotOnTheMerchantReference(): Unit = runBlocking {
        val results = watch()

        assertEquals(CHANNEL, awaitSubscription())

        // The API signs `private-payment.{payment id}` and nothing else.
        val auth = assertNotNull(authRequests.poll(10, TimeUnit.SECONDS))
        assertEquals("Bearer tok", auth.getHeader("Authorization"))
        assertTrue(auth.body.readUtf8().contains("channel_name=$CHANNEL"))

        assertIs<PaymentResult.Complete>(broadcastUntilAnswered(results, "succeeded").lastOrNull())
        assertTrue(withTimeout(10_000) { results.receiveCatching() }.isClosed)
    }

    @Test
    fun keepsPollingNextToAConnectedSocket(): Unit = runBlocking {
        val results = watch()
        awaitSubscription()

        // The first poll's answer (on a loaded machine it can come after the subscription).
        val first = withTimeout(10_000) { results.receive() }

        // Connected and subscribed, and the socket says nothing: the payment
        // succeeds and only `/pay/session` knows.
        status.set("succeeded")

        withTimeout(10_000) {
            val seen = mutableListOf(first)
            for (result in results) seen += result
            assertEquals(2, seen.size, "one result per status: $seen")
            assertIs<PaymentResult.Processing>(seen[0])
            assertIs<PaymentResult.Complete>(seen[1])
        }
    }

    @Test
    fun reportsEachStatusOnceWhenBothSourcesSeeIt(): Unit = runBlocking {
        val results = watch()
        awaitSubscription()

        withTimeout(10_000) {
            // The polling has already said `processing`; the socket repeats it.
            assertIs<PaymentResult.Processing>(results.receive())
            broadcast("processing")
        }
        val after = broadcastUntilAnswered(results, "succeeded")
        assertEquals(1, after.size, "no second processing: $after")
        assertIs<PaymentResult.Complete>(after.single())
        withTimeout(10_000) {
            // A final result ends the watch, polling included.
            assertTrue(results.receiveCatching().isClosed)
        }
    }
}
