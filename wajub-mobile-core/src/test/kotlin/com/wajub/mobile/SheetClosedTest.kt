package com.wajub.mobile

import com.wajub.mobile.client.PayClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A payment sheet closed before the payer submitted anything left the payment
 * open on Wajub, where the hosted checkout and the Flutter SDK cancel it.
 */
class SheetClosedTest {
    private lateinit var server: MockWebServer
    private lateinit var session: WajubSession
    private val status = AtomicInteger(200)

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(status.get()).setBody("""{"code":${status.get()},"status":"OK","message":"Cancelled"}""")
        }
        server.start()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(request.newBuilder().url(url).build())
            }
            .build()
        session = WajubSession("tok", PayClient(http))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `closed before anything was submitted, the payment is cancelled on Wajub`() = runTest {
        session.cancelIfNothingSubmitted(submitted = false, scope = this)?.join()

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/pay/cancel", request.path)
    }

    @Test
    fun `closed with an attempt under way, the payment is left alone`() = runTest {
        assertNull(session.cancelIfNothingSubmitted(submitted = true, scope = this))

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a cancel that fails is not the app's problem`() = runTest {
        status.set(500)

        session.cancelIfNothingSubmitted(submitted = false, scope = this)?.join()

        assertEquals(1, server.requestCount)
    }
}
