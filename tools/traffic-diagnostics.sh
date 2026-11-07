#!/system/bin/sh
# Read-only diagnostics for evaluating per-IP accounting and shaping support.
# This script does not change offload, qdiscs, firewall rules, or tethering state.

if [ "$(id -u)" != "0" ]; then
    echo "ERROR: Run this script through a root shell."
    exit 1
fi

section() { printf '\n[%s]\n' "$1"; }
section "Device and kernel"
getprop ro.product.model
getprop ro.build.version.release
getprop ro.build.version.sdk
uname -a

section "Available traffic tools"
for tool in tc ip iptables ip6tables bpftool; do
    command -v "$tool" || true
done
if command -v tc >/dev/null 2>&1; then tc -V 2>&1; fi

section "Configured offload flags (not proof of the active vendor traffic path)"
settings get global tether_offload_disabled
if command -v device_config >/dev/null 2>&1; then
    device_config get connectivity override_tether_enable_bpf_offload
    device_config get connectivity tether_enable_bpf_offload
fi

section "Downstream links and existing traffic-control attachments"
for iface in ap0 rndis0; do
    printf '\nInterface: %s\n' "$iface"
    ip -d link show dev "$iface" 2>&1
    ip -4 addr show dev "$iface" 2>&1
    ip -6 addr show dev "$iface" 2>&1
    if command -v tc >/dev/null 2>&1; then
        tc -s qdisc show dev "$iface" 2>&1
        tc -s filter show dev "$iface" ingress 2>&1
        tc -s filter show dev "$iface" egress 2>&1
    fi
done

section "Relevant kernel options, if exposed by the firmware"
if [ -r /proc/config.gz ] && command -v zcat >/dev/null 2>&1; then
    zcat /proc/config.gz | grep -E '^(# )?CONFIG_(NET_SCHED|NET_CLS_U32|NET_CLS_FLOWER|NET_CLS_ACT|NET_ACT_POLICE|NET_SCH_HTB|NET_SCH_INGRESS|IFB|BPF|BPF_SYSCALL|NETFILTER_XT_MATCH_(COMMENT|MAC)|IP_NF_IPTABLES|IP6_NF_IPTABLES)(=| )'
else
    echo "Kernel configuration is not exposed; unsupported features cannot be inferred from this absence."
fi

section "Current Android tethering/offload dump"
dumpsys tethering 2>&1

section "End"
echo "No configuration changes were made."
