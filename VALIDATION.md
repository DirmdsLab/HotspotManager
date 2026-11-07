# Validation results: Hotspot Manager 0.6.10

Validation date: 2026-11-07 (Asia/Jakarta).

## Build environment

- JDK: Eclipse Temurin 17; Gradle: 8.13.
- Android Gradle Plugin: 8.13.2; SDK platform: 36; build tools: 36.0.0.
- Application ID: `uwu.waguri.hotspotmanager`.
- Version: `0.6.10`; version code: `20`.

## Completed checks

```bash
./gradlew :app:clean :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

- Release build: passed.
- Unit tests: 24 passed, 0 failed, 0 errors, 0 skipped.
- Android lint: 0 errors, 26 warnings; no baseline or blanket suppression added.
- APK: non-debuggable; unsigned; 4-byte ZIP alignment check passed.
- Compiled APK manifest: explicit exported TetheringReceiver requires DUMP permission.
- APK DEX: CLI, receiver, and command-control classes are present.
- Xposed entry point and both static scopes remain present.
- HotspotProbe.java and UsbUpstreamRecovery.java are byte-for-byte unchanged from 0.6.8.

## Automated coverage

- `TetheringControlTest`: 14 tests.
- `UsbUpstreamRecoveryTest`: 10 tests.

The control tests cover reading state without mutation, idempotent commands,
USB/hotspot independence, waiting for real interface activation/removal,
missing-USB timeout, native permission failure, strict input rejection,
interrupted confirmation, and immutable snapshots. The original ten USB-first
retry lifecycle tests are retained.

## Lint warnings retained

- `DataExtractionRules`: 1.
- `DiscouragedPrivateApi`: 5.
- `IconLauncherShape`: 5.
- `IconLocation`: 1.
- `MonochromeLauncherIcon`: 1.
- `ObsoleteSdkInt`: 1.
- `PrivateApi`: 9.
- `SetTextI18n`: 2.
- `StaticFieldLeak`: 1.

Warnings include intentional framework reflection for the root/Vector helpers,
existing launcher/backup metadata, application/system-context caching, and
English-only UI string construction. The API 36 service-name constant warning
has been removed by using class-based service lookup.

## Deprecation verification

- Both release and debug Java were recompiled after `:app:clean`.
- `-Xlint:deprecation` was enabled for all JavaCompile tasks.
- No deprecated-API compiler notes or deprecation warnings were emitted.
- Release bytecode invokes `Looper.prepare()` and contains no call to
  `prepareMainLooper()`.
- No suppression or warning-disable flag was added for this change.
- The GUI, receiver, command engine, and USB-first hooks retain their behavior;
  only root-context initialization and service lookup changed.

AOSP Android 16 source shows ActivityThread capturing `Looper.myLooper()` and
ContextImpl returning that looper from `getMainLooper()`. This helper passes
explicit executors for Binder callbacks, so a process-global main looper is not
needed for its tethering calls. Physical-firmware validation remains required.

References:

- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/ActivityThread.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/app/ContextImpl.java

The lint HTML-report location is informational output. Gradle's Problems report
is also a diagnostic artifact; its existence is not a build failure. The final
clean build did not emit the previous Java deprecation diagnostics or a new
Problems-report announcement.

## Required physical-device validation

No physical Android device, Magisk daemon, Vector runtime, or Android UI runtime
was available here. The GUI has not been rendered or tapped on the Infinix.
Native app_process/Binder execution, ADB broadcast authorization/delivery,
USB transport transitions, saved hotspot configuration, and real DNS traffic
still need verification on that firmware. Automated tests cover the command
engine and retry lifecycle, not these integrations.

Test after signing, installing, granting app root access, and rebooting:

1. Confirm USB and Hotspot appear as two columns above Network Status.
2. Toggle hotspot on/off and verify the existing SSID/password are used.
3. Enable USB first with hotspot off; run the DNS checks in README.md.
4. Run the ADB on/off/status commands in TERMINAL.md with the GUI closed.
5. Reopen the GUI and verify actual state; also change tethering in Settings.
6. Enable both, disable one, and confirm the other remains active.
7. Test a missing USB cable and denied Magisk permission; verify there is no false On state.
8. Reconnect USB and verify the retained USB-first recovery behavior.

## Prebuilt artifact

`artifacts/HotspotManager-0.6.10-unsigned.apk` requires signing before installation.
Use the same key as the installed app for an in-place update. No private key is
included. The pre-signing digest is in `artifacts/SHA256SUMS`; signing changes it.
