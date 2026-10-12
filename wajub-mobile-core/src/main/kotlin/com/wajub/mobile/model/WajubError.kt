package com.wajub.mobile.model

enum class WajubErrorType {
    ApiError,
    AuthenticationError,
    InvalidRequestError,
    PaymentError,
    RateLimitError,
}

class WajubError(
    val type: WajubErrorType,
    override val message: String,
    val code: String,
    val declineCode: String? = null,
    val retryable: Boolean = false,
    val param: String? = null,
    val correlationId: String? = null,
    val retryAfterSeconds: Int? = null,
    val details: Map<String, String> = emptyMap(),
    val httpStatus: Int? = null,
) : Exception(message) {

    companion object {
        fun network(message: String = "Network error"): WajubError =
            WajubError(WajubErrorType.ApiError, message, "network_error", retryable = true)

        fun fromHttp(
            httpStatus: Int,
            message: String,
            code: String,
            details: Map<String, String> = emptyMap(),
            correlationId: String? = null,
            retryAfterSeconds: Int? = null,
        ): WajubError {
            // 401, 404 and 410 are a token that is not, or no longer, a
            // payment session: no retry of the same token can succeed. A 5xx
            // is Wajub's side, not the payer's payment.
            val type = when {
                httpStatus == 401 || httpStatus == 404 || httpStatus == 410 -> WajubErrorType.AuthenticationError
                httpStatus == 422 -> WajubErrorType.InvalidRequestError
                httpStatus == 429 -> WajubErrorType.RateLimitError
                httpStatus >= 500 -> WajubErrorType.ApiError
                else -> WajubErrorType.PaymentError
            }
            val param = details.keys.singleOrNull()
            return WajubError(
                type = type,
                message = message,
                code = code,
                declineCode = if (httpStatus == 402) code else null,
                retryable = httpStatus !in setOf(401, 403, 404, 410),
                param = param,
                correlationId = correlationId,
                retryAfterSeconds = retryAfterSeconds,
                details = details,
                httpStatus = httpStatus,
            )
        }
    }
}
