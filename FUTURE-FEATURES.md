# Future Features

Ideen für später, noch nicht umgesetzt.

> Detaillierte Umsetzungsrecherche (APIs, Auth, Codebase-Integration, Risiken):
> [`docs/FUTURE-FEATURES-RESEARCH.md`](docs/FUTURE-FEATURES-RESEARCH.md).

## Offene Ideen

- **Spotify steuern** — per Sprache an „Grok" Wiedergabe steuern (Play/Pause, nächster/vorheriger
  Titel, Song/Playlist starten) als neues `control_spotify`-`AssistantTool` (`@IntoSet` in
  `LlmModule`) über die Spotify Web API. Voraussetzung: der noch fehlende Tool-Execution-Loop in
  `LlmTurnRunner`. Gates: Premium + aktives Connect-Device (Tesla-Headunit).
- **Zielsetzung im Navi** — Fahrziel per Diktat ans Tesla-Navi (`navigate_to`-`AssistantTool`) über
  Tesla Fleet API `navigation_request` (ungesigntes REST, opt-in BYO-Credentials); Default-Fallback
  = Maps-Link als SMS. Braucht ebenfalls den Tool-Loop. (Reiner Android-Share-Intent an die
  Tesla-App ist nur ein Share-Sheet-Target, kein stiller Weg.)
- **Tageslimit abschaltbar machen** — Checkbox in den Einstellungen, ob die tägliche
  Weiterleitungsquote (`SendBudget`) überhaupt aktiv ist (Default: **an**). Beim Ausschalten eine
  Warnung mit den Risiken anzeigen: ohne Limit kann ein Messenger-Runaway/Spam beliebig viele
  Fake-SMS in die SMS-Datenbank schreiben, und im unwahrscheinlichen Fall eines Carriers, der die
  `+888`-Antwort-SMS doch zustellt (statt sie zu verwerfen), entstehen die Reply-Kosten ohne
  Obergrenze. Umsetzung: `booleanPreferencesKey` in `SettingsStore`, `SendBudget.checkAndIncrement`
  bei deaktiviertem Limit früh `true` liefern, Toggle-`SettingCard` + Warn-Dialog in
  `SettingsScreen` (EN + DE).
- **Nur weiterleiten, wenn mit dem Auto verbunden** — Nachrichten erst dann als Fake-SMS injizieren,
  wenn das Handy per Bluetooth (MAP/PBAP) mit dem Tesla verbunden ist. Aktuell wird rund um die Uhr
  injiziert (die Fake-SMS landet nur in der DB und wird beim nächsten MAP-Sync gezogen), was u.a.
  das Tageslimit unnötig schnell füllt, obwohl man gar nicht im Auto sitzt. Umsetzung: BT-Verbindungs-
  Check (verbundenes MAP/PBAP-Device via `BluetoothAdapter`/`BluetoothProfile`) als zusätzliches Gate
  in `NotificationCapture.captureInternal` vor `smsWriter.injectIncoming`; sinnvoll als eigener
  Toggle, plus Entscheidung, ob bei fehlender Verbindung verworfen oder bis zum Connect gepuffert wird.

---

_0.5.0: der „xAI-Key-Test (nur am Handy)" ist umgesetzt (siehe CHANGELOG)._

_0.6.0: der „Internetzugriff für Grok" ist umgesetzt — natives server-seitiges `web_search` +
`x_search` auf `/v1/responses`, zwei opt-in Toggles, `store=false` bleibt (siehe CHANGELOG)._
