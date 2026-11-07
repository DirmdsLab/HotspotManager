package uwu.waguri.hotspotmanager;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Connection generations come from native events, never neighbor-cache aging. */
final class TrafficSessionState {
    private final Map<String, String> wifi = new HashMap<>();
    private boolean wifiKnown;
    private boolean usbKnown;
    private Object usbOwner;
    private String usbToken;

    synchronized void wifiClients(Collection<String> clients) {
        Map<String, String> next = new HashMap<>();
        for (String value : clients) {
            String mac = DeviceProfile.normalizeMac(value);
            next.put(mac, wifi.containsKey(mac) ? wifi.get(mac) : UUID.randomUUID().toString());
        }
        wifi.clear();
        wifi.putAll(next);
        wifiKnown = true;
    }

    synchronized void usbStarted(Object owner) {
        if (owner == null) throw new IllegalArgumentException("Missing USB session owner.");
        usbKnown = true;
        if (usbOwner == owner) return;
        usbOwner = owner;
        usbToken = UUID.randomUUID().toString();
    }

    synchronized void usbStopped(Object owner) {
        if (usbOwner != null && usbOwner != owner) return;
        usbKnown = true;
        usbOwner = null;
        usbToken = null;
    }

    synchronized Map<String, String> wifiTokens() { return new HashMap<>(wifi); }
    synchronized boolean wifiKnown() { return wifiKnown; }
    synchronized boolean usbKnown() { return usbKnown; }
    synchronized String usbToken() { return usbToken; }
}
