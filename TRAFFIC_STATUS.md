# Per-IP speed and shaping: device verification required

## Status in 0.7.1

The Blocked devices section below Detected devices is implemented. It reads the
saved native Wi-Fi blocklist, including disconnected MACs and entries created in
Android Settings. Unblock removes only the selected MAC's native block and keeps
its profile and IP reservation. Failed reads are not presented as an empty list;
a previous snapshot is labeled as last known when refreshing fails.

Per-IP realtime Mbps display and speed limiting are not implemented or enabled
in this release. No new traffic path, counter, limit, qdisc, firewall rule, or
offload setting is installed. The current native tethering counters aggregate
upstream/interface traffic; dividing them among clients would not give each IP's
actual speed. A Wi-Fi link bitrate is also not a per-IP Internet throughput value.

Linux traffic-control shaping can be feasible on a rooted device, but it requires
verification of the actual firmware: available tc binary, kernel classifiers and
qdiscs, current attachments, and whether hardware/eBPF/vendor offload bypasses
the path being measured or shaped. Native HAL data limits are byte quotas per
upstream, not a per-client Mbps cap. Availability of root alone does not verify
these requirements.

The earlier requirement to preserve working tethering and skip unreliable
workarounds is retained. A software accounting/QoS mode must not claim a 1 Mbps
limit until the traffic path and enforcement have been tested on this device.

## Collect read-only diagnostics

The included `tools/traffic-diagnostics.sh` only reads system state. It does not
change tethering, offload, filters, rules, or qdiscs. Shell syntax was checked;
Android runtime execution still needs to be performed on the device.

With normal hotspot/USB tethering active, from the unpacked project directory:

```bash
adb push tools/traffic-diagnostics.sh /data/local/tmp/hm-traffic-diagnostics.sh
adb shell "su -c 'sh /data/local/tmp/hm-traffic-diagnostics.sh'" > traffic-diagnostics.txt
```

The shell must already have root authorization in Magisk. The output includes
device/kernel version, available tools, exposed kernel options, existing qdiscs
and filters, configured offload flags, and Android's current tethering dump.
Missing configuration files or unset flags are reported as unknown, not proof
of unsupported hardware. The dump contains local network identifiers; review it
before sharing.

Provide `traffic-diagnostics.txt` to continue evaluating per-IP accounting and
shaping. A later implementation still needs actual traffic tests, including
IPv4/IPv6, both directions, simultaneous clients, reconnects, IP reuse, and
restoration of pre-existing rules/state. No support decision is fabricated here.

## Relevant primary references

- Upstream-index keyed BPF stats and byte-limit maps:
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/bpf_progs/bpf_tethering.h
- Native BPF coordinator statistics:
  https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android16-release/Tethering/src/com/android/networkstack/tethering/BpfCoordinator.java
- Hardware offload requirements and accounting:
  https://source.android.com/docs/core/data/tethering-offload
- Linux HTB shaping:
  https://www.man7.org/linux/man-pages/man8/tc-htb.8.html
