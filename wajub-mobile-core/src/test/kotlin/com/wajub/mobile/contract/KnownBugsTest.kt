package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.model.PaymentResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The 1.2.1 bugs the spec-v2 audit found, each pinned by a test. */
@OptIn(ExperimentalCoroutinesApi::class)
class KnownBugsTest {
    @Test
    fun `the operators and the country sent follow the country picked`() = runTest {
        val channels = JSONArray()
            .put(JSONObject().put("id", "ci.orange").put("slug", "ci.orange").put("name", "Orange Money").put("type", "mobile_money").put("countries", JSONArray().put("CI")).put("currency", "XOF"))
            .put(JSONObject().put("id", "sn.orange").put("slug", "sn.orange").put("name", "Orange Money").put("type", "mobile_money").put("countries", JSONArray().put("SN")).put("currency", "XOF"))
            .put(JSONObject().put("id", "sn.wave").put("slug", "sn.wave").put("name", "Wave").put("type", "mobile_money").put("countries", JSONArray().put("SN")).put("currency", "XOF"))
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-ci-xof-wallets") { it.put("channels", channels) })
            .on("GET /pay/sdk-config", FakeResponse.status(500))
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-live"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        testScheduler.advanceTimeBy(10_000)
        val form = assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!
        assertEquals(listOf("CI", "SN"), form.countries)
        assertEquals("CI", form.country, "geo_country")
        controller.setCountry("SN")
        val senegal = assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!
        assertEquals(listOf("orange", "wave"), senegal.choices.map { it.id })
        assertEquals("+221", senegal.countryInfo!!.dialCode)
        controller.setPhone("771234567")
        controller.pickOperator("wave")
        controller.payMobileMoney()
        runCurrent()
        val sent = api.lastBody("POST /pay/process")
        assertEquals("sn.wave", sent.getString("channel"))
        assertEquals("SN", sent.getJSONObject("data").getString("country"))
        assertEquals("+221771234567", sent.getJSONObject("data").getString("phone"))
    }

    @Test
    fun `a payment error shows a failure screen, and the form keeps what was typed`() = runTest {
        val api = cmApi().on("POST /pay/process", Fixtures.response("process-failed-402"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("677123458")
        controller.payMobileMoney()
        runCurrent()
        assertIs<SheetScreen.Failed>(controller.screen.value)
        controller.tryAgain()
        assertEquals("677123458", assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.phone)
        assertTrue(recorder.results.isEmpty(), "the sheet stays open")
        controller.close()
        runCurrent()
        assertIs<CheckoutResult.Cancelled>(recorder.result)
    }

    @Test
    fun `watching an ended session stops instead of polling forever`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-processing-mobile-money"))
            .on("GET /pay/status", FakeResponse.status(503), Fixtures.response("error-410-session-terminal-expired"))
        val session = fakeSession(api)
        @Suppress("DEPRECATION")
        val results = session.watchStatusPolling(intervalMs = 50).toList()
        val failed = assertIs<PaymentResult.Failed>(results.last())
        assertEquals("session_terminal_expired", failed.error.code)
        assertEquals(2, api.count("GET /pay/status"), "one error, backed off, then the 410 ends it")
    }

    @Test
    fun `an expires_at with microseconds is read to the millisecond`() {
        assertEquals(
            com.wajub.mobile.Iso8601.parse("2026-10-14T09:30:00.123+00:00"),
            com.wajub.mobile.Iso8601.parse("2026-10-14T09:30:00.123456+00:00"),
        )
        assertEquals(com.wajub.mobile.Iso8601.parse("2026-10-14T09:30:00+00:00"), com.wajub.mobile.Iso8601.parse("2026-10-14T09:30:00Z"))
    }

    @Test
    fun `branding nulls stay null and booleans stay booleans`() {
        val data = com.wajub.mobile.mapper.RawSessionResponse.fromJson(Fixtures.body("session-ci-xof-wallets")).toModel()
        assertEquals(null, data.brand.logoUrl)
        assertEquals(false, data.brand.whiteLabel)
        @Suppress("DEPRECATION")
        assertEquals("false", data.branding["white_label"])
        @Suppress("DEPRECATION")
        assertEquals(null, data.branding["logo_url"])
    }
}
