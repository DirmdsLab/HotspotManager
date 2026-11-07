package uwu.waguri.hotspotmanager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** Keeps native blocklist snapshots distinct from failed or unavailable reads. */
final class BlockedDeviceState {
    private List<String> macs = Collections.emptyList();
    private boolean known;
    private String error;

    void update(Collection<String> values) {
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) normalized.add(DeviceProfile.normalizeMac(value));
        // Publish only after every entry has been validated.
        macs = Collections.unmodifiableList(new ArrayList<>(normalized));
        known = true;
        error = null;
    }

    void failed(String message) { error = message; }
    List<String> macs() { return macs; }
    boolean known() { return known; }
    String error() { return error; }
}
