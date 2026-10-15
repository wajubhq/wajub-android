# Changelog

## 1.3.0

- `PaymentSheet(PresentOptions(sessionToken = …)) { result -> }` shows the new payment sheet and
  closes exactly once with a `CheckoutResult`: `Complete`, `Processing`, `Failed`, or `Cancelled`
  with its reason. Nine optional callbacks follow the payment (`onReady`, `onStateChange`,
  `onLoadError`, `onPaymentStarted`, `onRedirect`, `onSuccess`, `onError`, `onCancel`, `onComplete`),
  and `onStateChange` reports 17 named states.
- The sheet speaks French and English: `PresentOptions.locale`, else the payment's language, else the
  device's. `ErrorCatalog` gives every error code's message in both.
- A refused payment shows a failure screen with **Try again**, which goes back to the form with what
  the payer typed; a refused field stays on the form, next to the field. It used to replace the whole
  form with the error.
- Mobile Money is one form: the country, the operators with their logos (or a brand badge), the number
  with its dial code and formatted as typed, and the operator detected from the number. Changing the
  country now changes the operators offered. Numbers are read by each country's current numbering
  plan (a number of an earlier plan in its current form, the leading 0 kept where it belongs to the
  number), the operator is recognised from the number in more countries, and in a sandbox payment the
  published test numbers are accepted. After **Pay**, the payer sees "Approve the payment on
  your phone" with the operator's instruction, also when the sheet reopens on an approval under way.
- Confirmation steps have their own screens: a code, the card PIN, a phone number, a date of birth
  (typed, or picked in a date picker), a billing address, and the sandbox 3-D Secure code, each with a
  resend link after a minute. When the provider asks for a second step (a code after the date of
  birth, a 3-D Secure page after the PIN), the sheet follows it. They used to fail with
  `unsupported_action`.
- A payment already paid when the sheet opens shows the success screen; one that has ended says so
  (`session_terminal_*`); one already under way shows its approval or "Payment in progress" screen.
- New `PresentOptions`: `appearance` (presets, variables, light, dark or auto, `inherit` from your
  Material theme, the merchant's branding underneath), `loader` (skeleton or spinner), `readyTimeout`
  (an error with **Retry** after 15 seconds), `returnUrl`, `mobileMoney` (`country`, `countryLocked`,
  `operatorMode`), `cancelOnDismiss` and `autoCloseSuccess`. Fonts your app ships reach the sheet with
  `WajubFonts.register(name, fontFamily)`; `inherit` also takes your theme's body font.
- Closing the sheet before any payment attempt, or back on the form after a refusal or
  **Try again**, cancels the payment (unless `cancelOnDismiss = false`). Closing it while a payment is
  under way resolves `Processing` and leaves the payment running; closing it on the failure screen
  resolves `Failed` with that error. **Cancel payment** is offered on the same terms.
- The sheet never shows a failure when it cannot be sure of the outcome: it shows "Payment in
  progress" and keeps waiting. When the payment turns out not to have been made, the payer is back on
  the form with a connection message and can pay again without being charged twice.
- The sheet survives configuration changes, a `WajubSession` created in the composable stays one
  sheet, and the callbacks called after a rotation are those of the screen now shown. When the screen
  that shows the sheet goes away for good, the sheet resolves `Cancelled` (`host_gone`) before any
  payment attempt, `Processing` once one started, `Complete` after a success, and never cancels the
  payment.
- New for your own UI: `CheckoutController` (the whole sheet without its UI), and on `WajubSession`
  `process(ProcessRequest)`, `openClientSession`, `confirmClientSession`, `verifyStep`, `resendStep`,
  `status()` and `watch()`. `SessionData.brand` gives the merchant's branding with its real types;
  `SessionData.branding` is deprecated. `SessionTransaction.errorCode` is new.
- Deprecated, and still working as in 1.2.1: `PaymentSheet(wajubSession, onDismiss, onResult)`,
  `payMobileMoney`, `payCard`, `payCardHostedRedirect`, `payWallet`, `process(channel, data)`,
  `startClientSession`, `completeClientSession`, `watchStatus`, `watchStatusPolling`,
  `RealtimeSubscriber` and `StatusPoller`. The 1.2 sheet shows the new screens and reports as 1.2.1
  did: `onResult` with each answer to a payment request (never a second `Complete` of its own, so an
  app that watches the status itself is not told twice), `onDismiss` at once when the payer closes it,
  and a cancel only when nothing was submitted.
- Fixed in `watchStatus`, `watchStatusPolling`, `RealtimeSubscriber` and `StatusPoller`: a session
  that has ended now ends the flow with `Failed` (`AuthenticationError`, not retryable, for an
  unknown or ended session) instead of watching forever; a payment refused after it started ends the
  flow with `Failed` and the decline code (or `payment_attempt_ended`) instead of `Processing`
  forever; a new value is emitted only when the result changes; a refunded payment ends the flow with
  `Complete`.
- `SessionChannel.nameFr` holds the channel's French name; it was always `null`. A channel without
  limits has `null` `minAmount`/`maxAmount` (was `NaN`), and a payment without a language has
  `SessionData.locale` `"en"` (was the text `"null"`).
- Compatibility: code written for 1.2.1 still compiles and runs, from Kotlin and from Java, including
  the 1.2.1 constructors and `copy` of the model classes. `PresentOptions.toString()` never prints the
  session token.
- Nothing to add to your R8 rules for a minified app. `wajub-mobile-core` no longer depends on Moshi.
- The sheet runs with Material 3 1.3 (Compose BOM 2024.12.01) and 1.4 (BOM 2025.09.01) alike.

## 1.2.1

- `watchStatus()` reports a change of status as soon as it happens, also for a change that happened
  before the watch started; it used to wait for the next check.
- An ended session is an `AuthenticationError` with `retryable = false`, and a server error an
  `ApiError`; both used to be a retryable `PaymentError`. An unknown session token is not retryable
  either.
- Closing `PaymentSheet` before submitting anything also cancels the payment; `onDismiss` is still
  called. Once an attempt was under way the payment is left alone, since a Mobile Money approval can
  still land. `WajubSession.cancelIfNothingSubmitted()` does the same for a sheet of your own.

Releases before 1.2.1 are described in the Git history.
