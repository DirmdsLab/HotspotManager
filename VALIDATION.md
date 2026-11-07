# Validation results: Hotspot Manager 0.8.0

Validation date: 2026-11-07 (UTC).

## Implemented scope

Optional Software QoS adds per-IP IPv4 Down/Up Mbps, permanent per-MAC rate
policies, foreground monitoring, ownership-checked tc cleanup, backed-up offload
preparation, and restoration. The native Blocked devices list and Unblock remain.
Software mode is off by default and requires a reboot before monitoring.

The supplied Infinix X1101B / Android 16 diagnostic confirmed kernel U32/policing
features, Android tc, clsact/native BPF filters, and active offload. It supports
implementing the optional path but does not prove live packet matching, accurate
throughput measurement, or rate enforcement. Those remain physical tests.

## Build and static checks

- JDK 17; Gradle 8.13; AGP 8.13.2; SDK 36; build tools 36.0.0.
- Package uwu.waguri.hotspotmanager; version code 23; version name 0.8.0.
- Release build passed; unsigned, non-debuggable APK; ZIP alignment check passed.
- Original Xposed entry point and both original package scopes retained.
- Unit tests: 56 passed, 0 failures, 0 errors, 0 skipped.
- Lint: 0 errors, 29 warnings; no baseline or blanket suppression added.
- Deprecation diagnostics enabled; no deprecated-API compiler note emitted.
- Read-only diagnostics shell syntax passed (sh -n).

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
sh -n tools/traffic-diagnostics.sh
```

## Automated coverage

| Suite | Tests |
|---|---:|
| TrafficRulesTest | 13 |
| BlockedDeviceStateTest | 7 |
| DeviceProfileTest | 12 |
| TetheringControlTest | 14 |
| UsbUpstreamRecoveryTest | 10 |

New tests cover independent 40 Mbps / 0.1 Mbps samples, variable sample duration,
initial/reset counters, accepted versus dropped bytes, ambiguous counters, old
and new tc cookie formats, shared-priority deletion refusal, native filter
separation, fingerprints, SI Mbps validation, source/destination MAC/IP matching,
shell-input rejection, neighbor validation, reboot identity, settings flags,
and native offload confirmation. Existing recovery/control/profile/blocklist
tests continue passing.

These are host-side Java tests. They do not execute Android services, Magisk,
netlink, kernel classifiers, Vector hooks, or the vendor Wi-Fi HAL.

## Retained core source

The following are byte-for-byte unchanged from 0.7.1: HotspotProbe,
UsbUpstreamRecovery, TetheringCli, TetheringControl, TetheringController,
TetheringReceiver, DeviceCli, DeviceController, DeviceProfile, DeviceProfiles,
and BlockedDeviceState. Gateway, DHCP, DNS, native controls, reservations, and
Wi-Fi blocking keep their original implementation.

RootShell adds a bounded 1 MiB output option for filter snapshots; existing
callers retain the 64 KiB default and 35-second timeout. The new worker batches
native readiness fields into one root call and filter samples into another.
MainActivity adds mode controls, speed labels and rate policy fields. Manifest
changes add the private foreground service, private boot receiver and required
permissions. No firewall, NAT, route, native root qdisc, or BPF map rewrite is added.

## Retained lint warnings

PrivateApi 11; DiscouragedPrivateApi 5; IconLauncherShape 5;
SetTextI18n 2; GradleDependency 1; DataExtractionRules 1; ObsoleteSdkInt 1;
StaticFieldLeak 1; MonochromeLauncherIcon 1; IconLocation 1.

These existing diagnostics concern framework reflection, artwork/backup,
English-only strings, context caching and an available dependency update.
Dependencies remain unchanged.

## Physical verification still required

No Android device was connected to the build environment. GUI rendering,
foreground notification/boot behavior, live tc matching/counters, vendor
acceleration disablement, reconnect/reuse handling, Mbps accuracy, and sustained
1 Mbps enforcement have not been tested on the phone. Rule readback is shown as
Installed, not as verified throughput enforcement. TRAFFIC_STATUS.md contains
the required two-client, both-direction, restore, and original DNS test plan.

IPv6 and Internet-only accounting, historical usage, quotas, aggregate caps for
multiple addresses, exact connection time, and native USB-client blocking are
not implemented. Short token-bucket bursts are allowed. Software mode can use
more CPU/battery and lower total throughput. A process kill can leave rules;
use Stop/Restore and inspect errors. Restore original offload settings and reboot
before clearing app data or uninstalling.

## Artifact

artifacts/HotspotManager-0.8.0-unsigned.apk requires signing with the installed
app's existing key for an in-place update. SHA256SUMS records its unsigned bytes.
The package excludes the private diagnostic dump, signing keys, credentials,
local.properties, dependency caches and build directories.
