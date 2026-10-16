package com.wajub.mobile.client

import com.wajub.mobile.API_URL
import com.wajub.mobile.WajubMobile
import com.wajub.mobile.device.DeviceEnvironment
import com.wajub.mobile.mapper.RawProcessResponse
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.mapper.bool
import com.wajub.mobile.mapper.obj
import com.wajub.mobile.mapper.parseObject
import com.wajub.mobile.mapper.str
import com.wajub.mobile.mapper.strings
import com.wajub.mobile.model.CancelResult
import com.wajub.mobile.model.SdkChannelConfig
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubLanguage
import kotlinx.coroutines.delay
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/** The automatic retries of one call (spec §2.5). */
internal enum class Retry(val max: Int) {
    /** GETs: network, timeout, 408, 429 (Retry-After ≤ 10 s), 500, 502, 503, 504. */
    Reads(3),

    /** Charges: network, timeout, 502, 503, 504 — the same body and Idempotency-Key. */
    Payments(2),

    /** `POST /pay/cancel`: network and timeout only. */
    Cancel(2),

    Never(0),
}

/** One `/pay` endpoint: its method, path, whole-request timeout and retries. */
internal class ApiCall(val method: String, val path: String, val timeoutMs: Long, val retry: Retry) {
    companion object {
        private const val SHORT = 30_000L
        private const val LONG = 60_000L

        val Session = ApiCall("GET", "/pay/session", SHORT, Retry.Reads)
        val SdkConfig = ApiCall("GET", "/pay/sdk-config", SHORT, Retry.Reads)
        val Status = ApiCall("GET", "/pay/status", SHORT, Retry.Reads)

        /** A watch's polls: its own schedule backs off on errors (spec §13.2). */
        val StatusPoll = ApiCall("GET", "/pay/status", SHORT, Retry.Never)
        val Process = ApiCall("POST", "/pay/process", LONG, Retry.Payments)
        val ClientSession = ApiCall("POST", "/pay/client-session", LONG, Retry.Payments)
        val ClientSessionComplete = ApiCall("POST", "/pay/client-session/complete", LONG, Retry.Payments)
        val Cancel = ApiCall("POST", "/pay/cancel", SHORT, Retry.Cancel)
        val ThreeDsVerify = ApiCall("POST", "/pay/3ds/verify", LONG, Retry.Never)
        val ThreeDsSend = ApiCall("POST", "/pay/3ds/send", SHORT, Retry.Never)
        val BroadcastingAuth = ApiCall("POST", "/pay/broadcasting/auth", SHORT, Retry.Never)

        /** `kind` is `otp`, `pin`, `phone`, `birthday` or `address`. */
        fun verify(kind: String) = ApiCall("POST", "/pay/$kind/verify", LONG, Retry.Never)

        fun resend(kind: String) = ApiCall("POST", "/pay/$kind/resend", SHORT, Retry.Never)
    }
}

/** An answer of the API, whatever its status. */
internal class ApiResponse(
    val status: Int,
    val body: JSONObject,
    /** The response's `X-Request-Id`. */
    val correlationId: String?,
    val retryAfter: String?,
    /** Clock time the request (its last try) was sent. */
    val sentAt: Long = 0L,
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * The low-level client the payment calls go through. Prefer [com.wajub.mobile.WajubSession].
 */
class PayClient internal constructor(
    private val transport: Transport,
    internal val baseUrl: String = API_URL,
    private val environment: () -> DeviceEnvironment = { WajubMobile.device },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Over your own [http] client (a proxy, certificate pinning, logging…), which is left untouched. */
    @JvmOverloads
    constructor(http: OkHttpClient = defaultHttp()) : this(OkHttpTransport(http))

    /** The language of the errors this client builds; the sheet sets it to its own. */
    @Volatile
    var language: WajubLanguage = WajubLanguage.device()

    // The 1.x calls below throw what they threw in 1.x: the 1.x WajubError for
    // an answer that is not a 2xx, an IOException when none came back.

    suspend fun getSession(token: String): SessionData =
        RawSessionResponse.fromJson(legacySuccess(legacySend(token, ApiCall.Session))).toModel()

    suspend fun getSdkConfig(token: String): SdkConfig = parseSdkConfig(legacySuccess(legacySend(token, ApiCall.SdkConfig)))

    /** `GET /pay/session`, its errors mapped as the spec says (load errors). */
    internal suspend fun fetchSession(token: String): SessionData =
        RawSessionResponse.fromJson(expectSuccess(send(token, ApiCall.Session), loading = true)).toModel()

    /** Pays through [channel] with [data] as given. Throws [WajubError] when the payment is refused. */
    suspend fun process(token: String, channel: String, data: Map<String, Any?>): RawProcessResponse {
        val payload = JSONObject().put("channel", channel).put("data", mapToJson(data))
        val response = legacySend(token, ApiCall.Process, payload, idempotencyKey = newIdempotencyKey())
        return RawProcessResponse.fromJson(legacySuccess(response))
    }

    /** `{channel, data}` with `data._client` (spec §2.3) unless the caller set it. */
    internal fun processPayload(channel: String, data: Map<String, Any?>): JSONObject {
        val withSignals = LinkedHashMap(data)
        if ("_client" !in withSignals) withSignals["_client"] = environment().clientSignals()
        return JSONObject().put("channel", channel).put("data", mapToJson(withSignals))
    }

    /**
     * Starts a card payment on the payment provider's page for [channel]. Calling
     * it again returns the same page unless [restart] is true.
     */
    suspend fun startClientSession(
        token: String,
        channel: String,
        email: String? = null,
        name: String? = null,
        returnUrl: String? = null,
        restart: Boolean = false,
    ): RawProcessResponse {
        val payload = clientSessionPayload(channel, email, name, returnUrl, restart, emptyMap())
        return RawProcessResponse.fromJson(legacySuccess(legacySend(token, ApiCall.ClientSession, payload)))
    }

    /**
     * Once the payer is back from the provider's page: paid, still pending, or
     * a decline (thrown as [WajubError]).
     */
    suspend fun completeClientSession(token: String, clientSessionId: String): RawProcessResponse {
        val payload = JSONObject().put("client_session_id", clientSessionId)
        return RawProcessResponse.fromJson(legacySuccess(legacySend(token, ApiCall.ClientSessionComplete, payload)))
    }

    /**
     * Cancels the payment. A payment being paid, or partly paid, is not cancelled:
     * a [WajubError] `payment_in_progress` or `payment_partly_paid` says so.
     */
    suspend fun cancel(token: String): CancelResult {
        val response = legacySend(token, ApiCall.Cancel, JSONObject())
        if (!response.isSuccess) {
            WajubError.cancelRefusal(response.status, response.body, response.correlationId, language)?.let { throw it }
        }
        return CancelResult(redirectUrl = legacySuccess(response).str("redirect_url"))
    }

    internal fun clientSessionPayload(
        channel: String,
        email: String?,
        name: String?,
        returnUrl: String?,
        restart: Boolean,
        fields: Map<String, Any?>,
    ): JSONObject {
        val payload = JSONObject().put("channel", channel)
        email?.takeIf { it.isNotBlank() }?.let { payload.put("email", it) }
        name?.takeIf { it.isNotBlank() }?.let { payload.put("name", it) }
        returnUrl?.takeIf { it.isNotBlank() }?.let { payload.put("return_url", it) }
        if (restart) payload.put("restart", true)
        fields.forEach { (key, value) -> if (value != null && !payload.has(key)) payload.put(key, toJsonValue(value)) }
        return payload
    }

    internal fun newIdempotencyKey(): String = UUID.randomUUID().toString()

    internal fun clientHeader(): String = environment().clientHeader

    internal fun warmUp() = environment().prewarm()

    /** The body of a 2xx, or the [WajubError] of any other answer. */
    // 1.x threw OkHttp's own IOException (a SocketTimeoutException…) when no answer came back.
    private suspend fun legacySend(token: String, call: ApiCall, body: JSONObject? = null, idempotencyKey: String? = null): ApiResponse =
        try {
            sendRaw(token, call, body, idempotencyKey)
        } catch (failure: TransportException) {
            throw (failure.cause as? java.io.IOException) ?: failure
        }

    private fun legacySuccess(response: ApiResponse): JSONObject {
        if (!response.isSuccess) throw WajubError.legacy(response.status, response.body, response.correlationId, response.retryAfter)
        return response.body
    }

    internal fun expectSuccess(response: ApiResponse, loading: Boolean = false): JSONObject {
        if (!response.isSuccess) throw errorOf(response, loading)
        return response.body
    }

    internal fun errorOf(response: ApiResponse, loading: Boolean): WajubError =
        WajubError.fromResponse(response.status, response.body, response.correlationId, response.retryAfter, language, loading)

    /** A transport failure (no answer) as the error the payer sees. */
    internal fun errorOf(failure: TransportException): WajubError =
        WajubError.of(if (failure.timedOut) "timeout" else "network_error", language, apiMessage = failure.message)

    /**
     * Sends [call] with its retries. Answers with the last response whatever its
     * status; throws [WajubError] (`network_error`, `timeout`) when no answer
     * came back after the retries.
     */
    internal suspend fun send(
        token: String,
        call: ApiCall,
        body: JSONObject? = null,
        idempotencyKey: String? = null,
    ): ApiResponse =
        try {
            sendRaw(token, call, body, idempotencyKey)
        } catch (failure: TransportException) {
            throw errorOf(failure)
        }

    /** As [send], but a missing answer is a [TransportException] (the caller decides). */
    internal suspend fun sendRaw(
        token: String,
        call: ApiCall,
        body: JSONObject? = null,
        idempotencyKey: String? = null,
    ): ApiResponse {
        val text = body?.toString() ?: if (call.method == "POST") "{}" else null
        var attempt = 0
        var sentAt: Long
        while (true) {
            val request = HttpRequest(
                method = call.method,
                url = baseUrl.trimEnd('/') + call.path,
                headers = headers(token, idempotencyKey),
                body = text,
                timeoutMs = call.timeoutMs,
            )
            sentAt = clock()
            val outcome: Any = try {
                transport.execute(request)
            } catch (failure: TransportException) {
                failure
            }
            val wait = if (attempt < call.retry.max) retryDelay(call.retry, attempt, outcome) else null
            if (wait == null) {
                return when (outcome) {
                    is HttpResponse -> ApiResponse(
                        status = outcome.status,
                        body = parseObject(outcome.body),
                        correlationId = outcome.header("X-Request-Id") ?: outcome.header("X-Trace-Id"),
                        retryAfter = outcome.header("Retry-After"),
                        sentAt = sentAt,
                    )
                    else -> throw (outcome as TransportException).also { it.sentAt = sentAt }
                }
            }
            pause(wait)
            attempt++
        }
    }

    private fun headers(token: String, idempotencyKey: String?): Map<String, String> {
        val device = environment()
        val client = device.clientHeader
        return buildMap {
            // AuthenticatePaymentSession reads the token with Laravel's
            // bearerToken(): the literal "Bearer " prefix is required.
            put("Authorization", "Bearer $token")
            put("Accept", "application/json")
            put("Accept-Language", device.acceptLanguage())
            put("X-Request-Id", UUID.randomUUID().toString())
            put("X-Wajub-Client", client)
            put("User-Agent", "okhttp/${OkHttp.VERSION} $client")
            idempotencyKey?.let { put("Idempotency-Key", it) }
        }
    }

    // null: do not retry.
    private fun retryDelay(retry: Retry, attempt: Int, outcome: Any): Long? {
        if (retry == Retry.Never) return null
        if (outcome is TransportException) return backoff(attempt)
        val status = (outcome as HttpResponse).status
        return when (retry) {
            Retry.Reads -> when (status) {
                408, 500, 502, 503, 504 -> backoff(attempt)
                429 -> {
                    val seconds = outcome.header("Retry-After")?.trim()?.toLongOrNull()
                    when {
                        seconds == null -> backoff(attempt)
                        seconds * 1000 <= MAX_RETRY_AFTER_MS -> seconds * 1000
                        else -> null
                    }
                }
                else -> null
            }
            Retry.Payments -> if (status in setOf(502, 503, 504)) backoff(attempt) else null
            else -> null
        }
    }

    // min(1 s × 2^n, 10 s) ± 30 %.
    private fun backoff(attempt: Int): Long {
        val base = min(BASE_DELAY_MS shl attempt, MAX_DELAY_MS).toDouble()
        val jitter = 1.0 + (random.nextDouble() * 2 - 1) * JITTER
        return (base * jitter).toLong()
    }

    private fun parseSdkConfig(body: JSONObject): SdkConfig {
        val channelsJson = body.obj("channels") ?: JSONObject()
        val channels = linkedMapOf<String, SdkChannelConfig>()
        channelsJson.keys().forEach { slug ->
            val ch = channelsJson.obj(slug) ?: return@forEach
            channels[slug] = SdkChannelConfig(
                available = ch.bool("available") ?: false,
                provider = ch.str("provider"),
                sdk = ch.str("sdk"),
                publishableKey = ch.str("publishable_key"),
                requiredFields = ch.strings("required_fields"),
                // Absent in sandbox and on unavailable entries: false.
                clientSession = ch.bool("client_session") ?: false,
            )
        }
        return SdkConfig(channels)
    }

    private fun mapToJson(data: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        data.forEach { (k, v) -> obj.put(k, toJsonValue(v)) }
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    private fun toJsonValue(v: Any?): Any =
        when (v) {
            null -> JSONObject.NULL
            is Map<*, *> -> mapToJson(v as Map<String, Any?>)
            is List<*> -> JSONArray().also { array -> v.forEach { array.put(toJsonValue(it)) } }
            else -> v
        }

    companion object {
        private const val BASE_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 10_000L
        private const val MAX_RETRY_AFTER_MS = 10_000L
        private const val JITTER = 0.3

        /** A default HTTP client. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder().build()
    }
}
