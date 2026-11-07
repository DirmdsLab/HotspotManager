package uwu.waguri.hotspotmanager;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class BlockedDeviceStateTest {
    private static final String FIRST = "02:ab:cd:33:44:55";
    private static final String SECOND = "04:de:fa:33:44:66";

    @Test
    public void missingReadIsNotAnEmptyNativeBlocklist() {
        BlockedDeviceState state = new BlockedDeviceState();
        state.failed("Root unavailable");
        assertFalse(state.known());
        assertEquals("Root unavailable", state.error());
    }

    @Test
    public void confirmedEmptySnapshotIsKnownAndClearsAnOldError() {
        BlockedDeviceState state = new BlockedDeviceState();
        state.failed("Service unavailable");
        state.update(Collections.emptyList());
        assertTrue(state.known());
        assertTrue(state.macs().isEmpty());
        assertNull(state.error());
    }

    @Test
    public void failedRefreshKeepsThePreviousSnapshotAndItsError() {
        BlockedDeviceState state = new BlockedDeviceState();
        state.update(List.of(FIRST));
        state.failed("Permission denied");
        assertTrue(state.known());
        assertEquals(List.of(FIRST), state.macs());
        assertEquals("Permission denied", state.error());
    }

    @Test
    public void invalidNativeSnapshotCannotPartiallyReplaceAValidList() {
        BlockedDeviceState state = new BlockedDeviceState();
        state.update(List.of(FIRST));
        assertThrows(IllegalArgumentException.class,
                () -> state.update(Arrays.asList(SECOND, "not-a-mac")));
        assertEquals(List.of(FIRST), state.macs());
    }

    @Test
    public void normalizedSnapshotIncludesDisconnectedMacsWithoutAProfile() {
        BlockedDeviceState state = new BlockedDeviceState();
        state.update(Arrays.asList(SECOND, FIRST.toUpperCase(), FIRST));
        assertEquals(Arrays.asList(FIRST, SECOND), state.macs());
    }

    @Test
    public void nativeUnblockSnapshotRemovesOnlyTheSelectedMac() {
        BlockedDeviceState state = new BlockedDeviceState();
        state.update(Arrays.asList(FIRST, SECOND));
        state.update(DeviceProfile.updateBlocked(state.macs(), FIRST, false));
        assertEquals(List.of(SECOND), state.macs());
    }

    @Test
    public void callerMutationCannotChangeThePublishedSnapshot() {
        BlockedDeviceState state = new BlockedDeviceState();
        List<String> source = new ArrayList<>(List.of(FIRST));
        state.update(source);
        source.clear();
        assertEquals(List.of(FIRST), state.macs());
        assertThrows(UnsupportedOperationException.class, () -> state.macs().clear());
    }
}
