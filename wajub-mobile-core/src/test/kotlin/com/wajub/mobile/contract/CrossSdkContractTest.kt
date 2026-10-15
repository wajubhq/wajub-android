package com.wajub.mobile.contract

import com.wajub.mobile.checkout.ActiveSheet
import com.wajub.mobile.checkout.CancelReason
import com.wajub.mobile.checkout.CheckoutCallbacks
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.realtime.RealtimeConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cross-SDK checklist the four mobile SDKs pass alike (settling, cancel
 * rules, unanswered payments, watching, load, client sessions, Altcha),
 * against the real controller and a fake HTTP layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossSdkContractTest {
    // The host's scope is cancelled first, then hostGone(): what CheckoutViewModel does
    // (viewModelScope is closed before onCleared()).
    class Hosted(val controller: CheckoutController, val recorder: Recorder, val host: Job) {
        fun goAway() {
            host.cancel()
            controller.hostGone()
        }
    }

    private fun TestScope.hosted(
        api: FakeApi,
        options: PresentOptions = PresentOptions(sessionToken = "t", locale = "en"),
        socket: RealtimeConnector = FakeSocket(),
        clear: Boolean = true,
    ): Hosted {
        if (clear) ActiveSheet.clear()
        api.clock = { testScheduler.currentTime }
        val job = Job(backgroundScope.coroutineContext[Job])
        val recorder = Recorder()
        val controller = CheckoutController(
            session = fakeSession(api, clock = { testScheduler.currentTime }),
            options = options.copy(callbacks = recorder.callbacks()),
            scope = CoroutineScope(backgroundScope.coroutineContext + job),
            clock = { testScheduler.currentTime },
            random = Random(3),
            connector = socket,
            compute = StandardTestDispatcher(testScheduler),
            deviceLanguage = { "en-US" },
        )
        controller.urlOpener = { true }
        return Hosted(controller, recorder, job)
    }

    private fun FakeResponse.after(ms: Long) = FakeResponse(status, body, headers, delayMs = ms, failure = failure)

    private fun statusOf(status: String, errorCode: String? = null) = Fixtures.response("status-processing") {
        it.getJSONObject("transaction").put("status", status)
        if (errorCode != null) it.getJSONObject("transaction").put("error_code", errorCode)
    }

    private fun Recorder.settledOnce(): CheckoutResult {
        assertEquals(1, results.size, "results=$results events=$events")
        assertEquals(1, events.count { it.startsWith("complete:") }, "$events")
        assertTrue(events.last().startsWith("complete:"), "nothing after onComplete: $events")
        return results.single()
    }

    private fun CheckoutController.payMomo() {
        selectMethod("mobile_money")
        setPhone("670000000")
        payMobileMoney()
    }

    // ── 1. Settling never hangs ─────────────────────────────────────────

    @Test
    fun `ST-04 settles once with the cancel's answer when the host goes away while it runs`() = runTest {
        class Case(val name: String, val cancel: FakeResponse, val status: FakeResponse?, val check: (CheckoutResult, CancelReason) -> Unit)
        val refused = Fixtures.response("cancel-400-not-cancellable")
        val cases = listOf(
            Case("200", Fixtures.response("cancel-200").after(5_000), null) { r, reason ->
                val c = assertIs<CheckoutResult.Cancelled>(r)
                assertEquals(reason, c.reason)
                assertEquals(Fixtures.body("cancel-200").getString("redirect_url"), c.redirectUrl)
            },
            Case("400+expired", refused.after(5_000), statusOf("expired")) { r, _ -> assertEquals("session_expired", assertIs<CheckoutResult.Failed>(r).error.code) },
            Case("400+failed", refused.after(5_000), statusOf("failed")) { r, _ -> assertEquals("payment_failed", assertIs<CheckoutResult.Failed>(r).error.code) },
            Case("400+pending", refused.after(5_000), statusOf("pending")) { r, _ -> assertIs<CheckoutResult.Cancelled>(r) },
            Case("400+succeeded", refused.after(5_000), Fixtures.response("status-succeeded")) { r, _ -> assertIs<CheckoutResult.Complete>(r) },
            Case("400 then status slow (host gone during the re-read)", refused, Fixtures.response("status-succeeded").after(5_000)) { r, _ -> assertIs<CheckoutResult.Complete>(r) },
            Case("network", FakeResponse.network(), null) { r, reason -> assertEquals(CheckoutResult.Cancelled(reason, null), r) },
        )
        for (case in cases) for (button in listOf(true, false)) {
            val api = cmApi().set("POST /pay/cancel", case.cancel)
            case.status?.let { api.on("GET /pay/status", it) }
            val h = hosted(api)
            h.controller.start()
            runCurrent()
            if (button) h.controller.cancelPayment() else h.controller.dismiss()
            runCurrent()
            advanceTimeBy(500)
            assertTrue(h.recorder.results.isEmpty(), "${case.name}: still running")
            h.goAway()
            advanceTimeBy(120_000)
            runCurrent()
            val result = h.recorder.settledOnce()
            case.check(result, if (button) CancelReason.PayerCancelled else CancelReason.Dismissed)
            assertEquals(result, h.controller.result.value, case.name)
        }
    }

    @Test
    fun `ST-04 settles processing when the host goes away during the status re-read after a 400`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-400-not-processable"))
            .on("GET /pay/status", Fixtures.response("status-succeeded").after(5_000))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(CheckoutState.PROCESSING, h.controller.state, "busy during the re-read")
        assertEquals(1, api.count("GET /pay/status"))
        h.goAway()
        advanceTimeBy(60_000)
        runCurrent()
        assertIs<CheckoutResult.Processing>(h.recorder.settledOnce())
        assertEquals(0, api.count("POST /pay/cancel"))
    }

    @Test
    fun `ST-04 settles when the host goes away while loading or during an attempt`() = runTest {
        val loading = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate").after(5_000))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/cancel", Fixtures.response("cancel-200"))
        val l = hosted(loading)
        l.controller.start()
        advanceTimeBy(1_000)
        l.goAway()
        advanceTimeBy(60_000)
        assertEquals(CheckoutResult.Cancelled(CancelReason.HostGone, null), l.recorder.settledOnce())
        assertEquals(0, loading.count("POST /pay/cancel"))

        // A request without its answer yet.
        val slow = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox").after(5_000))
            .on("GET /pay/status", Fixtures.response("status-succeeded"))
        val a = hosted(slow)
        a.controller.start()
        runCurrent()
        a.controller.payMomo()
        advanceTimeBy(1_000)
        a.goAway()
        advanceTimeBy(60_000)
        runCurrent()
        assertIs<CheckoutResult.Processing>(a.recorder.settledOnce())
        assertEquals(0, slow.count("POST /pay/cancel"))
        assertEquals(0, slow.count("GET /pay/status"))

        // Stripe tokenizing.
        val stripe = cmApi().on("POST /pay/process", Fixtures.response("process-succeeded-200"))
        val s = hosted(stripe)
        s.controller.start()
        runCurrent()
        s.controller.selectMethod("card")
        s.controller.payStripe {
            kotlinx.coroutines.delay(5_000)
            "pm_card_visa"
        }
        advanceTimeBy(1_000)
        s.goAway()
        advanceTimeBy(60_000)
        assertIs<CheckoutResult.Processing>(s.recorder.settledOnce())
        assertEquals(0, stripe.count("POST /pay/process"))
        assertEquals(0, stripe.count("POST /pay/cancel"))
    }

    @Test
    fun `ST-04 settles host_gone at once when started on a host already gone`() = runTest {
        val api = cmApi()
        val h = hosted(api)
        h.host.cancel()
        h.controller.start()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.recorder.results.size, "settled at start, without waiting for hostGone()")
        assertEquals(0, api.requests.size, "nothing loaded")
        h.controller.hostGone()
        runCurrent()
        val result = h.recorder.settledOnce()
        assertEquals(CheckoutResult.Cancelled(CancelReason.HostGone, null), result)
    }

    @Test
    fun `ST-05 presents again from onComplete after a host swap`() = runTest {
        val first = hosted(cmApi())
        first.controller.start()
        runCurrent()
        // Host swap (a configuration change): the new screen's callbacks.
        val swapped = Recorder()
        var reopened: CheckoutController? = null
        val nextApi = cmApi()
        val next = CheckoutController(
            session = fakeSession(nextApi, clock = { testScheduler.currentTime }),
            options = PresentOptions(sessionToken = "t2", locale = "en"),
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            random = Random(3),
            connector = FakeSocket(),
            compute = StandardTestDispatcher(testScheduler),
            deviceLanguage = { "en-US" },
        )
        val swappedCallbacks = swapped.callbacks()
        first.controller.callbacks = CheckoutCallbacks(
            onCancel = swappedCallbacks.onCancel,
            onComplete = {
                swappedCallbacks.onComplete?.invoke(it)
                next.start()
                reopened = next
            },
        )
        // A present right after the swap, while the first is open: rejected, the first left alone.
        val third = CheckoutController(fakeSession(cmApi()), PresentOptions(sessionToken = "t3"), backgroundScope)
        third.start()
        assertEquals("sheet_already_presented", assertIs<CheckoutResult.Failed>(third.result.value).error.code)
        assertNull(first.controller.result.value)
        assertIs<SheetScreen.Methods>(first.controller.screen.value)

        first.controller.dismiss()
        runCurrent()
        assertIs<CheckoutResult.Cancelled>(swapped.settledOnce())
        assertTrue(first.recorder.results.isEmpty(), "the old screen's callbacks are not called")
        assertNull(assertNotNull(reopened).result.value, "the sheet presented from onComplete is not rejected")
        runCurrent()
        assertIs<SheetScreen.Methods>(next.screen.value)
        assertEquals(1, nextApi.count("GET /pay/session"))
        next.hostGone()
    }

    // ── 2. Cancel rules ────────────────────────────────────────────────

    private fun TestScope.assertNoCancelOn(name: String, api: FakeApi, act: (CheckoutController) -> Unit, expectState: CheckoutState? = null) {
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        act(h.controller)
        if (expectState != null) assertEquals(expectState, h.controller.state, name)
        assertFalse(h.controller.cancellable.value, "$name: no Cancel payment")
        h.controller.cancelPayment()
        runCurrent()
        assertEquals(0, api.count("POST /pay/cancel"), "$name: Cancel payment is a no-op")
        assertTrue(h.recorder.results.isEmpty(), name)
        h.controller.dismiss()
        advanceTimeBy(1_000)
        runCurrent()
        assertIs<CheckoutResult.Processing>(h.recorder.settledOnce(), name)
        assertEquals(0, api.count("POST /pay/cancel"), "$name: a dismiss sends no cancel")
    }

    @Test
    fun `CN-03 offers no cancel on approval, USSD, pending, step, verifying and 3DS screens`() = runTest {
        assertNoCancelOn(
            "approval",
            cmApi().on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox")).on("GET /pay/status", Fixtures.response("status-processing")),
            { it.payMomo(); runCurrent() }, CheckoutState.APPROVAL_REQUIRED,
        )
        assertNoCancelOn(
            "ussd",
            cmApi().on(
                "POST /pay/process",
                Fixtures.response("process-processing-mobile-money-sandbox") {
                    it.getJSONObject("transaction").getJSONObject("processing_context").put("is_ussd", true).put("is_mobile_money", false)
                },
            ).on("GET /pay/status", Fixtures.response("status-processing")),
            { it.payMomo(); runCurrent() }, CheckoutState.USSD_REQUIRED,
        )
        assertNoCancelOn(
            "pending (redirect)",
            cmApi().on("POST /pay/process", Fixtures.response("process-redirect-wave")).on("GET /pay/status", Fixtures.response("status-processing")),
            { it.payMomo(); runCurrent() }, CheckoutState.PENDING,
        )
        assertNoCancelOn(
            "otp step",
            cmApi().on("POST /pay/process", Fixtures.response("process-requires-otp")).on("GET /pay/status", Fixtures.response("status-processing")),
            { it.payMomo(); runCurrent() }, CheckoutState.OTP_REQUIRED,
        )
        assertNoCancelOn(
            "verifying",
            cmApi().on("POST /pay/process", Fixtures.response("process-requires-otp"))
                .on("POST /pay/otp/verify", Fixtures.response("otp-verify-success").after(5_000))
                .on("GET /pay/status", Fixtures.response("status-processing")),
            {
                it.payMomo()
                runCurrent()
                it.setStepField("code", "123456")
                it.submitStep()
                runCurrent()
            },
            CheckoutState.VERIFYING,
        )
        assertNoCancelOn(
            "3ds",
            cmApi().on("POST /pay/process", Fixtures.response("process-requires-3ds-sandbox")).on("GET /pay/status", Fixtures.response("status-processing")),
            { it.payMomo(); runCurrent() }, CheckoutState.THREE_DS_REQUIRED,
        )
        // An unknown 2xx (no transaction) and a 500: undecided.
        assertNoCancelOn(
            "2xx without transaction",
            cmApi().on("POST /pay/process", FakeResponse.status(202, """{"code":202,"message":"Accepted"}""")).on("GET /pay/status", statusOf("pending")),
            { it.payMomo(); runCurrent() }, CheckoutState.PENDING,
        )
        // While tokenizing.
        assertNoCancelOn(
            "stripe tokenizing",
            cmApi().on("POST /pay/process", Fixtures.response("process-succeeded-200")),
            {
                it.selectMethod("card")
                it.payStripe {
                    kotlinx.coroutines.delay(5_000)
                    "pm_card_visa"
                }
                runCurrent()
            },
            CheckoutState.PROCESSING,
        )
    }

    @Test
    fun `CN-03 offers no cancel while an Altcha challenge is fetched for an attempt`() = runTest {
        val salt = "s?expires=1"
        fun challengeFor(n: Int) = JSONObject().put("algorithm", "SHA-256")
            .put("challenge", MessageDigest.getInstance("SHA-256").digest("$salt$n".toByteArray()).joinToString("") { "%02x".format(it) })
            .put("salt", salt).put("signature", "SIG").put("maxnumber", 1_000)
        val api = FakeApi()
            .on(
                "GET /pay/session",
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(5)) },
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(6)) }.after(5_000),
            )
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/process", Fixtures.response("process-failed-402"))
            .on("POST /pay/cancel", Fixtures.response("cancel-200"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        runCurrent()
        h.controller.tryAgain()
        h.controller.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.PROCESSING, h.controller.state)
        assertFalse(h.controller.cancellable.value)
        h.controller.cancelPayment()
        h.controller.dismiss()
        advanceTimeBy(10_000)
        runCurrent()
        assertIs<CheckoutResult.Processing>(h.recorder.settledOnce())
        assertEquals(0, api.count("POST /pay/cancel"))
        assertEquals(1, api.count("POST /pay/process"), "nothing sent after the close")
    }

    @Test
    fun `PA-03 an unsupported action fails without a cancel on dismiss`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp") { it.put("action", "confirm_magic") })
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        runCurrent()
        assertEquals("unsupported_action", assertIs<SheetScreen.Failed>(h.controller.screen.value).error.code)
        assertFalse(h.controller.cancellable.value)
        h.controller.close()
        runCurrent()
        assertEquals("unsupported_action", assertIs<CheckoutResult.Failed>(h.recorder.settledOnce()).error.code)
        assertEquals(0, api.count("POST /pay/cancel"))
    }

    @Test
    fun `CF-03 a step over for good releases the order on dismiss`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("POST /pay/otp/verify", Fixtures.response("otp-verify-max-attempts"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        runCurrent()
        h.controller.setStepField("code", "123456")
        h.controller.submitStep()
        runCurrent()
        assertEquals(CheckoutState.FAILED, h.controller.state)
        // Dismiss on FAILED: failed, and the cancel when cancellable.
        h.controller.close()
        runCurrent()
        assertIs<CheckoutResult.Failed>(h.recorder.settledOnce())
        assertTrue(h.recorder.events.none { it.startsWith("cancel:") })
        assertEquals(1, api.count("POST /pay/cancel"), "a step failed for good is a definitive refusal: the order is released")
    }

    @Test
    fun `CF-03 a step over for good offers Cancel payment again after Try again`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("POST /pay/otp/verify", Fixtures.response("otp-verify-max-attempts"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        runCurrent()
        h.controller.setStepField("code", "123456")
        h.controller.submitStep()
        runCurrent()
        h.controller.tryAgain()
        assertIs<SheetScreen.Form>(h.controller.screen.value)
        assertTrue(h.controller.cancellable.value, "Cancel payment offered again after a step failed for good")
    }

    @Test
    fun `CF-06 a 3-D Secure code refused or timed out offers Cancel payment again`() = runTest {
        for (refused in listOf(true, false)) {
            val api = cmApi()
                .on("POST /pay/process", Fixtures.response("process-requires-3ds-sandbox"))
                .on("POST /pay/3ds/verify", FakeResponse.status(422, """{"code":422,"error":{"code":"3ds_failed","message":"Authentication failed.","retryable":false}}"""))
                .on("GET /pay/status", Fixtures.response("status-processing"))
            val h = hosted(api)
            h.controller.start()
            runCurrent()
            h.controller.payMomo()
            runCurrent()
            assertEquals(CheckoutState.THREE_DS_REQUIRED, h.controller.state)
            if (refused) {
                h.controller.setStepField("code", "123456")
                h.controller.submitStep()
                runCurrent()
            } else {
                advanceTimeBy(600_001)
                runCurrent()
            }
            val failed = assertIs<SheetScreen.Failed>(h.controller.screen.value, "refused=$refused")
            assertEquals(if (refused) "3ds_failed" else "3ds_timeout", failed.error.code)
            h.controller.close()
            runCurrent()
            assertIs<CheckoutResult.Failed>(h.recorder.settledOnce())
            assertEquals(1, api.count("POST /pay/cancel"), "the order is released (refused=$refused)")
        }
    }

    @Test
    fun `CS-02 a client-session decline offers Cancel payment again`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-card-paystack"))
            .on("POST /pay/client-session", Fixtures.response("client-session-start-202"))
            .on("POST /pay/client-session/complete", Fixtures.response("client-session-complete-402"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
            .on("POST /pay/cancel", Fixtures.response("cancel-200"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.selectMethod("card")
        h.controller.payCard()
        runCurrent()
        h.controller.onReturn()
        runCurrent()
        assertEquals(CheckoutState.FAILED, h.controller.state)
        h.controller.tryAgain()
        assertTrue(h.controller.cancellable.value, "Cancel payment offered again after a 402 on /complete (transaction.status=${h.controller.transaction?.status})")
    }

    @Test
    fun `CN-04 a dismiss on FAILED releases the order, and a refused cancel with an unreadable status is processing`() = runTest {
        for (closeButton in listOf(true, false)) {
            val api = cmApi().on("POST /pay/process", Fixtures.response("process-failed-402"))
            val h = hosted(api)
            h.controller.start()
            runCurrent()
            h.controller.payMomo()
            runCurrent()
            if (closeButton) h.controller.close() else h.controller.dismiss()
            runCurrent()
            assertEquals("insufficient_funds", assertIs<CheckoutResult.Failed>(h.recorder.settledOnce()).error.code)
            assertTrue(h.recorder.events.none { it.startsWith("cancel:") })
            assertEquals(1, api.count("POST /pay/cancel"))
        }
        // 400, then a status answered without `status`: processing.
        val api = cmApi()
            .set("POST /pay/cancel", Fixtures.response("cancel-400-not-cancellable"))
            .on("GET /pay/status", FakeResponse.status(200, """{"code":200,"transaction":{"reference":"trx_1"}}"""))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.cancelPayment()
        runCurrent()
        assertIs<CheckoutResult.Processing>(h.recorder.settledOnce())
    }

    // ── 3. Unanswered payment ──────────────────────────────────────────

    @Test
    fun `TR-09 a refusal already on the payment at load is not news`() = runTest {
        // The payer was declined in an earlier presentation: the payment carries insufficient_funds
        // when this sheet loads, and the status still does after this sheet's unanswered attempt.
        val api = cmApi(session = "session-cm-aggregate")
            .set("GET /pay/session", Fixtures.response("session-cm-aggregate") { it.getJSONObject("transaction").put("error_code", "insufficient_funds") })
            .on("POST /pay/process", FakeResponse.timeout())
            .on("GET /pay/status", statusOf("pending", "insufficient_funds"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CheckoutState.PENDING, h.controller.state)
        advanceTimeBy(70_000)
        runCurrent()
        val form = assertIs<SheetScreen.Form>(h.controller.screen.value, "back to the form with network_error 60 s after the last try")
        assertEquals("Connection error. Check your network.", form.banner)
        assertTrue("error:network_error" in h.recorder.events)
        h.goAway()

        // A refusal the payment did not carry before: the request arrived and was refused.
        val refused = cmApi()
            .on("POST /pay/process", FakeResponse.timeout())
            .on("GET /pay/status", statusOf("pending", "insufficient_funds"))
        val r = hosted(refused)
        r.controller.start()
        runCurrent()
        r.controller.payMomo()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("insufficient_funds", assertIs<SheetScreen.Failed>(r.controller.screen.value).error.code)
        r.controller.tryAgain()
        assertTrue(r.controller.cancellable.value, "a definitive refusal: Cancel payment is offered again")
        r.goAway()
    }

    @Test
    fun `TR-06 reuses the key after no answer and makes a new one after a definitive answer`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("error-500-production"))
            .on("GET /pay/status", statusOf("pending"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        runCurrent()
        assertEquals(1, api.count("POST /pay/process"), "a 500 is not retried")
        assertEquals(CheckoutState.PENDING, h.controller.state)
        assertTrue(h.recorder.events.none { it.startsWith("error:") })
        advanceTimeBy(61_000)
        runCurrent()
        assertIs<SheetScreen.Form>(h.controller.screen.value)
        val k1 = api.requests("POST /pay/process")[0].headers["Idempotency-Key"]
        api.set("POST /pay/process", Fixtures.response("process-failed-402"))
        h.controller.payMobileMoney()
        runCurrent()
        assertEquals(k1, api.requests("POST /pay/process")[1].headers["Idempotency-Key"], "the same key after no answer")
        h.controller.tryAgain()
        h.controller.payMobileMoney()
        runCurrent()
        assertNotEquals(k1, api.requests("POST /pay/process")[2].headers["Idempotency-Key"], "a new key after a definitive answer")
        h.goAway()
    }

    // ── 4. Watching ────────────────────────────────────────────────────

    @Test
    fun `WT-01 caps the watch at 15 minutes of device time, never expires_at`() = runTest {
        // session-cm-aggregate expires_at = 2026-10-14T09:30Z: already past on the device's wall clock.
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api, PresentOptions(sessionToken = "t", locale = "en"))
        h.controller.start()
        runCurrent()
        val pressed = testScheduler.currentTime
        h.controller.payMomo()
        runCurrent()
        advanceTimeBy(14 * 60_000L)
        runCurrent()
        assertEquals(CheckoutState.APPROVAL_REQUIRED, h.controller.state)
        val polls = api.count("GET /pay/status")
        advanceTimeBy(30_000)
        assertTrue(api.count("GET /pay/status") > polls, "still polling at 14.5 min")
        advanceTimeBy(31_000)
        runCurrent()
        val pending = assertIs<SheetScreen.Pending>(h.controller.screen.value)
        assertTrue(pending.capped)
        val after = api.count("GET /pay/status")
        advanceTimeBy(120_000)
        assertEquals(after, api.count("GET /pay/status"), "stopped")
        h.controller.close()
        assertIs<CheckoutResult.Processing>(h.recorder.settledOnce())
    }

    @Test
    fun `WT-01 counts the cap from the Pay press when the answer is slow`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", FakeResponse.timeout(delayMs = 60_000))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api, PresentOptions(sessionToken = "t", locale = "en"))
        h.controller.start()
        runCurrent()
        val pressed = testScheduler.currentTime
        h.controller.payMomo()
        var cappedAt: Long? = null
        while (testScheduler.currentTime - pressed < 25 * 60_000L) {
            advanceTimeBy(1_000)
            runCurrent()
            val s = h.controller.screen.value
            if (s is SheetScreen.Pending && s.capped) {
                cappedAt = testScheduler.currentTime - pressed
                break
            }
        }
        assertNotNull(cappedAt)
        assertTrue(cappedAt in 900_000L..901_000L, "15 minutes from the press, not from the answer: $cappedAt ms")
        h.goAway()
    }

    @Test
    fun `WT-02 keeps watching on a status, frame or 2xx without information`() = runTest {
        val socket = FakeSocket()
        val api = cmApi()
            .on("POST /pay/process", FakeResponse.status(202, """{"code":202,"message":"Accepted"}"""))
            .on("GET /pay/status", FakeResponse.status(200, """{"code":200,"transaction":{"reference":"trx_x"}}"""))
        val h = hosted(api, socket = socket)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        runCurrent()
        assertEquals(CheckoutState.PENDING, h.controller.state)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(CheckoutState.PENDING, h.controller.state, "a status without status says nothing")
        val listener = assertNotNull(socket.listener)
        listener.onEvent("""{"reference":"trx_x","status_label":"?"}""")
        runCurrent()
        assertEquals(CheckoutState.PENDING, h.controller.state, "a frame without status says nothing")
        assertTrue(h.recorder.events.none { it.startsWith("error:") })
        listener.onEvent(Fixtures.file("realtime-payment-status-updated-succeeded").get("data").toString())
        runCurrent()
        assertEquals(CheckoutState.SUCCESS, h.controller.state)
        h.goAway()
    }

    @Test
    fun `WT-02 stops the watch on 401, 403, 404 and 410`() = runTest {
        for (code in listOf(401, 403, 404, 410)) {
            val api = cmApi()
                .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
                .on("GET /pay/status", FakeResponse.status(code, """{"code":$code,"error_code":"${if (code == 410) "session_terminal_expired" else "x"}"}"""))
            val h = hosted(api)
            h.controller.start()
            runCurrent()
            h.controller.payMomo()
            runCurrent()
            val polls = api.count("GET /pay/status")
            advanceTimeBy(120_000)
            assertEquals(polls, api.count("GET /pay/status"), "$code stops polling")
            val expected = if (code == 410) CheckoutState.EXPIRED else CheckoutState.FAILED
            assertEquals(expected, h.controller.state, "$code")
            h.goAway()
        }
    }

    // ── 5. 400 on /pay/process ─────────────────────────────────────────

    @Test
    fun `PA-09 decides a 400 on the status re-read, tied to its attempt`() = runTest {
        val cases = listOf(
            Fixtures.response("status-succeeded") to CheckoutState.SUCCESS,
            statusOf("refunded") to CheckoutState.SUCCESS,
            statusOf("failed") to CheckoutState.FAILED,
            statusOf("cancelled") to CheckoutState.CANCELLED,
            statusOf("expired") to CheckoutState.EXPIRED,
            Fixtures.response("status-processing") to CheckoutState.PENDING,
            statusOf("pending") to CheckoutState.FAILED,
            FakeResponse.network() to CheckoutState.PENDING,
            FakeResponse.status(200, """{"code":200,"transaction":{}}""") to CheckoutState.PENDING,
        )
        for ((status, expected) in cases) {
            val api = cmApi()
                .on("POST /pay/process", Fixtures.response("process-400-not-processable"))
                .on("GET /pay/status", status.after(3_000))
            // An unreadable status stays unreadable through the re-read's retries.
            if (status.failure != null) api.on("GET /pay/status", status, status, status)
            api.on("GET /pay/status", Fixtures.response("status-processing"))
            val h = hosted(api)
            h.controller.start()
            runCurrent()
            h.controller.payMomo()
            advanceTimeBy(1_000)
            assertEquals(CheckoutState.PROCESSING, h.controller.state, "busy during the re-read: ${status.body}")
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(expected, h.controller.state, status.body)
            if (status.body.contains("\"pending\"")) {
                assertEquals("not_processable", assertIs<SheetScreen.Failed>(h.controller.screen.value).error.code)
            }
            if (expected == CheckoutState.PENDING) {
                val before = api.count("GET /pay/status")
                advanceTimeBy(20_000)
                assertTrue(api.count("GET /pay/status") > before, "watching: ${status.body}")
            }
            h.goAway()
        }
        // Tied to its attempt: a dismiss during the re-read is processing, the late status changes nothing.
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-400-not-processable"))
            .on("GET /pay/status", statusOf("pending").after(5_000))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        advanceTimeBy(1_000)
        h.controller.dismiss()
        advanceTimeBy(10_000)
        runCurrent()
        assertIs<CheckoutResult.Processing>(h.recorder.settledOnce())
        assertEquals(0, api.count("POST /pay/cancel"))
    }

    // ── 6. Load ────────────────────────────────────────────────────────

    @Test
    fun `LD-06 a processing session restores at once while sdk-config hangs`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-processing-mobile-money"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox").after(20_000))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue("loadError:ready_timeout" !in h.recorder.events, "no ready_timeout on a processing session: ${h.recorder.events}")
        h.goAway()
    }

    @Test
    fun `LD-06 a processing session restores without waiting for sdk-config`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-processing-mobile-money"))
            .on("GET /pay/sdk-config", FakeResponse.status(500))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        assertEquals(CheckoutState.APPROVAL_REQUIRED, h.controller.state, "restored from the session alone (sdk-config still retrying)")
        h.goAway()
    }

    @Test
    fun `LD-06 a processing session whose host goes away during sdk-config resolves processing`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-processing-mobile-money"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox").after(8_000))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        advanceTimeBy(2_000)
        h.goAway()
        advanceTimeBy(20_000)
        assertIs<CheckoutResult.Processing>(h.recorder.settledOnce(), "an attempt is under way on a processing session")
    }

    @Test
    fun `LD-05 ended sessions never wait for sdk-config`() = runTest {
        for ((fixture, state) in listOf(
            "session-terminal-succeeded" to CheckoutState.SUCCESS,
            "session-terminal-failed" to CheckoutState.FAILED,
            "session-terminal-cancelled" to CheckoutState.CANCELLED,
            "session-terminal-expired" to CheckoutState.EXPIRED,
        )) {
            val api = FakeApi()
                .on("GET /pay/session", Fixtures.response(fixture))
                .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox").after(10 * 60_000L))
            val h = hosted(api)
            h.controller.start()
            runCurrent()
            assertEquals(state, h.controller.state, fixture)
            h.goAway()
        }
    }

    @Test
    fun `LD-01 card-only and wallet-only sessions give a retryable load_error when sdk-config fails`() = runTest {
        val cardOnly = Fixtures.response("session-cm-aggregate") { body ->
            val channels = body.getJSONArray("channels")
            val card = (0 until channels.length()).map { channels.getJSONObject(it) }.first { it.getString("type") == "card" }
            body.put("channels", org.json.JSONArray().put(card))
        }
        val walletOnly = Fixtures.response("session-ci-xof-wallets") { body ->
            val channels = body.getJSONArray("channels")
            val wallet = (0 until channels.length()).map { channels.getJSONObject(it) }.first { it.getString("type") == "wallet" }
            body.put("channels", org.json.JSONArray().put(wallet))
        }
        for ((name, session, config) in listOf(
            Triple("card", cardOnly, Fixtures.response("sdk-config-cm-stripe")),
            Triple("wallet", walletOnly, Fixtures.response("sdk-config-ci-wallets")),
        )) {
            val api = FakeApi()
                .on("GET /pay/session", session)
                .on("GET /pay/sdk-config", FakeResponse.network())
            val h = hosted(api)
            h.controller.start()
            advanceTimeBy(14_000)
            runCurrent()
            val error = assertIs<SheetScreen.LoadError>(h.controller.screen.value, name).error
            assertEquals("load_error", error.code, name)
            assertTrue(error.retryable, name)
            assertTrue(h.recorder.events.none { it == "loadError:no_payment_method" }, name)
            api.set("GET /pay/sdk-config", config)
            h.controller.retryLoad()
            advanceTimeBy(1_000)
            runCurrent()
            assertIs<SheetScreen.Form>(h.controller.screen.value, "$name after Retry")
            h.goAway()
        }
    }

    // ── 7. Client session ──────────────────────────────────────────────

    private fun clientSessionApi() = FakeApi()
        .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
        .on("GET /pay/sdk-config", Fixtures.response("sdk-config-card-paystack"))
        .on("POST /pay/client-session", Fixtures.response("client-session-start-202"))

    @Test
    fun `CS-02 completes only on return, on each return`() = runTest {
        val api = clientSessionApi()
            .on("POST /pay/client-session/complete", Fixtures.response("client-session-complete-202"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.selectMethod("card")
        h.controller.payCard()
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(0, api.count("POST /pay/client-session/complete"), "never while the provider page is in front")
        h.controller.onReturn()
        runCurrent()
        assertEquals(1, api.count("POST /pay/client-session/complete"))
        h.controller.onReturn()
        runCurrent()
        assertEquals(1, api.count("POST /pay/client-session/complete"), "a foreground without having left: no complete")
        h.controller.continuePayment()
        h.controller.onReturn()
        runCurrent()
        assertEquals(2, api.count("POST /pay/client-session/complete"), "each return from the provider page")
        h.goAway()
    }

    @Test
    fun `CS-02 a refusal never beats the success of a complete still running`() = runTest {
        val api = clientSessionApi()
            .on(
                "POST /pay/client-session/complete",
                Fixtures.response("client-session-complete-202").after(5_000),
                Fixtures.response("client-session-complete-200").after(10_000),
            )
            .on("GET /pay/status", Fixtures.response("status-processing"), Fixtures.response("status-pending-attempt-refused"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.selectMethod("card")
        h.controller.payCard()
        runCurrent()
        // Back once (complete #1 runs), straight back to the provider page, back again (complete #2 runs).
        h.controller.onReturn()
        runCurrent()
        advanceTimeBy(1_000)
        h.controller.continuePayment()
        h.controller.onReturn()
        runCurrent()
        assertEquals(2, api.count("POST /pay/client-session/complete"))
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(CheckoutState.SUCCESS, h.controller.state, "complete #2 said paid")
        assertTrue(h.recorder.events.none { it.startsWith("error:") }, "the refusal never showed: ${h.recorder.events}")
        h.goAway()
    }

    // ── 8. Altcha ──────────────────────────────────────────────────────

    private val salt = "8f2a1c?expires=1760000000"

    private fun challengeFor(n: Int, max: Int = 50_000) = JSONObject().put("algorithm", "SHA-256")
        .put("challenge", MessageDigest.getInstance("SHA-256").digest("$salt$n".toByteArray()).joinToString("") { "%02x".format(it) })
        .put("salt", salt).put("signature", "SIG").put("maxnumber", max)

    @Test
    fun `AL-01 a challenge that cannot be refreshed sends nothing and says so`() = runTest {
        val api = FakeApi()
            .on(
                "GET /pay/session",
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(11)) },
                FakeResponse.network(),
            )
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/process", Fixtures.response("process-failed-402"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        runCurrent()
        h.controller.tryAgain()
        h.controller.payMobileMoney()
        advanceTimeBy(30_000)
        runCurrent()
        val bodies = api.requests("POST /pay/process").map { JSONObject(it.body!!).getJSONObject("data") }
        assertTrue(bodies.all { it.has("altcha") }, "never sent without a solution")
        assertEquals(1, bodies.size, "nothing sent when the refresh failed")
        h.goAway()
    }

    @Test
    fun `AL-01 an unsolvable challenge is never sent without a solution`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(500, max = 100)) })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        advanceTimeBy(5_000)
        runCurrent()
        val bodies = api.requests("POST /pay/process").map { JSONObject(it.body!!).getJSONObject("data") }
        assertTrue(bodies.all { it.has("altcha") }, "never sent without a solution")
        h.goAway()
    }

    @Test
    fun `AL-01 one solution per request, a new challenge per attempt, nothing after close`() = runTest {
        val api = FakeApi()
            .on(
                "GET /pay/session",
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(21)) },
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(22)) },
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(23)) },
            )
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/process", FakeResponse.status(503), Fixtures.response("process-failed-402"), Fixtures.response("process-failed-402"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        advanceTimeBy(5_000)
        runCurrent()
        h.controller.tryAgain()
        h.controller.payMobileMoney()
        runCurrent()
        val sent = api.requests("POST /pay/process").map { JSONObject(it.body!!).getJSONObject("data").getJSONObject("altcha").getInt("number") }
        assertEquals(listOf(21, 21, 22), sent, "the retry resends its request's solution; the next attempt has a new one")
        // Host gone while the next challenge is fetched: nothing sent.
        api.set("GET /pay/session", Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(24)) }.after(5_000))
        h.controller.tryAgain()
        h.controller.payMobileMoney()
        advanceTimeBy(1_000)
        h.goAway()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, api.count("POST /pay/process"))
        assertIs<CheckoutResult.Processing>(h.recorder.settledOnce())
    }

    // ── Extra probes ───────────────────────────────────────────────────

    @Test
    fun `LD-06 a dismiss on a processing session with a slow sdk-config is processing`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-processing-mobile-money"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox").after(20_000))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val h = hosted(api)
        h.controller.start()
        advanceTimeBy(16_000)
        runCurrent()
        h.controller.dismiss()
        runCurrent()
        val result = h.recorder.settledOnce()
        assertIs<CheckoutResult.Processing>(result, "an attempt is under way")
    }

    @Test
    fun `WT-06 closes the socket when the host goes away and when the sheet closes`() = runTest {
        for (close in listOf(true, false)) {
            val socket = FakeSocket()
            val api = cmApi()
                .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
                .on("GET /pay/status", Fixtures.response("status-processing"))
            val h = hosted(api, socket = socket)
            h.controller.start()
            runCurrent()
            h.controller.payMomo()
            runCurrent()
            assertNotNull(socket.listener)
            assertFalse(socket.closed)
            if (close) h.controller.dismiss() else h.goAway()
            runCurrent()
            assertTrue(socket.closed, "closed (close=$close)")
            val polls = api.count("GET /pay/status")
            advanceTimeBy(120_000)
            assertEquals(polls, api.count("GET /pay/status"))
        }
    }

    @Test
    fun `TR-09 the attempt stays undecided after the 60 s rule, Altcha reload included`() = runTest {
        // Control: no altcha.
        val plain = cmApi().on("POST /pay/process", FakeResponse.timeout()).on("GET /pay/status", statusOf("pending"))
        val p = hosted(plain)
        p.controller.start()
        runCurrent()
        p.controller.payMomo()
        advanceTimeBy(70_000)
        runCurrent()
        assertIs<SheetScreen.Form>(p.controller.screen.value)
        val plainCancellable = p.controller.cancellable.value
        p.goAway()
        // Altcha refused once (422), reloaded, resent: no answer.
        val refusal = FakeResponse.status(422, """{"code":422,"message":"Security challenge verification failed.","errors":{"altcha":["Security challenge verification failed."]}}""")
        val api = FakeApi()
            .on(
                "GET /pay/session",
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(31)) },
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(32)) },
            )
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/process", refusal, FakeResponse.timeout())
            .on("GET /pay/status", statusOf("pending"))
            .on("POST /pay/cancel", Fixtures.response("cancel-200"))
        val h = hosted(api)
        h.controller.start()
        runCurrent()
        h.controller.payMomo()
        advanceTimeBy(70_000)
        runCurrent()
        assertIs<SheetScreen.Form>(h.controller.screen.value)
        val altchaCancellable = h.controller.cancellable.value
        h.controller.dismiss()
        runCurrent()
        assertEquals(0, api.count("POST /pay/cancel"), "a request that may have arrived is never cancelled")
        assertEquals(plainCancellable, altchaCancellable, "the same rule with or without the altcha reload")
    }
}
