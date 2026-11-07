# Validation results: Hotspot Manager 0.8.1

Validation date: 2026-11-07 (Asia/Jakarta).

## Reported problem and repair

The phone reported monitoring failure followed by a cleanup ownership mismatch.
The supplied WaguriHotspotPatch log shows normal gateway, DNS, DHCP reservations
and upstream selection. It does not contain a traffic filter dump or the original
monitor exception, so the exact old cookie mismatch could not be reproduced here.

The traffic reader now retrieves full cookies, static U32/police configuration
and 64-bit accepted-byte counters directly from Linux RTM_GETTFILTER netlink dumps
in a root-only helper. Vendor tc statistics formatting is no longer used for
ownership or usage/speed readings. tc remains responsible for validated rule
mutations; original Android root qdiscs and unrelated filters are retained.
Original monitoring errors remain visible when cleanup also fails. WaguriTraffic
logs the original exception and any specific ownership conflict.

Persistent recorded usage is added per IPv4/MAC binding and total per MAC. Totals
and their kernel-counter cursor are committed together. Rate changes, reconnects
and process recreation do not deliberately reset totals or re-count a persisted
counter. Usage-only reset is scoped to one MAC and establishes a new baseline.
Downstream identity is checked immediately before rule changes.

## Build and static checks

- JDK 17; Gradle 8.13; AGP 8.13.2; SDK 36; build tools 36.0.0.
- Package uwu.waguri.hotspotmanager; version code 24; version name 0.8.1.
- Release build passed; unsigned, non-debuggable APK; ZIP alignment passed.
- Original Xposed entry point and both original package scopes retained.
- Unit tests: 69 passed, 0 failures, 0 errors, 0 skipped.
- Lint: 0 errors, 30 warnings; no baseline or blanket suppression added.
- No deprecated-API compiler note emitted; deprecation diagnostics remain enabled.
- Read-only diagnostic script passes sh -n.

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
sh -n tools/traffic-diagnostics.sh
```

## Automated coverage

| Suite | Tests |
|---|---:|
| TrafficNetlinkTest | 7 |
| TrafficUsageTest | 6 |
| TrafficRulesTest | 13 |
| BlockedDeviceStateTest | 7 |
| DeviceProfileTest | 12 |
| TetheringControlTest | 14 |
| UsbUpstreamRecoveryTest | 10 |

Binary fixtures cover netlink request framing, clsact parent identity, full
16-byte cookies, counters above 32 bits, repeated alignment padding attributes,
interface/direction separation, stable configuration fingerprints despite changing
counters, multipart completion, corrupt frames, sequence mismatch, kernel errors
and interrupted dumps. Usage tests cover persisted cursor deltas, repeated reads,
counter resets, explicit baseline reset, independent directions/devices, overflow
rejection and explicit byte units. Existing Mbps, command validation, offload,
recovery, tethering control, profile and native blocklist tests continue passing.

Fixtures exercise the Linux UAPI decoder; they are not live vendor kernel tests.
The host sandbox rejects AF_NETLINK sockets, and no Android device is connected.
Android helper socket access, hidden-address reflection, actual kernel replies,
GUI rendering, notification/boot behavior and packet enforcement are therefore
not validated by these host tests.

## Retained core

HotspotProbe, UsbUpstreamRecovery, TetheringCli, TetheringControl,
TetheringController, TetheringReceiver, DeviceCli, DeviceController, DeviceProfile,
DeviceProfiles, BlockedDeviceState, TrafficMode and RootShell are byte-for-byte
unchanged from 0.8.0. DHCP, DNS, gateway, reservations, native Wi-Fi blocking,
terminal tethering commands and mode preparation/restoration keep their existing
implementation. MainActivity, TrafficEngine, TrafficStore, TrafficService and
TrafficRules integrate the repaired reader and recorded usage. No new firewall,
NAT, route, native BPF map or native root qdisc rewrite is introduced.

## Lint warnings

The 29 previous warnings remain. One additional PrivateApi warning identifies
the Android NetlinkSocketAddress constructor used only by the root helper; its
availability is checked at runtime and errors are surfaced. No suppression was
added. This is a firmware compatibility boundary still requiring a phone test.

Counts: PrivateApi 12; DiscouragedPrivateApi 5; IconLauncherShape 5;
SetTextI18n 2; GradleDependency 1; DataExtractionRules 1; ObsoleteSdkInt 1;
StaticFieldLeak 1; MonochromeLauncherIcon 1; IconLocation 1.

## Upgrade and physical verification

1. Sign with the existing app key and update in place. Keep app data.
2. Reboot Android to clear stale transient 0.8.0 filters. Saved MAC policies,
   reservations, usage and Software QoS preparation are retained. If software
   mode has not been prepared, use Prepare and reboot before monitoring.
3. Enable tethering and Start monitoring. Verify the first sample is Sampling,
   followed by independent Mbps for two client IPs during different transfers.
4. Verify per-IP usage and MAC totals rise; Stop/Start, change a cap and reconnect
   the same MAC. Verify totals persist and prior bytes are not counted again.
5. Set one client's download to 1 Mbps; leave the other client unlimited. Measure
   sustained IPv4 download/upload for at least 30 seconds in both directions.
6. Exercise usage-only reset, full device reset, IP reuse, USB-only first,
   hotspot-only, both interfaces, Stop and Restore. Confirm other devices,
   native filters, DHCP/DNS and original controls continue working.
7. On failure, retain adb logcat -d -s WaguriTraffic:V '*:S' and the read-only
   traffic diagnostic output. Do not remove the complete Android qdisc.

These phone tests have not been performed here. Rule readback means Installed,
not measured throughput enforcement. Software mode still requires disabled
hardware/BPF acceleration and can increase CPU/battery use. IPv6 and Internet-only
accounting, historical unmonitored usage, quotas, aggregate multi-address limits,
exact connection start time and native USB-client blocking remain unavailable.
Short policing bursts are allowed. Usage includes accepted local IPv4 traffic
and headers; it is not carrier billing. Lost kernel counters can leave a short
unmeasured interval. Restore original offload settings and reboot before clearing
app data or uninstalling.

## Artifact

artifacts/HotspotManager-0.8.1-unsigned.apk requires the installed app's existing
signing key for an in-place update. SHA256SUMS records the unsigned bytes. Private
diagnostic dumps, logs, credentials, signing keys, caches, build directories and
local.properties are excluded from the source package.
