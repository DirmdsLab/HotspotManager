package uwu.waguri.hotspotmanager;

import java.util.Locale;

/** Accepted IPv4 bytes recorded by the monitor; not a carrier billing estimate. */
final class TrafficUsage {
    final long down;
    final long up;
    TrafficUsage(long down, long up) {
        if (down < 0 || up < 0) throw new IllegalArgumentException("Negative usage.");
        this.down = down; this.up = up;
    }
    static long delta(long before, long now) {
        if (now < 0) throw new IllegalArgumentException("Invalid byte counter.");
        if (before < 0) return 0;
        return now >= before ? now - before : now;
    }
    TrafficUsage add(boolean upload, long delta) {
        if (delta < 0) throw new IllegalArgumentException("Negative usage delta.");
        return upload ? new TrafficUsage(down, Math.addExact(up, delta)) : new TrafficUsage(Math.addExact(down, delta), up);
    }
    static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1048576) return String.format(Locale.ENGLISH, "%.2f KiB", value / 1024.0);
        if (value < 1073741824L) return String.format(Locale.ENGLISH, "%.2f MiB", value / 1048576.0);
        return String.format(Locale.ENGLISH, "%.2f GiB", value / 1073741824.0);
    }
    String label() { return "Down " + bytes(down) + " / Up " + bytes(up); }
}
