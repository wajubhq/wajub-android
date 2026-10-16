package com.wajub.mobile.model

import java.util.UUID

/** One payment attempt for `WajubSession.process(ProcessRequest)`. */
data class ProcessRequest(
    /** The channel slug: `cm.mobile`, `ci.wave`, `card`, `ci.djamo`… */
    val channel: String,
    /** What the payer entered (`phone`, `country`, `operator`…). */
    val data: Map<String, Any?> = emptyMap(),
    /** Pass the same key to retry the same payment. */
    val idempotencyKey: String = UUID.randomUUID().toString(),
)

/** What happens next while a payment is under way. */
enum class ProcessingKind(val value: String) {
    /** A push was sent to the phone: approve it there. */
    Approval("approval"),

    /** Dial the code of the instruction. */
    Ussd("ussd"),

    /** Nothing to do but wait (also: an unknown outcome, the transaction then being null). */
    Pending("pending"),
}

/** The answer to a payment request. */
sealed class ProcessOutcome {
    /** Paid. */
    data class Complete(val transaction: SessionTransaction) : ProcessOutcome()

    /**
     * Under way: watch the payment. [transaction] is null when the outcome is
     * not known yet.
     */
    data class Processing(
        val transaction: SessionTransaction?,
        /** The provider's instruction for the payer, in English. */
        val instruction: String? = null,
        val kind: ProcessingKind = ProcessingKind.Pending,
        /** When no answer came back at all: the clock time (ms) of the last try. */
        val unansweredSince: Long? = null,
    ) : ProcessOutcome()

    /**
     * The payer has something to do: a page to open, a confirmation step, or
     * (`payer_otp`) a code to generate in the operator's menu, nothing being charged yet.
     */
    data class RequiresAction(
        /** What to do: `redirect`, `confirm`, `confirm_3ds`, `confirm_otp`…, `client_session`, `payer_otp`. */
        val action: String,
        /** The page to open. */
        val url: String? = null,
        /** The step's id, for [com.wajub.mobile.WajubSession.verifyStep]. */
        val stepId: String? = null,
        /** Where the code was sent, or the provider's instruction (in English). */
        val hint: String? = null,
        val clientSession: ClientSession? = null,
        val transaction: SessionTransaction? = null,
        /** Seconds the sandbox 3-D Secure code is valid for. */
        val timeoutSeconds: Int? = null,
        /**
         * With `payer_otp`: how the payer gets the code. Then pay again with a
         * new [ProcessRequest]: the same channel and data, plus the code as `otp`.
         */
        val payerOtp: PayerOtpInstructions? = null,
    ) : ProcessOutcome() {
        /**
         * The confirmation step this action asks for, or null for a page to open
         * (3-D Secure is a step only for the sandbox code).
         */
        val step: StepKind?
            get() = StepKind.ofAction(action)?.takeIf { it != StepKind.ThreeDs || (url == null && stepId != null) }
    }

    data class Failed(
        val error: WajubError,
        val transaction: SessionTransaction? = null,
    ) : ProcessOutcome() {
        // An answer that charged nothing (the payer's own code asked for without its instructions):
        // the attempt has not started.
        internal var chargedNothing: Boolean = false
    }
}

/** The confirmation steps, and the sandbox 3-D Secure code. */
enum class StepKind(
    /** The step's short name: `otp`, `pin`, `phone`, `birthday`, `address`, `3ds`. */
    val path: String,
    internal val idKey: String,
    /** The fields the payer enters. */
    val fields: List<String>,
) {
    Otp("otp", "otp_id", listOf("code")),
    Pin("pin", "pin_id", listOf("pin")),
    Phone("phone", "phone_id", listOf("phone")),
    Birthday("birthday", "birthday_id", listOf("birthday")),
    Address("address", "address_id", listOf("address", "city", "state", "zip_code")),

    /** The sandbox 3-D Secure code, sent by email. */
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
                "confirm_3ds" -> ThreeDs
                else -> null
            }
    }
}

/** The answer to a confirmation step. */
sealed class StepResult {
    /**
     * Accepted. [next], when set, is the provider's next step (another code, a
     * page) to follow as a payment answer; otherwise [transaction] (null for
     * 3-D Secure) decides: `succeeded` is paid, anything else is still to watch.
     */
    data class Accepted(
        val transaction: SessionTransaction?,
        /** A [ProcessOutcome.RequiresAction], or [ProcessOutcome.Failed] (`unsupported_action`). */
        val next: ProcessOutcome? = null,
    ) : StepResult()

    /**
     * Refused. [canRetryHere]: a wrong value with attempts left — stay on the
     * step; otherwise the step is over (`max_attempts_exceeded`, `*_expired`).
     */
    data class Rejected(
        val error: WajubError,
        val attemptsRemaining: Int? = null,
        val canRetryHere: Boolean,
    ) : StepResult()

    /** The fields in [WajubError.details] need fixing. */
    data class Invalid(val error: WajubError) : StepResult()
}
