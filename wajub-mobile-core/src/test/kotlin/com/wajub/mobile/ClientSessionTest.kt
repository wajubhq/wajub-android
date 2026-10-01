package com.wajub.mobile

import com.wajub.mobile.client.PayClient
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.mapper.RawProcessResponse
import com.wajub.mobile.model.ActionKind
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.UNSUPPORTED_ACTION_CODE
import com.wajub.mobile.model.WajubError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val TXN = """{"id":"trx","reference":"trx","amount":1000,"currency":"NGN","status":"processing"}"""

class ClientSessionMapperTest {
    @Test
    fun mapsClientSessionActionWithItsSession() {
        val raw = RawProcessResponse.fromJson(
            JSONObject(
                """
                {"code":202,"status":"Accepted","action":"client_session","transaction":$TXN,
                 "client_session":{"id":"prc_1","provider":"paystack","reference":"prc-1","amount":100000,
                   "currency":"NGN","hosted_url":"https://checkout.paystack.com/abc","access_code":"abc",
                   "public_key":"pk_test"}}
                """.trimIndent(),
            ),
        )

        val result = PaymentMapper.mapProcessResponse(raw, "card")

        assertIs<PaymentResult.RequiresAction>(result)
        assertEquals(ActionKind.ClientSession, result.action)
        assertEquals("https://checkout.paystack.com/abc", result.actionUrl)
        val session = result.clientSession!!
        assertEquals("prc_1", session.id)
        assertEquals("paystack", session.provider)
        assertEquals("abc", session.accessCode)
        assertEquals("pk_test", session.publicKey)
        assertEquals(100000.0, session.amount)
    }

    @Test
    fun failsExplicitlyOnAnActionTheSdkCannotPerform() {
        for (action in listOf("confirm_otp", "confirm_pin", "card_reauth")) {
            val raw = RawProcessResponse.fromJson(
                JSONObject("""{"code":202,"status":"Accepted","action":"$action","transaction":$TXN}"""),
            )

            val result = PaymentMapper.mapProcessResponse(raw, "card")

            assertIs<PaymentResult.Failed>(result)
            assertEquals(UNSUPPORTED_ACTION_CODE, result.error.code)
            assertTrue(result.error.message.contains(action))
        }
    }

    @Test
    fun doesNotTurnAnUnsupportedMobileMoneyActionIntoAPushApproval() {
        val raw = RawProcessResponse.fromJson(
            JSONObject("""{"code":202,"status":"Accepted","action":"confirm_otp","transaction":$TXN}"""),
        )

        val result = PaymentMapper.mapProcessResponse(raw, "mobile_money")

        assertIs<PaymentResult.Failed>(result)
        assertEquals(UNSUPPORTED_ACTION_CODE, result.error.code)
    }

    @Test
    fun failsOnAClientSessionActionMissingItsSession() {
        val raw = RawProcessResponse.fromJson(
            JSONObject("""{"code":202,"status":"Accepted","action":"client_session","transaction":$TXN}"""),
        )

        assertIs<PaymentResult.Failed>(PaymentMapper.mapProcessResponse(raw, "card"))
    }
}

class ClientSessionPayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PayClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        // PayClient targets the fixed API_URL — point it at the mock server instead.
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(request.newBuilder().url(url).build())
            }
            .build()
        client = PayClient(http)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun startsAClientSessionSendingOnlyTheProvidedFields() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(202).setBody(
                """{"code":202,"status":"Accepted","action":"client_session","transaction":$TXN,
                   "client_session":{"id":"prc_1","provider":"flutterwave","hosted_url":"https://l"}}""",
            ),
        )

        val raw = client.startClientSession("tok", "card", email = "payer@example.test")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/pay/client-session", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("card", body.getString("channel"))
        assertEquals("payer@example.test", body.getString("email"))
        assertFalse(body.has("name"))
        assertFalse(body.has("return_url"))
        assertFalse(body.has("restart"))
        assertEquals("prc_1", raw.clientSession?.id)
    }

    @Test
    fun sendsRestartOnlyWhenAsked() = runTest {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"code":202,"status":"Accepted","transaction":$TXN}"""))

        client.startClientSession("tok", "card", restart = true)

        assertTrue(JSONObject(server.takeRequest().body.readUtf8()).getBoolean("restart"))
    }

    @Test
    fun completesByClientSessionIdOnly() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":200,"status":"OK","transaction":$TXN}"""))

        client.completeClientSession("tok", "prc_1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/pay/client-session/complete", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("prc_1", body.getString("client_session_id"))
        assertEquals(1, body.length())
    }

    @Test
    fun throwsTheBackendErrorCodeWhenCompletionFails() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(402).setBody(
                """{"code":402,"status":"Payment Required","message":"Declined","error_code":"failed"}""",
            ),
        )

        val error = assertFailsWith<WajubError> { client.completeClientSession("tok", "prc_1") }

        assertEquals("failed", error.code)
        assertEquals(402, error.httpStatus)
    }

    @Test
    fun readsTheClientSessionFlagFromSdkConfig() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"channels":{
                     "card":{"available":true,"provider":"paystack","sdk":"form","publishable_key":"pk","required_fields":[],"client_session":true},
                     "cm.mtn":{"available":true,"provider":"system","sdk":"form","publishable_key":null,"required_fields":["phone"]}}}""",
            ),
        )

        val config = client.getSdkConfig("tok")

        assertTrue(config.channels.getValue("card").clientSession)
        assertFalse(config.channels.getValue("cm.mtn").clientSession)
    }
}
