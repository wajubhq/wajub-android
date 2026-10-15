# Wajub Android SDK (Kotlin)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Native Android checkout SDK for [Wajub](https://wajub.com). Presents Mobile Money, card and wallet payments in-app — **no WebView**. Calls `https://api.wajub.com/pay/*` with a session `authorization_token` from your [server SDK](https://docs.wajub.com/libraries/sdks).

It implements the Wajub mobile contract (spec v2), shared with the Flutter, React Native and iOS SDKs and with Wajub.js 1.8: the same states, callbacks, errors and appearance object on every platform.

## Installation

```kotlin
// build.gradle.kts of your app
dependencies {
    implementation("com.wajub:wajub-mobile-compose:1.3.0") // the payment sheet (brings wajub-mobile-core)
    // or, to draw your own UI on the API client and checkout engine only:
    // implementation("com.wajub:wajub-mobile-core:1.3.0")
}
```

Both artifacts are on Maven Central.

| Module | Artifact | Description |
|--------|----------|-------------|
| `wajub-mobile-core` | `com.wajub:wajub-mobile-core` | API client, Mobile Money rules, status watching (polling + Pusher/Reverb), the headless checkout engine — a JVM library |
| `wajub-mobile-compose` | `com.wajub:wajub-mobile-compose` | Jetpack Compose (Material 3) `PaymentSheet` |

### Requirements

| Requirement | Version |
|-------------|---------|
| `minSdk` | 24 (Android 7.0) |
| `compileSdk` | 35 (the library sets no `targetSdk`: your app keeps its own) |
| JVM target | 17 |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.10.2 (committed wrapper at repo root) |

## Quick start

1. Your server creates the payment (`POST /payments` with your secret key) and sends its `authorization_token` to the app.
2. Show `PaymentSheet` with it. `onComplete` receives the result exactly once, when the sheet closes:

```kotlin
var paying by rememberSaveable { mutableStateOf(true) }

if (paying) {
    PaymentSheet(PresentOptions(sessionToken = authorizationToken)) { result ->
        paying = false
        when (result) {
            is CheckoutResult.Complete -> showReceipt(result.payment)      // confirm on your server first
            is CheckoutResult.Processing -> showPending()                  // money may still move
            is CheckoutResult.Failed -> showError(result.error.message)
            is CheckoutResult.Cancelled -> Unit                            // result.reason, result.redirectUrl
        }
    }
}
```

3. **Confirm on your server** before delivering: `GET /payments/{id}` or the `payment.succeeded` webhook. The app's result is what the payer saw, not proof of payment.

The sheet survives configuration changes (rotation, dark mode, language) and settles when the screen that shows it goes away for good: `cancelled` with reason `host_gone` before any attempt, `processing` once one started, `complete` after a success. It never cancels the payment then.

## What the sheet does

| Channel (`GET /pay/session`) | In the sheet |
|---|---|
| `mobile_money`, `mobile` (operators and aggregates such as `cm.mobile`) | One Mobile Money form: country, the operators with their logos (or brand badges), the number with its dial code and formatted as typed, operator detection from the number, the web checkout's checks, then the "Approve the payment on your phone" screen |
| `card`, by sdk-config | Paystack / Flutterwave / Adyen hosted checkout (`client_session`), Stripe's card widget (`stripe_elements`), the PSP's page after the fields it needs (`hosted_redirect`), or the sandbox test-card form |
| `wallet` paid in one tap (Djamo) | One tap, then the wallet's page |
| anything else (bank, crypto, USSD…) | not shown |

Provider pages (hosted card pages, 3-D Secure, wallets) open in Chrome Custom Tabs — never a WebView. When the payer comes back the status is read at once. Confirmation steps the provider asks for (OTP, card PIN, phone, date of birth, billing address, sandbox 3-D Secure code) have their own screens. The outcome is watched with `GET /pay/status` and the session's Pusher/Reverb socket until the payment decides.

Every text exists in English and French. The sheet's language is `PresentOptions.locale`, else the session's, else the device's (`fr…` → French, anything else → English).

## `PresentOptions`

| Option | Default | |
|---|---|---|
| `sessionToken` | — | The `authorization_token` from your server |
| `locale` | `null` | `"fr"` or `"en"`; else the session's language, else the device's |
| `appearance` | `null` | The sheet's look ([Appearance](#appearance)) |
| `loader` | `LoaderStyle.Skeleton` | `Skeleton` (placeholder blocks) or `Spinner` |
| `readyTimeout` | `15000` | Milliseconds before a sheet still loading reports `ready_timeout` (with Retry); `0` disables it |
| `returnUrl` | `null` | An https app link back into your app, sent to provider pages as `return_url`. Without it the payer comes back by hand |
| `mobileMoney` | `MobileMoneyOptions()` | `country` (ISO-2 to start on), `countryLocked` (hide the selector), `operatorMode` (`Choose`: the list is shown; `Detect`: the detected operator shows inside the field, the list opens when needed) |
| `realtime` | `null` | `RealtimeOptions(enabled, host, port, tls)`: a self-hosted Reverb or Soketi behind your own hostname, or `enabled = false` for polling only |
| `cancelOnDismiss` | `true` | Closing the sheet while the payment can still be cancelled cancels it (`POST /pay/cancel`, see [Closing the sheet](#closing-the-sheet)). `false` keeps it open so you can show the sheet again |
| `autoCloseSuccess` | `3000` | Milliseconds before the success screen closes itself; `0` keeps it until the payer taps Done |
| `callbacks` | `CheckoutCallbacks()` | See below |

To use your own `OkHttpClient` (proxy, pinning, logging), create the session yourself, once per token:

```kotlin
val session = remember(token) { WajubMobile.createSession(token, okHttpClient) }
PaymentSheet(session, options) { result -> handle(result) }
```

The sheet is keyed on the token, so a session created again on a recomposition is still the same sheet, but `remember` saves the work. The SDK calls the API through a client derived from yours with `newBuilder()`: your interceptors, proxy, pinning and connection pool are kept; its own timeouts replace yours (connect 10 s, then 30 s per call, 60 s for a payment request); your client is left untouched.

### Closing the sheet

What a dismiss (the close button, a swipe, Back, a tap outside) does depends on where the payer is:

| When | Result | `POST /pay/cancel` |
|---|---|---|
| Still loading | `Cancelled(dismissed)` | no |
| Before any attempt, the payment `pending` | `Cancelled(dismissed)` | yes, with `cancelOnDismiss` (the default) |
| From Pay until the API answers for sure, on the approval, USSD, "Payment in progress", step and verifying screens | `Processing` | never: an approval can still land |
| After a definitive refusal (a decline, a refused attempt, a confirmation step over for good, a 3-D Secure code refused or timed out, a declined client session) or "Try again" | as before any attempt | yes, with `cancelOnDismiss` |
| On the failure screen | `Failed` with that error (no `onCancel`) | yes, in the background, when it can still be cancelled |

"Cancel payment" is offered on the same terms. A session already decided when it loads closes on its own status at once; one already `processing` shows its approval or "Payment in progress" screen at once, without waiting for sdk-config. A result that lands while your screen is recreated (rotation) reaches the new one.

## Callbacks

All optional, all on the main thread, in Wajub.js's order: `onReady` → `onStateChange`… → `onPaymentStarted`, `onRedirect` → `onSuccess` / `onError` / `onCancel` → `onComplete` (exactly once). A callback that throws does not break the sheet.

```kotlin
PresentOptions(
    sessionToken = token,
    callbacks = CheckoutCallbacks(
        onReady = { event -> },            // sessionId, environment ("sandbox"/"live"), amount, currency, methods
        onStateChange = { event -> },      // event.state: CheckoutState, event.method: "mobile_money" | "card" | "wallet" | "unknown"
        onLoadError = { error -> },        // session_not_found, session_terminal_expired/_cancelled/_failed, load_error, ready_timeout, no_payment_method
        onPaymentStarted = { event -> },   // first 2xx of an attempt: from now on a dismiss resolves "processing"
        onRedirect = { event -> },         // right before a provider page opens (event.url)
        onSuccess = { payment -> },        // PaymentSuccess: id, reference, amount (amount_total), currency, method, timestamp, receiptUrl
        onError = { error -> },            // every failure the payer sees (not field validation)
        onCancel = { event -> },           // reason: payer_cancelled | dismissed | host_gone, redirectUrl
        onComplete = { result -> },        // the same CheckoutResult as PaymentSheet's onComplete
    ),
)
```

### States

`CheckoutState` has Wajub.js's 17 names (`state.value` is the exact string):

| State | Screen |
|---|---|
| `INITIATED` | method list |
| `COLLECTING_DETAILS` | a method's form (also after a refused field, or "Try again") |
| `PROCESSING` | the form, its Pay button busy |
| `APPROVAL_REQUIRED` | "Approve the payment on your phone", with the provider's instruction |
| `USSD_REQUIRED` | "Dial this code" |
| `OTP_REQUIRED`, `PIN_REQUIRED`, `PHONE_REQUIRED`, `BIRTHDAY_REQUIRED`, `ADDRESS_REQUIRED` | a confirmation step |
| `3DS_REQUIRED` (`THREE_DS_REQUIRED`) | the sandbox 3-D Secure code, or 3-D Secure in the browser |
| `VERIFYING` | a step being checked |
| `PENDING` | "Payment in progress" ("Continue payment" reopens a provider page) |
| `SUCCESS`, `FAILED` ("Try again" when retryable), `EXPIRED`, `CANCELLED` | outcomes |

### Results

| `CheckoutResult` | When |
|---|---|
| `Complete(payment, transaction)` | closed after a success (also: already paid when the sheet opened) |
| `Processing(transaction)` | closed while an attempt runs, or after 15 minutes of watching: money may still move — confirm server-side |
| `Failed(error, transaction)` | closed on a failure, an expiry, a load error, or a payment refused on the phone (`payment_cancelled`) |
| `Cancelled(reason, redirectUrl)` | "Cancel payment", a dismiss before any attempt, or the host going away first |

Showing a second `PaymentSheet` while one is open resolves the second at once with `Failed(sheet_already_presented)` and leaves the first alone.

## Errors

`WajubError` is an exception with Wajub.js's fields plus mobile ones: `type` (`api_error`, `authentication_error`, `invalid_request_error`, `payment_error`, `rate_limit_error`), `code`, `message` (for the payer, in the sheet's language), `declineCode`, `retryable`, `param`, `httpStatus`, `correlationId` (the response's `X-Request-Id`, for support), `retryAfterSeconds`, `details` (field errors), `blockReference`, `apiMessage` (the API's own English text, for logs). `toMap()` gives the shape with Wajub.js's keys.

Codes you will meet: `session_not_found`, `session_terminal_expired`, `load_error`, `ready_timeout`, `no_payment_method`, `network_error`, `timeout`, `server_error`, `rate_limited`, `insufficient_funds`, `payment_declined`, `merchant_not_verified`, `shield_blocked`, `payment_method_restricted`, `payment_failed`, `payment_attempt_ended`, `payment_cancelled`, `session_expired`, `unsupported_action`, `redirect_unavailable`, the steps' `invalid_otp`, `otp_expired`, `max_attempts_exceeded`, `3ds_timeout`…, and `client_session_unavailable` / `client_session_expired`. `ErrorCatalog` lists them all with their English and French messages.

## Appearance

The appearance object is Wajub.js's: the one that styles your web Components styles the native sheet.

```kotlin
PresentOptions(
    sessionToken = token,
    appearance = Appearance(
        theme = "stripe",                // a preset (stripe, night, flat, none) or a scheme (light, dark, auto)
        colorScheme = "auto",            // follows the phone, live
        variables = AppearanceVariables(
            colorPrimary = "#0F766E",
            borderRadius = "12px",
            fontFamily = "Poppins",      // a font your app registers (below), else the system's
        ),
        disableAnimations = false,
    ),
)
```

- Keys: `theme`, `colorScheme`, `inherit`, `variables` (`colorPrimary`, `colorBackground`, `colorText`, `colorTextSecondary`, `colorTextPlaceholder`, `colorDanger`, `colorSuccess`, `fontFamily`, `fontSizeBase`, `fontWeightNormal`, `fontWeightMedium`, `fontWeightBold`, `spacingUnit`, `borderRadius`, `spacingGridRow`, `spacingGridColumn`), and the top-level `primaryColor`, `secondaryColor`, `backgroundColor`, `fontFamily`, `borderRadius`, `buttonTextColor`, `inputBackgroundColor`, `inputBorderColor`, `textMutedColor`, `successColor`, `errorColor`, `shadow` (`none`/`low`/`high`), `labels` (`above`/`floating`), `disableAnimations`.
- Values: colours `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `rgba()`; lengths `8px`, `8`, `0.5rem`; weights `400`…`700`, `normal`, `bold`. Anything else is ignored, never an error. `Appearance.fromMap(map)` reads the same object from decoded JSON.
- Precedence, per token: `variables` > top-level keys > the preset > the app's theme (`inherit = true` reads your `MaterialTheme`: its colours, keeping only those that contrast enough, `shapes.small` and `typography.bodyLarge`'s font) > the merchant's branding from the dashboard > Wajub's default palette.
- Fonts: `fontFamily` (and the merchant's font) is a name; a CSS stack gives its first family. The sheet uses the family your app registers under that name, else a system font of that name, else the platform's. Fonts in `res/font` or assets reach the sheet only through `WajubFonts`, once, before showing it:

  ```kotlin
  WajubFonts.register("Poppins", FontFamily(Font(R.font.poppins_regular), Font(R.font.poppins_semibold, FontWeight.SemiBold)))
  ```
- Scheme: `colorScheme` (or `theme`) > the app's theme (`inherit`) > the merchant's `appearance_mode` > `auto` (the phone's, switching live).
- The merchant's branding (logo, business name, colours, radius, font, weights) fills what you leave unset; a merchant colour that does not stand out on the background falls back to the default; a white-label merchant hides "Secured by Wajub".
- The loader: `loader = LoaderStyle.Skeleton` draws pulsing placeholder blocks in these colours (static with `disableAnimations` or when the phone asks for reduced motion); `Spinner` a progress indicator.

## Drawing your own UI

`WajubSession` is the API client of one payment; `CheckoutController` is the whole sheet without its UI (states, callbacks, results, watching, cancel), for an app that wants its own screens.

```kotlin
val session = WajubMobile.createSession(authorizationToken)
val data = session.loadSession()                    // amount, channels, branding…
val config = session.getSdkConfig()

val outcome = session.process(
    ProcessRequest("cm.mobile", mapOf("phone" to "+237677123456", "country" to "CM", "operator" to "mtn")),
)
when (outcome) {
    is ProcessOutcome.Complete -> Unit
    is ProcessOutcome.Processing -> Unit                                  // kind: Approval, Ussd, Pending — watch
    is ProcessOutcome.RequiresAction -> Unit                              // a page (url) or a step (step, stepId)
    is ProcessOutcome.Failed -> showError(outcome.error.message)
}

session.watch().events().collect { event ->                              // StatusChanged, Succeeded, Failed, Cancelled, Expired, StillProcessing
}
```

- `process(ProcessRequest)` adds the device signals (`data._client`), sends an `Idempotency-Key` that its automatic retries reuse, and maps every answer of the API. An answer that never came (or a 5xx) is `Processing` without a transaction: watch the status, never show a failure.
- `openClientSession(fields, returnUrl)` / `confirmClientSession(id)`: cards on the PSP's own page.
- `verifyStep(kind, stepId, values)` / `resendStep(kind, stepId)`: OTP, PIN, phone, birthday (`YYYY-MM-DD`), address, sandbox 3-D Secure.
- `status()`, `cancel()`, `watch(realtime, seenProcessing)`.
- `MobileMoney` and `MobileMoneyRules` give the web checkout's number rules (national digits, validity, operator detection, E.164, format as you type) and choices, from the shared `mobile-money.json` tables.

An app using only `wajub-mobile-core` on Android can set `WajubMobile.device` (`DeviceEnvironment`) to report the screen size and keep the install id in its no-backup files (`FileInstallIdStore(File(context.noBackupFilesDir, "wajub-install-id"))`); `wajub-mobile-compose` does it on its own at start-up. Without it the install id lives in memory on Android (a new one per process) and in `~/.wajub/install-id` on a plain JVM; the SDK never writes to the temporary directory. The fingerprint is computed once, off the main thread.

## Migrating from 1.2

Code written for 1.2 still compiles and gets what it got then; the new names sit beside the old ones, which are deprecated:

- `PaymentSheet(wajubSession, onDismiss, onResult)` → `PaymentSheet(session, PresentOptions(), onComplete)` (keep your handlers: `onDismiss` becomes a `Cancelled(dismissed)` result, `onResult` the result or `callbacks.onSuccess` / `onError`). The 1.2 sheet runs the new screens (French, failure screen with Try again, operators and their logos, approval and confirmation screens) and reports as 1.2.1 did: `onResult` with each answer to a payment request (`RequiresAction(PushApproval)` for a push, `RequiresAction(Redirect | Confirm | Confirm3ds)` for a provider page the sheet opens, `Complete`, `Processing`, and the outcome of a card paid on the provider's page) — never a second `Complete` from its own watching, so an app that watches the status itself, as 1.2 apps did, is not told twice. `onDismiss` comes at once when the payer closes it (not when your app removes it), and, as in 1.2.1, the payment is cancelled in the background only if nothing was submitted; once Pay was pressed it is never cancelled, even after a refused number or "Try again".
- `payMobileMoney`, `payCard`, `payCardHostedRedirect`, `payWallet`, `process(channel, data)`, `startClientSession`, `completeClientSession` → `process(ProcessRequest)`, `openClientSession`, `confirmClientSession`, which map every answer of the API (`ProcessOutcome`) and report errors as the spec says. The 1.2 methods keep their `PaymentResult` and their errors (the API's code and message; an `IOException` when no answer came back).
- `watchStatus`, `watchStatusPolling`, `RealtimeSubscriber`, `StatusPoller` → `watch()`. The 1.2 watchers keep their `Flow<PaymentResult>`, their `intervalMs` and their results for `succeeded` (`Complete`), `failed`, `cancelled` and `expired` (`Failed` with the status as code), and still have no time limit. What changed, each a 1.2.1 bug: they read `GET /pay/status` (1.2.1 reloaded the whole session every time); a session that is gone (`401`, `403`, `404`, `410`) ends the flow with `Failed` and the error the 1.2 calls give for that answer (an `AuthenticationError` for `401`, `404`, `410`) where 1.2.1 polled forever; a payment back to `pending` after `processing` (an attempt that ended without charging) ends it with `Failed` (the decline code, or `payment_attempt_ended`) where 1.2.1 reported `Processing` forever; a new value is emitted only when the result changes (`pending` then `processing` is one `Processing`, 1.2.1 sent two); errors back off 5 s, 10 s… up to a minute (1.2.1: `intervalMs` × failures); they end on `succeeded`, `refunded` and `partially_refunded`, no longer on `paid`, `success`, `successful` or `complete`, which the API never sends.
- `SessionData.branding` (every value as text, JSON `null` used to arrive as `"null"`) → `SessionData.brand`.
- The 1.2 methods send what they sent in 1.2 (`buildMobileMoneyRequest` keeps the number as given); the device signals (`data._client`) and the E.164 number come with `process(ProcessRequest)` and `MobileMoneyRules.requestData`. Every request, old or new, now carries `X-Wajub-Client`, `X-Request-Id` and `Accept-Language`, with its own timeout and the spec's retries.

## Development

```bash
export JAVA_HOME=/path/to/jdk-17      # Gradle 8.10.2 does not run on newer JDKs
./gradlew :wajub-mobile-core:test :wajub-mobile-compose:testReleaseUnitTest :wajub-mobile-compose:assembleRelease
```

The contract tests (`wajub-mobile-core/src/test/kotlin/com/wajub/mobile/contract`) are named after the spec's requirements (`PR-01`, `MM-05`…) and run against the API's real answers in `src/test/resources/fixtures`, synced from the shared spec with `sync-spec.sh kotlin` — never edit them by hand. The Compose UI tests run on the JVM with Robolectric.

## Documentation & support

- Mobile SDK docs: [docs.wajub.com/libraries/sdks/mobile](https://docs.wajub.com/libraries/sdks/mobile)
- Server SDKs (create payments): [docs.wajub.com/libraries/sdks](https://docs.wajub.com/libraries/sdks)

## License

MIT — see [LICENSE](LICENSE).
