# Releasing TMM

Maintainer guide for producing a **signed release APK**. End users don't need this — they
install the prebuilt APK from the
[Releases](https://github.com/LycheeAPPF/TMM-App/releases) page. Contributors building
locally only need the debug build (`./gradlew :app:assembleDebug`, see the
[README](../README.md)).

## 1. Create a release keystore (once)

Keep it backed up — losing it means you can no longer ship updates under the same
signature:

```bash
keytool -genkeypair -v -keystore tmm-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias tmm
```

## 2. Configure signing

Copy the template and fill in your values:

```bash
cp keystore.properties.example keystore.properties
```

`keystore.properties` and the `*.jks` keystore are **gitignored and must never be
committed**.

## 3. Build the signed APK

```bash
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The release build **fails fast** if `keystore.properties` is missing — it never produces an
unsigned APK.

## 4. Cut a release (every shipped version)

Never publish silently: every build that reaches `main` must carry a distinct `versionCode`, a
`CHANGELOG.md` entry, and a git tag, so installs upgrade cleanly and field diagnostics can
identify the build.

1. **Bump the version** in `app/build.gradle.kts` — increment `versionCode` (Android keys updates
   off it) and `versionName` (semver, e.g. `0.2.0`).
2. **Update [`CHANGELOG.md`](../CHANGELOG.md)** with an entry for the new version.
3. **Verify** `./gradlew :app:testDebugUnitTest` is green and build the signed APK (step 3 above).
4. **Tag** the release commit on `main` and push it:

   ```bash
   git tag v0.2.0
   git push origin main v0.2.0
   ```

5. **Publish** a GitHub release for the tag and attach the signed APK:

   ```bash
   gh release create v0.2.0 app/build/outputs/apk/release/app-release.apk \
     --title "v0.2.0" --notes "See CHANGELOG.md for this release's changes."
   ```
