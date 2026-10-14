package com.wajub.mobile.model

import java.util.UUID

/** One payment attempt for `WajubSession.process(ProcessRequest)`. */
data class ProcessRequest(
    /** The channel slug: `cm.mobile`, `ci.wave`, `card`, `ci.djamo`… */
    val channel: String,
    /** `data` of the request; `_client` is added by the SDK. */
    val data: Map<String, Any?> = emptyMap(),
    /**
     * Sent as `Idempotency-Key`, and reused by the automatic retries of this
     * request. One per press of Pay: a new request gets a new key.
     */
    val idempotencyKey: String = UUID.randomUUID().toString(),
)

/** What the API asks next, when a `202` carries nothing for the payer to do. */
enum class ProcessingKind(val value: String) {
    /** A push was sent to the phone: approve it there. */
    Approval("approval"),

    /** Dial the code of the instruction. */
    Ussd("ussd"),

    /** Nothing to do but wait (also: an unknown outcome, the transaction then being null). */
    Pending("pending"),
}

/**
 * One `/pay/process` (or client-session) answer, mapped on its HTTP status,
 * `action` and `transaction.status` — never on `message` (spec §9).
 */
sealed class ProcessOutcome {
    /** Paid. */
    data class Complete(val transaction: SessionTransaction) : ProcessOutcome()

    /**
     * Under way: watch the status. [transaction] is null when the outcome is
     * unknown (a timeout, a 5xx, or a charge already in flight).
     */
    data class Processing(
        val transaction: SessionTransaction?,
        /** English, from `processing_context.payer_instruction`. */
        val instruction: String? = null,
        val kind: ProcessingKind = ProcessingKind.Pending,
    ) : ProcessOutcome()

    /** The payer has something to do: a page to open, or a confirmation step. */
    data class RequiresAction(
        /** The API's `action`: `redirect`, `confirm`, `confirm_3ds`, `confirm_otp`…, `client_session`. */
        val action: String,
        /** `confirm_url`, or the client session's `hosted_url`. */
        val url: String? = null,
        /** `otp_id`, `pin_id`, `phone_id`, `birthday_id`, `address_id`, `three_ds_id`. */
        val stepId: String? = null,
        /** `otp_masked_contact`, `*_instruction` or `masked_email`. */
        val hint: String? = null,
        val clientSession: ClientSession? = null,
        val transaction: SessionTransaction? = null,
        /** `three_ds_timeout`, seconds. */
        val timeoutSeconds: Int? = null,
    ) : ProcessOutcome() {
        /** The confirmation step this action asks for, or null for a page to open. */
        val step: StepKind? get() = StepKind.ofAction(action)
    }

    data class Failed(
        val error: WajubError,
        val transaction: SessionTransaction? = null,
    ) : ProcessOutcome()
}

/** The payer confirmation steps (spec §10) and the sandbox 3-D Secure code. */
enum class StepKind(
    /** The endpoint's segment: `/pay/{path}/verify`. */
    val path: String,
    /** The id key of the 202 and of the verify body. */
    val idKey: String,
    /** The fields the payer enters, as the verify body names them. */
    val fields: List<String>,
) {
    Otp("otp", "otp_id", listOf("code")),
    Pin("pin", "pin_id", listOf("pin")),
    Phone("phone", "phone_id", listOf("phone")),
    Birthday("birthday", "birthday_id", listOf("birthday")),
    Address("address", "address_id", listOf("address", "city", "state", "zip_code")),

    /** Sandbox email code: `/pay/3ds/verify {three_ds_id, code}`, `/pay/3ds/send`. */
    ThreeDs("3ds", "three_ds_id", listOf("code")),
    ;

    companion object {
        fun ofAction(action: String): StepKind? =
            when (action) {
                "confirm_otp" -> Otp
                "confirm_pin" -> Pin
                "confirm_phone" -> Phone
                "confirm_birthday" -> Birthday
                "confirm_address" -> Address
                else -> null
            }
    }
}

/** The answer to a confirmation step. */
sealed class StepResult {
    /**
     * Accepted. [transaction] (null for 3-D Secure) decides: `succeeded` is
     * paid, anything else is still to watch.
     */
    data class Accepted(val transaction: SessionTransaction?) : StepResult()

    /**
     * Refused. [canRetryHere]: a wrong value with attempts left — stay on the
     * step; otherwise the step is over (`max_attempts_exceeded`, `*_expired`).
     */
    data class Rejected(
        val error: WajubError,
        val attemptsRemaining: Int? = null,
        val canRetryHere: Boolean,
    ) : StepResult()

    /** A 422: the fields in [WajubError.details] need fixing. */
    data class Invalid(val error: WajubError) : StepResult()
}
