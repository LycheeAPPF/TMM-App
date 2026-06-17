# Changelog

All notable changes to **Tesla Messages Manager (TMM)** are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] — 2026-06-17

### Added
- **In-car Grok assistant.** A static, Tesla-visible **“Grok”** contact lets the car
  message Grok by voice without opening the app — dictate a question via the car’s
  “reply to message” function and the answer is read aloud. Gated on privacy consent and
  an xAI API key. _Idea by **DaGeneral**._
- **Configurable voice-address contact.** An optional second contact that Tesla’s voice
  control recognises more reliably (a full first + last name). Dictating to it is
  redirected to the Grok session, so **Grok still replies as “Grok.”** Choose a preset
  (**“xAI Grok”** by default, or **“Elon Musk”**), enter a custom name, or switch it off
  — all under **Settings → Grok assistant**.
- `TeslaContactResync`, which forces the car to re-pull the phonebook after contact changes.
- Onboarding step to pick which apps are forwarded.

### Changed
- The Grok **reply name is fixed to “Grok.”**
- No apps are forwarded by default until you select them.

### Removed
- The earlier phonetic **“Grog” / “Grogg”** voice aliases, superseded by the single
  configurable voice-address contact.

### Fixed
- Grok settings text fields dropping keystrokes.

## [0.1.0] — 2026-06-16

### Added
- Initial public release: bridges phone messenger notifications (WhatsApp, Telegram,
  Signal, …) to a Tesla over Bluetooth MAP and routes dictated replies back to the
  originating app.

[0.2.0]: https://github.com/LycheeAPPF/TMM-App/releases/tag/v0.2.0
