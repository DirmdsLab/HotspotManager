# Device profiles in Hotspot Manager 0.7.0

## Existing controls retained

The Detected devices cards retain the interface (`ap0` / `rndis0`), IPv4 address,
MAC address, neighbor state, dynamic/static indication, locked IP, and both
existing IP-assignment buttons. The only additional summary field is Device type.
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
| Reset | Removes the chosen MAC's profile and any native Wi-Fi block; releases only its selected-interface DHCP reservation. |

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
If the MAC has a native Wi-Fi block, unblock it first; a permanent block cannot
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
removes any native Wi-Fi block for that MAC, then writes the reservation update,
and removes the local profile after that write succeeds. Other MACs and
reservations for the other interface are retained. A client must renew DHCP or
reconnect to receive a dynamic address after its reservation is removed.

If native unblock fails, reset stops before removing the profile or reservation.
If the later configuration write fails, the profile remains and the error is
shown; an already removed Wi-Fi block is not automatically re-added. Retry reset
after resolving root/configuration access. Reset does not erase native DHCP
history or disconnect all tethering interfaces.

## Features intentionally skipped

| Requested feature | Status in 0.7.0 |
|---|---|
| Exact connection start time | Not reported by the native client/lease metadata used here. No lease-expiration subtraction or first-observation estimate is substituted. |
| Per-device data usage | No reliable per-client byte accounting is supplied by this Android path. |
| Current per-device Internet speed | Not shown without a reliable per-client byte/time source. |
| Per-IP/MAC speed limiting | Not implemented through firewall/traffic-control workarounds. |
| Per-device data quota | Not implemented without reliable per-client usage and enforcement. |
| Individual USB blocking | No native per-client control is used; USB tethering is not stopped as a substitute. |
| Automatic physical device type/model | Unknown until assigned manually. Hostnames remain labeled as client-supplied DHCP hostnames. |

No iptables/tc rules, offload changes, traffic redirection, background packet
capture, or ongoing polling service are added. Native Android statistics often
cover interface pairs/upstreams rather than individual client MACs; hardware
and eBPF offload further make generic software counters unsuitable as a claimed
per-client measurement here.

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
