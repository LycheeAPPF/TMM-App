# Changelog

All notable changes to **Tesla Messages Manager (TMM)** are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] — 2026-06-24

### Added
- **Internet access for Grok (web search + X/Twitter search).** Two new opt-in toggles in
  the **Grok assistant** settings let Grok look things up live while you drive — *Allow
  internet access (web search)* and *Allow X/Twitter search*. Both are **off by default**.
  When on, your dictated question is answered with current information (weather, news,
  prices, live discussion) via xAI's native server-side search; the whole search runs on
  xAI's side and comes back as one spoken reply. The dictation goes to the **same xAI** as a
  normal reply (no second service, no second key), still with `store: false`. Note that
  searches cost extra — xAI bills tokens **plus** each successful search, and one question
  can trigger several — which is why it stays opt-in.
- **One-tap diagnostics export.** A new **Settings → Support → "Send diagnostics"**
  button (no developer mode required) writes a single redacted log file and opens the
  Android share sheet — so a tester can send **one file** to the maintainer and nothing
  else. The export bundles build/version info, settings, mappings, reply history and
  the **persistent** event log in one JSON. All personal data is removed: contact names
  are masked (`•••(len=N)`), conversation keys are hashed (schema prefix kept), and reply
  text is reduced to its length. **The file contains no message contents.** The detailed
  Diagnostics screen's toolbar action is now a **Share** button (was a local-only export).

### Changed
- With a search toggle on, Grok's behaviour prompt now tells it that it **can** look things
  up live (instead of the old "no live tools" wording), while still never reading out URLs,
  citation markers or markdown. Inline citations are disabled at the source and the spoken
  text is additionally cleaned of any links/URLs so nothing gibberish gets read aloud.
- **The diagnostics log is now persistent** — a rolling on-disk file (~4 MB, in
  `filesDir`) instead of in-memory only, so the export survives app restarts and long
  drives. The in-memory live-log tail is reloaded from disk on start.
- **More of the reply pipeline is logged** (and exportable): `NotificationReplyExecutor`
  now records each outcome branch (success via cache/rebuild, no action, no remote input,
  pending-intent canceled with reason, provider error) and `PendingIntentRebuilder`
  records the notification-listener / active-notification state. This makes "Reply not
  delivered" cases diagnosable from the exported file alone.

### Fixed
- **Privacy:** a contact name that was previously written to the in-memory log on capture
  is no longer logged (the log is exportable). Only metadata/lengths are logged.

## [0.5.0] — 2026-06-19

### Added
- **Local xAI API-key test.** A new **“Test key”** button in the **Grok assistant**
  settings (next to Save / Remove) checks whether the stored xAI key actually works —
  entirely on the phone, with **no Bluetooth or Tesla connection**. It sends a single
  minimal request to the xAI API and shows a colour-coded result: **green** = key valid;
  **red** = key rejected (auth error), no network, or server error; **yellow** = timeout
  or rate-limited. The ping goes against the default model (so a custom model name can’t
  be mistaken for an invalid key) and sends no personal data — just a fixed `"ping"` — so
  it can be used to validate the key during setup, before privacy consent is granted.

## [0.4.0] — 2026-06-18

### Added
- **English + German, with an in-app language switcher.** The whole interface is now
  fully localized — it was German-only before. **Settings → Language** offers
  **System / Deutsch / English**, and the same choice is exposed through the Android 13+
  per-app language picker (**Settings › Apps › TMM › Language**); the two stay in sync.
  English is the fallback for other system languages, while existing users keep German.
- **Localized Grok assistant.** The assistant’s spoken answers, status and error
  messages, and the default system prompt and welcome now follow the app language, so an
  English UI yields English replies read aloud. A system prompt or welcome you have
  customized is preserved across a language change; only an unedited default flips along
  with the language.

### Changed
- All user-facing text — screens, notifications, notification-channel names, toasts and
  the read-aloud Grok strings — now resolves through string resources / the active per-app
  locale instead of hard-coded German. Notification-channel names refresh on a language
  change from either the in-app switch or the OS picker, and SMS-bubble timestamps follow
  the chosen language.

## [0.3.0] — 2026-06-17

### Added
- **Standard SMS support.** Now that the app is the default SMS app, it doubles as a
  real SMS client:
  - **Inbox / history** — a new **“SMS”** bottom-nav tab shows real conversations read
    directly from `content://sms`, grouped by thread, with contact-name resolution. The
    `+888…` Tesla-bridge messages are filtered out so they never appear here.
  - **Send** — compose new messages and reply to real SMS straight from the app. Incoming
    messages are marked read when their thread is opened (scoped to real, non-bridge rows).

### Changed
- **`+888` (ITU TDR) is now the only fake-address scheme.** The multi-scheme machinery
  (per-scheme parse loop, the `toE164` scheme parameter, the runtime scheme selector) was
  collapsed accordingly.

### Removed
- The deprecated **`+4932` (DE)** and **`+99942` (ITU test)** legacy fake-address schemes,
  including their parse/cleanup paths — a real SMS to such a number can no longer be
  mistaken for a Tesla-bridge message.
- The vestigial Tesla **display-mode / number-scheme** configuration (its switcher was
  already gone): the unused accessors, the dead non-numeric “bracket-form” write path, and
  the related diagnostics UI.
- A batch of dead code surfaced by an audit: unused Room queries (`countForChannel`,
  `refreshExpiry`, `deleteByAddress`, `countSince`, …), the unused `MappingRepository.deleteAll`
  chain, the `ApiCompat` helper, `ToolRegistry.isEmpty`, and assorted stale annotations and
  KDoc links.

### Internal
- Outbound real SMS are guarded by a `SelfSendLedger` so the bridge’s outbox observer never
  treats a user-sent SMS as a Tesla-dictated reply.

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

[0.5.0]: https://github.com/LycheeAPPF/TMM-App/releases/tag/v0.5.0
[0.4.0]: https://github.com/LycheeAPPF/TMM-App/releases/tag/v0.4.0
[0.3.0]: https://github.com/LycheeAPPF/TMM-App/releases/tag/v0.3.0
[0.2.0]: https://github.com/LycheeAPPF/TMM-App/releases/tag/v0.2.0
