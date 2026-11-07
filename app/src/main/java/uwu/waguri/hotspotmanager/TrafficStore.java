package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Private per-MAC rate policies and recovery metadata; independent of DHCP reservations. */
final class TrafficStore {
    final SharedPreferences prefs;
    TrafficStore(Context context) { prefs = context.getApplicationContext().getSharedPreferences("traffic_control_v1", Context.MODE_PRIVATE); }
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
    private TrafficStore() { throw new AssertionError(); }
}
