# Tesla Messages Manager (TMM)

An independent, unofficial Android app that bridges your phone's messages to a Tesla's
infotainment over Bluetooth MAP — and adds an in-car **Grok** (xAI) voice assistant you can talk
to hands-free while driving.

> **Disclaimer:** Not affiliated with, endorsed by, or sponsored by Tesla, Inc. or xAI. "Tesla"
> and "Grok" are trademarks of their respective owners. Use at your own risk.

The app's UI is currently German.

## What it does

- **Message bridge** — forwards notifications from messengers (WhatsApp, Telegram, Signal, …) to your
  Tesla so the car reads them aloud, and routes your dictated replies back to the original app.
- **In-car Grok assistant** — start a chat, dictate a question via the car's built-in "reply to
  message" function; the question goes to the xAI Grok API and the answer is injected back as a
  message that the Tesla reads aloud. The default system prompt is tuned for short, TTS-friendly,
  hands-free answers, and you can set your name + edit the prompt in the app.

## How the Grok assistant works

```
Tap "AI chat"  ──►  app injects a "Grok" message  ──►  Tesla reads it aloud
You dictate a question (car reply function)        ──►  app captures it
        ──►  xAI Grok Responses API  ──►  answer cleaned for TTS  ──►  injected back  ──►  read aloud
```

The LLM conversation context is in-memory only and expires after ~120 s of inactivity.

## Requirements

- Android 13+ (minSdk 33).
- A Tesla that exposes messaging over Bluetooth MAP (e.g. MCU2).
- An xAI API key (for the Grok assistant; the bridge works without one).
- Permissions granted in-app: Default SMS app role, Notification access, POST_NOTIFICATIONS, Contacts.

## Install

Download the latest signed APK from the
[Releases](https://github.com/LycheeAPPF/TMM-App/releases) page and install it (you may need to
allow installing from unknown sources). On first launch, complete the setup cards.

## Get an xAI API key

The app never ships an API key. Create one at <https://console.x.ai> and paste it into
**Settings → AI Assistant → xAI API key**. It is stored **encrypted in the Android Keystore**
(AES-256-GCM) and excluded from device backups.

## Build from source

Requirements: JDK 17 and the Android SDK (API 36).

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Maintainers building a **signed release** APK: see [docs/RELEASING.md](docs/RELEASING.md).

## Tests

```bash
./gradlew :app:test     # JVM unit tests
./gradlew :app:lint     # Android lint
```

## Privacy

- Your xAI API key is stored **encrypted** (Android Keystore, AES-256-GCM) and excluded from backups.
- The Grok **conversation context** is kept **in memory only** and discarded after ~120 s of inactivity.
- **Reply attempts** (including dictated text) are logged to a local on-device table
  (`reply_history`) used for diagnostics and retry, and are included in diagnostics exports. This
  data never leaves your device except for the messages you send to your chosen LLM provider (xAI).
- All LLM traffic is HTTPS-only.

## Tech stack

Kotlin · Jetpack Compose · Hilt · Room · DataStore · WorkManager · Retrofit/OkHttp ·
kotlinx.serialization · xAI Responses API.

## License

[GPLv3](LICENSE). Shares conceptual lineage with QKSMS and notification-forwarder patterns; no code
was directly forked.
