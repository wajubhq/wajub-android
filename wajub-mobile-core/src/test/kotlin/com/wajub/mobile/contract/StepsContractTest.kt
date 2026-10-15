package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.model.StepKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Confirmation steps (CF), client sessions (CS) and redirects (RD). */
@OptIn(ExperimentalCoroutinesApi::class)
class StepsContractTest {
    private val english = PresentOptions(sessionToken = "test_tok", locale = "en")

    // A payment whose /pay/process answers [answer]: a Mobile Money form submitted.
    private fun TestScope.answered(api: FakeApi, recorder: Recorder = Recorder(), options: PresentOptions = english): CheckoutController {
        api.on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api, options, recorder = recorder)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("677123456")
        controller.payMobileMoney()
        runCurrent()
        return controller
    }

    // The sandbox test-card form submitted, answered with [answer].
    private fun TestScope.sandboxCard(api: FakeApi, recorder: Recorder = Recorder()): CheckoutController {
        api.on("GET /pay/session", Fixtures.response("session-sandbox-cm"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api, english, recorder = recorder)
        controller.start()
        runCurrent()
        controller.selectMethod("card")
        mapOf("card.number" to "4000056655665556", "card_holder" to "Awa Ngono", "expiry_month" to "12", "expiry_year" to "30", "cvv" to "123")
            .forEach { (k, v) -> controller.setCardField(k, v) }
        controller.payCard()
        runCurrent()
        return controller
    }

    private val steps = listOf(
        Triple("process-requires-otp", StepKind.Otp, mapOf("code" to "123456")),
        Triple("process-requires-pin", StepKind.Pin, mapOf("pin" to "1234")),
        Triple("process-requires-phone", StepKind.Phone, mapOf("phone" to "08012345678")),
        Triple("process-requires-birthday", StepKind.Birthday, mapOf("birthday" to "1990-04-21")),
        Triple("process-requires-address", StepKind.Address, mapOf("address" to "12 Admiralty Way", "city" to "Lagos", "state" to "Lagos", "zip_code" to "101233")),
    )

    private val states = mapOf(
        StepKind.Otp to CheckoutState.OTP_REQUIRED,
        StepKind.Pin to CheckoutState.PIN_REQUIRED,
        StepKind.Phone to CheckoutState.PHONE_REQUIRED,
        StepKind.Birthday to CheckoutState.BIRTHDAY_REQUIRED,
        StepKind.Address to CheckoutState.ADDRESS_REQUIRED,
    )

    @Test
    fun `CF-01 opens each confirmation step`() = runTest {
        for ((fixture, kind, values) in steps) {
            val success = "${kind.path}-verify-success"
            val api = cmApi().on("POST /pay/process", Fixtures.response(fixture)).on("POST /pay/${kind.path}/verify", Fixtures.response(success))
            val controller = answered(api)
            val step = assertIs<SheetScreen.Step>(controller.screen.value, fixture)
            assertEquals(kind, step.kind)
            assertEquals("prc_7hQm2LxV9pR4sT1wYb3N", step.stepId)
            assertEquals(states[kind], controller.state)
            val hintKey = if (kind == StepKind.Otp) "otp_masked_contact" else "${kind.path}_instruction"
            assertEquals(Fixtures.body(fixture).getString(hintKey), step.hint)
            values.forEach { (k, v) -> controller.setStepField(k, v) }
            controller.submitStep()
            assertEquals(CheckoutState.VERIFYING, controller.state)
            runCurrent()
            val body = api.lastBody("POST /pay/${kind.path}/verify")
            assertEquals("prc_7hQm2LxV9pR4sT1wYb3N", body.getString(kind.idKey))
            values.forEach { (k, v) -> assertEquals(v, body.getString(k), "$fixture $k") }
        }
    }

    @Test
    fun `CF-02 reads the transaction after a step`() = runTest {
        for ((fixture, kind, values) in steps) {
            val api = cmApi()
                .on("POST /pay/process", Fixtures.response(fixture))
                .on("POST /pay/${kind.path}/verify", Fixtures.response("${kind.path}-verify-success"))
            val controller = answered(api)
            values.forEach { (k, v) -> controller.setStepField(k, v) }
            val polls = api.count("GET /pay/status")
            controller.submitStep()
            runCurrent()
            val status = Fixtures.body("${kind.path}-verify-success").getJSONObject("transaction").getString("status")
            if (status == "succeeded") {
                // A step the provider settles at once (2.1): paid.
                assertEquals(CheckoutState.SUCCESS, controller.state, fixture)
            } else {
                // `success` with a transaction still processing (2.0 answers): waiting, not paid.
                assertEquals(CheckoutState.PENDING, controller.state, fixture)
                assertTrue(api.count("GET /pay/status") > polls, "watching")
            }
        }
        val paid = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("POST /pay/otp/verify", Fixtures.response("otp-verify-success") { it.getJSONObject("transaction").put("status", "succeeded") })
        val controller = answered(paid)
        controller.setStepField("code", "123456")
        controller.submitStep()
        runCurrent()
        assertEquals(CheckoutState.SUCCESS, controller.state)

        // The status decides first: a verify answer arriving later changes nothing.
        val late = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("POST /pay/otp/verify", Fixtures.response("otp-verify-success").let { FakeResponse(it.status, it.body, delayMs = 8_000) })
        val events = Recorder()
        val racing = answered(late, events)
        late.set("GET /pay/status", Fixtures.response("status-succeeded"))
        racing.setStepField("code", "123456")
        racing.submitStep()
        advanceTimeBy(5_001)
        assertEquals(CheckoutState.SUCCESS, racing.state)
        advanceTimeBy(5_000)
        assertEquals(CheckoutState.SUCCESS, racing.state)
        assertEquals(1, events.events.count { it.startsWith("success:") })
    }

    @Test
    fun `CF-03 handles step refusals`() = runTest {
        val invalid = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("POST /pay/otp/verify", Fixtures.response("otp-verify-invalid"))
        val recorder = Recorder()
        val otp = answered(invalid, recorder)
        otp.setStepField("code", "000000")
        otp.submitStep()
        runCurrent()
        val stay = assertIs<SheetScreen.Step>(otp.screen.value)
        assertEquals("Incorrect code. 2 attempts left.", stay.error)
        assertEquals(2, stay.attemptsRemaining)
        assertEquals(CheckoutState.OTP_REQUIRED, otp.state)
        assertTrue(recorder.events.none { it.startsWith("error:") })

        val pin = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-pin"))
            .on("POST /pay/pin/verify", Fixtures.response("pin-verify-invalid"))
        val pinController = answered(pin, options = PresentOptions(sessionToken = "t"))
        pinController.setStepField("pin", "0000")
        pinController.submitStep()
        runCurrent()
        assertEquals("Code PIN incorrect. Encore 2 essais.", assertIs<SheetScreen.Step>(pinController.screen.value).error)

        for (fixture in listOf("otp-verify-max-attempts", "otp-verify-expired")) {
            val api = cmApi()
                .on("POST /pay/process", Fixtures.response("process-requires-otp"))
                .on("POST /pay/otp/verify", Fixtures.response(fixture))
            val events = Recorder()
            val controller = answered(api, events)
            controller.setStepField("code", "000000")
            controller.submitStep()
            runCurrent()
            val failed = assertIs<SheetScreen.Failed>(controller.screen.value, fixture)
            assertEquals(Fixtures.body(fixture).getJSONObject("error").getString("code"), failed.error.code)
            assertTrue(failed.error.retryable, "the payer can start again")
            assertEquals(CheckoutState.FAILED, controller.state)
            assertTrue("error:${failed.error.code}" in events.events)
        }

        // The session ended meanwhile (410): expired.
        val gone = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("POST /pay/otp/verify", Fixtures.response("error-410-process-session-ended"))
        val ended = answered(gone)
        ended.setStepField("code", "123456")
        ended.submitStep()
        runCurrent()
        assertEquals(CheckoutState.EXPIRED, ended.state)

        // A 5xx says nothing about the step: stay on it, read the status, let the payer try again.
        val down = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("POST /pay/otp/verify", Fixtures.response("error-500-production"))
        val stuck = answered(down)
        val polls = down.count("GET /pay/status")
        stuck.setStepField("code", "123456")
        stuck.submitStep()
        runCurrent()
        assertEquals(CheckoutState.OTP_REQUIRED, stuck.state)
        assertEquals("Service temporarily unavailable.", assertIs<SheetScreen.Step>(stuck.screen.value).error)
        assertTrue(down.count("GET /pay/status") > polls)
    }

    @Test
    fun `CF-04 shows step validation errors`() = runTest {
        val address = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-address"))
            .on("POST /pay/address/verify", Fixtures.response("address-verify-422-missing-fields"))
        val controller = answered(address)
        mapOf("address" to "12 Admiralty Way", "city" to "x", "state" to "y", "zip_code" to "z").forEach { (k, v) -> controller.setStepField(k, v) }
        controller.submitStep()
        runCurrent()
        val step = assertIs<SheetScreen.Step>(controller.screen.value)
        assertEquals(setOf("city", "state", "zip_code"), step.fieldErrors.keys)
        assertEquals("The city field is required.", step.fieldErrors["city"])
        assertEquals(CheckoutState.ADDRESS_REQUIRED, controller.state)

        val birthday = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-birthday"))
            .on("POST /pay/birthday/verify", Fixtures.response("birthday-verify-422-format"))
        val other = answered(birthday)
        other.setStepField("birthday", "1990-21-04")
        other.submitStep()
        runCurrent()
        assertEquals(setOf("birthday"), assertIs<SheetScreen.Step>(other.screen.value).fieldErrors.keys)

        // Checked before sending: nothing goes out with an empty field.
        val empty = cmApi().on("POST /pay/process", Fixtures.response("process-requires-address"))
        val emptyController = answered(empty)
        emptyController.submitStep()
        assertEquals(4, assertIs<SheetScreen.Step>(emptyController.screen.value).fieldErrors.size)
        assertEquals(0, empty.count("POST /pay/address/verify"))
    }

    @Test
    fun `CF-05 resends after the countdown`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-otp"))
            .on("POST /pay/otp/resend", Fixtures.response("otp-resend-200"))
        val controller = answered(api)
        val opened = testScheduler.currentTime
        assertEquals(opened + 60_000, assertIs<SheetScreen.Step>(controller.screen.value).resendAt)
        controller.resendStep()
        runCurrent()
        assertEquals(0, api.count("POST /pay/otp/resend"), "not before 60 s")
        advanceTimeBy(60_000)
        controller.resendStep()
        runCurrent()
        assertEquals(1, api.count("POST /pay/otp/resend"))
        assertEquals("prc_7hQm2LxV9pR4sT1wYb3N", api.lastBody("POST /pay/otp/resend").getString("otp_id"))
        assertEquals(testScheduler.currentTime + 60_000, assertIs<SheetScreen.Step>(controller.screen.value).resendAt, "the countdown restarts")

        // Every step with a resend endpoint offers it.
        val pin = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-pin"))
            .on("POST /pay/pin/resend", FakeResponse.status(200, """{"code":200,"status":"OK","message":"Sent."}"""))
        val pinStep = answered(pin)
        advanceTimeBy(60_000)
        pinStep.resendStep()
        runCurrent()
        assertEquals("prc_7hQm2LxV9pR4sT1wYb3N", pin.lastBody("POST /pay/pin/resend").getString("pin_id"))
    }

    @Test
    fun `CF-06 handles the sandbox 3ds code`() = runTest {
        val api = FakeApi()
            .on("POST /pay/process", Fixtures.response("process-requires-3ds-sandbox"))
            .on(
                "POST /pay/3ds/verify",
                Fixtures.response("3ds-verify-422-invalid-code"),
                Fixtures.response("3ds-verify-500-invalid-code-api-bug"),
                Fixtures.response("3ds-verify-202"),
            )
            .on("POST /pay/3ds/send", Fixtures.response("3ds-send-200"))
        val controller = sandboxCard(api)
        val sentCard = api.lastBody("POST /pay/process").getJSONObject("data")
        assertEquals("4000056655665556", sentCard.getString("card.number"), "the sandbox test card")
        val step = assertIs<SheetScreen.Step>(controller.screen.value)
        assertEquals(StepKind.ThreeDs, step.kind)
        assertEquals("aw*******@example.com", step.hint)
        assertEquals(testScheduler.currentTime + 600_000, step.expiresAt)
        assertEquals(CheckoutState.THREE_DS_REQUIRED, controller.state)

        // For an app's own UI too: the code step is a step, 3-D Secure with a page is a page.
        val own = fakeSession(
            FakeApi()
                .on("GET /pay/session", Fixtures.response("session-sandbox-cm"))
                .on("POST /pay/process", Fixtures.response("process-requires-3ds-sandbox")),
        )
            .process(com.wajub.mobile.model.ProcessRequest("card", mapOf("card.number" to "4000056655665556")))
        val code = assertIs<com.wajub.mobile.model.ProcessOutcome.RequiresAction>(own)
        assertEquals(StepKind.ThreeDs, code.step)
        assertEquals("5f0c2b1e-8d4a-4c3b-9e7f-1a2b3c4d5e6f", code.stepId)
        assertEquals(600, code.timeoutSeconds)
        val page = com.wajub.mobile.model.ProcessOutcome.RequiresAction("confirm_3ds", url = "https://3ds.example/acs")
        assertNull(page.step)

        // 422 retryable, then the API's 500: a wrong code each time, same screen.
        for (expected in listOf("Invalid verification code.", "Invalid verification code.")) {
            controller.setStepField("code", "000000")
            controller.submitStep()
            runCurrent()
            val wrong = assertIs<SheetScreen.Step>(controller.screen.value)
            assertEquals(expected, wrong.error)
            assertEquals(CheckoutState.THREE_DS_REQUIRED, controller.state)
        }
        controller.setStepField("code", "123456")
        controller.submitStep()
        runCurrent()
        assertEquals(CheckoutState.VERIFYING, controller.state, "accepted: verifying while the status decides")
        assertEquals("5f0c2b1e-8d4a-4c3b-9e7f-1a2b3c4d5e6f", api.lastBody("POST /pay/3ds/verify").getString("three_ds_id"))

        // A new code, and the timeout.
        val timing = FakeApi().on("POST /pay/process", Fixtures.response("process-requires-3ds-sandbox")).on("POST /pay/3ds/send", Fixtures.response("3ds-send-200"))
        val waiting = sandboxCard(timing)
        advanceTimeBy(60_000)
        waiting.resendStep()
        runCurrent()
        assertEquals("5f0c2b1e-8d4a-4c3b-9e7f-1a2b3c4d5e6f", timing.lastBody("POST /pay/3ds/send").getString("three_ds_id"))
        advanceTimeBy(540_001)
        runCurrent()
        assertEquals("3ds_timeout", assertIs<SheetScreen.Failed>(waiting.screen.value).error.code)

        // The code expires while it is being checked: the answer decides first.
        for ((answer, expected) in listOf("3ds-verify-202" to CheckoutState.VERIFYING, "3ds-verify-422-invalid-code" to CheckoutState.FAILED)) {
            val api = FakeApi()
                .on("POST /pay/process", Fixtures.response("process-requires-3ds-sandbox"))
                .on("POST /pay/3ds/verify", Fixtures.response(answer).let { FakeResponse(it.status, it.body, delayMs = 30_000) })
            val step = sandboxCard(api)
            advanceTimeBy(590_000)
            step.setStepField("code", "123456")
            step.submitStep()
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(CheckoutState.VERIFYING, step.state, "not failed while its verify runs")
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(expected, step.state, answer)
            if (expected == CheckoutState.FAILED) assertEquals("3ds_timeout", assertIs<SheetScreen.Failed>(step.screen.value).error.code)
        }
    }

    @Test
    fun `CS-01 opens a client session`() = runTest {
        for (fixture in listOf("client-session-start-202", "client-session-start-202-flutterwave", "client-session-start-reserved")) {
            val api = FakeApi()
                .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
                .on("GET /pay/sdk-config", Fixtures.response("sdk-config-card-paystack"))
                .on("POST /pay/client-session", Fixtures.response(fixture))
                .on("GET /pay/status", Fixtures.response("status-processing"))
            val opened = mutableListOf<String>()
            val recorder = Recorder()
            val (controller, _) = checkout(api, english.copy(returnUrl = "https://shop.example.com/app-return"), recorder, opener = { opened += it; true })
            controller.start()
            runCurrent()
            controller.selectMethod("card")
            controller.payCard()
            runCurrent()
            val body = api.lastBody("POST /pay/client-session")
            assertEquals("card", body.getString("channel"))
            assertEquals("awa.ngono@example.com", body.getString("email"))
            assertEquals("https://shop.example.com/app-return", body.getString("return_url"))
            assertFalse(body.has("inline"), "inline is for web clients")
            val hosted = Fixtures.body(fixture).getJSONObject("client_session").getString("hosted_url")
            assertEquals(listOf(hosted), opened, fixture)
            assertTrue(recorder.events.containsAll(listOf("started:card:card", "redirect:$hosted")))
            assertEquals(CheckoutState.PENDING, controller.state)
            assertTrue(api.count("GET /pay/status") >= 1)
        }
    }

    @Test
    fun `CS-02 completes a client session`() = runTest {
        val cases = mapOf(
            "client-session-complete-200" to CheckoutState.SUCCESS,
            "client-session-complete-202" to CheckoutState.PENDING,
            "client-session-complete-402" to CheckoutState.FAILED,
            "client-session-complete-422-expired" to CheckoutState.FAILED,
        )
        for ((fixture, state) in cases) {
            val api = FakeApi()
                .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
                .on("GET /pay/sdk-config", Fixtures.response("sdk-config-card-paystack"))
                .on("POST /pay/client-session", Fixtures.response("client-session-start-202"))
                .on("POST /pay/client-session/complete", Fixtures.response(fixture))
                .on("GET /pay/status", Fixtures.response("status-processing"))
            val (controller, _) = checkout(api, english)
            controller.start()
            runCurrent()
            controller.selectMethod("card")
            controller.payCard()
            runCurrent()
            controller.onReturn()
            runCurrent()
            assertEquals("prc_7hQm2LxV9pR4sT1wYb3N", api.lastBody("POST /pay/client-session/complete").getString("client_session_id"))
            assertEquals(1, api.lastBody("POST /pay/client-session/complete").length(), "never a PSP reference")
            assertEquals(state, controller.state, fixture)
            if (state == CheckoutState.FAILED) {
                val error = assertIs<SheetScreen.Failed>(controller.screen.value).error
                assertTrue(error.retryable, fixture)
                assertEquals(if (fixture.endsWith("402")) "failed" else "client_session_expired", error.code)
                controller.tryAgain()
                assertIs<SheetScreen.Form>(controller.screen.value)
            }
        }

        // While /complete runs, a `pending` with an earlier refusal's error_code is held back:
        // /complete decides, and a success wins.
        for ((complete, expected) in listOf("client-session-complete-200" to CheckoutState.SUCCESS, "client-session-complete-202" to CheckoutState.FAILED)) {
            val api = FakeApi()
                .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
                .on("GET /pay/sdk-config", Fixtures.response("sdk-config-card-paystack"))
                .on("POST /pay/client-session", Fixtures.response("client-session-start-202"))
                .on("POST /pay/client-session/complete", Fixtures.response(complete).let { FakeResponse(it.status, it.body, delayMs = 4_000) })
                .on("GET /pay/status", Fixtures.response("status-processing"), Fixtures.response("status-pending-attempt-refused"))
            val (controller, _) = checkout(api, english)
            controller.start()
            runCurrent()
            controller.selectMethod("card")
            controller.payCard()
            runCurrent()
            controller.onReturn()
            runCurrent()
            assertEquals(CheckoutState.PENDING, controller.state, "the refusal waits for /complete")
            advanceTimeBy(4_001)
            runCurrent()
            assertEquals(expected, controller.state, complete)
        }

        // After process death: the saved client session is completed once the payer is back.
        val restored = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-processing-mobile-money"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
            .on("POST /pay/client-session/complete", Fixtures.response("client-session-complete-200"))
        val (revived, _) = checkout(restored, english)
        revived.restoreClientSession("prc_saved")
        revived.start()
        runCurrent()
        revived.onReturn()
        runCurrent()
        assertEquals("prc_saved", restored.lastBody("POST /pay/client-session/complete").getString("client_session_id"))
        assertEquals(CheckoutState.SUCCESS, revived.state)
    }

    @Test
    fun `CS-03 reports client sessions unavailable in sandbox`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-card-paystack"))
            .on("POST /pay/client-session", Fixtures.response("client-session-422-sandbox"))
        val recorder = Recorder()
        val (controller, _) = checkout(api, english, recorder)
        controller.start()
        runCurrent()
        controller.selectMethod("card")
        controller.payCard()
        advanceTimeBy(10_000)
        val error = assertIs<SheetScreen.Failed>(controller.screen.value).error
        assertEquals("client_session_unavailable", error.code)
        assertEquals("Card payment is not available in test mode for this provider.", error.message)
        assertFalse(error.retryable)
        assertEquals(1, api.count("POST /pay/client-session"), "not retried")
        assertTrue("error:client_session_unavailable" in recorder.events)
    }

    @Test
    fun `RD-02 sends the return url only when given`() = runTest {
        for (returnUrl in listOf("https://shop.example.com/app-return", null)) {
            val api = FakeApi()
                .on("GET /pay/session", Fixtures.response("session-ci-xof-wallets"))
                .on("GET /pay/sdk-config", Fixtures.response("sdk-config-ci-wallets"))
                .on("POST /pay/process", Fixtures.response("process-redirect-wallet-djamo"))
                .on("GET /pay/status", Fixtures.response("status-processing"))
            val (controller, _) = checkout(api, PresentOptions(sessionToken = "t", returnUrl = returnUrl))
            controller.start()
            runCurrent()
            controller.selectMethod("ci.djamo")
            controller.payWallet()
            runCurrent()
            val data = api.lastBody("POST /pay/process").getJSONObject("data")
            if (returnUrl == null) assertFalse(data.has("return_url")) else assertEquals(returnUrl, data.getString("return_url"))
        }
    }

    @Test
    fun `RD-03 polls on return`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-ci-xof-wallets"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-ci-wallets"))
            .on("POST /pay/process", Fixtures.response("process-redirect-wallet-djamo"))
            .on("GET /pay/status", Fixtures.response("status-processing"), Fixtures.response("status-succeeded"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("ci.djamo")
        controller.payWallet()
        runCurrent()
        assertEquals(1, api.count("GET /pay/status"))
        advanceTimeBy(1_000)
        controller.onReturn()
        runCurrent()
        assertEquals(2, api.count("GET /pay/status"), "at once, not at the next 5 s tick")
        assertEquals(CheckoutState.SUCCESS, controller.state)
        assertNull((controller.screen.value as? SheetScreen.Pending)?.url)
    }

    @Test
    fun `CF-07 follows a chained step`() = runTest {
        // A birthday, then a code the provider asks for: the OTP screen, then paid on the transaction's status.
        val chained = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-birthday"))
            .on("POST /pay/birthday/verify", Fixtures.response("birthday-verify-chained-otp"))
            .on("POST /pay/otp/verify", Fixtures.response("otp-verify-success"))
        val recorder = Recorder()
        val controller = answered(chained, recorder)
        assertEquals(CheckoutState.BIRTHDAY_REQUIRED, controller.state)
        controller.setStepField("birthday", "1990-04-21")
        controller.submitStep()
        runCurrent()
        val otp = assertIs<SheetScreen.Step>(controller.screen.value)
        assertEquals(StepKind.Otp, otp.kind)
        assertEquals(Fixtures.body("birthday-verify-chained-otp").getString("otp_id"), otp.stepId)
        assertEquals(Fixtures.body("birthday-verify-chained-otp").getString("otp_masked_contact"), otp.hint)
        assertNull(otp.attemptsRemaining, "a fresh attempt count")
        assertEquals(CheckoutState.OTP_REQUIRED, controller.state)
        assertTrue(recorder.events.none { it.startsWith("success:") }, "the birthday did not pay")
        controller.setStepField("code", "123456")
        controller.submitStep()
        runCurrent()
        assertEquals(otp.stepId, chained.lastBody("POST /pay/otp/verify").getString("otp_id"))
        assertEquals(CheckoutState.SUCCESS, controller.state)

        // A PIN, then a 3-D Secure page: onRedirect, the browser, PENDING.
        val page = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-pin"))
            .on("POST /pay/pin/verify", Fixtures.response("pin-verify-chained-redirect"))
        val pageEvents = Recorder()
        val pin = answered(page, pageEvents)
        pin.setStepField("pin", "1234")
        pin.submitStep()
        runCurrent()
        val url = Fixtures.body("pin-verify-chained-redirect").getString("confirm_url")
        assertTrue("redirect:$url" in pageEvents.events, "${pageEvents.events}")
        assertEquals(url, assertIs<SheetScreen.Pending>(pin.screen.value).url)
        assertEquals(CheckoutState.PENDING, pin.state)

        // A next step the sheet cannot take.
        val odd = cmApi()
            .on("POST /pay/process", Fixtures.response("process-requires-birthday"))
            .on("POST /pay/birthday/verify", Fixtures.response("birthday-verify-chained-otp") { it.put("action", "confirm_magic") })
        val magic = answered(odd)
        magic.setStepField("birthday", "1990-04-21")
        magic.submitStep()
        runCurrent()
        assertEquals("unsupported_action", assertIs<SheetScreen.Failed>(magic.screen.value).error.code)
    }
}
