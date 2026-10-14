package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CancelReason
import com.wajub.mobile.checkout.CheckoutCallbacks
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.PaymentSuccess
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.mapper.RawTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cancel and dismiss (CN), states, callbacks and results (ST). */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleContractTest {
    @Test
    fun `CN-01 cancels on request`() = runTest {
        val api = cmApi()
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.cancelPayment()
        runCurrent()
        assertEquals(1, api.count("POST /pay/cancel"))
        val result = assertIs<CheckoutResult.Cancelled>(recorder.result)
        assertEquals(CancelReason.PayerCancelled, result.reason)
        assertEquals(Fixtures.body("cancel-200").getString("redirect_url"), result.redirectUrl)
        assertEquals(listOf("state:CANCELLED", "cancel:payer_cancelled", "complete:cancelled"), recorder.events.takeLast(3))
    }

    @Test
    fun `CN-02 cancels on dismiss before any attempt`() = runTest {
        val api = cmApi()
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("677")
        controller.dismiss()
        runCurrent()
        assertEquals(1, api.count("POST /pay/cancel"))
        val result = assertIs<CheckoutResult.Cancelled>(recorder.result)
        assertEquals(CancelReason.Dismissed, result.reason)
        assertEquals(Fixtures.body("cancel-200").getString("redirect_url"), result.redirectUrl)

        val keep = cmApi()
        val (kept, keptEvents) = checkout(keep, PresentOptions(sessionToken = "t", cancelOnDismiss = false))
        kept.start()
        runCurrent()
        kept.dismiss()
        runCurrent()
        assertEquals(0, keep.count("POST /pay/cancel"))
        assertEquals(CheckoutResult.Cancelled(CancelReason.Dismissed, null), keptEvents.result)

        // A cancel that fails changes nothing for the payer.
        val down = cmApi().set("POST /pay/cancel", FakeResponse.network())
        val (downController, downEvents) = checkout(down)
        downController.start()
        runCurrent()
        downController.dismiss()
        advanceTimeBy(10_000)
        assertEquals(CheckoutResult.Cancelled(CancelReason.Dismissed, null), downEvents.result)
        assertEquals(3, down.count("POST /pay/cancel"), "network failures are retried twice")
    }

    @Test
    fun `CN-03 dismiss after start resolves processing`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        runCurrent()
        assertTrue(recorder.events.any { it.startsWith("started:") })
        controller.dismiss()
        runCurrent()
        assertEquals(0, api.count("POST /pay/cancel"))
        val result = assertIs<CheckoutResult.Processing>(recorder.result)
        assertEquals("trx_test_PVrU8x2kQ1mNa7Lb3cDe", result.transaction?.id)
    }

    @Test
    fun `CN-04 handles a refused cancel`() = runTest {
        val api = cmApi()
            .set("POST /pay/cancel", Fixtures.response("cancel-400-not-cancellable"))
            .on("GET /pay/status", Fixtures.response("status-succeeded"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.dismiss()
        runCurrent()
        assertEquals(1, api.count("GET /pay/status"))
        assertIs<CheckoutResult.Complete>(recorder.result, "already paid: the status decides")

        // "Cancel payment" refused: the payer sees the success.
        val pressed = cmApi()
            .set("POST /pay/cancel", Fixtures.response("cancel-400-not-cancellable"))
            .on("GET /pay/status", Fixtures.response("status-succeeded"))
        val (button, buttonEvents) = checkout(pressed)
        button.start()
        runCurrent()
        button.cancelPayment()
        runCurrent()
        assertEquals(CheckoutState.SUCCESS, button.state)
        advanceTimeBy(3_001)
        assertIs<CheckoutResult.Complete>(buttonEvents.result)
    }

    @Test
    fun `ST-01 reports Wajub js states`() = runTest {
        assertEquals(
            listOf(
                "INITIATED", "COLLECTING_DETAILS", "PROCESSING", "OTP_REQUIRED", "PIN_REQUIRED", "PHONE_REQUIRED",
                "BIRTHDAY_REQUIRED", "ADDRESS_REQUIRED", "3DS_REQUIRED", "USSD_REQUIRED", "APPROVAL_REQUIRED",
                "VERIFYING", "PENDING", "SUCCESS", "FAILED", "EXPIRED", "CANCELLED",
            ).sorted(),
            CheckoutState.entries.map { it.value }.sorted(),
        )
        val expected = mapOf(
            "process-processing-mobile-money-sandbox" to "APPROVAL_REQUIRED",
            "process-requires-otp" to "OTP_REQUIRED",
            "process-redirect-card-hosted" to "PENDING",
        )
        for ((fixture, state) in expected) {
            val api = cmApi()
                .on("POST /pay/process", Fixtures.response(fixture))
                .on("GET /pay/status", Fixtures.response("status-processing"))
            val (controller, recorder) = checkout(api)
            controller.start()
            runCurrent()
            controller.selectMethod("mobile_money")
            controller.setPhone("670000000")
            controller.payMobileMoney()
            runCurrent()
            assertEquals(listOf("INITIATED", "COLLECTING_DETAILS", "PROCESSING", state), recorder.states, fixture)
        }
    }

    @Test
    fun `ST-02 fires callbacks in order`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"), Fixtures.response("status-succeeded"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        runCurrent()
        advanceTimeBy(5_001)
        advanceTimeBy(3_001)
        assertEquals(
            listOf(
                "ready", "state:INITIATED", "state:COLLECTING_DETAILS", "state:PROCESSING", "started:mobile_money:cm.mobile",
                "state:APPROVAL_REQUIRED", "state:SUCCESS", "success:mobile_money", "complete:complete",
            ),
            recorder.events,
        )
        val states = recorder.states
        assertTrue(states.zipWithNext().none { (a, b) -> a == b }, "no state twice in a row")
    }

    @Test
    fun `ST-03 resolves present once`() = runTest {
        val api = cmApi()
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.dismiss()
        controller.dismiss()
        controller.close()
        // The host going away during the cancel loses neither it nor its redirect_url.
        controller.hostGone()
        runCurrent()
        controller.dismiss()
        assertEquals(1, recorder.results.size)
        assertEquals(
            CheckoutResult.Cancelled(CancelReason.Dismissed, Fixtures.body("cancel-200").getString("redirect_url")),
            recorder.result,
        )
        assertEquals(1, recorder.events.count { it.startsWith("complete:") })
        assertEquals(recorder.result, controller.result.value)
    }

    @Test
    fun `ST-04 settles when the host goes away`() = runTest {
        // No attempt: cancelled, host_gone, and the payment is not cancelled.
        val idle = cmApi()
        val (first, firstEvents) = checkout(idle)
        first.start()
        runCurrent()
        first.hostGone()
        runCurrent()
        assertEquals(CheckoutResult.Cancelled(CancelReason.HostGone, null), firstEvents.result)
        assertEquals(0, idle.count("POST /pay/cancel"))

        // Still loading: the same.
        val loading = FakeApi().on("GET /pay/session", FakeResponse(200, Fixtures.body("session-cm-aggregate").toString(), delayMs = 5_000)).on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
        val (early, earlyEvents) = checkout(loading, PresentOptions(sessionToken = "t", cancelOnDismiss = true))
        early.start()
        early.hostGone()
        advanceTimeBy(10_000)
        assertEquals(CheckoutResult.Cancelled(CancelReason.HostGone, null), earlyEvents.result)
        assertEquals(0, loading.count("POST /pay/cancel"))

        // An attempt started: processing, watching stopped.
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        runCurrent()
        controller.hostGone()
        val polls = api.count("GET /pay/status")
        advanceTimeBy(60_000)
        assertIs<CheckoutResult.Processing>(recorder.result)
        assertEquals(polls, api.count("GET /pay/status"))
        assertEquals(0, api.count("POST /pay/cancel"))

        // Nothing fires after the result: an answer arriving later is dropped.
        val slow = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox").let { FakeResponse(it.status, it.body, delayMs = 5_000) })
            .on("GET /pay/status", Fixtures.response("status-succeeded"))
        val (late, lateEvents) = checkout(slow)
        late.start()
        runCurrent()
        late.selectMethod("mobile_money")
        late.setPhone("670000000")
        late.payMobileMoney()
        runCurrent()
        late.hostGone()
        assertIs<CheckoutResult.Processing>(lateEvents.result, "a request without an answer may still charge")
        advanceTimeBy(30_000)
        assertEquals("complete:processing", lateEvents.events.last())

        // Success reached: complete.
        val paid = FakeApi().on("GET /pay/session", Fixtures.response("session-terminal-succeeded")).on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
        val (done, doneEvents) = checkout(paid)
        done.start()
        runCurrent()
        done.hostGone()
        assertIs<CheckoutResult.Complete>(doneEvents.result)
    }

    @Test
    fun `ST-05 rejects a second present`() = runTest {
        val api = cmApi()
        val (first, firstEvents) = checkout(api)
        first.start()
        runCurrent()
        val second = com.wajub.mobile.checkout.CheckoutController(fakeSession(cmApi()), PresentOptions(sessionToken = "t", callbacks = Recorder().let { r ->
            CheckoutCallbacks(onComplete = { r.results += it; firstEvents.events += "second:${it.status}" })
        }), backgroundScope)
        second.start()
        val rejected = assertIs<CheckoutResult.Failed>(second.result.value)
        assertEquals("sheet_already_presented", rejected.error.code)
        assertEquals("invalid_request_error", rejected.error.type.value)
        assertEquals("A Wajub payment sheet is already presented.", rejected.error.message)
        // The open sheet is left alone.
        assertNull(first.result.value)
        assertIs<SheetScreen.Methods>(first.screen.value)
        first.dismiss()
        runCurrent()
        assertIs<CheckoutResult.Cancelled>(first.result.value)

        // A sheet can be presented again at once, from onComplete itself.
        var reopened: com.wajub.mobile.checkout.CheckoutController? = null
        val (third, _) = checkout(cmApi(), PresentOptions(sessionToken = "t", callbacks = CheckoutCallbacks()))
        val next = com.wajub.mobile.checkout.CheckoutController(fakeSession(cmApi()), PresentOptions(sessionToken = "t"), backgroundScope)
        third.onFinished = {
            next.start()
            reopened = next
        }
        third.start()
        runCurrent()
        third.hostGone()
        assertNull(assertNotNull(reopened).result.value, "the new sheet is not rejected")
        next.hostGone()
    }

    @Test
    fun `ST-06 builds PaymentSuccess`() {
        val paid = RawTransaction.fromJson(Fixtures.body("client-session-complete-200").getJSONObject("transaction")).toModel()
        val success = PaymentSuccess.from(paid, "card")
        assertEquals("trx_ClientSessN7aQ2LmX9k", success.id)
        assertEquals("CMD-4172", success.reference)
        assertEquals(1000.0, success.amount)
        assertEquals("NGN", success.currency)
        assertEquals("card", success.method)
        assertEquals("success", success.status)
        assertEquals("2026-10-14T09:30:00+00:00", success.timestamp)
        assertEquals("https://invoice.wajub.com/r/trx_ClientSessN7aQ2LmX9k?s=SIGNATURE", success.receiptUrl)

        val fee = RawSessionResponse.fromJson(Fixtures.body("session-payer-bears-fee")).toModel().transaction
        assertEquals(10350.0, PaymentSuccess.from(fee, "mobile_money").amount, "amount = amount_total")
        val updatedOnly = fee.copy(terminalAt = null)
        assertEquals("2026-10-14T09:30:00+00:00", PaymentSuccess.from(updatedOnly, "card").timestamp)
        assertEquals("1970-01-01T00:00:01Z", PaymentSuccess.from(updatedOnly.copy(updatedAt = null), "card", now = 1_000).timestamp)
    }

    @Test
    fun `ST-07 survives throwing callbacks`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-succeeded"))
        val results = mutableListOf<CheckoutResult>()
        val boom = { _: Any -> throw IllegalStateException("host bug") }
        val callbacks = CheckoutCallbacks(
            onReady = boom, onStateChange = boom, onPaymentStarted = boom, onSuccess = boom,
            onComplete = { results += it; throw IllegalStateException("host bug") },
        )
        com.wajub.mobile.checkout.ActiveSheet.clear()
        val controller = com.wajub.mobile.checkout.CheckoutController(
            session = fakeSession(api),
            options = PresentOptions(sessionToken = "t", callbacks = callbacks),
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            random = kotlin.random.Random(1),
            connector = FakeSocket(),
            compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            deviceLanguage = { "en" },
        )
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.SUCCESS, controller.state)
        controller.close()
        assertIs<CheckoutResult.Complete>(results.single())
    }
}
