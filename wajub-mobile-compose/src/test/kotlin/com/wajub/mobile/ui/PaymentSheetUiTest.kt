package com.wajub.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.wajub.mobile.WajubSession
import com.wajub.mobile.checkout.Appearance
import com.wajub.mobile.checkout.CancelReason
import com.wajub.mobile.checkout.CheckoutCallbacks
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.LoaderStyle
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.ResolvedAppearance
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.checkout.ThemeTokens
import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.PaymentResult
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
// A phone-sized screen.
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PaymentSheetUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = MainScope()
    private val controllers = mutableListOf<CheckoutController>()
    private val events = mutableListOf<String>()

    // The Mobile Money tables are read off the main thread at the first load: read them now, so a
    // loaded machine cannot leave a sheet still loading when a test looks at it.
    @org.junit.Before
    fun readTables() {
        com.wajub.mobile.mobilemoney.MobileMoney.data()
    }

    @After
    fun tearDown() {
        // One sheet at a time: settle every sheet a test opened.
        controllers.forEach { it.hostGone() }
        scope.cancel()
    }

    private fun idle(seconds: Long = 1) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        compose.waitForIdle()
    }

    private fun controller(session: WajubSession, options: PresentOptions = PresentOptions(locale = "en")): CheckoutController {
        // The fake API's sessions have no `echo`: polling only, no socket leaves the test machine.
        val polling = options
        val callbacks = CheckoutCallbacks(
            onRedirect = { events += "redirect:${it.url}" },
            onLoadError = { events += "loadError:${it.code}" },
        )
        return CheckoutController(session, polling.copy(callbacks = callbacks), scope).also {
            controllers += it
            it.urlOpener = { url -> HostedRedirectLauncher.open(compose.activity, url) }
        }
    }

    private val shown = mutableStateOf<CheckoutController?>(null)
    private var contentSet = false

    // setContent once per test; later sheets replace the shown one.
    private fun show(controller: CheckoutController) {
        shown.value = controller
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                CompositionLocalProvider(LocalWajubImageLoader provides failingImages(LocalContext.current)) {
                    shown.value?.let { PaymentSheetContent(it) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `LD-07 offers retry on load failure`() {
        val api = FakeApi()
            .fail("GET /pay/session").fail("GET /pay/session").fail("GET /pay/session").fail("GET /pay/session")
            .on("GET /pay/session", "session-ci-xof-wallets")
            .on("GET /pay/sdk-config", "sdk-config-ci-wallets")
        val controller = controller(api.session())
        show(controller)
        controller.start()
        idle(10)
        assertEquals(4, api.count("GET /pay/session"), "the load and its 3 retries")
        compose.onNodeWithTag("wajub-load-error").assertIsDisplayed()
        compose.onNodeWithText("We couldn't load the payment").assertIsDisplayed()
        assertEquals("loadError:load_error", events.single())
        compose.onNodeWithTag("wajub-retry").assertIsEnabled().performClick()
        idle()
        assertEquals(5, api.count("GET /pay/session"))
        compose.onNodeWithTag("wajub-method-mobile_money").assertIsDisplayed()
        compose.onNodeWithTag("wajub-method-ci.djamo").assertIsDisplayed()
    }

    @Test
    fun `MM-07 falls back to brand badges`() {
        val api = FakeApi().on("GET /pay/session", "session-ci-xof-wallets").on("GET /pay/sdk-config", "sdk-config-ci-wallets")
        val controller = controller(api.session())
        show(controller)
        controller.start()
        idle()
        compose.onNodeWithTag("wajub-method-mobile_money").performClick()
        idle()
        // ci.moov has no logo; the others' logos fail to load here.
        for (operator in listOf("moov", "mtn", "orange", "wave")) {
            compose.onNodeWithTag("wajub-operator-logo-$operator-badge", useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithText("Moov", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("MoMo", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `CH-06 prefers logo_png where SVG cannot be drawn`() {
        // Android draws SVG: the logo first, its PNG when it does not load, else the brand badge.
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val api = FakeApi().on("GET /pay/session", "session-cm-aggregate").on("GET /pay/sdk-config", "sdk-config-cm-stripe")
        val controller = controller(api.session())
        compose.setContent {
            CompositionLocalProvider(LocalWajubImageLoader provides svgFailing(LocalContext.current, requested)) {
                PaymentSheetContent(controller)
            }
        }
        controller.start()
        idle()
        // Mobile Money: its SVG fails, its PNG draws. The card has no PNG: its badge.
        compose.onNodeWithTag("wajub-method-logo-mobile_money-badge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("wajub-method-logo-card-badge", useUnmergedTree = true).assertExists()
        assertTrue("https://assets.wajub.com/img/channels/momo.svg" in requested, "$requested")
        assertTrue("https://api.wajub.com/img/channels/momo.png" in requested, "$requested")
        compose.onNodeWithTag("wajub-method-mobile_money").performClick()
        idle()
        for (operator in listOf("mtn", "orange")) {
            compose.onNodeWithTag("wajub-operator-logo-$operator-badge", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun `AP-07 draws the loader`() {
        val api = FakeApi()
        val skeleton = controller(api.session())
        show(skeleton)
        compose.onNodeWithTag("wajub-skeleton").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(Pulsing, true))
        skeleton.hostGone()

        val still = controller(api.session(), PresentOptions(appearance = Appearance(disableAnimations = true)))
        show(still)
        compose.onNodeWithTag("wajub-skeleton").assert(SemanticsMatcher.expectValue(Pulsing, false))
        still.hostGone()

        val spinner = controller(api.session(), PresentOptions(loader = LoaderStyle.Spinner))
        show(spinner)
        compose.onNodeWithTag("wajub-spinner").assertIsDisplayed()
    }

    @Test
    fun `AP-08 inherits the host theme`() {
        var inherited: ResolvedAppearance? = null
        var refused: ResolvedAppearance? = null
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF9333EA), surface = Color.White, onSurface = Color(0xFF111111))) {
                inherited = rememberSheetLook(Appearance(inherit = true), null)
            }
            // A primary that does not stand out on the background is not kept.
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFFFFF00), surface = Color.White)) {
                refused = rememberSheetLook(Appearance(inherit = true), null)
            }
        }
        compose.waitForIdle()
        assertEquals(Color(0xFF9333EA).toArgb(), assertNotNull(inherited).colors.primary)
        assertEquals(Color(0xFF111111).toArgb(), inherited!!.colors.text)
        assertEquals(false, inherited!!.dark)
        assertEquals(ThemeTokens.LIGHT.primary, assertNotNull(refused).colors.primary)
    }

    @Test
    fun `AP-08 inherits the host theme's body font`() {
        val brand = androidx.compose.ui.text.font.FontFamily.Serif
        var themed: ResolvedAppearance? = null
        var plain: ResolvedAppearance? = null
        compose.setContent {
            MaterialTheme(typography = androidx.compose.material3.Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = brand))) {
                themed = rememberSheetLook(Appearance(inherit = true), null)
            }
            // Material's own sans serif is no choice of the app's.
            MaterialTheme {
                plain = rememberSheetLook(Appearance(inherit = true), null)
            }
        }
        compose.waitForIdle()
        assertEquals(brand, fontFamily(assertNotNull(themed).fontFamily))
        assertEquals(null, assertNotNull(plain).fontFamily)
    }

    @Test
    fun `a font the app registers is the sheet's font`() {
        val poppins = androidx.compose.ui.text.font.FontFamily.Cursive
        WajubFonts.register("Poppins", poppins)
        try {
            // A CSS stack: its first family, by name, without case.
            val look = com.wajub.mobile.checkout.AppearanceResolver.resolve(
                Appearance(variables = com.wajub.mobile.checkout.AppearanceVariables(fontFamily = "'poppins', sans-serif")),
                null,
                systemDark = false,
            )
            assertEquals(poppins, fontFamily(look.fontFamily))
        } finally {
            WajubFonts.unregister("Poppins")
        }
        assertEquals(androidx.compose.ui.text.font.FontFamily.Default, fontFamily(null))
    }

    @Test
    fun `RD-01 opens provider pages in the system browser`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-ci-xof-wallets")
            .on("GET /pay/sdk-config", "sdk-config-ci-wallets")
            .on("POST /pay/process", "process-redirect-wallet-djamo")
            .on("GET /pay/status", "status-processing")
        val controller = controller(api.session())
        show(controller)
        controller.start()
        idle()
        compose.onNodeWithTag("wajub-method-ci.djamo").performClick()
        idle()
        compose.onNodeWithTag("wajub-pay").performClick()
        idle()
        val url = "https://djamo.ci/?type=payment_confirmation&id=abc"
        assertEquals(listOf("redirect:$url"), events, "onRedirect first")
        val intent = assertNotNull(shadowOf(compose.activity).nextStartedActivity, "a browser was opened")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertTrue(intent.hasExtra("android.support.customtabs.extra.SESSION"), "a Custom Tab, never a WebView")
        compose.onNodeWithTag("wajub-pending").assertIsDisplayed()
        compose.onNodeWithTag("wajub-continue").assertIsDisplayed()
    }

    @Test
    fun `the OTP screen names the masked contact, or none`() {
        for ((contact, shown) in listOf("080******78" to "Code sent to 080******78", "" to null)) {
            val api = FakeApi()
                .on("GET /pay/session", "session-sandbox-cm")
                .on("GET /pay/sdk-config", "sdk-config-sandbox")
                .on("POST /pay/process", "process-requires-otp") { it.put("otp_masked_contact", contact) }
                .on("GET /pay/status", "status-processing")
            val controller = controller(api.session())
            show(controller)
            controller.start()
            idle()
            controller.selectMethod("mobile_money")
            controller.setPhone("670000000")
            controller.payMobileMoney()
            idle()
            compose.onNodeWithTag("wajub-step-screen-otp").assertExists()
            if (shown != null) {
                compose.onNodeWithTag("wajub-step-hint", useUnmergedTree = true).assertTextContains(shown)
            } else {
                // The code prompt alone, never "sent to" with nothing after it.
                compose.onNodeWithTag("wajub-step-hint", useUnmergedTree = true).assertDoesNotExist()
            }
            controller.hostGone()
            idle()
        }
    }

    @Test
    fun `MM-14 offers the operator's code to dial`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-payer-otp-announced")
            .on("GET /pay/sdk-config", "sdk-config-sandbox")
            .on("POST /pay/process", "process-payer-otp-accepted-sandbox")
            .on("GET /pay/status", "status-processing")
        val controller = controller(api.session())
        show(controller)
        controller.start()
        idle()
        controller.selectMethod("mobile_money")
        idle()
        compose.onNodeWithTag("wajub-momo-hint", useUnmergedTree = true)
            .assertTextContains("For this payment, you will generate a code by dialing *144*4*6#: the next step guides you.")
        controller.setPhone("70000000")
        idle()
        compose.onNodeWithTag("wajub-pay").performScrollTo().performClick()
        idle()
        compose.onNodeWithTag("wajub-payer-otp").assertExists()
        assertEquals(0, api.count("POST /pay/process"), "the code is asked for first")
        compose.onNodeWithText("Generate your payment code").assertExists()
        compose.onNodeWithTag("wajub-payer-otp-amount", useUnmergedTree = true).assertTextContains("1000 XOF")
        compose.onNodeWithText("1. On the phone of the Orange Money account, dial *144*4*6#.").assertExists()
        compose.onNodeWithTag("wajub-payer-otp-sandbox", useUnmergedTree = true)
            .assertTextContains("Test mode: enter the code 123456. Any other code is refused.")

        // The code opens the dialer, # written %23.
        compose.onNodeWithTag("wajub-payer-otp-dial").performScrollTo().performClick()
        idle()
        val intent = assertNotNull(shadowOf(compose.activity).nextStartedActivity, "the dialer was opened")
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("tel:*144*4*6%23", intent.dataString)

        // And copies as it is.
        compose.onNodeWithTag("wajub-payer-otp-copy").performScrollTo().performClick()
        idle()
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("*144*4*6#", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithTag("wajub-payer-otp-copy").assertTextContains("Copied")
        // The sheet's effects run on the test's frame clock.
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
        compose.onNodeWithTag("wajub-payer-otp-copy").assertTextEquals("Copy")

        // Typing the whole code sends nothing: Confirm payment does.
        compose.onNodeWithTag("wajub-payer-otp-code").performScrollTo().performTextInput("123456")
        idle()
        assertEquals("123456", assertIs<SheetScreen.PayerOtp>(controller.screen.value).code)
        assertEquals(0, api.count("POST /pay/process"))
        compose.onNodeWithTag("wajub-payer-otp-submit").performScrollTo().performClick()
        idle()
        assertEquals("123456", api.lastBody("POST /pay/process").getJSONObject("data").getString("otp"))
        compose.onNodeWithText("Code received. Your operator is processing the payment, this can take a moment.").assertExists()
        // Confirmed in the operator's menu already: no phone line, no notification to expect.
        compose.onNodeWithText("You will receive a notification to confirm the payment").assertDoesNotExist()
        compose.onNodeWithText("+22670000000", substring = true).assertDoesNotExist()
    }

    @Test
    fun `MM-13 disables Pay when no operator is offered any more`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-payer-otp-announced")
            .on("GET /pay/sdk-config", "sdk-config-sandbox")
            .on("POST /pay/process", "process-402-payer-otp-attempts-exceeded")
        val controller = controller(api.session())
        show(controller)
        controller.start()
        idle()
        controller.selectMethod("mobile_money")
        controller.setPhone("70000000")
        controller.payMobileMoney()
        controller.setPayerOtpCode("654323")
        controller.submitPayerOtp()
        idle()
        val text = "Your operator refused too many codes for this payment. Choose another payment method."
        compose.onNodeWithTag("wajub-banner").assertTextContains(text)
        compose.onNodeWithTag("wajub-operator-orange").assertIsNotEnabled().assertTextContains(text)
        compose.onAllNodesWithText(text).assertCountEquals(2)
        compose.onNodeWithTag("wajub-pay").assertIsNotEnabled()
    }

    @Test
    fun `MM-13 shows no amount the operator did not give`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-payer-otp-announced") { body ->
                body.getJSONArray("channels").getJSONObject(0).getJSONObject("payer_otp").apply {
                    remove("amount")
                    remove("steps")
                }
            }
            .on("GET /pay/sdk-config", "sdk-config-sandbox")
        val controller = controller(api.session())
        show(controller)
        controller.start()
        idle()
        controller.selectMethod("mobile_money")
        controller.setPhone("70000000")
        controller.payMobileMoney()
        idle()
        compose.onNodeWithTag("wajub-payer-otp").assertExists()
        compose.onNodeWithTag("wajub-payer-otp-amount").assertDoesNotExist()
        compose.onNodeWithText("2. Generate a payment code for the exact amount.").assertExists()
        compose.onNodeWithText("XOF", substring = true).assertDoesNotExist()
        compose.onNodeWithText("{amount}", substring = true).assertDoesNotExist()
        // Small rules of the screen's texts.
        assertEquals("No tries left.", SheetStrings.of(com.wajub.mobile.model.WajubLanguage.En).tries(-1))
        assertEquals("Il vous reste 2 essais.", SheetStrings.of(com.wajub.mobile.model.WajubLanguage.Fr).tries(2))
        assertEquals("tel:*144*4*6%23", dialUri(" *144*4*6 # ").toString())
    }

    @Test
    fun `a French session draws the sheet in French`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-cm-aggregate")
            .on("GET /pay/sdk-config", "sdk-config-ci-wallets")
            .on("POST /pay/process", "process-failed-402")
        val controller = controller(api.session(), PresentOptions())
        show(controller)
        controller.start()
        idle()
        compose.onNodeWithText("Numéro de téléphone").assertExists()
        compose.onNodeWithTag("wajub-pay").assertTextContains("Payer", substring = true)
        compose.onNodeWithText("Annuler le paiement").assertExists()
        compose.onNodeWithTag("wajub-phone").performTextInput("677123458")
        compose.onNodeWithTag("wajub-pay").performScrollTo().performClick()
        idle()
        // A failure screen with Try again, not an error replacing the form.
        assertIs<SheetScreen.Failed>(controller.screen.value)
        compose.onNodeWithText("Paiement échoué").assertExists()
        compose.onNodeWithText("Fonds insuffisants.").assertExists()
        compose.onNodeWithTag("wajub-try-again").assertTextContains("Réessayer").performClick()
        idle()
        compose.onNodeWithTag("wajub-phone").assertTextContains("67 71 23 45 8")
        assertIs<SheetScreen.Form>(controller.screen.value)
    }

    @Test
    fun `the 1_x PaymentSheet reports as it did, in its bottom sheet`() {
        // A sandbox session: no socket (echo has no key), polling only.
        val api = FakeApi()
            .on("GET /pay/session", "session-sandbox-cm")
            .on("GET /pay/sdk-config", "sdk-config-sandbox")
            .on("POST /pay/process", "process-processing-mobile-money-sandbox")
            .on("GET /pay/status", "status-processing").on("GET /pay/status", "status-succeeded")
        val results = mutableListOf<PaymentResult>()
        var dismissed = 0
        val session = api.session()
        val shown = mutableStateOf(true)
        compose.setContent {
            if (shown.value) {
                @Suppress("DEPRECATION")
                PaymentSheet(session, onDismiss = { dismissed++ }, onResult = { results += it })
            }
        }
        idle()
        // In the sheet's dialog window: click through the semantics actions.
        compose.onNodeWithTag("wajub-method-mobile_money").performSemanticsAction(SemanticsActions.OnClick)
        idle()
        compose.onNodeWithTag("wajub-phone").performTextInput("670000000")
        compose.onNodeWithTag("wajub-pay").performSemanticsAction(SemanticsActions.OnClick)
        idle()
        // As in 1.x: the push approval is a RequiresAction result.
        val approval = assertIs<PaymentResult.RequiresAction>(results.single())
        assertEquals(ActionKind.PushApproval, approval.action)
        assertEquals("Confirm the transaction on your phone. If no prompt appears, dial *126# to complete.", approval.transaction.processingContext?.payerInstruction)
        compose.onNodeWithTag("wajub-approval").assertExists()
        idle(6)
        compose.onNodeWithTag("wajub-success").assertExists()
        assertEquals(1, results.size, "1.x reported the answers only: an app watching itself gets no second Complete")
        // The app hides it, as 1.x apps did on a result: that is no dismiss.
        shown.value = false
        idle()
        assertEquals(0, dismissed)
    }

    @Test
    fun `the 1_x sheet cancels on a dismiss only when nothing was submitted, without waiting`() {
        // Pay pressed (a 422 sends the payer back to the form): 1.x never cancels then.
        val api = FakeApi()
            .on("GET /pay/session", "session-sandbox-cm")
            .on("GET /pay/sdk-config", "sdk-config-sandbox")
            .on("POST /pay/process", "process-422-sandbox-invalid-test-number")
            .on("POST /pay/cancel", "cancel-200")
        var dismissed = 0
        val session = api.session()
        compose.setContent {
            @Suppress("DEPRECATION")
            PaymentSheet(session, onDismiss = { dismissed++ }, onResult = { })
        }
        idle()
        compose.onNodeWithTag("wajub-method-mobile_money").performSemanticsAction(SemanticsActions.OnClick)
        idle()
        compose.onNodeWithTag("wajub-phone").performTextInput("677123456")
        compose.onNodeWithTag("wajub-pay").performSemanticsAction(SemanticsActions.OnClick)
        idle()
        compose.onNodeWithTag("wajub-close").performSemanticsAction(SemanticsActions.OnClick)
        idle()
        assertEquals(1, dismissed, "at once")
        Thread.sleep(300)
        assertEquals(0, api.count("POST /pay/cancel"))

        // A second sheet is not rejected: the first freed its place at once.
        val second = FakeApi().on("GET /pay/session", "session-sandbox-cm").on("GET /pay/sdk-config", "sdk-config-sandbox").on("POST /pay/cancel", "cancel-200")
        val results = mutableListOf<CheckoutResult>()
        val controller = controller(second.session())
        controller.onFinished = { results += it }
        controller.start()
        idle()
        assertTrue(results.isEmpty(), "not sheet_already_presented")
    }

    @Test
    fun `the 1_x sheet dismissed before anything was submitted cancels in the background`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-sandbox-cm")
            .on("GET /pay/sdk-config", "sdk-config-sandbox")
            .on("POST /pay/cancel", "cancel-200")
        var dismissed = 0
        val session = api.session()
        compose.setContent {
            @Suppress("DEPRECATION")
            PaymentSheet(session, onDismiss = { dismissed++ }, onResult = { })
        }
        idle()
        compose.onNodeWithTag("wajub-close").performSemanticsAction(SemanticsActions.OnClick)
        idle()
        assertEquals(1, dismissed)
        val deadline = System.currentTimeMillis() + 5_000
        while (api.count("POST /pay/cancel") == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, api.count("POST /pay/cancel"))
    }

    @Test
    fun `a session created again on recomposition is the same sheet`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-ci-xof-wallets")
            .on("GET /pay/sdk-config", "sdk-config-ci-wallets")
            .on("POST /pay/cancel", "cancel-200")
        val results = mutableListOf<CheckoutResult>()
        val tick = mutableIntStateOf(0)
        val options = PresentOptions(locale = "en")
        compose.setContent {
            tick.intValue
            // As integrators write it: a session created in the composable.
            PaymentSheet(api.session(), options) { results += it }
        }
        idle()
        tick.intValue++
        idle()
        tick.intValue++
        idle()
        assertTrue(results.isEmpty(), "$results")
        assertEquals(1, api.count("GET /pay/session"))
        compose.onNodeWithTag("wajub-method-mobile_money").assertExists()
    }

    @Test
    fun `a 1_x session created again on recomposition is the same sheet`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-ci-xof-wallets")
            .on("GET /pay/sdk-config", "sdk-config-ci-wallets")
            .on("POST /pay/cancel", "cancel-200")
        var dismissed = 0
        val tick = mutableIntStateOf(0)
        compose.setContent {
            tick.intValue
            @Suppress("DEPRECATION")
            PaymentSheet(api.session(), onDismiss = { dismissed++ }, onResult = { })
        }
        idle()
        tick.intValue++
        idle()
        assertEquals(0, dismissed)
        assertEquals(0, api.count("POST /pay/cancel"))
        assertEquals(1, api.count("GET /pay/session"))
    }

    @Test
    fun `the country selector opens and changes the operators`() {
        val channels = org.json.JSONArray()
            .put(org.json.JSONObject().put("id", "ci.orange").put("slug", "ci.orange").put("name", "Orange Money").put("type", "mobile_money").put("countries", org.json.JSONArray().put("CI")).put("currency", "XOF"))
            .put(org.json.JSONObject().put("id", "sn.wave").put("slug", "sn.wave").put("name", "Wave").put("type", "mobile_money").put("countries", org.json.JSONArray().put("SN")).put("currency", "XOF"))
        val api = FakeApi()
            .on("GET /pay/session", "session-ci-xof-wallets") { it.put("channels", channels) }
            .on("GET /pay/sdk-config", "sdk-config-ci-wallets")
        val controller = controller(api.session())
        show(controller)
        controller.start()
        idle()
        compose.onNodeWithTag("wajub-operator-orange").assertExists()
        compose.onNodeWithTag("wajub-country").performClick()
        idle()
        compose.onNodeWithTag("wajub-country-SN").performClick()
        idle()
        compose.onNodeWithTag("wajub-operator-wave").assertExists()
        compose.onNodeWithTag("wajub-operator-orange").assertDoesNotExist()
        assertEquals("SN", assertIs<SheetScreen.Form>(controller.screen.value).mobileMoney!!.country)
    }

    @Test
    fun `the birthday step takes the date as digits and sends YYYY-MM-DD`() {
        val api = FakeApi()
            .on("GET /pay/session", "session-sandbox-cm")
            .on("GET /pay/sdk-config", "sdk-config-sandbox")
            .on("POST /pay/process", "process-requires-birthday")
            .on("POST /pay/birthday/verify", "birthday-verify-success")
            .on("GET /pay/status", "status-processing")
        val controller = controller(api.session())
        show(controller)
        controller.start()
        idle()
        compose.onNodeWithTag("wajub-method-mobile_money").performClick()
        idle()
        compose.onNodeWithTag("wajub-phone").performTextInput("670000000")
        compose.onNodeWithTag("wajub-pay").performScrollTo().performClick()
        idle()
        compose.onNodeWithTag("wajub-step-birthday").assertExists().performTextInput("19850102")
        idle()
        compose.onNodeWithTag("wajub-step-birthday").assertTextContains("1985-01-02")
        // Or picked in the platform's date picker, opened on the typed date.
        compose.onNodeWithTag("wajub-birthday-picker").performClick()
        idle()
        val picker = org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.DatePickerDialog
        assertEquals(1985, picker.datePicker.year)
        picker.updateDate(1990, 3, 21)
        picker.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        compose.onNodeWithTag("wajub-step-birthday").assertTextContains("1990-04-21")
        // Every step offers its resend after the countdown, not only OTP.
        compose.onNodeWithTag("wajub-resend").assertExists()
        compose.onNodeWithTag("wajub-verify").performScrollTo().performClick()
        idle()
        assertEquals("1990-04-21", api.lastBody("POST /pay/birthday/verify").getString("birthday"))
    }

    @Test
    fun `the sheet's callbacks follow the screen across a configuration change`() {
        val api = FakeApi().on("GET /pay/session", "session-ci-xof-wallets").on("GET /pay/sdk-config", "sdk-config-ci-wallets")
        CallbackActivity.session = api.session()
        CallbackActivity.log.clear()
        val activity = Robolectric.buildActivity(CallbackActivity::class.java).setup()
        idle()
        activity.recreate()
        idle()
        val current = System.identityHashCode(activity.get())
        activity.pause().stop().destroy()
        idle()
        assertEquals(
            listOf("callbacks.onComplete in $current", "onComplete in $current"),
            CallbackActivity.log,
            "never in the activity the rotation destroyed",
        )
    }

    @Test
    fun `a second sheet shown while one is open reports sheet_already_presented`() {
        val api = FakeApi().on("GET /pay/session", "session-sandbox-cm").on("GET /pay/sdk-config", "sdk-config-sandbox")
        val first = mutableListOf<CheckoutResult>()
        val second = mutableListOf<CheckoutResult>()
        val options = PresentOptions(locale = "en")
        compose.setContent {
            PaymentSheet(api.session(), options) { first += it }
            PaymentSheet(api.session(), options) { second += it }
        }
        idle()
        assertEquals("sheet_already_presented", assertIs<CheckoutResult.Failed>(second.single()).error.code)
        assertTrue(first.isEmpty(), "the open sheet is left alone")
    }

    @Test
    fun `the sheet survives a configuration change and settles when its activity goes away`() {
        val api = FakeApi().on("GET /pay/session", "session-ci-xof-wallets").on("GET /pay/sdk-config", "sdk-config-ci-wallets")
        SheetTestActivity.session = api.session()
        SheetTestActivity.results.clear()
        val activity = Robolectric.buildActivity(SheetTestActivity::class.java).setup()
        idle()
        assertEquals(1, api.count("GET /pay/session"))
        activity.recreate()
        idle()
        assertEquals(1, api.count("GET /pay/session"), "the same sheet, not a new one")
        assertTrue(SheetTestActivity.results.isEmpty(), "a configuration change settles nothing")
        activity.pause().stop().destroy()
        idle()
        assertEquals(listOf<CheckoutResult>(CheckoutResult.Cancelled(CancelReason.HostGone, null)), SheetTestActivity.results)
        assertEquals(0, api.count("POST /pay/cancel"), "the host going away never cancels the payment")
    }
}

/** Logs which activity instance its callbacks run in. */
class CallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = System.identityHashCode(this)
        setContent {
            PaymentSheet(
                session!!,
                PresentOptions(
                    locale = "en",
                    callbacks = CheckoutCallbacks(onComplete = { log += "callbacks.onComplete in $me" }),
                ),
            ) { log += "onComplete in $me" }
        }
    }

    companion object {
        var session: WajubSession? = null
        val log = mutableListOf<String>()
    }
}

/** An activity that shows the sheet, as an app would. */
class SheetTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PaymentSheet(session!!, PresentOptions(locale = "en")) { results += it }
        }
    }

    companion object {
        var session: WajubSession? = null
        val results = mutableListOf<CheckoutResult>()
    }
}
