package com.wajub.mobile

/** Fixed Wajub API base URL — not configurable. */
const val API_URL: String = "https://api.wajub.com"

/**
 * Entry point for the Wajub Android mobile SDK.
 *
 * ```kotlin
 * val session = WajubMobile.createSession(authorizationToken)
 * val sheet = PaymentSheet(session)
 * sheet.present(activity) { result -> ... }
 * ```
 */
object WajubMobile {
    /** Creates a session-bound client from the backend `authorization_token`. */
    fun createSession(authorizationToken: String): WajubSession =
        WajubSession(authorizationToken.trim())
}
