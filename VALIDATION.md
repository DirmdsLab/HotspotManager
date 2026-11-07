# Validation results: Hotspot Manager 0.6.8

Validation date: 2026-11-07 (Asia/Jakarta).

## Build environment

- JDK: Eclipse Temurin 17.
- Gradle: 8.13; Android Gradle Plugin: 8.13.2.
- Android platform: SDK 36; build tools: 36.0.0.
- Application ID: `uwu.waguri.hotspotmanager`.
- Version: `0.6.8` / version code `18`.

## Checks completed

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

- Release build: passed.
- USB retry unit tests: 10 passed, 0 failed, 0 errors, 0 skipped.
- Android lint: 0 errors, 22 warnings; no lint baseline or blanket suppression added.
- Release APK: non-debuggable; unsigned; 4-byte ZIP alignment check passed.
- Xposed entry point and both static package scopes: present and verified.
- Gradle 8.13 distribution: SHA-256 verified; checksum pinned in wrapper settings.

## Remaining lint warnings

- `DataExtractionRules`: 1.
- `DiscouragedPrivateApi`: 3.
- `IconLauncherShape`: 5.
- `IconLocation`: 1.
- `MonochromeLauncherIcon`: 1.
- `ObsoleteSdkInt`: 1.
- `PrivateApi`: 8.
- `SetTextI18n`: 1.
- `StaticFieldLeak`: 1.

Private-API access is intrinsic to the existing Vector hooks. The cached Context
is an application/system context, not an Activity context. Other warnings concern
existing backup metadata, launcher artwork, or English-only UI string construction.
These warnings are retained for visibility.

## Device testing still required

No physical Infinix, Magisk/Vector runtime, tethering interface, cellular modem,
or client DNS traffic was available in this environment. Automated tests cover
retry timing and lifecycle, not successful Internet access on the vendor firmware.

Use the USB-first and reconnect procedures in README.md after signing, installing,
enabling the module scopes, and rebooting. If the failure remains, capture the
first USB-only session logs before enabling the hotspot.

## Included APK

`artifacts/HotspotManager-0.6.8-unsigned.apk` must be signed before installation.
Use the existing release key for an in-place update. No private key is included.
See `artifacts/SHA256SUMS` for the pre-signing APK digest. Signing changes its bytes.
