package uwu.waguri.hotspotmanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.graphics.drawable.Icon;
import java.util.Collections;
import java.util.Map;

/** User-controlled foreground worker for IPv4 accounting and persistent per-MAC policies. */
public final class TrafficService extends Service {
    static final String STOP = "stop_monitor";
    static final String RESTORE = "restore_native";
    static final String CHANNEL = "traffic_control";
    static volatile boolean running;
    static volatile String status = "Monitoring stopped";
    static volatile String usageWarning = "";
    static volatile long sampleTime;
    static volatile Map<String, TrafficEngine.Rate> rates = Collections.emptyMap();
    private volatile boolean stopping;
    private volatile boolean restoring;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        TrafficStore store = new TrafficStore(this);
        if (intent == null && !store.wanted()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (STOP.equals(action) || RESTORE.equals(action)) {
            store.wanted(false);
            restoring |= RESTORE.equals(action);
            stopping = true;
        } else if (stopping) {
            status = "Cleanup is in progress. Start monitoring again after it finishes.";
            return START_NOT_STICKY;
        } else store.wanted(true);
        if (worker == null) {
            running = true;
            try { foreground(); }
            catch (RuntimeException error) {
                running = false;
                store.wanted(false);
                status = "Cannot start foreground monitoring: " + error.getMessage();
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            worker = new Thread(this::monitor, "HotspotTrafficMonitor");
            worker.start();
        }
        return stopping ? START_NOT_STICKY : START_STICKY;
    }

    private void foreground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "IPv4 traffic control", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, TrafficService.class).setAction(STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Hotspot IPv4 traffic control")
                .setContentText("Software-mode monitoring and saved rate policies").setContentIntent(content)
                .setOngoing(true).addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop monitoring", stopIntent).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(70, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(70, notification);
    }

    private void monitor() {
        TrafficEngine engine = null;
        running = true;
        boolean failed = false;
        try {
            if (!stopping) {
                status = "Checking software mode...";
                engine = new TrafficEngine(this);
            }
            while (!stopping) {
                rates = engine.tick();
                sampleTime = SystemClock.elapsedRealtime();
                status = "Software IPv4 monitoring active";
                Thread.sleep(2000L);
            }
        } catch (Exception error) {
            failed = true;
            status = "Traffic control stopped: " + error.getMessage();
            android.util.Log.e("WaguriTraffic", status, error);
            new TrafficStore(this).wanted(false);
        } finally {
            rates = Collections.emptyMap(); sampleTime = 0L;
            try {
                if (engine != null) engine.close();
                else if (!new TrafficStore(this).owned().isEmpty()) TrafficEngine.cleanup(this);
                if (restoring) {
                    TrafficMode.restore(this);
                    status = "Original offload settings restored. Reboot to reload native acceleration.";
                } else if (!failed) status = "Monitoring stopped; owned filters removed. Saved limits are inactive.";
            } catch (Exception error) {
                status += "\nCleanup/restoration incomplete: " + error.getMessage();
                android.util.Log.e("WaguriTraffic", status, error);
            }
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        super.onDestroy();
    }
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
