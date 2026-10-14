package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.realtime.PaymentWatcher
import com.wajub.mobile.realtime.PusherConnector
import com.wajub.mobile.realtime.RealtimeEndpoint
import com.wajub.mobile.realtime.RealtimeOptions
import com.wajub.mobile.realtime.WatchEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchingContractTest {
    private fun session(name: String): SessionData = RawSessionResponse.fromJson(Fixtures.body(name)).toModel()

    // Watches [data] in virtual time; events land in the returned list.
    private fun TestScope.watch(
        api: FakeApi,
        data: SessionData = session("session-sandbox-cm"),
        seenProcessing: Boolean = true,
        socket: FakeSocket = FakeSocket(),
        realtime: RealtimeOptions? = null,
    ): Pair<PaymentWatcher, MutableList<WatchEvent>> {
        api.clock = { testScheduler.currentTime }
        val watcher = fakeSession(api).watcher(data, realtime, seenProcessing, { testScheduler.currentTime }, Random(5), socket)
        val events = mutableListOf<WatchEvent>()
        backgroundScope.launch { watcher.events().collect { events += it } }
        runCurrent()
        return watcher to events
    }

    private fun gaps(times: List<Long>): List<Long> = times.zipWithNext { a, b -> b - a }

    @Test
    fun `WT-01 polls on schedule`() = runTest {
        // 5 s, then ×1.5 per poll up to 15 s once 2 minutes passed without a change.
        val api = FakeApi().on("GET /pay/status", Fixtures.response("status-processing"))
        val (_, events) = watch(api)
        advanceTimeBy(180_000)
        val gaps = gaps(api.times)
        assertTrue(gaps.takeWhile { it == 5_000L }.size >= 23, "every 5 s for 2 minutes: $gaps")
        assertTrue(gaps.contains(7_500L) && gaps.contains(11_250L), "slowing ×1.5: $gaps")
        assertEquals(15_000L, gaps.last(), "up to 15 s")
        assertEquals(1, events.size, "one event per change")

        // Connected socket: 15 s.
        val withSocket = FakeApi().on("GET /pay/status", Fixtures.response("status-processing"))
        val socket = FakeSocket()
        watch(withSocket, session("session-cm-aggregate"), socket = socket)
        assertNotNull(socket.listener).onConnected()
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(listOf(5_000L, 15_000L, 15_000L, 15_000L), gaps(withSocket.times).take(4))

        // Errors: min(5 s × 2^n, 60 s) ± 20 %, reset on success.
        val flaky = FakeApi().on(
            "GET /pay/status",
            FakeResponse.status(503), FakeResponse.network(), FakeResponse.timeout(), FakeResponse.status(500), FakeResponse.status(502),
            Fixtures.response("status-processing"),
        )
        watch(flaky)
        advanceTimeBy(300_000)
        val errorGaps = gaps(flaky.times).take(6)
        val expected = listOf(10_000L, 20_000L, 40_000L, 60_000L, 60_000L)
        expected.forEachIndexed { i, base ->
            assertTrue(errorGaps[i] in (base * 0.8).toLong()..(base * 1.2).toLong(), "error delay $i: ${errorGaps[i]} for $base")
        }
        assertEquals(5_000L, errorGaps[5], "back to 5 s after a success")

        // 15 minutes without an outcome: still processing.
        val endless = FakeApi().on("GET /pay/status", Fixtures.response("status-processing"))
        val (_, capped) = watch(endless)
        advanceTimeBy(900_001)
        runCurrent()
        assertEquals(WatchEvent.StillProcessing, capped.last())

        // The sheet then says "you will be notified", and closing resolves processing.
        val api2 = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, recorder) = checkout(api2)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        runCurrent()
        advanceTimeBy(900_001)
        runCurrent()
        assertTrue(assertIs<SheetScreen.Pending>(controller.screen.value).capped)
        controller.close()
        assertIs<CheckoutResult.Processing>(recorder.result)
    }

    @Test
    fun `WT-02 decides on status`() = runTest {
        suspend fun TestScope.decide(vararg fixtures: String, seen: Boolean = true): List<WatchEvent> {
            val api = FakeApi().on("GET /pay/status", *fixtures.map { Fixtures.response(it) }.toTypedArray())
            val (_, events) = watch(api, seenProcessing = seen)
            advanceTimeBy(5_000L * fixtures.size + 1)
            runCurrent()
            return events
        }
        assertIs<WatchEvent.Succeeded>(decide("status-succeeded").last())

        for (fixture in listOf("status-pending-attempt-refused", "status-pending-attempt-refused-live")) {
            val failed = assertIs<WatchEvent.Failed>(decide(fixture).last(), fixture)
            assertEquals("insufficient_funds", failed.error.code)
            assertTrue(failed.error.retryable)
            assertEquals("Insufficient funds.", failed.error.message)
            assertEquals(
                "This payment could not be completed. Try again or use another payment method.",
                failed.error.apiMessage,
            )
        }
        val noCode = decide("status-processing", "status-pending-attempt-refused")
        assertIs<WatchEvent.Failed>(noCode.last())

        // A pending seen before this attempt is a leftover: keep watching.
        val leftover = decide("status-pending-attempt-refused", "status-pending-attempt-refused", seen = false)
        assertTrue(leftover.none { it.isFinal }, "$leftover")
        val learned = decide("status-pending-attempt-refused", "status-processing", "status-pending-attempt-refused", seen = false)
        assertEquals("insufficient_funds", assertIs<WatchEvent.Failed>(learned.last()).error.code)

        val plainPending = FakeApi().on("GET /pay/status", FakeResponse.status(200, """{"transaction":{"status":"pending"}}"""))
        val (_, ended) = watch(plainPending)
        assertEquals("payment_attempt_ended", assertIs<WatchEvent.Failed>(ended.last()).error.code)

        assertTrue(decide("status-processing").none { it.isFinal })
        val terminalStatuses = mapOf("failed" to "payment_failed", "cancelled" to "payment_cancelled", "expired" to "session_expired")
        for ((status, code) in terminalStatuses) {
            val api = FakeApi().on("GET /pay/status", FakeResponse.status(200, """{"transaction":{"status":"$status"}}"""))
            val (_, events) = watch(api)
            val last = events.last()
            val error = when (last) {
                is WatchEvent.Failed -> last.error
                is WatchEvent.Cancelled -> last.error
                is WatchEvent.Expired -> last.error
                else -> error("$status: $last")
            }
            assertEquals(code, error.code)
        }
        for (paid in listOf("refunded", "partially_refunded")) {
            val api = FakeApi().on("GET /pay/status", FakeResponse.status(200, """{"transaction":{"status":"$paid"}}"""))
            assertIs<WatchEvent.Succeeded>(watch(api).second.last())
        }
        assertEquals("session_terminal_expired", assertIs<WatchEvent.Expired>(decide("error-410-session-terminal-expired").last()).error.code)
        assertEquals("session_not_found", assertIs<WatchEvent.Failed>(decide("error-401-invalid-token").last()).error.code)

        // A 2xx without a status says nothing: no event, never a refusal.
        for (empty in listOf("{}", """{"transaction":{}}""", """{"transaction":{"status":null}}""")) {
            val api = FakeApi().on("GET /pay/status", FakeResponse.status(200, empty))
            val (_, events) = watch(api)
            advanceTimeBy(20_001)
            assertTrue(events.isEmpty(), "$empty: $events")
            assertEquals(5, api.count("GET /pay/status"), "still watching")
        }
        val socket = FakeSocket()
        val (_, socketEvents) = watch(FakeApi().on("GET /pay/status", Fixtures.response("status-processing")), session("session-cm-aggregate"), socket = socket)
        socket.listener!!.onEvent("""{"status_label":"Failed","uid":"trx_1"}""")
        runCurrent()
        assertTrue(socketEvents.none { it.isFinal }, "a frame without a status decides nothing")
    }

    @Test
    fun `WT-03 subscribes to the payment channel`() = runTest {
        val reverb = assertNotNull(RealtimeEndpoint.resolve(session("session-cm-aggregate").echo))
        assertEquals(RealtimeEndpoint("wajub-app-key", "ws.wajub.com", 443, true, "private-payment."), reverb)
        val pusher = assertNotNull(RealtimeEndpoint.resolve(session("session-ci-xof-wallets").echo))
        assertEquals("ws-eu.pusher.com", pusher.host)
        assertTrue(pusher.tls)
        val custom = RealtimeEndpoint.resolve(session("session-cm-aggregate").echo, RealtimeOptions(host = "reverb.shop.example.com", port = 6001, tls = false))
        assertEquals(RealtimeEndpoint("wajub-app-key", "reverb.shop.example.com", 6001, false, "private-payment."), custom)
        val plain = session("session-cm-aggregate").echo!!.copy(wsHost = "ws://reverb.local", wsPort = 8080)
        assertEquals(false, RealtimeEndpoint.resolve(plain)?.tls)

        val socket = FakeSocket()
        val api = FakeApi().on("GET /pay/status", Fixtures.response("status-processing"))
        val (_, events) = watch(api, session("session-cm-aggregate"), socket = socket)
        assertEquals(reverb, socket.endpoint)
        assertEquals("private-payment.trx_PVrU8x2kQ1mNa7Lb3cDe", socket.channel, "the payment's id, not the merchant's reference")
        assertEquals("https://api.wajub.com/pay/broadcasting/auth", socket.authUrl)
        assertEquals("Bearer test_tok", socket.authHeaders["Authorization"])
        assertEquals("PaymentStatusUpdated", PusherConnector.EVENT)
        socket.listener!!.onEvent(Fixtures.body("realtime-payment-status-updated-succeeded").toString())
        runCurrent()
        assertIs<WatchEvent.Succeeded>(events.last())
        assertTrue(socket.closed, "the socket closes once decided")
    }

    @Test
    fun `WT-04 falls back to polling`() = runTest {
        // echo key null (sandbox): no socket.
        val socket = FakeSocket()
        val api = FakeApi().on("GET /pay/status", Fixtures.response("status-processing"))
        watch(api, session("session-sandbox-cm"), socket = socket)
        advanceTimeBy(20_001)
        assertNull(socket.channel)
        assertEquals(listOf(5_000L, 5_000L, 5_000L, 5_000L), gaps(api.times))

        // echo absent, or realtime turned off.
        val noEcho = session("session-cm-aggregate").copy(echo = null)
        val absent = FakeSocket()
        watch(FakeApi().on("GET /pay/status", Fixtures.response("status-processing")), noEcho, socket = absent)
        assertNull(absent.channel)
        val off = FakeSocket()
        watch(FakeApi().on("GET /pay/status", Fixtures.response("status-processing")), session("session-cm-aggregate"), socket = off, realtime = RealtimeOptions(enabled = false))
        assertNull(off.channel)

        // A socket that never connects: polling stays at 5 s.
        val silent = FakeSocket()
        val silentApi = FakeApi().on("GET /pay/status", Fixtures.response("status-processing"))
        watch(silentApi, session("session-cm-aggregate"), socket = silent)
        advanceTimeBy(30_000)
        assertNotNull(silent.channel)
        assertTrue(gaps(silentApi.times).all { it == 5_000L })
    }

    @Test
    fun `WT-05 decides on realtime events`() = runTest {
        for ((fixture, expected) in listOf(
            "realtime-payment-status-updated-succeeded" to WatchEvent.Succeeded::class,
            "realtime-payment-status-updated-failed" to WatchEvent.Failed::class,
            "realtime-payment-status-updated-refused" to WatchEvent.Failed::class,
            "realtime-payment-status-updated-processing" to WatchEvent.StatusChanged::class,
        )) {
            for (asString in listOf(true, false)) {
                val socket = FakeSocket()
                val api = FakeApi().on("GET /pay/status", FakeResponse.status(200, """{"transaction":{"status":"pending"}}"""))
                val (_, events) = watch(api, session("session-cm-aggregate"), seenProcessing = false, socket = socket)
                // The data arrives as a JSON string, or a string holding one.
                val data = Fixtures.body(fixture).toString()
                socket.listener!!.onEvent(if (asString) data else JSONObject.quote(data))
                runCurrent()
                if (fixture.endsWith("refused")) {
                    // Refused before this watch saw processing: a leftover.
                    assertTrue(events.none { it.isFinal })
                    socket.listener!!.onEvent(Fixtures.body("realtime-payment-status-updated-processing").toString())
                    socket.listener!!.onEvent(data)
                    runCurrent()
                    assertEquals("insufficient_funds", assertIs<WatchEvent.Failed>(events.last()).error.code)
                } else {
                    assertEquals(expected, events.last()::class, "$fixture string=$asString")
                }
            }
        }
    }

    @Test
    fun `WT-06 stops watching on close`() = runTest {
        val socket = FakeSocket()
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, recorder) = checkout(api, socket = socket)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(CheckoutState.APPROVAL_REQUIRED, controller.state)
        val polls = api.count("GET /pay/status")
        assertNotNull(socket.channel)
        controller.dismiss()
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(polls, api.count("GET /pay/status"), "no poll after closing")
        assertTrue(socket.closed)
        assertIs<CheckoutResult.Processing>(recorder.result)
    }
}
