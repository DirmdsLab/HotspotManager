package uwu.waguri.hotspotmanager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Decodes Linux routing-netlink filter dumps without relying on vendor tc text. */
final class TrafficNetlink {
    static byte[] request(int index, int parent, int sequence) {
        return ByteBuffer.allocate(36).order(ByteOrder.nativeOrder())
                .putInt(36).putShort((short) 46).putShort((short) 0x301).putInt(sequence).putInt(0)
                .putInt(0).putInt(index).putInt(0).putInt(parent).putInt(0).array();
    }

    static boolean append(byte[] packet, int length, int sequence, int index, int parent, StringBuilder out) {
        ByteBuffer b = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.nativeOrder());
        boolean done = false;
        for (int pos = 0; pos < length;) {
            require(length - pos >= 16, "Short netlink header");
            int size = b.getInt(pos);
            require(size >= 16 && size <= length - pos, "Truncated netlink message");
            int type = Short.toUnsignedInt(b.getShort(pos + 4));
            int flags = Short.toUnsignedInt(b.getShort(pos + 6));
            require(b.getInt(pos + 8) == sequence, "Unexpected netlink sequence");
            require((flags & 0x10) == 0, "Interrupted filter dump; retry monitoring");
            if (type == 2) {
                require(size >= 20, "Short netlink error");
                require(b.getInt(pos + 16) == 0, "Kernel filter query failed: " + b.getInt(pos + 16));
            } else if (type == 3) {
                if (size >= 20) require(b.getInt(pos + 16) == 0, "Kernel filter dump failed");
                done = true;
            } else if (type == 4) throw new IllegalStateException("Netlink receive overrun");
            else if (type == 44) {
                require(size >= 36, "Short tc filter message");
                if (b.getInt(pos + 20) == index && b.getInt(pos + 28) == parent) {
                    int info = b.getInt(pos + 32);
                    int pref = info >>> 16;
                    if (pref >= TrafficRules.FIRST_PREF && pref <= TrafficRules.LAST_PREF) {
                        filter(b.getInt(pos + 24), pref, info & 65535,
                                attrs(Arrays.copyOfRange(packet, pos + 36, pos + size), 9), out);
                    }
                }
            }
            pos += align(size);
            require(pos <= length, "Invalid message alignment");
        }
        return done;
    }

    private static void filter(int handle, int pref, int protocol, Map<Integer, byte[]> top, StringBuilder out) {
        String kind = text(top.get(1));
        int chain = top.containsKey(11) ? integer(top.get(11)) : 0;
        out.append("filter protocol ").append(protocol == 8 ? "ip" : "other")
                .append(" pref ").append(pref).append(' ').append(kind).append(" chain ").append(chain);
        if (!kind.equals("u32") || protocol != 8 || chain != 0) {
            out.append(" foreign handle ").append(Integer.toUnsignedString(handle)).append('\n');
            return;
        }
        String h = Integer.toHexString(handle >>> 20) + ":"
                + (((handle >>> 12) & 255) == 0 ? "" : Integer.toHexString((handle >>> 12) & 255))
                + ((handle & 4095) == 0 ? "" : ":" + Integer.toHexString(handle & 4095));
        out.append(" fh ").append(h).append('\n');
        Map<Integer, byte[]> options = attrs(top.get(2), 12);
        if ((handle & 4095) == 0) return;
        out.append("match ").append(hex(options.get(5))).append('\n');
        for (Map.Entry<Integer, byte[]> entry : attrs(options.get(7)).entrySet()) {
            Map<Integer, byte[]> action = attrs(entry.getValue(), 5);
            out.append("action order ").append(entry.getKey()).append(": ");
            String actionKind = text(action.get(1));
            if (!actionKind.equals("police")) { out.append(actionKind).append('\n'); continue; }
            Map<Integer, byte[]> police = attrs(action.get(2), 7);
            byte[] tbf = police.get(1);
            require(tbf != null && tbf.length >= 44, "Missing police configuration");
            out.append("police 0x").append(Integer.toHexString(integer(tbf))).append(" config ")
                    .append(hex(Arrays.copyOf(tbf, 44)));
            // Exclude timers/refcounts, retain every static rate/control attribute.
            for (int id : new int[] {2, 3, 4, 5, 8, 9, 10, 11}) {
                if (police.containsKey(id)) out.append(' ').append(id).append(':').append(hex(police.get(id)));
            }
            out.append('\n');
            byte[] basic = attrs(action.get(4), 6).get(1);
            require(basic != null && basic.length >= 12, "Missing 64-bit police counters");
            long bytes = ByteBuffer.wrap(basic).order(ByteOrder.nativeOrder()).getLong();
            require(bytes >= 0, "Unsupported unsigned counter overflow");
            out.append("Sent ").append(bytes).append(" bytes 0 pkt\n");
            if (action.containsKey(6)) out.append("cookie ").append(hex(action.get(6))).append('\n');
        }
    }

    private static Map<Integer, byte[]> attrs(byte[] bytes) {
        return attrs(bytes, -1);
    }

    private static Map<Integer, byte[]> attrs(byte[] bytes, int padding) {
        Map<Integer, byte[]> values = new LinkedHashMap<>();
        if (bytes == null) return values;
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        for (int pos = 0; pos < bytes.length;) {
            require(bytes.length - pos >= 4, "Short netlink attribute");
            int size = Short.toUnsignedInt(b.getShort(pos));
            int type = Short.toUnsignedInt(b.getShort(pos + 2)) & 0x3fff;
            require(size >= 4 && size <= bytes.length - pos, "Truncated netlink attribute");
            if (type != 0 && !(type == padding && size == 4)) {
                require(!values.containsKey(type), "Duplicate netlink attribute");
                values.put(type, Arrays.copyOfRange(bytes, pos + 4, pos + size));
            }
            pos += align(size);
            require(pos <= bytes.length, "Invalid attribute alignment");
        }
        return values;
    }

    private static int integer(byte[] value) {
        require(value != null && value.length >= 4, "Missing netlink integer");
        return ByteBuffer.wrap(value).order(ByteOrder.nativeOrder()).getInt();
    }
    private static String text(byte[] value) {
        if (value == null) return "unknown";
        int end = 0;
        while (end < value.length && value[end] != 0) end++;
        return new String(value, 0, end, StandardCharsets.US_ASCII);
    }
    private static String hex(byte[] value) {
        if (value == null) return "missing";
        StringBuilder out = new StringBuilder();
        for (byte v : value) out.append(Character.forDigit((v >>> 4) & 15, 16)).append(Character.forDigit(v & 15, 16));
        return out.toString();
    }
    private static int align(int n) { return (n + 3) & ~3; }
    private static void require(boolean condition, String error) { if (!condition) throw new IllegalStateException(error); }
    private TrafficNetlink() {}
}
