# Wajub Android SDK (Kotlin)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Native Android checkout SDK for [Wajub](https://wajub.com). Presents mobile-money and card payments in-app — **no WebView**. Calls `https://api.wajub.com/pay/*` with a session `authorization_token` from your [server SDK](https://docs.wajub.com/libraries/sdks).

## Architecture

```
Your backend (server SDK, sk_)
        ↓ authorization_token
Android app
        ↓
WajubMobile.createSession(token) → GET /pay/session, GET /pay/sdk-config
        ↓
PaymentSheet (Compose)
        ↓
POST /pay/process → PaymentResult → Custom Tabs for redirect/3DS
        ↓
watchStatus() → Pusher or polling until terminal
```

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| `wajub-mobile-core` | `com.wajub:wajub-mobile-core` | HTTP client, mappers, Pusher/polling |
| `wajub-mobile-compose` | `com.wajub:wajub-mobile-compose` | Jetpack Compose `PaymentSheet` |

## Requirements

| Requirement | Version |
|-------------|---------|
| `minSdk` | 24 (Android 7.0) |
| `compileSdk` / `targetSdk` | 35 |
| JVM target | 17 |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.10.2 (committed wrapper at repo root) |

## Features

| Flavor | Support |
|--------|---------|
| Mobile Money (`form`) | Native Compose fields |
| Card (`stripe_elements`) | Stripe Android SDK tokenization |
| Card (`client_session`) | Paystack / Flutterwave / Adyen Pay by Link hosted checkout in Custom Tabs |
| Card (`hosted_redirect`) | PayPal / Mollie / Paddle / Kkiapay / FedaPay / PayDunya / CinetPay card page in Custom Tabs, after the sdk-config `requiredFields` (e.g. email, CinetPay billing) — `payCardHostedRedirect(channelSlug, billing)` |
| One-tap wallets, e.g. Djamo (`wallet` channel, `form`, no `requiredFields`) | "Wallet" tab — headless: `payWallet(channelSlug)` |
| Redirect / 3DS | Chrome Custom Tabs |
| Realtime | Pusher/Reverb + polling fallback |

## Quick start

Create a payment on your backend, then pass the `authorization_token` to the SDK:

```kotlin
val session = WajubMobile.createSession(authorizationToken)

PaymentSheet(
    wajubSession = session,
    onDismiss = { },
    onResult = { result -> /* PaymentResult */ },
)
```

## Headless integration

Build your own UI and call the session API directly:

```kotlin
val result = session.payCard("card", paymentMethodId = "pm_...")
session.watchStatus().collect { update -> /* Pusher or poll */ }
```

## Card via Paystack / Flutterwave (client sessions)

When the card channel's `sdk-config` entry has `clientSession == true`, the card is
entered on the PSP's own checkout — the SDK never sees card data. `PaymentSheet`
handles this for you: "Pay by card" opens the PSP page in Custom Tabs and, once the
payer is back in the app, verifies the payment server-side.

Headless, the flow is start → PSP UI → complete:

```kotlin
val start = session.startClientSession(channel = "card", email = "payer@example.com")
val clientSession = (start as? PaymentResult.RequiresAction)?.clientSession
    ?: return handle(start) // Failed / Complete

// Default: the PSP's hosted checkout in Custom Tabs.
HostedRedirectLauncher.open(context, clientSession.hostedUrl!!)

// …when the payer returns to the app:
val result = session.completeClientSession(clientSession.id)
// Complete, or Processing while the PSP hasn't settled yet (keep watchStatus() running).
```

To use the PSP's **native** Android SDK instead of the hosted page, launch it with the
session fields — Paystack: `clientSession.accessCode` (+ `publicKey`); Flutterwave:
`clientSession.reference` (tx_ref), `amount`, `currency`, `publicKey`, `encryptionKey` —
then call `completeClientSession(clientSession.id)` when the PSP SDK returns. Never
trust the PSP SDK's client-side result on its own: `completeClientSession` re-verifies
with the PSP. `startClientSession(..., restart = true)` replaces a session that expired
on the PSP side. A failed completion throws `WajubError` (HTTP 402).

## Development

```bash
./gradlew :wajub-mobile-core:test
```

## Documentation & support

- Mobile SDK docs: [docs.wajub.com/libraries/sdks/mobile](https://docs.wajub.com/libraries/sdks/mobile)
- Server SDKs (create payments): [docs.wajub.com/libraries/sdks](https://docs.wajub.com/libraries/sdks)

## License

MIT — see [LICENSE](LICENSE).
