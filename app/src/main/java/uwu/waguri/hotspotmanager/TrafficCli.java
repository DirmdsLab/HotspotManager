package uwu.waguri.hotspotmanager;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;
import java.io.FileDescriptor;
import java.io.ByteArrayOutputStream;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;

/** Root-only, read-only kernel snapshots. No shell-formatted cookies or counters are parsed. */
public final class TrafficCli {
    static final String PREFIX = "HM_TRAFFIC_V1 ";
    public static void main(String[] args) {
        JSONObject result = new JSONObject();
        int code = 0;
        try {
            if (Os.getuid() != 0) throw new SecurityException("Traffic snapshots require root.");
            if (args.length != 0 && args.length != 2) throw new IllegalArgumentException("Use no arguments or an interface and direction.");
            if (args.length == 2) {
                TrafficRules.show(args[0], args[1], TrafficRules.FIRST_PREF);
                result.put("filters", filters(args[0], args[1]));
            } else {
                for (String iface : TrafficRules.IFACES) {
                    int index = index(iface);
                    result.put(iface + "_index", index == 0 ? "" : Integer.toString(index));
                    result.put(iface + "_neighbors", index == 0 ? "" : neighbors(iface));
                    for (String direction : new String[] {"ingress", "egress"}) {
                        result.put(iface + "_" + direction, index == 0 ? "" : filters(iface, direction));
                    }
                    if (index != index(iface)) throw new IllegalStateException("Downstream changed during snapshot. Retry monitoring.");
                }
            }
            result.put("ok", true);
        } catch (Throwable error) {
            code = 1;
            try { result.put("ok", false).put("error", error.getClass().getSimpleName() + ": " + error.getMessage()); }
            catch (Exception ignored) { }
        }
        System.out.println(PREFIX + result);
        System.out.flush();
        System.exit(code);
    }

    private static int index(String iface) throws Exception {
        if (!Files.exists(Paths.get("/sys/class/net/" + iface + "/ifindex"))) return 0;
        return Integer.parseInt(new String(Files.readAllBytes(Paths.get("/sys/class/net/" + iface + "/ifindex")), StandardCharsets.US_ASCII).trim());
    }

    private static String neighbors(String iface) throws Exception {
        return command("/system/bin/ip", "-4", "neigh", "show", "dev", iface);
    }

    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 65536) throw new IllegalStateException("Snapshot exceeds bound.");
            }
            if (process.waitFor() != 0) throw new IllegalStateException("Snapshot command failed: " + bytes.toString(StandardCharsets.UTF_8.name()));
            return bytes.toString(StandardCharsets.UTF_8.name());
        } finally { process.destroy(); }
    }

    private static String filters(String iface, String direction) throws Exception {
        int index = index(iface);
        if (index == 0) return "";
        int parent = direction.equals("ingress") ? 0xfffffff2 : 0xfffffff3;
        FileDescriptor fd = Os.socket(OsConstants.AF_NETLINK, OsConstants.SOCK_RAW, OsConstants.NETLINK_ROUTE);
        try {
            Class<?> address = Class.forName("android.system.NetlinkSocketAddress");
            SocketAddress kernel = (SocketAddress) address.getConstructor(int.class, int.class).newInstance(0, 0);
            Os.bind(fd, kernel);
            Os.connect(fd, kernel);
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(2500));
            Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 262144);
            byte[] request = TrafficNetlink.request(index, parent, 1);
            if (Os.write(fd, request, 0, request.length) != request.length) throw new IllegalStateException("Short netlink request.");
            StringBuilder out = new StringBuilder();
            byte[] reply = new byte[262144];
            for (int part = 0; part < 256; part++) {
                int count = Os.read(fd, reply, 0, reply.length);
                if (count <= 0 || count == reply.length) throw new IllegalStateException("Incomplete kernel filter dump.");
                boolean done = TrafficNetlink.append(reply, count, 1, index, parent, out);
                if (out.length() > 524288) throw new IllegalStateException("Kernel filter snapshot exceeds bound.");
                if (done) return out.toString();
            }
            throw new IllegalStateException("Kernel filter dump did not finish.");
        } catch (IllegalStateException error) {
            // A downstream without clsact has no filters yet; the engine will create it.
            if ((error.getMessage().equals("Kernel filter query failed: -2") || error.getMessage().equals("Kernel filter query failed: -22"))
                    && !command("/system/bin/tc", "qdisc", "show", "dev", iface).contains("qdisc clsact")) return "";
            throw error;
        } finally { Os.close(fd); }
    }
    private TrafficCli() {}
}
