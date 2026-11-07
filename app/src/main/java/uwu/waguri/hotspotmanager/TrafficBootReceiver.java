package uwu.waguri.hotspotmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

/** Restarts only the software monitor explicitly enabled by the user. */
public final class TrafficBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        TrafficStore store = new TrafficStore(context);
        if (store.prepared() && store.wanted()) {
            try { ContextCompat.startForegroundService(context, new Intent(context, TrafficService.class)); }
            catch (Exception error) { TrafficService.status = "Open the app to resume traffic monitoring: " + error.getMessage(); }
        }
    }
}
