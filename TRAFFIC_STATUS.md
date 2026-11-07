# IPv4 speed and rate control in 0.8.2

## What the diagnostic established

The supplied Infinix X1101B / Android 16 diagnostic reports an Android tc binary,
clsact qdiscs, U32 classifiers, policing support, and native BPF tethering filters
on ap0 and rndis0. Hardware offload was started and BPF offload was enabled.
Native BPF accounting is keyed by upstream interface; it does not supply a
separate instantaneous speed for every client IP. The diagnostic contained no
proof that a new software limiter would intercept accelerated traffic.

The latest supplied runtime dump confirms that U32 walks can return upload and
download leaves together under the same priority. Both full cookies were present;
treating the entire priority as one rule caused four counters and a false cleanup
conflict. Linux 5.10 U32 common-table walking filters by priority, and clsact's two
directions can share that common storage.

Version 0.8.2 selects exactly one leaf by its full cookie for accounting and
fingerprints, and allocates different upload/download priorities. Legacy shared
priorities use exact-leaf cleanup when another leaf is present. Regression tests
reproduce the shared-table structure with synthetic identifiers.

The software mode remains explicit and off by default. Original gateways, DHCP,
DNS addresses, reservations, GUI switches and ADB controls are retained. USB-first
recovery now performs all three native refreshes and a single USB-only native
upstream rebind per session. The supplied client log also shows public-IP failure
before hotspot use, so that failure is broader than DNS alone.

## Enable and use

1. In Devices, choose Prepare software mode and read the confirmation.
2. The app saves both original offload settings, requests hardware/BPF offload
   disablement, and verifies those settings. Reboot Android manually.
3. Enable hotspot or USB tethering, then choose Start monitoring. Android's native
   dump must confirm hardware offload disabled and mIsBpfEnabled: false. A changed
   kernel boot identity is required; changing flags alone is insufficient.
4. Detected device cards show each IPv4 address's separate Down/Up Mbps. The
   first reading is Sampling; absent/stale/failed readings are Unavailable.
5. Tap the device/IP. Enable Remember settings for this MAC. Enter download and
   upload limits in Mbps, then Save MAC rate policy. For a 1 Mbps download cap,
   enter 1 under Download; leave Upload blank for unlimited upload.
6. A running monitor updates the MAC's current bindings on its next poll. The
   Installed label confirms rule installation/readback, not a measured traffic
   enforcement test. An inactive or missing binding is labeled pending.
7. Stop monitoring removes owned filters and makes saved limits inactive after
   successful cleanup. Restore native mode also restores original offload
   settings; reboot afterward to reload acceleration.

Policies survive in-place app updates and are keyed by normalized MAC. A changed
or randomized MAC is a different identity. Reconnection or a changed managed IPv4
address is detected while the foreground monitor runs. A previously requested
monitor is resumed on boot when Android permits it; firmware restrictions can
require opening the app and starting it again.

## Measurement and limit semantics

- Only IPv4 on ap0 / 192.168.77.0/24 and rndis0 / 192.168.78.0/24 is supported.
- Values include local IPv4 traffic and protocol overhead. They are not
  Internet-only application payload speeds, Wi-Fi link rates, unmonitored history,
  or IPv6 statistics. IPv6 is not limited and can bypass an IPv4 cap.
- Each filter matches both the IP and MAC, so another MAC reusing an old IP does
  not inherit its cap. Each current IPv4 binding receives the saved policy;
  several addresses for one MAC do not share an aggregate bucket.
- Upload is measured on downstream ingress; download on downstream egress.
  A second, non-dropping action counts bytes accepted by the first policer.
  Offered bytes dropped by the cap are not reported as accepted throughput.
- Mbps uses decimal units: delta bytes * 8 / elapsed seconds / 1,000,000.
  The interval is actual monotonic elapsed time, usually at least two seconds.
- Caps accept 0.1–500 Mbps with up to three decimal places. Blank means unlimited.
  The token bucket permits short bursts (at least 64 KiB for large packets).
  Sustained accepted throughput approaches the cap; short intervals can exceed
  it. Policing drops excess packets rather than queueing them, so TCP throughput
  can fall below the configured value.
- Missing baselines, counter resets, unknown offload state, incomplete snapshots,
  changed rule fingerprints, or stale service results never become fabricated
  zero-speed readings. A real zero-byte delta is displayed as zero.

Software QoS disables native acceleration. CPU/battery use can increase and total
throughput can fall. Monitoring is foreground work, with a persistent notification
and Stop action. A process kill/force-stop can leave the last kernel rules until
recovery or reboot. Use Stop or Restore to verify cleanup; do not assume that
closing the screen or killing the process removed a limit.

Unmonitored historical usage, quotas, exact connection-start time, IPv6 limits, aggregate
multi-address limits, and individual native USB blocking remain unimplemented.

## Recorded data usage

The device card and details show recorded Down/Up usage for the selected IPv4/MAC
binding and a total for the MAC across its addresses/interfaces. Binary units
are explicit (KiB/MiB/GiB). Totals persist in private app preferences across
monitor restarts, same-key updates, reconnects, and rate-policy changes.

Each accepted-byte counter has a saved cursor. Its delta and both usage totals
are committed together; reading the same counter twice or restarting the process
does not count the same bytes again. Old rules are sampled before policy changes
and again before verified cleanup. Initial owned counters start at zero; a reset
counter contributes its new observed bytes. Missing counters are not estimated.

Reset recorded usage for this MAC clears only its usage and establishes a fresh
baseline at the next sample. Full device Reset also clears usage. Speed policies,
other MACs, and DHCP reservations are retained by the usage-only reset.

These are observed accepted IPv4 bytes, including local traffic and headers,
not carrier billing. Traffic while monitoring is off, IPv6, and data lost when an
interface/kernel counter vanishes between samples cannot be reconstructed. A
hard process kill may miss a short interval; surviving owned counters can be
reconciled on the next start. There is no data-quota enforcement.

## Ownership and recovery

The app retains native root qdiscs and existing BPF/other filters. It allocates
unoccupied IPv4 priority pairs in 50000–50127, using the even priority for ingress
and the following odd priority for egress. Both priorities are checked in both
dumps before allocation; up to 64 bindings fit before external reservations. Random action
cookies, kernel boot identity, interface index, and rule fingerprints are saved
for ownership checks. A non-dropping meter proves full kernel cookie/counter readback before
any speed cap is requested. A failure stops monitoring and attempts cleanup.

Cleanup removes a complete priority only when its sole leaf is owned. When a
legacy dump includes other leaves, it deletes only the selected owned handle.
A missing/duplicate ownership cookie is reported; foreign leaves are retained.
An empty legacy hash table may remain until reboot rather than deleting shared state. An app-created empty clsact
is removed only when no ingress/egress filters remain. Existing clsact qdiscs and
native root mq/pfifo_fast qdiscs are retained. No global firewall/NAT/routing
rewrite or native BPF map edit is performed.

Offload backups are saved before changing flags. Restore checks for settings
changed outside the app and retains recovery metadata on failure. Retry Stop or
Restore after resolving root access. For a shared/conflicting filter, inspect
that specific priority; never remove the entire Android qdisc as a workaround.
Reboot clears the transient tc filters but does not restore persistent offload
flags. Restore native mode before clearing app data or uninstalling.

For emergency recovery while app data is intact, inspect the saved before_hw
and before_bpf values in the app's private traffic_control_v1 preferences using
root. Restore those exact values: delete a setting only when its backup is null.
For example, when BOTH original values were null (as in the supplied diagnostic):

```bash
adb shell "su -c 'settings delete global tether_offload_disabled'"
adb shell "su -c 'device_config delete connectivity override_tether_enable_bpf_offload'"
adb reboot
```

Do not use that example for a phone whose original settings were non-null.

## Required physical verification

Host tests validate command validation, binary netlink framing, 64-bit counter/cookie parsing, byte accumulation, Mbps conversion,
ownership parsing, and offload guards. No physical Android device was available
for live traffic, GUI, notification, or vendor enforcement testing.

On the phone, verify the following before depending on a cap:

1. Prepare and reboot. Confirm native dump state before starting monitoring.
2. Transfer IPv4 data for two different clients at different rates. Check each
   address's independent Down/Up values against endpoint transfer measurements.
3. Apply 1 Mbps to one MAC only. Measure sustained download and upload for at
   least 30 seconds; the other client should retain its own rate/policy.
4. Test USB-only first, hotspot-only, both interfaces, upstream changes, off/on,
   same-MAC reconnect, changed IP, and a different MAC reusing the previous IP.
5. Test saved policy updates/removal, reset, process recreation, reboot resume,
   Stop, and Restore. Confirm original native filters/root qdiscs are preserved.
6. Confirm IPv6 remains outside the measurement/cap. Do not treat the result as
   an all-protocol Internet cap.
7. Restore and reboot, then repeat original gateway, DNS, tethering-toggle, and
   reservation tests.

The monitoring log is separate from DHCP hook logs:

```bash
adb logcat -d -s WaguriTraffic:V '*:S'
```

Keep the original failure and cleanup failure together. An ownership mismatch
logs the specific interface/direction/priority and its kernel filter snapshot;
it can include local MAC/IP identifiers. A true foreign filter is still retained.
Do not delete Android's entire qdisc to bypass ownership checks.

The read-only tools/traffic-diagnostics.sh remains available for collecting
native state. Its output may include private network identifiers; it is not
included in this source package and is never collected automatically.

## Primary references

- Android BPF configuration and lifecycle:
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/src/com/android/networkstack/tethering/BpfCoordinator.java
- Android tethering offload:
  https://source.android.com/docs/core/data/tethering-offload
- Android tc action cookies:
  https://android.googlesource.com/platform/external/iproute2/+/refs/heads/android16-release/tc/m_action.c
- Android U32 Ethernet/IP matching:
  https://android.googlesource.com/platform/external/iproute2/+/refs/heads/android16-release/tc/f_u32.c
- Android police controls:
  https://android.googlesource.com/platform/external/iproute2/+/refs/heads/android16-release/tc/m_police.c
- Linux policing semantics:
  https://man7.org/linux/man-pages/man8/tc-police.8.html

- Kernel U32/action-cookie ABI:
  https://android.googlesource.com/kernel/common/+/refs/heads/android12-5.10/include/uapi/linux/pkt_cls.h
- Android netlink socket addresses:
  https://android.googlesource.com/platform/libcore/+/refs/heads/android16-release/luni/src/main/java/android/system/NetlinkSocketAddress.java

- Shared U32 classifier initialization and walking:
  https://android.googlesource.com/kernel/common/+/refs/heads/android12-5.10/net/sched/cls_u32.c
- Native IpServer cached-upstream comparison and forwarding rebuild:
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/src/android/net/ip/IpServer.java
- Native forwarding requester idempotence:
  https://android.googlesource.com/platform/system/netd/+/master/server/TetherController.cpp
