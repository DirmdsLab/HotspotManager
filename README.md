# Hotspot Manager 0.6.9

A Vector module and root-assisted Android app for fixed Wi-Fi/USB tethering IPv4
networks and live DHCP reservations. Intended device: Infinix with MediaTek
Helio G99, Magisk root, and Vector. Runtime hooks target the Android 16
Tethering and NetworkStack class layout used by this project.

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

Version 0.6.8:

- Hooks the active and inactive downstream methods on `TetherMainSM`.
- Sends the native `CMD_RETRY_UPSTREAM` message through that state machine's
  own handler. Android performs upstream selection and DNS-forwarder setup.
- Performs one refresh 2.5 seconds after USB becomes tethered, even when an
  upstream is already selected. If upstream network/interface selection is
  still unavailable, retries at 5 and 10 seconds, then stops.
- Cancels pending callbacks when that USB downstream stops. Duplicate active
  notifications do not create additional retry sequences.
- Deoptimizes the native serving-state callers so compiled call sites can
  reach the hooks.
- Keeps DHCP option 6 independent of gateway replacement and preserves the
  native DHCP parameters if DNS patching fails.
- Allows the fixed gateway when network enumeration succeeds but no upstream
  IPv4 address exists yet. An absent upstream is not a subnet conflict;
  detected overlaps and enumeration failures still use Android's address.

This fixes a verified hook-target error in the source. It has not been tested
on the physical Infinix firmware. OEM class or signature changes are reported
by the hook-installation logs rather than silently treated as success.

AOSP reference:
https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/src/com/android/networkstack/tethering/Tethering.java

## Efficiency and maintenance

- Cache parsed reservation snapshots by exact Settings.Global content and
  interface. Configuration changes remain live; clearing settings invalidates
  cached reservations. Unchanged invalid JSON is not repeatedly parsed/logged.
- Resolve DNS parcel fields and USB hook reflection once during installation.
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
`artifacts/HotspotManager-0.6.9-unsigned.apk`. Sign it before installation using
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
