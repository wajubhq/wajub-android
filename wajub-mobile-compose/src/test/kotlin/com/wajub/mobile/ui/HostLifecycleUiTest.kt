package com.wajub.mobile.ui

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.wajub.mobile.WajubMobile
import com.wajub.mobile.WajubSession
import com.wajub.mobile.checkout.CancelReason
import com.wajub.mobile.checkout.CheckoutCallbacks
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Answers from fixtures on OkHttp's own threads; a held route waits for its latch. */
class LatchApi {
    private val routes = mutableMapOf<String, ArrayDeque<String>>()
    val requests: MutableList<String> = CopyOnWriteArrayList()
    val answered: MutableList<String> = CopyOnWriteArrayList()
    private val held = mutableMapOf<String, CountDownLatch>()

    fun on(route: String, fixture: String): LatchApi {
        synchronized(routes) { routes.getOrPut(route) { ArrayDeque() }.add(fixture) }
        return this
    }

    fun hold(route: String): CountDownLatch = CountDownLatch(1).also { synchronized(held) { held[route] = it } }

    fun count(route: String) = requests.count { it == route }

    fun session(token: String): WajubSession =
        WajubMobile.createSession(
            token,
            OkHttpClient.Builder().addInterceptor { chain ->
                val route = "${chain.request().method} ${chain.request().url.encodedPath}"
                requests += route
                synchronized(held) { held[route] }?.await(20, TimeUnit.SECONDS)
                val fixture = synchronized(routes) {
                    val queue = routes[route] ?: error("No fake answer for $route")
                    if (queue.size > 1) queue.removeFirst() else queue.first()
                }
                val file = Fixtures.file(fixture)
                answered += route
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(file.getInt("http_status"))
                    .message("fake")
                    .body(Fixtures.served(fixture).toString().toResponseBody("application/json".toMediaType()))
                    .build()
            }.build(),
        )
}

/** Shows the sheet as an app would, logging lifecycle, composition and every callback. */
class HostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = System.identityHashCode(this)
        log += "create $me"
        val restored = savedInstanceState != null
        setContent {
            DisposableEffect(Unit) {
                log += "composed $me"
                onDispose { log += "disposed $me" }
            }
            // The first frame after a recreation without the sheet (a window drawn at its first traversal).
            var ready by androidx.compose.runtime.remember { mutableStateOf(!(deferOnRestore && restored)) }
            if (!ready) androidx.compose.runtime.LaunchedEffect(Unit) {
                log += "deferred sheet composes"
                ready = true
            }
            androidx.compose.runtime.SideEffect { log += "body $me ready=$ready first=${first.value} second=${second.value}" }
            if (ready && first.value) {
                PaymentSheet(session!!, options.copy(callbacks = callbacks("cb"))) {
                    log += "host.onComplete $me ${it.status}"
                    results += it
                    onCompleteHook?.invoke(it)
                }
            }
            if (ready && second.value) {
                PaymentSheet(secondSession!!, options.copy(callbacks = callbacks("cb2"))) {
                    log += "host2.onComplete $me ${it.status}"
                    secondResults += it
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log += "destroy ${System.identityHashCode(this)} changing=$isChangingConfigurations"
        onDestroyHook?.let {
            onDestroyHook = null
            it()
        }
    }

    companion object {
        var session: WajubSession? = null
        var secondSession: WajubSession? = null
        var options = PresentOptions(locale = "en")
        val first = mutableStateOf(true)
        val second = mutableStateOf(false)
        val log: MutableList<String> = CopyOnWriteArrayList()
        val results: MutableList<CheckoutResult> = CopyOnWriteArrayList()
        val secondResults: MutableList<CheckoutResult> = CopyOnWriteArrayList()
        var onDestroyHook: (() -> Unit)? = null
        var onCompleteHook: ((CheckoutResult) -> Unit)? = null
        var deferOnRestore = false

        fun callbacks(tag: String) = CheckoutCallbacks(
            onSuccess = { log += "$tag.onSuccess" },
            onError = { log += "$tag.onError ${it.code}" },
            onCancel = { log += "$tag.onCancel ${it.reason.value}" },
            onComplete = { log += "$tag.onComplete ${it.status}" },
            onStateChange = { log += "$tag.state ${it.state.value}" },
        )

        fun reset(first: WajubSession, second: WajubSession? = null) {
            session = first
            secondSession = second
            options = PresentOptions(locale = "en")
            this.first.value = true
            this.second.value = false
            log.clear()
            results.clear()
            secondResults.clear()
            onDestroyHook = null
            onCompleteHook = null
            deferOnRestore = false
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HostLifecycleUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun activeRef(): AtomicReference<Any?> {
        val cls = Class.forName("com.wajub.mobile.checkout.ActiveSheet")
        val field = cls.getDeclaredField("current").apply { isAccessible = true }
        val owner = if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else cls.getField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        return field.get(owner) as AtomicReference<Any?>
    }

    private fun active(): CheckoutController? = activeRef().get() as CheckoutController?

    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<HostActivity>>()

    private fun built(c: org.robolectric.android.controller.ActivityController<HostActivity>) = c.also { activities += it }

    @Before
    fun clearSheets() {
        activeRef().set(null)
    }

    @org.junit.After
    fun destroyActivities() {
        HostActivity.onCompleteHook = null
        HostActivity.onDestroyHook = null
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        activeRef().get()?.let { (it as CheckoutController).hostGone() }
        activeRef().set(null)
        HostActivity.session?.let { HostActivity.reset(it) }
    }

    private fun pump(ms: Long = 50) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun pumpUntil(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what; log=${HostActivity.log}")
            pump()
            Thread.sleep(10)
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private fun sandboxApi(cancel: String = "cancel-200") = LatchApi()
        .on("GET /pay/session", "session-sandbox-cm")
        .on("GET /pay/sdk-config", "sdk-config-sandbox")
        .on("POST /pay/cancel", cancel)
        .on("GET /pay/status", "status-succeeded")

    // ── A ────────────────────────────────────────────────────────────────

    @Test
    fun `ST-03 a cancel's answer landing during a configuration change reaches the new screen`() {
        val api = sandboxApi()
        val gate = api.hold("POST /pay/cancel")
        HostActivity.reset(api.session("tok_a"))
        val activity = built(Robolectric.buildActivity(HostActivity::class.java).setup())
        pumpUntil("methods") { active()?.screen?.value is SheetScreen.Methods }
        val controller = active()!!
        controller.cancelPayment()
        pumpUntil("cancel sent") { api.count("POST /pay/cancel") == 1 }
        // The cancel's answer arrives while the activity is being recreated.
        HostActivity.onDestroyHook = {
            gate.countDown()
            waitFor { "POST /pay/cancel" in api.answered }
            Thread.sleep(300)
            HostActivity.log += "cancel answer posted to main"
        }
        activity.recreate()
        HostActivity.log += "recreate() returned"
        repeat(40) { pump(100) }
        val log = HostActivity.log.toList()
        println("A controller.result=${controller.result.value}")
        assertEquals(1, HostActivity.results.size, "host onComplete calls; log=$log; controller.result=${controller.result.value}")
    }

    @Test
    fun `ST-03 a result posted during a configuration change reaches the new screen`() {
        // Same window, deterministic: a dismiss (cancelOnDismiss = false, settles at once) runs in a main-thread
        // message queued while the old activity is being destroyed, as a coroutine resumption would be.
        val api = sandboxApi()
        HostActivity.reset(api.session("tok_a2"))
        HostActivity.options = HostActivity.options.copy(cancelOnDismiss = false)
        val activity = built(Robolectric.buildActivity(HostActivity::class.java).setup())
        pumpUntil("methods") { active()?.screen?.value is SheetScreen.Methods }
        val controller = active()!!
        HostActivity.onDestroyHook = {
            android.os.Handler(Looper.getMainLooper()).post {
                HostActivity.log += "posted dismiss runs"
                controller.dismiss()
            }
        }
        activity.recreate()
        HostActivity.log += "recreate() returned"
        repeat(20) { pump(100) }
        val log = HostActivity.log.toList()
        println("A2 controller.result=${controller.result.value}")
        assertEquals(1, HostActivity.results.size, "host onComplete calls; log=$log; controller.result=${controller.result.value}")
    }

    // ── B ────────────────────────────────────────────────────────────────

    @Test
    fun `ST-04 an activity finishing while Cancel payment runs settles once with the cancel's result`() {
        for (variant in listOf("200", "400 + succeeded")) {
            activeRef().set(null)
            val api = sandboxApi(if (variant == "200") "cancel-200" else "cancel-400-not-cancellable")
            val gate = api.hold("POST /pay/cancel")
            HostActivity.reset(api.session("tok_b"))
            val activity = built(Robolectric.buildActivity(HostActivity::class.java).setup())
            pumpUntil("methods") { active()?.screen?.value is SheetScreen.Methods }
            val controller = active()!!
            controller.cancelPayment()
            pumpUntil("cancel sent") { api.count("POST /pay/cancel") == 1 }
            activity.pause().stop().destroy()
            HostActivity.log += "activity destroyed"
            repeat(5) { pump(100) }
            assertTrue(HostActivity.results.isEmpty(), "$variant: not before the cancel answers")
            gate.countDown()
            pumpUntil("result") { HostActivity.results.isNotEmpty() }
            repeat(30) { pump(100) }
            val log = HostActivity.log.toList()
            assertEquals(1, HostActivity.results.size, "$variant: $log")
            val result = HostActivity.results.single()
            if (variant == "200") {
                val cancelled = assertIs<CheckoutResult.Cancelled>(result)
                assertEquals(CancelReason.PayerCancelled, cancelled.reason)
                assertEquals(Fixtures.file("cancel-200").getJSONObject("body").getString("redirect_url"), cancelled.redirectUrl)
            } else {
                assertIs<CheckoutResult.Complete>(result)
                assertEquals(1, api.count("GET /pay/status"))
            }
            val last = log.indexOfLast { it.startsWith("host.onComplete") }
            assertEquals(log.size - 1, last, "$variant: nothing after onComplete: $log")
            assertEquals(1, log.count { it.startsWith("cb.onComplete") }, "$variant: callbacks.onComplete once")
        }
    }

    // ── D ────────────────────────────────────────────────────────────────

    private fun failedThenClose(control: String) {
        val api = FakeApi()
            .on("GET /pay/session", "session-sandbox-cm")
            .on("GET /pay/sdk-config", "sdk-config-sandbox")
            .on("POST /pay/process", "process-failed-402")
            .on("POST /pay/cancel", "cancel-200")
        val events = mutableListOf<String>()
        val results = mutableListOf<CheckoutResult>()
        val options = PresentOptions(
            locale = "en",
            callbacks = CheckoutCallbacks(
                onError = { events += "error:${it.code}" },
                onCancel = { events += "cancel:${it.reason.value}" },
                onComplete = { events += "complete:${it.status}" },
            ),
        )
        val session = api.session()
        compose.setContent {
            PaymentSheet(session, options) { results += it }
        }
        idle()
        compose.onNodeWithTag("wajub-method-mobile_money").performSemanticsAction(SemanticsActions.OnClick)
        idle()
        compose.onNodeWithTag("wajub-phone").performTextInput("670000000")
        compose.onNodeWithTag("wajub-pay").performSemanticsAction(SemanticsActions.OnClick)
        idle()
        compose.onNodeWithTag("wajub-failed").assertExists()
        when (control) {
            "X" -> compose.onNodeWithTag("wajub-close").performSemanticsAction(SemanticsActions.OnClick)
            "Close" -> compose.onNodeWithTag("wajub-close-button").performSemanticsAction(SemanticsActions.OnClick)
            "scrim" -> compose.onNodeWithTag("wajub-scrim").performSemanticsAction(SemanticsActions.OnClick)
            "back" -> (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
            "swipe" -> compose.onNodeWithTag("wajub-bottom-sheet").performTouchInput {
                swipe(start = Offset(centerX, 5f), end = Offset(centerX, 2_500f), durationMillis = 400)
            }
        }
        idle()
        val failed = assertIs<CheckoutResult.Failed>(results.single(), control)
        assertEquals("insufficient_funds", failed.error.code, control)
        assertEquals(1, api.count("POST /pay/cancel"), "$control: the order is released")
        assertTrue(events.none { it.startsWith("cancel:") }, "$control: no onCancel: $events")
        assertEquals("complete:failed", events.last(), control)
    }

    private fun idle(seconds: Long = 1) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        compose.waitForIdle()
    }

    @Test
    fun `CN-02 a dismiss on FAILED with the header X releases the order and resolves failed`() = failedThenClose("X")

    @Test
    fun `CN-02 a dismiss on FAILED with the Close button releases the order and resolves failed`() = failedThenClose("Close")

    @Test
    fun `CN-02 a dismiss on FAILED with a scrim tap releases the order and resolves failed`() = failedThenClose("scrim")

    @Test
    fun `CN-02 a dismiss on FAILED with back releases the order and resolves failed`() = failedThenClose("back")

    @Test
    fun `CN-02 a dismiss on FAILED with a swipe down releases the order and resolves failed`() = failedThenClose("swipe")
}
