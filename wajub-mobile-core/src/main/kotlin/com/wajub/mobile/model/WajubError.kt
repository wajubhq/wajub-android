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
            val type = when (httpStatus) {
                401, 404 -> WajubErrorType.AuthenticationError
                422 -> WajubErrorType.InvalidRequestError
                429 -> WajubErrorType.RateLimitError
                else -> WajubErrorType.PaymentError
            }
            val param = details.keys.singleOrNull()
            return WajubError(
                type = type,
                message = message,
                code = code,
                declineCode = if (httpStatus == 402) code else null,
                retryable = httpStatus != 403,
                param = param,
                correlationId = correlationId,
                retryAfterSeconds = retryAfterSeconds,
                details = details,
                httpStatus = httpStatus,
            )
        }
    }
}
