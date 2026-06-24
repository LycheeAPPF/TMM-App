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

---

_0.5.0: der „xAI-Key-Test (nur am Handy)" ist umgesetzt (siehe CHANGELOG)._

_0.6.0: der „Internetzugriff für Grok" ist umgesetzt — natives server-seitiges `web_search` +
`x_search` auf `/v1/responses`, zwei opt-in Toggles, `store=false` bleibt (siehe CHANGELOG)._
