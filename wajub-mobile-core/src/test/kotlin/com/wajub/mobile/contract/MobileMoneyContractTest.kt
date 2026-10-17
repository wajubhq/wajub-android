package com.wajub.mobile.contract

import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.mobilemoney.MobileMoney
import com.wajub.mobile.mobilemoney.MobileMoneyData
import com.wajub.mobile.mobilemoney.MobileMoneyRules
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobileMoneyContractTest {
    private val vectors = Fixtures.vectors()

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

    private fun cases(key: String): List<JSONObject> = vectors.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun JSONObject.ids(key: String): List<String> = getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }

    @Test
    fun `MM-01 matches the number vectors`() {
        for (v in cases("numbers")) {
            val input = v.getString("input")
            val country = MobileMoney.data().country(v.getString("country"))!!
            val label = "${v.getString("country")} '$input'"
            assertEquals(v.getString("national_digits"), MobileMoney.nationalDigits(input, country), label)
            assertEquals(v.getBoolean("valid"), MobileMoney.isValid(input, country), label)
            assertEquals(v.nullableString("detected_operator"), MobileMoney.detectOperator(input, country), label)
            assertEquals(v.getString("e164"), MobileMoney.e164(input, country), label)
            assertEquals(v.getString("formatted"), MobileMoney.formatAsYouType(input), label)
        }
    }

    @Test
    fun `MM-02 matches the mismatch vectors`() {
        for (v in cases("validation_and_mismatch")) {
            val country = MobileMoney.data().country(v.getString("country"))!!
            val issue = MobileMoneyRules.validate(
                phone = v.getString("phone"),
                country = country,
                choiceIds = v.ids("choices"),
                selected = v.nullableString("selected"),
                label = { it },
                sandbox = v.optBoolean("sandbox", false),
            )
            assertEquals(v.nullableString("expected"), issue?.code, v.toString())
            if (issue?.code == "operator_mismatch") {
                assertEquals(v.getString("detected"), issue.args["detected"])
                assertEquals(v.getString("selected"), issue.args["selected"])
            }
        }
    }

    @Test
    fun `MM-03 matches the preselection vectors`() {
        for (v in cases("preselection")) {
            val country = MobileMoney.data().country(v.getString("country"))!!
            val choices = v.ids("choices")
            val picked = v.nullableString("picked_by_hand")
            // Before any number: the sheet's initial selection.
            val initial = MobileMoneyRules.selection(choices, current = null, pickedByHand = false, detected = null)
            val selection = MobileMoneyRules.selection(
                choiceIds = choices,
                current = picked ?: initial,
                pickedByHand = picked != null,
                detected = MobileMoney.detectOperator(v.getString("phone"), country),
            )
            assertEquals(v.getString("expected_selection"), selection, v.toString())
        }
    }

    @Test
    fun `MM-04 lists aggregate operators`() {
        val cm = RawSessionResponse.fromJson(Fixtures.body("session-cm-aggregate")).toModel()
        val cmChoices = MobileMoneyRules.choices(cm.channels, "CM", WajubLanguage.Fr)
        assertEquals(listOf("mtn", "orange"), cmChoices.map { it.id })
        assertEquals(listOf("MTN MoMo", "Orange Money"), cmChoices.map { it.label })
        assertEquals(
            listOf("https://assets.wajub.com/img/channels/momo.svg", "https://assets.wajub.com/img/channels/om.svg"),
            cmChoices.map { it.logo },
        )
        assertTrue(cmChoices.all { it.channelSlug == "cm.mobile" && it.viaAggregate }, "paid through the aggregate")

        val ci = RawSessionResponse.fromJson(Fixtures.body("session-ci-xof-wallets")).toModel()
        val ciChoices = MobileMoneyRules.choices(ci.channels, "CI", WajubLanguage.En)
        assertEquals(listOf("mtn", "moov", "orange", "wave"), ciChoices.map { it.id })
        assertEquals(listOf("ci.mtn", "ci.moov", "ci.orange", "ci.wave"), ciChoices.map { it.channelSlug })
        assertFalse(ciChoices.any { it.viaAggregate })
        val moov = ciChoices.first { it.id == "moov" }
        assertNull(moov.logo)
        assertEquals("Moov", moov.badge.mark)
        assertEquals("#005BAC", moov.badge.background)
    }

    @Test
    fun `MM-05 builds the mobile money request`() = runTest {
        for (v in cases("operator_hint")) {
            val hint = MobileMoneyRules.operatorHint(
                viaAggregate = v.getString("channel").endsWith(".mobile"),
                selected = v.getString("selected"),
                pickedByHand = v.getBoolean("picked_by_hand"),
                detected = v.nullableString("detected"),
            )
            assertEquals(v.nullableString("send_operator"), hint, v.toString())
        }

        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-sandbox-cm"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("67 000 0000")
        controller.payMobileMoney()
        runCurrent()
        val expected = Fixtures.file("process-processing-mobile-money-sandbox").getJSONObject("request").getJSONObject("body")
        val sent = api.lastBody("POST /pay/process")
        assertEquals(expected.getString("channel"), sent.getString("channel"))
        val data = sent.getJSONObject("data")
        val expectedData = expected.getJSONObject("data")
        for (key in listOf("phone", "country", "operator")) assertEquals(expectedData.getString(key), data.getString(key), key)
        assertTrue(data.has("_client"))
        assertFalse(data.has("return_url"), "no returnUrl given")
        assertEquals(setOf("phone", "country", "operator", "_client"), data.keys().asSequence().toSet())

        // A default the payer never touched, on a number naming nothing, is not sent.
        val untouched = MobileMoneyRules.requestData(
            MobileMoneyRules.choices(RawSessionResponse.fromJson(Fixtures.body("session-cm-aggregate")).toModel().channels, "CM", WajubLanguage.En).first(),
            "620000000",
            MobileMoney.data().country("CM")!!,
            pickedByHand = false,
        )
        assertEquals(mapOf("phone" to "+237620000000", "country" to "CM"), untouched)
    }

    @Test
    fun `MM-05 reads a number typed in another script's digits`() = runTest {
        // Arabic-Indic digits for 677 123 456: read, checked and sent as the payer's number.
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-live"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("+٢٣٧ ٦٧٧ ١٢٣ ٤٥٦")
        assertEquals("237677123456", assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.phone, "digits only, as the field shows them")
        controller.payMobileMoney()
        runCurrent()
        assertEquals(1, api.count("POST /pay/process"))
        assertEquals("+237677123456", api.lastBody("POST /pay/process").getJSONObject("data").getString("phone"))
    }

    @Test
    fun `MM-01 reads digits outside the BMP`() {
        // Adlam digits (U+1E950…, Fulfulde), two UTF-16 units each.
        val adlam = { ascii: String -> ascii.map { c -> String(Character.toChars(0x1E950 + (c - '0'))) }.joinToString("") }
        val ci = MobileMoney.data().country("CI")!!
        assertEquals("0701020304", MobileMoney.sanitize(adlam("0701020304")))
        assertTrue(MobileMoney.isValid(adlam("0701020304"), ci))
        assertEquals("orange", MobileMoney.detectOperator(adlam("0701020304"), ci))
        assertEquals("+2250701020304", MobileMoney.e164(adlam("0701020304"), ci))
        val gn = MobileMoney.data().country("GN")!!
        assertTrue(MobileMoney.isValid(adlam("622123456"), gn))
        assertEquals("+224622123456", MobileMoney.e164(adlam("622123456"), gn))
    }

    @Test
    fun `MM-05 keeps digits only in the field, as the web does`() = runTest {
        // A `+` typed in the field is dropped: `+680000000` is a Cameroonian number, not a foreign one.
        val api = cmApi()
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-live"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("+680000000")
        assertEquals("680000000", assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.phone)
        controller.payMobileMoney()
        runCurrent()
        assertEquals("+237680000000", api.lastBody("POST /pay/process").getJSONObject("data").getString("phone"))
    }

    @Test
    fun `MM-06 shows the approval screen`() = runTest {
        for (fixture in listOf("process-processing-mobile-money-live", "process-processing-mobile-money-sandbox")) {
            val api = cmApi()
                .on("POST /pay/process", Fixtures.response(fixture))
                .on("GET /pay/status", Fixtures.response("status-processing"))
            val (controller, recorder) = checkout(api)
            controller.start()
            runCurrent()
            controller.selectMethod("mobile_money")
            controller.setPhone("677123456")
            controller.payMobileMoney()
            runCurrent()
            val approval = assertIs<SheetScreen.Approval>(controller.screen.value, fixture)
            assertEquals("Confirm the transaction on your phone. If no prompt appears, dial *126# to complete.", approval.instruction)
            assertEquals("+237677123456", approval.phone)
            assertEquals(CheckoutState.APPROVAL_REQUIRED, controller.state)
            assertTrue(api.count("GET /pay/status") >= 1, "watching")
            assertTrue("started:mobile_money:cm.mobile" in recorder.events)
        }
    }

    @Test
    fun `MM-08 reads mismatches in sandbox from the test number`() = runTest {
        // An MTN test number picked on Orange: the sandbox would refuse it, so the form does, as in live.
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-sandbox-cm"))
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.pickOperator("orange")
        controller.payMobileMoney()
        runCurrent()
        assertEquals(0, api.count("POST /pay/process"))
        assertEquals(
            "This number belongs to MTN MoMo. Pick MTN MoMo in the list, or enter a Orange Money number.",
            assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.phoneError,
        )
        assertTrue(recorder.events.none { it.startsWith("error:") }, "form errors are not onError")
        // On its own operator it goes.
        controller.pickOperator("mtn")
        controller.payMobileMoney()
        runCurrent()
        assertEquals(1, api.count("POST /pay/process"))

        // In live, the same mismatch stops the payment with the web's message.
        val live = cmApi()
        val (liveController, _) = checkout(live, com.wajub.mobile.checkout.PresentOptions(sessionToken = "t", locale = "en"))
        liveController.start()
        runCurrent()
        liveController.selectMethod("mobile_money")
        liveController.setPhone("677123456")
        liveController.pickOperator("orange")
        liveController.payMobileMoney()
        assertEquals(
            "This number belongs to MTN MoMo. Pick MTN MoMo in the list, or enter a Orange Money number.",
            assertIs<SheetScreen.Form>(liveController.screen.value).mobileMoney!!.phoneError,
        )
        assertEquals(0, live.count("POST /pay/process"))
    }

    @Test
    fun `MM-09 loads the shared data file`() {
        val data = MobileMoney.data()
        assertEquals(MobileMoneyData.SUPPORTED_SCHEMA_VERSION, data.schemaVersion)
        val shipped = File("src/main/resources/com/wajub/mobile/mobile-money.json").readText()
        val json = JSONObject(shipped)
        assertEquals(json.getJSONObject("countries").length(), data.countries.size)
        assertEquals("+237", data.country("cm")!!.dialCode)
        assertTrue(data.operatorIndependentWallets.containsAll(listOf("wave", "eu", "djamo")))
        assertEquals(2, MobileMoneyData.SUPPORTED_SCHEMA_VERSION)
        for (other in listOf(1, 3)) {
            val unknown = JSONObject(shipped).put("schema_version", other).toString()
            assertFailsWith<IllegalArgumentException>("schema $other") { MobileMoneyData.parse(unknown) }
        }
        // The shipped copy carries the tables, not the spec's notes.
        for (note in listOf("source", "errata", "algorithms")) assertFalse(json.has(note), note)
    }

    @Test
    fun `MM-10 takes the sandbox test numbers`() = runTest {
        for (v in cases("sandbox_numbers")) {
            val input = v.getString("input")
            val country = MobileMoney.data().country(v.getString("country"))!!
            val label = "${v.getString("country")} '$input'"
            val operator = v.nullableString("operator")
            val e164 = v.nullableString("e164")
            val test = MobileMoney.sandboxTestNumber(input, country)
            assertEquals(operator, test?.operator, label)
            assertEquals(e164, test?.e164, label)
            if (test != null) {
                // In a sandbox session: valid whatever the plan says, the prefix's operator, sent as read.
                assertTrue(MobileMoney.isValid(input, country, sandbox = true), label)
                assertEquals(operator, MobileMoney.detectOperator(input, country, sandbox = true), label)
                assertEquals(e164, MobileMoney.e164(input, country, sandbox = true), label)
            }
        }

        // The sheet in a sandbox session: a Gabonese Airtel test number, sent as read.
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-sandbox-cm") { body ->
                val channel = JSONObject().put("id", "ga.airtel").put("slug", "ga.airtel").put("name", "Airtel Money")
                    .put("type", "mobile_money").put("countries", org.json.JSONArray().put("GA")).put("currency", "XAF")
                body.put("channels", org.json.JSONArray().put(channel))
            })
            .on("GET /pay/sdk-config", Fixtures.response("sdk-config-sandbox"))
            .on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
            .on("GET /pay/status", Fixtures.response("status-processing"))
        val (controller, _) = checkout(api)
        controller.start()
        runCurrent()
        controller.setPhone("0000000")
        controller.payMobileMoney()
        runCurrent()
        assertEquals(1, api.count("POST /pay/process"), "a test number is valid in sandbox")
        assertEquals("+2410000000", api.lastBody("POST /pay/process").getJSONObject("data").getString("phone"))
    }
}
