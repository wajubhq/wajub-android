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
    fun `an ended session (410) is an authentication error the payer cannot retry`() {
        val error = WajubError.fromHttp(410, "Session expired", "session_expired")
        assertEquals(WajubErrorType.AuthenticationError, error.type)
        assertFalse(error.retryable)
    }

    @Test
    fun `a server error is Wajub side, not a declined payment`() {
        val error = WajubError.fromHttp(503, "Unavailable", "service_unavailable")
        assertEquals(WajubErrorType.ApiError, error.type)
        assertTrue(error.retryable)
        assertNull(error.declineCode)
    }
}
