package com.wajub.mobile

import com.wajub.mobile.checkout.AltchaSolution
import com.wajub.mobile.client.ApiCall
import com.wajub.mobile.client.ApiResponse
import com.wajub.mobile.client.PayClient
import com.wajub.mobile.client.TransportException
import com.wajub.mobile.mapper.OutcomeMapper
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.mapper.str
import com.wajub.mobile.model.CancelResult
import com.wajub.mobile.model.MobileMoneyInput
import com.wajub.mobile.model.PaymentResult
import com.wajub.mobile.model.PaymentStatus
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessRequest
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.StepKind
import com.wajub.mobile.model.StepResult
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubLanguage
import com.wajub.mobile.realtime.PaymentWatcher
import com.wajub.mobile.realtime.PollSchedule
import com.wajub.mobile.realtime.PusherConnector
import com.wajub.mobile.realtime.RealtimeConnector
import com.wajub.mobile.realtime.RealtimeEndpoint
import com.wajub.mobile.realtime.RealtimeOptions
import com.wajub.mobile.realtime.WATCH_CAP_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

/**
 * Session-scoped checkout client — the `/pay` API of one payment, for apps
 * that draw their own UI. `PaymentSheet` (wajub-mobile-compose) is built on it.
 */
class WajubSession internal constructor(
    private val token: String,
    internal val client: PayClient = PayClient(),
) {
    @Volatile
    private var cachedSession: SessionData? = null

    /**
     * A stable identity of this session's payment (a hash of its token, never
     * the token): two sessions created for the same token share it, e.g. to
     * key UI state.
     */
    val sessionKey: String by lazy {
        java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }

    /** The language of the errors' messages: French or English. */
    var language: WajubLanguage
        get() = client.language
        set(value) {
            client.language = value
        }

    /** Loads (or returns cached) checkout session data. */
    suspend fun loadSession(forceRefresh: Boolean = false): SessionData {
        if (!forceRefresh) cachedSession?.let { return it }
        val session = client.getSession(token)
        cachedSession = session
        return session
    }

    // The session for the v2 calls: cached, else loaded with the spec's load errors.
    internal suspend fun session(forceRefresh: Boolean = false): SessionData {
        if (!forceRefresh) cachedSession?.let { return it }
        return client.fetchSession(token).also { cachedSession = it }
    }

    /** Per-channel PSP tokenizer configuration. */
    suspend fun getSdkConfig(): SdkConfig = client.getSdkConfig(token)

    /** `GET /pay/status`: the payment's status, and why an attempt was refused. */
    suspend fun status(): PaymentStatus = OutcomeMapper.status(client.expectSuccess(client.send(token, ApiCall.Status)))

    /**
     * `POST /pay/process`, mapped as the spec says (§9). `data._client` (the
     * device signals) is added. The request's automatic retries reuse its
     * Idempotency-Key; an answer that never came, or a 5xx, is an unknown
     * outcome: [ProcessOutcome.Processing] without a transaction — watch the status.
     */
    suspend fun process(request: ProcessRequest): ProcessOutcome {
        val session = session()
        // Card numbers, CVCs and PINs never go to the API in live (README §7).
        if (!session.transaction.sandbox && carriesRawCard(request.data)) {
            return ProcessOutcome.Failed(WajubError.of("raw_card_data_not_accepted", language))
        }
        val channel = session.channels.firstOrNull { it.slug == request.channel }
        val mobileMoney = channel?.type?.lowercase() in setOf("mobile_money", "mobile")
        val payload = client.processPayload(request.channel, request.data)
        val response = try {
            client.sendRaw(token, ApiCall.Process, payload, request.idempotencyKey)
        } catch (failure: TransportException) {
            return ProcessOutcome.Processing(transaction = null, unansweredSince = failure.sentAt)
        }
        // A 5xx (502/503/504 after the retries, or a 500): unknown, as no answer.
        if (response.status >= 500) return ProcessOutcome.Processing(transaction = null, unansweredSince = response.sentAt)
        return OutcomeMapper.process(response, mobileMoney, language) { client.errorOf(it, loading = false) }
    }

    /**
     * `POST /pay/client-session` for a card paid on the PSP's own page
     * (sdk-config `clientSession`). [fields] are the contact/billing fields
     * sdk-config requires (`email`, `first_name`…); [returnUrl] an https link
     * back into the app. Open the [ProcessOutcome.RequiresAction.url], then
     * call [confirmClientSession] when the payer is back.
     */
    suspend fun openClientSession(
        fields: Map<String, String> = emptyMap(),
        returnUrl: String? = null,
        restart: Boolean = false,
        altcha: AltchaSolution? = null,
        channel: String = "card",
    ): ProcessOutcome {
        val extra = LinkedHashMap<String, Any?>(fields.filterValues { it.isNotBlank() })
        altcha?.let { extra["altcha"] = it.toMap() }
        val payload = client.clientSessionPayload(channel, fields["email"], fields["name"], returnUrl, restart, extra)
        val response = try {
            client.sendRaw(token, ApiCall.ClientSession, payload)
        } catch (failure: TransportException) {
            return ProcessOutcome.Failed(client.errorOf(failure))
        }
        return OutcomeMapper.process(response, mobileMoney = false, language) { client.errorOf(it, loading = false) }
    }

    /**
     * `POST /pay/client-session/complete` once the payer is back from the PSP:
     * [ProcessOutcome.Complete] when paid, [ProcessOutcome.Processing] while the
     * PSP has not settled it (keep watching), [ProcessOutcome.Failed] for a
     * decline (402) or an expired session (`client_session_expired`).
     */
    suspend fun confirmClientSession(clientSessionId: String): ProcessOutcome {
        val payload = JSONObject().put("client_session_id", clientSessionId)
        val response = try {
            client.sendRaw(token, ApiCall.ClientSessionComplete, payload)
        } catch (_: TransportException) {
            return ProcessOutcome.Processing(transaction = null)
        }
        if (response.status >= 500) return ProcessOutcome.Processing(transaction = null)
        return OutcomeMapper.process(response, mobileMoney = false, language) { client.errorOf(it, loading = false) }
    }

    /**
     * Sends what the payer entered for a confirmation step: `code` (OTP, 3-D
     * Secure), `pin`, `phone`, `birthday` (`YYYY-MM-DD`), or `address`, `city`,
     * `state`, `zip_code`. Never retried: on a [WajubError] (no answer), read
     * [status] before letting the payer try again.
     */
    suspend fun verifyStep(kind: StepKind, stepId: String, values: Map<String, String>): StepResult {
        val payload = JSONObject().put(kind.idKey, stepId)
        kind.fields.forEach { field -> payload.put(field, values[field].orEmpty()) }
        val call = if (kind == StepKind.ThreeDs) ApiCall.ThreeDsVerify else ApiCall.verify(kind.path)
        val response = client.send(token, call, payload)
        // A 5xx on a step is no answer about the step (3-D Secure's is a wrong code).
        if (kind != StepKind.ThreeDs && response.status >= 500) throw client.errorOf(response, loading = false)
        val errorOf = { r: ApiResponse -> client.errorOf(r, loading = false) }
        return if (kind == StepKind.ThreeDs) {
            OutcomeMapper.threeDs(response, language, errorOf)
        } else {
            OutcomeMapper.step(response, language, errorOf)
        }
    }

    /** Asks for a new code (`/pay/{kind}/resend`, `/pay/3ds/send`); returns the masked email 3-D Secure names. */
    suspend fun resendStep(kind: StepKind, stepId: String): String? {
        val call = if (kind == StepKind.ThreeDs) ApiCall.ThreeDsSend else ApiCall.resend(kind.path)
        return client.expectSuccess(client.send(token, call, JSONObject().put(kind.idKey, stepId))).str("masked_email")
    }

    /**
     * Watches the payment until it decides (spec §13): `GET /pay/status` every
     * 5 s (15 s while the socket is connected, slower after 2 minutes without
     * a change), with the session's Pusher/Reverb socket next to it unless
     * [realtime] turns it off. [seenProcessing]: an attempt of this payer is
     * under way, so a `pending` status means it ended without charging.
     */
    suspend fun watch(realtime: RealtimeOptions? = null, seenProcessing: Boolean = true): PaymentWatcher =
        watcher(session(), realtime, seenProcessing)

    internal fun watcher(
        session: SessionData,
        realtime: RealtimeOptions?,
        seenProcessing: Boolean,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        random: Random = Random.Default,
        connector: RealtimeConnector = PusherConnector,
        startedAt: Long? = null,
    ): PaymentWatcher {
        // 15 minutes of the device's monotonic clock from the attempt (the Pay press):
        // never a server timestamp against the device's.
        val deadline = (startedAt ?: clock()) + WATCH_CAP_MS
        return PaymentWatcher(
            client = client,
            token = token,
            transactionId = session.transaction.id,
            endpoint = RealtimeEndpoint.resolve(session.echo, realtime),
            seenProcessing = seenProcessing,
            deadline = deadline,
            language = { language },
            schedule = PollSchedule(),
            clock = clock,
            random = random,
            connector = connector,
            authUrl = client.baseUrl.trimEnd('/') + ApiCall.BroadcastingAuth.path,
            authHeaders = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json",
                "X-Wajub-Client" to client.clientHeader(),
            ),
        )
    }

    private fun carriesRawCard(data: Map<*, *>): Boolean =
        data.any { (key, value) ->
            val name = key.toString().lowercase()
            name in RAW_CARD_KEYS || name == "card" && value is Map<*, *> && value.keys.any { it.toString().lowercase() in CARD_FIELDS }
        }

    /** `POST /pay/cancel` as the API answered it (the checkout reads a 400 itself). */
    internal suspend fun cancelRaw(): ApiResponse = client.send(token, ApiCall.Cancel, JSONObject())

    /**
     * Submit a Mobile Money payment using the native `form` flavor.
     * Channel slug must match a channel from [loadSession].
     */
    @Deprecated(
        "Use process(ProcessRequest), which maps every answer of the API (ProcessOutcome).",
        ReplaceWith("process(ProcessRequest(input.channelSlug, PaymentMapper.buildMobileMoneyRequest(input)))"),
    )
    suspend fun payMobileMoney(input: MobileMoneyInput): PaymentResult {
        loadSession()
        val raw = client.process(token, input.channelSlug, PaymentMapper.buildMobileMoneyRequest(input))
        return PaymentMapper.mapProcessResponse(raw, "mobile_money")
    }

    /** Stripe tokenized card — `payment_method_id` from [StripeTokenizer]. */
    @Deprecated("Use process(ProcessRequest(channelSlug, mapOf(\"payment_method_id\" to paymentMethodId))).")
    suspend fun payCard(channelSlug: String, paymentMethodId: String, cardholderName: String? = null): PaymentResult {
        loadSession()
        val body = linkedMapOf<String, Any?>("payment_method_id" to paymentMethodId)
        if (!cardholderName.isNullOrBlank()) {
            body["card_holder"] = cardholderName
        }
        val raw = client.process(token, channelSlug, body)
        return PaymentMapper.mapProcessResponse(raw, "card")
    }

    /**
     * Card on a `hosted_redirect` channel (PayPal, Mollie, Paddle, Kkiapay, FedaPay,
     * PayDunya, CinetPay): returns [PaymentResult.RequiresAction] redirecting to the
     * PSP's own card page. [billing] must carry every field the channel's
     * `requiredFields` lists (see [PaymentMapper.hostedCardFields]).
     */
    @Deprecated("Use process(ProcessRequest(channelSlug, PaymentMapper.buildHostedCardRequest(billing))).")
    suspend fun payCardHostedRedirect(channelSlug: String, billing: Map<String, String> = emptyMap()): PaymentResult {
        loadSession()
        val raw = client.process(token, channelSlug, PaymentMapper.buildHostedCardRequest(billing))
        return PaymentMapper.mapProcessResponse(raw, "card")
    }

    /**
     * One-tap wallet channel (`sdk-config` `form`, no `requiredFields`, channel type
     * `wallet` — e.g. Djamo): returns [PaymentResult.RequiresAction] redirecting to the
     * wallet's payment page, which opens the wallet app.
     */
    @Deprecated("Use process(ProcessRequest(channelSlug)).")
    suspend fun payWallet(channelSlug: String): PaymentResult {
        loadSession()
        @Suppress("DEPRECATION")
        return process(channelSlug, emptyMap())
    }

    /** Generic process call for advanced integrations (Stripe adapter, etc.). */
    @Deprecated("Use process(ProcessRequest(channel, data)), which maps every answer of the API (ProcessOutcome).")
    suspend fun process(channel: String, data: Map<String, Any?>): PaymentResult {
        val raw = client.process(token, channel, data)
        val methodType = sessionMethodType(channel)
        return PaymentMapper.mapProcessResponse(raw, methodType)
    }

    /**
     * Opens (or re-serves) a PSP-hosted checkout for [channel] — for channels whose
     * `sdk-config` entry has `clientSession == true` (Paystack / Flutterwave cards).
     * Returns [PaymentResult.RequiresAction] with [com.wajub.mobile.model.ActionKind.ClientSession]
     * and a [com.wajub.mobile.model.ClientSession]: open its `hostedUrl` in Custom Tabs
     * (or launch the PSP's native SDK yourself), then call [completeClientSession].
     *
     * [email] is required by Paystack/Flutterwave when the transaction has no customer
     * email. [returnUrl] (http/https, e.g. an app link) is where the PSP's hosted page
     * redirects after payment. [restart] replaces a session that died on the PSP side —
     * the backend re-verifies the old one first.
     */
    @Deprecated("Use openClientSession(), which maps every answer of the API (ProcessOutcome).")
    suspend fun startClientSession(
        channel: String,
        email: String? = null,
        name: String? = null,
        returnUrl: String? = null,
        restart: Boolean = false,
    ): PaymentResult {
        loadSession()
        val raw = client.startClientSession(token, channel, email, name, returnUrl, restart)
        return PaymentMapper.mapProcessResponse(raw, sessionMethodType(channel))
    }

    /**
     * Verifies a client session once the payer is back from the PSP's UI.
     * [PaymentResult.Complete] when confirmed, [PaymentResult.Processing] while the PSP
     * hasn't settled it yet (keep [watchStatus] running — its webhook will), and a
     * thrown [com.wajub.mobile.model.WajubError] (HTTP 402) when the PSP reports it failed.
     */
    @Deprecated("Use confirmClientSession(), which maps every answer of the API (ProcessOutcome).")
    suspend fun completeClientSession(clientSessionId: String): PaymentResult {
        val raw = client.completeClientSession(token, clientSessionId)
        return PaymentMapper.mapProcessResponse(raw, "card")
    }

    /** Cancels the checkout session (`POST /pay/cancel`); returns the merchant's `redirect_url`. */
    suspend fun cancel(): String? = client.cancel(token).redirectUrl

    /**
     * For a payment sheet the payer closed. When nothing had been [submitted],
     * the payment is cancelled on Wajub too (`POST /pay/cancel`), as the hosted
     * checkout does; an attempt under way is left alone, since a Mobile Money
     * approval can still land. Best effort, in the background: closing the
     * sheet never waits on it, and a failure is ignored.
     */
    fun cancelIfNothingSubmitted(
        submitted: Boolean,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ): Job? {
        if (submitted) return null
        return scope.launch { runCatching { cancel() } }
    }

    /**
     * Polls `GET /pay/status`, with Pusher/Reverb next to it when `echo` is
     * configured. Emits when the result changes and ends on a final one.
     */
    @Deprecated("Use watch(), whose events follow every decision of the spec.")
    @Suppress("DEPRECATION")
    suspend fun watchStatus(intervalMs: Long = 5_000L): Flow<PaymentResult> {
        val session = loadSession()
        return com.wajub.mobile.realtime.RealtimeSubscriber(client, token, session, intervalMs).subscribe()
    }

    /** Legacy polling-only API. */
    @Deprecated("Use watch(RealtimeOptions(enabled = false)).")
    @Suppress("DEPRECATION")
    fun watchStatusPolling(intervalMs: Long = 5_000L): Flow<PaymentResult> =
        com.wajub.mobile.realtime.StatusPoller(client, token, intervalMs).poll()

    fun cardChannelSlug(): String? =
        cachedSession?.channels?.firstOrNull { it.type.equals("card", ignoreCase = true) }?.slug

    private fun sessionMethodType(channel: String): String {
        val channels = cachedSession?.channels.orEmpty()
        val match = channels.firstOrNull { it.slug == channel }
        return when (match?.type?.lowercase()) {
            "mobile_money", "mobile" -> "mobile_money"
            "card" -> "card"
            "wallet" -> "wallet"
            else -> "unknown"
        }
    }
}

// Keys of raw card data (RawCardDataGuard): never sent in live.
private val RAW_CARD_KEYS = setOf("card.number", "card_number", "pan", "cvv", "cvc", "cvv2", "card.cvv", "card.cvc", "pin", "card.pin")

// The same fields nested under `card`.
private val CARD_FIELDS = setOf("number", "cvv", "cvc", "pin")
