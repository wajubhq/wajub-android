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
}
