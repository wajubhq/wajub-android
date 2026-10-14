package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.model.WajubErrorType
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingContractTest {
    @Test
    fun `LD-01 loads session and sdk-config in parallel`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate").let { FakeResponse(it.status, it.body, delayMs = 500) })
            .on("GET /pay/sdk-config", FakeResponse.status(500))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        assertEquals(listOf(0L, 0L), api.times.take(2), "both requests leave at once")
        advanceTimeBy(10_000)
        runCurrent()
        // sdk-config failed (after its retries): Mobile Money still shows, the card needs it.
        val form = assertIs<SheetScreen.Form>(controller.screen.value)
        assertEquals("mobile_money", form.method.family)
        assertEquals(listOf("mtn", "orange"), form.mobileMoney!!.choices.map { it.id })
        assertEquals(listOf("ready", "state:COLLECTING_DETAILS"), recorder.events)
    }

    @Test
    fun `LD-02 maps unknown sessions to session_not_found`() = runTest {
        for (fixture in listOf("error-401-invalid-token", "error-401-missing-token", "error-403-not-a-payment-session")) {
            val api = FakeApi().on("GET /pay/session", Fixtures.response(fixture)).on("GET /pay/sdk-config", FakeResponse.status(401))
            val (controller, recorder) = checkout(api)
            controller.start()
            advanceTimeBy(1_000)
            runCurrent()
            val screen = assertIs<SheetScreen.LoadError>(controller.screen.value, fixture)
            assertEquals("session_not_found", screen.error.code, fixture)
            assertEquals(WajubErrorType.AuthenticationError, screen.error.type)
            assertFalse(screen.error.retryable)
            assertEquals(listOf("loadError:session_not_found"), recorder.events, fixture)
            controller.close()
            assertIs<CheckoutResult.Failed>(recorder.result)
        }
    }

    @Test
    fun `LD-03 maps both 410s to session_terminal_expired`() = runTest {
        for (fixture in listOf("error-410-session-terminal-expired", "error-410-session-expired")) {
            val api = FakeApi().on("GET /pay/session", Fixtures.response(fixture)).on("GET /pay/sdk-config", FakeResponse.status(410))
            val (controller, recorder) = checkout(api)
            controller.start()
            runCurrent()
            val screen = assertIs<SheetScreen.LoadError>(controller.screen.value)
            assertEquals("session_terminal_expired", screen.error.code, fixture)
            assertFalse(screen.error.retryable, fixture)
            assertEquals(listOf("loadError:session_terminal_expired"), recorder.events)
        }
    }

    @Test
    fun `LD-04 shows an already paid session as success`() = runTest {
        val api = FakeApi().on("GET /pay/session", Fixtures.response("session-terminal-succeeded")).on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        val success = assertIs<SheetScreen.Success>(controller.screen.value)
        assertEquals("unknown", success.payment.method)
        assertEquals("https://invoice.wajub.com/r/trx_TerminalSucc0000000000?s=SIGNATURE", success.payment.receiptUrl)
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(listOf("ready", "state:SUCCESS", "success:unknown", "complete:complete"), recorder.events)
        assertIs<CheckoutResult.Complete>(recorder.result)
        assertEquals(0, api.count("POST /pay/process"))
    }

    @Test
    fun `LD-05 reports ended sessions with Wajub js codes`() = runTest {
        val cases = mapOf(
            "session-terminal-failed" to ("session_terminal_failed" to CheckoutState.FAILED),
            "session-terminal-cancelled" to ("session_terminal_cancelled" to CheckoutState.CANCELLED),
            "session-terminal-expired" to ("session_terminal_expired" to CheckoutState.EXPIRED),
        )
        for ((fixture, expected) in cases) {
            val api = FakeApi().on("GET /pay/session", Fixtures.response(fixture)).on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            val (controller, recorder) = checkout(api)
            controller.start()
            runCurrent()
            val ended = assertIs<SheetScreen.Ended>(controller.screen.value, fixture)
            assertEquals(expected.first, ended.error.code)
            assertEquals(expected.second, controller.state)
            assertEquals(listOf("ready", "state:${expected.second.value}", "loadError:${expected.first}"), recorder.events)
            controller.close()
            val result = assertIs<CheckoutResult.Failed>(recorder.result)
            assertEquals(expected.first, result.error.code)
        }
    }

    @Test
    fun `LD-06 restores a pending approval`() = runTest {
        for (fixture in listOf("session-processing-mobile-money", "session-processing-mobile-money-live")) {
            val api = FakeApi()
                .on("GET /pay/session", Fixtures.response(fixture))
                .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
                .on("GET /pay/status", Fixtures.response("status-processing"))
            val (controller, recorder) = checkout(api)
            controller.start()
            runCurrent()
            val approval = assertIs<SheetScreen.Approval>(controller.screen.value, fixture)
            assertEquals(
                "Confirm the transaction on your phone. If no prompt appears, dial *126# to complete.",
                approval.instruction,
            )
            assertEquals(CheckoutState.APPROVAL_REQUIRED, controller.state)
            assertEquals(listOf("ready", "state:APPROVAL_REQUIRED"), recorder.events)
            assertEquals(1, api.count("GET /pay/status"), "watching from the start")
            advanceTimeBy(5_001)
            assertEquals(2, api.count("GET /pay/status"))
            // An attempt is in flight: a dismiss leaves the payment alone.
            controller.dismiss()
            assertIs<CheckoutResult.Processing>(recorder.result)
            assertEquals(0, api.count("POST /pay/cancel"))
        }
    }

    @Test
    fun `LD-08 raises ready_timeout`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate").let { FakeResponse(it.status, it.body, delayMs = 20_000) })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
        val (controller, recorder) = checkout(api)
        controller.start()
        advanceTimeBy(15_001)
        runCurrent()
        val error = assertIs<SheetScreen.LoadError>(controller.screen.value).error
        assertEquals("ready_timeout", error.code)
        assertEquals(WajubErrorType.ApiError, error.type)
        assertTrue(error.retryable)
        assertEquals("Payment sheet not ready after 15000 ms", error.apiMessage)
        assertEquals(listOf("loadError:ready_timeout"), recorder.events)
        // The data arrives later: the sheet becomes ready and shows it.
        advanceTimeBy(5_000)
        runCurrent()
        assertIs<SheetScreen.Methods>(controller.screen.value)
        assertEquals(listOf("loadError:ready_timeout", "ready", "state:INITIATED"), recorder.events)

        // Retry starts the requests again, with a new timer; 0 disables it.
        val slow = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate").let { FakeResponse(it.status, it.body, delayMs = 20_000) })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
        val (retrying, _) = checkout(slow)
        retrying.start()
        advanceTimeBy(15_001)
        retrying.retryLoad()
        runCurrent()
        assertEquals(2, slow.count("GET /pay/session"))
        assertIs<SheetScreen.Loading>(retrying.screen.value)
        val (patient, patientEvents) = checkout(slow, PresentOptions(sessionToken = "t", readyTimeout = 0))
        patient.start()
        advanceTimeBy(19_000)
        assertTrue(patientEvents.events.isEmpty())
    }

    @Test
    fun `LD-09 resolves the sheet language`() = runTest {
        suspend fun languageOf(session: String, locale: String?, device: String): WajubLanguage {
            val api = FakeApi().on("GET /pay/session", Fixtures.response(session)).on("GET /pay/sdk-config", Fixtures.response("sdk-config-ci-wallets"))
            val (controller, _) = checkout(api, PresentOptions(sessionToken = "t", locale = locale), deviceLanguage = device)
            controller.start()
            runCurrent()
            return controller.language.value
        }
        assertEquals(WajubLanguage.Fr, languageOf("session-cm-aggregate", null, "en-US"), "the session's locale")
        assertEquals(WajubLanguage.En, languageOf("session-cm-aggregate", "en", "fr-FR"), "PresentOptions.locale first")
        assertEquals(WajubLanguage.Fr, languageOf("session-ci-xof-wallets", null, "fr-CI"), "no session locale: the device's")
        assertEquals(WajubLanguage.En, languageOf("session-ci-xof-wallets", null, "de-DE"), "neither fr nor en: en")
        assertEquals(WajubLanguage.Fr, languageOf("session-ci-xof-wallets", "fr-SN", "en-US"))
    }
}
