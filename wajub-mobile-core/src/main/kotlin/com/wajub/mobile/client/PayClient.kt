package com.wajub.mobile.client

import com.wajub.mobile.API_URL
import com.wajub.mobile.model.CancelResult
import com.wajub.mobile.model.EchoConfig
import com.wajub.mobile.model.ProcessingContext
import com.wajub.mobile.model.SdkChannelConfig
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionChannel
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.mapper.RawProcessResponse
import com.wajub.mobile.mapper.RawSessionResponse
import com.wajub.mobile.mapper.RawTransaction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class PayClient(
    private val http: OkHttpClient = defaultHttp(),
) {
    suspend fun getSession(token: String): SessionData =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getSessionBlocking(token)
        }

    suspend fun getSdkConfig(token: String): SdkConfig =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            getSdkConfigBlocking(token)
        }

    suspend fun process(token: String, channel: String, data: Map<String, Any?>): RawProcessResponse =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            processBlocking(token, channel, data)
        }

    suspend fun cancel(token: String): CancelResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cancelBlocking(token)
        }

    private fun getSessionBlocking(token: String): SessionData {
        val body = request("GET", token, "/pay/session")
        return mapSession(body)
    }

    private fun getSdkConfigBlocking(token: String): SdkConfig {
        val body = request("GET", token, "/pay/sdk-config")
        return mapSdkConfig(body)
    }

    private fun processBlocking(token: String, channel: String, data: Map<String, Any?>): RawProcessResponse {
        val payload = JSONObject()
            .put("channel", channel)
            .put("data", mapToJson(data))
        val body = request(
            method = "POST",
            token = token,
            path = "/pay/process",
            jsonBody = payload,
            extraHeaders = mapOf("Idempotency-Key" to idempotencyKey()),
        )
        return mapProcessBody(body)
    }

    private fun cancelBlocking(token: String): CancelResult {
        val body = request("POST", token, "/pay/cancel", JSONObject())
        return CancelResult(redirectUrl = body.optString("redirect_url").ifBlank { null })
    }

    private fun request(
        method: String,
        token: String,
        path: String,
        jsonBody: JSONObject? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val url = API_URL.trimEnd('/') + path
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            // AuthenticatePaymentSession (api.wajub) reads this via Laravel's
            // Request::bearerToken(), which requires the literal "Bearer " prefix —
            // without it every /pay/* call 401s with "Payment session token required."
            .header("Authorization", "Bearer $token")

        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        when (method) {
            "GET" -> builder.get()
            "POST" -> {
                val media = "application/json; charset=utf-8".toMediaType()
                val bodyString = jsonBody?.toString() ?: "{}"
                builder.post(bodyString.toRequestBody(media))
            }
            else -> error("Unsupported method: $method")
        }

        http.newCall(builder.build()).execute().use { response ->
            val correlationId = response.header("X-Request-Id") ?: response.header("X-Trace-Id")
            val text = response.body?.string().orEmpty()
            val json = if (text.isNotBlank()) JSONObject(text) else JSONObject()
            if (!response.isSuccessful) {
                throw mapError(response.code, json, correlationId, response.header("Retry-After"))
            }
            return json
        }
    }

    private fun mapError(code: Int, body: JSONObject, correlationId: String?, retryAfter: String?): WajubError {
        val message = body.optString("message").ifBlank { "Request failed ($code)" }
        val errorCode = when {
            body.has("error_code") -> body.getString("error_code")
            code == 402 -> "insufficient_funds"
            code == 403 -> "blocked"
            code == 410 -> "session_terminal_expired"
            code == 422 -> "validation_error"
            code == 429 -> "rate_limited"
            code == 401 || code == 404 -> "session_not_found"
            code >= 500 -> "server_error"
            else -> "payment_error"
        }
        val details = flattenErrors(body.opt("errors"))
        val retrySeconds = retryAfter?.toIntOrNull()
        return WajubError.fromHttp(code, message, errorCode, details, correlationId, retrySeconds)
    }

    private fun flattenErrors(raw: Any?): Map<String, String> {
        if (raw !is JSONObject) return emptyMap()
        val out = linkedMapOf<String, String>()
        raw.keys().forEach { key ->
            val value = raw.get(key)
            out[key] = when (value) {
                is JSONArray -> if (value.length() > 0) value.getString(0) else ""
                else -> value.toString()
            }
        }
        return out
    }

    private fun mapSession(body: JSONObject): SessionData {
        val raw = RawSessionResponse.fromJson(body)
        return SessionData(
            transaction = mapTransaction(raw.transaction),
            channels = raw.channels.map { mapChannel(it) },
            locale = raw.locale ?: "en",
            branding = raw.branding,
            echo = raw.echo?.let {
                EchoConfig(it.key, it.wsHost, it.wsPort, it.channelPrefix, it.broadcaster)
            },
            callback = raw.callback,
        )
    }

    private fun mapSdkConfig(body: JSONObject): SdkConfig {
        val channelsJson = body.optJSONObject("channels") ?: JSONObject()
        val channels = linkedMapOf<String, SdkChannelConfig>()
        channelsJson.keys().forEach { slug ->
            val ch = channelsJson.getJSONObject(slug)
            channels[slug] = SdkChannelConfig(
                available = ch.optBoolean("available"),
                provider = ch.optString("provider").ifBlank { null },
                sdk = ch.optString("sdk").ifBlank { null },
                publishableKey = ch.optString("publishable_key").ifBlank { null },
                requiredFields = jsonStringList(ch.optJSONArray("required_fields")),
            )
        }
        return SdkConfig(channels)
    }

    private fun mapProcessBody(body: JSONObject): RawProcessResponse =
        RawProcessResponse.fromJson(body)

    private fun mapTransaction(raw: RawTransaction): SessionTransaction =
        SessionTransaction(
            id = raw.id,
            reference = raw.reference,
            trxref = raw.trxref,
            amount = raw.amount,
            amountTotal = raw.amountTotal,
            currency = raw.currency,
            status = raw.status,
            statusLabel = raw.statusLabel,
            sandbox = raw.sandbox,
            description = raw.description,
            processingContext = raw.processingContext?.let {
                ProcessingContext(
                    channelSlug = it.channelSlug,
                    isMobileMoney = it.isMobileMoney,
                    payerInstruction = it.payerInstruction,
                    actionUrl = it.actionUrl,
                )
            },
        )

    private fun mapChannel(raw: com.wajub.mobile.mapper.RawChannel): SessionChannel =
        SessionChannel(
            id = raw.id,
            slug = raw.slug,
            name = raw.name,
            nameFr = raw.nameFr,
            type = raw.type,
            logo = raw.logo,
            countries = raw.countries,
            minAmount = raw.minAmount,
            maxAmount = raw.maxAmount,
            currency = raw.currency,
        )

    private fun mapToJson(data: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        data.forEach { (k, v) ->
            when (v) {
                null -> obj.put(k, JSONObject.NULL)
                is Map<*, *> -> obj.put(k, mapToJson(v as Map<String, Any?>))
                is List<*> -> obj.put(k, JSONArray(v))
                else -> obj.put(k, v)
            }
        }
        return obj
    }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun idempotencyKey(): String = "wajub-${UUID.randomUUID()}"

    companion object {
        fun defaultHttp(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .build()
    }
}
