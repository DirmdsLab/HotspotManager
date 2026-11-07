# Validation results: Hotspot Manager 0.7.1

Validation date: 2026-11-07 (Asia/Jakarta).

## Scope and pending work

Implemented: native Blocked devices list below Detected devices, refresh/error
states, disconnected/native-only MAC entries, and selected-MAC Unblock without
removing the profile or DHCP reservation.

Not implemented: per-IP realtime speed and speed limiting. Device/kernel/offload
verification is still required. TRAFFIC_STATUS.md and the included read-only
traffic-diagnostics.sh explain the next input needed. No traffic counters or
shaping settings are installed, and no Mbps display or rate-limit success is claimed.

## Build checks

- JDK 17; Gradle 8.13; AGP 8.13.2; SDK 36; build tools 36.0.0.
- Package: uwu.waguri.hotspotmanager; version code 22; version name 0.7.1.
- Release build passed; unsigned/non-debuggable APK; ZIP alignment check passed.
- Xposed entry point and both original scopes retained.
- Unit tests: 43 passed, 0 failures, 0 errors, 0 skipped.
- Lint: 0 errors, 29 warnings; no baseline or blanket suppression added.
- Deprecation diagnostics remain enabled; no deprecated-API compiler note emitted.
- Read-only diagnostic script shell syntax: passed (`sh -n`).

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
sh -n tools/traffic-diagnostics.sh
```

## Automated suites

- BlockedDeviceStateTest: 7 tests.
- DeviceProfileTest: 12 tests.
- TetheringControlTest: 14 tests.
- UsbUpstreamRecoveryTest: 10 tests.

The new snapshot tests distinguish unavailable/failed reads from confirmed empty
lists, retain last-known state after failure, reject partially invalid snapshots,
normalize/deduplicate MAC identities, keep disconnected entries independently of
profiles, preserve unrelated MACs after unblock, and prevent caller mutation.
The previous 36 recovery, command, and profile tests remain passing.

## Retained code

Gateway/DHCP/DNS/USB recovery, the core tethering control classes, RootShell,
TetheringCli, DeviceProfile, and DeviceProfiles are byte-for-byte unchanged from
0.7.0. DeviceCli adds native list reads/results; DeviceController adds the
validated list query; MainActivity adds the visible blocklist and its Unblock UI.

No global offload, qdisc, firewall, NAT, or routing change is introduced. The
read-only diagnostic script is not run automatically by the app.

## Warnings retained

- DataExtractionRules: 1.
- DiscouragedPrivateApi: 5.
- GradleDependency: 1.
- IconLauncherShape: 5.
- IconLocation: 1.
- MonochromeLauncherIcon: 1.
- ObsoleteSdkInt: 1.
- PrivateApi: 11.
- SetTextI18n: 2.
- StaticFieldLeak: 1.

These are the existing intentional framework-reflection, artwork/backup,
English-only string, context-cache, and available-dependency-update diagnostics.
Dependencies were kept stable.

## Physical testing still required

No Android device, Infinix kernel, Magisk daemon, Android UI, or vendor Wi-Fi HAL
was available here. Native list retrieval/unblock and existing functionality
must be verified on the phone. The diagnostic script was syntax checked, not
executed against the Android device. No traffic support decision is inferred
from host tools or from unavailable device data.

Check that the native list includes disconnected entries without an app profile;
unblocking one entry leaves other entries, its profile, and its IP reservation
intact; failed reads do not report an empty list; and original USB-first DNS,
tethering controls, and device profiles still work.

## Artifact

artifacts/HotspotManager-0.7.1-unsigned.apk requires signing with the installed
app's key for an in-place update. SHA256SUMS records pre-signing bytes.
