package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;

/** Native session snapshots delivered to a permission-protected app receiver. */
final class TrafficSessionSnapshot {
    static final String KEY = "native_session_snapshot_v1";
    private final JSONObject value;

    private TrafficSessionSnapshot(JSONObject value) { this.value = value; }

    static TrafficSessionSnapshot read(Context context) {
        try {
            return parse(new TrafficStore(context).prefs.getString(KEY, null),
                    Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
        } catch (Exception error) { return new TrafficSessionSnapshot(new JSONObject()); }
    }

    static TrafficSessionSnapshot parse(String raw, int boot) {
        try {
            JSONObject value = new JSONObject(raw);
            if (boot < 0 || value.getInt("boot") != boot || value.getInt("version") != 1) {
                return new TrafficSessionSnapshot(new JSONObject());
            }
            return new TrafficSessionSnapshot(value);
        } catch (Exception error) { return new TrafficSessionSnapshot(new JSONObject()); }
    }

    boolean known(String iface) {
        return value.optBoolean("ap0".equals(iface) ? "wifiKnown" : "usbKnown");
    }

    // Null means disconnected; an empty string means native session events are unavailable.
    String token(String iface, String mac) {
        if (!known(iface)) return "";
        JSONObject wifi = value.optJSONObject("wifi");
        String token = "ap0".equals(iface) ? wifi == null ? "" : wifi.optString(DeviceProfile.normalizeMac(mac))
                : value.optString("usb");
        return token.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") ? token : null;
    }

    static boolean newerThan(JSONObject incoming, JSONObject previous, int boot) throws Exception {
        if (boot < 0 || incoming.getInt("boot") != boot || incoming.getInt("version") != 1) return false;
        long time = incoming.getLong("publishedAt");
        return time >= 0 && (previous.optInt("boot", -1) != boot || previous.optLong("publishedAt", -1) < time);
    }

    static void publish(Context context, TrafficSessionState state) throws Exception {
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) throw new IllegalStateException("Android boot count is unavailable.");
        JSONObject value = new JSONObject().put("version", 1).put("boot", boot)
                .put("publishedAt", SystemClock.elapsedRealtimeNanos())
                .put("wifiKnown", state.wifiKnown()).put("wifi", new JSONObject(state.wifiTokens()))
                .put("usbKnown", state.usbKnown()).put("usb", state.usbToken() == null ? "" : state.usbToken());
        Intent intent = new Intent(TrafficSessionReceiver.ACTION)
                .setClassName("uwu.waguri.hotspotmanager", TrafficSessionReceiver.class.getName())
                .putExtra("snapshot", value.toString());
        context.sendBroadcast(intent);
    }

    private TrafficSessionSnapshot() { throw new AssertionError(); }
}
