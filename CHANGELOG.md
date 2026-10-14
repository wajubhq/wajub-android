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
- Confirmation steps work: OTP, card PIN, phone, date of birth and billing address
  (`/pay/{step}/verify`, resend after 60 s), and the sandbox 3-D Secure email code. They used to fail
  with `unsupported_action`.
- `PresentOptions` adds `appearance` (Wajub.js's appearance object: presets, variables, light, dark or
  auto, `inherit` from your Material theme, the merchant's branding underneath), `loader` (skeleton or
  spinner), `readyTimeout` (15 s, then `ready_timeout` with Retry), `returnUrl`, `mobileMoney`
  (`country`, `countryLocked`, `operatorMode`), `realtime` (your own Reverb or Soketi host, or polling
  only), `cancelOnDismiss` and `autoCloseSuccess`.
- The status is read with `GET /pay/status` every 5 s (15 s while the socket is connected, slower after
  2 minutes), errors back off up to a minute, and the watch decides on every status: a `410` or a token
  that is no longer valid ends it (1.2 polled forever), an attempt that ended without charging is
  reported, and after 15 minutes the result is `processing`. The socket follows the session's `echo`
  host, port and TLS.
- Every request sends `X-Wajub-Client`, `X-Request-Id` and the device's `Accept-Language`, has its own
  timeout (30 s, 60 s for payments), and retries what is safe: reads, and payments with the same
  `Idempotency-Key`. A payment request that gets no answer is never shown as a failure: the sheet waits
  for the status, and if the payment is still pending a minute later it goes back to the form, the next
  Pay reusing the same key. `/pay/process` sends the device signals the API's risk checks read
  (`data._client`, with a fingerprint kept per install).
- Merchant branding is read with its real types: JSON `null` used to arrive as the text `"null"` on
  Android. Read `SessionData.brand`; `SessionData.branding` is deprecated.
- New on `WajubSession` for your own UI: `process(ProcessRequest)` (a `ProcessOutcome` for every answer
  of the API), `openClientSession`, `confirmClientSession`, `verifyStep`, `resendStep`, `status()` and
  `watch()`. `CheckoutController` is the whole sheet without its UI.
- `PaymentSheet(wajubSession, onDismiss, onResult)`, `payMobileMoney`, `payCard`, `payCardHostedRedirect`,
  `payWallet`, `process(channel, data)`, `startClientSession`, `completeClientSession`, `watchStatus`,
  `watchStatusPolling`, `RealtimeSubscriber` and `StatusPoller` are deprecated; they keep working as in
  1.2.1. The 1.2 sheet now runs the new screens and still calls `onResult` with each answer, then with
  the payment's outcome.

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
