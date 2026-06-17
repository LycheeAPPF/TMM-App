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
