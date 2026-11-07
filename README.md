# Hotspot Manager 0.8.2

A Vector module and root-assisted Android app for fixed Wi-Fi/USB tethering IPv4
networks and live DHCP reservations. Intended device: Infinix with MediaTek
Helio G99, Magisk root, and Vector. Runtime hooks target the Android 16
Tethering and NetworkStack class layout used by this project.

## Version 0.8.2 USB-first forwarding and shared-U32 repair

- Fix the confirmed monitoring failure: this kernel's U32 dump can include both
  clsact directions when they use the same priority. Select one exact leaf by its
  full cookie before reading its two action counters or checking its fingerprint.
- Allocate separate even/odd priorities for upload/download, preventing new
  directions from sharing a U32 priority. Keep existing speed limits and usage.
- Reconcile legacy shared-priority filters by deleting only the verified owned
  leaf when another leaf is present. Retain foreign rules and root qdiscs.
- Run all three bounded USB upstream refreshes at 2.5, 5 and 10 seconds. A selected
  network alone is no longer treated as proof of working USB forwarding.
- Once per active USB session, reassert native IP forwarding using Android's
  existing requester, then queue a USB-only native upstream clear/rebind after
  selection. This rebuilds forwarding/NAT despite an unchanged cached interface
  set. Native upstream selection also refreshes DNS forwarders.

Update with the existing signing key and reboot Android to load the new Vector
hooks and clear old transient filters. Test USB first with hotspot off. Wait up
to 10 seconds; test the gateway, a public IP and DNS separately. Start Software
QoS monitoring after USB connectivity works. No hotspot toggle is automated.
Physical Infinix validation of these changes is still required.

## Version 0.8.1 monitoring repair and usage

- Read full action cookies and 64-bit accepted-byte counters directly from kernel
  routing netlink, avoiding firmware-specific tc display formats.
- Reconcile saved 0.8.0 ownership metadata using kernel cookies, while keeping
  foreign/shared filters protected. A reboot clears stale transient filters.
- Preserve the original monitoring failure when cleanup also fails; add the
  WaguriTraffic log tag for monitoring/ownership diagnostics.
- Add persistent recorded IPv4 usage per current IP/MAC binding and total per MAC.
  Atomically checkpoint byte totals with their counter cursors to avoid counting
  the same bytes again after process recreation.
- Keep usage across rate-policy changes and reconnects; reset usage for one MAC
  without changing its speed limits or other devices.
- Check downstream interface identity before traffic-control mutations.

Update in place with the same signing key, reboot Android, enable tethering,
and start monitoring. Existing Software QoS preparation and rate policies are
retained; prepare/reboot first if software mode has not been prepared. Do not
uninstall or clear app data to recover from the old cookie message.

Recorded usage covers accepted IPv4 bytes actually observed by the monitor,
including local traffic and headers. It does not reconstruct traffic outside
monitoring, data from before installation, IPv6, or carrier billing. Kernel
counter loss before a checkpoint can leave a short unmeasured interval.

## Version 0.8.0 optional Software QoS

- Show each detected IPv4 address's measured download/upload speed in Mbps.
- Save separate download/upload rate policies by MAC; blank means unlimited.
- Reapply a policy to the same MAC's current managed IPv4 address on reconnect.
- Run monitoring in an explicit foreground service with a Stop action.
- Prepare Software QoS with backed-up hardware/BPF offload settings and a required
  reboot; verify native offload state before installing filters or reporting rates.
- Restore the original offload settings and remove only app-owned traffic filters.
- Keep the native Blocked devices list and selected-MAC Unblock below the device list.

Software QoS is off by default. To use it: **Prepare software mode → reboot
Android → enable tethering → Start monitoring**. To leave it: **Restore native
mode → reboot Android**. CPU/battery use can increase and total throughput can
fall while native acceleration is disabled. Restore before uninstalling or
clearing app data so recovery information remains available.

Rates include local IPv4 traffic and protocol overhead; they are interval
averages, not Internet-only throughput or Wi-Fi link bitrate. IPv6 is neither
measured nor limited. The limiter uses packet policing with short bursts rather
than a traffic queue. Each current IPv4 binding receives its MAC's policy; it is
not an aggregate cap across several addresses belonging to the same MAC.

Device diagnostics confirmed the relevant kernel features and Android tc binary.
Live speed accuracy and enforcement still require physical Infinix traffic tests;
they were not available in the build environment. Missing capabilities or
unexpected offload state produce an error instead of a fabricated speed.
See [TRAFFIC_STATUS.md](TRAFFIC_STATUS.md) for operation and validation.

## Version 0.7.1 blocked-device list

- Add Blocked devices below Detected devices, read from Android's native saved blocklist.
- Include disconnected MACs and native entries without an app profile.
- Unblock one MAC without deleting its type/profile or DHCP reservation.
- Distinguish a failed list read from a confirmed empty list.
- Include a read-only firmware diagnostics script for future per-IP traffic control.


## Version 0.7.0 device profiles

- Existing device cards and IP-assignment actions are retained; add manual device type.
- Tap a device/IP to open native metadata and settings scoped to that MAC.
- Save permanent device-type profiles, reopen saved/disconnected MACs, or reset one device.
- Native Wi-Fi MAC blocking is capability-gated and persists in Android's own blocklist.
- Exact connection time, historical unmonitored usage, quotas, and native USB blocking remain unavailable.
  Version 0.8.0 adds optional IPv4 speed monitoring and rate policies.

See [DEVICE_PROFILES.md](DEVICE_PROFILES.md) for supported fields, persistence,
reset scope, omitted features, and firmware validation.

## Version 0.6.10 maintenance

- Replace deprecated `Looper.prepareMainLooper()` with `Looper.prepare()` in
  the standalone root helper. ActivityThread captures the current thread's
  looper, and tethering callbacks continue to use explicit executors.
- Resolve TetheringManager through `Context.getSystemService(Class)` to avoid
  referencing the API 36 public service-name constant on older SDK targets.
- Enable `-Xlint:deprecation` for every Java compilation task, so future deprecated
  calls are reported with their exact locations.
- Retain the GUI/ADB commands and USB-first recovery from the previous release.

## Version 0.6.9 controls

- Two columns above Network Status: USB and Hotspot on/off switches.
- GUI state follows Android tethering state, including changes from Settings.
- ADB broadcast commands support on, off, and status without opening the GUI.
- A root-only helper uses native TetheringManager APIs and confirms actual state.
- Bounded root execution and serialized controls prevent overlapping requests.
- The 0.6.8 USB-first recovery implementation is retained.

See [TERMINAL.md](TERMINAL.md) for setup, commands, result semantics, and tests.

## USB-first startup fix

The previous recovery hook looked up `handleInterfaceServingStateActive` on
`Tethering`. In AOSP Android 16, this method is declared on the inner
`Tethering$TetherMainSM` class, so the old hook could fail to install before
queuing any recovery message.

Current recovery (updated in 0.8.2):

- Hooks the active and inactive downstream methods on `TetherMainSM`.
- Sends the native `CMD_RETRY_UPSTREAM` message through that state machine's
  own handler. Android performs upstream selection and DNS-forwarder setup.
- Performs native refreshes at 2.5, 5 and 10 seconds even when an upstream has
  already been selected. After selection, reasserts forwarding once per active
  USB session with Android's existing requester and queues a USB-only native
  clear/rebind to reinstall forwarding/NAT through IpServer.
- Cancels pending callbacks when that USB downstream stops. Duplicate active
  notifications do not create additional retry sequences.
- Deoptimizes the native serving-state callers so compiled call sites can
  reach the hooks.
- Keeps DHCP option 6 independent of gateway replacement and preserves the
  native DHCP parameters if DNS patching fails.
- Allows the fixed gateway when network enumeration succeeds but no upstream
  IPv4 address exists yet. An absent upstream is not a subnet conflict;
  detected overlaps and enumeration failures still use Android's address.

This retains the hook-target fix and adds native USB forwarding recovery. It has not been tested
on the physical Infinix firmware. OEM class or signature changes are reported
by the hook-installation logs rather than silently treated as success.

AOSP reference:
https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/src/com/android/networkstack/tethering/Tethering.java

## Efficiency and maintenance

- Cache parsed reservation snapshots by exact Settings.Global content and
  interface. Configuration changes remain live; clearing settings invalidates
  cached reservations. Unchanged invalid JSON is not repeatedly parsed/logged.
- Cache DNS parcel fields and the core USB hook reflection during installation.
  The optional native forwarding refresh runs once per active USB session.
- Prevent reservation changes while another refresh/save is in progress.
- Make private release-signing files optional, so the source package can build
  without the original developer's keystore.
- Include a Gradle wrapper with a pinned distribution checksum and deterministic
  unit tests for USB recovery.
- Guard the API 33 public `IpPrefix` constructor while retaining the existing
  hidden-constructor path for older scoped Android processes.

All application text, source comments, and documentation are in English.

## Network configuration

| Interface | Gateway | Subnet | Dynamic pool |
|---|---|---|---|
| Wi-Fi `ap0` | `192.168.77.1` | `192.168.77.0/24` | `.100-.200` |
| USB `rndis0` | `192.168.78.1` | `192.168.78.0/24` | `.100-.200` |

DHCP advertises `1.1.1.1` and `8.8.8.8`. Gateway replacement is skipped when a
conflicting interface subnet is detected. Public resolvers still require a
working upstream and packet forwarding; a successful DHCP hook alone does not
prove Internet reachability.

Reservations are stored in `Settings.Global` under
`waguri_hotspot_reservations_v1`:

```json
{
  "version": 2,
  "reservations": [
    {"iface": "rndis0", "mac": "02:11:22:33:44:55", "ip": "192.168.78.10"}
  ]
}
```

The example MAC is illustrative; no reservation is installed by default.
Reserved addresses can be `.2-.254`, including addresses inside the dynamic
pool. A client must renew DHCP or reconnect after its reservation changes.

## Included release artifact

The fixed source distribution includes
`artifacts/HotspotManager-0.8.2-unsigned.apk`. Sign it before installation using
the instructions below. `VALIDATION.md` records the completed checks and their
limits; `artifacts/SHA256SUMS` contains the pre-signing APK digest.

## Build

Requirements: JDK 17 or newer, Android SDK platform 36, Android build tools,
and network access for Gradle dependencies.

```bash
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

Without signing credentials, the release output is:

`app/build/outputs/apk/release/app-release-unsigned.apk`

To reuse the original release-signing workflow, provide both sibling files:

- `../key/infinix-hotspot-release.jks`
- `../key/keystore.properties`

Properties must define `storePassword`, `keyPassword`, and `keyAlias`. No
private keystore or passwords are included. If either local signing file is
present, both files and all properties must be valid.

Alternatively, sign the unsigned release with your existing key:

```bash
apksigner sign --ks my-release-key.jks --out app-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose app-release.apk
adb install -r app-release.apk
```

Use the same signing key as the installed app to update it in place. Android
rejects an update signed with a different key. Keep the current APK and export
reservations before uninstalling anything.

## Install and enable

1. Install the signed APK with application ID `uwu.waguri.hotspotmanager`.
2. Enable Hotspot Manager in Vector and keep both scopes enabled:
   `com.android.networkstack` and `com.android.networkstack.tethering`.
3. Grant Hotspot Manager root permission in Magisk for reading clients and
   saving reservations.
4. Reboot once so the new hook code is loaded into the tethering processes.
5. Leave Wi-Fi hotspot disabled and test USB tethering first.

The root permission serves app configuration access. Hook execution depends
on Vector loading the module in the scoped processes.

## Device validation

After reboot, connect mobile data or an upstream Wi-Fi network, leave Wi-Fi
hotspot disabled, and enable USB tethering. Wait 10 seconds.

On Windows:

```bat
ping 192.168.78.1
ping 1.1.1.1
nslookup example.com 1.1.1.1
nslookup example.com 8.8.8.8
nslookup example.com
```

On Linux/macOS:

```bash
ping -c 3 192.168.78.1
ping -c 3 1.1.1.1
dig @1.1.1.1 example.com +time=2 +tries=1
dig @8.8.8.8 example.com +time=2 +tries=1
```

Also test USB off/on, hotspot-first then USB, both active with hotspot stopped,
and USB enabled before mobile data becomes available. Android's native network
callbacks continue handling connectivity changes after the bounded retries end.

Interpretation:

- Gateway unreachable: check the USB link and client address assignment.
- Public IP unreachable: check upstream selection, forwarding, and NAT.
- Public IP reachable but DNS queries fail: inspect DNS forwarding and DHCP
  option 6; verify the DNS servers actually received by the client.
- `USB_UPSTREAM_RECOVERY_HOOK_INSTALLED` confirms hook registration.
- `USB_UPSTREAM_RETRY_QUEUED ... queued=true` confirms a pending callback.
- `USB_UPSTREAM_RETRY_SENT ... queued=true` confirms a native retry was sent.
- `USB_NATIVE_FORWARDING_REFRESH` confirms the native forwarding requester was reapplied.
- `USB_NATIVE_UPSTREAM_REBIND_QUEUED` confirms a USB-only native binding reset was queued.
- `UPSTREAM_SELECTION` records the selected upstream network and interfaces.
- `DNS_DHCP_FIXED iface=rndis0` confirms the DHCP DNS parcel was patched.

If a test fails, collect logs before enabling the hotspot:

```bash
adb logcat -d -s WaguriHotspotPatch:I '*:S'
adb shell "su -c 'dumpsys tethering'"
adb shell "su -c 'ip -4 addr show rndis0; ip route; ip rule show'"
```

Do not clear logcat after reproducing the failure. Capture the first-session
logs and the failing client commands together.

## Automated coverage

`UsbUpstreamRecoveryTest` exercises bounded USB-first retry timing, an already
selected upstream, delayed upstream availability, duplicate notifications,
USB shutdown, unrelated Wi-Fi shutdown, rapid reconnects, replacement
interfaces, rejected scheduling, and cancellation during a retry callback.

These tests validate retry lifecycle behavior. They do not emulate Vector's
runtime method interception, the vendor modem, NAT, or actual DNS traffic.
