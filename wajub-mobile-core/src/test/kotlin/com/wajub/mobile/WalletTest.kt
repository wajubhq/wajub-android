package com.wajub.mobile

import com.wajub.mobile.client.PayClient
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.SdkChannelConfig
import com.wajub.mobile.model.SessionChannel
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val djamo = SessionChannel(id = "d", slug = "ci.djamo", name = "Djamo CI", type = "wallet", currency = "XOF")
private val formConfig = SdkChannelConfig(available = true, provider = "djamo", sdk = "form")

class RedirectWalletChannelTest {
    @Test
    fun acceptsAFormWalletWithNothingToCollectAndNothingElse() {
        assertTrue(PaymentMapper.isRedirectWalletChannel(djamo, formConfig))
        assertFalse(PaymentMapper.isRedirectWalletChannel(djamo, formConfig.copy(sdk = "stripe_elements")))
        assertFalse(PaymentMapper.isRedirectWalletChannel(djamo, formConfig.copy(requiredFields = listOf("phone"))))
        assertFalse(PaymentMapper.isRedirectWalletChannel(djamo, formConfig.copy(available = false)))
        assertFalse(PaymentMapper.isRedirectWalletChannel(djamo.copy(type = "mobile_money"), formConfig))
        assertFalse(PaymentMapper.isRedirectWalletChannel(djamo, null))
    }
}

class PayWalletTest {
    private lateinit var server: MockWebServer
    private lateinit var session: WajubSession

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val txn = """{"id":"trx","reference":"trx","amount":10000,"currency":"XOF","status":"processing"}"""
                return if (request.path == "/pay/session") {
                    MockResponse().setResponseCode(200).setBody(
                        """{"transaction":$txn,"channels":[{"id":"d","slug":"ci.djamo","name":"Djamo CI","type":"wallet","countries":["CI"],"currency":"XOF"}]}""",
                    )
                } else {
                    MockResponse().setResponseCode(202).setBody(
                        """{"code":202,"status":"Accepted","action":"redirect","confirm_url":"https://djamo.ci/?type=payment_confirmation","transaction":$txn}""",
                    )
                }
            }
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
    fun submitsTheChannelWithEmptyDataAndMapsTheWalletRedirect() = runTest {
        val result = session.payWallet("ci.djamo")

        val requests = List(server.requestCount) { server.takeRequest() }
        val body = JSONObject(requests.single { it.path == "/pay/process" }.body.readUtf8())
        assertEquals("ci.djamo", body.getString("channel"))
        assertEquals(0, body.getJSONObject("data").length())
        assertIs<PaymentResult.RequiresAction>(result)
        assertEquals("https://djamo.ci/?type=payment_confirmation", result.actionUrl)
    }
}
