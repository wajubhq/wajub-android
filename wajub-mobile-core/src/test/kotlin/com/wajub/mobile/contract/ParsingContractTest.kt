package com.wajub.mobile.contract

import com.wajub.mobile.client.ApiResponse
import com.wajub.mobile.mapper.OutcomeMapper
import com.wajub.mobile.mapper.RawProcessResponse
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.mapper.str
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.test.runTest
import org.json.JSONException
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingContractTest {
    private fun session(body: JSONObject): SessionData = RawSessionResponse.fromJson(body).toModel()

    private fun session(name: String, mutate: (JSONObject) -> Unit = {}): SessionData =
        session(Fixtures.body(name).also(mutate))

    @Test
    fun `PR-01 parses every fixture`() = runTest {
        val names = Fixtures.names()
        assertEquals(108, names.size, "the spec ships 108 fixtures")
        for (name in names) {
            val file = Fixtures.file(name)
            if (name.startsWith("realtime-")) {
                assertNotNull(OutcomeMapper.event(file.getJSONObject("data").toString()), name)
                continue
            }
            val status = file.getInt("http_status")
            val path = file.getJSONObject("request").getString("path")
            val body = file.getJSONObject("body")
            val response = ApiResponse(status, body, "req-1", null)
            val errorOf = { r: ApiResponse -> WajubError.fromResponse(r.status, r.body, r.correlationId, r.retryAfter, WajubLanguage.En, false) }
            try {
                when {
                    status >= 400 && path != "/pay/3ds/verify" -> assertNotNull(errorOf(response).code, name)
                    path == "/pay/session" -> session(body)
                    path == "/pay/sdk-config" -> {
                        val api = FakeApi().on("GET /pay/sdk-config", Fixtures.response(name))
                        assertTrue(fakeSession(api).getSdkConfig().channels.isNotEmpty(), name)
                    }
                    path == "/pay/status" -> assertTrue(OutcomeMapper.status(body).status.isNotEmpty(), name)
                    path == "/pay/process" || path.startsWith("/pay/client-session") -> {
                        RawProcessResponse.fromJson(body)
                        OutcomeMapper.process(response, mobileMoney = true, WajubLanguage.En, errorOf)
                    }
                    path == "/pay/3ds/verify" -> OutcomeMapper.threeDs(response, WajubLanguage.En, errorOf)
                    path.endsWith("/verify") -> OutcomeMapper.step(response, WajubLanguage.En, errorOf)
                    path == "/pay/cancel" -> assertNotNull(body.str("redirect_url"), name)
                    path == "/pay/broadcasting/auth" -> assertNotNull(body.str("auth"), name)
                    path.endsWith("/resend") || path.endsWith("/send") -> assertNotNull(body.str("message"), name)
                    else -> error("unmapped fixture $name ($path)")
                }
            } catch (e: Exception) {
                throw AssertionError("$name did not parse: ${e.message}", e)
            }
        }
    }

    @Test
    fun `PR-02 ignores unknown keys`() {
        val plain = session("session-cm-aggregate")
        val noisy = session("session-cm-aggregate") { body ->
            body.put("future_root", JSONObject().put("x", 1))
            body.getJSONObject("transaction").put("future_txn", listOf(1, 2))
            body.getJSONObject("branding").put("future_brand", true)
            val channel = body.getJSONArray("channels").getJSONObject(0)
            channel.put("future_channel", "x")
            body.getJSONArray("channels").getJSONObject(1).getJSONArray("operators").getJSONObject(0).put("future_op", 3)
        }
        assertEquals(plain.transaction, noisy.transaction)
        assertEquals(plain.channels, noisy.channels)
        assertEquals(plain.brand, noisy.brand)
    }

    @Test
    fun `PR-03 accepts numbers as int, double or numeric string`() {
        val fee = session("session-payer-bears-fee")
        assertEquals(10000.0, fee.transaction.amount)
        assertEquals(10350.0, fee.transaction.amountTotal)
        assertEquals(0.035, fee.transaction.fee?.rate)
        for (amount in listOf<Any>(25000, 25000.0, "25000", "25000.00")) {
            val parsed = session("session-payer-bears-fee") { it.getJSONObject("transaction").put("amount", amount) }
            assertEquals(25000.0, parsed.transaction.amount, "amount $amount")
        }
        val optional = session("session-payer-bears-fee") {
            it.getJSONObject("transaction").put("amount_total", "n/a")
            it.getJSONArray("channels").getJSONObject(1).put("min_amount", JSONObject.NULL)
        }
        assertNull(optional.transaction.amountTotal)
        assertNull(optional.channels[1].minAmount)
        assertFailsWith<JSONException> {
            session("session-payer-bears-fee") { it.getJSONObject("transaction").put("amount", "lots") }
        }
    }

    @Test
    fun `PR-04 accepts booleans in every encoding`() {
        for ((value, expected) in listOf<Pair<Any, Boolean>>(true to true, 1 to true, "true" to true, false to false, 0 to false, "false" to false)) {
            val parsed = session("session-cm-aggregate") {
                it.getJSONObject("branding").put("white_label", value)
                it.getJSONObject("transaction").put("sandbox", value)
            }
            assertEquals(expected, parsed.brand.whiteLabel, "white_label $value")
            assertEquals(expected, parsed.transaction.sandbox, "sandbox $value")
        }
    }

    @Test
    fun `PR-05 parses branding with mixed types`() {
        // 2.1: integers.
        val brand = session("session-cm-aggregate").brand
        assertTrue(brand.whiteLabel)
        assertEquals(40, brand.overlayIntensity)
        assertEquals("600", brand.fontWeightHeading)
        assertEquals("400", brand.fontWeightBody)
        assertEquals("#0F766E", brand.primaryColor)
        assertEquals("light", brand.appearanceMode)
        // 2.0: a weight as a numeric string.
        for (name in listOf("session-saved-methods", "session-payer-bears-fee")) {
            val old = session(name).brand
            assertEquals("600", old.fontWeightHeading, name)
            assertEquals("400", old.fontWeightBody, name)
        }

        val objectWhiteLabel = session("session-cm-aggregate") {
            it.getJSONObject("branding").put("white_label", JSONObject().put("enabled", true).put("brand_name", "Akwa"))
        }
        assertTrue(objectWhiteLabel.brand.whiteLabel)

        // Default branding: nulls stay null (they used to become the text "null").
        val plain = session("session-ci-xof-wallets")
        assertNull(plain.brand.primaryColor)
        assertFalse(plain.brand.whiteLabel)
        assertEquals("Maquis Plateau", plain.brand.businessName)
        @Suppress("DEPRECATION")
        assertNull(plain.branding["primary_color"])

        val empty = session("session-cm-aggregate") { it.put("branding", JSONObject()) }
        assertNull(empty.brand.primaryColor)
        assertFalse(empty.brand.whiteLabel)
    }

    @Test
    fun `PR-06 reads dotted keys literally`() {
        val data = session("session-cm-aggregate")
        assertEquals("Carte bancaire", data.channels.first { it.slug == "card" }.nameFr)
        @Suppress("DEPRECATION")
        assertEquals("#FFFFFF", data.branding["card.background_color"])
        assertEquals("Carte bancaire", session("session-sandbox-cm").channels.first { it.slug == "card" }.nameFr)
        assertEquals("Card", session("session-sandbox-cm").channels.first { it.slug == "card" }.name)
    }

    @Test
    fun `PR-07 accepts null optionals`() {
        val wallets = session("session-ci-xof-wallets")
        assertNull(wallets.transaction.customer)
        assertNull(wallets.transaction.fee)
        assertNull(wallets.transaction.receiptUrl)
        assertNull(wallets.sessionLocale)
        assertNull(wallets.channels.first { it.slug == "ci.moov" }.logo)
        assertNull(wallets.channels.first().minAmount)
        val sandbox = session("session-sandbox-cm")
        assertNull(sandbox.echo, "2.1: no key, no echo")
        assertNull(sandbox.altcha)
        assertEquals("", session("session-processing-mobile-money").echo?.key, "2.0: echo.key null, polling only")
    }

    @Test
    fun `PR-08 accepts absent optional objects`() = runTest {
        val data = session("session-cm-aggregate")
        assertNull(data.transaction.processingContext)
        assertTrue(data.savedMethods.isEmpty())
        val noEcho = session("session-cm-aggregate") { it.remove("echo") }
        assertNull(noEcho.echo)

        val sandboxConfig = fakeSession(FakeApi().on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))).getSdkConfig()
        assertFalse(sandboxConfig.channels.getValue("card").clientSession)

        val process = RawProcessResponse.fromJson(Fixtures.body("process-processing-mobile-money-sandbox"))
        assertNull(process.action)
        assertNull(process.clientSession)
    }
}
