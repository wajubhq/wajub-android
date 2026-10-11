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
  (`5xx`) an `ApiError`. Both used to come back as a retryable `PaymentError`.

Releases before 1.2.1 are described in the Git history.
