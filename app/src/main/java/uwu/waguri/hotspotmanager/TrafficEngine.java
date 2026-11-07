package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns only app-recorded tc filters. Native root qdiscs and other filter priorities are retained. */
final class TrafficEngine {
    static final class Rate {
        final double down;
        final double up;
        final int downLimit;
        final int upLimit;
        Rate(double down, double up, int downLimit, int upLimit) {
            this.down = down; this.up = up; this.downLimit = downLimit; this.upLimit = upLimit;
        }
    }
    private static final class Binding {
        final TrafficRules.Client client;
        final int pref;
        final int down;
        final int up;
        final String session;
        String ifindex;
        long lastDown = -1;
        long lastUp = -1;
        long lastTime;
        Binding(TrafficRules.Client client, int pref, int down, int up, String session) {
            this.client = client; this.pref = pref; this.down = down; this.up = up; this.session = session;
        }
    }
    private final Context context;
    private final TrafficStore store;
    private final String boot;
    private final Map<String, Binding> bindings = new HashMap<>();
    private Map<String, String> batch = Collections.emptyMap();

    TrafficEngine(Context context) throws Exception {
        this(context, true);
    }

    private TrafficEngine(Context context, boolean requireReady) throws Exception {
        this.context = context.getApplicationContext();
        store = new TrafficStore(context);
        if (requireReady) TrafficMode.verifyReady(context);
        boot = TrafficMode.bootId();
        batch = readBatch();
        cleanupRecorded();
    }

    static void cleanup(Context context) throws Exception {
        new TrafficEngine(context, false).close();
    }

    Map<String, Rate> tick() throws Exception {
        TrafficMode.verifyReady(context);
        batch = readBatch();
        TrafficSessionSnapshot sessions = TrafficSessionSnapshot.read(context);
        Map<String, TrafficRules.Client> clients = new LinkedHashMap<>();
        for (String iface : TrafficRules.IFACES) {
            for (TrafficRules.Client client : TrafficRules.neighbors(iface, part(iface + "_neighbors"))) {
                // STALE ARP entries can outlive a physical disconnect.
                if (sessions.token(iface, client.mac) != null) clients.put(client.key(), client);
            }
        }
        store.refreshSessions(sessions, clients.values());
        // Checkpoint old rules before a policy change or connection generation replaces them.
        for (Binding binding : bindings.values()) {
            if (part(binding.client.iface + "_index").trim().equals(binding.ifindex)) capture(binding);
        }
        for (Binding binding : new ArrayList<>(bindings.values())) {
            TrafficRules.Client client = clients.get(binding.client.key());
            int[] limits = store.limits(binding.client.mac);
            if (client == null || !part(binding.client.iface + "_index").trim().equals(binding.ifindex)
                    || limits[0] != binding.down || limits[1] != binding.up
                    || !binding.session.equals(sessions.token(binding.client.iface, binding.client.mac))) {
                remove(binding);
                bindings.remove(binding.client.key());
            }
        }
        Set<Integer> used = new HashSet<>();
        for (Binding binding : bindings.values()) { used.add(binding.pref); used.add(binding.pref + 1); }
        boolean changed = false;
        for (TrafficRules.Client client : clients.values()) {
            if (bindings.containsKey(client.key())) continue;
            int slot = -1;
            for (int pref = TrafficRules.FIRST_PREF; pref < TrafficRules.LAST_PREF; pref += 2) {
                if (!used.contains(pref) && !used.contains(pref + 1)
                        && TrafficRules.selectedFilter(part(client.iface + "_ingress"), pref).trim().isEmpty()
                        && TrafficRules.selectedFilter(part(client.iface + "_egress"), pref).trim().isEmpty()
                        && TrafficRules.selectedFilter(part(client.iface + "_ingress"), pref + 1).trim().isEmpty()
                        && TrafficRules.selectedFilter(part(client.iface + "_egress"), pref + 1).trim().isEmpty()) {
                    slot = pref; break;
                }
            }
            if (slot < 0) throw new IllegalStateException("No unoccupied app filter priority is available.");
            int[] limits = store.limits(client.mac);
            Binding binding = new Binding(client, slot, limits[0], limits[1], sessions.token(client.iface, client.mac));
            install(binding);
            bindings.put(client.key(), binding);
            used.add(slot);
            used.add(slot + 1);
            changed = true;
        }
        if (changed) batch = readBatch();
        long now = SystemClock.elapsedRealtime();
        Map<String, Rate> rates = new HashMap<>();
        for (Binding binding : bindings.values()) {
            int downPref = TrafficRules.directionPref(binding.pref, "egress");
            long up = TrafficRules.acceptedBytes(verifyOwned(binding.client.iface, "ingress", binding.pref));
            long down = TrafficRules.acceptedBytes(verifyOwned(binding.client.iface, "egress", downPref));
            store.account(key(binding.client.iface, "ingress", binding.pref), up);
            store.account(key(binding.client.iface, "egress", downPref), down);
            double downMbps = TrafficRules.mbps(binding.lastDown, down, now - binding.lastTime);
            double upMbps = TrafficRules.mbps(binding.lastUp, up, now - binding.lastTime);
            rates.put(binding.client.key(), new Rate(downMbps, upMbps, binding.down, binding.up));
            binding.lastDown = down; binding.lastUp = up; binding.lastTime = now;
        }
        return Collections.unmodifiableMap(rates);
    }

    void close() throws Exception {
        batch = readBatch();
        cleanupRecorded();
        bindings.clear();
    }

    private void install(Binding binding) throws Exception {
        String iface = binding.client.iface;
        String ifindex = part(iface + "_index").trim();
        binding.ifindex = ifindex;
        if (!ifindex.matches("[0-9]+")) throw new IllegalStateException("Downstream disappeared before filter installation.");
        String qdiscs = TrafficMode.root("tc qdisc show dev " + iface);
        if (!qdiscs.contains("qdisc clsact")) {
            mutate(iface, ifindex, "tc qdisc add dev " + iface + " clsact");
            store.owned("owned_qdisc_" + iface, new JSONObject().put("boot", boot).put("ifindex", ifindex).toString());
        }
        for (String direction : new String[] {"ingress", "egress"}) {
            int pref = TrafficRules.directionPref(binding.pref, direction);
            String existing = readFilter(iface, direction, pref);
            if (!existing.trim().isEmpty()) throw new IllegalStateException("Conflicting tc filter; existing rule was not replaced.");
            String key = key(iface, direction, pref);
            String cookie = java.util.UUID.randomUUID().toString().replace("-", "");
            JSONObject record = new JSONObject().put("boot", boot).put("ifindex", ifindex)
                    .put("iface", iface).put("direction", direction).put("pref", pref)
                    .put("ip", binding.client.ip).put("mac", binding.client.mac).put("session", binding.session).put("cookie", cookie).put("pending", true);
            store.owned(key, record.toString());
            // Prove cookie/counter support with a non-dropping rule before applying any cap.
            mutate(iface, ifindex, TrafficRules.filter(binding.client, direction, pref, 0, cookie));
            String installed = TrafficRules.ownedLeaf(readFilter(iface, direction, pref), cookie);
            if (!TrafficRules.hasCookie(installed, cookie)) {
                throw new IllegalStateException("tc ownership cookies are not readable. No speed cap was requested.");
            }
            TrafficRules.acceptedBytes(installed);
            record.put("pending", false).put("fingerprint", TrafficRules.fingerprint(installed));
            store.owned(key, record.toString());
            int rate = direction.equals("ingress") ? binding.up : binding.down;
            if (rate > 0) {
                String changed = TrafficRules.filter(binding.client, direction, pref, rate, cookie)
                        .replace("tc filter add ", "tc filter replace ")
                        .replace(" u32 match ", " handle " + TrafficRules.leafHandle(installed) + " u32 match ");
                mutate(iface, ifindex, changed);
                installed = TrafficRules.ownedLeaf(readFilter(iface, direction, pref), cookie);
                if (!TrafficRules.hasCookie(installed, cookie)) throw new IllegalStateException("Updated filter ownership was lost.");
                TrafficRules.acceptedBytes(installed);
                record.put("fingerprint", TrafficRules.fingerprint(installed));
                store.owned(key, record.toString());
            }
        }
    }

    private void remove(Binding binding) throws Exception {
        for (String direction : new String[] {"ingress", "egress"}) removeRecorded(key(binding.client.iface, direction,
                TrafficRules.directionPref(binding.pref, direction)));
    }

    private void capture(Binding binding) throws Exception {
        for (String direction : new String[] {"ingress", "egress"}) {
            int pref = TrafficRules.directionPref(binding.pref, direction);
            store.account(key(binding.client.iface, direction, pref), TrafficRules.acceptedBytes(
                    verifyOwned(binding.client.iface, direction, pref)));
        }
    }

    private String verifyOwned(String iface, String direction, int pref) throws Exception {
        String raw = store.prefs.getString(key(iface, direction, pref), null);
        if (raw == null) throw new IllegalStateException("Traffic rule ownership was lost.");
        JSONObject record = new JSONObject(raw);
        String leaf = TrafficRules.ownedLeaf(TrafficRules.selectedFilter(part(iface + "_" + direction), pref), record.getString("cookie"));
        String actual = TrafficRules.fingerprint(leaf);
        if (!actual.equals(record.optString("fingerprint")) || actual.isEmpty()) {
            throw new IllegalStateException("A traffic filter changed outside the app; measurements are unavailable.");
        }
        return leaf;
    }

    private void cleanupRecorded() throws Exception {
        Exception failure = null;
        for (String key : new ArrayList<>(store.owned().keySet())) {
            if (key.startsWith("owned_qdisc_")) continue;
            try { removeRecorded(key); } catch (Exception error) { failure = error; }
        }
        for (String key : new ArrayList<>(store.owned().keySet())) {
            if (!key.startsWith("owned_qdisc_")) continue;
            try {
                JSONObject record = new JSONObject(store.prefs.getString(key, "{}"));
                String iface = key.substring("owned_qdisc_".length());
                if (!boot.equals(record.optString("boot")) || !part(iface + "_index").trim().equals(record.optString("ifindex"))) {
                    store.owned(key, null); continue;
                }
                String ingress = TrafficMode.root("tc filter show dev " + iface + " ingress");
                String egress = TrafficMode.root("tc filter show dev " + iface + " egress");
                if (ingress.trim().isEmpty() && egress.trim().isEmpty()) {
                    mutate(iface, record.getString("ifindex"), "tc qdisc del dev " + iface + " clsact");
                    store.owned(key, null);
                }
                // Do not delete a clsact now shared with another component.
            } catch (Exception error) { failure = error; }
        }
        if (failure != null) throw failure;
    }

    private void removeRecorded(String key) throws Exception {
        String raw = store.prefs.getString(key, null);
        if (raw == null) return;
        JSONObject record = new JSONObject(raw);
        String iface = record.getString("iface");
        String direction = record.getString("direction");
        int pref = record.getInt("pref");
        if (!boot.equals(record.optString("boot")) || !part(iface + "_index").trim().equals(record.optString("ifindex"))) {
            store.owned(key, null); return;
        }
        String actual = readFilter(iface, direction, pref);
        if (actual.trim().isEmpty()) { store.owned(key, null); return; }
        String cookie = record.optString("cookie");
        String leaf;
        try { leaf = TrafficRules.ownedLeaf(actual, cookie); }
        catch (IllegalStateException error) {
            android.util.Log.e("WaguriTraffic", "Ownership check: " + iface + " " + direction + " pref=" + pref
                    + " expectedCookie=" + cookie + " kernel=" + actual);
            throw new IllegalStateException("Kernel ownership mismatch on " + iface + " " + direction + " pref " + pref + "; existing filter retained. See WaguriTraffic log.");
        }
        try { store.account(key, TrafficRules.acceptedBytes(leaf)); }
        catch (Exception error) {
            TrafficService.usageWarning = "Usage checkpoint failed: " + error.getMessage();
            android.util.Log.e("WaguriTraffic", TrafficService.usageWarning, error);
        }
        // Legacy u32 dumps can contain both directions' leaves under the same priority.
        String deletion = TrafficRules.ownsPreference(actual, cookie) ? TrafficRules.delete(iface, direction, pref)
                : TrafficRules.deleteLeaf(iface, direction, pref, leaf);
        mutate(iface, record.getString("ifindex"), deletion);
        store.owned(key, null);
    }

    private static String key(String iface, String direction, int pref) { return "owned_" + iface + "_" + direction + "_" + pref; }
    private String part(String name) { return batch.getOrDefault(name, ""); }

    private static void mutate(String iface, String ifindex, String command) {
        TrafficRules.show(iface, "ingress", TrafficRules.FIRST_PREF);
        if (!ifindex.matches("[0-9]+")) throw new IllegalArgumentException("Invalid interface identity.");
        TrafficMode.root("if [ \"$(cat /sys/class/net/" + iface + "/ifindex 2>/dev/null)\" = " + ifindex
                + " ]; then " + command + "; else echo 'Downstream changed before traffic update; retry monitoring.'; exit 1; fi");
    }

    private String readFilter(String iface, String direction, int pref) throws Exception {
        TrafficRules.show(iface, direction, pref);
        return TrafficRules.selectedFilter(query(" " + RootShell.quote(iface) + " " + RootShell.quote(direction)).getString("filters"), pref);
    }

    private JSONObject query(String args) throws Exception {
        String apk = context.getApplicationInfo().sourceDir;
        RootShell.Result result = RootShell.run("CLASSPATH=" + RootShell.quote(apk)
                + " /system/bin/app_process /system/bin " + TrafficCli.class.getName() + args, 1048576);
        for (String line : result.output.split("\\r?\\n")) {
            if (!line.startsWith(TrafficCli.PREFIX)) continue;
            JSONObject data = new JSONObject(line.substring(TrafficCli.PREFIX.length()));
            if (result.exitCode != 0 || !data.optBoolean("ok")) throw new IllegalStateException(data.optString("error", "Kernel snapshot failed."));
            return data;
        }
        throw new IllegalStateException("Kernel snapshot helper failed (exit " + result.exitCode + "): " + result.output.trim());
    }

    private Map<String, String> readBatch() throws Exception {
        JSONObject data = query("");
        Map<String, String> values = new HashMap<>();
        for (String iface : TrafficRules.IFACES) for (String suffix : new String[] {"_index", "_neighbors", "_ingress", "_egress"})
            values.put(iface + suffix, data.getString(iface + suffix));
        return values;
    }
}
