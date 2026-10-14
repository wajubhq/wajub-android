package com.wajub.mobile

import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubErrorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WajubErrorTest {
    @Test
    fun `an ended session (410) is session_terminal_expired, which the payer cannot retry`() {
        val error = WajubError.fromHttp(410, "Session expired", "session_expired")
        assertEquals("session_terminal_expired", error.code)
        assertEquals(WajubErrorType.InvalidRequestError, error.type)
        assertFalse(error.retryable)
    }

    @Test
    fun `a token that is not a session (401, 404) is an authentication error the payer cannot retry`() {
        for (status in listOf(401, 404)) {
            val error = WajubError.fromHttp(status, "Payment session not found", "session_not_found")
            assertEquals(WajubErrorType.AuthenticationError, error.type, "status $status")
            assertFalse(error.retryable, "status $status")
        }
    }

    @Test
    fun `a server error is Wajub side, not a declined payment`() {
        val error = WajubError.fromHttp(503, "Unavailable", "service_unavailable")
        assertEquals(WajubErrorType.ApiError, error.type)
        assertTrue(error.retryable)
        assertNull(error.declineCode)
    }
}
