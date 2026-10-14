package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CardFlavor
import com.wajub.mobile.checkout.PaymentMethods
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsContractTest {
    private fun session(name: String, mutate: (JSONObject) -> Unit = {}): SessionData =
        RawSessionResponse.fromJson(Fixtures.body(name).also(mutate)).toModel()

    private suspend fun config(name: String): SdkConfig =
        fakeSession(FakeApi().on("GET /pay/sdk-config", Fixtures.response(name))).getSdkConfig()

    @Test
    fun `CH-01 shows only supported channels`() = runTest {
        val wallets = session("session-ci-xof-wallets")
        val ci = PaymentMethods.build(wallets.channels, config("sdk-config-ci-wallets"), sandbox = false, WajubLanguage.En)
        assertEquals(listOf("ci.djamo", "mobile_money"), ci.map { it.id }, "API order: Djamo comes first")
        assertEquals(listOf("ci.mtn", "ci.moov", "ci.orange", "ci.wave"), ci[1].channels.map { it.slug })

        val sandbox = session("session-sandbox-cm")
        val cm = PaymentMethods.build(sandbox.channels, config("sdk-config-sandbox"), sandbox = true, WajubLanguage.En)
        assertEquals(listOf("mobile_money", "card"), cm.map { it.id })
        assertEquals(CardFlavor.SandboxForm, cm[1].cardFlavor)

        // Bank, crypto, USSD… are not shown; with nothing left the sheet reports no_payment_method.
        val unsupported = JSONArray()
            .put(JSONObject().put("slug", "ng.bank").put("type", "bank").put("name", "Bank").put("currency", "NGN"))
            .put(JSONObject().put("slug", "crypto").put("type", "crypto").put("name", "Crypto").put("currency", "USD"))
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-ci-xof-wallets") { it.put("channels", unsupported) })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-ci-wallets"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        assertEquals("no_payment_method", assertIs<SheetScreen.LoadError>(controller.screen.value).error.code)
        assertEquals(listOf("loadError:no_payment_method"), recorder.events)
    }

    @Test
    fun `CH-02 localises channel names`() = runTest {
        val sandbox = session("session-sandbox-cm")
        val sdk = config("sdk-config-sandbox")
        assertEquals("Carte bancaire", PaymentMethods.build(sandbox.channels, sdk, true, WajubLanguage.Fr).first { it.id == "card" }.name)
        assertEquals("Card", PaymentMethods.build(sandbox.channels, sdk, true, WajubLanguage.En).first { it.id == "card" }.name)
        // Aggregates are "Mobile Money" in both.
        assertEquals("Mobile Money", PaymentMethods.build(sandbox.channels, sdk, true, WajubLanguage.Fr).first().name)
    }

    @Test
    fun `CH-03 resolves sdk-config for aggregates`() = runTest {
        val aggregate = session("session-cm-aggregate").channels.first { it.slug == "cm.mobile" }
        val sdk = config("sdk-config-cm-stripe")
        assertFalse(sdk.channels.containsKey("cm.mobile"), "sdk-config uses raw slugs")
        assertEquals(sdk.channels["cm.orange"], PaymentMethods.entryFor(aggregate, sdk, operator = "orange"))
        assertEquals(sdk.channels["cm.mtn"], PaymentMethods.entryFor(aggregate, sdk, operator = "mtn"))
        assertEquals(sdk.channels["cm.mtn"], PaymentMethods.entryFor(aggregate, sdk), "no operator yet: any member's")

        // A member turned off by sdk-config is not offered.
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe") { body ->
                body.getJSONObject("channels").getJSONObject("cm.orange").put("available", false)
            })
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        assertEquals(listOf("mtn"), assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.choices.map { it.id })
    }

    @Test
    fun `CH-04 defaults mobile money without sdk-config`() = runTest {
        val sandbox = session("session-sandbox-cm")
        val sdk = config("sdk-config-sandbox")
        for (channel in sandbox.channels.filter { it.type == "mobile_money" }) {
            val entry = PaymentMethods.entryFor(channel, sdk)!!
            assertTrue(entry.available)
            assertEquals("form", entry.sdk)
            assertEquals(listOf("phone"), entry.requiredFields)
        }
        val methods = PaymentMethods.build(sandbox.channels, sdk, sandbox = true, WajubLanguage.En)
        assertEquals(listOf("cm.eu", "cm.mobile"), methods.first { it.id == "mobile_money" }.channels.map { it.slug })
    }

    @Test
    fun `CH-05 disables channels out of their limits`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-cm-aggregate") { body ->
                body.getJSONObject("transaction").put("amount", 50).put("amount_total", 50)
                body.getJSONArray("channels").getJSONObject(0).put("min_amount", 100)
            })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-cm-stripe"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        val methods = assertIs<SheetScreen.Methods>(controller.screen.value).methods
        val card = methods.first { it.id == "card" }
        assertTrue(card.outOfLimits(50.0))
        controller.selectMethod("card")
        assertIs<SheetScreen.Methods>(controller.screen.value, "a disabled method does not open")
        controller.selectMethod("mobile_money")
        val form = assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!
        assertTrue(form.choices.all { controller.outOfLimits(it) }, "cm.mobile takes 100 to 500 000 XAF")
        assertEquals(null, form.selected, "nothing payable is preselected")
    }
}
