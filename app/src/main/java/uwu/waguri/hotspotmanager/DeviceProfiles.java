package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Durable profiles are keyed only by normalized MAC; temporary profiles last for this process. */
final class DeviceProfiles {
    private static final Map<String, DeviceProfile> TEMPORARY = new HashMap<>();
    private final SharedPreferences preferences;

    DeviceProfiles(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                "device_profiles_v1", Context.MODE_PRIVATE);
    }

    DeviceProfile get(String mac) {
        String key;
        try { key = DeviceProfile.normalizeMac(mac); }
        catch (IllegalArgumentException ignored) { return null; }
        if (TEMPORARY.containsKey(key)) return TEMPORARY.get(key);
        String raw = preferences.getString(key, null);
        if (raw == null) return null;
        try {
            JSONObject value = new JSONObject(raw);
            return new DeviceProfile(key, DeviceProfile.Type.fromStored(value.optString("type")),
                    value.optString("iface"), value.optString("ip"), true);
        } catch (Exception ignored) {
            return null;
        }
    }

    void save(DeviceProfile profile) throws Exception {
        if (profile.permanent) {
            JSONObject value = new JSONObject();
            value.put("version", 1);
            value.put("type", profile.type.name());
            value.put("iface", profile.iface);
            value.put("ip", profile.ip);
            preferences.edit().putString(profile.mac, value.toString()).apply();
            TEMPORARY.remove(profile.mac);
        } else {
            preferences.edit().remove(profile.mac).apply();
            TEMPORARY.put(profile.mac, profile);
        }
    }

    void remove(String mac) {
        String key = DeviceProfile.normalizeMac(mac);
        preferences.edit().remove(key).apply();
        TEMPORARY.remove(key);
    }

    List<DeviceProfile> saved() {
        List<DeviceProfile> result = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            try {
                DeviceProfile profile = get(key);
                if (profile != null && profile.permanent) result.add(profile);
            } catch (IllegalArgumentException ignored) { }
        }
        result.sort((left, right) -> left.mac.compareTo(right.mac));
        return result;
    }
}
