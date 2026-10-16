package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.mapper.RawProcessResponse
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.model.PayerOtpInstructions
import com.wajub.mobile.model.PayerOtpPart
import com.wajub.mobile.model.PayerOtpStep
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the payer pays on each channel (CH-07, ST-06) and the code the payer generates (MM-11 to MM-13). */
@OptIn(ExperimentalCoroutinesApi::class)
class PayerContractTest {
    private val english = PresentOptions(sessionToken = "t", locale = "en")

    private fun feeApi(): FakeApi =
        FakeApi()
            .on("GET /pay/session", Fixtures.response("session-payer-fee-per-channel"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))

    @Test
    fun `CH-07 shows what the payer pays on the chosen channel`() = runTest {
        val session = RawSessionResponse.fromJson(Fixtures.body("session-payer-fee-per-channel")).toModel()
        val aggregate = session.channels.first { it.slug == "cm.mobile" }
        assertNull(aggregate.amountTotal, "its operators differ: none on the aggregate")
        assertEquals(listOf(200.0, 450.0), aggregate.operators.map { it.feeAmount })
        assertEquals(listOf(10200.0, 10450.0), aggregate.operators.map { it.amountTotal })
        assertEquals(10200.0, session.channels.first { it.slug == "card" }.amountTotal)

        val api = feeApi()
            .on("POST /pay/process", Fixtures.response("process-payer-fee-on-channel-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api, english)
        controller.start()
        runCurrent()
        assertIs<SheetScreen.Methods>(controller.screen.value)
        assertEquals(10200.0, controller.amountDue(), "before a method: the payment's amount_total")
        controller.selectMethod("mobile_money")
        assertEquals("mtn", assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.selected)
        assertEquals(10200.0, controller.amountDue(), "the initial selection's price")
        controller.setPhone("690000000")
        assertEquals("orange", assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.selected)
        assertEquals(10450.0, controller.amountDue(), "it follows the operator the number names")
        controller.pickOperator("mtn")
        assertEquals(10200.0, controller.amountDue())
        controller.pickOperator("orange")
        controller.payMobileMoney()
        runCurrent()
        assertEquals("orange", api.lastBody("POST /pay/process").getJSONObject("data").getString("operator"))
        assertIs<SheetScreen.Approval>(controller.screen.value)
        assertEquals(10450.0, controller.amountDue(), "after a 2xx: what the answer charges")

        // The card has its own price.
        val (card, _) = checkout(feeApi(), english)
        card.start()
        runCurrent()
        card.selectMethod("card")
        assertEquals(10200.0, card.amountDue())

        // Without these fields: the payment's amount_total, as before.
        val (plain, _) = checkout(cmApi(), english)
        plain.start()
        runCurrent()
        plain.selectMethod("mobile_money")
        plain.setPhone("690000000")
        assertEquals(plain.transaction!!.payableAmount, plain.amountDue())

        // CH-05 takes the operator's own total: Orange's 10 450 is over a 10 300 limit, MTN's 10 200 is not.
        val limited = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-payer-fee-per-channel") { body ->
                body.getJSONArray("channels").getJSONObject(0).put("max_amount", 10300)
            })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
        val (capped, _) = checkout(limited, english)
        capped.start()
        runCurrent()
        capped.selectMethod("mobile_money")
        val form = assertIs<SheetScreen.Form>(capped.screen.value).mobileMoney!!
        assertEquals(listOf(false, true), form.choices.map { capped.outOfLimits(it) })
    }

    @Test
    fun `ST-06 builds PaymentSuccess from what the channel charged`() = runTest {
        val api = feeApi()
            .on("POST /pay/process", Fixtures.response("process-payer-fee-on-channel-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-succeeded"))
        val (controller, _) = checkout(api, english)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("690000000")
        controller.payMobileMoney()
        runCurrent()
        val payment = assertIs<SheetScreen.Success>(controller.screen.value).payment
        assertEquals(10450.0, payment.amount, "Orange's price, not the session's 10 200")
        assertEquals("XAF", payment.currency)
    }

    // The Burkina Faso session; [announced] false: the channel does not say it asks for a code.
    private fun bfApi(announced: Boolean = true): FakeApi =
        FakeApi()
            .on("GET /pay/session", Fixtures.response("session-payer-otp-announced") { body ->
                if (!announced) body.getJSONArray("channels").getJSONObject(0).remove("payer_otp")
            })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("POST /pay/cancel", Fixtures.response("cancel-200"))

    private fun TestScope.bfCheckout(api: FakeApi, recorder: Recorder = Recorder(), options: PresentOptions = english) =
        checkout(api, options, recorder).first.also { controller ->
            controller.start()
            runCurrent()
            controller.selectMethod("mobile_money")
            controller.setPhone("70000000")
        }

    @Test
    fun `MM-11 asks for the payer's code before the charge`() = runTest {
        val api = bfApi()
            .on("POST /pay/process", Fixtures.response("process-payer-otp-accepted-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val recorder = Recorder()
        val controller = bfCheckout(api, recorder)
        val form = assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!
        assertEquals("*144*4*6#", form.selectedChoice!!.payerOtp!!.ussdCode, "the form says a code will be asked")
        controller.payMobileMoney()
        runCurrent()
        val screen = assertIs<SheetScreen.PayerOtp>(controller.screen.value)
        assertEquals(CheckoutState.PAYER_OTP_REQUIRED, controller.state)
        assertEquals(0, api.count("POST /pay/process"), "no request before the code")
        assertTrue(recorder.events.none { it.startsWith("started:") })
        assertEquals("1000", screen.instructions.amount)
        assertEquals("123456", screen.sandboxCode)

        controller.setPayerOtpCode("123456")
        controller.submitPayerOtp()
        runCurrent()
        val sent = api.lastBody("POST /pay/process")
        assertEquals("bf.orange", sent.getString("channel"))
        assertEquals("123456", sent.getJSONObject("data").getString("otp"), "the code goes with the first request")
        assertEquals("+22670000000", sent.getJSONObject("data").getString("phone"))
        assertTrue(assertIs<SheetScreen.Approval>(controller.screen.value).codeReceived)
        assertEquals(listOf("started:mobile_money:bf.orange"), recorder.events.filter { it.startsWith("started:") })

        // An aggregate's operator is never announced: Pay sends the request.
        val (plain, _) = checkout(cmApi().on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox")), english)
        plain.start()
        runCurrent()
        plain.selectMethod("mobile_money")
        plain.setPhone("670000000")
        assertNull(assertIs<SheetScreen.Form>(plain.screen.value).mobileMoney!!.selectedChoice!!.payerOtp)
    }

    @Test
    fun `MM-12 asks for the payer's code the charge asks for`() = runTest {
        val api = bfApi(announced = false)
            .on("POST /pay/process", Fixtures.response("process-payer-otp-required"), Fixtures.response("process-payer-otp-accepted-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val recorder = Recorder()
        val controller = bfCheckout(api, recorder)
        controller.payMobileMoney()
        runCurrent()
        val screen = assertIs<SheetScreen.PayerOtp>(controller.screen.value)
        assertEquals(listOf("INITIATED", "COLLECTING_DETAILS", "PROCESSING", "PAYER_OTP_REQUIRED"), recorder.states)
        assertTrue(recorder.events.none { it.startsWith("started:") }, "nothing was charged")
        val instructions = screen.instructions
        assertEquals("*144*4*6#", instructions.ussdCode)
        assertEquals(6..6, instructions.codeLengths)
        assertEquals("1000", instructions.amount)
        assertEquals("XOF", instructions.currency)
        val steps = instructions.stepsIn(WajubLanguage.En)
        assertEquals(
            listOf(
                PayerOtpPart("On the phone of the Orange Money account, dial ", PayerOtpPart.Kind.Text),
                PayerOtpPart("*144*4*6#", PayerOtpPart.Kind.UssdCode),
                PayerOtpPart(".", PayerOtpPart.Kind.Text),
            ),
            steps[0],
        )
        assertEquals(
            listOf(
                PayerOtpPart("Enter the exact amount: ", PayerOtpPart.Kind.Text),
                PayerOtpPart("1000", PayerOtpPart.Kind.Amount),
                PayerOtpPart(" ", PayerOtpPart.Kind.Text),
                PayerOtpPart("XOF", PayerOtpPart.Kind.Text),
                PayerOtpPart(".", PayerOtpPart.Kind.Text),
            ),
            steps[1],
        )
        assertEquals("Saisissez votre code PIN Orange Money.", instructions.stepsIn(WajubLanguage.Fr)[2].single().text)

        // Checked before sending: digits, the code's length.
        controller.setPayerOtpCode("12")
        controller.submitPayerOtp()
        runCurrent()
        assertEquals("The code must have 6 digits.", assertIs<SheetScreen.PayerOtp>(controller.screen.value).error)
        assertEquals(1, api.count("POST /pay/process"))
        controller.setPayerOtpCode("1234567890")
        assertEquals("123456", assertIs<SheetScreen.PayerOtp>(controller.screen.value).code, "at most its length")
        // Never sent on its own.
        runCurrent()
        assertEquals(1, api.count("POST /pay/process"))

        controller.submitPayerOtp()
        runCurrent()
        val (first, second) = api.requests("POST /pay/process")
        assertNotEquals(first.headers["Idempotency-Key"], second.headers["Idempotency-Key"], "a new key")
        val data = api.lastBody("POST /pay/process").getJSONObject("data")
        assertEquals("123456", data.getString("otp"))
        assertEquals("+22670000000", data.getString("phone"))
        assertEquals("BF", data.getString("country"))
        assertIs<SheetScreen.Approval>(controller.screen.value)
        assertEquals(CheckoutState.APPROVAL_REQUIRED, controller.state)
        assertEquals(listOf("started:mobile_money:bf.orange"), recorder.events.filter { it.startsWith("started:") })

        // "Use another payment method": the Mobile Money form again.
        val back = bfCheckout(bfApi(announced = false).on("POST /pay/process", Fixtures.response("process-payer-otp-required")))
        back.payMobileMoney()
        runCurrent()
        back.leavePayerOtp()
        assertIs<SheetScreen.Form>(back.screen.value)
        assertEquals(CheckoutState.COLLECTING_DETAILS, back.state)

        // Steps: the sheet's language, else English, else French; the plain text when the template names
        // something else or a value is missing.
        val bare = PayerOtpInstructions(
            channel = "bf.orange", ussdCode = null, otpLength = null, amount = "20.5", currency = "XOF",
            steps = mapOf(
                "fr" to listOf(PayerOtpStep("Composez le code.", "Composez {ussd_code}.")),
                "en" to listOf(PayerOtpStep("Dial the code.", "Dial {ussd_code}."), PayerOtpStep("Pay 20.5.", "Pay {amount} {unknown}.")),
            ),
        )
        assertEquals(listOf("Dial the code.", "Pay 20.5."), bare.stepsIn(WajubLanguage.En).map { it.single().text })
        assertEquals(listOf("Composez le code."), bare.stepsIn(WajubLanguage.Fr).map { it.single().text })
        assertEquals(listOf("Dial the code.", "Pay 20.5."), bare.copy(steps = bare.steps - "fr").stepsIn(WajubLanguage.Fr).map { it.single().text })
        assertEquals(emptyList(), bare.copy(steps = emptyMap()).stepsIn(WajubLanguage.En))
        assertEquals(4..8, bare.codeLengths)
        val parsed = assertIs<ProcessOutcome.RequiresAction>(
            com.wajub.mobile.mapper.OutcomeMapper.process(
                com.wajub.mobile.client.ApiResponse(202, Fixtures.body("process-payer-otp-required"), null, null),
                mobileMoney = true,
                language = WajubLanguage.En,
            ) { error("not an error") },
        )
        assertEquals("payer_otp", parsed.action)
        assertEquals("required", parsed.payerOtp!!.reason)
        assertEquals(3, parsed.payerOtp!!.attemptsRemaining)
        assertEquals("*144*4*6#", RawProcessResponse.fromJson(Fixtures.body("process-payer-otp-required")).payerOtp!!.ussdCode)
    }

    @Test
    fun `MM-13 handles refused payer codes`() = runTest {
        val api = bfApi()
            .on(
                "POST /pay/process",
                Fixtures.response("process-payer-otp-invalid"),
                Fixtures.response("process-payer-otp-refused"),
                Fixtures.response("process-402-payer-otp-attempts-exceeded"),
            )
        val recorder = Recorder()
        val controller = bfCheckout(api, recorder)
        controller.payMobileMoney()
        controller.setPayerOtpCode("654321")
        controller.submitPayerOtp()
        runCurrent()
        val invalid = assertIs<SheetScreen.PayerOtp>(controller.screen.value)
        assertEquals("The code must have 6 digits.", invalid.error)
        assertEquals("", invalid.code, "the field is emptied")
        assertNull(invalid.attemptsRemaining)

        controller.setPayerOtpCode("654321")
        controller.submitPayerOtp()
        runCurrent()
        val refused = assertIs<SheetScreen.PayerOtp>(controller.screen.value)
        assertEquals("Your operator did not accept this code. Generate a new one and try again.", refused.error)
        assertEquals(2, refused.attemptsRemaining)
        assertEquals("", refused.code)
        assertTrue(recorder.events.none { it.startsWith("error:") }, "inline refusals are not onError")

        controller.setPayerOtpCode("654323")
        controller.submitPayerOtp()
        runCurrent()
        val form = assertIs<SheetScreen.Form>(controller.screen.value)
        assertEquals("Your operator refused too many codes for this payment. Choose another payment method.", form.banner)
        assertEquals(CheckoutState.COLLECTING_DETAILS, controller.state)
        assertEquals(listOf("error:otp_attempts_exceeded"), recorder.events.filter { it.startsWith("error:") })
        assertTrue(controller.cancellable.value, "still payable, and cancellable")

        // While watching after the code: refused later, then too many refused.
        for ((code, expectForm) in listOf("invalid_otp" to false, "otp_attempts_exceeded" to true)) {
            val watched = bfApi()
                .on("POST /pay/process", Fixtures.response("process-payer-otp-accepted-sandbox"))
                .on(
                    "GET /pay/status",
                    Fixtures.response("status-processing"),
                    Fixtures.response("status-pending-payer-otp-refused") { body ->
                        body.getJSONObject("transaction").put("error_code", code)
                    },
                )
            val events = Recorder()
            val later = bfCheckout(watched, events, options = PresentOptions(sessionToken = "t", locale = "fr"))
            later.payMobileMoney()
            later.setPayerOtpCode("123456")
            later.submitPayerOtp()
            runCurrent()
            assertIs<SheetScreen.Approval>(later.screen.value)
            advanceTimeBy(10_000)
            if (expectForm) {
                assertEquals(
                    "Votre opérateur a refusé trop de codes pour ce paiement. Choisissez un autre moyen de paiement.",
                    assertIs<SheetScreen.Form>(later.screen.value).banner,
                )
                assertEquals(listOf("error:otp_attempts_exceeded"), events.events.filter { it.startsWith("error:") })
            } else {
                val again = assertIs<SheetScreen.PayerOtp>(later.screen.value)
                assertEquals("Votre opérateur n'a pas accepté ce code. Générez-en un nouveau et réessayez.", again.error)
                assertNull(again.attemptsRemaining, "the count is unknown there")
                assertEquals(CheckoutState.PAYER_OTP_REQUIRED, later.state)
                assertTrue(events.events.none { it.startsWith("error:") })
            }
        }
    }

    @Test
    fun `CH-07 shows the selection's amount again after a code step`() = runTest {
        val charged = Fixtures.body("process-payer-fee-on-channel-sandbox").getJSONObject("transaction")
            .put("status", "pending").put("amount_collected", 0).put("remaining_amount", 10450)
        val api = feeApi()
            .on("POST /pay/process", Fixtures.response("process-payer-otp-required") { it.put("transaction", charged) })
        val (controller, _) = checkout(api, english)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("690000000")
        controller.payMobileMoney()
        runCurrent()
        assertIs<SheetScreen.PayerOtp>(controller.screen.value)
        assertEquals(10450.0, controller.amountDue(), "the code is for Orange's price")
        controller.leavePayerOtp()
        controller.pickOperator("mtn")
        assertEquals(10200.0, controller.amountDue(), "the selection's again")
        controller.changeMethod()
        assertIs<SheetScreen.Methods>(controller.screen.value)
        assertEquals(10200.0, controller.amountDue(), "not the price of the attempt that ended")
    }

    @Test
    fun `MM-13 keeps a refused code as the baseline`() = runTest {
        val api = bfApi()
            .on("POST /pay/process", Fixtures.response("process-payer-otp-refused"), FakeResponse.status(500))
            .on("GET /pay/status", Fixtures.response("status-pending-payer-otp-refused"))
        val recorder = Recorder()
        val controller = bfCheckout(api, recorder)
        controller.payMobileMoney()
        controller.setPayerOtpCode("654321")
        controller.submitPayerOtp()
        runCurrent()
        assertEquals(2, assertIs<SheetScreen.PayerOtp>(controller.screen.value).attemptsRemaining)
        // The next code gets no answer; the status still carries the refusal of the last answer: not news.
        controller.setPayerOtpCode("111111")
        controller.submitPayerOtp()
        runCurrent()
        advanceTimeBy(30_000)
        assertEquals(CheckoutState.PENDING, controller.state)
        advanceTimeBy(40_000)
        assertIs<SheetScreen.Form>(controller.screen.value)
        assertTrue("FAILED" !in recorder.states)
        assertEquals(listOf("error:network_error"), recorder.events.filter { it.startsWith("error:") })
    }

    @Test
    fun `MM-13 sends a refusal no code of this sheet led to back to the form`() = runTest {
        // (a) A sheet reopened on an approval under way: the code was sent from elsewhere.
        val reopened = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-payer-otp-announced") { body ->
                body.getJSONObject("transaction").put("status", "processing").put(
                    "processing_context",
                    org.json.JSONObject().put("channel_slug", "bf.orange").put("is_mobile_money", true),
                )
            })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-pending-payer-otp-refused"))
        val recorder = Recorder()
        val (controller, _) = checkout(reopened, english, recorder)
        controller.start()
        runCurrent()
        advanceTimeBy(10_000)
        val form = assertIs<SheetScreen.Form>(controller.screen.value)
        assertEquals("Your operator did not accept this code. Generate a new one and try again.", form.banner)
        assertEquals(CheckoutState.COLLECTING_DETAILS, controller.state)
        assertTrue(recorder.events.none { it.startsWith("error:") }, "not FAILED, no onError")
        // The payment's form, as on a first opening: its country and operators.
        assertEquals("BF", form.mobileMoney!!.country)
        assertEquals(listOf("orange"), form.mobileMoney!!.choices.map { it.id })
        assertEquals("orange", form.mobileMoney!!.selected)

        // The same sheet, too many codes refused elsewhere: that form, the operator disabled.
        val exhausted = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-payer-otp-announced") { body ->
                body.getJSONObject("transaction").put("status", "processing").put(
                    "processing_context",
                    org.json.JSONObject().put("channel_slug", "bf.orange").put("is_mobile_money", true),
                )
            })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-pending-payer-otp-refused") { body ->
                body.getJSONObject("transaction").put("error_code", "otp_attempts_exceeded")
            })
        val exhaustedEvents = Recorder()
        val (gone, _) = checkout(exhausted, english, exhaustedEvents)
        gone.start()
        runCurrent()
        advanceTimeBy(10_000)
        val goneForm = assertIs<SheetScreen.Form>(gone.screen.value).mobileMoney!!
        assertEquals("BF", goneForm.country)
        assertEquals(listOf("orange"), goneForm.choices.map { it.id })
        assertEquals(setOf("orange"), goneForm.unavailable.keys)
        assertNull(goneForm.selected, "none left: no selection")
        assertEquals(listOf("error:otp_attempts_exceeded"), exhaustedEvents.events.filter { it.startsWith("error:") })

        // (b) A request with no answer, then that refusal: the same, never "{n}".
        val unanswered = bfApi(announced = false)
            .on("POST /pay/process", FakeResponse.status(500))
            .on("GET /pay/status", Fixtures.response("status-pending-payer-otp-refused"))
        val events = Recorder()
        val other = bfCheckout(unanswered, events)
        other.payMobileMoney()
        runCurrent()
        advanceTimeBy(10_000)
        val back = assertIs<SheetScreen.Form>(other.screen.value)
        assertEquals("Your operator did not accept this code. Generate a new one and try again.", back.banner)
        assertTrue(events.events.none { it.startsWith("error:") })
        assertTrue("FAILED" !in events.states)

        // A status refusal shown elsewhere never keeps a placeholder.
        val status = com.wajub.mobile.model.PaymentStatus("trx", "pending", "invalid_otp", null)
        assertTrue("{" !in com.wajub.mobile.realtime.PaymentWatcher.attemptEnded(status, WajubLanguage.En).message)
        assertTrue("{" !in com.wajub.mobile.realtime.PaymentWatcher.attemptEnded(status.copy(errorCode = "invalid_pin"), WajubLanguage.Fr).message)
    }

    @Test
    fun `MM-13 offers no exhausted channel again`() = runTest {
        val api = bfApi().on("POST /pay/process", Fixtures.response("process-402-payer-otp-attempts-exceeded"))
        val controller = bfCheckout(api)
        controller.payMobileMoney()
        controller.setPayerOtpCode("654323")
        controller.submitPayerOtp()
        runCurrent()
        val text = "Your operator refused too many codes for this payment. Choose another payment method."
        val form = assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!
        assertEquals(mapOf("orange" to text), form.unavailable, "shown disabled with that error's text")
        assertNull(form.selected)
        controller.pickOperator("orange")
        assertNull(assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.selected)
        controller.payMobileMoney()
        runCurrent()
        assertIs<SheetScreen.Form>(controller.screen.value, "Pay never reopens the code screen there")
        assertEquals(1, api.count("POST /pay/process"))
        // Still so on the form opened again.
        controller.changeMethod()
        controller.selectMethod("mobile_money")
        assertEquals(mapOf("orange" to text), assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.unavailable)

        // With another operator offered, the selection moves to it, and detection never brings it back.
        val two = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-payer-otp-announced") { body ->
                val channels = body.getJSONArray("channels")
                val moov = org.json.JSONObject(channels.getJSONObject(0).toString())
                    .put("id", "bf.moov").put("slug", "bf.moov").put("name", "Moov Money").put("name.fr", "Moov Money")
                moov.remove("payer_otp")
                channels.put(moov)
            })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("POST /pay/process", Fixtures.response("process-402-payer-otp-attempts-exceeded"))
        val (moved, _) = checkout(two, english)
        moved.start()
        runCurrent()
        moved.selectMethod("mobile_money")
        moved.setPhone("74123456")
        assertEquals("orange", assertIs<SheetScreen.Form>(moved.screen.value).mobileMoney!!.selected)
        moved.payMobileMoney()
        moved.setPayerOtpCode("654323")
        moved.submitPayerOtp()
        runCurrent()
        assertEquals("moov", assertIs<SheetScreen.Form>(moved.screen.value).mobileMoney!!.selected)
        moved.setPhone("74123457")
        assertEquals("moov", assertIs<SheetScreen.Form>(moved.screen.value).mobileMoney!!.selected, "an Orange number does not select Orange")
    }

    @Test
    fun `MM-03 never selects a disabled operator`() = runTest {
        // Orange's 10 450 is over a 10 300 limit: neither the first choice nor detection selects it.
        val limited = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-payer-fee-per-channel") { body ->
                val operators = body.getJSONArray("channels").getJSONObject(0).put("max_amount", 10300).getJSONArray("operators")
                // Orange first: the first choice offered is MTN.
                val mtn = operators.getJSONObject(0)
                operators.put(0, operators.getJSONObject(1)).put(1, mtn)
            })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
        val (controller, _) = checkout(limited, english)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        val form = assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!
        assertEquals(listOf("orange", "mtn"), form.choices.map { it.id })
        assertEquals("mtn", form.selected)
        controller.setPhone("690000000")
        assertEquals("mtn", assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.selected)
        controller.pickOperator("orange")
        assertEquals("mtn", assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.selected)
    }

    @Test
    fun `MM-13 takes missing fields and odd steps`() = runTest {
        // `action: payer_otp` without its instructions: unsupported, and nothing was charged.
        val api = bfApi(announced = false)
            .on("POST /pay/process", Fixtures.response("process-payer-otp-required") { it.remove("payer_otp") })
        val recorder = Recorder()
        val controller = bfCheckout(api, recorder)
        controller.payMobileMoney()
        runCurrent()
        assertEquals("unsupported_action", assertIs<SheetScreen.Failed>(controller.screen.value).error.code)
        assertTrue(recorder.events.none { it.startsWith("started:") }, "no onPaymentStarted")

        // No amount: nothing in its place. Plain-string steps; an empty list falls to the next language;
        // a length outside 1 to 12 is none.
        val odd = Fixtures.body("process-payer-otp-required").getJSONObject("payer_otp").apply {
            remove("amount")
            put("otp_length", 0)
            put(
                "steps",
                org.json.JSONObject()
                    .put("fr", org.json.JSONArray())
                    .put("en", org.json.JSONArray().put("Dial the code.").put(org.json.JSONObject().put("text", "Pay.").put("template", "Pay {amount}."))),
            )
        }
        val parsed = RawProcessResponse.fromJson(org.json.JSONObject().put("action", "payer_otp").put("payer_otp", odd)).payerOtp!!
        assertEquals("", parsed.amount)
        assertNull(parsed.otpLength)
        assertEquals(4..8, parsed.codeLengths)
        assertEquals(listOf("Dial the code.", "Pay."), parsed.stepsIn(WajubLanguage.Fr).map { it.single().text })
        for (length in listOf(-3, 13)) {
            assertNull(RawProcessResponse.fromJson(org.json.JSONObject().put("payer_otp", odd.put("otp_length", length))).payerOtp!!.otpLength)
        }
        assertEquals(12, RawProcessResponse.fromJson(org.json.JSONObject().put("payer_otp", odd.put("otp_length", 12))).payerOtp!!.otpLength)
    }

    @Test
    fun `MM-13 keeps the tries left and the code step`() = runTest {
        // The tries left stay after a code fails the local check.
        val api = bfApi().on("POST /pay/process", Fixtures.response("process-payer-otp-refused"))
        val controller = bfCheckout(api)
        controller.payMobileMoney()
        controller.setPayerOtpCode("654321")
        controller.submitPayerOtp()
        runCurrent()
        controller.setPayerOtpCode("12")
        controller.submitPayerOtp()
        val screen = assertIs<SheetScreen.PayerOtp>(controller.screen.value)
        assertEquals("The code must have 6 digits.", screen.error)
        assertEquals(2, screen.attemptsRemaining)
        // Typing again clears the refusal; the tries left stay.
        controller.setPayerOtpCode("1")
        assertNull(assertIs<SheetScreen.PayerOtp>(controller.screen.value).error)
        assertEquals(2, assertIs<SheetScreen.PayerOtp>(controller.screen.value).attemptsRemaining)

        // A negative count is none left.
        val negative = bfApi().on("POST /pay/process", Fixtures.response("process-payer-otp-refused") { body ->
            body.getJSONObject("payer_otp").put("attempts_remaining", -1)
        })
        val none = bfCheckout(negative)
        none.payMobileMoney()
        none.setPayerOtpCode("654321")
        none.submitPayerOtp()
        runCurrent()
        assertEquals(0, assertIs<SheetScreen.PayerOtp>(none.screen.value).attemptsRemaining)

        // A security-check retry of a code keeps the code step: the same code, the same flow.
        val secured = bfApi()
            .on("POST /pay/process", Fixtures.response("process-422-security-check-failed"), Fixtures.response("process-payer-otp-accepted-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val retried = bfCheckout(secured)
        retried.payMobileMoney()
        retried.setPayerOtpCode("123456")
        retried.submitPayerOtp()
        runCurrent()
        val sent = secured.requests("POST /pay/process")
        assertEquals(2, sent.size)
        assertEquals(listOf("123456", "123456"), sent.map { org.json.JSONObject(it.body!!).getJSONObject("data").getString("otp") })
        assertTrue(assertIs<SheetScreen.Approval>(retried.screen.value).codeReceived)
    }
}
