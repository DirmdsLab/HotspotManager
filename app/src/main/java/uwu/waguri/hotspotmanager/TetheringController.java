package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Serializes GUI/ADB requests and returns verified state on the main thread. */
final class TetheringController {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    static final class Reply {
        final boolean ok;
        final TetheringControl.State state;
        final String error;

        Reply(boolean ok, TetheringControl.State state, String error) {
            this.ok = ok;
            this.state = state;
            this.error = error;
        }

        static Reply failure(String error) {
            return new Reply(false, null, error);
        }

        String json() {
            JSONObject json = new JSONObject();
            try {
                json.put("ok", ok);
                if (state != null) {
                    json.put("hotspot", state.hotspot);
                    json.put("usb", state.usb);
                    json.put("interfaces", new JSONArray(state.interfaces));
                }
                if (error != null) json.put("error", error);
            } catch (Exception ignored) { }
            return json.toString();
        }
    }

    static void execute(Context context, TetheringControl.Request request, Consumer<Reply> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (!RUNNING.compareAndSet(false, true)) {
            main.post(() -> callback.accept(Reply.failure("Another tethering operation is in progress.")));
            return;
        }
        // Capture the current APK path before leaving the app process; do not retain an Activity.
        String apk = context.getApplicationContext().getApplicationInfo().sourceDir;
        WORKER.execute(() -> {
            Reply reply;
            try {
                StringBuilder command = new StringBuilder("CLASSPATH=")
                        .append(RootShell.quote(apk))
                        .append(" /system/bin/app_process /system/bin ")
                        .append(TetheringCli.class.getName());
                for (String argument : request.arguments()) {
                    command.append(' ').append(RootShell.quote(argument));
                }
                reply = parse(RootShell.run(command.toString()));
            } catch (Exception e) {
                reply = Reply.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                RUNNING.set(false);
            }
            Reply completed = reply;
            main.post(() -> callback.accept(completed));
        });
    }

    private static Reply parse(RootShell.Result result) throws Exception {
        String[] lines = result.output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith(TetheringControl.RESULT_PREFIX)) continue;
            JSONObject json = new JSONObject(line.substring(TetheringControl.RESULT_PREFIX.length()));
            if (!json.optBoolean("ok", false)) {
                return Reply.failure(json.optString("error", "Tethering operation failed."));
            }
            if (result.exitCode != 0 || !(json.get("hotspot") instanceof Boolean)
                    || !(json.get("usb") instanceof Boolean)) {
                return Reply.failure("The root helper returned an invalid state.");
            }
            List<String> interfaces = new ArrayList<>();
            JSONArray values = json.optJSONArray("interfaces");
            if (values != null) {
                for (int j = 0; j < values.length(); j++) interfaces.add(values.getString(j));
            }
            return new Reply(true, new TetheringControl.State(
                    json.getBoolean("hotspot"), json.getBoolean("usb"), interfaces), null);
        }
        return Reply.failure(result.exitCode == 124
                ? "Root command timed out. Check Magisk authorization and the USB connection."
                : "Root control is unavailable. Grant Hotspot Manager root access in Magisk.");
    }

    private TetheringController() {}
}
