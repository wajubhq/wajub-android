package com.wajub.mobile.ui

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.realtime.RealtimeOptions
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Activities driven by Robolectric's own looper and Choreographer (no Compose test clock). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class RecreationUiTest {
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

    private fun sandboxApi(cancel: String = "cancel-200") = LatchApi()
        .on("GET /pay/session", "session-sandbox-cm")
        .on("GET /pay/sdk-config", "sdk-config-sandbox")
        .on("POST /pay/cancel", cancel)
        .on("GET /pay/status", "status-succeeded")

    // A result (a coroutine resumption on Dispatchers.Main is an async message) queued while the
    // activity is being recreated.
    private fun recreateWithResultQueued(defer: Boolean): Pair<List<String>, CheckoutController> {
        val api = sandboxApi()
        HostActivity.reset(api.session("tok_a3_$defer"))
        HostActivity.options = HostActivity.options.copy(cancelOnDismiss = false)
        HostActivity.deferOnRestore = defer
        val activity = built(Robolectric.buildActivity(HostActivity::class.java).setup())
        pumpUntil("methods") { active()?.screen?.value is SheetScreen.Methods }
        val controller = active()!!
        HostActivity.onDestroyHook = {
            Handler.createAsync(Looper.getMainLooper()).post {
                HostActivity.log += "queued result runs (settled=${controller.result.value != null})"
                controller.dismiss()
            }
        }
        activity.recreate()
        HostActivity.log += "recreate() returned"
        repeat(20) { pump(100) }
        return HostActivity.log.toList() to controller
    }

    @Test
    fun `ST-03 a result queued during a recreation reaches the new screen`() {
        val (log, controller) = recreateWithResultQueued(defer = false)
        assertEquals(1, HostActivity.results.size, "host onComplete calls; log=$log; controller.result=${controller.result.value}")
    }

    @Test
    fun `ST-03 a result landing before the new activity's first frame reaches the new screen`() {
        val (log, controller) = recreateWithResultQueued(defer = true)
        assertEquals(1, HostActivity.results.size, "host onComplete calls; log=$log; controller.result=${controller.result.value}")
    }

    @Test
    fun `ST-03 the view model delivers a result that settled while no screen was attached`() {
        WajubAndroid.install(RuntimeEnvironment.getApplication())
        val api = sandboxApi()
        val session = api.session("tok_a4")
        val results = mutableListOf<CheckoutResult>()
        val model = CheckoutViewModel(SavedStateHandle())
        val options = PresentOptions(locale = "en", realtime = RealtimeOptions(enabled = false), cancelOnDismiss = false)
        // The first screen attaches, as SheetHost does.
        model.attach { results += it }
        val controller = model.controllerFor({ session }, options)
        pumpUntil("methods") { controller.screen.value is SheetScreen.Methods }
        // Configuration change: the old screen detaches.
        model.detach()
        // The sheet settles before the new screen attaches (an auto-close, a cancel's answer…).
        controller.dismiss()
        pump()
        assertTrue(model.settled)
        assertTrue(results.isEmpty(), "no screen to tell yet")
        // The new screen attaches (its composition, then its SideEffect): told once.
        model.attach { results += it }
        model.attach { results += it }
        pump()
        assertEquals(1, results.size, "controller.result=${controller.result.value}")
        assertIs<CheckoutResult.Cancelled>(results.single())
    }

    @Test
    fun `ST-05 a sheet presented again from onComplete is not rejected and loads`() {
        val api = sandboxApi()
        val api2 = sandboxApi()
        HostActivity.reset(api.session("tok_c1"), api2.session("tok_c1_bis"))
        HostActivity.onCompleteHook = {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                HostActivity.first.value = false
                HostActivity.second.value = true
            }
        }
        built(Robolectric.buildActivity(HostActivity::class.java).setup())
        pumpUntil("methods") { active()?.screen?.value is SheetScreen.Methods }
        val firstController = active()!!
        firstController.dismiss()
        pumpUntil("first result") { HostActivity.results.isNotEmpty() }
        pumpUntil("second sheet loaded") { active() != null && active() !== firstController && active()?.screen?.value is SheetScreen.Methods }
        repeat(5) { pump(100) }
        val log = HostActivity.log.toList()
        assertIs<CheckoutResult.Cancelled>(HostActivity.results.single())
        assertTrue(HostActivity.secondResults.isEmpty(), "not rejected: ${HostActivity.secondResults}")
        assertEquals(1, api2.count("GET /pay/session"))
        active()!!.hostGone()
    }

    @Test
    fun `ST-05 after a configuration change a second sheet is rejected and the first left alone`() {
        val api = sandboxApi()
        val api2 = sandboxApi()
        HostActivity.reset(api.session("tok_c2"), api2.session("tok_c2_bis"))
        val activity = built(Robolectric.buildActivity(HostActivity::class.java).setup())
        pumpUntil("methods") { active()?.screen?.value is SheetScreen.Methods }
        val firstController = active()!!
        activity.recreate()
        repeat(5) { pump(100) }
        assertTrue(HostActivity.results.isEmpty())
        // The recreated screen also asks for a second sheet. (Shown with the next configuration
        // change: an earlier test of this class leaves Robolectric unable to recompose this
        // activity on a test-side state write, while its first composition is unaffected.)
        HostActivity.second.value = true
        activity.recreate()
        pumpUntil("second rejected") { HostActivity.secondResults.isNotEmpty() }
        repeat(5) { pump(100) }
        val rejected = assertIs<CheckoutResult.Failed>(HostActivity.secondResults.single())
        assertEquals("sheet_already_presented", rejected.error.code)
        assertEquals(0, api2.count("GET /pay/session"), "the rejected sheet loads nothing")
        assertTrue(HostActivity.results.isEmpty(), "the first sheet is left alone")
        assertTrue(active() === firstController)
        assertIs<SheetScreen.Methods>(firstController.screen.value)
        firstController.dismiss()
        pumpUntil("first result") { HostActivity.results.isNotEmpty() }
        repeat(5) { pump(100) }
        assertEquals(1, HostActivity.results.size)
        assertIs<CheckoutResult.Cancelled>(HostActivity.results.single())
        assertEquals(1, api.count("POST /pay/cancel"))
    }
}
