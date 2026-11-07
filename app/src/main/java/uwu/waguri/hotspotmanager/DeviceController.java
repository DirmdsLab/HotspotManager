package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Runs only validated per-device native operations; callbacks return to the UI thread. */
final class DeviceController {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    static final class Reply {
        final JSONObject data;
        final String error;
        Reply(JSONObject data, String error) { this.data = data; this.error = error; }
        boolean ok() { return data != null; }
    }

    static void execute(Context context, String action, String mac, Consumer<Reply> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        String target;
        try {
            target = DeviceProfile.normalizeMac(mac);
            if (!("inspect".equals(action) || "block".equals(action) || "unblock".equals(action))) {
                throw new IllegalArgumentException("Unsupported device operation.");
            }
        } catch (Exception e) {
            main.post(() -> callback.accept(new Reply(null, e.getMessage())));
            return;
        }
        submit(context, action, target, callback);
    }

    static void loadBlocked(Context context, Consumer<Reply> callback) {
        submit(context, "blocked", null, callback);
    }

    private static void submit(Context context, String action, String target, Consumer<Reply> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!RUNNING.compareAndSet(false, true)) {
            main.post(() -> callback.accept(new Reply(null, "Another device operation is in progress.")));
            return;
        }
        String apk = context.getApplicationContext().getApplicationInfo().sourceDir;
        WORKER.execute(() -> {
            Reply reply;
            try {
                RootShell.Result result = RootShell.run("CLASSPATH=" + RootShell.quote(apk)
                        + " /system/bin/app_process /system/bin " + DeviceCli.class.getName()
                        + " " + RootShell.quote(action)
                        + (target == null ? "" : " " + RootShell.quote(target)));
                reply = parse(result, target);
            } catch (Exception e) {
                reply = new Reply(null, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                RUNNING.set(false);
            }
            Reply completed = reply;
            main.post(() -> callback.accept(completed));
        });
    }

    private static Reply parse(RootShell.Result result, String mac) throws Exception {
        String[] lines = result.output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith(DeviceCli.PREFIX)) continue;
            JSONObject value = new JSONObject(line.substring(DeviceCli.PREFIX.length()));
            if (!value.optBoolean("ok", false)) {
                return new Reply(null, value.optString("error", "The device operation failed."));
            }
            if (result.exitCode != 0 || (mac != null && !mac.equals(value.optString("mac")))) {
                return new Reply(null, "The root helper returned a mismatched device result.");
            }
            if (mac == null) {
                JSONArray blocked = value.getJSONArray("blockedMacs");
                for (int j = 0; j < blocked.length(); j++) DeviceProfile.normalizeMac(blocked.getString(j));
            }
            return new Reply(value, null);
        }
        return new Reply(null, result.exitCode == 124 ? "Device operation timed out."
                : "Device control is unavailable. Check Magisk root permission.");
    }

    private DeviceController() {}
}
