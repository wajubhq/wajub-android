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

## Development

```bash
./gradlew :wajub-mobile-core:test
```

## Documentation & support

- Mobile SDK docs: [docs.wajub.com/libraries/sdks/mobile](https://docs.wajub.com/libraries/sdks/mobile)
- Server SDKs (create payments): [docs.wajub.com/libraries/sdks](https://docs.wajub.com/libraries/sdks)

## License

MIT — see [LICENSE](LICENSE).
