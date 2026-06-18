# CLAUDE.md — Tesla Messages Manager (TMM)

Unofficial Android app (`io.github.lycheeappf.tmm`) that bridges phone messenger notifications to a Tesla over Bluetooth MAP/PBAP and adds an in-car **xAI Grok** voice assistant. Two core features: (1) a **message bridge** — captures messenger notifications and injects them as fake inbound SMS so the car's MAP UI reads them aloud and routes the driver's dictated reply back to the original app; (2) a **Grok assistant** — the driver dictates to a fake "Grok" contact, the reply is sent to the xAI Responses API and the answer is injected back as a fake inbound SMS. Single Gradle module (`:app`), Android 13+ (minSdk 33), Kotlin/Compose/Hilt. **UI is fully localized EN + DE** (English is the default resource locale; per-app language via the framework `LocaleManager`); code/comments are mixed German+English. Developed on **Windows**.

## Commands

Use the Gradle wrapper, never a system gradle. Windows = `gradlew.bat`; README uses `./gradlew`. Both wrap Gradle 8.10.2. JDK 17 + Android SDK API 36 required.

| Task | Command |
|---|---|
| Debug APK | `gradlew.bat :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| Unit tests (JVM) | `gradlew.bat :app:test` (aggregates the debug + release unit-test tasks) |
| Single test class | `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.LlmTurnRunnerTest"` |
| Package subset | `gradlew.bat :app:testDebugUnitTest --tests "io.github.lycheeappf.tmm.channel.llm.*"` |
| Lint | `gradlew.bat :app:lint` |
| Release / bundle | `gradlew.bat :app:assembleRelease` / `:app:bundleRelease` (needs `keystore.properties`) |
| Clean | `gradlew.bat clean` |

- **Release fail-fast:** if a requested task name contains `assembleRelease`/`bundleRelease` and `keystore.properties` is missing, the build throws a `GradleException` at config time (never produces an unsigned artifact). Copy from `keystore.properties.example`; it and `*.jks` are gitignored. Debug builds need no keystore.
- Debug builds get `applicationIdSuffix ".debug"` (+ `versionNameSuffix "-debug"`) so debug+release coexist on one device.
- Versions live in `gradle/libs.versions.toml` (change there, not inline). `versionCode`/`versionName` live in `app/build.gradle.kts`.

## Architecture

Pragmatic clean architecture. `domain/` holds pure interfaces + models (no Android deps); impls live in `data/`, `channel/`, `sms/`, `contact/`, `platform/` and are bound to interfaces in `core/di/`. **Depend on domain interfaces, not impls.**

| Package | Role |
|---|---|
| `channel/` | `MessagingChannel` impls: `notification/`, `system/`, `llm/` (Grok) |
| `core/` | DI modules (`di/`), security (Keystore), util (Clock, LogBuffer, SendBudget), i18n seam (`locale/`), notification channels (`notification/`), model, network |
| `data/` | Room DB + DAOs (`db/`), repositories (`repository/`, entity↔domain mapping), DataStore (`store/`) |
| `domain/` | Interfaces + models: `channel/`, `routing/`, `repository/`, `reply/` |
| `listener/` | NotificationListenerService + MessagingStyle/whitelist filters |
| `platform/` | RoleManager wrapper (`role/`), BootReceiver, PermissionGate (`permission/`) |
| `sms/` | default-SMS-app components (`default_app/`), SMS provider writer (`provider/`), outbound observer/classifier (`outbound/`) |
| `contact/` | fake-contact sync adapter (account, authenticator, writer, backfill, resync) |
| `ui/` | single-Activity Compose: navigation, theme tokens, components, 7 screens |
| `work/` | WorkManager `CleanupWorker` + `HealthCheckWorker` + `WorkScheduler` |

**Key abstractions:** `MessagingChannel`/`ChannelRegistry` (Hilt `@IntoSet` multibinding indexed by `ChannelId`); `ReplyDispatcher` (central router); `MappingRepository` (`ChannelMapping` lifecycle); `LlmProvider`/`LlmRequest`/`LlmResponse` (provider-agnostic LLM seam).

**Hilt wiring:** `@HiltAndroidApp` on `MfsApplication`; all modules `@InstallIn(SingletonComponent::class)`. `@Binds` for interface→impl (Repository/Channel/Llm/Security), `@Provides` for constructed deps (App/Database/Http). **Add a channel** = implement `MessagingChannel` + one `@Binds @IntoSet` in `core/di/ChannelModule.kt` (registry + dispatcher pick it up automatically). **Add an LLM tool** = `@IntoSet` into the `@Multibinds Set<AssistantTool>` in `core/di/LlmModule.kt`. Use dispatcher qualifiers `@IoDispatcher`/`@DefaultDispatcher`/`@MainDispatcher`, never `Dispatchers.*` directly. WorkManager is **not** auto-initialized: the manifest removes `androidx.work.WorkManagerInitializer`; `MfsApplication` is the `Configuration.Provider` supplying `HiltWorkerFactory`.

## Key subsystems / flows

**Inbound bridge** (`listener/NotificationForwardingService` → `channel/notification/NotificationCapture`): whitelist → MessagingStyle extract (stable `conversationKey`) → dedup → `roleManager.isDefault()` check → `SendBudget` reserve (rollback on any later failure) → `MappingRepository.allocateOrReuse` → SMS provider writer injects a fake INBOX SMS. Reply Action (RemoteInput + PendingIntent) cached in `ActionCache` by notification key.

**Reply routing** (`sms/outbound/OutboundSmsObserver` on `content://sms`): detects Tesla outbox row → `OutboundSmsClassifier` decodes `fakeAddress` → `(mappingId, channelCode)` → `ReplyDispatcher` loads mapping, checks TTL, resolves channel via `ChannelRegistry`, calls `handleTeslaReply` then `maybeInjectFollowUp` (skipped for `Ignored`). NOTIFICATION channel fires the cached RemoteInput PendingIntent back into the source app; `FallbackNotifier` posts a "tap to copy" notification if the PendingIntent is dead/immutable.

**Grok assistant** (`channel/llm/`): fake "Grok" contact (`+88810000000`, reserved mapping 0, never expires). Default system prompt + welcome are localized (EN + DE, chosen by app language at read time in `AssistantPreferencesStore` via `LocaleProvider`); a user-customized prompt/welcome survives a language switch. Driver dictates → `LlmChannel.handleTeslaReply` → `LlmTurnRunner.run` (single atomic turn under per-session Mutex: TTL check → rate-limit → `GrokProvider.complete` POST `/v1/responses` with `store=false` and full in-memory history → `LlmResponseFormatter` strips Markdown, caps 800 chars) → answer injected back as inbound SMS via `maybeInjectFollowUp`. Context is in-memory only (`LlmConversationStore`, ~120s inactivity TTL), wiped on restart. Gated on privacy consent + Keystore API key — **re-checked at turn time** because the Tesla auto-reply path bypasses `LlmStarter` and the static mapping never expires. Optional user-named **voice alias** (`+88810000001`, no DB row) — `OutboundSmsClassifier` must redirect it to canonical Grok id 0 *before* DB/parse lookups.

**Default-SMS role + fake contacts:** the app must hold `ROLE_SMS` because only the default SMS app may write `content://sms`. `sms/default_app/` re-implements the minimum AOSP contract (SMS_DELIVER + WAP_PUSH receivers, RESPOND_VIA_MESSAGE service, MMS provider stub, SEND activity) — mostly no-ops that delegate real sending to Google Messages. Sender names appear in the car via a sync-adapter pseudo-account (`contact/`): groupless RawContacts made visible to AOSP-MAP PhoneLookup via `UNGROUPED_VISIBLE=1` while an `AUTO_ADD` anchor group keeps them out of the user's Contacts app. PBAP is pull-only — the only way to force re-sync is removing the account (`TeslaContactResync.force`).

## Tech stack

| | |
|---|---|
| AGP | 8.7.2 | Kotlin | 2.0.21 | KSP | 2.0.21-1.0.27 (Room + Hilt; **not kapt**) |
| compileSdk/targetSdk | 36 | minSdk | 33 | JDK / jvmTarget | 17 |
| Hilt | 2.52 | Room | 2.6.1, v1 schema (`app/schemas/`) | WorkManager | 2.10.0, Hilt-integrated |
| Compose | BOM 2025.06.01, Material3 1.4.0 (pinned) | DataStore | Preferences | Retrofit/OkHttp | LLM path only |
| kotlinx.serialization | JSON (`ignoreUnknownKeys`, `explicitNulls=false`, `encodeDefaults=true`) | | | | |

Configuration cache, build cache, and parallel execution are all ON (`gradle.properties`). `compileSdk=36` is ahead of AGP 8.7.2's tested range — warning silenced via `android.suppressUnsupportedCompileSdk=36`.

## Conventions & gotchas

- **Testing:** JUnit4 + Google Truth + MockK + kotlinx-coroutines-test (`runTest`) + OkHttp MockWebServer. Robolectric **only** when a real Context/DataStore is needed (`@RunWith(RobolectricTestRunner)` + `@Config(sdk=[33])` + `ApplicationProvider`). Tests mirror the main package tree under `app/src/test/java`. Test names are backtick sentences. `robolectric.properties` boots a stub `android.app.Application`, **not** `MfsApplication`.
- **Time is injectable:** use the `Clock` fun-interface / `() -> Long` lambdas (rate limiter, TTLs, echo ledger) for deterministic tests. Abstract secrets behind interfaces (`ApiKeyStore`) so tests use in-memory fakes.
- **Security:** the xAI key is AES-256-GCM encrypted with an AndroidKeyStore master key (`KeystoreApiKeyStore`), Base64 ciphertext in DataStore `mfs_assistant_secure`. **Never shipped** — no bundled/default key; missing/undecryptable = `MissingKey`. `allowBackup=false` + data extraction rules exclude it. HTTPS-only (`network_security_config`), no cert pinning by design. `RedactingAuthInterceptor` logs only method+path+status — never the auth header/body.
- **Coroutines:** always rethrow `CancellationException` (use `coRunCatching`) — wrapping it makes "app stopped" get read aloud as a Grok error. Map provider failures to the sealed `LlmProviderError` hierarchy.
- **Never log message bodies / dictations / prompts** into `LogBuffer` — it is exported via `DiagnosticsExporter`. Log metadata/lengths only.
- **Room:** still v1, no migrations (`fallbackToDestructiveMigrationOnDowngrade` only — upgrades throw without a `Migration`). A schema change requires bumping the version, shipping a `Migration`, AND regenerating `app/schemas/.../<n>.json`. The `ChannelPayload` JSON column uses `classDiscriminator="kind"`; always read via `PayloadJson.decodeOrFallback`.
- **Compose:** single `MainActivity` → `MfsTheme { MfsApp() }`; all nav in `MfsNavHost`; never add Activities. New screen = `MfsDestination` entry + composable + (if a tab) `MfsBottomNavItem`. MVVM with immutable `UiState` `StateFlow`; collect with `collectAsStateWithLifecycle`; all blocking work in `withContext(ioDispatcher)`. Build from shared components (`MfsScaffold`, `SettingCard`, `StatusPill`) + tokens (`MfsSpacing`, `MfsShapes`, `MfsMotion`) — no raw hex. Dev surfaces (Channels, Diagnostics) gated behind `developerMode`.
- **i18n:** UI fully localized EN + DE. English is the default resource locale (`res/values/` = EN, `res/values-de/` = DE; `res/resources.properties` `unqualifiedResLocale=en`; `build.gradle.kts` `generateLocaleConfig=true` + `resourceConfigurations += setOf("en","de")`). Framework `LocaleManager` (API 33+) is the single source of truth — deliberately NOT mirrored in DataStore. `core/locale/`: `AppLocaleManager` (per-app language get/set), `Context.localizedString`/`localizedContext` (LocaleExt — resolves strings for non-Compose surfaces against the active locale, even before the process config applies it), `LocaleProvider` (fakeable seam, bound in `AppModule`). The Settings language toggle (System / Deutsch / English) calls `AppLocaleManager.setLanguageTag` (auto-recreates the Activity) and stays in sync with the OS per-app picker; both paths re-run `AppNotificationChannels.ensure` (idempotent) to re-localize cached channel names (`SettingsViewModel.setLanguage` directly; OS picker via `MfsApplication.onConfigurationChanged`). `MfsApplication` seeds `de` once on first run so existing users keep German despite the English default. Grok default prompt/welcome are localized too (see Key subsystems).
- `kotlin.code.style=official`.
