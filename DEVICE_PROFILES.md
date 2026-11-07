# Device profiles in Hotspot Manager 0.8.3

## Existing controls retained

The Detected devices cards retain the interface (`ap0` / `rndis0`), IPv4 address,
MAC address, neighbor state, dynamic/static indication, locked IP, and both
existing IP-assignment buttons. Additional summary fields show Device type and optional IPv4 download/upload speeds.
Tap the card or its IP address to open that MAC's details.

The gateway, DHCP, fixed DNS, reservations, USB-first recovery, GUI tethering
switches, and ADB tethering commands retain their existing behavior.

## Available features

| Feature | Behavior |
|---|---|
| Device type | Manual selection: Unknown, Phone, Tablet, Computer, TV, Game console, IoT device, Other. Android does not reliably identify the form factor. No MAC/vendor/hostname guess is made. |
| Device details | Selected interface/IP/MAC, existing reservation, native client-record presence, DHCP hostname and assigned addresses when reported. |
| Lease expiration | Shown only when Android supplies a finite DHCP/address expiration. It is not a connection-start timestamp. |
| Remember settings | Saves the manual type and last interface/IP under the normalized MAC in private app preferences. |
| Temporary type | With Remember settings unchecked, the type lasts only for the app process. |
| Wi-Fi block | Available only when the native Soft AP capability reports client force-disconnect support, or when an existing block needs removal. |
| Saved device profiles | Added below the existing Manual & Saved content, so a blocked/disconnected MAC can still be opened and reset. |
| Reset | Removes the chosen MAC's profile, recorded usage, rate policy and native Wi-Fi block; releases only its selected-interface DHCP reservation. |

## Blocked-device list in 0.7.1

Blocked devices appears below Detected devices and reads Android's saved Wi-Fi
blocklist directly. It includes entries from Android Settings even without an
app profile or a current neighbor record. Unblock on this list keeps the profile
and IP reservation. Refresh reloads native state; failed reads are shown as an
error/last-known snapshot, not as an empty blocklist.

See TRAFFIC_STATUS.md for the optional software IPv4 speed/limit mode and its physical validation requirements.

## MAC identity and persistence

Profiles are keyed by normalized MAC alone, not IP. The same MAC keeps its type
when DHCP assigns a different address or when the saved profile is reopened.
The profile may be updated with the latest interface/IP when saved again.

A changed or rotated private MAC is a different identity. MAC-based profiles do
not identify the physical device behind a changed MAC. A cached neighbor entry
or a DHCP lease is not proof that the device is currently connected.

Permanent device-type profiles survive app restarts, device reboots, and in-place
updates signed with the same key. They are not part of the existing
Settings.Global DHCP-reservation JSON; its schema and key remain unchanged.
Temporary profiles disappear when Android terminates the app process.

Unchecking Remember settings and saving removes that permanent type profile.
Remove any saved rate policy first. If the MAC has a native Wi-Fi block, unblock it first; a permanent block cannot
be presented as temporary. The app also refuses to remove a permanent profile
while its native block state could not be read.

## Native Wi-Fi blocking

Blocking uses Android's saved SoftApConfiguration MAC blocklist, guarded by
SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT capability detection. A missing API,
permission failure, missing capability, or capability timeout prevents adding
a new block. Firmware without reported support is skipped.

The app copies the current hotspot configuration, edits only the selected MAC's
blocked-list membership, calls setSoftApConfiguration, and verifies the saved
membership. SSID, password, allowed-client list, client-control mode, DHCP,
DNS, other MACs, and the remaining hotspot fields are preserved.

If the selected MAC is explicitly in Android's allowed-client list, blocking
is rejected. Resolve that selected-MAC entry in Android hotspot settings first;
the app does not enable allowlist-only mode or rewrite that list.

The native block is permanent until explicitly unblocked or reset. Remember
settings must be checked before adding a block, and a saved profile lets you
reach the controls after that client disappears from the neighbor list. Android
keeps enforcing the block on the same MAC without a monitoring service or app
relaunch. The result confirms saved policy, not measured packet loss or a
verified disconnection on the vendor firmware.

Android owns the saved blocklist. A native block can survive uninstalling this
app or disabling the Vector module. Unblock/reset the MAC before uninstalling,
or remove that entry in Android hotspot settings. An in-place app update keeps
the saved app profiles.

USB per-device blocking is skipped. A Wi-Fi block does not block USB traffic.

## Reset scope

Reset asks for confirmation naming the selected MAC and interface. It first
removes any native Wi-Fi block and rate policy for that MAC, then writes the reservation update,
and removes the local profile after that write succeeds. Recorded usage is also cleared for that MAC. Other MACs and
reservations for the other interface are retained. A client must renew DHCP or
reconnect to receive a dynamic address after its reservation is removed.

If native unblock fails, reset stops before removing the profile or reservation.
If the later configuration write fails, the profile remains and the error is
shown; an already removed Wi-Fi block is not automatically re-added. Retry reset
after resolving root/configuration access. Reset does not erase native DHCP
history or disconnect all tethering interfaces.

## Traffic policies

The details page adds separate download/upload Mbps fields. Remember settings
must be enabled to save a nonzero limit. The policy is permanent and keyed by MAC,
independently of DHCP reservations. Remove MAC rate limits clears both directions;
reset also clears the MAC policy. A running monitor refreshes active rules after
a change. Stops and failures are reported; saved policies alone do not prove
that a cap is being enforced. See TRAFFIC_STATUS.md.

## Features intentionally skipped

| Requested feature | Status in 0.8.3 |
|---|---|
| Exact connection start time | Not reported by the native client/lease metadata used here. No lease-expiration subtraction or first-observation estimate is substituted. |
| Historical unmonitored usage | Not available. Current-session and cumulative MAC IPv4 usage count only observed monitor counters. |
| Internet-only / IPv6 throughput | The optional monitor reports IPv4 traffic including local traffic, not Internet-only or IPv6 throughput. |
| IPv6 or aggregate multi-address cap | Not implemented. Software policing applies separately to each current IPv4/MAC binding. |
| Per-device data quota | Not implemented. Recorded usage does not enforce a byte quota. |
| Individual USB blocking | No native per-client control is used; USB tethering is not stopped as a substitute. |
| Automatic physical device type/model | Unknown until assigned manually. Hostnames remain labeled as client-supplied DHCP hostnames. |

No quota, packet capture, vendor-identity guess, or USB-disconnect substitute
is added. The optional Software QoS mode explicitly changes acceleration settings
and adds its own IPv4 tc filters. Ordinary tethering starts with this mode off.

## Device validation

After signing/installing the APK and rebooting:

1. Verify all original card fields, Lock/Use dynamic actions, and tethering controls.
2. Tap a device; assign a manual type and save with Remember settings checked.
3. Restart the app and reconnect the same MAC with a different IP; verify its type.
4. Verify a temporary type disappears after the app process is terminated.
5. Check optional native hostname/addresses; missing data must show Not reported.
6. If Wi-Fi blocking support is reported, block one test MAC. Confirm other clients
   remain online, saved hotspot credentials are retained, and that MAC cannot rejoin.
7. Open that MAC under Saved device profiles and unblock or reset it.
8. Verify reset removes only that MAC's selected-interface reservation/profile/block.
9. Repeat the retained USB-first gateway/public-IP/DNS tests from README.md.

No physical Infinix/HAL/UI testing was available during creation. A reported
capability and successful configuration write still need the firmware checks above.

## Primary Android references

- Native tethered-client metadata: https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/common/TetheringLib/src/android/net/TetheringManager.java
- Native Soft AP capability and blocklist: https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android16-release/framework/java/android/net/wifi/SoftApConfiguration.java
- Native client enforcement: https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/android16-release/service/java/com/android/server/wifi/SoftApManager.java
- Offload accounting: https://source.android.com/docs/core/data/tethering-offload
- Interface-pair tethering statistics: https://source.android.com/docs/core/data/tethering-data
