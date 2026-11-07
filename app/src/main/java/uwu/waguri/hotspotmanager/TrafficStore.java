package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Private per-MAC rate policies and recovery metadata; independent of DHCP reservations. */
final class TrafficStore {
    final SharedPreferences prefs;
    TrafficStore(Context context) { prefs = context.getApplicationContext().getSharedPreferences("traffic_control_v1", Context.MODE_PRIVATE); }
    TrafficStore(SharedPreferences prefs) { this.prefs = prefs; }
    boolean prepared() { return prefs.getBoolean("prepared", false); }
    boolean wanted() { return prefs.getBoolean("wanted", false); }
    void wanted(boolean value) { prefs.edit().putBoolean("wanted", value).apply(); }
    int[] limits(String mac) {
        try {
            JSONObject value = new JSONObject(prefs.getString("rate_" + DeviceProfile.normalizeMac(mac), "{}"));
            return new int[] {value.optInt("down"), value.optInt("up")};
        } catch (Exception error) { return new int[] {0, 0}; }
    }
    void limits(String mac, int down, int up) throws Exception {
        mac = DeviceProfile.normalizeMac(mac);
        for (int rate : new int[] {down, up}) if (rate != 0 && (rate < 100 || rate > 500000)) throw new IllegalArgumentException("Invalid traffic rate.");
        JSONObject json = new JSONObject(); json.put("down", down); json.put("up", up);
        if (!prefs.edit().putString("rate_" + mac, json.toString()).commit()) {
            throw new IllegalStateException("Could not save the rate policy.");
        }
    }
    Map<String, String> owned() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ?> value : prefs.getAll().entrySet()) {
            if (value.getKey().startsWith("owned_") && value.getValue() instanceof String) result.put(value.getKey(), (String) value.getValue());
        }
        return result;
    }
    void owned(String key, String value) {
        SharedPreferences.Editor editor = prefs.edit();
        if (value == null) editor.remove(key); else editor.putString(key, value);
        if (!editor.commit()) throw new IllegalStateException("Could not persist traffic rule ownership.");
    }

    TrafficUsage usage(String identity) {
        try {
            JSONObject value = new JSONObject(prefs.getString("usage_" + identity, "{}"));
            return new TrafficUsage(value.optLong("down"), value.optLong("up"));
        } catch (Exception error) { throw new IllegalStateException("Saved usage is invalid.", error); }
    }

    private static String sessionKey(String iface, String mac) {
        if (!"ap0".equals(iface) && !"rndis0".equals(iface)) throw new IllegalArgumentException("Unsupported interface.");
        return "session_" + iface + "|" + DeviceProfile.normalizeMac(mac);
    }

    void refreshSessions(TrafficSessionSnapshot snapshot, Collection<TrafficRules.Client> clients) throws Exception {
        Map<String, String> tokens = new HashMap<>();
        Set<String> identities = new HashSet<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("session_")) identities.add(key.substring("session_".length()));
        }
        for (TrafficRules.Client client : clients) identities.add(client.iface + "|" + client.mac);
        for (String identity : identities) {
            String[] parts = identity.split("\\|", -1);
            if (parts.length != 2 || !snapshot.known(parts[0])) continue;
            tokens.put(sessionKey(parts[0], parts[1]), snapshot.token(parts[0], parts[1]));
        }
        refreshSessionTokens(tokens);
    }

    void refreshSessionTokens(Map<String, String> tokens) throws Exception {
        synchronized (TrafficStore.class) {
            SharedPreferences.Editor editor = prefs.edit();
            boolean changed = false;
            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                String raw = prefs.getString(entry.getKey(), null);
                String token = entry.getValue();
                if (token == null) {
                    if (raw != null) { editor.remove(entry.getKey()); changed = true; }
                } else if (!token.equals(new JSONObject(raw == null ? "{}" : raw).optString("token"))) {
                    editor.putString(entry.getKey(), new JSONObject().put("token", token)
                            .put("down", 0L).put("up", 0L).toString());
                    changed = true;
                }
            }
            if (changed && !editor.commit()) throw new IllegalStateException("Could not checkpoint connection sessions.");
        }
    }

    TrafficUsage sessionUsage(String iface, String mac, TrafficSessionSnapshot snapshot) throws Exception {
        String token = snapshot.token(iface, mac);
        if ("".equals(token)) return null;
        JSONObject session = new JSONObject(prefs.getString(sessionKey(iface, mac), "{}"));
        if (token == null || !token.equals(session.optString("token"))) return new TrafficUsage(0, 0);
        return new TrafficUsage(session.optLong("down"), session.optLong("up"));
    }

    void account(String key, long bytes) throws Exception {
        synchronized (TrafficStore.class) {
            JSONObject record = new JSONObject(prefs.getString(key, "{}"));
            long before = record.optLong("accounted", 0L);
            if (before == bytes) return;
            String mac = DeviceProfile.normalizeMac(record.getString("mac"));
            TrafficRules.Client client = new TrafficRules.Client(record.getString("iface"), record.getString("ip"), mac);
            boolean upload = record.getString("direction").equals("ingress");
            long delta = TrafficUsage.delta(before, bytes);
            SharedPreferences.Editor editor = prefs.edit().putString(key, record.put("accounted", bytes).toString());
            TrafficUsage total = usage(mac).add(upload, delta);
            editor.putString("usage_" + mac, new JSONObject().put("down", total.down).put("up", total.up).toString());
            String sessionKey = sessionKey(client.iface, mac);
            JSONObject session = new JSONObject(prefs.getString(sessionKey, "{}"));
            String token = record.optString("session");
            // Recovered rules and pre-reconnect counters must never enter a new session.
            if (!token.isEmpty() && token.equals(session.optString("token"))) {
                TrafficUsage next = new TrafficUsage(session.optLong("down"), session.optLong("up")).add(upload, delta);
                editor.putString(sessionKey, session.put("down", next.down).put("up", next.up).toString());
            }
            // Totals and their kernel cursor are committed together to avoid double counting on restart.
            if (!editor.commit()) throw new IllegalStateException("Could not checkpoint traffic usage.");
        }
    }

    void resetUsage(String mac) throws Exception {
        mac = DeviceProfile.normalizeMac(mac);
        synchronized (TrafficStore.class) {
            SharedPreferences.Editor editor = prefs.edit().remove("usage_" + mac);
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("usage_") && key.contains("|" + mac + "|")) editor.remove(key);
                if (key.startsWith("session_") && key.endsWith("|" + mac)) {
                    JSONObject session = new JSONObject(prefs.getString(key, "{}"));
                    editor.putString(key, session.put("down", 0L).put("up", 0L).toString());
                }
            }
            for (Map.Entry<String, String> entry : owned().entrySet()) {
                if (entry.getKey().startsWith("owned_qdisc_")) continue;
                JSONObject record = new JSONObject(entry.getValue());
                if (mac.equals(record.optString("mac"))) editor.putString(entry.getKey(), record.put("accounted", -1L).toString());
            }
            if (!editor.commit()) throw new IllegalStateException("Could not reset recorded usage.");
        }
    }
    private TrafficStore() { throw new AssertionError(); }
}
