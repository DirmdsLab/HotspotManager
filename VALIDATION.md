# Validation results: Hotspot Manager 0.7.0

Validation date: 2026-11-07 (Asia/Jakarta).

## Build and APK

- JDK 17; Gradle 8.13; Android Gradle Plugin 8.13.2.
- SDK platform 36; build tools 36.0.0.
- Package: `uwu.waguri.hotspotmanager`; version code 21; version name 0.7.0.
- Release build passed; non-debuggable, unsigned APK; ZIP alignment check passed.
- Xposed entry point and both static scopes are present.
- Device inspection, profiles, and native block-control classes are present in DEX.
- Java compilation retains `-Xlint:deprecation`; no deprecated-API compiler note emitted.
- Unit tests: 36 passed, 0 failed, 0 errors, 0 skipped.
- Android lint: 0 errors, 29 warnings; no baseline or blanket suppression added.

Commands used:

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

## Retained implementation

The following files are byte-for-byte unchanged from 0.6.10:

- `HotspotProbe.java`
- `UsbUpstreamRecovery.java`
- `TetheringControl.java`
- `TetheringController.java`
- `TetheringReceiver.java`
- `RootShell.java`

TetheringCli.systemContext has package visibility so the new root helper can reuse
its initialization; its existing implementation is retained. MainActivity keeps
the previous device-summary data, IP actions, reservation JSON key/schema, and
GUI tethering controls. It adds details/profiles and an optional post-save callback
for a selected-device reset.

## Automated suites

- `DeviceProfileTest`: 12 tests.
- `TetheringControlTest`: 14 tests.
- `UsbUpstreamRecoveryTest`: 10 tests.

New tests cover normalized unicast MAC validation, distinct MAC identities despite
IP reuse, matching identities across IP/interface changes, unknown manual types,
selected-only blocklist edits, case-insensitive duplicate handling, preserving
unrelated entries, and refusing a purported temporary native block.

Existing tests cover USB-first recovery and native-control command lifecycle.
No test substitutes a mock result for claimed vendor-HAL, Android UI, persistent
storage/reboot, or actual traffic verification.

## Warnings retained

- `DataExtractionRules`: 1.
- `DiscouragedPrivateApi`: 5.
- `GradleDependency`: 1.
- `IconLauncherShape`: 5.
- `IconLocation`: 1.
- `MonochromeLauncherIcon`: 1.
- `ObsoleteSdkInt`: 1.
- `PrivateApi`: 11.
- `SetTextI18n`: 2.
- `StaticFieldLeak`: 1.

The warnings concern intentional framework reflection for root/Vector features,
existing artwork/backup metadata, English-only strings, application/system-context
caching, and an available dependency update. Dependencies were not upgraded as
part of this feature change. Compiler deprecation diagnostics remain enabled.

## Firmware and UI validation still required

No physical Infinix device, Android UI runtime, Magisk daemon, Vector process, or
Wi-Fi HAL was available. No Android screenshot or device-level success is claimed.
The framework method signatures and native capability/enforcement flow were
checked against AOSP Android 16 source.

Verify on the device:

1. Original gateway, DHCP, DNS, Lock/Use dynamic buttons, GUI toggles, and ADB commands.
2. Device card/IP click opens the selected MAC only; manual type appears in the list.
3. Remembered profiles survive restart/reboot and a different DHCP IP for the same MAC.
4. Temporary types disappear when the app process terminates.
5. Missing native hostname/addresses/time/stats are not fabricated.
6. Unsupported Wi-Fi blocking is skipped without changing hotspot configuration.
7. If capability is reported, block one test MAC; verify actual disconnection/rejoin
   prevention while other clients and saved hotspot credentials remain intact.
8. Open a disconnected/blocked MAC through Saved device profiles and reset/unblock it.
9. Verify reset affects only the chosen MAC/profile/block and selected-interface IP
   reservation; another interface's reservation and other MACs remain intact.
10. Re-run USB-first DNS tests before enabling the hotspot.

Native block policy is permanent until removed. A successful saved-list readback
is not proof that a vendor driver has disconnected the client. Exact connection
start time, per-client usage/speed/limits/quota, automatic device classification,
and individual USB blocking are intentionally not implemented. See DEVICE_PROFILES.md.

## Included artifact

`artifacts/HotspotManager-0.7.0-unsigned.apk` must be signed with the installed
app's key before an in-place update. No signing key is included. Pre-signing
integrity data is in `artifacts/SHA256SUMS`; signing changes APK bytes.
