# Validation results: Hotspot Manager 0.8.3

Validation date: 2026-11-07 (Asia/Jakarta).

## Changes and evidence

The user reported approximately 3–4 Mbps download at a 5 Mbps cap, and about
5 Mbps at an 8 Mbps cap, while upload tracked its configured rate. No new phone
trace or physical Android device was available. The exact cause of that measured
shortfall has not been proven.

Source review found a download policer bucket that can hold barely one full
64 KiB GRO/GSO skb at 5 Mbps. Closely spaced aggregates can exhaust that bucket,
producing clustered packet loss and TCP backoff. The download bucket now holds
at least four such aggregates or 500 ms of tokens. Upload retains its previous
parameters. The configured rate and SI unit conversion are unchanged.

| Input | Download burst in 0.8.2 | Download burst in 0.8.3 | Installed rate |
|---|---:|---:|---:|
| 5 Mbps | 65,536 bytes | 312,500 bytes | 5,000 kbit/s |
| 8 Mbps | 100,000 bytes | 500,000 bytes | 8,000 kbit/s |

This is a targeted burst-tolerance improvement, not a guarantee that TCP payload
goodput equals the selected cap. The token envelope remains rate times elapsed
time plus the bucket capacity; short samples can exceed the cap. The kernel
still drops excess packets, and upstream capacity, radio conditions, CPU limits,
protocol overhead and TCP behavior still affect sustained throughput. Native root
qdiscs and all existing ownership guards are retained.

Usage now has two rows: current-session IPv4 usage per interface/MAC, and
cumulative recorded IPv4 usage for the MAC across interfaces and addresses.
Existing `usage_<MAC>` totals are retained. Native association and USB lifecycle
notifications generate session identities, rather than interpreting ARP cache
states as physical connection events. Session snapshots use an explicit receiver
with the `TETHER_PRIVILEGED` sender permission, boot validation and monotonic
publication ordering. No new global settings write or permission grant is needed.

Reconnection rotates the session while retaining the MAC total. Policy/IP changes
and monitor recreation preserve the same native session. Accepted-byte cursors,
MAC totals and matching session totals are committed together. Old-generation
rules contribute only to the MAC total after a reconnect. The first interval
before fresh filters are installed cannot be attributed precisely to the new
session and is excluded from its usage; it is never fabricated.

## Completed checks

- JDK 17; Gradle 8.13; AGP 8.13.2; SDK 36; build tools 36.0.0.
- Package `uwu.waguri.hotspotmanager`; version code 26; version name 0.8.3.
- Release build passed; non-debuggable, unsigned APK; ZIP alignment passed.
- Final release DEX contains the current session snapshot ordering implementation.
- Original Vector entry point and both package scopes retained.
- Unit tests: 95 passed, 0 failures, 0 errors, 0 skipped.
- Lint: 0 errors, 31 warnings; no baseline or blanket suppression added.
- Read-only diagnostic script passes `sh -n`.

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
sh -n tools/traffic-diagnostics.sh
```

| Suite | Tests |
|---|---:|
| TrafficRulesTest | 20 |
| UsbUpstreamRecoveryTest | 12 |
| TrafficNetlinkTest | 7 |
| TrafficUsageTest | 6 |
| TrafficSessionStateTest | 6 |
| TrafficStoreTest | 11 |
| DeviceProfileTest | 12 |
| BlockedDeviceStateTest | 7 |
| TetheringControlTest | 14 |

New regressions cover aggregate-sized burst clusters and the sustained token
budget, fast same-MAC reconnects, independent USB/Wi-Fi generations, duplicate
notifications, stale USB owners, preserved legacy totals, counter deduplication
across store recreation, IP/rule replacement, reset baselines, failed atomic
checkpoints, unknown native events, immediate session display before the next
poll, and previous-boot/duplicate/out-of-order native snapshots. Host tests do not
emulate actual TCP, Android broadcast permission enforcement, Vector interception
or physical connection events on the vendor firmware.

Warnings are PrivateApi 14, DiscouragedPrivateApi 5, IconLauncherShape 5,
SetTextI18n 2, and one each for DataExtractionRules, ObsoleteSdkInt,
StaticFieldLeak, MonochromeLauncherIcon and IconLocation. Hidden API operations
remain runtime-guarded and failures are logged.

## Required phone verification

1. Sign with the installed app's key and update in place. Keep app data.
2. Reboot to load the Vector hooks. Open the app once and reconnect clients.
3. Start Software QoS monitoring. Preparation does not need repeating if it was
   already completed and retained. Confirm `TRAFFIC_SESSION_HOOK_INSTALLED` in
   `WaguriHotspotPatch`; session publication failures have distinct log markers.
4. With an unrestricted IPv4 baseline comfortably above 8 Mbps, test 5 Mbps
   download/upload for at least 60 seconds after warm-up. Repeat at 8 Mbps.
   Compare average endpoint throughput, monitor rates and packet loss, rather
   than a single peak reading. Verify another MAC's rate policy remains independent.
5. Transfer data, disconnect and reconnect the same MAC/IP. Confirm current-session
   usage restarts from zero and the MAC total remains. Repeat with a quick
   reconnect, an IP change, a policy change and monitor Stop/Start.
6. Repeat on USB and Wi-Fi separately and together. Check hotspot/USB off/on,
   stale ARP records, an old IP reused by another MAC, explicit usage reset,
   process recreation and reboot. Only a genuine connection generation change
   resets the session; a tethering-process restart also starts fresh generations
   because its prior physical connection history cannot be reconstructed.
7. Verify original USB-first forwarding, gateways, DNS, reservations, GUI/ADB
   controls and Stop/Restore cleanup. These existing implementations are retained.

Usage includes accepted monitored IPv4 bytes, local traffic and headers. It
excludes IPv6, unmonitored historical traffic and data lost when kernel counters
vanish. Neither row is a carrier-billing estimate. A force-stopped app can miss
native broadcasts until opened again. Exact physical Mbps performance and OEM
hook/broadcast behavior still require testing on the phone.

## Artifact

`artifacts/HotspotManager-0.8.3-unsigned.apk` must be signed with the existing
installation's key. `artifacts/SHA256SUMS` records its unsigned bytes. The archive
excludes the old 0.8.2 APK, private signing keys, credentials, local SDK paths,
caches and build directories.
