package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.model.ErrorCatalog
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessRequest
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubErrorType
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Errors (ER), Altcha (AL) and the synced spec files (SY). */
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorsContractTest {
    private class Expected(
        val code: String,
        val type: WajubErrorType,
        val retryable: Boolean,
        val declineCode: String? = null,
        val param: String? = null,
        val retryAfter: Int? = null,
        val blockReference: String? = null,
    )

    private val api = WajubErrorType.ApiError
    private val auth = WajubErrorType.AuthenticationError
    private val invalid = WajubErrorType.InvalidRequestError
    private val payment = WajubErrorType.PaymentError

    private val expected = mapOf(
        "3ds-verify-422-invalid-code" to Expected("invalid_code", payment, true, param = "request"),
        "3ds-verify-500-invalid-code-api-bug" to Expected("server_error", api, true),
        "address-verify-422-missing-fields" to Expected("validation_error", invalid, true),
        "birthday-verify-422-format" to Expected("validation_error", invalid, true, param = "birthday"),
        "broadcasting-auth-403" to Expected("merchant_restricted", payment, false),
        "cancel-400-not-cancellable" to Expected("not_processable", invalid, false),
        "client-session-422-sandbox" to Expected("client_session_unavailable", invalid, false, param = "request"),
        "client-session-complete-402" to Expected("failed", payment, true, declineCode = "failed"),
        "client-session-complete-422-expired" to Expected("client_session_expired", invalid, true, param = "request"),
        "error-401-invalid-token" to Expected("session_not_found", auth, false),
        "error-401-missing-token" to Expected("session_not_found", auth, false),
        "error-403-not-a-payment-session" to Expected("merchant_restricted", payment, false),
        "error-410-process-session-ended" to Expected("session_terminal_expired", invalid, false),
        "error-410-session-expired" to Expected("session_terminal_expired", invalid, false),
        "error-410-session-terminal-expired" to Expected("session_terminal_expired", invalid, false),
        "error-429-rate-limited" to Expected("rate_limited", WajubErrorType.RateLimitError, true, retryAfter = 300),
        "error-500-production" to Expected("server_error", api, true),
        "process-400-not-processable" to Expected("not_processable", invalid, false),
        "process-402-insufficient-funds-sandbox" to Expected("insufficient_funds", payment, true, declineCode = "insufficient_funds"),
        "process-402-merchant-not-verified" to Expected("merchant_not_verified", payment, false, declineCode = "merchant_not_verified"),
        "process-403-restricted" to Expected("payment_method_restricted", payment, true),
        "process-403-shield-blocked" to Expected("shield_blocked", payment, false, blockReference = "BLK-PLACEHOLDER"),
        "process-409-order-not-validated" to Expected("order_not_validated", invalid, false),
        "process-409-payment-in-progress" to Expected("payment_in_progress", invalid, false),
        "process-422-idempotency-key-invalid" to Expected("validation_error", invalid, true, param = "idempotency_key"),
        "process-422-operator-mismatch" to Expected("validation_error", invalid, true, param = "phone"),
        "process-422-over-mobile-money-limit-sandbox" to Expected("validation_error", invalid, true, param = "request"),
        "process-422-raw-card-data-live" to Expected("raw_card_data_not_accepted", invalid, false, param = "card"),
        "process-422-sandbox-invalid-test-number" to Expected("validation_error", invalid, true, param = "data.phone"),
        "process-422-unknown-channel" to Expected("validation_error", invalid, true, param = "channel"),
        "process-422-validation" to Expected("validation_error", invalid, true),
        "process-failed-402" to Expected("insufficient_funds", payment, true, declineCode = "insufficient_funds"),
    )

    @Test
    fun `ER-01 exposes the error shape`() {
        val error = WajubError.fromResponse(429, Fixtures.body("error-429-rate-limited"), "req-1", "300", WajubLanguage.En, loading = false)
        assertEquals("WajubError", error.name)
        assertEquals(
            listOf(
                "name", "type", "code", "message", "decline_code", "retryable", "param", "http_status",
                "correlation_id", "retry_after_seconds", "details", "block_reference", "api_message",
            ),
            error.toMap().keys.toList(),
        )
        val map = error.toMap()
        assertEquals("rate_limit_error", map["type"])
        assertEquals(429, map["http_status"])
        assertEquals("req-1", map["correlation_id"])
        assertEquals(300, map["retry_after_seconds"])
        assertNull(map["details"])
        assertEquals("Too many requests", map["api_message"])
        assertTrue(error is Exception, "an exception the host can throw and catch")
    }

    @Test
    fun `ER-02 maps every error fixture`() = runTest {
        val errorFixtures = Fixtures.names().filter { !it.startsWith("realtime-") && Fixtures.status(it) >= 400 }.map { it.removeSuffix(".json") }
        assertEquals(expected.keys.sorted(), errorFixtures.sorted(), "every error fixture has its expectation")
        for ((fixture, e) in expected) {
            val status = Fixtures.status(fixture)
            val error = WajubError.fromResponse(
                status, Fixtures.body(fixture), "req-42", Fixtures.headers(fixture)["Retry-After"], WajubLanguage.En, loading = false,
            )
            assertEquals(e.code, error.code, fixture)
            assertEquals(e.type, error.type, fixture)
            assertEquals(e.retryable, error.retryable, fixture)
            assertEquals(e.declineCode, error.declineCode, fixture)
            assertEquals(e.param, error.param, fixture)
            assertEquals(e.retryAfter, error.retryAfterSeconds, fixture)
            assertEquals(e.blockReference, error.blockReference, fixture)
            assertEquals(status, error.httpStatus)
            assertEquals("req-42", error.correlationId)
        }
        // 403 while loading: not a payment session.
        val loading = WajubError.fromResponse(403, Fixtures.body("error-403-not-a-payment-session"), null, null, WajubLanguage.En, loading = true)
        assertEquals("session_not_found", loading.code)
        assertEquals(auth, loading.type)

        // The confirmation steps' HTTP 200 failures.
        val steps = mapOf(
            "otp-verify-invalid" to Expected("invalid_otp", invalid, true),
            "otp-verify-expired" to Expected("otp_expired", invalid, true),
            "otp-verify-max-attempts" to Expected("max_attempts_exceeded", payment, true),
            "pin-verify-invalid" to Expected("invalid_pin", invalid, true),
        )
        for ((fixture, e) in steps) {
            val error = WajubError.fromStepFailure(Fixtures.body(fixture), WajubLanguage.En)
            assertEquals(e.code, error.code, fixture)
            assertEquals(e.type, error.type, fixture)
            assertEquals(e.retryable, error.retryable, fixture)
        }

        // correlation_id is the response's X-Request-Id, end to end.
        val traced = cmApi().on("POST /pay/process", Fixtures.response("process-failed-402").let { FakeResponse(it.status, it.body, mapOf("X-Request-Id" to "9b2f-req")) })
        val outcome = fakeSession(traced).process(ProcessRequest("cm.mobile", mapOf("phone" to "+237677123458")))
        assertEquals("9b2f-req", assertIs<ProcessOutcome.Failed>(outcome).error.correlationId)
    }

    @Test
    fun `ER-03 localises error messages`() = runTest {
        fun message(fixture: String, language: WajubLanguage) =
            WajubError.fromResponse(Fixtures.status(fixture), Fixtures.body(fixture), null, null, language, loading = false).message
        assertEquals("Insufficient funds.", message("process-failed-402", WajubLanguage.En))
        assertEquals("Fonds insuffisants.", message("process-failed-402", WajubLanguage.Fr))
        // A provider code the catalogue does not know (`failed`): the generic decline text.
        assertEquals("This payment could not be completed. Try again or use another payment method.", message("client-session-complete-402", WajubLanguage.En))
        assertEquals("Ce paiement n'a pas abouti. Réessayez ou utilisez un autre moyen de paiement.", message("client-session-complete-402", WajubLanguage.Fr))
        // Unknown codes: English sheets show the API's text, French ones the catalogue's.
        val odd = JSONObject().put("message", "Something odd happened.").put("error_code", "odd_thing")
        assertEquals("Something odd happened.", WajubError.fromResponse(400, odd, null, null, WajubLanguage.En, false).message)
        assertEquals("Service temporairement indisponible.", WajubError.fromResponse(400, odd, null, null, WajubLanguage.Fr, false).message)
        assertEquals("Vérifiez les informations saisies.", message("process-422-unknown-channel", WajubLanguage.Fr))
        assertEquals("The selected channel is invalid.", message("process-422-unknown-channel", WajubLanguage.En))
        assertEquals("Trop de tentatives. Merci de patienter 300 s avant de réessayer.", message("error-429-rate-limited", WajubLanguage.Fr))
        assertEquals("Paiement bloqué pour sécurité. Référence : BLK-PLACEHOLDER.", message("process-403-shield-blocked", WajubLanguage.Fr))

        // Every code of the catalogue has its English and French text.
        for (code in ErrorCatalog.codes) {
            val en = ErrorCatalog.message(code, WajubLanguage.En, null)!!
            val fr = ErrorCatalog.message(code, WajubLanguage.Fr, null)!!
            assertTrue(en.isNotBlank() && fr.isNotBlank(), code)
            if (code != "sheet_already_presented") assertNotEquals(en, fr, "$code is translated")
        }

        // In the sheet: a French session's failure screen speaks French.
        val failing = cmApi().on("POST /pay/process", Fixtures.response("process-failed-402"))
        val (controller, _) = checkout(failing, PresentOptions(sessionToken = "t"))
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("677123458")
        controller.payMobileMoney()
        runCurrent()
        assertEquals(CheckoutState.FAILED, controller.state)
        assertEquals("Fonds insuffisants.", assertIs<SheetScreen.Failed>(controller.screen.value).error.message)
    }

    @Test
    fun `AL-01 solves altcha challenges`() = runTest {
        val salt = "8f2a1c?expires=1760000000"
        val number = 4242
        val challenge = MessageDigest.getInstance("SHA-256").digest("$salt$number".toByteArray()).joinToString("") { "%02x".format(it) }
        val altcha = JSONObject().put("algorithm", "SHA-256").put("challenge", challenge).put("salt", salt)
            .put("signature", "SIG").put("maxnumber", 50_000)
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate") { it.put("altcha", altcha) })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-card-paystack"))
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("POST /pay/client-session", Fixtures.response("client-session-start-202"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        runCurrent()
        val sent = api.lastBody("POST /pay/process").getJSONObject("data").getJSONObject("altcha")
        assertEquals(number, sent.getInt("number"))
        assertEquals(challenge, sent.getString("challenge"))
        assertEquals(salt, sent.getString("salt"))
        assertEquals("SIG", sent.getString("signature"))
        assertEquals("SHA-256", sent.getString("algorithm"))

        // On /pay/client-session the solution is a top-level key.
        val (card, _) = checkout(api)
        card.start()
        runCurrent()
        card.selectMethod("card")
        card.payCard()
        runCurrent()
        assertEquals(number, api.lastBody("POST /pay/client-session").getJSONObject("altcha").getInt("number"))

        // Refused: a new challenge once, then security_check_failed.
        val refusal = FakeResponse.status(422, """{"code":422,"message":"Security challenge verification failed.","errors":{"altcha":["Security challenge verification failed."]}}""")
        val strict = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate") { it.put("altcha", altcha) })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/process", refusal)
        val (refused, _) = checkout(strict)
        refused.start()
        runCurrent()
        refused.selectMethod("mobile_money")
        refused.setPhone("670000000")
        refused.payMobileMoney()
        runCurrent()
        assertEquals(2, strict.count("GET /pay/session"), "the session (and its challenge) reloaded once")
        assertEquals(2, strict.count("POST /pay/process"))
        assertEquals("security_check_failed", assertIs<SheetScreen.Failed>(refused.screen.value).error.code)

        // A solution is used once: the next attempt solves the session's new challenge.
        fun challengeFor(n: Int) = JSONObject(altcha.toString())
            .put("challenge", MessageDigest.getInstance("SHA-256").digest("$salt$n".toByteArray()).joinToString("") { "%02x".format(it) })
        val twice = FakeApi()
            .on(
                "GET /pay/session",
                Fixtures.response("session-cm-aggregate") { it.put("altcha", altcha) },
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(77)) },
            )
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/process", Fixtures.response("process-failed-402"), Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (again, _) = checkout(twice)
        again.start()
        runCurrent()
        again.selectMethod("mobile_money")
        again.setPhone("670000000")
        again.payMobileMoney()
        runCurrent()
        again.tryAgain()
        again.payMobileMoney()
        runCurrent()
        val numbers = twice.requests("POST /pay/process").map { org.json.JSONObject(it.body!!).getJSONObject("data").getJSONObject("altcha").getInt("number") }
        assertEquals(listOf(number, 77), numbers)

        // Nothing leaves when the sheet closes while a challenge is being fetched.
        val slow = FakeApi()
            .on(
                "GET /pay/session",
                Fixtures.response("session-cm-aggregate") { it.put("altcha", altcha) },
                Fixtures.response("session-cm-aggregate") { it.put("altcha", challengeFor(78)) }.let { FakeResponse(it.status, it.body, delayMs = 5_000) },
            )
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
            .on("POST /pay/process", Fixtures.response("process-failed-402"))
        val (closing, closingEvents) = checkout(slow)
        closing.start()
        runCurrent()
        closing.selectMethod("mobile_money")
        closing.setPhone("670000000")
        closing.payMobileMoney()
        runCurrent()
        closing.tryAgain()
        closing.payMobileMoney()
        runCurrent()
        closing.dismiss()
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertIs<com.wajub.mobile.checkout.CheckoutResult.Processing>(closingEvents.result)
        assertEquals(1, slow.count("POST /pay/process"))
        assertEquals(0, slow.count("POST /pay/cancel"))
    }

    @Test
    fun `SY-01 uses the synced spec files`() {
        val manifest = File("src/test/resources/fixtures/spec.sha256")
        assertTrue(manifest.isFile, "run sync-spec.sh kotlin")
        val lines = manifest.readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 94)
        for (line in lines) {
            val (sum, path) = line.split(Regex("\\s+"), limit = 2)
            val file = File(path)
            assertTrue(file.isFile, "missing $path")
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(sum, actual, "$path differs from the spec")
        }
    }
}
