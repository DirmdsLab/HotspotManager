package uwu.waguri.hotspotmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONObject;

/** The manifest admits only senders holding Android's native tethering permission. */
public final class TrafficSessionReceiver extends BroadcastReceiver {
    static final String ACTION = "uwu.waguri.hotspotmanager.NATIVE_TRAFFIC_SESSIONS";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        try {
            String raw = intent.getStringExtra("snapshot");
            if (raw == null || raw.length() > 65536) throw new IllegalArgumentException("Invalid session snapshot size.");
            JSONObject incoming = new JSONObject(raw);
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            synchronized (TrafficStore.class) {
                TrafficStore store = new TrafficStore(context);
                JSONObject previous = new JSONObject(store.prefs.getString(TrafficSessionSnapshot.KEY, "{}"));
                if (!TrafficSessionSnapshot.newerThan(incoming, previous, boot)) return;
                if (!store.prefs.edit().putString(TrafficSessionSnapshot.KEY, raw).commit()) {
                    throw new IllegalStateException("Could not save native connection sessions.");
                }
            }
        } catch (Exception error) { Log.w("WaguriTraffic", "Native session snapshot rejected", error); }
    }
}
