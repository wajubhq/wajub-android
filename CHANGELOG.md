# Changelog

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
