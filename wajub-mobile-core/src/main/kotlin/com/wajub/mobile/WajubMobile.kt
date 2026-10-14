package com.wajub.mobile

import com.wajub.mobile.client.PayClient
import com.wajub.mobile.device.DeviceEnvironment
import okhttp3.OkHttpClient

/** Fixed Wajub API base URL — not configurable. */
const val API_URL: String = "https://api.wajub.com"

/**
 * Entry point for the Wajub Android mobile SDK.
 *
 * ```kotlin
 * PaymentSheet(PresentOptions(sessionToken = authorizationToken)) { result -> … }
 * ```
 *
 * or, to draw your own UI, `WajubMobile.createSession(authorizationToken)`.
 */
object WajubMobile {
    /**
     * What the SDK says about the device (headers, `_client` signals, install
     * fingerprint). `wajub-mobile-compose` sets it from the app's context at
     * start-up; an app using only `wajub-mobile-core` can set it itself.
     */
    @Volatile
    var device: DeviceEnvironment = DeviceEnvironment()

    /** Creates a session-bound client from the backend `authorization_token`. */
    fun createSession(authorizationToken: String): WajubSession =
        WajubSession(authorizationToken.trim())

    /** The same, over your own [OkHttpClient] (a proxy, certificate pinning, logging…). */
    fun createSession(authorizationToken: String, httpClient: OkHttpClient): WajubSession =
        WajubSession(authorizationToken.trim(), PayClient(httpClient))
}
