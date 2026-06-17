# Future Features

Ideen für später, noch nicht umgesetzt.

## 1. xAI-Key-Test (nur am Handy)

Ein Button in den Einstellungen, der prüft, ob der eingegebene xAI-Key gültig ist
und eine Antwort von der API zurückkommt — **ohne** Bluetooth-/Tesla-Verbindung,
also rein lokal auf dem Handy testbar.

- Sendet einen minimalen Test-Request an die Grok-API (z. B. „ping").
- Zeigt Ergebnis an: ✅ Key gültig / ❌ Auth-Fehler (401/403) / ❌ kein Netz / ⏱ Timeout.
- Vorlage: ähnlich wie der bestehende `PreFlightTester` (Carrier-Test), nur für die API.

## 2. i18n / Mehrsprachigkeit

App-Oberfläche in weitere Sprachen übersetzbar machen, **insbesondere Englisch**.

- Aktuell ist die UI fest auf Deutsch.
- Hartcodierte Strings in den Compose-Screens nach `strings.xml` auslagern.
- `values-en/strings.xml` (und ggf. weitere Sprachen) anlegen.
- Optional: Sprache per Android-13-Per-App-Language umschaltbar.
