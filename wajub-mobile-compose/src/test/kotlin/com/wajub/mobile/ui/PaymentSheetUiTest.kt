package com.wajub.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
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
import com.wajub.mobile.realtime.RealtimeOptions
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
        // Polling only: no socket leaves the test machine.
        val polling = options.copy(realtime = RealtimeOptions(enabled = false))
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
        assertIs<PaymentResult.Complete>(results.last(), "and now the outcome too")
        // The app hides it, as 1.x apps did on a result: that is no dismiss.
        shown.value = false
        idle()
        assertEquals(0, dismissed)
        assertEquals(2, results.size)
    }

    @Test
    fun `a second sheet shown while one is open reports sheet_already_presented`() {
        val api = FakeApi().on("GET /pay/session", "session-sandbox-cm").on("GET /pay/sdk-config", "sdk-config-sandbox")
        val first = mutableListOf<CheckoutResult>()
        val second = mutableListOf<CheckoutResult>()
        val options = PresentOptions(locale = "en", realtime = RealtimeOptions(enabled = false))
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
