package uwu.waguri.hotspotmanager;

import android.content.Context;

/** Explicit software-mode preparation with backups and verified, conditional restoration. */
final class TrafficMode {
    static String root(String command) {
        RootShell.Result result = RootShell.run(command);
        if (result.exitCode != 0) throw new IllegalStateException("Root command failed: " + result.output.trim());
        return result.output.trim();
    }
    static String bootId() {
        String id = root("cat /proc/sys/kernel/random/boot_id");
        if (!id.matches("[0-9a-f-]{36}")) throw new IllegalStateException("Cannot verify kernel boot identity.");
        return id;
    }
    static void prepare(Context context) {
        TrafficStore store = new TrafficStore(context);
        if (store.prepared()) throw new IllegalStateException("Software mode is already prepared. Start monitoring after reboot, or restore native mode.");
        String hw = root("settings get global tether_offload_disabled");
        String bpf = root("device_config get connectivity override_tether_enable_bpf_offload");
        if (!(hw.equals("null") || hw.equals("0") || hw.equals("1"))
                || !(bpf.equals("null") || bpf.equals("true") || bpf.equals("false"))) {
            throw new IllegalStateException("Unexpected offload settings; no change was made.");
        }
        String boot = bootId();
        if (!store.prefs.edit().putString("before_hw", hw).putString("before_bpf", bpf)
                .putString("prepare_boot", boot).putBoolean("prepared", true).commit()) {
            throw new IllegalStateException("Could not save offload restoration data.");
        }
        root("settings put global tether_offload_disabled 1 && device_config put connectivity override_tether_enable_bpf_offload false");
        verifyFlags();
    }
    static void verifyFlags() {
        if (!root("settings get global tether_offload_disabled").equals("1")
                || !root("device_config get connectivity override_tether_enable_bpf_offload").equals("false")) {
            throw new IllegalStateException("Software offload settings are not retained. Monitoring is stopped.");
        }
    }
    static void verifyReady(Context context) {
        TrafficStore store = new TrafficStore(context);
        if (!store.prepared()) throw new IllegalStateException("Prepare Software QoS first.");
        // One root process per check avoids repeatedly starting su for individual flags.
        String snapshot = root("printf __HM_BOOT__; cat /proc/sys/kernel/random/boot_id; "
                + "printf __HM_HW__; settings get global tether_offload_disabled; "
                + "printf __HM_BPF__; device_config get connectivity override_tether_enable_bpf_offload; "
                + "echo __HM_DUMP__; dumpsys tethering | sed -n '1,190p'");
        TrafficRules.verifyReadySnapshot(snapshot, store.prefs.getString("prepare_boot", ""));
    }
    static void restore(Context context) {
        TrafficStore store = new TrafficStore(context);
        if (!store.prepared()) return;
        String hw = root("settings get global tether_offload_disabled");
        String bpf = root("device_config get connectivity override_tether_enable_bpf_offload");
        String oldHw = store.prefs.getString("before_hw", "null");
        String oldBpf = store.prefs.getString("before_bpf", "null");
        if ((!hw.equals("1") && !hw.equals(oldHw)) || (!bpf.equals("false") && !bpf.equals(oldBpf))) {
            throw new IllegalStateException("Offload settings changed outside the app. Recovery data is retained.");
        }
        if (!(oldHw.equals("null") || oldHw.equals("0") || oldHw.equals("1"))
                || !(oldBpf.equals("null") || oldBpf.equals("true") || oldBpf.equals("false"))) {
            throw new IllegalStateException("Invalid restoration data; no command was generated.");
        }
        String command = oldHw.equals("null") ? "settings delete global tether_offload_disabled" : "settings put global tether_offload_disabled " + oldHw;
        command += oldBpf.equals("null") ? " && device_config delete connectivity override_tether_enable_bpf_offload"
                : " && device_config put connectivity override_tether_enable_bpf_offload " + oldBpf;
        root(command);
        if (!root("settings get global tether_offload_disabled").equals(oldHw)
                || !root("device_config get connectivity override_tether_enable_bpf_offload").equals(oldBpf)) {
            throw new IllegalStateException("Restoration could not be verified. Recovery data is retained.");
        }
        if (!store.prefs.edit().remove("before_hw").remove("before_bpf").remove("prepare_boot")
                .putBoolean("prepared", false).putBoolean("wanted", false).commit()) {
            throw new IllegalStateException("Could not record the restored mode.");
        }
    }
    private TrafficMode() {}
}
