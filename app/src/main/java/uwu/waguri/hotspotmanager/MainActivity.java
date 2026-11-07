package uwu.waguri.hotspotmanager;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Switch;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "WaguriHotspotUI";
    private static final String CONFIG_KEY = "waguri_hotspot_reservations_v1";
    private static final String WIFI_IFACE = "ap0";
    private static final String USB_IFACE = "rndis0";

    // Dark Android 16 / Material You-inspired palette.
    private static final int BG = 0xFF0B1017;
    private static final int SURFACE = 0xFF121A24;
    private static final int SURFACE_HIGH = 0xFF1A2634;
    private static final int OUTLINE = 0xFF2A394B;
    private static final int TEXT = 0xFFE8EEF6;
    private static final int MUTED = 0xFF9AAABD;
    private static final int ACCENT = 0xFFB8C8FF;
    private static final int ACCENT_TEXT = 0xFF18243A;
    private static final int TEAL = 0xFF80D8C5;
    private static final int RED = 0xFFFFB4AB;

    private final List<Reservation> reservations = new ArrayList<>();
    private final List<Client> clients = new ArrayList<>();

    private LinearLayout page;
    private TextView status;
    private Button devicesTab;
    private Button manualTab;
    private Switch usbToggle;
    private Switch hotspotToggle;
    private TextView usbControlStatus;
    private TextView hotspotControlStatus;
    private boolean syncingToggles;
    private boolean controlBusy;
    private boolean tetherRefreshPending;
    private boolean resumed;
    private boolean tetherReceiverRegistered;
    private TetheringControl.Target changingTarget;
    private boolean changingEnabled;
    private TetheringControl.State tetheringState;
    private final Handler controlHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTetheringTask = this::refreshTetheringState;
    private final BroadcastReceiver tetherStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.net.conn.TETHER_STATE_CHANGED".equals(intent.getAction())) {
                scheduleTetheringRefresh();
            }
        }
    };
    private int activeTab = 0;
    private volatile boolean busy = false;

    private static final class Reservation {
        final String iface;
        final String mac;
        final String ip;
        Reservation(String iface, String mac, String ip) {
            this.iface = iface;
            this.mac = mac;
            this.ip = ip;
        }
    }

    private static final class Client {
        final String iface;
        final String ip;
        final String mac;
        final String state;
        Client(String iface, String ip, String mac, String state) {
            this.iface = iface;
            this.ip = ip;
            this.mac = mac;
            this.state = state;
        }
        String label() {
            return (WIFI_IFACE.equals(iface) ? "Wi-Fi · ap0" : "USB · rndis0")
                    + "  /  " + ip + "  /  " + mac;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            // System bar colors and icon appearance are defined by AppTheme.
            // A new Activity starts with system bars visible; no legacy UI flags are needed.
            buildShell();
            refreshAll();
        } catch (Throwable t) {
            Log.e(TAG, "ACTIVITY_START_FAILED", t);
            showStartupError(t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (usbToggle == null) return;
        try {
            ContextCompat.registerReceiver(this, tetherStateReceiver,
                    new IntentFilter("android.net.conn.TETHER_STATE_CHANGED"),
                    ContextCompat.RECEIVER_EXPORTED);
            tetherReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "TETHER_STATE_RECEIVER_UNAVAILABLE", e);
        }
        scheduleTetheringRefresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
        controlHandler.removeCallbacks(refreshTetheringTask);
        if (tetherReceiverRegistered) {
            unregisterReceiver(tetherStateReceiver);
            tetherReceiverRegistered = false;
        }
        super.onPause();
    }

    private void showStartupError(Throwable error) {
        TextView message = new TextView(this);
        message.setText("Hotspot Manager could not load the interface.\n\n"
                + error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())
                + "\n\nCheck logcat with the WaguriHotspotUI tag for details.");
        message.setTextColor(TEXT);
        message.setTextSize(14);
        message.setPadding(dp(24), dp(32), dp(24), dp(32));
        message.setBackgroundColor(BG);
        setContentView(message);
    }

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(26));
        root.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
        applyInsets(scroll, root);

        LinearLayout header = row();
        header.setPadding(dp(15), dp(14), dp(15), dp(14));
        header.setBackground(shape(SURFACE_HIGH, OUTLINE, 24));

        ImageView icon = new ImageView(this);
        try {
            icon.setImageResource(R.mipmap.ic_launcher);
        } catch (Throwable ignored) {
            icon.setVisibility(View.GONE);
        }
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(54), dp(54));
        iconParams.rightMargin = dp(13);
        header.addView(icon, iconParams);

        LinearLayout heading = column();
        heading.addView(label("HOTSPOT MANAGER", 10, true, ACCENT));
        heading.addView(label("Network Manager", 21, true, TEXT));
        heading.addView(label("Android 16  ·  Runtime DHCP", 12, false, MUTED));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header);

        buildTetheringControls(root);

        LinearLayout summary = card();
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.addView(label("NETWORK STATUS", 10, true, MUTED));
        LinearLayout networkRows = row();
        networkRows.addView(networkBadge("Wi-Fi", "192.168.77.1", TEAL),
                new LinearLayout.LayoutParams(0, -2, 1f));
        networkRows.addView(networkBadge("USB", "192.168.78.1", ACCENT),
                new LinearLayout.LayoutParams(0, -2, 1f));
        summary.addView(networkRows);
        TextView dns = label("DNS  1.1.1.1     8.8.8.8", 12, true, MUTED);
        dns.setPadding(0, dp(10), 0, 0);
        summary.addView(dns);
        addPageCard(root, summary);

        LinearLayout statusCard = row();
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusCard.setBackground(shape(SURFACE, OUTLINE, 16));
        TextView dot = label("●", 12, true, TEAL);
        statusCard.addView(dot);
        status = label("Initializing…", 12, false, MUTED);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, -2, 1f);
        statusParams.leftMargin = dp(9);
        statusCard.addView(status, statusParams);
        root.addView(statusCard);

        LinearLayout tabs = row();
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(shape(SURFACE, OUTLINE, 18));
        devicesTab = tabButton("Devices");
        manualTab = tabButton("Manual & Saved");
        devicesTab.setOnClickListener(v -> showTab(0));
        manualTab.setOnClickListener(v -> showTab(1));
        tabs.addView(devicesTab, new LinearLayout.LayoutParams(0, dp(46), 1f));
        tabs.addView(manualTab, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, -2);
        tabsParams.topMargin = dp(14);
        root.addView(tabs, tabsParams);

        page = column();
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(-1, -2);
        pageParams.topMargin = dp(8);
        root.addView(page, pageParams);
        showTab(0);
    }

    private void buildTetheringControls(LinearLayout root) {
        LinearLayout controls = row();
        controls.setGravity(Gravity.TOP);
        LinearLayout usb = card();
        usb.addView(label("USB", 14, true, ACCENT));
        usbToggle = tetheringSwitch("USB tethering");
        usbControlStatus = label("Checking...", 11, false, MUTED);
        usb.addView(usbToggle);
        usb.addView(usbControlStatus);
        LinearLayout hotspot = card();
        hotspot.addView(label("Hotspot", 14, true, TEAL));
        hotspotToggle = tetheringSwitch("Wi-Fi hotspot");
        hotspotControlStatus = label("Checking...", 11, false, MUTED);
        hotspot.addView(hotspotToggle);
        hotspot.addView(hotspotControlStatus);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1f);
        left.rightMargin = dp(4);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1f);
        right.leftMargin = dp(4);
        controls.addView(usb, left);
        controls.addView(hotspot, right);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        root.addView(controls, params);
        usbToggle.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingToggles) changeTethering(TetheringControl.Target.USB, checked);
        });
        hotspotToggle.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingToggles) changeTethering(TetheringControl.Target.HOTSPOT, checked);
        });
    }

    private Switch tetheringSwitch(String description) {
        Switch toggle = new Switch(this);
        toggle.setText("Unknown");
        toggle.setTextColor(TEXT);
        toggle.setTextSize(13);
        toggle.setContentDescription(description);
        toggle.setMinHeight(dp(48));
        toggle.setEnabled(false);
        toggle.setThumbTintList(new ColorStateList(new int[][] {
                new int[] {android.R.attr.state_checked}, new int[0]
        }, new int[] {ACCENT, MUTED}));
        toggle.setTrackTintList(new ColorStateList(new int[][] {
                new int[] {android.R.attr.state_checked}, new int[0]
        }, new int[] {OUTLINE, SURFACE_HIGH}));
        toggle.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        return toggle;
    }

    private void renderTetheringControls() {
        if (usbToggle == null) return;
        syncingToggles = true;
        try {
            renderTetheringControl(usbToggle, usbControlStatus, TetheringControl.Target.USB);
            renderTetheringControl(hotspotToggle, hotspotControlStatus, TetheringControl.Target.HOTSPOT);
        } finally {
            syncingToggles = false;
        }
    }

    private void renderTetheringControl(Switch toggle, TextView detail, TetheringControl.Target target) {
        boolean known = tetheringState != null;
        boolean enabled = known && tetheringState.enabled(target);
        boolean changing = controlBusy && changingTarget == target;
        toggle.setChecked(changing ? changingEnabled : enabled);
        toggle.setText(changing ? (changingEnabled ? "Starting" : "Stopping")
                : known ? (enabled ? "On" : "Off") : "Unknown");
        toggle.setEnabled(known && !controlBusy);
        detail.setText(changing ? (changingEnabled ? "Turning on..." : "Turning off...")
                : controlBusy ? "Checking..."
                : known ? (enabled ? "Tethering active" : "Tethering inactive") : "Status unavailable");
    }

    private void scheduleTetheringRefresh() {
        if (!resumed) return;
        controlHandler.removeCallbacks(refreshTetheringTask);
        controlHandler.postDelayed(refreshTetheringTask, 500L);
    }

    private void refreshTetheringState() {
        if (!resumed) return;
        if (controlBusy) {
            tetherRefreshPending = true;
            return;
        }
        submitTethering(TetheringControl.Request.parse("status"));
    }

    private void changeTethering(TetheringControl.Target target, boolean enabled) {
        if (controlBusy || isOperationInProgress()) {
            renderTetheringControls();
            return;
        }
        submitTethering(TetheringControl.Request.parse(
                target == TetheringControl.Target.USB ? "usb" : "hotspot", enabled ? "on" : "off"));
    }

    private void submitTethering(TetheringControl.Request request) {
        controlBusy = true;
        changingTarget = request.action == TetheringControl.Action.STATUS ? null : request.target;
        changingEnabled = request.action == TetheringControl.Action.ON;
        renderTetheringControls();
        TetheringController.execute(this, request, reply -> {
            controlBusy = false;
            changingTarget = null;
            if (isDestroyed() || isFinishing()) return;
            if (reply.ok) {
                tetheringState = reply.state;
                if (request.action != TetheringControl.Action.STATUS) {
                    setStatus((request.target == TetheringControl.Target.USB ? "USB tethering" : "Hotspot")
                            + (request.action == TetheringControl.Action.ON ? " enabled." : " disabled."));
                }
            } else {
                if (request.action == TetheringControl.Action.STATUS) tetheringState = null;
                setStatus(reply.error);
                if (request.action != TetheringControl.Action.STATUS) scheduleTetheringRefresh();
            }
            renderTetheringControls();
            if (tetherRefreshPending) {
                tetherRefreshPending = false;
                scheduleTetheringRefresh();
            }
        });
    }

    private void applyInsets(ScrollView scroll, LinearLayout root) {
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(dp(16), dp(12) + bars.top, dp(16), dp(26) + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(scroll);
    }

    private void showTab(int tab) {
        activeTab = tab;
        if (devicesTab == null || manualTab == null) return;
        devicesTab.setBackground(tab == 0
                ? shape(ACCENT, ACCENT, 14) : shape(SURFACE, SURFACE, 14));
        devicesTab.setTextColor(tab == 0 ? ACCENT_TEXT : MUTED);
        manualTab.setBackground(tab == 1
                ? shape(ACCENT, ACCENT, 14) : shape(SURFACE, SURFACE, 14));
        manualTab.setTextColor(tab == 1 ? ACCENT_TEXT : MUTED);
        renderPage();
    }

    private void renderPage() {
        if (page == null) return;
        page.removeAllViews();
        if (activeTab == 0) renderDevicesPage();
        else renderManualPage();
    }

    private void renderDevicesPage() {
        LinearLayout titleRow = row();
        titleRow.addView(label("Detected devices", 19, true, TEXT),
                new LinearLayout.LayoutParams(0, -2, 1f));
        Button refresh = actionButton("Refresh", false);
        refresh.setOnClickListener(v -> {
            refreshAll();
            scheduleTetheringRefresh();
        });
        titleRow.addView(refresh);
        page.addView(titleRow);

        TextView description = label(
                "Lock the device's current IP address, or release its MAC address back to dynamic DHCP.",
                12, false, MUTED);
        description.setPadding(0, dp(4), 0, dp(8));
        page.addView(description);

        if (clients.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(label("No devices detected", 16, true, TEXT));
            empty.addView(label(
                    "Connect a device over Wi-Fi or USB, then tap Refresh.",
                    12, false, MUTED));
            addPageCard(page, empty);
        } else {
            for (Client client : new ArrayList<>(clients)) renderClientCard(client);
        }
    }

    private void renderClientCard(Client client) {
        LinearLayout box = card();
        LinearLayout top = row();
        top.addView(label(WIFI_IFACE.equals(client.iface)
                ? "WI-FI HOTSPOT  /  ap0" : "USB TETHERING  /  rndis0",
                10, true, MUTED), new LinearLayout.LayoutParams(0, -2, 1f));

        Reservation saved = findReservation(client.iface, client.mac);
        TextView stateChip = chip(saved == null ? "DYNAMIC" : "STATIC",
                saved == null ? 0xFF15372F : 0xFF283452,
                saved == null ? TEAL : ACCENT);
        top.addView(stateChip);
        box.addView(top);

        box.addView(label(client.ip, 28, true, TEXT));
        box.addView(label("MAC  " + client.mac, 13, false, MUTED));
        box.addView(label("Neighbor state  ·  " + client.state, 11, false, MUTED));
        if (saved != null) {
            box.addView(label("Locked IP  ·  " + saved.ip, 12, true, ACCENT));
        }

        LinearLayout actions = row();
        Button lock = actionButton(saved == null ? "Make static" : "Lock current IP", true);
        lock.setOnClickListener(v -> lockCurrentIp(client));
        Button dynamic = actionButton("Use dynamic DHCP", false);
        dynamic.setOnClickListener(v -> makeDynamic(client));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actionParams.topMargin = dp(10);
        actionParams.rightMargin = dp(5);
        actions.addView(lock, actionParams);
        LinearLayout.LayoutParams dynamicParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        dynamicParams.topMargin = dp(10);
        dynamicParams.leftMargin = dp(5);
        actions.addView(dynamic, dynamicParams);
        box.addView(actions);
        addPageCard(page, box);
    }

    private void renderManualPage() {
        page.addView(label("DHCP reservations", 19, true, TEXT));
        TextView intro = label(
                "Select a detected device or enter a MAC address manually.",
                12, false, MUTED);
        intro.setPadding(0, dp(3), 0, dp(8));
        page.addView(intro);

        LinearLayout form = card();
        Spinner mode = spinner(new String[] {
                "Detected devices", "Add MAC manually"
        });
        form.addView(fieldLabel("MAC SOURCE"));
        form.addView(mode);

        TextView deviceLabel = fieldLabel("DEVICE");
        Spinner detected = spinner(deviceLabels().toArray(new String[0]));
        form.addView(deviceLabel);
        form.addView(detected);

        TextView ifaceLabel = fieldLabel("INTERFACE");
        Spinner iface = spinner(new String[] {
                "Wi-Fi hotspot · ap0", "USB tethering · rndis0"
        });
        form.addView(ifaceLabel);
        form.addView(iface);

        TextView macLabel = fieldLabel("MAC ADDRESS");
        EditText mac = edit("aa:bb:cc:dd:ee:ff");
        form.addView(macLabel);
        form.addView(mac);

        form.addView(fieldLabel("TARGET IP"));
        EditText ip = edit("Enter target IP");
        form.addView(ip);

        final int[] previousMode = {-1};
        Runnable sync = () -> {
            boolean manual = mode.getSelectedItemPosition() == 1;
            if (manual && previousMode[0] != 1) {
                mac.setText("");
                ip.setText("");
            }
            previousMode[0] = mode.getSelectedItemPosition();
            setVisible(deviceLabel, !manual);
            setVisible(detected, !manual);
            setVisible(ifaceLabel, manual);
            setVisible(iface, manual);
            setVisible(macLabel, manual);
            setVisible(mac, manual);
            if (!manual && !clients.isEmpty()) {
                int index = Math.min(Math.max(0, detected.getSelectedItemPosition()),
                        clients.size() - 1);
                Client c = clients.get(index);
                mac.setText(c.mac);
                ip.setText(c.ip);
            }
            if (manual && ip.length() == 0) {
                ip.setHint(iface.getSelectedItemPosition() == 0
                        ? "192.168.77.x" : "192.168.78.x");
            }
        };

        mode.setOnItemSelectedListener(new SelectionListener(sync));
        detected.setOnItemSelectedListener(new SelectionListener(() -> {
            if (mode.getSelectedItemPosition() == 0 && !clients.isEmpty()) {
                int index = Math.min(Math.max(0, detected.getSelectedItemPosition()),
                        clients.size() - 1);
                Client c = clients.get(index);
                mac.setText(c.mac);
                ip.setText(c.ip);
            }
        }));
        iface.setOnItemSelectedListener(new SelectionListener(() -> {
            if (mode.getSelectedItemPosition() == 1) {
                ip.setText("");
                ip.setHint(iface.getSelectedItemPosition() == 0
                        ? "192.168.77.x" : "192.168.78.x");
            }
        }));

        if (!clients.isEmpty()) {
            mac.setText(clients.get(0).mac);
            ip.setText(clients.get(0).ip);
        }
        sync.run();

        Button save = actionButton("Save reservation", true);
        save.setOnClickListener(v -> {
            if (isOperationInProgress()) return;
            String chosenMac;
            String chosenIface;
            if (mode.getSelectedItemPosition() == 0) {
                if (clients.isEmpty()) {
                    setStatus("No devices found. Select Add MAC manually.");
                    return;
                }
                int index = Math.min(Math.max(0, detected.getSelectedItemPosition()),
                        clients.size() - 1);
                Client selected = clients.get(index);
                chosenMac = selected.mac;
                chosenIface = selected.iface;
            } else {
                chosenMac = normalizeMac(mac.getText().toString());
                chosenIface = iface.getSelectedItemPosition() == 0
                        ? WIFI_IFACE : USB_IFACE;
            }

            String target = ip.getText().toString().trim();
            if (chosenMac == null) {
                setStatus("Invalid MAC address format.");
                return;
            }
            if (!isValidStaticIp(chosenIface, target)) {
                setStatus("The IP address must be in the selected interface subnet (.2–.254).");
                return;
            }
            if (isIpReservedByOther(chosenIface, target, chosenMac)) {
                setStatus("This IP is already reserved for another MAC address.");
                return;
            }
            if (isIpUsedByOtherClient(chosenIface, target, chosenMac)) {
                setStatus("This IP is currently in use by another device. Choose a different IP.");
                return;
            }
            putReservation(chosenIface, chosenMac, target);
            saveReservations("Reservation saved. Reconnect the client to renew its DHCP lease.");
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(48));
        saveParams.topMargin = dp(12);
        form.addView(save, saveParams);
        form.addView(label("DNS is fixed to 1.1.1.1 and 8.8.8.8.", 11, false, MUTED));
        addPageCard(page, form);

        page.addView(label("Saved reservations", 19, true, TEXT));
        if (reservations.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(label("No static reservations yet.", 13, false, MUTED));
            addPageCard(page, empty);
        } else {
            for (Reservation reservation : new ArrayList<>(reservations)) {
                renderSavedReservation(reservation);
            }
        }
    }

    private void renderSavedReservation(Reservation r) {
        LinearLayout box = card();
        box.addView(label(WIFI_IFACE.equals(r.iface)
                ? "WI-FI  /  ap0" : "USB  /  rndis0", 10, true, MUTED));
        box.addView(label(r.mac, 14, true, TEXT));
        EditText target = edit("Target IP");
        target.setText(r.ip);
        box.addView(target);

        LinearLayout actions = row();
        Button update = actionButton("Update IP", true);
        update.setOnClickListener(v -> {
            if (isOperationInProgress()) return;
            String nextIp = target.getText().toString().trim();
            if (!isValidStaticIp(r.iface, nextIp)) {
                setStatus("Invalid IP address for " + r.iface + ".");
                return;
            }
            if (isIpReservedByOther(r.iface, nextIp, r.mac)) {
                setStatus("This IP is already reserved for another MAC address.");
                return;
            }
            if (isIpUsedByOtherClient(r.iface, nextIp, r.mac)) {
                setStatus("This IP is currently in use by another device.");
                return;
            }
            putReservation(r.iface, r.mac, nextIp);
            saveReservations("IP updated. The previous lease will be released when the client renews.");
        });

        Button delete = actionButton("Delete", false);
        delete.setTextColor(RED);
        delete.setOnClickListener(v -> {
            if (isOperationInProgress()) return;
            removeReservation(r.iface, r.mac);
            saveReservations("Reservation deleted. This MAC will use dynamic DHCP again.");
        });

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        p1.topMargin = dp(8);
        p1.rightMargin = dp(5);
        actions.addView(update, p1);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        p2.topMargin = dp(8);
        p2.leftMargin = dp(5);
        actions.addView(delete, p2);
        box.addView(actions);
        addPageCard(page, box);
    }

    private void lockCurrentIp(Client client) {
        if (isOperationInProgress()) return;
        if (!isValidStaticIp(client.iface, client.ip)) {
            setStatus("The current IP address is invalid for this interface.");
            return;
        }
        if (isIpReservedByOther(client.iface, client.ip, client.mac)) {
            setStatus("This IP is locked to another MAC address.");
            return;
        }
        putReservation(client.iface, client.mac, client.ip);
        saveReservations("IP " + client.ip + " is now reserved for " + client.mac
                + ". The client must renew DHCP or reconnect.");
    }

    private void makeDynamic(Client client) {
        if (isOperationInProgress()) return;
        removeReservation(client.iface, client.mac);
        saveReservations("Reservation for " + client.mac + " removed. The client will use dynamic DHCP.");
    }

    private boolean isOperationInProgress() {
        if (!busy) return false;
        setStatus("Please wait for the current operation to finish.");
        return true;
    }

    private void refreshAll() {
        if (busy) return;
        busy = true;
        setStatus("Checking root access and loading devices…");
        new Thread(() -> {
            String output = runRoot(
                    "echo __ROOT_UID__; id -u; "
                    + "echo __WIFI__; ip -4 neigh show dev ap0; "
                    + "echo __USB__; ip -4 neigh show dev rndis0; "
                    + "echo __CONFIG__; settings get global " + CONFIG_KEY);

            if (!hasRootAccess(output)) {
                Log.e(TAG, "ROOT_ACCESS_UNAVAILABLE output=" + String.valueOf(output));
                runOnUiThread(() -> {
                    clients.clear();
                    reservations.clear();
                    busy = false;
                    setStatus("Root access unavailable. Open Magisk > Superuser, "
                            + "grant root to Hotspot Manager, then tap Refresh.");
                    renderPage();
                });
                return;
            }

            ParsedState parsed = parseState(output);
            runOnUiThread(() -> {
                clients.clear();
                clients.addAll(parsed.clients);
                reservations.clear();
                reservations.addAll(parsed.reservations);
                busy = false;
                setStatus("Ready  ·  " + clients.size() + " devices  ·  "
                        + reservations.size() + " reservations");
                renderPage();
            });
        }).start();
    }

    private boolean hasRootAccess(String output) {
        if (output == null || output.contains("ROOT_EXIT=")
                || output.contains("ROOT_ERROR")) return false;
        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if ("__ROOT_UID__".equals(lines[i].trim())) {
                return "0".equals(lines[i + 1].trim());
            }
        }
        return false;
    }

    private static final class ParsedState {
        final List<Client> clients = new ArrayList<>();
        final List<Reservation> reservations = new ArrayList<>();
    }

    private ParsedState parseState(String output) {
        ParsedState state = new ParsedState();
        if (output == null) output = "";
        String section = "";
        StringBuilder config = new StringBuilder();

        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            if ("__WIFI__".equals(line)) { section = WIFI_IFACE; continue; }
            if ("__USB__".equals(line)) { section = USB_IFACE; continue; }
            if ("__CONFIG__".equals(line)) { section = "CONFIG"; continue; }

            if ("CONFIG".equals(section)) {
                if (!line.isEmpty()) config.append(line);
                continue;
            }
            if (!WIFI_IFACE.equals(section) && !USB_IFACE.equals(section)) continue;

            String[] parts = line.split("\\s+");
            int lladdr = -1;
            for (int i = 1; i < parts.length; i++) {
                if ("lladdr".equalsIgnoreCase(parts[i])) { lladdr = i; break; }
            }
            if (lladdr < 0 || lladdr + 1 >= parts.length) continue;
            String macValue = normalizeMac(parts[lladdr + 1]);
            String ipValue = parts[0];
            if (macValue != null && isValidStaticIp(section, ipValue)) {
                state.clients.add(new Client(section, ipValue, macValue,
                        parts[parts.length - 1]));
            }
        }

        Collections.sort(state.clients, Comparator
                .comparing((Client c) -> c.iface).thenComparing(c -> c.ip));

        String rawConfig = config.toString().trim();
        if (!rawConfig.isEmpty() && !"null".equals(rawConfig)) {
            try {
                JSONObject root = new JSONObject(rawConfig);
                JSONArray array = root.optJSONArray("reservations");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) continue;
                        String iface = item.optString("iface");
                        String macValue = normalizeMac(item.optString("mac"));
                        String ipValue = item.optString("ip");
                        if (macValue != null && isValidStaticIp(iface, ipValue)
                                && !hasOtherMacForIp(state.reservations,
                                        iface, ipValue, macValue)) {
                            state.reservations.add(
                                    new Reservation(iface, macValue, ipValue));
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "CONFIG_JSON_PARSE_FAILED", t);
            }
        }
        return state;
    }

    private boolean hasOtherMacForIp(List<Reservation> list,
            String iface, String ip, String mac) {
        for (Reservation r : list) {
            if (r.iface.equals(iface) && r.ip.equals(ip)
                    && !r.mac.equals(mac)) return true;
        }
        return false;
    }

    private List<String> deviceLabels() {
        List<String> values = new ArrayList<>();
        for (Client c : clients) values.add(c.label());
        if (values.isEmpty()) values.add("No detected devices");
        return values;
    }

    private void saveReservations(String successMessage) {
        String json;
        try {
            JSONObject root = new JSONObject();
            root.put("version", 2);
            JSONArray array = new JSONArray();
            for (Reservation r : reservations) {
                JSONObject item = new JSONObject();
                item.put("iface", r.iface);
                item.put("mac", r.mac);
                item.put("ip", r.ip);
                array.put(item);
            }
            root.put("reservations", array);
            json = root.toString();
        } catch (Throwable t) {
            setStatus("Could not create configuration: " + t.getMessage());
            return;
        }

        String encoded = Base64.encodeToString(
                json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String command = "test \"$(id -u)\" = 0"
                + " && settings put global " + CONFIG_KEY
                + " \"$(printf '%s' '" + encoded + "' | base64 -d)\""
                + " && echo __SAVE_OK__ && settings get global " + CONFIG_KEY;

        busy = true;
        setStatus("Saving configuration…");
        new Thread(() -> {
            String output = runRoot(command);
            runOnUiThread(() -> {
                busy = false;
                if (output == null || !output.contains("__SAVE_OK__")
                        || output.contains("ROOT_EXIT=") || output.contains("ROOT_ERROR")) {
                    Log.e(TAG, "CONFIG_SAVE_FAILED output=" + String.valueOf(output));
                    setStatus("Save failed. Grant root access in Magisk Superuser and try again.");
                    refreshAll();
                } else {
                    setStatus(successMessage);
                    refreshAll();
                }
            });
        }).start();
    }

    private void putReservation(String iface, String mac, String ip) {
        removeReservation(iface, mac);
        reservations.add(new Reservation(iface, mac, ip));
        Collections.sort(reservations, Comparator
                .comparing((Reservation r) -> r.iface).thenComparing(r -> r.mac));
    }

    private void removeReservation(String iface, String mac) {
        reservations.removeIf(r -> r.iface.equals(iface)
                && r.mac.equalsIgnoreCase(mac));
    }

    private Reservation findReservation(String iface, String mac) {
        for (Reservation r : reservations) {
            if (r.iface.equals(iface) && r.mac.equalsIgnoreCase(mac)) return r;
        }
        return null;
    }

    private boolean isIpReservedByOther(String iface, String ip, String mac) {
        for (Reservation r : reservations) {
            if (r.iface.equals(iface) && r.ip.equals(ip)
                    && !r.mac.equalsIgnoreCase(mac)) return true;
        }
        return false;
    }

    private boolean isIpUsedByOtherClient(String iface, String ip, String mac) {
        for (Client c : clients) {
            if (c.iface.equals(iface) && c.ip.equals(ip)
                    && !c.mac.equalsIgnoreCase(mac)) return true;
        }
        return false;
    }

    private boolean isValidStaticIp(String iface, String ip) {
        if (!WIFI_IFACE.equals(iface) && !USB_IFACE.equals(iface)) return false;
        String prefix = WIFI_IFACE.equals(iface) ? "192.168.77." : "192.168.78.";
        if (ip == null || !ip.startsWith(prefix)) return false;
        try {
            int host = Integer.parseInt(ip.substring(prefix.length()));
            return host >= 2 && host <= 254;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String normalizeMac(String value) {
        if (value == null) return null;
        String mac = value.trim().toLowerCase(Locale.ROOT);
        return mac.matches("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$") ? mac : null;
    }

    private String runRoot(String command) {
        return RootShell.run(command).legacyOutput();
    }

    private LinearLayout card() {
        LinearLayout box = column();
        box.setPadding(dp(14), dp(13), dp(14), dp(13));
        box.setBackground(shape(SURFACE, OUTLINE, 20));
        return box;
    }

    private void addPageCard(LinearLayout parent, LinearLayout box) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(6);
        p.bottomMargin = dp(6);
        parent.addView(box, p);
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private TextView fieldLabel(String value) {
        TextView t = label(value, 10, true, MUTED);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(9);
        p.bottomMargin = dp(3);
        return t;
    }

    private EditText edit(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setPadding(dp(12), dp(7), dp(12), dp(7));
        input.setBackground(shape(SURFACE_HIGH, OUTLINE, 13));
        return input;
    }

    private Spinner spinner(String[] items) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(shape(SURFACE_HIGH, OUTLINE, 13));
        return spinner;
    }

    private Button tabButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(shape(SURFACE, SURFACE, 14));
        button.setTextColor(MUTED);
        return button;
    }

    private Button actionButton(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(shape(primary ? ACCENT : SURFACE_HIGH,
                primary ? ACCENT : OUTLINE, 14));
        button.setTextColor(primary ? ACCENT_TEXT : TEXT);
        return button;
    }

    private TextView chip(String value, int background, int foreground) {
        TextView chip = label(value, 10, true, foreground);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(shape(background, background, 20));
        return chip;
    }

    private LinearLayout networkBadge(String name, String address, int tint) {
        LinearLayout badge = column();
        badge.setPadding(dp(10), dp(8), dp(10), dp(8));
        badge.setBackground(shape(SURFACE_HIGH, OUTLINE, 14));
        badge.addView(label(name, 11, true, tint));
        badge.addView(label(address, 14, true, TEXT));
        return badge;
    }

    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != color) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SelectionListener implements AdapterView.OnItemSelectedListener {
        private final Runnable action;
        SelectionListener(Runnable action) { this.action = action; }
        @Override public void onItemSelected(AdapterView<?> parent, View view,
                int position, long id) { action.run(); }
        @Override public void onNothingSelected(AdapterView<?> parent) {}
    }
}
