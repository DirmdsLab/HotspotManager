package uwu.waguri.hotspotmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Per-MAC identity and targeted native blocklist edits. No device type is inferred. */
final class DeviceProfile {
    enum Type {
        UNKNOWN("Unknown"), PHONE("Phone"), TABLET("Tablet"), COMPUTER("Computer"),
        TV("TV"), CONSOLE("Game console"), IOT("IoT device"), OTHER("Other");
        final String label;
        Type(String label) { this.label = label; }
        static Type fromStored(String value) {
            try { return valueOf(value); } catch (Exception ignored) { return UNKNOWN; }
        }
    }

    final String mac;
    final Type type;
    final String iface;
    final String ip;
    final boolean permanent;

    DeviceProfile(String mac, Type type, String iface, String ip, boolean permanent) {
        this.mac = normalizeMac(mac);
        this.type = type == null ? Type.UNKNOWN : type;
        if (!"ap0".equals(iface) && !"rndis0".equals(iface)) {
            throw new IllegalArgumentException("Unsupported downstream interface.");
        }
        this.iface = iface;
        this.ip = ip == null ? "" : ip;
        this.permanent = permanent;
    }

    static String normalizeMac(String value) {
        String mac = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!mac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")
                || "00:00:00:00:00:00".equals(mac)
                || (Integer.parseInt(mac.substring(0, 2), 16) & 1) != 0) {
            throw new IllegalArgumentException("A valid unicast client MAC address is required.");
        }
        return mac;
    }

    static void requireRememberedBlock(boolean blocked, boolean remember) {
        if (blocked && !remember) {
            throw new IllegalArgumentException(
                    "Enable Remember settings before blocking. Android saves Wi-Fi blocks permanently.");
        }
    }

    static List<String> updateBlocked(List<String> current, String selectedMac, boolean blocked) {
        String target = normalizeMac(selectedMac);
        List<String> result = new ArrayList<>();
        for (String value : current) {
            // Preserve every unrelated entry exactly; remove only the selected identity.
            if (!target.equals(value.toLowerCase(Locale.ROOT))) result.add(value);
        }
        if (blocked) result.add(target);
        return result;
    }
}
