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
import kotlin.test.assertFalse
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

        // A Stripe card is in flight from the press, while Stripe tokenizes it:
        // a dismiss resolves processing, and no charge leaves after the sheet closed.
        val stripe = cmApi().on("POST /pay/process", Fixtures.response("process-succeeded-200"))
        val (card, cardEvents) = checkout(stripe)
        card.start()
        runCurrent()
        card.selectMethod("card")
        card.payStripe {
            kotlinx.coroutines.delay(5_000)
            "pm_card_visa"
        }
        runCurrent()
        assertEquals(CheckoutState.PROCESSING, card.state)
        card.dismiss()
        runCurrent()
        assertIs<CheckoutResult.Processing>(cardEvents.result)
        advanceTimeBy(10_000)
        assertEquals(0, stripe.count("POST /pay/cancel"))
        assertEquals(0, stripe.count("POST /pay/process"), "nothing is sent once the sheet has closed")

        // A tokenizing error shows on the form.
        val (refused, _) = checkout(cmApi())
        refused.start()
        runCurrent()
        refused.selectMethod("card")
        refused.payStripe { throw IllegalStateException("Your card number is incorrect.") }
        runCurrent()
        val form = assertIs<SheetScreen.Form>(refused.screen.value)
        assertEquals("Your card number is incorrect.", form.banner)
        assertEquals(CheckoutState.COLLECTING_DETAILS, refused.state)
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
        // Already paid: the sheet resolves complete (with onSuccess), never CANCELLED then SUCCESS.
        assertIs<CheckoutResult.Complete>(buttonEvents.result)
        assertEquals(listOf("success:unknown", "complete:complete"), buttonEvents.events.takeLast(2))
        assertTrue("state:CANCELLED" !in buttonEvents.events)

        // A refused cancel whose status cannot be read: money may move, processing.
        val unreadable = cmApi()
            .set("POST /pay/cancel", Fixtures.response("cancel-400-not-cancellable"))
            .on("GET /pay/status", FakeResponse.network())
        val (blind, blindEvents) = checkout(unreadable)
        blind.start()
        runCurrent()
        blind.dismiss()
        advanceTimeBy(60_000)
        runCurrent()
        assertIs<CheckoutResult.Processing>(blindEvents.result)

        // Never while loading: the session has not answered, nothing to cancel.
        val loading = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate").let { FakeResponse(it.status, it.body, delayMs = 5_000) })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/cancel", Fixtures.response("cancel-200"))
        val (early, earlyEvents) = checkout(loading)
        early.start()
        runCurrent()
        early.dismiss()
        advanceTimeBy(10_000)
        assertEquals(CheckoutResult.Cancelled(CancelReason.Dismissed, null), earlyEvents.result)
        assertEquals(0, loading.count("POST /pay/cancel"))

        // Never on a processing session, decided as soon as the session answered (not after sdk-config).
        val processing = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-processing-mobile-money"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe").let { FakeResponse(it.status, it.body, delayMs = 8_000) })
            .on("POST /pay/cancel", Fixtures.response("cancel-200"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (approving, approvingEvents) = checkout(processing)
        approving.start()
        advanceTimeBy(2_000)
        approving.dismiss()
        advanceTimeBy(20_000)
        assertIs<CheckoutResult.Processing>(approvingEvents.result)
        assertEquals(0, processing.count("POST /pay/cancel"))

        // Never while an attempt is undecided: no "Cancel payment" on the approval screen, a dismiss resolves processing.
        val waiting = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (approval, approvalEvents) = checkout(waiting)
        approval.start()
        runCurrent()
        approval.selectMethod("mobile_money")
        assertTrue(approval.cancellable.value)
        approval.setPhone("670000000")
        approval.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.APPROVAL_REQUIRED, approval.state)
        assertFalse(approval.cancellable.value)
        approval.cancelPayment()
        approval.dismiss()
        runCurrent()
        assertIs<CheckoutResult.Processing>(approvalEvents.result)
        assertEquals(0, waiting.count("POST /pay/cancel"))

        // Nor on a confirmation step: the attempt waits for the payer's code.
        val step = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (otp, otpEvents) = checkout(step)
        otp.start()
        runCurrent()
        otp.selectMethod("mobile_money")
        otp.setPhone("670000000")
        otp.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.OTP_REQUIRED, otp.state)
        assertFalse(otp.cancellable.value)
        otp.dismiss()
        runCurrent()
        assertIs<CheckoutResult.Processing>(otpEvents.result)
        assertEquals(0, step.count("POST /pay/cancel"))

        // A definitive refusal (a decline, or a pending with a new error_code after processing) makes it
        // cancellable again: dismissing the failure screen releases the order, the result stays failed.
        for (refusal in listOf("decline", "attempt ended")) {
            val api = cmApi()
            if (refusal == "decline") {
                api.on("POST /pay/process", Fixtures.response("process-failed-402"))
            } else {
                api.on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
                    .on("GET /pay/status", Fixtures.response("status-processing"), Fixtures.response("status-pending-attempt-refused"))
            }
            val (refused, refusedEvents) = checkout(api)
            refused.start()
            runCurrent()
            refused.selectMethod("mobile_money")
            refused.setPhone("670000000")
            refused.payMobileMoney()
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(CheckoutState.FAILED, refused.state, refusal)
            refused.dismiss()
            runCurrent()
            assertIs<CheckoutResult.Failed>(refusedEvents.result, refusal)
            assertTrue(refusedEvents.events.none { it.startsWith("cancel:") }, "no onCancel: $refusal")
            assertEquals(1, api.count("POST /pay/cancel"), "the order is released: $refusal")
        }

        // Back on the form after Try again: a dismiss is a dismiss before any attempt.
        val again = cmApi().on("POST /pay/process", Fixtures.response("process-failed-402"))
        val (retry, retryEvents) = checkout(again)
        retry.start()
        runCurrent()
        retry.selectMethod("mobile_money")
        retry.setPhone("670000000")
        retry.payMobileMoney()
        runCurrent()
        retry.tryAgain()
        assertTrue(retry.cancellable.value)
        retry.dismiss()
        runCurrent()
        assertEquals(
            CheckoutResult.Cancelled(CancelReason.Dismissed, Fixtures.body("cancel-200").getString("redirect_url")),
            retryEvents.result,
        )
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

        // A sheet whose host cancelled its scope without settling it does not block the next one.
        val hostScope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        val abandoned = com.wajub.mobile.checkout.CheckoutController(fakeSession(cmApi()), PresentOptions(sessionToken = "t"), hostScope)
        com.wajub.mobile.checkout.ActiveSheet.clear()
        abandoned.start()
        runCurrent()
        hostScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        val after = com.wajub.mobile.checkout.CheckoutController(fakeSession(cmApi()), PresentOptions(sessionToken = "t"), backgroundScope)
        after.start()
        assertNull(after.result.value, "not rejected")
        after.hostGone()

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
        assertFalse("sk_" in PresentOptions(sessionToken = "sk_secret_token").toString(), "the token never prints")
        val updatedOnly = fee.copy(terminalAt = null)
        assertEquals("2026-10-14T09:30:00+00:00", PaymentSuccess.from(updatedOnly, "card").timestamp)
        assertEquals("1970-01-01T00:00:01Z", PaymentSuccess.from(updatedOnly.copy(updatedAt = null), "card", now = 1_000).timestamp)
    }

    @Test
    fun `ST-06 dates a watched success when it was seen`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-succeeded"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        runCurrent()
        val payment = assertIs<SheetScreen.Success>(controller.screen.value).payment
        // The answer's updated_at is the attempt's, not the success's: the time it was seen.
        assertTrue(payment.timestamp != "2026-10-14T09:30:00+00:00" && payment.timestamp.endsWith("Z"), payment.timestamp)
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
