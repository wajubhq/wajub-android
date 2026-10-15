# Changelog

## 1.3.0

- The SDK follows the Wajub mobile contract (spec v2), shared with the Flutter, React Native and iOS
  SDKs and with Wajub.js 1.8: `PaymentSheet(PresentOptions(sessionToken = …)) { result -> }` takes the
  same options, fires the same callbacks (`onReady`, `onStateChange`, `onLoadError`, `onPaymentStarted`,
  `onRedirect`, `onSuccess`, `onError`, `onCancel`, `onComplete`) in the same order, reports Wajub.js's
  17 states, and closes exactly once with a `CheckoutResult` (`Complete`, `Processing`, `Failed`,
  `Cancelled` with its reason). The sheet survives configuration changes, and settles (`host_gone`, or
  `processing` once an attempt started) when the screen that shows it goes away for good.
- The sheet speaks French and English: `PresentOptions.locale`, else the session's language, else the
  device's. Errors come from one catalogue (`ErrorCatalog`), with the web checkout's messages.
- A refused payment shows a failure screen with Try again, which goes back to the form with what the
  payer typed; a refused field (a `422`) stays on the form, next to the field. It used to replace the
  whole form with the error.
- Mobile Money is one form: the country, the operators of an aggregate channel such as `cm.mobile` with
  their logos (or a brand badge when a logo is missing or does not load), the number with its dial code
  and grouped as typed, the operator detected from the number and preselected until the payer picks one,
  the web checkout's checks (`phone_required`, `invalid_phone`, `operator_mismatch`, `operator_required`),
  the number sent in E.164 with the `operator` an aggregate needs. Changing the country now changes the
  operators and the country sent; it used to stay on the first channel's.
- A push approval shows "Approve the payment on your phone" with the provider's instruction, and so does
  a session reopened while its approval runs. An already paid session opens on the success screen; a
  failed, cancelled or expired one says so (`session_terminal_*`).
- Confirmation steps work: OTP, card PIN, phone, date of birth (typed, or picked in the platform's
  date picker) and billing address (`/pay/{step}/verify`, each with its resend link after 60 s), and the
  sandbox 3-D Secure email code. They used to fail with `unsupported_action`.
- `PresentOptions` adds `appearance` (Wajub.js's appearance object: presets, variables, light, dark or
  auto, `inherit` from your Material theme, the merchant's branding underneath), `loader` (skeleton or
  spinner), `readyTimeout` (15 s, then `ready_timeout` with Retry), `returnUrl`, `mobileMoney`
  (`country`, `countryLocked`, `operatorMode`), `realtime` (your own Reverb or Soketi host, or polling
  only), `cancelOnDismiss` and `autoCloseSuccess`. Fonts your app ships (`res/font`, assets) reach the
  sheet with `WajubFonts.register(name, fontFamily)`; `inherit` also takes your theme's body font.
- Closing the sheet follows one rule on every platform: the payment is cancelled (`POST /pay/cancel`,
  with `cancelOnDismiss`) only when the session is loaded, still `pending`, and no attempt is waiting
  for its answer — before Pay, or after a definitive refusal or "Try again". From Pay until the API
  answers for sure, and on the approval, USSD, "Payment in progress" and step screens, a dismiss is
  `processing` and never cancels; while loading it is `cancelled` without a call; on the failure screen
  it is `failed` with that error (the cancel is still sent in the background when it can be). "Cancel
  payment" is offered on the same terms. A definitive refusal is a decline, a refusal the status
  reports, a confirmation step over for good, a 3-D Secure code refused or timed out, and a declined
  client session. A session already `processing` when it loads shows its screen at once, without
  waiting for sdk-config.
- The status is read with `GET /pay/status` every 5 s (15 s while the socket is connected, slower after
  2 minutes), errors back off up to a minute, and the watch decides on every status: a `410` or a token
  that is no longer valid ends it (1.2 polled forever), an attempt that ended without charging is
  reported, and after 15 minutes the result is `processing` — counted on the device's monotonic clock
  from the Pay press, never by comparing the server's timestamps with the phone's clock. The socket
  follows the session's `echo` host, port and TLS.
- Every request sends `X-Wajub-Client`, `X-Request-Id` and the device's `Accept-Language`, has its own
  timeout (30 s, 60 s for payments), and retries what is safe: reads, and payments with the same
  `Idempotency-Key`. A payment request that gets no answer is never shown as a failure: the sheet waits
  for the status, and if the payment is still pending a minute later it goes back to the form, the next
  Pay reusing the same key (a refusal the payment carried before this attempt, even from an earlier
  sheet, is not taken as its answer; a new one is). With Altcha on, nothing is sent without a solved
  challenge: a challenge that cannot be fetched or solved leaves the payer on the form with the error. `/pay/process` sends the device signals the API's risk checks read
  (`data._client`, with a fingerprint kept per install).
- Merchant branding is read with its real types: JSON `null` used to arrive as the text `"null"` on
  Android. Read `SessionData.brand`; `SessionData.branding` is deprecated.
- New on `WajubSession` for your own UI: `process(ProcessRequest)` (a `ProcessOutcome` for every answer
  of the API), `openClientSession`, `confirmClientSession`, `verifyStep`, `resendStep`, `status()` and
  `watch()`. `CheckoutController` is the whole sheet without its UI.
- `PaymentSheet(wajubSession, onDismiss, onResult)`, `payMobileMoney`, `payCard`, `payCardHostedRedirect`,
  `payWallet`, `process(channel, data)`, `startClientSession`, `completeClientSession`, `watchStatus`,
  `watchStatusPolling`, `RealtimeSubscriber` and `StatusPoller` are deprecated; they keep working as in
  1.2.1, with the same results and errors. The 1.2 sheet runs the new screens and reports as 1.2.1 did:
  `onResult` with each answer to a payment request (never a second `Complete` from its own watching, so
  an app that watches the status itself is not told twice), `onDismiss` at once when the payer closes
  it, and a cancel in the background only when nothing was submitted — never once Pay was pressed, even
  after a refused number or "Try again".
- The sheet is keyed on the session token, so a `WajubSession` created in the composable stays one sheet
  across recompositions, and after a configuration change the callbacks called are those of the screen
  now shown. A result that lands while the screen is being recreated reaches the new one.
- Fixed in the 1.2 watchers (`watchStatus`, `watchStatusPolling`, `RealtimeSubscriber`, `StatusPoller`),
  which keep their flows, `intervalMs`, results and lack of time limit: they read `GET /pay/status`
  instead of reloading the session; a session that is gone (`401`, `403`, `404`, `410`) ends the flow
  with `Failed` and the error the 1.2 calls give for that answer (`AuthenticationError`, not retryable,
  for `401`, `404` and `410`) where they polled forever; a payment back to `pending` after `processing`
  ends it with `Failed` (the decline code, or `payment_attempt_ended`) where they reported `Processing`
  forever; a value is emitted only when the result changes (`pending` then `processing` is one
  `Processing`, 1.2.1 sent two); errors back off 5 s, 10 s… up to a minute. They end on `succeeded`,
  `refunded` and `partially_refunded` (`Complete`), and no longer on `paid`, `success`, `successful` or
  `complete`, which the API never sends.
- `SessionChannel.nameFr` is read from the API's `name.fr` key. 1.2.1 read `name_fr`, which the API
  never sends, so it was always `null`.
- Values the API leaves out are read as such, as the 1.2 calls return them too: a channel without
  `min_amount`/`max_amount` has `null` limits (1.2.1: `NaN`), a session without a `locale` has
  `SessionData.locale` `"en"` (1.2.1: the text `"null"`; `sessionLocale` is `null`), and an `echo`
  without a `key` has `EchoConfig.key` `""` (1.2.1: `"null"`). `Idempotency-Key` is a bare UUID
  (1.2.1: `wajub-` and a UUID), as the API documents it.
- Compatibility: the 1.2.1 constructors of `PayClient` (`new PayClient()` from Java included),
  `MobileMoneyInput`, `WajubError`, `SessionTransaction`, `ProcessingContext`, `SessionChannel` and
  `SessionData` are kept, and so is the 1.2.1 `copy` of `SessionTransaction`, `ProcessingContext`,
  `SessionChannel`, `SessionData`, `MobileMoneyInput` and the `Raw*` wire models, for Java callers and
  for code compiled against 1.2.1 (it keeps the fields 1.3 added). `SessionTransaction.errorCode` is
  new. `PresentOptions.toString()` no longer prints the session token.
- Both artifacts ship R8 rules for the Pusher client (`-dontwarn` for its optional SLF4J binding), so
  a minified app builds without rules of its own. `wajub-mobile-core` no longer depends on Moshi, which
  it did not use.
- The sheet uses stable Compose APIs only (no experimental Material 3 menu or date picker), so it runs
  with Material 3 1.3 (Compose BOM 2024.12.01) and 1.4 (BOM 2025.09.01) alike. The device fingerprint
  is computed once and off the main thread; solving the Altcha challenge stops when the sheet closes.

## 1.2.1

- `watchStatus()` now uses realtime when it is on. The Pusher client refused the listener the SDK
  bound to the private status channel, so the socket was never opened and every update came from
  polling. The channel was also named after your own reference, while Wajub publishes on the
  payment's id. The SDK now binds a private-channel listener and subscribes on the payment's id
  (`transaction.id`).
- Polling keeps running next to the socket for the whole watch, so a status the socket did not
  carry (one that changed before the subscription, or a missed event) still reaches the flow
  within `intervalMs`. With realtime on, the flow emits when the result changes and ends on the
  first final one.
- An ended session (`410`) is an `AuthenticationError` with `retryable = false`, and a server error
  (`5xx`) an `ApiError`. Both used to come back as a retryable `PaymentError`. A token that is not
  a session (`401`, `404`) is not retryable either: no retry of the same token can succeed.
- Closing `PaymentSheet` before submitting anything also cancels the payment on Wajub
  (`POST /pay/cancel`), as the hosted checkout and the Flutter SDK do; `onDismiss` is still
  called. When an attempt was under way the payment is left alone, since a Mobile Money approval
  can still land. `WajubSession.cancelIfNothingSubmitted()` does the same for a sheet of your own.

Releases before 1.2.1 are described in the Git history.
