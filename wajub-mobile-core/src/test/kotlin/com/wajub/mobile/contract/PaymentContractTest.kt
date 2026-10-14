package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CardFlavor
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.model.SdkChannelConfig
import com.wajub.mobile.model.WajubErrorType
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

/** Card (CD), wallets (WL) and the answers of `/pay/process` (PA). */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentContractTest {
    // session-cm-aggregate is a French session: these tests read English messages.
    private val english = PresentOptions(sessionToken = "test_tok", locale = "en")

    private suspend fun flavor(fixture: String, sandbox: Boolean): CardFlavor {
        val config = fakeSession(FakeApi().on("GET /pay/sdk-config", Fixtures.response(fixture))).getSdkConfig()
        return CardFlavor.decide(config.channels["card"], sandbox)
    }

    // A Cameroon checkout on its Mobile Money form, the number typed.
    private fun TestScope.momoCheckout(api: FakeApi, recorder: Recorder = Recorder()): CheckoutController {
        val (controller, _) = checkout(api, english, recorder = recorder)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("677123456")
        return controller
    }

    @Test
    fun `CD-01 picks the card flavour`() = runTest {
        assertEquals(CardFlavor.ClientSession(listOf("email")), flavor("sdk-config-card-paystack", sandbox = false))
        assertEquals(CardFlavor.ClientSession(emptyList()), flavor("sdk-config-card-adyen", sandbox = false), "Adyen's encrypted_* fields are not asked")
        assertEquals(CardFlavor.Stripe("pk_live_51Hx4mPLACEHOLDER"), flavor("sdk-config-cm-stripe", sandbox = false))
        assertEquals(
            CardFlavor.HostedRedirect(listOf("first_name", "last_name", "email", "phone", "address", "city", "country", "zip_code")),
            flavor("sdk-config-card-cinetpay", sandbox = false),
        )
        assertEquals(CardFlavor.HostedRedirect(listOf("email")), flavor("sdk-config-card-kkiapay", sandbox = false))
        assertEquals(CardFlavor.HostedRedirect(emptyList()), flavor("sdk-config-card-mollie", sandbox = false))
        assertEquals(CardFlavor.SandboxForm, flavor("sdk-config-sandbox", sandbox = true))
        assertEquals(CardFlavor.Hidden, flavor("sdk-config-sandbox", sandbox = false), "form in live: hidden")
        val stripe = SdkChannelConfig(available = true, sdk = "stripe_elements", publishableKey = "pk")
        assertEquals(CardFlavor.ClientSession(emptyList()), CardFlavor.decide(stripe.copy(clientSession = true), false), "client_session first")
        assertEquals(CardFlavor.Hidden, CardFlavor.decide(stripe.copy(available = false), false))
        assertEquals(CardFlavor.Hidden, CardFlavor.decide(stripe.copy(sdk = "adyen_custom_card"), false))
        assertEquals(CardFlavor.Hidden, CardFlavor.decide(stripe.copy(sdk = "psp_inline"), false))
        assertEquals(CardFlavor.Hidden, CardFlavor.decide(null, false))
    }

    @Test
    fun `CD-02 collects hosted card required fields`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-card-cinetpay"))
            .on("POST /pay/process", Fixtures.response("process-redirect-card-hosted"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val opened = mutableListOf<String>()
        val (controller, recorder) = checkout(api, PresentOptions(sessionToken = "t", returnUrl = "https://shop.example.com/app-return"), opener = { opened += it; true })
        controller.start()
        runCurrent()
        controller.selectMethod("card")
        val card = assertIs<SheetScreen.Form>(controller.screen.value).card!!
        assertEquals(listOf("first_name", "last_name", "email", "phone", "address", "city", "country", "zip_code"), card.fields)
        assertEquals("awa.ngono@example.com", card.values["email"], "the customer's email is prefilled")
        controller.payCard()
        assertEquals(setOf("first_name", "last_name", "phone", "address", "city", "country", "zip_code"), assertIs<SheetScreen.Form>(controller.screen.value).card!!.errors.keys)
        val values = mapOf(
            "first_name" to "Awa", "last_name" to "Ngono", "phone" to "+237677123456", "address" to "Rue 1",
            "city" to "Douala", "country" to "cm", "zip_code" to "00237",
        )
        values.forEach { (k, v) -> controller.setCardField(k, v) }
        controller.payCard()
        runCurrent()
        val data = api.lastBody("POST /pay/process").getJSONObject("data")
        assertEquals(values.keys + setOf("email", "return_url", "_client"), data.keys().asSequence().toSet(), "exactly the required fields, flat")
        assertEquals("CM", data.getString("country"))
        assertEquals("https://shop.example.com/app-return", data.getString("return_url"))
        assertEquals(listOf("https://checkout.psp.example/pay/abc123"), opened)
        assertTrue("redirect:https://checkout.psp.example/pay/abc123" in recorder.events)
        assertEquals(CheckoutState.PENDING, controller.state)
    }

    @Test
    fun `CD-03 never sends raw card data in live`() = runTest {
        // A live session whose card is a raw `form`: hidden, never sent.
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        val form = assertIs<SheetScreen.Form>(controller.screen.value)
        assertEquals("mobile_money", form.method.family, "the card is not offered")
        controller.payCard()
        assertEquals(0, api.count("POST /pay/process"))
        // Were it sent, the API refuses it; the SDK names the bug.
        val refused = cmApi().on("POST /pay/process", Fixtures.response("process-422-raw-card-data-live"))
        val outcome = fakeSession(refused).process(com.wajub.mobile.model.ProcessRequest("card", mapOf("card.number" to "4242")))
        val error = assertIs<com.wajub.mobile.model.ProcessOutcome.Failed>(outcome).error
        assertEquals("raw_card_data_not_accepted", error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `WL-01 pays one-tap wallets`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-ci-xof-wallets"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-ci-wallets"))
            .on("POST /pay/process", Fixtures.response("process-redirect-wallet-djamo"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val opened = mutableListOf<String>()
        val (controller, recorder) = checkout(api, opener = { opened += it; true })
        controller.start()
        runCurrent()
        controller.selectMethod("ci.djamo")
        assertEquals(CheckoutState.COLLECTING_DETAILS, controller.state)
        controller.payWallet()
        runCurrent()
        val sent = api.lastBody("POST /pay/process")
        assertEquals("ci.djamo", sent.getString("channel"))
        assertEquals(setOf("_client"), sent.getJSONObject("data").keys().asSequence().toSet())
        assertEquals(listOf("https://djamo.ci/?type=payment_confirmation&id=abc"), opened)
        assertEquals(
            listOf("started:wallet:ci.djamo", "redirect:https://djamo.ci/?type=payment_confirmation&id=abc", "state:PENDING"),
            recorder.events.takeLast(3),
        )
        assertEquals("https://djamo.ci/?type=payment_confirmation&id=abc", assertIs<SheetScreen.Pending>(controller.screen.value).url)
        assertTrue(api.count("GET /pay/status") >= 1)
    }

    @Test
    fun `WL-02 follows a wave redirect`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-ci-xof-wallets"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-ci-wallets"))
            .on("POST /pay/process", Fixtures.response("process-redirect-wave"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val opened = mutableListOf<String>()
        val (controller, _) = checkout(api, opener = { opened += it; true })
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("07 01 02 03 04")
        controller.pickOperator("wave")
        controller.payMobileMoney()
        runCurrent()
        val sent = api.lastBody("POST /pay/process")
        assertEquals("ci.wave", sent.getString("channel"))
        assertEquals("+2250701020304", sent.getJSONObject("data").getString("phone"), "CI keeps its 0")
        assertEquals("CI", sent.getJSONObject("data").getString("country"))
        assertFalse(sent.getJSONObject("data").has("operator"), "not an aggregate")
        assertEquals(listOf("https://pay.wave.com/c/cos-1x2y3z?a=10000&c=XOF&m=Maquis"), opened)
        assertEquals(CheckoutState.PENDING, controller.state)
    }

    @Test
    fun `PA-01 ignores message when deciding`() = runTest {
        val api = cmApi().on("POST /pay/process", Fixtures.response("process-succeeded-200"))
        val recorder = Recorder()
        val controller = momoCheckout(api, recorder)
        controller.payMobileMoney()
        runCurrent()
        // Its message says "Confirm the transaction on your phone": the status says paid.
        assertEquals(CheckoutState.SUCCESS, controller.state)
        assertEquals("trx_Succeeded0000000000000", assertIs<SheetScreen.Success>(controller.screen.value).payment.id)
    }

    @Test
    fun `PA-02 follows redirects`() = runTest {
        for ((fixture, state) in listOf(
            "process-redirect-card-hosted" to CheckoutState.PENDING,
            "process-redirect-wallet-djamo" to CheckoutState.PENDING,
        )) {
            val api = cmApi().on("POST /pay/process", Fixtures.response(fixture)).on("GET /pay/status", Fixtures.response("status-processing"))
            val recorder = Recorder()
            val opened = mutableListOf<String>()
            val (controller, _) = checkout(api, english, recorder = recorder, opener = { opened += it; true })
            controller.start()
            runCurrent()
            controller.selectMethod("mobile_money")
            controller.setPhone("677123456")
            controller.payMobileMoney()
            runCurrent()
            val url = Fixtures.body(fixture).getString("confirm_url")
            assertEquals(listOf(url), opened, fixture)
            assertEquals(state, controller.state)
            val redirect = recorder.events.indexOf("redirect:$url")
            assertTrue(redirect > recorder.events.indexOfFirst { it.startsWith("started:") }, "onPaymentStarted, then onRedirect")
            assertTrue(api.count("GET /pay/status") >= 1, "watching")
        }
        // confirm_3ds with a URL: 3DS_REQUIRED while the browser is open, PENDING once back.
        val threeDs = cmApi()
            .on("POST /pay/process", Fixtures.response("process-redirect-card-hosted") { it.put("action", "confirm_3ds") })
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val controller = momoCheckout(threeDs)
        controller.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.THREE_DS_REQUIRED, controller.state)
        controller.onReturn()
        assertEquals(CheckoutState.PENDING, controller.state)
        // No browser: redirect_unavailable.
        val nowhere = cmApi().on("POST /pay/process", Fixtures.response("process-redirect-card-hosted"))
        val (stuck, stuckEvents) = checkout(nowhere, english, opener = { false })
        stuck.start()
        runCurrent()
        stuck.selectMethod("mobile_money")
        stuck.setPhone("677123456")
        stuck.payMobileMoney()
        runCurrent()
        assertEquals("redirect_unavailable", assertIs<SheetScreen.Failed>(stuck.screen.value).error.code)
        assertTrue("error:redirect_unavailable" in stuckEvents.events)
    }

    @Test
    fun `PA-03 maps unknown actions to unsupported_action`() = runTest {
        val variants = listOf(
            Fixtures.response("process-requires-otp") { it.put("action", "confirm_teleport") },
            Fixtures.response("process-requires-otp") { it.remove("otp_id") },
            Fixtures.response("process-redirect-card-hosted") { it.remove("confirm_url") },
        )
        for (answer in variants) {
            val api = cmApi().on("POST /pay/process", answer)
            val recorder = Recorder()
            val controller = momoCheckout(api, recorder)
            controller.payMobileMoney()
            runCurrent()
            val failed = assertIs<SheetScreen.Failed>(controller.screen.value)
            assertEquals("unsupported_action", failed.error.code)
            assertFalse(failed.error.retryable)
            assertEquals(WajubErrorType.ApiError, failed.error.type)
            assertTrue("error:unsupported_action" in recorder.events)
        }
    }

    @Test
    fun `PA-04 maps declines`() = runTest {
        for ((fixture, retryable) in listOf(
            "process-failed-402" to true,
            "process-402-insufficient-funds-sandbox" to true,
            "process-402-merchant-not-verified" to false,
        )) {
            val api = cmApi().on("POST /pay/process", Fixtures.response(fixture))
            val recorder = Recorder()
            val controller = momoCheckout(api, recorder)
            controller.payMobileMoney()
            runCurrent()
            // The failure screen, not a replaced form.
            val failed = assertIs<SheetScreen.Failed>(controller.screen.value, fixture)
            val errorCode = Fixtures.body(fixture).getString("error_code")
            assertEquals(errorCode, failed.error.code)
            assertEquals(errorCode, failed.error.declineCode)
            assertEquals(retryable, failed.error.retryable, fixture)
            assertEquals(WajubErrorType.PaymentError, failed.error.type)
            assertEquals(CheckoutState.FAILED, controller.state)
            assertTrue("error:$errorCode" in recorder.events)
            controller.tryAgain()
            if (retryable) {
                val form = assertIs<SheetScreen.Form>(controller.screen.value, "Try again returns to the form")
                assertEquals("677123456", form.mobileMoney!!.phone, "with what the payer typed")
                assertEquals(CheckoutState.COLLECTING_DETAILS, controller.state)
            } else {
                assertIs<SheetScreen.Failed>(controller.screen.value)
            }
        }
    }

    @Test
    fun `PA-05 waits on a charge in progress`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-409-payment-in-progress"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val recorder = Recorder()
        val controller = momoCheckout(api, recorder)
        controller.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.PENDING, controller.state)
        assertTrue(recorder.events.none { it.startsWith("error:") })
        assertEquals(1, api.count("GET /pay/status"))
        advanceTimeBy(5_001)
        assertEquals(2, api.count("GET /pay/status"))
    }

    @Test
    fun `PA-06 maps validation errors to fields`() = runTest {
        val cases = mapOf(
            "process-422-validation" to "Invalid phone number",
            "process-422-operator-mismatch" to "This number belongs to Orange Money. Pick Orange Money in the list, or enter a MTN MoMo number.",
            "process-422-sandbox-invalid-test-number" to "Invalid phone number",
        )
        for ((fixture, message) in cases) {
            val api = cmApi().on("POST /pay/process", Fixtures.response(fixture))
            val recorder = Recorder()
            val controller = momoCheckout(api, recorder)
            controller.payMobileMoney()
            runCurrent()
            val form = assertIs<SheetScreen.Form>(controller.screen.value, fixture)
            assertFalse(form.busy)
            assertEquals(message, form.mobileMoney!!.phoneError, fixture)
            assertEquals(CheckoutState.COLLECTING_DETAILS, controller.state)
            assertTrue(recorder.events.none { it.startsWith("error:") }, "field validation is not onError")
        }
        // Tied to no field: a banner above the form.
        val api = cmApi().on("POST /pay/process", Fixtures.response("process-422-over-mobile-money-limit-sandbox"))
        val controller = momoCheckout(api)
        controller.payMobileMoney()
        runCurrent()
        val form = assertIs<SheetScreen.Form>(controller.screen.value)
        assertNull(form.mobileMoney!!.phoneError)
        assertEquals("This amount is above the limit for this payment method. Use a smaller amount or another method.", form.banner)
    }

    @Test
    fun `PA-07 surfaces rate limits`() = runTest {
        val api = cmApi().on("POST /pay/process", Fixtures.response("error-429-rate-limited"))
        val controller = momoCheckout(api)
        controller.payMobileMoney()
        runCurrent()
        val failed = assertIs<SheetScreen.Failed>(controller.screen.value)
        assertEquals("rate_limited", failed.error.code)
        assertEquals(300, failed.error.retryAfterSeconds)
        assertEquals(WajubErrorType.RateLimitError, failed.error.type)
        assertEquals("Too many attempts. Please wait 300 s before trying again.", failed.error.message)
        assertEquals(testScheduler.currentTime + 300_000, failed.retryAt)
        assertEquals(1, api.count("POST /pay/process"), "no automatic retry")
        controller.tryAgain()
        assertIs<SheetScreen.Failed>(controller.screen.value, "Try again waits for the countdown")
        advanceTimeBy(300_000)
        controller.tryAgain()
        assertIs<SheetScreen.Form>(controller.screen.value)
        assertEquals(1, api.count("POST /pay/process"))
    }

    @Test
    fun `PA-08 maps refusals`() = runTest {
        val cases = mapOf(
            "process-403-shield-blocked" to "shield_blocked",
            "process-403-restricted" to "payment_method_restricted",
        )
        for ((fixture, code) in cases) {
            val api = cmApi().on("POST /pay/process", Fixtures.response(fixture))
            val controller = momoCheckout(api)
            controller.payMobileMoney()
            runCurrent()
            val error = assertIs<SheetScreen.Failed>(controller.screen.value).error
            assertEquals(code, error.code)
            assertEquals(WajubErrorType.PaymentError, error.type)
            if (code == "shield_blocked") {
                assertEquals("BLK-PLACEHOLDER", error.blockReference)
                assertEquals("Payment blocked for security. Reference: BLK-PLACEHOLDER.", error.message)
                assertFalse(error.retryable)
            } else {
                assertTrue(error.retryable, "another method may work")
            }
        }
    }

    @Test
    fun `PA-09 handles ended sessions during payment`() = runTest {
        val paid = cmApi()
            .on("POST /pay/process", Fixtures.response("process-400-not-processable"))
            .on("GET /pay/status", Fixtures.response("status-succeeded"))
        val controller = momoCheckout(paid)
        controller.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.SUCCESS, controller.state, "400: the status says it is paid")

        // Still processing: money may move, the sheet waits for the outcome.
        val running = cmApi()
            .on("POST /pay/process", Fixtures.response("process-400-not-processable"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val waiting = momoCheckout(running)
        waiting.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.PENDING, waiting.state)
        assertTrue(running.count("GET /pay/status") >= 2, "the re-read, then the watch")

        // Neither paid nor under way: not_processable.
        val stuck = cmApi()
            .on("POST /pay/process", Fixtures.response("process-400-not-processable"))
            .on("GET /pay/status", FakeResponse.status(200, """{"transaction":{"status":"pending"}}"""))
        val other = momoCheckout(stuck)
        other.payMobileMoney()
        runCurrent()
        assertEquals("not_processable", assertIs<SheetScreen.Failed>(other.screen.value).error.code)

        val gone = cmApi().on("POST /pay/process", Fixtures.response("error-410-process-session-ended"))
        val ended = momoCheckout(gone)
        ended.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.EXPIRED, ended.state)
        assertEquals("session_terminal_expired", assertIs<SheetScreen.Expired>(ended.screen.value).error.code)
    }

    @Test
    fun `PA-10 fires payment started on the first 2xx`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val recorder = Recorder()
        val controller = momoCheckout(api, recorder)
        controller.payMobileMoney()
        runCurrent()
        advanceTimeBy(20_000)
        assertEquals(listOf("started:mobile_money:cm.mobile"), recorder.events.filter { it.startsWith("started:") })

        // A refused attempt (402) never started.
        val declined = cmApi().on("POST /pay/process", Fixtures.response("process-failed-402"))
        val declinedEvents = Recorder()
        momoCheckout(declined, declinedEvents).payMobileMoney()
        runCurrent()
        assertTrue(declinedEvents.events.none { it.startsWith("started:") })
    }
}
