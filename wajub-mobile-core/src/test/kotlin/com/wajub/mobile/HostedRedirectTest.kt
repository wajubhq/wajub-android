package com.wajub.mobile

import com.wajub.mobile.client.PayClient
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.model.PaymentResult
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
import kotlin.test.assertIs
import kotlin.test.assertNull

class HostedCardFieldsTest {
    @Test
    fun validatesRequiredEmailAndTwoLetterCountry() {
        assertEquals("Required", PaymentMapper.hostedCardFieldError("city", "  "))
        assertEquals("Invalid email", PaymentMapper.hostedCardFieldError("email", "nope"))
        assertNull(PaymentMapper.hostedCardFieldError("email", "payer@example.test"))
        assertEquals("Use the 2-letter country code", PaymentMapper.hostedCardFieldError("country", "CIV"))
        assertNull(PaymentMapper.hostedCardFieldError("country", "ci"))
    }

    @Test
    fun buildsFlatDataTrimmingUppercasingCountryAndDroppingBlanksAndUnknownKeys() {
        assertEquals(
            mapOf("email" to "payer@example.test", "country" to "CI"),
            PaymentMapper.buildHostedCardRequest(
                mapOf("email" to " payer@example.test ", "country" to "ci", "city" to "", "card.number" to "4242"),
            ),
        )
    }
}

class PayCardHostedRedirectTest {
    private lateinit var server: MockWebServer
    private lateinit var session: WajubSession

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val txn = """{"id":"trx","reference":"trx","amount":1000,"currency":"XOF","status":"processing"}"""
                return if (request.path == "/pay/session") {
                    MockResponse().setResponseCode(200).setBody(
                        """{"transaction":$txn,"channels":[{"id":"c","slug":"card","name":"Card","type":"card","countries":[],"currency":"XOF"}]}""",
                    )
                } else {
                    MockResponse().setResponseCode(202).setBody(
                        """{"code":202,"status":"Accepted","action":"redirect","confirm_url":"https://checkout.cinetpay.com/payment/abc","transaction":$txn}""",
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
    fun sendsTheBillingFieldsFlatAndMapsThePspRedirect() = runTest {
        val result = session.payCardHostedRedirect("card", mapOf("email" to "payer@example.test", "zip_code" to "00225"))

        val requests = List(server.requestCount) { server.takeRequest() }
        val process = requests.single { it.path == "/pay/process" }
        val body = JSONObject(process.body.readUtf8())
        assertEquals("card", body.getString("channel"))
        val data = body.getJSONObject("data")
        assertEquals("payer@example.test", data.getString("email"))
        assertEquals("00225", data.getString("zip_code"))
        assertEquals(setOf("email", "zip_code", "_client"), data.keys().asSequence().toSet())
        assertIs<PaymentResult.RequiresAction>(result)
        assertEquals("https://checkout.cinetpay.com/payment/abc", result.actionUrl)
    }
}
