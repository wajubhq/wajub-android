package com.wajub.mobile.checkout

import com.wajub.mobile.WajubSession
import com.wajub.mobile.mapper.OutcomeMapper
import com.wajub.mobile.mapper.PaymentMapper
import com.wajub.mobile.mapper.str
import com.wajub.mobile.mobilemoney.MobileMoney
import com.wajub.mobile.mobilemoney.MobileMoneyRules
import com.wajub.mobile.mobilemoney.OperatorChoice
import com.wajub.mobile.model.ErrorCatalog
import com.wajub.mobile.model.PaymentStatus
import com.wajub.mobile.model.ProcessOutcome
import com.wajub.mobile.model.ProcessRequest
import com.wajub.mobile.model.ProcessingKind
import com.wajub.mobile.model.SdkConfig
import com.wajub.mobile.model.SessionData
import com.wajub.mobile.model.SessionTransaction
import com.wajub.mobile.model.StepKind
import com.wajub.mobile.model.StepResult
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random

/**
 * The payment sheet's engine, without its UI: loading, methods, forms,
 * answers, confirmation steps, watching, cancel and dismiss — the states,
 * callbacks and result of states.md. `PaymentSheet` draws [screen] and
 * forwards the payer's actions; an app can draw its own UI on it the same way.
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
) {
    constructor(session: WajubSession, options: PresentOptions, scope: CoroutineScope) : this(
        session, options, scope, System::currentTimeMillis, Random.Default, PusherConnector, Dispatchers.Default,
        { Locale.getDefault().toLanguageTag() },
    )

    private val callbacks = options.callbacks

    private val _screen = MutableStateFlow<SheetScreen>(SheetScreen.Loading)

    /** What to draw. */
    val screen: StateFlow<SheetScreen> = _screen.asStateFlow()

    private val _language = MutableStateFlow(WajubLanguage.resolve(options.locale, null, deviceLanguage()))

    /** The sheet's language (spec LD-09). */
    val language: StateFlow<WajubLanguage> = _language.asStateFlow()

    private val _session = MutableStateFlow<SessionData?>(null)

    /** The loaded session (amount, merchant branding), once it is. */
    val sessionData: StateFlow<SessionData?> = _session.asStateFlow()

    private val _closing = MutableStateFlow(false)

    /** The sheet is going away (dismissed, or done): the UI hides while the result settles. */
    val closing: StateFlow<Boolean> = _closing.asStateFlow()

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
     * Every answer to a payment request — `/pay/process`, `/pay/client-session`
     * and its completion — before the sheet acts on it. The 1.x `PaymentSheet`
     * reports its results from it.
     */
    var onAnswer: ((ProcessOutcome) -> Unit)? = null

    /** The payment as last seen (from the session, an answer, or the status). */
    val transaction: SessionTransaction? get() = lastTransaction

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
    private var clientSessionId: String? = null
    private var altcha: Deferred<AltchaSolution?>? = null
    private var altchaReloaded = false
    private var resubmit: (suspend () -> Unit)? = null

    // A request that got no answer: its key is reused by the next Pay (spec §2.5).
    private var unansweredKey: String? = null
    private var unansweredCheck: Job? = null
    private var lastSeen: PaymentStatus? = null
    private var sawProcessing = false
    private var cancelling: CancelReason? = null

    // Runs the cancel: it must settle the sheet even when the host's scope is gone.
    private val detached = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())

    private var loadJob: Job? = null
    private var readyTimer: Job? = null
    private var watcher: PaymentWatcher? = null
    private var watchJob: Job? = null
    private var autoClose: Job? = null
    private var stepTimer: Job? = null

    /** Loads the session and shows the first screen. */
    fun start() {
        if (started) return
        started = true
        if (!ActiveSheet.claim(this)) {
            // Another sheet is open: this one rejects at once and leaves it alone.
            finish(CheckoutResult.Failed(WajubError.of("sheet_already_presented", lang), null), release = false)
            return
        }
        load()
    }

    /** Retry of a load error (`load_error`, `ready_timeout`): the requests start again. */
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
        sdkConfig = config.await()
        methods = PaymentMethods.build(loaded.channels, sdkConfig, txn.sandbox, lang)
        loaded.altcha?.let { challenge -> altcha = scope.async(compute) { Altcha.solve(challenge) } }
        if (txn.status.lowercase() in setOf("processing", "partial")) {
            becomeReady()
            restore(txn)
            return
        }
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
        val amount = lastTransaction?.payableAmount ?: 0.0
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
        return !finished && (current is SheetScreen.Methods || (current is SheetScreen.Form && !current.busy))
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

    private fun enabledIds(choices: List<OperatorChoice>): List<String> {
        val amount = lastTransaction?.payableAmount ?: 0.0
        return choices.filterNot { outOfLimits(it, amount) }.map { it.id }
    }

    /** Whether [choice] cannot pay [amount] (its channel's limits). */
    fun outOfLimits(choice: OperatorChoice, amount: Double = lastTransaction?.payableAmount ?: 0.0): Boolean =
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
        val detected = MobileMoney.data().country(code)?.let { MobileMoney.detectOperator(form.phone, it) }
        form.copy(
            country = code.uppercase(),
            choices = choices,
            pickedByHand = false,
            detected = detected,
            selected = MobileMoneyRules.selection(enabledIds(choices), null, false, detected),
            phoneError = null,
            operatorError = null,
        )
    }

    /** The number as typed: detection preselects its operator until the payer picks one. */
    fun setPhone(text: String) = updateMobileMoney { form ->
        val phone = text.filter { it.isDigit() || it == '+' }.take(16)
        val detected = form.countryInfo?.let { MobileMoney.detectOperator(phone, it) }
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
        if (choice == null || outOfLimits(choice)) {
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
        show(current.copy(card = card.copy(values = card.values + (field to value), errors = card.errors - field), banner = null))
    }

    // ── Paying ───────────────────────────────────────────────────────────

    /** Pay with the Mobile Money form, after the web checkout's checks (spec §6.3). */
    fun payMobileMoney() {
        val current = _screen.value as? SheetScreen.Form ?: return
        val form = current.mobileMoney ?: return
        if (current.busy || finished) return
        val country = form.countryInfo
        if (form.choices.isEmpty() || country == null) {
            show(current.copy(mobileMoney = form.copy(operatorError = message("operator_unavailable"))))
            return
        }
        val sandbox = lastTransaction?.sandbox == true
        val labels = form.choices.associate { it.id to it.label }
        val issue = MobileMoneyRules.validate(form.phone, country, form.choices.map { it.id }, form.selected, { labels[it] ?: it }, sandbox)
        if (issue != null) {
            val text = message(issue.code, issue.args)
            val updated = if (issue.code == "operator_required") form.copy(operatorError = text) else form.copy(phoneError = text)
            show(current.copy(mobileMoney = updated))
            return
        }
        val choice = form.selectedChoice ?: return
        val data = MobileMoneyRules.requestData(choice, form.phone, country, form.pickedByHand)
        submit(current, choice.channelSlug) { solution, key ->
            session.process(ProcessRequest(choice.channelSlug, withExtras(data, solution), key))
        }
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

    /** Pay with the `pm_…` Stripe's card widget made. */
    fun payStripe(paymentMethodId: String) {
        val current = _screen.value as? SheetScreen.Form ?: return
        if (current.busy || finished || current.card?.flavor !is CardFlavor.Stripe) return
        val data = buildMap<String, Any?> {
            put("payment_method_id", paymentMethodId)
            current.card.values["card_holder"]?.takeIf { it.isNotBlank() }?.let { put("card_holder", it.trim()) }
        }
        submit(current, current.method.slug) { solution, key ->
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
    private fun submit(form: SheetScreen.Form, channel: String, request: suspend (AltchaSolution?, String) -> ProcessOutcome) {
        forms[form.method.id] = form
        paymentStarted = false
        attemptChannel = channel
        attemptInFlight = true
        lastSeen = null
        sawProcessing = false
        show(form.copy(busy = true, banner = null))
        val run: suspend (String) -> Unit = { key ->
            val outcome = request(altcha?.await(), key)
            handleOutcome(outcome, form, key)
        }
        resubmit = { run(UUID.randomUUID().toString()) }
        val key = unansweredKey ?: UUID.randomUUID().toString()
        scope.launch { run(key) }
    }

    private fun family(): String = method?.family ?: "unknown"

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
        fire { onAnswer?.invoke(outcome) }
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
                    ProcessingKind.Approval -> show(SheetScreen.Approval(outcome.instruction, approvalPhone(form)))
                    ProcessingKind.Ussd -> show(SheetScreen.Ussd(outcome.instruction))
                    ProcessingKind.Pending -> show(SheetScreen.Pending())
                }
                watch(seenProcessing = seen)
                unansweredSince?.let { checkUnanswered(it, form) }
            }
            is ProcessOutcome.RequiresAction -> {
                started()
                outcome.transaction?.let { lastTransaction = it }
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
                        showStep(step, outcome.stepId, outcome.hint, null)
                        watch(seenProcessing = true)
                    }
                    outcome.action == "confirm_3ds" && outcome.url == null && outcome.stepId != null -> {
                        showStep(StepKind.ThreeDs, outcome.stepId, outcome.hint, outcome.timeoutSeconds)
                        watch(seenProcessing = true)
                    }
                    outcome.url != null -> openPage(outcome.url, threeDs = outcome.action == "confirm_3ds")
                    else -> showFailed(WajubError.of("unsupported_action", lang))
                }
            }
            is ProcessOutcome.Failed -> onRefused(outcome.error, outcome.transaction, form)
        }
    }

    private fun approvalPhone(form: SheetScreen.Form): String? {
        val momo = form.mobileMoney ?: return null
        val country = momo.countryInfo ?: return null
        return MobileMoney.e164(momo.phone, country)
    }

    private suspend fun onRefused(error: WajubError, txn: SessionTransaction?, form: SheetScreen.Form) {
        attemptInFlight = false
        txn?.let { lastTransaction = it }
        when {
            error.httpStatus == 422 && altchaRefused(error) -> {
                if (altchaReloaded) {
                    showFailed(WajubError.of("security_check_failed", lang))
                    return
                }
                // A new challenge, once, then the same payment again.
                altchaReloaded = true
                val reloaded = try {
                    session.session(forceRefresh = true)
                } catch (_: Exception) {
                    null
                }
                val challenge = reloaded?.altcha
                if (challenge == null) {
                    showFailed(WajubError.of("security_check_failed", lang))
                    return
                }
                altcha = scope.async(compute) { Altcha.solve(challenge) }
                attemptInFlight = true
                resubmit?.invoke()
            }
            // A 422 without a code of its own is the form's: field errors, stay.
            error.httpStatus == 422 && error.code == "validation_error" -> show(fieldErrors(form, error))
            error.httpStatus == 400 -> {
                // The payment can no longer be processed: the status says why.
                val status = try {
                    session.status()
                } catch (_: WajubError) {
                    null
                }
                when (status?.status) {
                    in OutcomeMapper.SUCCESS_STATUSES -> success(lastTransaction?.copy(status = status!!.status), family())
                    // Still under way (money may move): wait for its outcome.
                    "processing", "partial" -> {
                        attemptInFlight = true
                        show(SheetScreen.Pending(url = lastUrl))
                        watch(seenProcessing = true)
                    }
                    "cancelled" -> showEnded(SheetScreen.Cancelled(WajubError.of("payment_cancelled", lang)))
                    "expired" -> showEnded(SheetScreen.Expired(WajubError.of("session_expired", lang)))
                    "failed" -> showFailed(WajubError.of("payment_failed", lang))
                    else -> showFailed(error)
                }
            }
            error.httpStatus == 410 -> showEnded(SheetScreen.Expired(error))
            error.code == "rate_limited" -> {
                val seconds = error.retryAfterSeconds?.toLong() ?: 0L
                showFailed(error, retryAt = clock() + seconds * 1000)
            }
            else -> showFailed(error)
        }
    }

    private fun altchaRefused(error: WajubError): Boolean =
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
        val detected = named ?: form.countryInfo?.let { MobileMoney.detectOperator(form.phone, it) }
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
                scope.launch {
                    val outcome = session.confirmClientSession(id)
                    if (finished || _screen.value !is SheetScreen.Pending) return@launch
                    fire { onAnswer?.invoke(outcome) }
                    when (outcome) {
                        is ProcessOutcome.Complete -> success(outcome.transaction, family())
                        is ProcessOutcome.Failed -> {
                            clientSessionId = null
                            attemptInFlight = false
                            showFailed(outcome.error)
                        }
                        // Not paid yet: the PSP's webhook settles it while the watch runs.
                        else -> Unit
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
                if (current is SheetScreen.Step && current.stepId == id) showFailed(WajubError.of("3ds_timeout", lang))
            }
        }
    }

    /** A step's field: `code`, `pin`, `phone`, `birthday`, `address`, `city`, `state`, `zip_code`. */
    fun setStepField(field: String, value: String) {
        val current = _screen.value as? SheetScreen.Step ?: return
        if (current.busy || finished) return
        val clean = when (field) {
            "code", "pin" -> value.filter { it.isDigit() }.take(if (current.kind == StepKind.Pin) 6 else 10)
            else -> value
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
                field == "code" && current.kind == StepKind.ThreeDs -> v.length in 4..8
                field == "code" -> v.length in 4..10
                field == "pin" -> v.length in 4..6
                field == "birthday" -> BIRTHDAY.matches(v)
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
        if (finished) return
        when (result) {
            is StepResult.Accepted -> {
                val txn = result.transaction
                txn?.let { lastTransaction = it }
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
            is StepResult.Rejected -> if (result.canRetryHere) {
                val text = result.error.localized(lang, mapOf("n" to (result.attemptsRemaining ?: 0).toString())).message
                showIfStillOn(step) { it.copy(busy = false, error = text, attemptsRemaining = result.attemptsRemaining, values = emptyMap()) }
            } else {
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

    private fun showIfStillOn(step: SheetScreen.Step, change: (SheetScreen.Step) -> SheetScreen.Step) {
        val current = _screen.value
        if (current is SheetScreen.Step && current.stepId == step.stepId && !finished) show(change(current))
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
        val running = watcher
        if (running != null && watchJob?.isActive == true) {
            if (seenProcessing) running.markSeenProcessing()
            return
        }
        val data = _session.value ?: return
        val w = session.watcher(data, options.realtime, seenProcessing, clock, random, connector)
        watcher = w
        watchJob = scope.launch { w.events().collect { onWatchEvent(it) } }
    }

    // No answer, and still `pending` 60 s after the last try: the request most
    // likely never reached the API — back to the form, the same key kept.
    private fun checkUnanswered(sentAt: Long, form: SheetScreen.Form) {
        unansweredCheck?.cancel()
        unansweredCheck = scope.launch {
            delay((sentAt + UNANSWERED_AFTER_MS - clock()).coerceAtLeast(0))
            val seen = lastSeen
            if (_screen.value !is SheetScreen.Pending || sawProcessing || seen?.status != "pending" || seen.errorCode != null) return@launch
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
                if (event.status.status in setOf("processing", "partial")) sawProcessing = true
                lastTransaction = lastTransaction?.copy(status = event.status.status)
            }
            is WatchEvent.Succeeded -> success(lastTransaction?.copy(status = event.status.status), family())
            is WatchEvent.Failed -> showFailed(event.error)
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
        if (finished) return
        stopWatching()
        attemptInFlight = false
        val t = txn ?: lastTransaction ?: return
        lastTransaction = t
        val payment = PaymentSuccess.from(t, methodFamily, clock())
        show(SheetScreen.Success(payment))
        fire { callbacks.onSuccess?.invoke(payment) }
        if (options.autoCloseSuccess > 0) {
            autoClose = scope.launch {
                delay(options.autoCloseSuccess)
                finish(CheckoutResult.Complete(payment, t))
            }
        }
    }

    private fun showFailed(error: WajubError, retryAt: Long? = null) {
        if (finished) return
        stopWatching()
        attemptInFlight = false
        clientSessionId = null
        show(SheetScreen.Failed(error, retryAt))
        fire { callbacks.onError?.invoke(error) }
    }

    private fun showEnded(screen: SheetScreen) {
        if (finished) return
        stopWatching()
        attemptInFlight = false
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
        if (!canEditForm() || attemptInFlight) return
        cancelAndFinish(CancelReason.PayerCancelled, showCancelled = true)
    }

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
        if (finished || cancelling != null) return
        val current = _screen.value
        settledResult(current)?.let {
            finish(it)
            return
        }
        when {
            attemptInFlight || current is SheetScreen.Form && current.busy -> finish(CheckoutResult.Processing(lastTransaction))
            options.cancelOnDismiss -> cancelAndFinish(CancelReason.Dismissed, showCancelled = false)
            else -> finish(CheckoutResult.Cancelled(CancelReason.Dismissed, null))
        }
    }

    /**
     * The screen that presented the sheet went away for good (activity
     * finished, composable gone). Never cancels the payment.
     */
    fun hostGone() {
        if (finished) return
        // The payer's own cancel is on its way (it outlives the host): it settles the sheet.
        if (cancelling != null) return
        val current = _screen.value
        settledResult(current)?.let {
            finish(it)
            return
        }
        if (attemptInFlight || current is SheetScreen.Form && current.busy) {
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

    private fun cancelAndFinish(reason: CancelReason, showCancelled: Boolean) {
        cancelling = reason
        if (!showCancelled) _closing.value = true
        loadJob?.cancel()
        readyTimer?.cancel()
        if (showCancelled) show(SheetScreen.Cancelled(null))
        detached.launch {
            val response = try {
                session.cancelRaw()
            } catch (_: WajubError) {
                // A failed cancel changes nothing for the payer.
                finish(CheckoutResult.Cancelled(reason, null))
                return@launch
            }
            if (response.isSuccess) {
                finish(CheckoutResult.Cancelled(reason, response.body.str("redirect_url")))
                return@launch
            }
            // Refused (already paid, processing…): the status decides.
            val status = runCatching { session.status() }.getOrNull()?.status
            val txn = lastTransaction?.let { t -> status?.let { t.copy(status = it) } ?: t }
            val result = when (status) {
                in OutcomeMapper.SUCCESS_STATUSES -> txn?.let { CheckoutResult.Complete(PaymentSuccess.from(it, "unknown", clock()), it) }
                "processing", "partial" -> CheckoutResult.Processing(txn)
                "failed" -> CheckoutResult.Failed(WajubError.of("payment_failed", lang), txn)
                "expired" -> CheckoutResult.Failed(WajubError.of("session_expired", lang), txn)
                else -> null
            } ?: CheckoutResult.Cancelled(reason, null)
            if (result is CheckoutResult.Complete && showCancelled) {
                cancelling = null
                success(result.transaction, "unknown")
            } else {
                finish(result)
            }
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
        const val RESEND_AFTER_MS: Long = 60_000L
        const val UNANSWERED_AFTER_MS: Long = 60_000L
        private val BIRTHDAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val BELONGS_TO = Regex("""belongs to ([A-Za-z_]+)""", RegexOption.IGNORE_CASE)
        private val LOG: Logger = Logger.getLogger("Wajub")
    }
}

/** The one sheet open at a time (states.md §5). */
internal object ActiveSheet {
    private val current = AtomicReference<CheckoutController?>(null)

    fun claim(controller: CheckoutController): Boolean =
        current.compareAndSet(null, controller) || current.get() === controller

    fun release(controller: CheckoutController) {
        current.compareAndSet(controller, null)
    }

    /** Tests only: forgets a sheet a test left open. */
    fun clear() {
        current.set(null)
    }
}
