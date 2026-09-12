# Multi-Tesla: mehrere Autos im Bluetooth-Gate + Fleet-API-Navigation ans verbundene Auto

## Context

TMM geht heute an zwei Stellen von **genau einem** Tesla aus:

1. **Bluetooth-Gate** — `SettingsStore` speichert eine MAC (`tesla_bt_address`/`tesla_bt_name`), `BluetoothConnectionChecker.isTeslaConnected()` matcht nur diese, `TeslaConnectionCard` + `TeslaDevicePickerDialog` (Radio-Auswahl) sind auf ein Gerät gebaut. Konsumenten: `NotificationCapture` (Messenger-Bridge) und `LlmChannel` (Grok-Turn).
2. **Fleet API** — `TeslaTokenStore.selectedVin`/`selectedVehicleId` ist ein manuell gewähltes Fahrzeug; `TeslaNavigateTool` schickt Grok-Navigationsziele immer dorthin. Latenter Bug: `TeslaVehicleCommandClient.wakeUpByVin()` nutzt die *globale* `selectedVehicleId`, egal welche VIN übergeben wurde.

Ziel (mit dem User abgestimmt): **2+ Teslas** auswählen; Nachrichten/Grok werden weitergeleitet, sobald *eines* der Autos verbunden ist; **Fleet-Fahrzeuge lassen sich einem Bluetooth-Tesla zuordnen**, Grok navigiert das Auto, mit dem das Handy gerade verbunden ist — das bisherige gewählte Fahrzeug bleibt als **Standard-Fahrzeug** der Fallback (kein Auto verbunden / Desk-Test / Selbsttest).

Fail-Open-Vertrag bleibt unverändert: keine Geräte gewählt / keine Permission / kein Adapter → weiterleiten wie bisher.

## Design

### 1. Datenmodell + Persistenz

**Neu `domain/tesla/TeslaDevice.kt`** (domain hat mit `ChannelPayload` bereits kotlinx.serialization-Präzedenz):
```kotlin
@Serializable
data class TeslaDevice(
    val address: String,          // BT-MAC, beim Schreiben UPPERCASE normalisiert; Vergleich ignoreCase
    val name: String,             // BT-Anzeigename (nur UI)
    val vin: String? = null,      // verknüpftes Fleet-API-Fahrzeug (optional)
    val vehicleId: Long? = null   // numerische Fleet-ID (für wake_up)
)
```
**Nie loggen** (`toString()` enthält MAC + VIN) — nur Counts (LogBuffer-Regel in CLAUDE.md).

**`data/store/SettingsStore.kt`** — Abschnitt „Tesla-Bluetooth-Verbindung" ersetzen:
- `private val Context.dataStore` → `internal` (Präzedenz `KeystoreTeslaTokenStore.kt:212`), damit der Migrationstest Legacy-Keys seeden kann (zweiter `preferencesDataStore("mfs_settings")` im Test würde crashen).
- Neuer Key `KEY_TESLA_DEVICES = "tesla_bt_devices"` = JSON-`List<TeslaDevice>` (private `Json { ignoreUnknownKeys = true; encodeDefaults = false }`). Decode-Fehler → `runCatching{}.getOrElse { emptyList() }` + `Log.w` ohne Payload. Leere Liste → Key `remove()` statt `"[]"`.
- `suspend fun teslaDevices(): List<TeslaDevice>` — **Read-through-Migration**: Key vorhanden → decode; sonst Legacy `tesla_bt_address` gesetzt → `listOf(TeslaDevice(addr.uppercase(), name ?: addr))`; sonst leer. Kein Write beim Lesen.
- Private `updateTeslaDevices(transform: (List<TeslaDevice>) -> List<TeslaDevice>)`: **ein** `store.edit`, liest innerhalb des Edits aus `prefs` (inkl. Legacy-Fallback), encodiert im Transform, schreibt JSON, **entfernt die Legacy-Keys**. Kein `store.data`/zweites `edit` im Transform (Deadlock).
- Public Writer (alle über `updateTeslaDevices`, damit Merge + Link nie racen):
  - `setTeslaDevices(selected: List<TeslaDevice>)` — ersetzt die Liste; für Adressen, die bleiben, werden `vin/vehicleId` aus dem Bestand übernommen (Name aus `selected`); Adressen uppercase, Duplikate (ignoreCase) entfernt.
  - `removeTeslaDevice(address)` (ignoreCase).
  - `linkVehicleToDevice(vin: String, vehicleId: Long, address: String?)` — entfernt die VIN von allen Geräten, setzt sie am Ziel; `address == null` = nur entkoppeln.
  - `clearTeslaVehicleLinks()`.
- `teslaBtAddress()/teslaBtName()/setTeslaBtDevice()/clearTeslaBtDevice()` **entfernen**; alte Key-Konstanten bleiben für die Migration.

### 2. Bluetooth-Gate — `platform/bluetooth/BluetoothConnectionChecker.kt`

- `isTeslaConnected()`: Liste statt Einzel-MAC; Leiter exakt erhalten: leer → `true`; keine Permission → `true`; kein Adapter → `true`; Adapter aus → `false`; ACL-Set enthält *eine* der MACs → `true`; keine Proxies → `true`; Proxy-Match auf *eine* MAC; `SecurityException` → `true`.
- Private `proxyConnectedAddresses(): Set<String>?` (uppercase; `null` = Proxies nicht bereit / denied) und `bondedAddressesOrNull(): Set<String>?` (`null` = kein Adapter / denied) — von den Public-Methoden geteilt.
- Neu `suspend fun connectedTeslaDevices(): List<TeslaDevice>` — konfigurierte Geräte, die gerade verbunden sind (ACL ∪ Proxy); **leer wenn nicht bestimmbar** (kein Fail-Open — der Resolver fällt dann aufs Standard-Fahrzeug zurück).
- Neu `suspend fun teslaDeviceStatuses(): List<TeslaDeviceStatus>` mit `data class TeslaDeviceStatus(val device: TeslaDevice, val missing: Boolean, val connected: Boolean)` — ersetzt die in beiden ViewModels duplizierte Stale-Prüfung. `missing` **nur** wenn Permission + Adapter vorhanden **und** `bondedDevices` erfolgreich (heute gibt es False-Positives, wenn `pairedDevices()` wegen Adapter/SecurityException leer ist). `connected` = Snapshot zum Refresh-Zeitpunkt.
- `pairedDevices()` unverändert. Klassen-Doc auf Plural.

### 3. Aktives Fahrzeug — Domain-Interface + Platform-Impl

**Neu `domain/tesla/VehicleRef.kt`**: `data class VehicleRef(val vin: String, val vehicleId: Long?)`.

**Neu `domain/tesla/ActiveVehicleResolver.kt`** (Interface, damit Tool-/Selbsttest-Tests einen In-Memory-Fake nutzen):
```kotlin
interface ActiveVehicleResolver {
    /** Verbundenes verknüpftes Auto zuerst; sonst Standard-Fahrzeug; null = nichts konfiguriert. */
    suspend fun resolve(): VehicleRef?
    /** Standard-Fahrzeug gesetzt ODER mindestens ein Gerät verknüpft (Selbsttest-Stufe TESLA_LOCAL). */
    suspend fun isAnyVehicleConfigured(): Boolean
}
```
**Neu `platform/tesla/DefaultActiveVehicleResolver.kt`** (`BluetoothConnectionChecker`, `TeslaTokenStore`, `SettingsStore`, `LogBuffer`), `@Binds` in `TeslaStoreModule` (`core/di/TeslaModule.kt`).
`resolve()`: `connectedTeslaDevices().firstOrNull { it.vin != null }` → `VehicleRef(vin, vehicleId)`; sonst `readSelectedVin()` → `VehicleRef(vin, readSelectedVehicleId())`; sonst `null`. Wenn Geräte verknüpft sind, aber keins bestimmbar verbunden ist und aufs Standard-Fahrzeug zurückgefallen wird → `logBuffer.warn("vehicle resolved via default (no linked Tesla connected)")` ohne VIN/MAC, damit „falsches Auto" diagnostizierbar ist. Verbundenes, *unverknüpftes* Auto → Standard (= heutiges Verhalten; im KDoc festhalten).

### 4. Fleet-API-Konsumenten

- **`platform/tesla/api/TeslaVehicleCommandClient.kt`**: `navigate(vehicle: VehicleRef, address)` / `navigateGps(vehicle: VehicleRef, lat, lon)`; `wakeUp(vehicle)` nutzt `vehicle.vehicleId ?: listVehicles().firstOrNull { it.vin == vehicle.vin }?.id` (behebt den Global-ID-Bug). **`TeslaTokenStore` aus dem Konstruktor entfernen** (einzige Nutzung war `readSelectedVehicleId()`).
- **`channel/llm/tools/tesla/TeslaNavigateTool.kt`**: `TeslaTokenStore` → `ActiveVehicleResolver`; `resolve() ?: Failure(tesla_error_no_vehicle_configured)`; Tool-Description: „…navigates the Tesla the phone is currently connected to via Bluetooth; falls back to the default vehicle selected in settings".
- **`channel/llm/GrokSelfTester.kt`**: `TeslaTokenStore` → `ActiveVehicleResolver`; `vinSelected = resolver.isAnyVehicleConfigured()` (nicht `resolve() != null` — sonst „kein Fahrzeug" für einen komplett konfigurierten 2-Auto-User zu Hause ohne Standard). Event-Shape unverändert.
- **`platform/tesla/auth/TeslaAuthManager.kt`**: `logout()` und `clearCredentials()` rufen zusätzlich `settingsStore.clearTeslaVehicleLinks()` (neue Konstruktor-Abhängigkeit `SettingsStore`, kein Hilt-Modul nötig) — konsistent damit, dass `tokenStore.clear()` VIN + ID verwirft. `selectVehicle()`/`Authenticated.selectedVin` bleiben (= Standard-Fahrzeug).
- **`core/util/DiagnosticsExporter.kt`** `TeslaApiSnapshot`: `+ configuredTeslaDevices: Int`, `+ linkedVehicles: Int` (keine MACs/Namen — Gerätename = Autoname = PII). `SettingsStore` ist dort bereits injiziert.

### 5. UI — Tesla-Verbindung (Settings + Onboarding, geteilte Komponente)

**`ui/component/TeslaConnection.kt`**
- `TeslaConnectionCard(devices: List<TeslaDeviceStatus>, hasPermission, permanentlyDenied, onGrantPermission, onOpenAppSettings, onSelectDevices, onRemoveDevice: (address) -> Unit)`:
  - leer → `settings_tesla_conn_none` (wie heute).
  - pro Gerät eine `MfsListItem`-Zeile: Titel = Name; Subtitle = `settings_tesla_conn_linked_vehicle` („Vehicle: …1234", VIN maskiert) wenn verknüpft; bei `missing` stattdessen `settings_tesla_conn_missing` (Text in `error`-Farbe → Subtitle-Slot ist nicht färbbar, daher eigener `Text` unter der Zeile oder `MfsListItem` um `subtitleColor` erweitern — Letzteres bevorzugt, ein Parameter mit Default); Trailing = `Row { StatusPill(tesla_api_status_connected, Success) wenn connected; IconButton(Icons.Outlined.Close, contentDescription = settings_tesla_conn_clear) → onRemoveDevice }`. **Entfernen bleibt immer möglich** (auch ohne Permission — bestehender Vertrag).
  - Wenn *alle* Geräte `missing` → zusätzlicher Warntext `settings_tesla_conn_all_missing`.
  - Permission-Block unverändert; darunter `TextButton` `settings_tesla_conn_select` („Select Teslas") bzw. `settings_tesla_conn_change` („Change selection").
- `TeslaDevicePickerDialog(devices: List<PairedBtDevice>, selectedAddresses: Set<String>, loading, onConfirm: (List<PairedBtDevice>) -> Unit, onCancel)`: **Multi-Select** — Zeilen mit `Modifier.toggleable(role = Role.Checkbox)` + `Checkbox(onCheckedChange = null)`, **kein** `selectableGroup()`; lokaler `rememberSaveable`-State (uppercase-Adressen) aus `selectedAddresses` geseedet; `confirmButton` = `settings_tesla_conn_dialog_confirm` („Apply"); Scroll wie heute.
- `maskVin()` aus `SettingsScreen.kt` hierher (public), beide Karten brauchen sie.
- Neu `TeslaVehicleLinkDialog(vehicle: VehicleInfo, devices: List<TeslaDevice>, onLink: (address: String?) -> Unit, onCancel)`: Radio-Liste der konfigurierten Geräte + `tesla_api_link_none` („Not linked"); Vorauswahl = aktueller Link, sonst Namens-Match `device.name.equals(vehicle.displayName, ignoreCase = true)` (Tesla-BT-Name = Fahrzeugname). Tippen bestätigt sofort.

**`ui/screen/settings/SettingsViewModel.kt`** / **`ui/screen/onboarding/OnboardingViewModel.kt`** (identische Änderung; Onboarding-`Snapshot` entsprechend):
- UiState: `teslaBtDeviceName/teslaBtAddress/teslaDeviceMissing` → `teslaDevices: List<TeslaDeviceStatus>`; `hasBluetoothPermission`, `pairedDevices`, `pairedDevicesLoading` bleiben.
- Refresh: `teslaDevices = bluetoothConnectionChecker.teslaDeviceStatuses()`.
- `selectTeslaDevice(address, name)` → `setTeslaDevices(selected: List<PairedBtDevice>)` → `store.setTeslaDevices(selected.map { TeslaDevice(it.address, it.name) })` (Merge der Links passiert im Store) + refresh.
- `clearTeslaDevice()` → `removeTeslaDevice(address)`.
- Settings zusätzlich: `linkTeslaVehicle(vehicle: VehicleInfo, address: String?)` → `store.linkVehicleToDevice(vehicle.vin, vehicle.id, address)` + `refreshSettings()`.

**`SettingsScreen.kt` / `OnboardingScreen.kt`**: Dialog-Wiring `selectedAddresses = state.teslaDevices.map { it.device.address }.toSet()`, `onConfirm = { showDevicePicker = false; viewModel.setTeslaDevices(it) }`; Card-Wiring auf die neuen Parameter.

### 6. UI — Fleet-API-Karte (`SettingsScreen.kt` `TeslaFleetApiCard`)

- Fahrzeug-Liste in `Column(Modifier.selectableGroup())`; Zeile = `MfsListItem(modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = select), onClick = null, leading = { RadioButton(selected, onClick = null) }, trailing = { IconButton(Icons.Outlined.Link, tint = primary wenn verknüpft, contentDescription = tesla_api_link_device) })` — Muster `LanguageOption` in derselben Datei; a11y bekommt endlich „selected".
- Subtitle = maskierte VIN + ` · ` + `tesla_api_linked_to` („Bluetooth: <Gerätename>") wenn verknüpft.
- Link-Button + Hinweis `tesla_api_link_hint` nur wenn `state.teslaDevices.isNotEmpty()`.
- Statuszeile: `tesla_api_vehicle_selected` wird zu „Default vehicle: %1$s"/„Standard-Fahrzeug: %1$s".

### 7. Strings (EN `res/values/`, DE `res/values-de/`; Parität manuell prüfen — Lint crasht, siehe Memory)

`strings_settings.xml`:
- ändern: `settings_tesla_conn_desc` („Messages are only forwarded while one of your Teslas is connected via Bluetooth."), `settings_tesla_conn_select` („Select Teslas"/„Teslas auswählen"), `settings_tesla_conn_change` („Change selection"/„Auswahl ändern"), `settings_tesla_conn_missing` (Zeilen-Text „No longer paired — pair again or remove"/„Nicht mehr gekoppelt — neu koppeln oder entfernen"), `settings_tesla_conn_dialog_title` („Select your Teslas"/„Wähle deine Teslas").
- neu: `settings_tesla_conn_all_missing`, `settings_tesla_conn_dialog_confirm` („Apply"/„Übernehmen"), `settings_tesla_conn_linked_vehicle` („Vehicle: %1$s"/„Fahrzeug: %1$s").
- entfernen: `settings_tesla_conn_selected`.
- wiederverwenden: `tesla_api_status_connected` (Pill), `settings_tesla_conn_clear` (contentDescription).

`strings.xml`: ändern `tesla_api_vehicle_selected` (→ „Default vehicle: %1$s"); neu `tesla_api_link_device`, `tesla_api_link_dialog_title` („Which Bluetooth device is this vehicle?"), `tesla_api_link_none`, `tesla_api_linked_to` („Bluetooth: %1$s"), `tesla_api_link_hint` („Link each vehicle to its Bluetooth device — Grok then navigates the car you're in. The default vehicle is the fallback.").

`strings_assistant.xml`: `assistant_selftest_tesla_no_vehicle` → „No vehicle selected or linked"/„Kein Fahrzeug gewählt oder verknüpft".

### 8. Doku

- `CHANGELOG.md`: neuen `## [Unreleased]`-Abschnitt über `## [1.0.1]` (Bold-Lead-in-Stil): **Added** Multi-Tesla (Mehrfachauswahl, Fahrzeug↔Gerät-Verknüpfung, Navigation ans verbundene Auto, Standard-Fahrzeug als Fallback); **Changed** Selbsttest-Text; **Fixed** wake_up nutzte die global gewählte Fahrzeug-ID statt der Ziel-VIN. Hinweis: Downgrade auf eine ältere Version verliert die Geräteauswahl (Fail-Open, 24/7-Weiterleitung).
- `CLAUDE.md`: Paket-Tabelle `domain/` + `tesla/`; `platform/` + `bluetooth/` (Gate), `location/`, `tesla/` (Fleet API, Auth, Resolver); im Grok-Absatz ein Satz zur Fahrzeug-Auflösung.
- `channel/llm/README.md`: Zeile `tesla_navigate` → Resolver (Tools-Abschnitt ist veraltet, „V2 leer").
- Kopie dieses Plans nach `docs/superpowers/plans/2026-09-09-multi-tesla.md` (Repo-Konvention).

## Umsetzungsreihenfolge (TDD, jeweils Test zuerst)

1. `TeslaDevice`, `VehicleRef` + `SettingsStore` (Liste, Migration, Link-Ops, `internal` dataStore) — `SettingsStoreTest`.
2. `BluetoothConnectionChecker` (Liste, `connectedTeslaDevices`, `teslaDeviceStatuses`) — `BluetoothConnectionCheckerTest`.
3. `ActiveVehicleResolver` (Interface) + `DefaultActiveVehicleResolver` + `@Binds` — neuer `DefaultActiveVehicleResolverTest`.
4. `TeslaVehicleCommandClient` (`VehicleRef`, wake_up-ID, ohne TokenStore) — `TeslaVehicleCommandClientTest`.
5. `TeslaNavigateTool`, `GrokSelfTester`, `TeslaAuthManager`, `DiagnosticsExporter` — jeweilige Tests.
6. ViewModels (Settings, Onboarding) — `SettingsViewModelTest`.
7. Compose: `TeslaConnection.kt` (Card, Multi-Picker, Link-Dialog, `maskVin`), `MfsListItem` (`subtitleColor`), `SettingsScreen.kt`, `OnboardingScreen.kt`; Strings EN+DE; Compose-Test `app/src/testDebug/java/.../ui/component/TeslaConnectionTest.kt`.
8. Doku.

## Tests (Detail)

- **SettingsStoreTest** (Robolectric, order-unabhängig — jeder Fall beginnt mit `setTeslaDevices(emptyList())`): Legacy-Keys geseedet → `teslaDevices()` = 1 Element, Legacy bleibt (kein Write); erster Write entfernt Legacy-Keys; `setTeslaDevices` behält VIN-Link behaltener Adressen und verwirft abgewählte; Adressen uppercase/dedupliziert; `removeTeslaDevice` ignoreCase; `linkVehicleToDevice` (VIN wandert vom alten zum neuen Gerät; `null` entkoppelt); `clearTeslaVehicleLinks`; kaputtes JSON → leer.
- **BluetoothConnectionCheckerTest**: Stubs auf `teslaDevices()`; leere Liste fail-open; 2 Geräte + Permission fehlt → `true`; `connectedTeslaDevices()` ohne Permission/Adapter → leer; `teslaDeviceStatuses()` ohne Adapter → `missing=false` (kein False-Positive).
- **DefaultActiveVehicleResolverTest**: verbundenes verknüpftes → dessen `VehicleRef`; verbundenes unverknüpftes → Standard; nichts verbunden → Standard + Warn-Log (ohne VIN/MAC) wenn Links existieren; nichts → `null`; `isAnyVehicleConfigured()` für Standard-only / Link-only / nichts.
- **TeslaVehicleCommandClientTest**: `navigate(VehicleRef(VIN, id))` → wake_up nutzt `id`; `vehicleId == null` → Lookup über `/vehicles`; Konstruktor ohne TokenStore; bestehende Calls auf `VehicleRef`.
- **TeslaNavigateToolTest / GrokSelfTesterTest**: Resolver-Fake statt `tokenStore`; „kein Fahrzeug" = `resolve()` → `null`; Selbsttest `isAnyVehicleConfigured()`.
- **TeslaAuthManagerTest**: `buildManager()` + `settingsStore = mockk(relaxed = true)`; `logout()`/`clearCredentials()` → `coVerify { clearTeslaVehicleLinks() }`.
- **SettingsViewModelTest**: `setTeslaDevices` → `store.setTeslaDevices(...)` mit den Picker-Geräten; `removeTeslaDevice`; `linkTeslaVehicle` → `store.linkVehicleToDevice(vin, id, address)`.
- **DiagnosticsExporterTest**: Counts im Snapshot (`teslaDevices()` explizit stubben), keine MAC/Namen im Export.
- **TeslaConnectionTest** (testDebug, Robolectric + `createComposeRule`, `MfsTheme`): Apply liefert genau die angehakten Geräte; Entfernen-Button auch ohne Permission; „No longer paired" bei `missing`.

## Verification

1. `gradlew.bat :app:testDebugUnitTest` und `gradlew.bat :app:test` grün.
2. `gradlew.bat :app:assembleDebug` → APK aufs Gerät (`.debug`-Suffix koexistiert mit Release).
3. Manuell:
   - Bestandsinstallation mit gewähltem Gerät → nach Update eine Zeile (Migration); Auswahl ändern → Legacy-Keys weg (Diagnose-Export zeigt `configuredTeslaDevices`).
   - Zwei gekoppelte Teslas anhaken → zwei Zeilen; in Auto A: Pill „Verbunden" bei A, Nachricht wird injiziert; in Auto B analog; keines verbunden → Drop-Log „Tesla not connected".
   - Fleet-Karte: Fahrzeuge laden, X ↔ A und Y ↔ B verknüpfen; in A „Navigiere zu …" diktieren → Ziel landet in A (Log `navigation_request OK`); Standard-Fahrzeug am Schreibtisch per Selbsttest → weiterhin grün.
   - Sprache DE/EN → alle neuen Texte übersetzt.
   - Tesla „Verbindung trennen" → Links in der Bluetooth-Karte verschwinden.
