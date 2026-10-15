package com.wajub.mobile.contract

import com.wajub.mobile.API_URL
import com.wajub.mobile.SDK_VERSION
import com.wajub.mobile.checkout.CheckoutState
import com.wajub.mobile.checkout.PresentOptions
import com.wajub.mobile.checkout.SheetScreen
import com.wajub.mobile.client.OkHttpTransport
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.device.DeviceEnvironment
import com.wajub.mobile.device.FileInstallIdStore
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessRequest
import com.wajub.mobile.model.StepKind
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.WajubSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransportContractTest {
    private class Captured(val request: Request, val connectMs: Int, val readMs: Int, val callTimeoutNanos: Long)

    // OkHttp with an interceptor that answers in place of the network.
    private fun okHttp(captured: MutableList<Captured>, answer: (Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            captured += Captured(chain.request(), chain.connectTimeoutMillis(), chain.readTimeoutMillis(), chain.call().timeout().timeoutNanos())
            val (code, body) = answer(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .header("X-Request-Id", chain.request().header("X-Request-Id").orEmpty())
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    private fun okSession(captured: MutableList<Captured>, answer: (Request) -> Pair<Int, String>): WajubSession =
        WajubSession("test_tok", PayClient(OkHttpTransport(okHttp(captured, answer)), environment = { testDevice() }))

    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun fixtureFor(request: Request): Pair<Int, String> {
        val name = when (request.url.encodedPath) {
            "/pay/session" -> "session-cm-aggregate"
            "/pay/sdk-config" -> "sdk-config-cm-stripe"
            "/pay/status" -> "status-processing"
            "/pay/cancel" -> "cancel-200"
            "/pay/process" -> "process-processing-mobile-money-sandbox"
            "/pay/client-session" -> "client-session-start-202"
            "/pay/client-session/complete" -> "client-session-complete-202"
            "/pay/otp/verify" -> "otp-verify-success"
            "/pay/otp/resend" -> "otp-resend-200"
            "/pay/3ds/verify" -> "3ds-verify-202"
            "/pay/3ds/send" -> "3ds-send-200"
            else -> error("unexpected ${request.url}")
        }
        return Fixtures.status(name) to Fixtures.body(name).toString()
    }

    @Test
    fun `TR-01 sends the common headers`() = runBlocking {
        val captured = mutableListOf<Captured>()
        val session = okSession(captured, ::fixtureFor)
        session.loadSession()
        session.getSdkConfig()
        session.process(ProcessRequest("cm.mobile", mapOf("phone" to "+237670000000", "country" to "CM")))
        session.cancel()

        assertEquals(4, captured.size)
        for (c in captured) {
            val r = c.request
            assertEquals("Bearer test_tok", r.header("Authorization"))
            assertEquals("application/json", r.header("Accept"))
            assertEquals("fr-CM, fr;q=0.9, en;q=0.8", r.header("Accept-Language"))
            assertTrue(uuid.matches(r.header("X-Request-Id")!!), r.header("X-Request-Id"))
            assertEquals("wajub-kotlin/$SDK_VERSION (android 14)", r.header("X-Wajub-Client"))
            if (r.method == "POST") {
                assertTrue(r.body!!.contentType().toString().startsWith("application/json"))
            } else {
                assertNull(r.body)
            }
        }
        assertEquals(4, captured.map { it.request.header("X-Request-Id") }.toSet().size, "a fresh X-Request-Id per call")
    }

    @Test
    fun `TR-02 suffixes the user agent`() = runBlocking {
        val captured = mutableListOf<Captured>()
        okSession(captured, ::fixtureFor).loadSession()
        val agent = captured.single().request.header("User-Agent")!!
        assertTrue(agent.startsWith("okhttp/"), agent)
        assertTrue(agent.endsWith(" wajub-kotlin/$SDK_VERSION (android 14)"), agent)
    }

    @Test
    fun `TR-03 sends device signals`() = runTest {
        val api = cmApi().on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
        val reference = Fixtures.file("process-processing-mobile-money-sandbox")
            .getJSONObject("request").getJSONObject("body").getJSONObject("data").getJSONObject("_client")
        fakeSession(api).process(ProcessRequest("cm.mobile", mapOf("phone" to "+237670000000", "country" to "CM")))
        val client = api.lastBody("POST /pay/process").getJSONObject("data").getJSONObject("_client")
        for (key in reference.keys()) assertTrue(client.has(key), "_client.$key")
        assertEquals("Africa/Douala", client.getString("timezone"))
        assertEquals("fr-CM", client.getString("language"))
        assertEquals("1080x2400", client.getString("screen"))
        assertEquals(24, client.getInt("color_depth"))
        assertEquals("wajub-kotlin/$SDK_VERSION (android 14)", client.getString("client"))
        val fingerprint = client.getString("fingerprint")
        assertTrue(Regex("^[a-f0-9]{16,64}$").matches(fingerprint), fingerprint)

        // A second SDK instance on the same install sends the same fingerprint.
        val second = cmApi().on("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
        fakeSession(second).process(ProcessRequest("cm.mobile", mapOf("phone" to "+237670000000")))
        assertEquals(fingerprint, second.lastBody("POST /pay/process").getJSONObject("data").getJSONObject("_client").getString("fingerprint"))
    }

    @Test
    fun `TR-04 keeps a stable install fingerprint`() {
        val dir = Files.createTempDirectory("wajub-install").toFile()
        val file = dir.resolve("install-id")
        val first = DeviceEnvironment(installIds = FileInstallIdStore(file)).fingerprint()
        val id = file.readText()
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(id), "a random UUID is stored, not a hardware id")
        // A restarted SDK reads the same id.
        assertEquals(first, DeviceEnvironment(installIds = FileInstallIdStore(file)).fingerprint())
        val expected = MessageDigest.getInstance("SHA-256").digest("device:$id".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, first)
        // Another install, another fingerprint.
        assertNotEquals(first, DeviceEnvironment(installIds = FileInstallIdStore(dir.resolve("other"))).fingerprint())
        // Read once, then from memory; prewarmed off the calling thread.
        var reads = 0
        val counted = DeviceEnvironment(installIds = object : com.wajub.mobile.device.InstallIdStore {
            override fun read(): String? = "5f0c2b1e-8d4a-4c3b-9e7f-1a2b3c4d5e6f".also { reads++ }
            override fun write(id: String) = Unit
        })
        counted.prewarm()
        val warm = counted.fingerprint()
        assertEquals(warm, counted.fingerprint())
        assertEquals(1, reads)
        dir.deleteRecursively()
    }

    @Test
    fun `TR-05 applies per-call timeouts`() = runBlocking {
        val captured = mutableListOf<Captured>()
        val session = okSession(captured, ::fixtureFor)
        session.loadSession()
        session.getSdkConfig()
        session.status()
        session.cancel()
        session.resendStep(StepKind.Otp, "prc_1")
        session.resendStep(StepKind.ThreeDs, "3ds_1")
        val short = captured.size
        session.process(ProcessRequest("cm.mobile", mapOf("phone" to "+237670000000")))
        session.openClientSession(mapOf("email" to "a@b.co"))
        session.confirmClientSession("prc_1")
        session.verifyStep(StepKind.Otp, "prc_1", mapOf("code" to "123456"))
        session.verifyStep(StepKind.ThreeDs, "3ds_1", mapOf("code" to "123456"))
        for ((index, c) in captured.withIndex()) {
            val path = c.request.url.encodedPath
            assertEquals(10_000, c.connectMs, path)
            val expected = if (index < short) 30_000L else 60_000L
            assertEquals(expected * 1_000_000, c.callTimeoutNanos, "whole-call timeout of $path")
            assertEquals(expected.toInt(), c.readMs, "read timeout of $path")
        }
    }

    @Test
    fun `TR-06 retries process with the same idempotency key`() = runTest {
        val api = cmApi().on(
            "POST /pay/process",
            FakeResponse.timeout(),
            FakeResponse.status(503),
            Fixtures.response("process-idempotent-replay"),
        )
        val session = fakeSession(api)
        val outcome = session.process(ProcessRequest("cm.mobile", mapOf("phone" to "+237670000000", "country" to "CM", "operator" to "mtn")))
        assertIs<ProcessOutcome.Processing>(outcome)
        val tries = api.requests("POST /pay/process")
        assertEquals(3, tries.size)
        assertEquals(1, tries.map { it.body }.toSet().size, "the identical body")
        val key = tries.first().headers.getValue("Idempotency-Key")
        assertTrue(uuid.matches(key))
        assertTrue(tries.all { it.headers["Idempotency-Key"] == key })

        // A new press of Pay is a new key.
        api.set("POST /pay/process", Fixtures.response("process-processing-mobile-money-sandbox"))
        session.process(ProcessRequest("cm.mobile", mapOf("phone" to "+237670000000")))
        assertNotEquals(key, api.requests("POST /pay/process").last().headers["Idempotency-Key"])
    }

    @Test
    fun `TR-07 does not retry non-retryable posts`() = runTest {
        for (fixture in listOf("process-failed-402", "process-422-validation", "error-500-production")) {
            val api = cmApi().on("POST /pay/process", Fixtures.response(fixture))
            fakeSession(api).process(ProcessRequest("cm.mobile", mapOf("phone" to "+237670000000")))
            assertEquals(1, api.count("POST /pay/process"), fixture)
        }
        val verify = cmApi().on("POST /pay/otp/verify", FakeResponse.network())
        assertFailsWith<WajubError> { fakeSession(verify).verifyStep(StepKind.Otp, "prc_1", mapOf("code" to "000000")) }
        assertEquals(1, verify.count("POST /pay/otp/verify"))
        val invalid = cmApi().on("POST /pay/otp/verify", Fixtures.response("otp-verify-invalid"))
        fakeSession(invalid).verifyStep(StepKind.Otp, "prc_1", mapOf("code" to "000000"))
        assertEquals(1, invalid.count("POST /pay/otp/verify"))
    }

    @Test
    fun `TR-08 retries idempotent reads`() = runTest {
        val api = FakeApi().on("GET /pay/session", FakeResponse.status(503), FakeResponse.status(503), Fixtures.response("session-cm-aggregate"))
        api.clock = { testScheduler.currentTime }
        val session = fakeSession(api).loadSession()
        assertEquals("trx_PVrU8x2kQ1mNa7Lb3cDe", session.transaction.id)
        assertEquals(3, api.count("GET /pay/session"))
        val (t0, t1, t2) = api.times
        assertTrue(t1 - t0 in 700L..1300L, "first backoff ~1 s ±30 %: ${t1 - t0}")
        assertTrue(t2 - t1 in 1400L..2600L, "second backoff ~2 s ±30 %: ${t2 - t1}")

        // Network, timeout, 408, 500, 502, 504 are retried, 3 times at most.
        val flaky = FakeApi().on(
            "GET /pay/status",
            FakeResponse.network(), FakeResponse.timeout(), FakeResponse.status(408), FakeResponse.status(500),
        )
        assertFailsWith<WajubError> { fakeSession(flaky).status() }
        assertEquals(4, flaky.count("GET /pay/status"))

        // 429: Retry-After ≤ 10 s is waited for; a longer one is not.
        val short = FakeApi().on(
            "GET /pay/sdk-config",
            FakeResponse.status(429, headers = mapOf("Retry-After" to "4")),
            Fixtures.response("sdk-config-cm-stripe"),
        )
        short.clock = { testScheduler.currentTime }
        fakeSession(short).getSdkConfig()
        assertEquals(4_000L, short.times[1] - short.times[0])
        val long = FakeApi().on("GET /pay/sdk-config", Fixtures.response("error-429-rate-limited"))
        val error = assertFailsWith<WajubError> { fakeSession(long).getSdkConfig() }
        assertEquals("rate_limited", error.code)
        assertEquals(1, long.count("GET /pay/sdk-config"))

        // Never a 4xx.
        val gone = FakeApi().on("GET /pay/session", Fixtures.response("error-401-invalid-token"))
        assertFailsWith<WajubError> { fakeSession(gone).loadSession() }
        assertEquals(1, gone.count("GET /pay/session"))
    }

    @Test
    fun `TR-09 treats an unanswered process as pending`() = runTest {
        val api = cmApi()
            .on("POST /pay/process", FakeResponse.timeout())
            .on("GET /pay/status", Fixtures.response("status-processing"), Fixtures.response("status-succeeded"))
        val (controller, recorder) = checkout(api)
        controller.start()
        runCurrent()
        controller.selectMethod("mobile_money")
        controller.setPhone("670000000")
        controller.payMobileMoney()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(3, api.count("POST /pay/process"), "the request and its 2 retries")
        assertEquals(CheckoutState.PENDING, controller.state)
        assertTrue(recorder.events.none { it.startsWith("error:") }, "no failure for an unknown outcome")
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(CheckoutState.SUCCESS, controller.state)
        assertTrue(api.count("GET /pay/status") >= 2)

        // Still `pending` 60 s after the last try: the request most likely never
        // arrived. Back to the form with network_error; the next Pay reuses the key.
        val lost = cmApi()
            .on("POST /pay/process", FakeResponse.timeout())
            .on("GET /pay/status", Fixtures.response("status-processing") { it.getJSONObject("transaction").put("status", "pending") })
        val (again, events) = checkout(lost, PresentOptions(sessionToken = "t", locale = "en"))
        again.start()
        runCurrent()
        again.selectMethod("mobile_money")
        again.setPhone("670000000")
        again.payMobileMoney()
        advanceTimeBy(4_000)
        runCurrent()
        val lastTry = lost.times.last()
        val key = lost.requests("POST /pay/process").first().headers.getValue("Idempotency-Key")
        assertEquals(CheckoutState.PENDING, again.state)
        advanceTimeBy(lastTry + 59_000 - testScheduler.currentTime)
        runCurrent()
        assertEquals(CheckoutState.PENDING, again.state, "not before 60 s")
        advanceTimeBy(1_001)
        runCurrent()
        val form = assertIs<SheetScreen.Form>(again.screen.value)
        assertEquals("Connection error. Check your network.", form.banner)
        assertEquals(CheckoutState.COLLECTING_DETAILS, again.state)
        assertTrue("error:network_error" in events.events)
        val polls = lost.count("GET /pay/status")
        advanceTimeBy(30_000)
        assertEquals(polls, lost.count("GET /pay/status"), "no longer watching")
        lost.set("POST /pay/process", Fixtures.response("process-idempotent-replay"))
        lost.set("GET /pay/status", Fixtures.response("status-processing"))
        again.payMobileMoney()
        runCurrent()
        assertEquals(key, lost.requests("POST /pay/process").last().headers["Idempotency-Key"], "the same key: a replay, never a second charge")
        assertEquals(CheckoutState.APPROVAL_REQUIRED, again.state)

        // As on a real phone: each try times out after 60 s, so the first status is read
        // only once the 60 s have passed — the check waits for that read.
        val slow = cmApi()
            .on("POST /pay/process", FakeResponse.timeout(delayMs = 60_000))
            .on("GET /pay/status", FakeResponse.status(503), Fixtures.response("status-processing") { it.getJSONObject("transaction").put("status", "pending") })
        val (patient, _) = checkout(slow, PresentOptions(sessionToken = "t", locale = "en"))
        patient.start()
        runCurrent()
        patient.selectMethod("mobile_money")
        patient.setPhone("670000000")
        patient.payMobileMoney()
        // 3 tries of 60 s, then a failing poll (backed off 10 s), then `pending`.
        advanceTimeBy(185_000)
        runCurrent()
        assertEquals(CheckoutState.PENDING, patient.state)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("Connection error. Check your network.", assertIs<SheetScreen.Form>(patient.screen.value).banner)

        // It did arrive (the watch saw it processing) and then failed: Try again is a new attempt, with a new key.
        val arrived = cmApi()
            .on("POST /pay/process", FakeResponse.timeout())
            .on(
                "GET /pay/status",
                Fixtures.response("status-processing"),
                Fixtures.response("status-pending-attempt-refused"),
            )
        val (retrying, _) = checkout(arrived, PresentOptions(sessionToken = "t", locale = "en"))
        retrying.start()
        runCurrent()
        retrying.selectMethod("mobile_money")
        retrying.setPhone("670000000")
        retrying.payMobileMoney()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(CheckoutState.FAILED, retrying.state)
        val firstKey = arrived.requests("POST /pay/process").first().headers.getValue("Idempotency-Key")
        retrying.tryAgain()
        retrying.payMobileMoney()
        runCurrent()
        assertNotEquals(firstKey, arrived.requests("POST /pay/process").last().headers["Idempotency-Key"])

        // A 502/503/504 after the retries, or a 500, is no answer too: the same 60 s rule. A
        // refusal an earlier try left on the payment (its error_code) is not news.
        for (answer in listOf(FakeResponse.status(503), Fixtures.response("error-500-production"))) {
            val failing = cmApi()
                .on("POST /pay/process", Fixtures.response("process-failed-402"), answer)
                .on("GET /pay/status", Fixtures.response("status-pending-attempt-refused"))
            val (sheet, _) = checkout(failing, PresentOptions(sessionToken = "t", locale = "en"))
            sheet.start()
            runCurrent()
            sheet.selectMethod("mobile_money")
            sheet.setPhone("670000000")
            sheet.payMobileMoney()
            runCurrent()
            assertEquals(CheckoutState.FAILED, sheet.state, "the first try is declined (insufficient_funds)")
            sheet.tryAgain()
            sheet.payMobileMoney()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(CheckoutState.PENDING, sheet.state)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("Connection error. Check your network.", assertIs<SheetScreen.Form>(sheet.screen.value).banner)
            val keys = failing.requests("POST /pay/process").drop(1).map { it.headers["Idempotency-Key"] }.toSet()
            assertEquals(1, keys.size, "the retries of the second press share its key")
            sheet.payMobileMoney()
            runCurrent()
            assertEquals(keys.single(), failing.requests("POST /pay/process").last().headers["Idempotency-Key"])
        }
    }

    @Test
    fun `TR-10 defaults to the production api`() = runBlocking {
        assertEquals("https://api.wajub.com", API_URL)
        val captured = mutableListOf<Captured>()
        WajubSession("test_tok", PayClient(okHttp(captured, ::fixtureFor))).loadSession()
        val url = captured.single().request.url
        assertEquals("https", url.scheme)
        assertEquals("api.wajub.com", url.host)
        assertEquals("/pay/session", url.encodedPath)
        assertEquals("https://api.wajub.com", PayClient().baseUrl)
    }
}
