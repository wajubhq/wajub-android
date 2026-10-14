package com.wajub.mobile.contract

import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubErrorType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Code written against 1.2.1 keeps compiling and gets what it got then. */
class CompatibilityTest {
    @Test
    fun `the 1_x calls throw the 1_x errors`() = runTest {
        val gone = FakeApi().on("GET /pay/session", Fixtures.response("error-410-session-expired"))
        val error = assertFailsWith<WajubError> { fakeSession(gone).loadSession() }
        assertEquals("session_expired", error.code, "the API's code, as in 1.x")
        assertEquals(WajubErrorType.AuthenticationError, error.type)
        assertEquals("This payment session has expired.", error.message, "the API's message, as in 1.x")
        assertFalse(error.retryable)

        val declined = cmApi().on("POST /pay/process", Fixtures.response("process-failed-402"))
        val decline = assertFailsWith<WajubError> {
            @Suppress("DEPRECATION")
            fakeSession(declined).process("cm.mobile", mapOf("phone" to "+237677123458"))
        }
        assertEquals("insufficient_funds", decline.declineCode)
        assertEquals(WajubErrorType.PaymentError, decline.type)

        // No answer at all: an IOException, as OkHttp threw in 1.x.
        val offline = FakeApi().on("GET /pay/session", FakeResponse.network())
        assertFailsWith<IOException> { fakeSession(offline).loadSession() }
    }

    @Test
    fun `the 1_x watchers report an ended payment with the 1_x error`() = runTest {
        val api = FakeApi()
            .on("GET /pay/session", Fixtures.response("session-processing-mobile-money"))
            .on("GET /pay/status", Fixtures.response("status-processing"), FakeResponse.status(200, """{"transaction":{"status":"failed"}}"""))
        @Suppress("DEPRECATION")
        val results = fakeSession(api).watchStatusPolling(intervalMs = 50).toList()
        assertIs<PaymentResult.Processing>(results.first())
        val failed = assertIs<PaymentResult.Failed>(results.last())
        assertEquals("failed", failed.error.code)
        assertEquals(WajubErrorType.PaymentError, failed.error.type)
        assertEquals("failed", failed.transaction?.status)
    }
}
