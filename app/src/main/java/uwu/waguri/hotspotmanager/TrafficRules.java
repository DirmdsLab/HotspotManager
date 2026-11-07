package uwu.waguri.hotspotmanager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validated IPv4/MAC-bound tc rules, counter parsing, and SI Mbps conversion. */
final class TrafficRules {
    static final int FIRST_PREF = 50000;
    static final int LAST_PREF = 50127;
    static final String[] IFACES = {"ap0", "rndis0"};

    static final class Client {
        final String iface;
        final String ip;
        final String mac;
        Client(String iface, String ip, String mac) {
            if (!"ap0".equals(iface) && !"rndis0".equals(iface)) throw new IllegalArgumentException("Unsupported interface.");
            String prefix = "ap0".equals(iface) ? "192.168.77." : "192.168.78.";
            if (ip == null || !ip.startsWith(prefix)) throw new IllegalArgumentException("IP is outside the managed subnet.");
            String tail = ip.substring(prefix.length());
            if (!tail.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid IPv4 host.");
            int host = Integer.parseInt(tail);
            if (host < 2 || host > 254 || !tail.equals(Integer.toString(host))) throw new IllegalArgumentException("Invalid client IPv4 address.");
            this.iface = iface;
            this.ip = ip;
            this.mac = DeviceProfile.normalizeMac(mac);
        }
        String key() { return iface + "|" + mac + "|" + ip; }
    }

    static int parseMbps(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            int kbps = new BigDecimal(value.trim()).multiply(new BigDecimal("1000")).intValueExact();
            if (kbps < 100 || kbps > 500000) throw new IllegalArgumentException("Use 0.1 to 500 Mbps, or leave blank for unlimited.");
            return kbps;
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Enter Mbps with at most three decimal places.");
        }
    }

    static List<Client> neighbors(String iface, String text) {
        List<Client> clients = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String[] fields = line.trim().split("\\s+");
            for (int i = 1; i + 1 < fields.length; i++) {
                if (!"lladdr".equals(fields[i])) continue;
                try { clients.add(new Client(iface, fields[0], fields[i + 1])); }
                catch (IllegalArgumentException ignored) { }
                break;
            }
        }
        return clients;
    }

    static String filter(Client client, String direction, int pref, int kbps, String cookie) {
        validateDirection(direction, pref);
        if (cookie == null || !cookie.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid ownership cookie.");
        if (kbps != 0 && (kbps < 100 || kbps > 500000)) throw new IllegalArgumentException("Invalid rate.");
        boolean upload = "ingress".equals(direction);
        String selector = upload ? "src" : "dst";
        int burst = kbps == 0 ? 1048576 : Math.max(65536, kbps * 1000 / 80);
        String first = kbps == 0 ? "1000000kbit burst 1048576 conform-exceed pipe/pipe"
                : kbps + "kbit burst " + burst + " mtu 65535 conform-exceed drop/pipe";
        // The second, non-dropping action counts only bytes accepted by the first action.
        return "tc filter add dev " + client.iface + " " + direction
                + " protocol ip pref " + pref + " u32 match ip " + selector + " " + client.ip
                + "/32 match ether " + selector + " " + client.mac
                + " action police rate " + first
                + " action police rate 1000000kbit burst 1048576 conform-exceed continue/continue cookie " + cookie;
    }

    static String show(String iface, String direction, int pref) {
        if (!"ap0".equals(iface) && !"rndis0".equals(iface)) throw new IllegalArgumentException("Unsupported interface.");
        validateDirection(direction, pref);
        return "tc -s filter show dev " + iface + " " + direction + " protocol ip pref " + pref;
    }

    static String delete(String iface, String direction, int pref) {
        show(iface, direction, pref);
        return "tc filter del dev " + iface + " " + direction + " protocol ip pref " + pref;
    }

    static int directionPref(int base, String direction) {
        if ((base & 1) != 0) throw new IllegalArgumentException("Expected an even priority base.");
        int pref = base + ("egress".equals(direction) ? 1 : 0);
        validateDirection(direction, pref);
        return pref;
    }

    static String ownedLeaf(String text, String cookie) {
        String found = null;
        StringBuilder leaf = new StringBuilder();
        for (String line : (text + "\nfilter END\n").split("\\r?\\n")) {
            if (line.startsWith("filter ")) {
                if (hasCookie(leaf.toString(), cookie)) {
                    if (found != null) throw new IllegalStateException("Duplicate ownership cookie in kernel filters.");
                    found = leaf.toString();
                }
                leaf.setLength(0);
            }
            leaf.append(line).append('\n');
        }
        if (found == null || !ownsPreference(found, cookie)) throw new IllegalStateException("The owned kernel leaf was not found.");
        return found;
    }

    static String leafHandle(String leaf) {
        Matcher handle = Pattern.compile("\\bfh\\s+([0-9a-f]{1,3}::[0-9a-f]{1,3})\\b").matcher(leaf);
        if (!handle.find()) throw new IllegalStateException("Cannot identify the owned u32 leaf handle.");
        String value = handle.group(1);
        if (handle.find()) throw new IllegalStateException("Ambiguous u32 leaf handle.");
        return value;
    }

    static String deleteLeaf(String iface, String direction, int pref, String leaf) {
        show(iface, direction, pref);
        return "tc filter del dev " + iface + " " + direction + " protocol ip pref " + pref
                + " handle " + leafHandle(leaf) + " u32";
    }

    private static void validateDirection(String direction, int pref) {
        if (!("ingress".equals(direction) || "egress".equals(direction)) || pref < FIRST_PREF || pref > LAST_PREF) {
            throw new IllegalArgumentException("Invalid owned filter identifier.");
        }
    }

    static long acceptedBytes(String text) {
        Matcher matcher = Pattern.compile("Sent\\s+(\\d+)\\s+bytes\\s+\\d+\\s+pkt").matcher(text);
        List<Long> values = new ArrayList<>();
        while (matcher.find()) values.add(Long.parseLong(matcher.group(1)));
        if (values.size() != 2) throw new IllegalStateException("Expected two policer counters; rate is unavailable.");
        return values.get(1);
    }

    static boolean hasCookie(String text, String cookie) {
        if (cookie == null || !cookie.matches("[0-9a-f]{32}")) return false;
        return Pattern.compile("(?im)^\\s*cookie\\s+(?:len\\s+16\\s+)?" + cookie + "\\s*$").matcher(text).find();
    }

    static boolean ownsPreference(String text, String cookie) {
        if (!hasCookie(text, cookie) || text.contains(" foreign handle ")) return false;
        Matcher leaves = Pattern.compile("\\bfh\\s+[0-9a-f]{1,3}::[0-9a-f]{1,3}\\b").matcher(text);
        int count = 0;
        while (leaves.find()) count++;
        // A shared priority must never be deleted as a unit.
        return count == 1;
    }

    static String selectedFilter(String text, int pref) {
        StringBuilder selected = new StringBuilder();
        int current = -1;
        Pattern header = Pattern.compile("^filter .*\\bpref\\s+(\\d+)\\b.*");
        for (String line : text.split("\\r?\\n")) {
            Matcher match = header.matcher(line);
            if (match.matches()) current = Integer.parseInt(match.group(1));
            if (current == pref) selected.append(line).append('\n');
        }
        return selected.toString();
    }

    static String fingerprint(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String value = line.trim().replaceAll("\\s+", " ");
            if (value.startsWith("filter ") || value.startsWith("match ")
                    || value.contains("police 0x") || value.startsWith("action order ") || value.startsWith("cookie ")) {
                result.append(value).append('\n');
            }
        }
        return result.toString();
    }

    static double mbps(long before, long after, long elapsedMillis) {
        if (before < 0 || after < before || elapsedMillis <= 0) return Double.NaN;
        return (after - before) * 8.0 / (elapsedMillis * 1000.0);
    }

    static boolean offloadDisabled(String dump) {
        return dump.contains("Hardware offload:") && dump.contains("Offload disabled")
                && dump.contains("mIsBpfEnabled: false") && !dump.contains("mIsBpfEnabled: true")
                && !dump.contains("Offload HALs started");
    }

    static void verifyReadySnapshot(String snapshot, String prepareBoot) {
        if (prepareBoot == null || !prepareBoot.matches("[0-9a-f-]{36}")) {
            throw new IllegalStateException("Preparation boot identity is missing. Restore native mode and prepare again.");
        }
        Matcher boot = Pattern.compile("(?m)^__HM_BOOT__([0-9a-f-]{36})$").matcher(snapshot);
        if (!boot.find()) throw new IllegalStateException("Cannot verify the current Android boot.");
        if (boot.group(1).equals(prepareBoot)) throw new IllegalStateException("Reboot Android after preparing Software QoS, then start monitoring.");
        if (!Pattern.compile("(?m)^__HM_HW__1$").matcher(snapshot).find()
                || !Pattern.compile("(?m)^__HM_BPF__false$").matcher(snapshot).find()) {
            throw new IllegalStateException("Software offload settings are not retained. Monitoring is stopped.");
        }
        int start = snapshot.indexOf("__HM_DUMP__\n");
        if (start < 0 || !offloadDisabled(snapshot.substring(start))) {
            throw new IllegalStateException("Android has not confirmed hardware and BPF offload are disabled. No rate is reported and no new limit is applied.");
        }
    }

    static String displayRate(double down, double up) {
        if (!Double.isFinite(down) || !Double.isFinite(up)) return "IPv4 rate: sampling...";
        return String.format(Locale.ENGLISH, "IPv4  Down %.2f Mbps  /  Up %.2f Mbps", down, up);
    }

    private TrafficRules() {}
}
