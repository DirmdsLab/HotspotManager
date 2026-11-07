package uwu.waguri.hotspotmanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, DUMP-permission-protected ADB entry point; no arbitrary shell input is accepted. */
public final class TetheringReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        TetheringControl.Request request;
        try {
            String target = intent.getStringExtra("target");
            String action = intent.getStringExtra("action");
            if (action == null) action = "status";
            request = target == null
                    ? TetheringControl.Request.parse(action)
                    : TetheringControl.Request.parse(target, action);
        } catch (Exception e) {
            setResultCode(1);
            setResultData(TetheringController.Reply.failure(e.getMessage()).json());
            return;
        }
        PendingResult pending = goAsync();
        TetheringController.execute(context, request, reply -> {
            try {
                pending.setResultCode(reply.ok ? 0 : 1);
                pending.setResultData(reply.json());
            } finally {
                pending.finish();
            }
        });
    }
}
