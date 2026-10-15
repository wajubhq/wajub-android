# Wajub Android SDK (Kotlin)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Take Mobile Money, card and wallet payments in your Android app with a ready-made payment sheet built with Jetpack Compose. No WebView.

## Installation

```kotlin
// build.gradle.kts of your app
dependencies {
    implementation("com.wajub:wajub-mobile-compose:1.3.0")
}
```

| Artifact | What it is for |
|---|---|
| `com.wajub:wajub-mobile-compose` | The `PaymentSheet` composable. It brings `wajub-mobile-core` with it |
| `com.wajub:wajub-mobile-core` | The same payment flow without any UI, for an app that draws its own screens (see [Your own UI](#your-own-ui)) |

Both are on Maven Central.

| Requirement | Value |
|---|---|
| `minSdk` | 24 (Android 7.0) |
| `compileSdk` | 35. The library sets no `targetSdk`: your app keeps its own |
| Java and Kotlin target | 17 |
| Compose | Material 3 1.3 (BOM 2024.12.01) or 1.4 (BOM 2025.09.01) |

There is nothing to add to your manifest or your R8 rules: the artifacts bring their own.

## Quick start

1. Your server creates the payment with `POST /payments` and its secret key, and hands the app the `authorization_token`. The secret key never goes into the app.
2. The app shows `PaymentSheet` with that token. `onComplete` receives the result once, when the sheet closes:

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

3. Your server confirms the payment, with the `payment.succeeded` webhook or `GET /payments/{id}`, before it delivers. The result in the app is what the payer saw, not proof of payment.

The sheet survives configuration changes such as a rotation or a change of language. Showing a second `PaymentSheet` while one is open resolves the second at once with `Failed` and the code `sheet_already_presented`, and leaves the first alone.

## Options

| Option | Default | What it does |
|---|---|---|
| `sessionToken` | — | The `authorization_token` from your server |
| `locale` | `null` | `"fr"` or `"en"`. Without it, the payment's language, else the device's |
| `appearance` | `null` | The sheet's look, see [Appearance](#appearance) |
| `loader` | `LoaderStyle.Skeleton` | `Skeleton`, pulsing placeholder blocks, or `Spinner` |
| `readyTimeout` | `15000` | Milliseconds a sheet may load before it shows an error with **Retry** (`ready_timeout`); `0` disables it |
| `returnUrl` | `null` | An https App Link back into your app, where the payer lands after a payment provider's page. Without it, the payer comes back to the app by hand |
| `mobileMoney` | `MobileMoneyOptions()` | `country` (ISO-2 to start on), `countryLocked` (hides the country selector), `operatorMode` (`Choose` lists the operators; `Detect` shows the operator detected from the number in the field) |
| `cancelOnDismiss` | `true` | Closing the sheet before any payment attempt cancels the payment. `false` keeps it open so you can show the sheet again |
| `autoCloseSuccess` | `3000` | Milliseconds before the success screen closes itself; `0` keeps it until the payer taps **Done** |
| `callbacks` | `CheckoutCallbacks()` | See [Callbacks](#callbacks) |

To use your own `OkHttpClient` (a proxy, certificate pinning, logging), create the session yourself, once per token:

```kotlin
val session = remember(token) { WajubMobile.createSession(token, okHttpClient) }
PaymentSheet(session, options) { result -> handle(result) }
```

### Closing the sheet

The payer closes the sheet with its close button, by dragging it down, by pressing Back, or by tapping outside it.

| When the payer closes it | Result | The payment |
|---|---|---|
| While the payment loads | `Cancelled`, `Dismissed` | Unchanged |
| Before any payment attempt, or back on the form after a refusal or **Try again** | `Cancelled`, `Dismissed` | Cancelled, unless `cancelOnDismiss = false` |
| While a payment is under way: approval, USSD code, confirmation step, "Payment in progress" | `Processing` | Keeps running: confirm on your server |
| On the failure screen | `Failed`, with the error shown. `onCancel` does not fire | Cancelled when it still can be, unless `cancelOnDismiss = false` |

**Cancel payment**, offered on the method list and on a form, cancels the payment for good and resolves `Cancelled` with `PayerCancelled` and the merchant's `redirectUrl`.

Your app removing the composable, or finishing its activity, is not the payer closing it: the sheet resolves `Cancelled` with `HostGone` before any payment attempt, `Processing` once one started, `Complete` after a success, and never cancels the payment.

## Callbacks

All optional, all on the main thread. A callback that throws does not break the sheet.

```kotlin
PresentOptions(
    sessionToken = token,
    callbacks = CheckoutCallbacks(
        onReady = { event -> },            // sessionId, environment ("sandbox"/"live"), amount, currency, methods
        onStateChange = { event -> },      // event.state: CheckoutState, event.method: "mobile_money" | "card" | "wallet" | "unknown"
        onLoadError = { error -> },        // the sheet cannot offer a payment
        onPaymentStarted = { event -> },   // the payment request is accepted: from now on, closing resolves Processing
        onRedirect = { event -> },         // right before a payment provider's page opens (event.url)
        onSuccess = { payment -> },        // PaymentSuccess: id, reference, amount, currency, method, timestamp, receiptUrl
        onError = { error -> },            // every failure the payer sees (not field validation)
        onCancel = { event -> },           // reason: payer_cancelled | dismissed | host_gone, redirectUrl
        onComplete = { result -> },        // the same CheckoutResult as PaymentSheet's onComplete
    ),
)
```

### States

`StateChangeEvent.state` is a `CheckoutState`; its `value` is the state's name as a string (the constant `THREE_DS_REQUIRED` has the value `3DS_REQUIRED`).

| `CheckoutState` | Screen |
|---|---|
| `INITIATED`, `COLLECTING_DETAILS`, `PROCESSING` | Method list, a method's form, the form with Pay busy |
| `APPROVAL_REQUIRED`, `USSD_REQUIRED` | "Approve the payment on your phone", or a USSD code to dial |
| `OTP_REQUIRED`, `PIN_REQUIRED`, `PHONE_REQUIRED`, `BIRTHDAY_REQUIRED`, `ADDRESS_REQUIRED`, `THREE_DS_REQUIRED`, `VERIFYING` | A confirmation step, then its check |
| `PENDING` | "Payment in progress", with **Continue payment** when a provider's page can be reopened |
| `SUCCESS`, `FAILED`, `EXPIRED`, `CANCELLED` | Outcomes, with **Try again** on a retryable failure |

### Results

| `CheckoutResult` | When |
|---|---|
| `Complete(payment, transaction)` | The sheet closed after a success, also when the payment was already paid as it opened |
| `Processing(transaction)` | The sheet closed while a payment was under way. Money may still move: confirm on your server |
| `Failed(error, transaction)` | The sheet closed on a failure, an expired payment, a load error, or a payment cancelled elsewhere (`payment_cancelled`) |
| `Cancelled(reason, redirectUrl)` | **Cancel payment**, a close before any payment attempt, or your screen going away first |

The sheet never shows a failure when the outcome is not known yet: it shows "Payment in progress", and closing it then resolves `Processing`. If your app's process dies, no result arrives: at the next launch, ask your server.

## Errors

`WajubError` extends `Exception`. The sheet hands it to `onLoadError`, `onError` and `CheckoutResult.Failed`.

| Property | What it is for |
|---|---|
| `type` | `WajubErrorType`: `ApiError`, `AuthenticationError`, `InvalidRequestError`, `PaymentError`, `RateLimitError` |
| `code` | What went wrong, `insufficient_funds` for instance |
| `message` | For the payer, in the sheet's language. Show it as is |
| `declineCode` | On a decline, the reason given for it |
| `retryable` | Whether **Try again** can work |
| `param` | The field at fault |
| `correlationId` | The request id to quote to Wajub support |
| `retryAfterSeconds` | The wait on `rate_limited` |
| `blockReference` | On `shield_blocked`, the reference to quote to support |

Codes you will meet: `session_not_found`, `session_terminal_expired`, `load_error`, `ready_timeout`, `no_payment_method`, `network_error`, `rate_limited`, `insufficient_funds`, `payment_declined`, `payment_attempt_ended` (refused, nothing was charged), `payment_failed`, `payment_cancelled`, `session_expired`, `shield_blocked`, `merchant_restricted`, `unsupported_action`, `redirect_unavailable`, `sheet_already_presented`. `ErrorCatalog.message(code, WajubLanguage.Fr, null)` gives a code's message in English or French.

## Appearance

```kotlin
PresentOptions(
    sessionToken = token,
    appearance = Appearance(
        theme = "stripe",                // a preset (stripe, night, flat, none) or a scheme (light, dark, auto)
        colorScheme = "auto",            // follows the phone
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
- Every value is a `String`. Colours are `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb()` or `rgba()`; lengths `8px`, `8` or `0.5rem`; weights `400`…`700`, `normal`, `bold`. A value that does not parse is ignored. `Appearance.fromMap(map)` reads the same object from decoded JSON.
- `inherit = true` takes what you leave unset from your app's `MaterialTheme`: its colours, its `shapes.small` and its body font.
- A `fontFamily` reaches the sheet once your app registers it, before showing the sheet (in `Application.onCreate`, say); `WajubFonts.unregister(name)` takes it back:

  ```kotlin
  WajubFonts.register("Poppins", FontFamily(Font(R.font.poppins_regular), Font(R.font.poppins_semibold, FontWeight.SemiBold)))
  ```
- The merchant's logo, business name and colours from the Dashboard fill what you leave unset, and a white-label merchant hides "Secured by Wajub".
- `disableAnimations = true` turns the sheet's animations off, as does the phone's reduced-motion setting.

## Your own UI

`CheckoutController`, in `wajub-mobile-core`, is the whole payment sheet without its UI: it runs the payment, fires the same callbacks and ends with the same `CheckoutResult`, while you draw the screens from its `screen` flow and forward the payer's actions (`selectMethod`, `setPhone`, `payMobileMoney`, `payCard`, `submitStep`, `tryAgain`, `cancelPayment`, `dismiss`…). Call `onReturn()` when your app is resumed and `hostGone()` when your screen goes away for good.

`WajubSession` is the lower-level client of one payment:

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

- `process(ProcessRequest)` answers with a `ProcessOutcome`. `Processing` without a transaction means the outcome is not known yet: watch, never show a failure.
- `openClientSession(fields, returnUrl)` and `confirmClientSession(id)`: a card paid on the provider's page.
- `verifyStep(kind, stepId, values)` and `resendStep(kind, stepId)`: the confirmation steps. A `StepResult.Accepted` whose `next` is set asks for another step or a page.
- `status()`, `cancel()`, `watch()`.
- `MobileMoney` checks, formats and reads a number (`isValid`, `formatAsYouType`, `detectOperator`), and `data()` gives the countries and their dial codes.

An app that uses only `wajub-mobile-core` on Android sets `WajubMobile.device` once at start-up (see `DeviceEnvironment`); `wajub-mobile-compose` does it for you.

## Privacy

To make the payment, the SDK sends Wajub what the payer types. For fraud prevention, it also sends basic device information and a random install identifier, which is not the advertising identifier. When the payer uses Stripe's card form, Stripe's SDK also collects what Stripe documents for its own fraud checks.

For Google Play's data safety form, declare it collected for *App functionality* and *Fraud prevention, security, and compliance*, shared with Wajub as your payment processor, encrypted in transit:

| Google Play data safety | What the SDK sends |
|---|---|
| Personal info, Name | First and last name, or the card holder, when the payment method asks for them |
| Personal info, Email address | When the card payment asks for it |
| Personal info, Phone number | The Mobile Money number, or a phone number a card payment asks for |
| Personal info, Address | Address, city, ZIP code and country, when the card payment asks for them |
| Personal info, Other info | A date of birth, when one is asked |
| Financial info, User payment info | The Mobile Money account, the Stripe card token, a card PIN when one is asked, and test cards in sandbox |
| Device or other IDs | The random install identifier and basic device information, for fraud prevention |

## Testing

Create the payment with a sandbox key, and the whole flow runs in sandbox, sheet included, which shows a "Test mode" badge. There is nothing to switch in the app. The test numbers and cards are on [Test scenarios](https://docs.wajub.com/developer-tools/testing/test-scenarios).

## Documentation & support

- Android SDK: [docs.wajub.com/libraries/sdks/kotlin](https://docs.wajub.com/libraries/sdks/kotlin)
- Mobile SDKs: [docs.wajub.com/libraries/sdks/mobile](https://docs.wajub.com/libraries/sdks/mobile)

## License

MIT — see [LICENSE](LICENSE).
