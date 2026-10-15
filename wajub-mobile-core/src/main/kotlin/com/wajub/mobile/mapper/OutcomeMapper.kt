package com.wajub.mobile.mapper

import com.wajub.mobile.client.ApiResponse
import com.wajub.mobile.model.PaymentStatus
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessingKind
import com.wajub.mobile.model.StepResult
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubLanguage
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Decisions on the API's answers (spec §9, §10, §13). */
internal object OutcomeMapper {
    /** Paid, whatever happened next to the money. */
    val SUCCESS_STATUSES = setOf("succeeded", "refunded", "partially_refunded")

    /**
     * A 2xx (or 4xx) answer of `/pay/process` or the client-session calls.
     * [mobileMoney]: the channel is Mobile Money, so a bare 202 is a push to approve.
     * A 5xx is not mapped here: the caller decides whether the outcome is unknown.
     */
    fun process(response: ApiResponse, mobileMoney: Boolean, language: WajubLanguage, errorOf: (ApiResponse) -> WajubError): ProcessOutcome {
        if (!response.isSuccess) {
            val transaction = response.body.obj("transaction")?.let { runCatching { RawTransaction.fromJson(it).toModel() }.getOrNull() }
            if (response.status == 409 && response.body.str("error_code") == "payment_in_progress") {
                return ProcessOutcome.Processing(transaction = null)
            }
            return ProcessOutcome.Failed(errorOf(response), transaction)
        }
        // A body that does not parse (a transaction without its amount…) tells nothing: watch.
        val raw = try {
            RawProcessResponse.fromJson(response.body)
        } catch (_: JSONException) {
            return ProcessOutcome.Processing(transaction = null)
        }
        val transaction = raw.transaction?.toModel()
        val context = transaction?.processingContext
        return when (raw.action) {
            null -> when {
                // No transaction, nothing to do: the outcome is unknown, watch the status.
                transaction == null -> ProcessOutcome.Processing(transaction = null)
                transaction.status.lowercase() in SUCCESS_STATUSES -> ProcessOutcome.Complete(transaction)
                context?.isUssd == true -> ProcessOutcome.Processing(transaction, context.payerInstruction, ProcessingKind.Ussd)
                mobileMoney || context?.isMobileMoney == true ->
                    ProcessOutcome.Processing(transaction, context?.payerInstruction, ProcessingKind.Approval)
                else -> ProcessOutcome.Processing(transaction, context?.payerInstruction, ProcessingKind.Pending)
            }
            else -> action(raw, transaction, language)
        }
    }

    // A 202's `action` and the fields it comes with: a page, a step, a client
    // session, or one the SDK cannot take (`unsupported_action`).
    private fun action(raw: RawProcessResponse, transaction: com.wajub.mobile.model.SessionTransaction?, language: WajubLanguage): ProcessOutcome {
        val action = raw.action ?: ""
        val unsupported = {
            ProcessOutcome.Failed(WajubError.of("unsupported_action", language, apiMessage = "action: ${raw.action}"), transaction)
        }
        return when (action) {
            "redirect", "confirm" -> raw.confirmUrl?.let {
                ProcessOutcome.RequiresAction(action, url = it, transaction = transaction)
            } ?: unsupported()
            "confirm_3ds" -> when {
                raw.confirmUrl != null -> ProcessOutcome.RequiresAction(action, url = raw.confirmUrl, transaction = transaction)
                raw.threeDsId != null -> ProcessOutcome.RequiresAction(
                    action,
                    stepId = raw.threeDsId,
                    hint = raw.maskedEmail,
                    transaction = transaction,
                    timeoutSeconds = raw.threeDsTimeout,
                )
                else -> unsupported()
            }
            "confirm_otp" -> step(action, raw.otpId, raw.otpMaskedContact, transaction) ?: unsupported()
            "confirm_pin" -> step(action, raw.pinId, raw.pinInstruction, transaction) ?: unsupported()
            "confirm_phone" -> step(action, raw.phoneId, raw.phoneInstruction, transaction) ?: unsupported()
            "confirm_birthday" -> step(action, raw.birthdayId, raw.birthdayInstruction, transaction) ?: unsupported()
            "confirm_address" -> step(action, raw.addressId, raw.addressInstruction, transaction) ?: unsupported()
            "client_session" -> raw.clientSession?.toModel()?.let {
                ProcessOutcome.RequiresAction(action, url = it.hostedUrl, clientSession = it, transaction = transaction)
            } ?: unsupported()
            else -> unsupported()
        }
    }

    private fun step(action: String, id: String?, hint: String?, transaction: com.wajub.mobile.model.SessionTransaction?) =
        id?.let { ProcessOutcome.RequiresAction(action, stepId = it, hint = hint, transaction = transaction) }

    /**
     * `POST /pay/{kind}/verify`: HTTP 200 with `{status: success|failed}`, or a 422.
     * A `success` may carry the provider's next step (`action` and its fields,
     * as a `/pay/process` 202): [StepResult.Accepted.next].
     */
    fun step(response: ApiResponse, language: WajubLanguage, errorOf: (ApiResponse) -> WajubError): StepResult {
        if (!response.isSuccess) {
            val error = errorOf(response)
            return if (response.status == 422) StepResult.Invalid(error) else StepResult.Rejected(error, canRetryHere = false)
        }
        val body = response.body
        if (body.str("status") == "failed") {
            val error = WajubError.fromStepFailure(body, language, response.correlationId)
            val attempts = body.int("attempts_remaining")
            val stay = error.code.startsWith("invalid_") && (attempts ?: 0) > 0
            return StepResult.Rejected(error, attempts, canRetryHere = stay)
        }
        val transaction = body.obj("transaction")?.let { runCatching { RawTransaction.fromJson(it).toModel() }.getOrNull() }
        val next = body.str("action")?.let {
            // The step's transaction is often partial: the action's fields decide.
            val fields = JSONObject(body.toString()).apply { remove("transaction") }
            val raw = runCatching { RawProcessResponse.fromJson(fields) }.getOrNull()
            if (raw == null) {
                ProcessOutcome.Failed(WajubError.of("unsupported_action", language, apiMessage = "action: $it"), transaction)
            } else {
                action(raw, transaction, language)
            }
        }
        return StepResult.Accepted(transaction, next)
    }

    /**
     * `POST /pay/3ds/verify`: HTTP 200 (body code 202) accepted; a 422 with
     * `error.retryable: true`, or any 5xx (an API bug answers a wrong code
     * with a 500), is a wrong code to type again.
     */
    fun threeDs(response: ApiResponse, language: WajubLanguage, errorOf: (ApiResponse) -> WajubError): StepResult {
        if (response.isSuccess) return StepResult.Accepted(null)
        val attempts = response.body.int("attempts_remaining")
        if (response.status >= 500) {
            return StepResult.Rejected(WajubError.of("invalid_code", language), attempts, canRetryHere = true)
        }
        val error = errorOf(response)
        val retryable = response.body.obj("error")?.bool("retryable")
        return when {
            retryable == true -> StepResult.Rejected(error, attempts, canRetryHere = true)
            retryable == false -> StepResult.Rejected(error, attempts, canRetryHere = false)
            response.status == 422 -> StepResult.Invalid(error)
            else -> StepResult.Rejected(error, attempts, canRetryHere = false)
        }
    }

    /** `GET /pay/status` → `{transaction: {reference, status, error_code?, payer_message?}}`; null without a status. */
    fun statusOrNull(body: JSONObject): PaymentStatus? {
        val t = body.obj("transaction") ?: body
        val status = t.str("status") ?: return null
        return PaymentStatus(t.str("reference"), status.lowercase(), t.str("error_code"), t.str("payer_message"))
    }

    fun status(body: JSONObject): PaymentStatus = statusOrNull(body) ?: PaymentStatus(null, "")

    /** The realtime event's data: a JSON object, or a JSON string holding one. */
    fun event(data: Any?): PaymentStatus? {
        val obj = when (data) {
            is JSONObject -> data
            is String -> {
                var value: Any? = runCatching { JSONTokener(data).nextValue() }.getOrNull()
                if (value is String) value = runCatching { JSONTokener(value).nextValue() }.getOrNull()
                value as? JSONObject
            }
            else -> null
        } ?: return null
        val status = obj.str("status") ?: return null
        return PaymentStatus(obj.str("reference") ?: obj.str("uid"), status.lowercase(), obj.str("error_code"), obj.str("payer_message"))
    }
}
