# Validation results: Hotspot Manager 0.8.2

Validation date: 2026-11-07 (Asia/Jakarta).

## Confirmed monitoring defect

The supplied phone log contains two distinct owned U32 leaves under priority
50000 in both ingress and egress dumps. Both full ownership cookies are present.
The old engine assumed a priority contained one leaf, so it read four action
counters instead of two and incorrectly refused cleanup as a cookie conflict.
Linux 5.10 U32 common-table walking filters by priority; clsact directions can
share the common classifier storage. A requested dump parent is not sufficient
to distinguish the leaves in this case.

The engine now chooses exactly one leaf by its full cookie before counter reads,
fingerprint checks and accounting. New bindings use an even ingress priority and
the following odd egress priority. Both priorities are checked in both dumps
before allocation. Legacy shared-priority cleanup deletes only the verified owned
handle when another leaf exists. Missing/duplicate cookies still fail closed;
foreign leaves and native root qdiscs are retained. An unused legacy hash table
can remain until reboot rather than risking shared state.

Realtime IPv4 Down/Up, persistent recorded IP/MAC usage and per-MAC speed policies
are retained. Counter totals/cursors, limit settings, GUI controls and their
storage schemas are unchanged.

## USB-first repair

The client log shows public-IP ping failure before hotspot use, followed by
successful IPv4/DNS traffic later. This is broader than DNS alone. The attachment
does not contain the matching native tethering dump or hook log, so the exact
vendor forwarding failure is not independently proven.

Source review found two gaps in the old recovery: it stopped further retries
when an upstream was selected, and an unchanged IpServer upstream set is ignored
without reinstalling forwarding. Version 0.8.2 keeps all three native retries at
2.5, 5 and 10 seconds. After native selection, a session-checked callback performs
one USB-only native upstream clear/rebind while USB is still STATE_TETHERED.
Android's own IpServer handles forwarding/NAT cleanup and re-addition. Native
upstream selection refreshes DNS forwarders. No hotspot enable/disable action,
USB link toggle, DHCP restart or handwritten firewall/NAT rule is introduced.

Once per session, native IP forwarding is reapplied with Tethering's existing
requester string. Netd's requester set makes this operation idempotent; native
stop uses the same owner. Reflection/API failures are logged and do not escape
into Android's state machine. Inactive, replaced and not-yet-started sessions
cannot claim the rebind; a failed operation can be retried by the next bounded
attempt. Successful rebind is limited to once per active USB session.

## Completed checks

- JDK 17; Gradle 8.13; AGP 8.13.2; SDK 36; build tools 36.0.0.
- Package uwu.waguri.hotspotmanager; version code 25; version name 0.8.2.
- Release build passed; unsigned, non-debuggable APK; ZIP alignment passed.
- Original Xposed entry point and both original package scopes retained.
- Unit tests: 76 passed, 0 failures, 0 errors, 0 skipped.
- Lint: 0 errors, 31 warnings; no baseline or blanket suppression added.
- No compiler deprecation note; exact deprecation diagnostics remain enabled.
- Read-only diagnostic shell script passes sh -n.

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
sh -n tools/traffic-diagnostics.sh
```

| Suite | Tests |
|---|---:|
| TrafficRulesTest | 18 |
| UsbUpstreamRecoveryTest | 12 |
| TrafficNetlinkTest | 7 |
| TrafficUsageTest | 6 |
| DeviceProfileTest | 12 |
| BlockedDeviceStateTest | 7 |
| TetheringControlTest | 14 |

New monitoring regressions reproduce the shared-table structure with synthetic
identifiers: four counters in the combined dump, two after exact-cookie leaf
selection, independent directional counters, precise leaf deletion, absent or
duplicate cookies, priority-pair validation and stable fingerprints despite
another direction's presence. USB tests cover bounded timing, inactive/stale
callbacks, one rebind per session, failed rebind retry, replacement and reconnect.
Redundant old readiness-shortcut tests were removed because that shortcut no
longer exists. Existing packet-decoder, usage, control and profile tests pass.

## Scope preservation

TetheringCli, TetheringControl, TetheringController, TetheringReceiver, DeviceCli,
DeviceController, DeviceProfile, DeviceProfiles, BlockedDeviceState, TrafficMode,
TrafficStore, TrafficService, TrafficCli, TrafficNetlink, TrafficUsage, RootShell
and MainActivity are byte-for-byte unchanged from 0.8.1. The existing gateway,
DHCP/DNS and reservation methods in HotspotProbe are unchanged. Only its USB
recovery integration and the recovery controller change. TrafficEngine and
TrafficRules fix selection, direction priorities and legacy cleanup. Source
version, tests and documentation are updated.

The existing 30 lint warnings remain. One additional PrivateApi warning names
INetd access in the scoped native forwarding refresh. The existing hidden API
boundaries are runtime-guarded; no warnings are hidden. Counts: PrivateApi 13,
DiscouragedPrivateApi 5, IconLauncherShape 5, SetTextI18n 2, and one each for
GradleDependency, DataExtractionRules, ObsoleteSdkInt, StaticFieldLeak,
MonochromeLauncherIcon and IconLocation.

## Required phone verification

No physical Android device is connected here. The supplied logs verify that the
previous kernel reader ran on the phone and expose the shared-table bug. Host
regression tests verify the new selection logic, not live forwarding, actual
Mbps enforcement, Vector interception, vendor netd behavior or Android UI.

1. Sign with the installed app's key and update in place. Keep app data.
2. Reboot Android: required to load the new Vector hook and clear stale filters.
3. Leave hotspot off. Enable mobile/upstream data, then USB tethering. Wait up to
   10 seconds. From the client, test the gateway, 1.1.1.1 and DNS independently.
4. Confirm USB_NATIVE_FORWARDING_REFRESH and USB_NATIVE_UPSTREAM_REBIND_QUEUED
   in WaguriHotspotPatch logs; collect failures before enabling hotspot.
5. Start Software QoS monitoring. Test independent rates/usage with two clients,
   then sustained 1 Mbps download/upload enforcement for one client only.
6. Stop/Start, change/remove limits, reconnect USB, change IP, reuse an old IP
   with another MAC, reset usage and Restore. Verify other devices are retained.
7. Test the same USB-first sequence after Restore native mode and its required
   reboot, then return to Software QoS with Prepare/reboot if desired.

Useful logs and read-only diagnostics:

```bash
adb logcat -d -s WaguriHotspotPatch:V WaguriTraffic:V '*:S'
adb shell "su -c 'dumpsys tethering'"
```

IPv6 remains outside measurement and caps. Recorded usage is accepted IPv4 bytes
observed during monitoring, including local traffic and headers, not carrier
billing or unmonitored history. Kernel counter loss can leave an unmeasured
interval. Software QoS can increase CPU/battery use. Policing allows short bursts.
Restore original offload settings and reboot before uninstalling/clearing data.

## Artifact

artifacts/HotspotManager-0.8.2-unsigned.apk requires the existing app's signing
key. SHA256SUMS records its unsigned bytes. Raw user logs/diagnostics, credentials,
keystores, local.properties, caches and build directories are excluded.
