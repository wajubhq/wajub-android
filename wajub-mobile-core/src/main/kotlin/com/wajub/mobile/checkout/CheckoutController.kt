package com.wajub.mobile.checkout

import com.wajub.mobile.WajubSession
import com.wajub.mobile.mapper.OutcomeMapper
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.mapper.str
import com.wajub.mobile.mobilemoney.MobileMoney
import com.wajub.mobile.mobilemoney.MobileMoneyRules
import com.wajub.mobile.mobilemoney.OperatorChoice
import com.wajub.mobile.model.ErrorCatalog
import com.wajub.mobile.model.PayerOtpInstructions
import com.wajub.mobile.model.PaymentStatus
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessRequest
import com.wajub.mobile.model.ProcessingKind
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.StepKind
import com.wajub.mobile.model.StepResult
import com.wajub.mobile.model.UNSUPPORTED_ACTION_CODE
import com.wajub.mobile.model.WajubError
import com.wajub.mobile.model.WajubLanguage
import com.wajub.mobile.realtime.PaymentWatcher
import com.wajub.mobile.realtime.PusherConnector
import com.wajub.mobile.realtime.RealtimeConnector
import com.wajub.mobile.realtime.WatchEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random

/**
 * The payment sheet without its UI: it runs the payment, fires the callbacks
 * and ends with a [CheckoutResult]. `PaymentSheet` draws [screen] and forwards
 * the payer's actions; an app can draw its own UI on it the same way.
 *
 * Every method must be called on [scope]'s thread (the main thread), where the
 * callbacks run too. [start] once; the result arrives exactly once, through
 * `callbacks.onComplete`, [onFinished] and [result].
 */
class CheckoutController internal constructor(
    val session: WajubSession,
    val options: PresentOptions,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random,
    private val connector: RealtimeConnector,
    private val compute: CoroutineDispatcher,
    private val deviceLanguage: () -> String?,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    constructor(session: WajubSession, options: PresentOptions, scope: CoroutineScope) : this(
        session, options, scope, ::monotonicMillis, Random.Default, PusherConnector, Dispatchers.Default,
        { Locale.getDefault().toLanguageTag() },
    )

    /**
     * The callbacks the sheet fires: [PresentOptions.callbacks] at first. A UI
     * that outlives its screen (a configuration change) sets the new screen's.
     */
    @Volatile
    var callbacks: CheckoutCallbacks = options.callbacks

    private val _screen = MutableStateFlow<SheetScreen>(SheetScreen.Loading)

    /** What to draw. */
    val screen: StateFlow<SheetScreen> = _screen.asStateFlow()

    private val _language = MutableStateFlow(WajubLanguage.resolve(options.locale, null, deviceLanguage()))

    /** The sheet's language. */
    val language: StateFlow<WajubLanguage> = _language.asStateFlow()

    private val _session = MutableStateFlow<SessionData?>(null)

    /** The loaded session (amount, merchant branding), once it is. */
    val sessionData: StateFlow<SessionData?> = _session.asStateFlow()

    private val _closing = MutableStateFlow(false)

    /** The sheet is going away (dismissed, or done): the UI hides while the result settles. */
    val closing: StateFlow<Boolean> = _closing.asStateFlow()

    private val _cancellable = MutableStateFlow(false)

    /** "Cancel payment" can be offered: the payment is loaded and no payment attempt is under way. */
    val cancellable: StateFlow<Boolean> = _cancellable.asStateFlow()

    private val _clientSession = MutableStateFlow<String?>(null)

    /** The provider's page the payer left for, if any (save it to finish the payment after the process dies). */
    val pendingClientSession: StateFlow<String?> = _clientSession.asStateFlow()

    private val _result = MutableStateFlow<CheckoutResult?>(null)

    /** The result, once the sheet has closed. */
    val result: StateFlow<CheckoutResult?> = _result.asStateFlow()

    /** The state last reported to `onStateChange`. */
    var state: CheckoutState? = null
        private set

    /** Opens a URL in the system browser; false when none can. Set by the UI. */
    var urlOpener: ((String) -> Boolean)? = null

    /** Called once with the result, after `callbacks.onComplete`. */
    var onFinished: ((CheckoutResult) -> Unit)? = null

    /**
     * Every answer to a payment request, before the sheet acts on it;
     * [completion] is true for the answer read once the payer is back from a
     * provider's page.
     */
    var onAnswer: ((outcome: ProcessOutcome, completion: Boolean) -> Unit)? = null

    /** The payment as last seen (from the session, an answer, or the status). */
    val transaction: SessionTransaction? get() = lastTransaction

    /**
     * What the payer pays on [screen], in major units of the payment's currency:
     * on a form and on the code screen, the price of the operator or method
     * chosen (the payer's fee may depend on it); elsewhere the payment's amount,
     * which is what was charged once a payment request is answered.
     */
    fun amountDue(screen: SheetScreen = _screen.value): Double {
        val form = when (screen) {
            is SheetScreen.Form -> screen
            // The code is generated for the price of the channel it pays on.
            is SheetScreen.PayerOtp -> payerOtpRequest?.form ?: return selectionBase()
            is SheetScreen.Methods -> return selectionBase()
            else -> return lastTransaction?.payableAmount ?: 0.0
        }
        form.mobileMoney?.let { return it.selectedChoice?.amountTotal ?: selectionBase() }
        return form.method.amountTotal ?: selectionBase()
    }

    // While the payer chooses: the payment's amount as it loaded, not the price of an attempt that ended.
    private fun selectionBase(): Double =
        (_session.value?.transaction ?: lastTransaction)?.payableAmount ?: 0.0

    private val lang: WajubLanguage get() = _language.value
    private var started = false
    private var ready = false
    private var finished = false
    private var sdkConfig: SdkConfig? = null
    private var methods: List<PaymentMethod> = emptyList()
    private var method: PaymentMethod? = null
    private val forms = mutableMapOf<String, SheetScreen.Form>()
    private var lastTransaction: SessionTransaction? = null

    // An attempt whose outcome is not known yet: a dismiss resolves `processing`.
    private var attemptInFlight = false
    private var paymentStarted = false
    private var attemptChannel: String? = null
    private var lastUrl: String? = null
    private var leftForBrowser = false
    private var clientSessionId: String?
        get() = _clientSession.value
        set(value) {
            _clientSession.value = value
        }

    // From the Pay press until a definitive answer (a refusal, a decided status):
    // the payment must not be cancelled then (README §14).
    private var undecided = false

    // Each press of Pay; an answer for an older one is dropped.
    private var attemptSeq = 0
    // `/pay/client-session/complete` calls in flight (one per return from the PSP's page).
    private var completions = 0
    private var heldFailure: WajubError? = null
    // When the attempt started (the Pay press, or the load of a payment already under way):
    // the 15 minutes of watching count from here.
    private var attemptStartedAt: Long? = null
    private var knownErrorCode: String? = null
    private var errorCodeBeforeAttempt: String? = null
    // Whether the refusal left on the payment is known: an older API's session
    // leaves `error_code` out, and only a status read tells it then.
    private var errorCodeKnown = true
    private var errorCodeBeforeAttemptKnown = true
    private var threeDsExpired = false
    // A solution is good for one request: the next one solves a new challenge.
    private var altcha: Deferred<AltchaSolution?>? = null
    private var altchaReloaded = false
    private var resubmit: (suspend () -> Unit)? = null

    // A request that got no answer: its key is reused by the next Pay (spec §2.5).
    private var unansweredKey: String? = null
    private var unansweredCheck: Job? = null
    private var lastSeen: PaymentStatus? = null
    private var sawProcessing = false
    private var cancelling: CancelReason? = null
    private var hostLeftDuringCancel = false

    // The Mobile Money request a code the payer generates goes with (README §6.7),
    // and whether the code of this attempt was sent.
    private var payerOtpRequest: PayerOtpRequest? = null
    private var payerOtpSent = false

    // Runs the cancel: it must settle the sheet even when the host's scope is gone.
    private val detached = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())

    private var loadJob: Job? = null
    private var readyTimer: Job? = null
    private var watcher: PaymentWatcher? = null
    private var watchJob: Job? = null
    private var autoClose: Job? = null
    private var stepTimer: Job? = null

    internal val isAlive: Boolean get() = !finished && scope.coroutineContext[Job]?.isActive != false

    /** The controller's clock (monotonic milliseconds), for countdowns such as [SheetScreen.Step.resendAt]. */
    fun now(): Long = clock()

    /**
     * A client session the payer had left for before the process died: once
     * the session reloads as `processing`, it is completed on return.
     */
    fun restoreClientSession(id: String) {
        if (!started) {
            clientSessionId = id
            leftForBrowser = true
        }
    }

    /** Loads the session and shows the first screen. */
    fun start() {
        if (started) return
        started = true
        if (scope.coroutineContext[Job]?.isActive == false) {
            // The host is already gone: nothing was shown, nothing to cancel.
            finish(CheckoutResult.Cancelled(CancelReason.HostGone, null), release = false)
            return
        }
        if (!ActiveSheet.claim(this)) {
            // Another sheet is open: this one rejects at once and leaves it alone.
            finish(CheckoutResult.Failed(WajubError.of("sheet_already_presented", lang), null), release = false)
            return
        }
        // The install id is read now, off the main thread, not when the payer presses Pay.
        session.client.warmUp()
        load()
    }

    /** Retry of a load error (`load_error`, `ready_timeout`): the payment loads again. */
    fun retryLoad() {
        val current = _screen.value
        if (finished || current !is SheetScreen.LoadError || !current.error.retryable) return
        load()
    }

    // ── Loading (spec §4) ────────────────────────────────────────────────

    private fun load() {
        loadJob?.cancel()
        readyTimer?.cancel()
        if (!ready) _screen.value = SheetScreen.Loading
        loadJob = scope.launch {
            val config = async { runCatching { session.getSdkConfig() }.getOrNull() }
            val loaded = try {
                session.session(forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WajubError) {
                config.cancel()
                loadFailed(loadError(e))
                return@launch
            } catch (_: Exception) {
                config.cancel()
                loadFailed(WajubError.of("load_error", lang))
                return@launch
            }
            onLoaded(loaded, config)
        }
        if (options.readyTimeout > 0) {
            readyTimer = scope.launch {
                delay(options.readyTimeout)
                if (!ready && !finished) {
                    // The requests keep running: data arriving later still shows the sheet.
                    val error = WajubError.of(
                        "ready_timeout", lang,
                        apiMessage = "Payment sheet not ready after ${options.readyTimeout} ms",
                    )
                    _screen.value = SheetScreen.LoadError(error)
                    fire { callbacks.onLoadError?.invoke(error) }
                }
            }
        }
    }

    // 401/403/404 and 410 keep their code; anything else is a retryable load_error.
    private fun loadError(e: WajubError): WajubError =
        when (e.code) {
            "session_not_found", "session_terminal_expired" -> e.localized(lang)
            else -> WajubError.of("load_error", lang, apiMessage = e.apiMessage ?: e.message)
        }

    private fun loadFailed(error: WajubError) {
        if (finished) return
        readyTimer?.cancel()
        _screen.value = SheetScreen.LoadError(error)
        fire { callbacks.onLoadError?.invoke(error) }
    }

    private suspend fun onLoaded(loaded: SessionData, config: Deferred<SdkConfig?>) {
        _session.value = loaded
        lastTransaction = loaded.transaction
        // A refusal an earlier presentation left on the payment is not news to this one.
        knownErrorCode = loaded.transaction.errorCode
        errorCodeKnown = loaded.transaction.errorCodeReported
        _language.value = WajubLanguage.resolve(options.locale, loaded.sessionLocale, deviceLanguage())
        session.language = lang
        val txn = loaded.transaction
        when (txn.status.lowercase()) {
            in OutcomeMapper.SUCCESS_STATUSES -> {
                config.cancel()
                becomeReady()
                success(txn, "unknown")
                return
            }
            "failed" -> return ended(config, "session_terminal_failed", CheckoutState.FAILED)
            "cancelled" -> return ended(config, "session_terminal_cancelled", CheckoutState.CANCELLED)
            "expired" -> return ended(config, "session_terminal_expired", CheckoutState.EXPIRED)
        }
        loaded.altcha?.let { challenge -> altcha = scope.async(compute) { Altcha.solve(challenge) } }
        if (txn.status.lowercase() in setOf("processing", "partial")) {
            // An attempt under way: its screen from the session alone, at once. sdk-config
            // (for a later Try again) arrives when it does, or never.
            methods = PaymentMethods.build(loaded.channels, null, txn.sandbox, lang)
            becomeReady()
            restore(txn)
            scope.launch {
                val late = config.await() ?: return@launch
                if (finished) return@launch
                sdkConfig = late
                methods = PaymentMethods.build(loaded.channels, late, txn.sandbox, lang)
                method = method?.let { m -> methods.firstOrNull { it.id == m.id } } ?: method
            }
            return
        }
        sdkConfig = config.await()
        // The Mobile Money tables are read once, off the main thread.
        withContext(compute) { MobileMoney.data() }
        methods = PaymentMethods.build(loaded.channels, sdkConfig, txn.sandbox, lang)
        if (methods.isEmpty()) {
            // Without sdk-config (its call failed) nothing says no method exists: Retry may find one.
            loadFailed(WajubError.of(if (sdkConfig == null) "load_error" else "no_payment_method", lang))
            return
        }
        becomeReady()
        showMethodsOrForm()
    }

    private fun ended(config: Deferred<SdkConfig?>, code: String, state: CheckoutState) {
        config.cancel()
        if (finished) return
        val error = WajubError.of(code, lang)
        becomeReady()
        show(SheetScreen.Ended(error, state))
        fire { callbacks.onLoadError?.invoke(error) }
    }

    private fun becomeReady() {
        readyTimer?.cancel()
        if (ready) return
        ready = true
        val txn = lastTransaction ?: return
        val event = ReadyEvent(
            sessionId = txn.id,
            environment = if (txn.sandbox) "sandbox" else "live",
            amount = txn.payableAmount,
            currency = txn.currency,
            methods = methods.flatMap { m -> m.channels.map { it.slug } },
        )
        fire { callbacks.onReady?.invoke(event) }
    }

    // processing / partial at load: restore the screen of the attempt in flight (spec §4.2).
    private fun restore(txn: SessionTransaction) {
        val context = txn.processingContext
        method = methods.firstOrNull { m -> m.channels.any { it.slug == context?.channelSlug || it.operators.any { op -> op.slug == context?.channelSlug } } }
        attemptInFlight = true
        undecided = true
        attemptStartedAt = clock()
        attemptChannel = context?.channelSlug
        lastUrl = context?.actionUrl
        when {
            context?.isMobileMoney == true -> show(SheetScreen.Approval(context.payerInstruction, phone = null))
            context?.isUssd == true -> show(SheetScreen.Ussd(context.payerInstruction))
            context?.threeDsId != null -> showStep(StepKind.ThreeDs, context.threeDsId, context.maskedEmail, context.threeDsTimeout)
            else -> show(SheetScreen.Pending(url = context?.actionUrl))
        }
        watch(seenProcessing = true)
    }

    // ── Methods and forms ────────────────────────────────────────────────

    private fun showMethodsOrForm() {
        val only = methods.singleOrNull()
        if (only != null) {
            method = only
            show(formFor(only))
        } else {
            method = null
            show(SheetScreen.Methods(methods))
        }
    }

    /** Opens a method's form. */
    fun selectMethod(id: String) {
        if (!canEditForm()) return
        val chosen = methods.firstOrNull { it.id == id } ?: return
        // The limits take what the payer pays with this method.
        val amount = chosen.amountTotal ?: selectionBase()
        if (chosen.outOfLimits(amount)) return
        method = chosen
        show(forms[chosen.id]?.copy(busy = false, banner = null) ?: formFor(chosen))
    }

    /** Back to the method list ("Change"). */
    fun changeMethod() {
        if (!canEditForm() || methods.size < 2) return
        method = null
        show(SheetScreen.Methods(methods))
    }

    private fun canEditForm(): Boolean {
        val current = _screen.value
        return !finished && cancelling == null && (current is SheetScreen.Methods || (current is SheetScreen.Form && !current.busy))
    }

    private fun formFor(m: PaymentMethod): SheetScreen.Form {
        val canChange = methods.size > 1
        return when (m.family) {
            "mobile_money" -> {
                val data = _session.value
                val country = MobileMoneyRules.defaultCountry(m.channels, options.mobileMoney.country, data?.geoCountry)
                val choices = choicesFor(m, country)
                SheetScreen.Form(
                    method = m,
                    mobileMoney = MobileMoneyForm(
                        countries = MobileMoneyRules.countries(m.channels),
                        country = country,
                        countryLocked = options.mobileMoney.countryLocked && options.mobileMoney.country != null,
                        choices = choices,
                        selected = MobileMoneyRules.selection(enabledIds(choices), null, false, null),
                        operatorMode = options.mobileMoney.operatorMode,
                        unavailable = unavailable(choices),
                    ),
                    canChangeMethod = canChange,
                )
            }
            "card" -> {
                val flavor = m.cardFlavor ?: CardFlavor.Hidden
                val fields = when (flavor) {
                    is CardFlavor.ClientSession -> flavor.requiredFields
                    is CardFlavor.HostedRedirect -> flavor.requiredFields
                    CardFlavor.SandboxForm -> CardForm.SANDBOX_FIELDS
                    is CardFlavor.Stripe -> listOf("card_holder")
                    CardFlavor.Hidden -> emptyList()
                }
                val customer = lastTransaction?.customer
                val prefill = buildMap {
                    customer?.email?.let { if ("email" in fields) put("email", it) }
                    customer?.name?.let { if ("card_holder" in fields) put("card_holder", it) }
                }
                SheetScreen.Form(m, card = CardForm(flavor, fields, prefill), canChangeMethod = canChange)
            }
            else -> SheetScreen.Form(m, canChangeMethod = canChange)
        }
    }

    // sdk-config may turn one member of an aggregate off.
    private fun choicesFor(m: PaymentMethod, country: String?): List<OperatorChoice> =
        MobileMoneyRules.choices(m.channels, country, lang).filter { choice ->
            val channel = m.channels.firstOrNull { it.slug == choice.channelSlug } ?: return@filter true
            sdkConfig == null || PaymentMethods.entryFor(channel, sdkConfig, choice.id)?.available != false
        }

    private fun enabledIds(choices: List<OperatorChoice>): List<String> =
        choices.filterNot { outOfLimits(it) || exhausted(it) }.map { it.id }

    /** Whether [choice] cannot pay [amount] (its channel's limits): by default, what the payer pays on it. */
    fun outOfLimits(choice: OperatorChoice, amount: Double = choice.amountTotal ?: selectionBase()): Boolean =
        (choice.minAmount != null && amount < choice.minAmount) || (choice.maxAmount != null && amount > choice.maxAmount)

    private fun updateMobileMoney(change: (MobileMoneyForm) -> MobileMoneyForm) {
        val current = _screen.value as? SheetScreen.Form ?: return
        val form = current.mobileMoney ?: return
        if (current.busy || finished) return
        show(current.copy(mobileMoney = change(form), banner = null))
    }

    /** The country of the Mobile Money form: its operators follow it. */
    fun setCountry(code: String) = updateMobileMoney { form ->
        val m = method ?: return@updateMobileMoney form
        val choices = choicesFor(m, code)
        val detected = MobileMoney.data().country(code)?.let { MobileMoney.detectOperator(form.phone, it, sandbox) }
        form.copy(
            country = code.uppercase(),
            choices = choices,
            pickedByHand = false,
            detected = detected,
            selected = MobileMoneyRules.selection(enabledIds(choices), null, false, detected),
            phoneError = null,
            operatorError = null,
            unavailable = unavailable(choices),
        )
    }

    /** The number as typed: detection preselects its operator until the payer picks one. */
    fun setPhone(text: String) = updateMobileMoney { form ->
        // Digits only, as the field shows them (a `+` is dropped, as on the web).
        val phone = asciiDigits(text).take(15)
        val detected = form.countryInfo?.let { MobileMoney.detectOperator(phone, it, sandbox) }
        form.copy(
            phone = phone,
            detected = detected,
            selected = MobileMoneyRules.selection(enabledIds(form.choices), form.selected, form.pickedByHand, detected),
            phoneError = null,
        )
    }

    /** The payer picked an operator: never overridden by detection. */
    fun pickOperator(id: String) = updateMobileMoney { form ->
        val choice = form.choices.firstOrNull { it.id == id }
        if (choice == null || outOfLimits(choice) || exhausted(choice)) {
            form
        } else {
            form.copy(selected = id, pickedByHand = true, operatorError = null, phoneError = null)
        }
    }

    /** Shows the operator list (`operatorMode: detect`). */
    fun openOperatorList() = updateMobileMoney { it.copy(listOpened = true) }

    /** A card form field (`email`, `card.number`, `card_holder`…). */
    fun setCardField(field: String, value: String) {
        val current = _screen.value as? SheetScreen.Form ?: return
        val card = current.card ?: return
        if (current.busy || finished) return
        // Digits typed in another script are read as 0-9; the card's numbers keep their digits only.
        val clean = if (field in CARD_NUMBER_FIELDS) asciiDigits(value) else asciiDigitsKeepingText(value)
        show(current.copy(card = card.copy(values = card.values + (field to clean), errors = card.errors - field), banner = null))
    }

    // ── Paying ───────────────────────────────────────────────────────────

    /** Pay with the Mobile Money form, once its number and operator pass their checks. */
    fun payMobileMoney() {
        val current = _screen.value as? SheetScreen.Form ?: return
        val form = current.mobileMoney ?: return
        if (current.busy || finished) return
        val country = form.countryInfo
        if (form.choices.isEmpty() || country == null) {
            show(current.copy(mobileMoney = form.copy(operatorError = message("operator_unavailable"))))
            return
        }
        val labels = form.choices.associate { it.id to it.label }
        val issue = MobileMoneyRules.validate(form.phone, country, form.choices.map { it.id }, form.selected, { labels[it] ?: it }, sandbox)
        if (issue != null) {
            val text = message(issue.code, issue.args)
            val updated = if (issue.code == "operator_required") form.copy(operatorError = text) else form.copy(phoneError = text)
            show(current.copy(mobileMoney = updated))
            return
        }
        val choice = form.selectedChoice ?: return
        if (cancelling != null) return
        exhaustedText(choice)?.let { text ->
            // Too many codes refused there: never the code screen again on that channel.
            show(current.copy(mobileMoney = form.copy(operatorError = text)))
            return
        }
        val data = MobileMoneyRules.requestData(choice, form.phone, country, form.pickedByHand, sandbox)
        val request = PayerOtpRequest(current, choice.channelSlug, data, exhaustedKey(choice))
        val announced = choice.payerOtp
        if (announced != null) {
            // The channel takes the payer's own code: it is asked for first, and goes with the
            // first payment request. Nothing is sent yet.
            forms[current.method.id] = current
            payerOtpRequest = request
            payerOtpSent = false
            showPayerOtp(announced, request)
            return
        }
        submit(current, choice.channelSlug, payerOtp = request) { solution, key ->
            session.process(ProcessRequest(choice.channelSlug, withExtras(data, solution), key))
        }
    }

    // ── The payer's own code (README §6.7) ───────────────────────────────

    private class PayerOtpRequest(
        val form: SheetScreen.Form,
        val channel: String,
        val data: Map<String, Any?>,
        /** The operator paid on, as [exhaustedChannels] keys it. */
        val choiceKey: String,
    ) {
        var instructions: PayerOtpInstructions? = null
    }

    // The operators whose codes were refused too many times for this payment, with that error's text.
    private val exhaustedChannels = mutableMapOf<String, String>()

    private fun exhaustedKey(choice: OperatorChoice): String = "${choice.channelSlug}/${choice.id}"

    // Its operator, or (a refusal no code of this sheet led to) its whole channel.
    private fun exhaustedText(choice: OperatorChoice): String? =
        exhaustedChannels[exhaustedKey(choice)] ?: exhaustedChannels["${choice.channelSlug}/*"]

    private fun exhausted(choice: OperatorChoice): Boolean = exhaustedText(choice) != null

    private fun unavailable(choices: List<OperatorChoice>): Map<String, String> =
        choices.mapNotNull { choice -> exhaustedText(choice)?.let { choice.id to it } }.toMap()

    private fun showPayerOtp(instructions: PayerOtpInstructions, request: PayerOtpRequest, error: String? = null, attemptsRemaining: Int? = null) {
        request.instructions = instructions
        show(
            SheetScreen.PayerOtp(
                instructions = instructions,
                error = error,
                attemptsRemaining = attemptsRemaining,
                sandboxCode = if (sandbox) PayerOtpTexts.SANDBOX_CODE else null,
            ),
        )
    }

    /** The code the payer generated, as typed: digits only, at most its length. */
    fun setPayerOtpCode(text: String) {
        val current = _screen.value as? SheetScreen.PayerOtp ?: return
        if (current.busy || finished) return
        show(current.copy(code = asciiDigits(text).take(current.instructions.codeLengths.last), error = null))
    }

    /** "Confirm payment": the payment request again, with the payer's code. */
    fun submitPayerOtp() {
        val current = _screen.value as? SheetScreen.PayerOtp ?: return
        val request = payerOtpRequest ?: return
        if (current.busy || finished || cancelling != null) return
        val code = current.code
        // A code of the wrong length is not sent.
        if (code.length !in current.instructions.codeLengths || code.any { it !in '0'..'9' }) {
            show(current.copy(error = PayerOtpTexts.length(current.instructions, lang)))
            return
        }
        val data = request.data + ("otp" to code)
        submit(request.form, request.channel, busy = current.copy(busy = true, error = null), payerOtp = request, payerCode = true) { solution, key ->
            session.process(ProcessRequest(request.channel, withExtras(data, solution), key))
        }
    }

    /** "Use another payment method": back to the Mobile Money form. */
    fun leavePayerOtp() {
        val current = _screen.value as? SheetScreen.PayerOtp ?: return
        val request = payerOtpRequest ?: return
        if (current.busy || finished || cancelling != null) return
        payerOtpSent = false
        show(forms[request.form.method.id]?.copy(busy = false, banner = null) ?: request.form.copy(busy = false, banner = null))
    }

    // `action: payer_otp`: nothing was charged; the code is asked for, or asked again.
    private fun onPayerOtpAsked(instructions: PayerOtpInstructions?) {
        attemptInFlight = false
        undecided = false
        payerOtpSent = false
        val request = payerOtpRequest
        if (instructions == null || request == null) {
            showFailed(WajubError.of("unsupported_action", lang))
            return
        }
        when (instructions.reason) {
            "invalid" -> showPayerOtp(instructions, request, error = PayerOtpTexts.length(instructions, lang))
            "refused" -> showPayerOtp(instructions, request, error = PayerOtpTexts.refused(lang), attemptsRemaining = instructions.attemptsRemaining)
            else -> showPayerOtp(instructions, request)
        }
    }

    // The operator refused too many codes: that operator is not offered again for this
    // payment, and the payer chooses another way to pay.
    private fun onPayerOtpExhausted(error: WajubError, form: SheetScreen.Form?) {
        unansweredKey = null
        stopWatching()
        refusedForGood(null)
        payerOtpSent = false
        (payerOtpRequest?.choiceKey ?: attemptChannel?.let { "$it/*" })?.let { exhaustedChannels[it] = error.message }
        backToMobileMoney(error.message, payerOtpRequest?.form ?: form)
        fire { callbacks.onError?.invoke(error) }
    }

    // A `pending` status refusing the payer's own code: the operator refused it later (back to the
    // code screen; without a code from this sheet, the form), or refused too many. False for any other.
    private fun onPayerOtpRefusal(status: PaymentStatus, error: WajubError): Boolean {
        if (status.status != "pending") return false
        val request = payerOtpRequest
        return when (status.errorCode) {
            "invalid_otp" -> {
                val instructions = request?.instructions
                if (payerOtpSent && request != null && instructions != null) {
                    stopWatching()
                    refusedForGood(null)
                    payerOtpSent = false
                    // How many tries are left is not known here.
                    showPayerOtp(instructions, request, error = PayerOtpTexts.refused(lang))
                } else {
                    onPayerOtpRefusedElsewhere()
                }
                true
            }
            OTP_ATTEMPTS_EXCEEDED -> {
                onPayerOtpExhausted(error, request?.form)
                true
            }
            else -> false
        }
    }

    // A code refused while none was sent from this sheet (sent from elsewhere): the form, with the refusal.
    private fun onPayerOtpRefusedElsewhere() {
        unansweredKey = null
        stopWatching()
        refusedForGood(null)
        backToMobileMoney(PayerOtpTexts.refused(lang), null)
    }

    // The Mobile Money form with [banner], its unavailable operators disabled.
    private fun backToMobileMoney(banner: String, form: SheetScreen.Form?) {
        val m = form?.method ?: methods.firstOrNull { it.family == "mobile_money" }
        val base = m?.let { forms[it.id] ?: form ?: formFor(it) }
        if (m == null || base == null) {
            showFailed(WajubError.of("payment_attempt_ended", lang))
            return
        }
        method = m
        val momo = base.mobileMoney?.let { momo ->
            val selected = momo.selectedChoice?.takeIf { !exhausted(it) }?.id
                ?: MobileMoneyRules.selection(enabledIds(momo.choices), null, false, momo.detected)
            momo.copy(
                unavailable = unavailable(momo.choices),
                selected = selected,
                pickedByHand = momo.pickedByHand && selected == momo.selected,
            )
        }
        val back = base.copy(mobileMoney = momo, busy = false, banner = banner)
        forms[m.id] = back.copy(banner = null)
        show(back)
    }

    /** Pay a one-tap wallet (Djamo): nothing to collect. */
    fun payWallet() {
        val current = _screen.value as? SheetScreen.Form ?: return
        if (current.busy || finished || current.method.family != "wallet") return
        val slug = current.method.slug
        submit(current, slug) { solution, key ->
            session.process(ProcessRequest(slug, withExtras(emptyMap(), solution), key))
        }
    }

    /**
     * Pay with Stripe's card widget: [createPaymentMethod] tokenizes the card
     * (`pm_…`) once the attempt has started; its error message shows on the form.
     */
    fun payStripe(createPaymentMethod: suspend () -> String) {
        val current = _screen.value as? SheetScreen.Form ?: return
        if (current.busy || finished || current.card?.flavor !is CardFlavor.Stripe) return
        val holder = current.card.values["card_holder"]?.trim()?.takeIf { it.isNotBlank() }
        submit(current, current.method.slug) { solution, key ->
            val id = try {
                createPaymentMethod()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureOpen()
                throw CardNotTokenized(e.message ?: message("validation_error"))
            }
            ensureOpen()
            val data = buildMap<String, Any?> {
                put("payment_method_id", id)
                holder?.let { put("card_holder", it) }
            }
            session.process(ProcessRequest(current.method.slug, withExtras(data, solution), key))
        }
    }

    /** Pay the card form of a client session, hosted redirect or sandbox test card. */
    fun payCard() {
        val current = _screen.value as? SheetScreen.Form ?: return
        val card = current.card ?: return
        if (current.busy || finished) return
        val errors = card.fields.mapNotNull { field -> cardFieldError(card.flavor, field, card.values[field])?.let { field to it } }.toMap()
        if (errors.isNotEmpty()) {
            show(current.copy(card = card.copy(errors = errors)))
            return
        }
        val slug = current.method.slug
        val values = card.fields.associateWith { field ->
            val v = card.values[field].orEmpty().trim()
            if (field == "country") v.uppercase() else v
        }
        when (card.flavor) {
            is CardFlavor.ClientSession -> submit(current, slug) { solution, _ ->
                session.openClientSession(values, options.returnUrl, restart = false, altcha = solution, channel = slug)
            }
            is CardFlavor.HostedRedirect -> submit(current, slug) { solution, key ->
                session.process(ProcessRequest(slug, withExtras(values, solution), key))
            }
            // The test cards exist in sandbox only: raw card data never goes out in live.
            CardFlavor.SandboxForm -> if (lastTransaction?.sandbox == true) {
                submit(current, slug) { solution, key -> session.process(ProcessRequest(slug, withExtras(values, solution), key)) }
            }
            else -> Unit
        }
    }

    private fun cardFieldError(flavor: CardFlavor, field: String, value: String?): String? {
        val v = value?.trim().orEmpty()
        if (flavor is CardFlavor.Stripe) return null
        if (v.isEmpty()) return message("field_required")
        if (field in CARD_NUMBER_FIELDS && v.any { it !in '0'..'9' }) return message("field_required")
        return when {
            field == "email" && PaymentMapper.hostedCardFieldError("email", v) != null -> message("invalid_email")
            field == "country" && PaymentMapper.hostedCardFieldError("country", v) != null -> message("field_required")
            else -> null
        }
    }

    // `return_url` and the Altcha solution, sent in `data` of /pay/process.
    private fun withExtras(data: Map<String, Any?>, solution: AltchaSolution?): Map<String, Any?> {
        return buildMap {
            putAll(data)
            options.returnUrl?.takeIf { it.isNotBlank() }?.let { put("return_url", it) }
            solution?.let { put("altcha", it.toMap()) }
        }
    }

    // One press of Pay: the request and its answer. Its Idempotency-Key is new,
    // unless the previous request got no answer at all.
    // The attempt is in flight from the press (Altcha solving and card
    // tokenizing included): a dismiss then resolves `processing`, and nothing
    // is sent once the sheet has closed.
    private fun submit(
        form: SheetScreen.Form,
        channel: String,
        busy: SheetScreen = form.copy(busy = true, banner = null),
        payerOtp: PayerOtpRequest? = null,
        payerCode: Boolean = false,
        request: suspend (AltchaSolution?, String) -> ProcessOutcome,
    ) {
        if (cancelling != null || finished) return
        payerOtpRequest = payerOtp
        payerOtpSent = payerCode
        attemptSeq++
        undecided = true
        attemptStartedAt = clock()
        errorCodeBeforeAttempt = knownErrorCode
        errorCodeBeforeAttemptKnown = errorCodeKnown
        heldFailure = null
        threeDsExpired = false
        forms[form.method.id] = form
        paymentStarted = false
        attemptChannel = channel
        lastUrl = null
        attemptInFlight = true
        altchaReloaded = false
        lastSeen = null
        sawProcessing = false
        show(busy)
        // Where the payer is back when nothing could be sent.
        val idle = { text: String? ->
            if (busy is SheetScreen.PayerOtp) busy.copy(busy = false, error = text) else form.copy(busy = false, banner = text)
        }
        val run: suspend (String) -> Unit = run@{ key ->
            val solution = try {
                takeAltcha()
            } catch (failure: AltchaUnavailable) {
                // Nothing was sent: the next Pay solves a new challenge.
                if (finished) return@run
                attemptInFlight = false
                undecided = false
                show(idle(failure.error.message))
                fire { callbacks.onError?.invoke(failure.error) }
                return@run
            }
            if (finished) return@run
            val outcome = try {
                request(solution, key)
            } catch (_: SheetClosed) {
                return@run
            } catch (failure: CardNotTokenized) {
                // Nothing was sent.
                attemptInFlight = false
                undecided = false
                show(idle(failure.message))
                return@run
            }
            handleOutcome(outcome, form, key)
        }
        resubmit = { run(UUID.randomUUID().toString()) }
        // The payer's code changes the request: a new key, whatever came before.
        val key = unansweredKey?.takeIf { !payerCode } ?: UUID.randomUUID().toString()
        scope.launch { run(key) }
    }

    /** Throws when the sheet has closed: the last check before a request leaves. */
    private fun ensureOpen() {
        if (finished) throw SheetClosed()
    }

    private class SheetClosed : Exception()

    private class CardNotTokenized(message: String) : Exception(message)

    // Altcha is on but no solution came: the request is not sent.
    private class AltchaUnavailable(val error: WajubError) : Exception(error.message)

    // The prepared solution once; after that, a new challenge (a session reload) each time.
    // With Altcha on, a request never leaves without its solution.
    private suspend fun takeAltcha(): AltchaSolution? {
        altcha?.let { prepared ->
            altcha = null
            return prepared.await() ?: throw AltchaUnavailable(WajubError.of("security_check_failed", lang))
        }
        // After an Altcha refusal the session is reloaded even if it had no challenge.
        if (_session.value?.altcha == null && !altchaReloaded) return null
        val challenge = try {
            session.session(forceRefresh = true).altcha
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AltchaUnavailable(WajubError.of("network_error", lang))
        } ?: return null
        return scope.async(compute) { Altcha.solve(challenge) }.await()
            ?: throw AltchaUnavailable(WajubError.of("security_check_failed", lang))
    }

    private fun family(): String = method?.family ?: "unknown"

    // [text] with its decimal digits as 0-9, whatever script they were typed in; the rest as typed.
    // By code point: some scripts' digits lie outside the BMP (Adlam, U+1E950…).
    private fun asciiDigitsKeepingText(text: String): String = buildString {
        text.codePoints().forEach { cp ->
            val digit = Character.digit(cp, 10)
            if (digit >= 0) append('0' + digit) else appendCodePoint(cp)
        }
    }

    // The decimal digits of [text] as 0-9, whatever script the keyboard typed them in (٠٧ -> 07).
    private fun asciiDigits(text: String): String = MobileMoney.sanitize(text)

    // A sandbox session: its test numbers are valid and test their own operator.
    private val sandbox: Boolean get() = (_session.value?.transaction ?: lastTransaction)?.sandbox == true

    private fun started() {
        attemptInFlight = true
        if (paymentStarted) return
        paymentStarted = true
        val event = PaymentStartedEvent(family(), attemptChannel ?: method?.slug ?: "")
        fire { callbacks.onPaymentStarted?.invoke(event) }
    }

    // The answer of a payment request (spec §9).
    private suspend fun handleOutcome(outcome: ProcessOutcome, form: SheetScreen.Form, key: String) {
        if (finished) return
        fire { onAnswer?.invoke(outcome, false) }
        val unansweredSince = (outcome as? ProcessOutcome.Processing)?.unansweredSince
        unansweredKey = if (unansweredSince != null) key else null
        when (outcome) {
            is ProcessOutcome.Complete -> {
                started()
                success(outcome.transaction, family())
            }
            is ProcessOutcome.Processing -> {
                // No transaction: the outcome is unknown (timeout, 5xx, a charge in flight).
                if (outcome.transaction != null) started()
                outcome.transaction?.let { lastTransaction = it }
                val seen = outcome.transaction?.status?.lowercase() in setOf("processing", "partial")
                when (outcome.kind) {
                    ProcessingKind.Approval -> show(SheetScreen.Approval(outcome.instruction, approvalPhone(form), codeReceived = payerOtpSent))
                    ProcessingKind.Ussd -> show(SheetScreen.Ussd(outcome.instruction))
                    ProcessingKind.Pending -> show(SheetScreen.Pending())
                }
                watch(seenProcessing = seen)
                unansweredSince?.let { checkUnanswered(it, form) }
            }
            is ProcessOutcome.RequiresAction -> if (outcome.action == PAYER_OTP_ACTION) {
                // Nothing was charged: the attempt has not started. The refusal this answer leaves
                // on the payment (a refused code) is the baseline: a status still carrying it is not news.
                outcome.transaction?.let {
                    lastTransaction = it
                    if (it.errorCodeReported) {
                        knownErrorCode = it.errorCode
                        errorCodeKnown = true
                    }
                }
                onPayerOtpAsked(outcome.payerOtp)
            } else {
                started()
                outcome.transaction?.let { lastTransaction = it }
                follow(outcome)
            }
            is ProcessOutcome.Failed -> {
                // An action the sheet cannot take still came in a 2xx: the attempt started — unless
                // it asked for the payer's own code, which charged nothing.
                if (outcome.error.code == UNSUPPORTED_ACTION_CODE && outcome.error.httpStatus == null && !outcome.chargedNothing) started()
                onRefused(outcome.error, outcome.transaction, form, chargedNothing = outcome.chargedNothing)
            }
        }
    }

    // A page to open, a step to show, or a client session's page: from a payment
    // answer, or from a confirmation step that chains another one.
    private fun follow(outcome: ProcessOutcome.RequiresAction) {
        val step = outcome.step
        when {
            outcome.action == "client_session" -> {
                val url = outcome.url
                if (url == null) {
                    showFailed(WajubError.of("unsupported_action", lang))
                    return
                }
                clientSessionId = outcome.clientSession?.id
                openPage(url, threeDs = false)
            }
            step != null && outcome.stepId != null -> {
                // The sandbox 3-D Secure code has its timeout; the other steps none.
                showStep(step, outcome.stepId, outcome.hint, outcome.timeoutSeconds)
                watch(seenProcessing = true)
            }
            outcome.url != null -> openPage(outcome.url, threeDs = outcome.action == "confirm_3ds")
            else -> showFailed(WajubError.of("unsupported_action", lang))
        }
    }

    private fun approvalPhone(form: SheetScreen.Form): String? {
        val momo = form.mobileMoney ?: return null
        val country = momo.countryInfo ?: return null
        return MobileMoney.e164(momo.phone, country, sandbox)
    }

    private suspend fun onRefused(error: WajubError, txn: SessionTransaction?, form: SheetScreen.Form, chargedNothing: Boolean = false) {
        attemptInFlight = false
        // A refusal is definitive; an action the sheet cannot take came in a 2xx: still under way
        // (not the payer's own code asked without its instructions: nothing was charged).
        undecided = error.code == UNSUPPORTED_ACTION_CODE && !chargedNothing
        error.declineCode?.let {
            // The refusal now on the payment: known, the baseline of the next attempt.
            knownErrorCode = it
            errorCodeKnown = true
        }
        txn?.let { lastTransaction = it }
        when {
            error.httpStatus == 422 && altchaRefused(error) -> {
                if (altchaReloaded) {
                    showFailed(WajubError.of("security_check_failed", lang))
                    return
                }
                // A new challenge (taking a solution reloads one), once, then the same payment again:
                // the attempt stays undecided across the reload and the resend.
                altchaReloaded = true
                attemptInFlight = true
                undecided = true
                resubmit?.invoke()
            }
            // A 422 without a code of its own is the form's: field errors, stay.
            error.httpStatus == 422 && error.code == "validation_error" -> show(fieldErrors(form, error))
            error.httpStatus == 400 -> {
                // The payment can no longer be processed: the status says why.
                val status = try {
                    session.status().status.takeIf { it.isNotBlank() }
                } catch (_: WajubError) {
                    null
                }
                when (status) {
                    in OutcomeMapper.SUCCESS_STATUSES -> success(lastTransaction?.copy(status = status!!), family())
                    // Still under way (money may move), or unreadable: wait for the outcome.
                    "processing", "partial", null -> {
                        attemptInFlight = true
                        undecided = true
                        show(SheetScreen.Pending(url = lastUrl))
                        watch(seenProcessing = status != null)
                    }
                    "cancelled" -> showEnded(SheetScreen.Cancelled(WajubError.of("payment_cancelled", lang)))
                    "expired" -> showEnded(SheetScreen.Expired(WajubError.of("session_expired", lang)))
                    "failed" -> showFailed(WajubError.of("payment_failed", lang))
                    else -> showFailed(if (error.code == "not_processable") error else WajubError.of("not_processable", lang))
                }
            }
            error.httpStatus == 410 -> showEnded(SheetScreen.Expired(error))
            error.code == OTP_ATTEMPTS_EXCEEDED -> onPayerOtpExhausted(error, payerOtpRequest?.form ?: form)
            error.code == "rate_limited" -> {
                val seconds = error.retryAfterSeconds?.toLong() ?: 0L
                showFailed(error, retryAt = clock() + seconds * 1000)
            }
            else -> showFailed(error)
        }
    }

    // Recognised by its code; an answer without one (an older API) by its message,
    // only when the session has a challenge.
    private fun altchaRefused(error: WajubError): Boolean =
        error.code == "security_check_failed" ||
            _session.value?.altcha != null &&
            ("altcha" in error.details || error.apiMessage?.contains("security challenge", ignoreCase = true) == true)

    // A 422 stays on the form: the phone's own message, field errors, or a banner (spec §9.1).
    private fun fieldErrors(form: SheetScreen.Form, error: WajubError): SheetScreen.Form {
        var momo = form.mobileMoney
        var card = form.card
        var banner: String? = null
        val details = error.details.ifEmpty { mapOf("request" to (error.apiMessage ?: error.message)) }
        for ((key, apiText) in details) {
            val field = key.removePrefix("data.")
            when {
                field in setOf("phone", "account_number") && momo != null -> {
                    val text = if (apiText.contains("belongs to", ignoreCase = true)) mismatchMessage(momo, apiText) else message("invalid_phone")
                    momo = momo.copy(phoneError = text)
                }
                card != null && field in card.fields -> card = card.copy(errors = card.errors + (field to localizedApiText(apiText)))
                else -> banner = banner ?: localizedApiText(apiText)
            }
        }
        return form.copy(mobileMoney = momo, card = card, banner = banner, busy = false)
    }

    // "This phone number belongs to ORANGE (cm.orange) but…": the API names the operator.
    private fun mismatchMessage(form: MobileMoneyForm, apiText: String): String {
        val named = BELONGS_TO.find(apiText)?.groupValues?.get(1)?.lowercase()
        val detected = named ?: form.countryInfo?.let { MobileMoney.detectOperator(form.phone, it, sandbox) }
        val label = { id: String? ->
            form.choices.firstOrNull { it.id == id }?.label
                ?: id?.let { MobileMoney.data().brandLabel(it) ?: it.uppercase() }.orEmpty()
        }
        return message("operator_mismatch", mapOf("detected" to label(detected), "selected" to label(form.selected)))
    }

    // The API's 422 texts are English: a French sheet says "check the information".
    private fun localizedApiText(text: String): String =
        if (lang == WajubLanguage.En) text else message("validation_error")

    private fun openPage(url: String, threeDs: Boolean) {
        fire { callbacks.onRedirect?.invoke(RedirectEvent(url)) }
        val opened = try {
            urlOpener?.invoke(url) ?: false
        } catch (_: Exception) {
            false
        }
        if (!opened) {
            showFailed(WajubError.of("redirect_unavailable", lang))
            return
        }
        lastUrl = url
        leftForBrowser = true
        show(SheetScreen.Pending(url = url, threeDs = threeDs))
        watch(seenProcessing = true)
    }

    /** "Continue payment": reopens the page the payer left. */
    fun continuePayment() {
        val url = lastUrl ?: return
        if (finished || _screen.value !is SheetScreen.Pending) return
        fire { callbacks.onRedirect?.invoke(RedirectEvent(url)) }
        if (urlOpener?.invoke(url) == true) leftForBrowser = true
    }

    /**
     * The payer came back to the app (browser closed, deep link, foreground):
     * the status is read at once, and a client session is completed.
     */
    fun onReturn() {
        if (finished) return
        val current = _screen.value
        if (current is SheetScreen.Pending && current.threeDs) show(current.copy(threeDs = false))
        if (leftForBrowser) {
            leftForBrowser = false
            clientSessionId?.let { id ->
                val seq = attemptSeq
                completions++
                scope.launch {
                    val outcome = try {
                        session.confirmClientSession(id)
                    } finally {
                        completions--
                    }
                    // An answer for an older attempt, or after the outcome, changes nothing.
                    if (finished || seq != attemptSeq || clientSessionId != id) return@launch
                    fire { onAnswer?.invoke(outcome, true) }
                    when (outcome) {
                        is ProcessOutcome.Complete -> success(outcome.transaction, family())
                        is ProcessOutcome.Failed -> {
                            // A decline: the payment is `pending` again (the 402's own transaction says so).
                            refusedForGood(outcome.transaction)
                            showFailed(outcome.error)
                        }
                        // Not paid yet: once no other complete can still say paid, a refusal
                        // the status held back decides; else the PSP's webhook settles it.
                        else -> if (completions == 0) {
                            heldFailure?.let {
                                heldFailure = null
                                refusedForGood(null)
                                showFailed(it)
                            }
                        }
                    }
                }
            }
        }
        watcher?.pollNow()
    }

    // ── Confirmation steps (spec §10) ────────────────────────────────────

    private fun showStep(kind: StepKind, id: String, hint: String?, timeoutSeconds: Int?) {
        val now = clock()
        val expiresAt = timeoutSeconds?.takeIf { it > 0 }?.let { now + it * 1000L }
        show(SheetScreen.Step(kind, id, hint, resendAt = now + RESEND_AFTER_MS, expiresAt = expiresAt))
        stepTimer?.cancel()
        if (expiresAt != null) {
            stepTimer = scope.launch {
                delay(expiresAt - now)
                val current = _screen.value
                if (current is SheetScreen.Step && current.stepId == id) {
                    // A code being checked decides first; the timeout fails it if it is refused.
                    if (current.busy) {
                        threeDsExpired = true
                    } else {
                        refusedForGood(null)
                        showFailed(WajubError.of("3ds_timeout", lang))
                    }
                }
            }
        }
    }

    /** A step's field: `code`, `pin`, `phone`, `birthday`, `address`, `city`, `state`, `zip_code`. */
    fun setStepField(field: String, value: String) {
        val current = _screen.value as? SheetScreen.Step ?: return
        if (current.busy || finished) return
        val clean = when (field) {
            "code", "pin" -> asciiDigits(value).take(if (current.kind == StepKind.Pin) 6 else 10)
            // Digits, shown as YYYY-MM-DD while typed.
            "birthday" -> asciiDigits(value).take(8).let { d ->
                listOfNotNull(d.take(4), d.drop(4).take(2).ifEmpty { null }, d.drop(6).ifEmpty { null }).joinToString("-")
            }
            // A phone or an address: its digits read as 0-9, the rest as typed.
            else -> asciiDigitsKeepingText(value)
        }
        show(current.copy(values = current.values + (field to clean), fieldErrors = current.fieldErrors - field, error = null))
    }

    /** Sends the step. */
    fun submitStep() {
        val current = _screen.value as? SheetScreen.Step ?: return
        if (current.busy || finished) return
        val errors = current.kind.fields.mapNotNull { field ->
            val v = current.values[field].orEmpty().trim()
            val ok = when {
                v.isEmpty() -> false
                // A code is ASCII digits only.
                field in setOf("code", "pin") && v.any { it !in '0'..'9' } -> false
                field == "code" && current.kind == StepKind.ThreeDs -> v.length in 4..8
                field == "code" -> v.length in 4..10
                field == "pin" -> v.length in 4..6
                field == "birthday" -> validBirthday(v)
                else -> true
            }
            if (ok) null else field to message("field_required")
        }.toMap()
        if (errors.isNotEmpty()) {
            show(current.copy(fieldErrors = errors))
            return
        }
        show(current.copy(busy = true, error = null))
        scope.launch {
            val result = try {
                session.verifyStep(current.kind, current.stepId, current.values.mapValues { it.value.trim() })
            } catch (e: WajubError) {
                // No answer: the status may have moved; the payer can try again.
                watcher?.pollNow()
                showIfStillOn(current) { it.copy(busy = false, error = e.message) }
                return@launch
            }
            onStepResult(current, result)
        }
    }

    private fun onStepResult(step: SheetScreen.Step, result: StepResult) {
        // A late answer (the status already decided) changes nothing.
        val current = _screen.value
        if (finished || current !is SheetScreen.Step || current.stepId != step.stepId || current.kind != step.kind || !current.busy) return
        // The 3-D Secure code expired while it was being checked: a refusal ends it.
        if (step.kind == StepKind.ThreeDs && threeDsExpired && result !is StepResult.Accepted) {
            refusedForGood(null)
            showFailed(WajubError.of("3ds_timeout", lang))
            return
        }
        when (result) {
            is StepResult.Accepted -> {
                val txn = result.transaction
                txn?.let { lastTransaction = it }
                // The provider asks for another step (a code after the birthday, a page after
                // the PIN): followed as a payment answer. Paid only on the transaction's status.
                when (val next = result.next) {
                    is ProcessOutcome.RequiresAction -> {
                        stepTimer?.cancel()
                        follow(next)
                        return
                    }
                    is ProcessOutcome.Failed -> {
                        stepTimer?.cancel()
                        showFailed(next.error)
                        return
                    }
                    else -> Unit
                }
                when {
                    // 3-D Secure: verifying, until the status decides.
                    step.kind == StepKind.ThreeDs -> {
                        stepTimer?.cancel()
                        watcher?.pollNow()
                    }
                    txn?.status?.lowercase() in OutcomeMapper.SUCCESS_STATUSES -> success(txn, family())
                    txn?.status?.lowercase() == "failed" -> showFailed(WajubError.of("payment_failed", lang))
                    else -> {
                        show(SheetScreen.Pending(url = lastUrl))
                        watch(seenProcessing = true)
                        watcher?.pollNow()
                    }
                }
            }
            is StepResult.Rejected -> if (result.error.httpStatus == 410) {
                showEnded(SheetScreen.Expired(result.error))
            } else if (result.canRetryHere) {
                val text = result.error.localized(lang, mapOf("n" to (result.attemptsRemaining ?: 0).toString())).message
                showIfStillOn(step) { it.copy(busy = false, error = text, attemptsRemaining = result.attemptsRemaining, values = emptyMap()) }
            } else {
                // The step is over (max attempts, expired, 3-D Secure refused): a definitive refusal.
                refusedForGood(null)
                showFailed(result.error)
            }
            is StepResult.Invalid -> {
                val fields = result.error.details.mapKeys { it.key.removePrefix("data.") }
                    .mapValues { localizedApiText(it.value) }
                val known = fields.filterKeys { it in step.kind.fields }
                showIfStillOn(step) {
                    it.copy(
                        busy = false,
                        fieldErrors = known,
                        error = if (known.size < fields.size) localizedApiText(result.error.apiMessage ?: result.error.message) else null,
                    )
                }
            }
        }
    }

    // A real date, YYYY-MM-DD, in the past.
    private fun validBirthday(value: String): Boolean {
        if (!BIRTHDAY.matches(value)) return false
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
        val date = runCatching { format.parse(value) }.getOrNull() ?: return false
        return date.time < wallClock()
    }

    private fun showIfStillOn(step: SheetScreen.Step, change: (SheetScreen.Step) -> SheetScreen.Step) {
        val current = _screen.value
        if (current is SheetScreen.Step && current.stepId == step.stepId && current.kind == step.kind && !finished) show(change(current))
    }

    /** "Resend code", offered 60 s after the step opened. */
    fun resendStep() {
        val current = _screen.value as? SheetScreen.Step ?: return
        if (current.busy || finished || clock() < current.resendAt) return
        show(current.copy(resendAt = clock() + RESEND_AFTER_MS, error = null))
        scope.launch {
            val masked = runCatching { session.resendStep(current.kind, current.stepId) }.getOrNull()
            if (masked != null) showIfStillOn(current) { it.copy(hint = masked) }
        }
    }

    // ── Watching (spec §13) ──────────────────────────────────────────────

    private fun watch(seenProcessing: Boolean) {
        if (finished) return
        val running = watcher
        if (running != null && watchJob?.isActive == true) {
            if (seenProcessing) running.markSeenProcessing()
            return
        }
        val data = _session.value ?: return
        val w = session.watcher(data, seenProcessing, clock, random, connector, attemptStartedAt)
        watcher = w
        watchJob = scope.launch { w.events().collect { onWatchEvent(it) } }
    }

    // No answer, and still `pending` 60 s after the last try: the request most
    // likely never reached the API — back to the form, the same key kept.
    private fun checkUnanswered(sentAt: Long, form: SheetScreen.Form) {
        unansweredCheck?.cancel()
        unansweredCheck = scope.launch {
            delay((sentAt + UNANSWERED_AFTER_MS - clock()).coerceAtLeast(0))
            // Decide on a status read (the polls may still be failing): check again until one says.
            while (true) {
                if (_screen.value !is SheetScreen.Pending || sawProcessing || unansweredKey == null) return@launch
                val seen = lastSeen
                if (seen != null) {
                    // A refusal an earlier try left on the payment is not news.
                    // A new refusal is this attempt's answer (decided as it was read); with the
                    // earlier one unknown, no code is taken for it and the rule applies.
                    if (seen.status != "pending") return@launch
                    if (errorCodeBeforeAttemptKnown && seen.errorCode != null && seen.errorCode != errorCodeBeforeAttempt) return@launch
                    break
                }
                delay(UNANSWERED_RECHECK_MS)
            }
            stopWatching()
            attemptInFlight = false
            val error = WajubError.of("network_error", lang)
            show(forms[form.method.id]?.copy(busy = false, banner = error.message) ?: form.copy(busy = false, banner = error.message))
            fire { callbacks.onError?.invoke(error) }
        }
    }

    private fun stopWatching() {
        unansweredCheck?.cancel()
        watchJob?.cancel()
        watchJob = null
        watcher = null
        stepTimer?.cancel()
    }

    private fun onWatchEvent(event: WatchEvent) {
        if (finished) return
        when (event) {
            is WatchEvent.StatusChanged -> {
                lastSeen = event.status
                // A status says which refusal the payment carries (null: none): the
                // baseline of the next attempt.
                knownErrorCode = event.status.errorCode
                errorCodeKnown = true
                // A request without an answer, then a new refusal on the payment: it arrived and was
                // refused. One the payment already carried before this attempt is not news.
                val code = event.status.errorCode
                if (unansweredKey != null && !sawProcessing && event.status.status == "pending" &&
                    errorCodeBeforeAttemptKnown && code != null && code != errorCodeBeforeAttempt &&
                    _screen.value is SheetScreen.Pending
                ) {
                    lastTransaction = lastTransaction?.copy(status = "pending", errorCode = code)
                    if (onPayerOtpRefusal(event.status, PaymentWatcher.attemptEnded(event.status, lang))) return
                    showFailed(PaymentWatcher.attemptEnded(event.status, lang))
                    return
                }
                if (event.status.status in setOf("processing", "partial")) {
                    sawProcessing = true
                    // It arrived: a later Pay is a new attempt, with a new key.
                    unansweredKey = null
                }
                lastTransaction = lastTransaction?.copy(status = event.status.status)
            }
            // Seen now: the timestamp is now unless the payment says when it ended.
            is WatchEvent.Succeeded -> success(lastTransaction?.copy(status = event.status.status, updatedAt = null), family())
            is WatchEvent.Failed -> {
                event.error.declineCode?.let { knownErrorCode = it }
                event.status?.let { lastTransaction = lastTransaction?.copy(status = it.status) }
                val status = event.status
                if (status != null && onPayerOtpRefusal(status, event.error)) return
                // While a client session completes, its answer decides (a success wins).
                if (completions > 0 && event.status?.status == "pending") heldFailure = event.error else showFailed(event.error)
            }
            is WatchEvent.Cancelled -> showEnded(SheetScreen.Cancelled(event.error))
            is WatchEvent.Expired -> showEnded(SheetScreen.Expired(event.error))
            WatchEvent.StillProcessing -> {
                watcher = null
                show(SheetScreen.Pending(url = lastUrl, capped = true))
            }
        }
    }

    // ── Outcomes ─────────────────────────────────────────────────────────

    private fun success(txn: SessionTransaction?, methodFamily: String) {
        if (finished || _screen.value is SheetScreen.Success) return
        unansweredKey = null
        heldFailure = null
        stopWatching()
        attemptInFlight = false
        undecided = false
        val t = txn ?: lastTransaction ?: return
        lastTransaction = t
        val payment = PaymentSuccess.from(t, methodFamily, wallClock())
        show(SheetScreen.Success(payment))
        fire { callbacks.onSuccess?.invoke(payment) }
        if (options.autoCloseSuccess > 0) {
            autoClose = scope.launch {
                delay(options.autoCloseSuccess)
                finish(CheckoutResult.Complete(payment, t))
            }
        }
    }

    // A definitive refusal (a step over, a 3-D Secure refused or timed out, a client-session
    // decline): the payment is `pending` again, and can be cancelled.
    private fun refusedForGood(txn: SessionTransaction?) {
        attemptInFlight = false
        undecided = false
        // Only the status is taken from a refusal's (often partial) transaction.
        val status = txn?.status?.takeIf { it.isNotBlank() } ?: "pending"
        lastTransaction = lastTransaction?.copy(status = status) ?: txn
    }

    private fun showFailed(error: WajubError, retryAt: Long? = null) {
        if (finished) return
        unansweredKey = null
        stopWatching()
        attemptInFlight = false
        // A page that could not open, or a step the sheet cannot take: the payment may still move.
        if (error.code != UNSUPPORTED_ACTION_CODE && error.code != "redirect_unavailable") undecided = false
        clientSessionId = null
        show(SheetScreen.Failed(error, retryAt))
        fire { callbacks.onError?.invoke(error) }
    }

    private fun showEnded(screen: SheetScreen) {
        if (finished) return
        unansweredKey = null
        stopWatching()
        attemptInFlight = false
        undecided = false
        show(screen)
        val error = when (screen) {
            is SheetScreen.Expired -> screen.error
            is SheetScreen.Cancelled -> screen.error
            else -> null
        }
        error?.let { fire { callbacks.onError?.invoke(it) } }
    }

    /** "Try again" on a retryable failure: the same method's form, a new attempt. */
    fun tryAgain() {
        val current = _screen.value as? SheetScreen.Failed ?: return
        if (finished || !current.error.retryable) return
        if (current.retryAt != null && clock() < current.retryAt) return
        stopWatching()
        attemptInFlight = false
        val m = method
        if (m == null || methods.none { it.id == m.id }) {
            showMethodsOrForm()
        } else {
            show(forms[m.id]?.copy(busy = false, banner = null) ?: formFor(m))
        }
    }

    // ── Closing (spec §14, states.md §4–5) ───────────────────────────────

    /** "Cancel payment", offered while no attempt is in flight. */
    fun cancelPayment() {
        if (!canEditForm() || !canCancel()) return
        cancelAndFinish(CancelReason.PayerCancelled, showCancelled = true)
    }

    // README §14: `/pay/cancel` only for a loaded session whose last known
    // status is `pending`, with no attempt undecided — the API cancels a
    // `processing` payment too.
    private fun canCancel(): Boolean =
        !finished && cancelling == null && !attemptInFlight && !undecided && _session.value != null &&
            lastTransaction?.status?.lowercase() == "pending"

    /** The sheet's Close button. */
    fun close() {
        if (finished) return
        when (val current = _screen.value) {
            is SheetScreen.Success -> {
                autoClose?.cancel()
                finish(CheckoutResult.Complete(current.payment, lastTransaction))
            }
            else -> dismiss()
        }
    }

    /** The payer swiped the sheet away, pressed back, or tapped outside. */
    fun dismiss() {
        if (finished) return
        if (cancelling != null) {
            // The cancel under way settles the sheet; the sheet hides now.
            _closing.value = true
            return
        }
        val current = _screen.value
        settledResult(current)?.let {
            // A payment refused for good is still open: released (cancelled) as the payer leaves.
            // The result stays the failure the payer saw.
            if (current is SheetScreen.Failed && options.cancelOnDismiss && canCancel()) {
                detached.launch { runCatching { session.cancelRaw() } }
            }
            finish(it)
            return
        }
        val loaded = _session.value
        when {
            // The session has not answered: nothing to cancel.
            loaded == null -> finish(CheckoutResult.Cancelled(CancelReason.Dismissed, null))
            attemptInFlight || undecided || current.watching ||
                loaded.transaction.status.lowercase() in setOf("processing", "partial") ->
                finish(CheckoutResult.Processing(lastTransaction))
            options.cancelOnDismiss && canCancel() -> cancelAndFinish(CancelReason.Dismissed, showCancelled = false)
            else -> finish(CheckoutResult.Cancelled(CancelReason.Dismissed, null))
        }
    }

    // A screen where an attempt runs (its Pay pressed, or watched).
    private val SheetScreen.watching: Boolean
        get() = this is SheetScreen.Form && busy || this is SheetScreen.Approval || this is SheetScreen.Ussd ||
            this is SheetScreen.Pending || this is SheetScreen.Step || this is SheetScreen.PayerOtp && busy

    /**
     * The screen that presented the sheet went away for good (activity
     * finished, composable gone). Never cancels the payment.
     */
    fun hostGone() {
        if (finished) return
        // The payer's own cancel is on its way (it outlives the host): it settles the sheet.
        if (cancelling != null) {
            hostLeftDuringCancel = true
            return
        }
        val current = _screen.value
        settledResult(current)?.let {
            finish(it)
            return
        }
        val underWay = _session.value?.transaction?.status?.lowercase() in setOf("processing", "partial") &&
            lastTransaction?.status?.lowercase() != "pending"
        if (attemptInFlight || underWay || current is SheetScreen.Form && current.busy) {
            finish(CheckoutResult.Processing(lastTransaction))
        } else {
            finish(CheckoutResult.Cancelled(CancelReason.HostGone, null))
        }
    }

    // The result of a screen that already has one, or null.
    private fun settledResult(screen: SheetScreen): CheckoutResult? =
        when (screen) {
            is SheetScreen.Success -> CheckoutResult.Complete(screen.payment, lastTransaction)
            is SheetScreen.Failed -> CheckoutResult.Failed(screen.error, lastTransaction)
            is SheetScreen.Expired -> CheckoutResult.Failed(screen.error, lastTransaction)
            is SheetScreen.Cancelled -> screen.error?.let { CheckoutResult.Failed(it, lastTransaction) }
            is SheetScreen.Ended -> CheckoutResult.Failed(screen.error, lastTransaction)
            is SheetScreen.LoadError -> CheckoutResult.Failed(screen.error, null)
            is SheetScreen.Pending -> if (screen.capped) CheckoutResult.Processing(lastTransaction) else null
            else -> null
        }

    // Runs on a scope of its own: a cancel the payer asked for settles the
    // sheet even when the host is gone. CANCELLED is shown once it is done.
    private fun cancelAndFinish(reason: CancelReason, showCancelled: Boolean) {
        cancelling = reason
        if (!showCancelled) _closing.value = true
        loadJob?.cancel()
        readyTimer?.cancel()
        detached.launch {
            val response = try {
                session.cancelRaw()
            } catch (_: WajubError) {
                // A failed cancel changes nothing for the payer.
                finish(CheckoutResult.Cancelled(reason, null))
                return@launch
            }
            if (response.isSuccess) {
                if (showCancelled) show(SheetScreen.Cancelled(null))
                finish(CheckoutResult.Cancelled(reason, response.body.str("redirect_url")))
                return@launch
            }
            // Refused (already paid, processing…): the status decides.
            val status = runCatching { session.status() }.getOrNull()?.status?.takeIf { it.isNotBlank() }
            val txn = lastTransaction?.let { t -> status?.let { t.copy(status = it) } ?: t }
            val result = when (status) {
                in OutcomeMapper.SUCCESS_STATUSES -> txn?.let { CheckoutResult.Complete(PaymentSuccess.from(it, "unknown", wallClock()), it) }
                // Under way, or unreadable: money may move.
                "processing", "partial", null -> CheckoutResult.Processing(txn)
                "failed" -> CheckoutResult.Failed(WajubError.of("payment_failed", lang), txn)
                "expired" -> CheckoutResult.Failed(WajubError.of("session_expired", lang), txn)
                else -> null
            }
            if (result == null && reason == CancelReason.PayerCancelled && !hostLeftDuringCancel) {
                // Still pending, and not cancelled: the payer stays where they were.
                cancelling = null
                show(_screen.value)
                return@launch
            }
            val settled = result ?: CheckoutResult.Cancelled(if (hostLeftDuringCancel) CancelReason.HostGone else reason, null)
            if (settled is CheckoutResult.Complete) fire { callbacks.onSuccess?.invoke(settled.payment) }
            finish(settled)
        }
    }

    private fun finish(result: CheckoutResult, release: Boolean = true) {
        if (finished) return
        finished = true
        _closing.value = true
        loadJob?.cancel()
        readyTimer?.cancel()
        autoClose?.cancel()
        stopWatching()
        if (release) ActiveSheet.release(this)
        if (result is CheckoutResult.Cancelled) {
            val event = CancelEvent(result.reason, result.redirectUrl)
            fire { callbacks.onCancel?.invoke(event) }
        }
        fire { callbacks.onComplete?.invoke(result) }
        _result.value = result
        fire { onFinished?.invoke(result) }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private fun show(screen: SheetScreen) {
        if (finished) return
        _screen.value = screen
        _cancellable.value = canCancel() && (screen is SheetScreen.Methods || screen is SheetScreen.Form && !screen.busy)
        val next = stateOf(screen) ?: return
        if (!ready || next == state) return
        state = next
        val event = StateChangeEvent(next, family())
        fire { callbacks.onStateChange?.invoke(event) }
    }

    private fun stateOf(screen: SheetScreen): CheckoutState? =
        when (screen) {
            SheetScreen.Loading, is SheetScreen.LoadError -> null
            is SheetScreen.Methods -> CheckoutState.INITIATED
            is SheetScreen.Form -> if (screen.busy) CheckoutState.PROCESSING else CheckoutState.COLLECTING_DETAILS
            is SheetScreen.Approval -> CheckoutState.APPROVAL_REQUIRED
            is SheetScreen.PayerOtp -> if (screen.busy) CheckoutState.PROCESSING else CheckoutState.PAYER_OTP_REQUIRED
            is SheetScreen.Ussd -> CheckoutState.USSD_REQUIRED
            is SheetScreen.Step -> if (screen.busy) {
                CheckoutState.VERIFYING
            } else {
                when (screen.kind) {
                    StepKind.Otp -> CheckoutState.OTP_REQUIRED
                    StepKind.Pin -> CheckoutState.PIN_REQUIRED
                    StepKind.Phone -> CheckoutState.PHONE_REQUIRED
                    StepKind.Birthday -> CheckoutState.BIRTHDAY_REQUIRED
                    StepKind.Address -> CheckoutState.ADDRESS_REQUIRED
                    StepKind.ThreeDs -> CheckoutState.THREE_DS_REQUIRED
                }
            }
            is SheetScreen.Pending -> if (screen.threeDs) CheckoutState.THREE_DS_REQUIRED else CheckoutState.PENDING
            is SheetScreen.Success -> CheckoutState.SUCCESS
            is SheetScreen.Failed -> CheckoutState.FAILED
            is SheetScreen.Expired -> CheckoutState.EXPIRED
            is SheetScreen.Cancelled -> CheckoutState.CANCELLED
            is SheetScreen.Ended -> screen.state
        }

    private fun message(code: String, args: Map<String, String> = emptyMap()): String =
        ErrorCatalog.message(code, lang, null, args) ?: code

    // A callback that throws must not break the sheet.
    private inline fun fire(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            LOG.log(Level.WARNING, "A Wajub checkout callback threw", e)
        }
    }

    internal companion object {
        // The card fields that hold digits only.
        private val CARD_NUMBER_FIELDS = setOf("card.number", "cvv", "expiry_month", "expiry_year")

        const val RESEND_AFTER_MS: Long = 60_000L
        private const val PAYER_OTP_ACTION = "payer_otp"
        private const val OTP_ATTEMPTS_EXCEEDED = "otp_attempts_exceeded"
        const val UNANSWERED_AFTER_MS: Long = 60_000L
        private const val UNANSWERED_RECHECK_MS: Long = 5_000L
        private val BIRTHDAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val BELONGS_TO = Regex("""belongs to ([A-Za-z_]+)""", RegexOption.IGNORE_CASE)
        private val LOG: Logger = Logger.getLogger("Wajub")
    }
}

internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

/** The one sheet open at a time (states.md §5). */
internal object ActiveSheet {
    private val current = AtomicReference<CheckoutController?>(null)

    fun claim(controller: CheckoutController): Boolean {
        while (true) {
            val open = current.get()
            if (open === controller) return true
            // A sheet whose host cancelled its scope without settling it no longer blocks.
            if (open != null && open.isAlive) return false
            if (current.compareAndSet(open, controller)) return true
        }
    }

    fun release(controller: CheckoutController) {
        current.compareAndSet(controller, null)
    }

    /** Tests only: forgets a sheet a test left open. */
    fun clear() {
        current.set(null)
    }
}
