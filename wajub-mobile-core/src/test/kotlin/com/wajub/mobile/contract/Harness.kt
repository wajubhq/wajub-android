package com.wajub.mobile.contract

import com.wajub.mobile.checkout.ActiveSheet
import com.wajub.mobile.checkout.CheckoutCallbacks
import com.wajub.mobile.checkout.CheckoutController
import com.wajub.mobile.checkout.CheckoutResult
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.realtime.RealtimeConnection
import com.wajub.mobile.realtime.RealtimeConnector
import com.wajub.mobile.realtime.RealtimeEndpoint
import com.wajub.mobile.realtime.RealtimeListener
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.random.Random

/** Every callback, in order, as short strings. */
class Recorder {
    val events = mutableListOf<String>()
    val results = mutableListOf<CheckoutResult>()

    fun callbacks() = CheckoutCallbacks(
        onReady = { events += "ready" },
        onStateChange = { events += "state:${it.state.value}" },
        onLoadError = { events += "loadError:${it.code}" },
        onPaymentStarted = { events += "started:${it.method}:${it.channel}" },
        onRedirect = { events += "redirect:${it.url}" },
        onSuccess = { events += "success:${it.method}" },
        onError = { events += "error:${it.code}" },
        onCancel = { events += "cancel:${it.reason.value}" },
        onComplete = {
            events += "complete:${it.status}"
            results += it
        },
    )

    val states: List<String> get() = events.filter { it.startsWith("state:") }.map { it.removePrefix("state:") }

    val result: CheckoutResult get() = results.single()
}

/** A socket that never opens on its own: the test says when it connects. */
internal class FakeSocket : RealtimeConnector {
    var endpoint: RealtimeEndpoint? = null
    var channel: String? = null
    var authUrl: String? = null
    var authHeaders: Map<String, String> = emptyMap()
    var listener: RealtimeListener? = null
    var closed = false

    override fun open(
        endpoint: RealtimeEndpoint,
        channel: String,
        authUrl: String,
        authHeaders: Map<String, String>,
        listener: RealtimeListener,
    ): RealtimeConnection {
        this.endpoint = endpoint
        this.channel = channel
        this.authUrl = authUrl
        this.authHeaders = authHeaders
        this.listener = listener
        return RealtimeConnection { closed = true }
    }
}

/**
 * A checkout over [api] in virtual time: the controller runs on the test's
 * scheduler, so `advanceTimeBy` drives timeouts, polling and auto-close.
 */
internal fun TestScope.checkout(
    api: FakeApi,
    options: PresentOptions = PresentOptions(sessionToken = "test_tok"),
    recorder: Recorder = Recorder(),
    socket: RealtimeConnector = FakeSocket(),
    deviceLanguage: String = "en-US",
    opener: (String) -> Boolean = { true },
): Pair<CheckoutController, Recorder> {
    ActiveSheet.clear()
    api.clock = { testScheduler.currentTime }
    val controller = CheckoutController(
        session = fakeSession(api),
        options = options.copy(callbacks = recorder.callbacks()),
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        random = Random(3),
        connector = socket,
        compute = StandardTestDispatcher(testScheduler),
        deviceLanguage = { deviceLanguage },
    )
    controller.urlOpener = opener
    return controller to recorder
}

/** The API of a pending Cameroon session with its sdk-config, Mobile Money approved and paid. */
internal fun cmApi(session: String = "session-cm-aggregate", sdkConfig: String = "sdk-config-cm-stripe"): FakeApi =
    FakeApi()
        .on("GET /pay/session", Fixtures.response(session))
        .on("GET /pay/sdk-config", Fixtures.response(sdkConfig))
        .on("POST /pay/cancel", Fixtures.response("cancel-200"))
