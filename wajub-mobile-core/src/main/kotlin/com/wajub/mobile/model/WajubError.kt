package com.wajub.mobile.model

import com.wajub.mobile.mapper.firstMessages
import com.wajub.mobile.mapper.int
import com.wajub.mobile.mapper.obj
import com.wajub.mobile.mapper.str
import org.json.JSONObject

enum class WajubErrorType(
    /** The type's name: `api_error`, `payment_error`… */
    val value: String,
) {
    ApiError("api_error"),
    AuthenticationError("authentication_error"),
    InvalidRequestError("invalid_request_error"),
    PaymentError("payment_error"),
    RateLimitError("rate_limit_error"),
}

/**
 * What went wrong. [message] is for the payer, in the sheet's language;
 * [apiMessage] is Wajub's own English text, for your logs.
 */
class WajubError(
    val type: WajubErrorType,
    override val message: String,
    /** What went wrong: `insufficient_funds`, `session_not_found`… */
    val code: String,
    /** On a decline, the reason given for it. */
    val declineCode: String? = null,
    val retryable: Boolean = false,
    /** The field at fault. */
    val param: String? = null,
    /** The request id to quote to Wajub support. */
    val correlationId: String? = null,
    val retryAfterSeconds: Int? = null,
    /** The field errors, one message per field. */
    val details: Map<String, String> = emptyMap(),
    val httpStatus: Int? = null,
    /** On `shield_blocked`: the reference to quote to support. */
    val blockReference: String? = null,
    /** Wajub's own message, in English. */
    val apiMessage: String? = null,
) : Exception(message) {
    /** The earlier constructor, kept for Java and compiled code. */
    constructor(
        type: WajubErrorType,
        message: String,
        code: String,
        declineCode: String? = null,
        retryable: Boolean = false,
        param: String? = null,
        correlationId: String? = null,
        retryAfterSeconds: Int? = null,
        details: Map<String, String> = emptyMap(),
        httpStatus: Int? = null,
    ) : this(type, message, code, declineCode, retryable, param, correlationId, retryAfterSeconds, details, httpStatus, blockReference = null)

    /** Always `WajubError`. */
    val name: String get() = "WajubError"

    /** The error as a map, with snake_case keys. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "type" to type.value,
        "code" to code,
        "message" to message,
        "decline_code" to declineCode,
        "retryable" to retryable,
        "param" to param,
        "http_status" to httpStatus,
        "correlation_id" to correlationId,
        "retry_after_seconds" to retryAfterSeconds,
        "details" to details.takeIf { it.isNotEmpty() },
        "block_reference" to blockReference,
        "api_message" to apiMessage,
    )

    /**
     * The same error with its message in [language] when its code is in the
     * catalogue; [args] fill placeholders the error does not carry (`{n}`).
     */
    fun localized(language: WajubLanguage, args: Map<String, String> = emptyMap()): WajubError {
        val text = ErrorCatalog.message(code, language, this, args) ?: return this
        return WajubError(
            type, text, code, declineCode, retryable, param, correlationId, retryAfterSeconds,
            details, httpStatus, blockReference, apiMessage,
        )
    }

    override fun toString(): String = "WajubError(code=$code, type=${type.value}, httpStatus=$httpStatus, message=$message)"

    companion object {
        fun network(message: String = "Network error"): WajubError =
            WajubError(WajubErrorType.ApiError, message, "network_error", retryable = true)

        /** An error from an HTTP status, its [message] and its [code]. */
        fun fromHttp(
            httpStatus: Int,
            message: String,
            code: String,
            details: Map<String, String> = emptyMap(),
            correlationId: String? = null,
            retryAfterSeconds: Int? = null,
        ): WajubError {
            val type = when {
                httpStatus == 401 || httpStatus == 404 || httpStatus == 410 -> WajubErrorType.AuthenticationError
                httpStatus == 422 -> WajubErrorType.InvalidRequestError
                httpStatus == 429 -> WajubErrorType.RateLimitError
                httpStatus >= 500 -> WajubErrorType.ApiError
                else -> WajubErrorType.PaymentError
            }
            return WajubError(
                type = type,
                message = message,
                code = code,
                declineCode = if (httpStatus == 402) code else null,
                retryable = httpStatus !in setOf(401, 403, 404, 410),
                param = details.keys.singleOrNull(),
                correlationId = correlationId,
                retryAfterSeconds = retryAfterSeconds,
                details = details,
                httpStatus = httpStatus,
                apiMessage = message,
            )
        }

        /** The 1.x error of an HTTP answer: its code, else one picked by status. */
        internal fun legacy(httpStatus: Int, body: JSONObject, correlationId: String?, retryAfterHeader: String?): WajubError {
            val code = body.str("error_code") ?: when {
                httpStatus == 402 -> "insufficient_funds"
                httpStatus == 403 -> "blocked"
                httpStatus == 410 -> "session_terminal_expired"
                httpStatus == 422 -> "validation_error"
                httpStatus == 429 -> "rate_limited"
                httpStatus == 401 || httpStatus == 404 -> "session_not_found"
                httpStatus >= 500 -> "server_error"
                else -> "payment_error"
            }
            return fromHttp(
                httpStatus,
                body.str("message") ?: "Request failed ($httpStatus)",
                code,
                body.firstMessages("errors"),
                correlationId,
                retryAfterHeader?.trim()?.toIntOrNull(),
            )
        }

        // errors.md §2: an HTTP answer ≥ 400 as a WajubError in [language].
        internal fun fromResponse(
            httpStatus: Int,
            body: JSONObject,
            correlationId: String?,
            retryAfterHeader: String?,
            language: WajubLanguage,
            loading: Boolean,
        ): WajubError {
            val apiCode = body.str("error_code") ?: body.obj("error")?.str("code")
            val code = when {
                httpStatus == 410 -> "session_terminal_expired"
                apiCode != null -> apiCode
                else -> codeForStatus(httpStatus, loading)
            }
            val entry = ErrorCatalog.entry(code)
            val type = entry?.type ?: typeForStatus(httpStatus)
            val retryable = entry?.retryable ?: when {
                httpStatus == 402 || httpStatus == 422 || httpStatus >= 500 -> true
                else -> false
            }
            val details = body.firstMessages("errors")
            val apiMessage = body.str("message") ?: body.obj("error")?.str("message")
            val retryAfter = body.int("retry_after") ?: retryAfterHeader?.trim()?.toIntOrNull()
            val draft = WajubError(
                type = type,
                message = "",
                code = code,
                declineCode = if (httpStatus == 402) apiCode else null,
                retryable = retryable,
                param = details.keys.singleOrNull(),
                correlationId = correlationId,
                retryAfterSeconds = if (httpStatus == 429) retryAfter else null,
                details = details,
                httpStatus = httpStatus,
                blockReference = body.str("block_reference"),
                apiMessage = apiMessage,
            )
            val text = ErrorCatalog.message(code, language, draft, attemptArgs(body))
                ?: fallbackMessage(httpStatus, language, body.str("payer_message") ?: apiMessage)
            return draft.withMessage(text)
        }

        // errors.md §3.4: the 409 of /pay/cancel for a payment being paid, or partly paid; null for any other answer.
        internal fun cancelRefusal(httpStatus: Int, body: JSONObject, correlationId: String?, language: WajubLanguage): WajubError? {
            if (httpStatus != 409) return null
            val code = body.str("error_code") ?: return null
            val entry = ErrorCatalog.cancelRefusal(code) ?: return null
            return WajubError(
                type = entry.type,
                message = if (language == WajubLanguage.Fr) entry.fr else entry.en,
                code = code,
                retryable = entry.retryable,
                correlationId = correlationId,
                httpStatus = httpStatus,
                apiMessage = body.str("message"),
            )
        }

        // A confirmation step's HTTP 200 `{status: "failed", attempts_remaining, error}`.
        internal fun fromStepFailure(body: JSONObject, language: WajubLanguage, correlationId: String? = null): WajubError {
            val code = body.obj("error")?.str("code") ?: "request_failed"
            val entry = ErrorCatalog.entry(code)
            val draft = WajubError(
                type = entry?.type ?: WajubErrorType.InvalidRequestError,
                message = "",
                code = code,
                retryable = entry?.retryable ?: true,
                correlationId = correlationId,
                httpStatus = 200,
                apiMessage = body.obj("error")?.str("message"),
            )
            val text = ErrorCatalog.message(code, language, draft, attemptArgs(body))
                ?: (if (language == WajubLanguage.En) draft.apiMessage else null)
                ?: ErrorCatalog.message("request_failed", language, draft)!!
            return draft.withMessage(text)
        }

        /** The error of [code], with its message in [language]. */
        fun of(
            code: String,
            language: WajubLanguage,
            param: String? = null,
            details: Map<String, String> = emptyMap(),
            apiMessage: String? = null,
            args: Map<String, String> = emptyMap(),
        ): WajubError {
            val entry = ErrorCatalog.entry(code)
            val draft = WajubError(
                type = entry?.type ?: WajubErrorType.ApiError,
                message = "",
                code = code,
                retryable = entry?.retryable ?: false,
                param = param,
                details = details,
                apiMessage = apiMessage,
            )
            return draft.withMessage(ErrorCatalog.message(code, language, draft, args) ?: apiMessage ?: code)
        }

        private fun attemptArgs(body: JSONObject): Map<String, String> =
            body.int("attempts_remaining")?.let { mapOf("n" to it.toString()) } ?: emptyMap()

        private fun codeForStatus(status: Int, loading: Boolean): String =
            when {
                status == 400 -> "not_processable"
                status == 401 || status == 404 -> "session_not_found"
                status == 402 -> "payment_declined"
                status == 403 -> if (loading) "session_not_found" else "merchant_restricted"
                status == 409 -> "order_not_validated"
                status == 422 -> "validation_error"
                status == 429 -> "rate_limited"
                status >= 500 -> "server_error"
                else -> "request_failed"
            }

        private fun typeForStatus(status: Int): WajubErrorType =
            when {
                status == 402 || status == 403 -> WajubErrorType.PaymentError
                status == 401 || status == 404 -> WajubErrorType.AuthenticationError
                status == 429 -> WajubErrorType.RateLimitError
                status >= 500 -> WajubErrorType.ApiError
                else -> WajubErrorType.InvalidRequestError
            }

        // errors.md §2 step 4, for a code the catalogue does not know.
        private fun fallbackMessage(status: Int, language: WajubLanguage, apiText: String?): String {
            // A provider's own decline code (`failed`, `stolen_card`…), in both languages.
            if (status == 402) return ErrorCatalog.genericDecline(language)
            if (language == WajubLanguage.En && apiText != null) return apiText
            val code = when (status) {
                422 -> "validation_error"
                else -> "server_error"
            }
            return ErrorCatalog.message(code, language, null)!!
        }
    }

    private fun withMessage(text: String): WajubError =
        WajubError(
            type, text, code, declineCode, retryable, param, correlationId, retryAfterSeconds,
            details, httpStatus, blockReference, apiMessage,
        )
}
