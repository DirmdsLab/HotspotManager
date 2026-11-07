package uwu.waguri.hotspotmanager;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TrafficSessionStateTest {
    private static final String MAC = "02:11:22:33:44:55";
    private static final String OTHER = "02:11:22:33:44:66";

    @Test public void unknownEventsAreNotReportedAsDisconnected() {
        TrafficSessionState state = new TrafficSessionState();
        assertFalse(state.wifiKnown());
        assertFalse(state.usbKnown());
    }

    @Test public void duplicateWifiEventsKeepEachConnectedClientsGeneration() {
        TrafficSessionState state = new TrafficSessionState();
        state.wifiClients(List.of(MAC, OTHER));
        Map<String, String> before = state.wifiTokens();
        state.wifiClients(List.of(OTHER, MAC.toUpperCase()));
        assertEquals(before, state.wifiTokens());
        assertTrue(state.wifiKnown());
    }

    @Test public void fastWifiReconnectChangesOnlyTheReconnectedMac() {
        TrafficSessionState state = new TrafficSessionState();
        state.wifiClients(List.of(MAC, OTHER));
        Map<String, String> before = state.wifiTokens();
        state.wifiClients(List.of(OTHER));
        state.wifiClients(List.of(MAC, OTHER));
        assertNotEquals(before.get(MAC), state.wifiTokens().get(MAC));
        assertEquals(before.get(OTHER), state.wifiTokens().get(OTHER));
    }

    @Test public void hotspotStopAndSnapshotCopiesDoNotRetainOldClients() {
        TrafficSessionState state = new TrafficSessionState();
        state.wifiClients(List.of(MAC));
        state.wifiTokens().clear();
        assertEquals(1, state.wifiTokens().size());
        state.wifiClients(List.of());
        assertTrue(state.wifiKnown());
        assertTrue(state.wifiTokens().isEmpty());
    }

    @Test public void duplicateUsbStartKeepsTheSessionAndReconnectChangesIt() {
        TrafficSessionState state = new TrafficSessionState();
        Object owner = new Object();
        state.usbStarted(owner);
        String token = state.usbToken();
        state.usbStarted(owner);
        assertEquals(token, state.usbToken());
        state.usbStopped(owner);
        assertNull(state.usbToken());
        state.usbStarted(owner);
        assertNotEquals(token, state.usbToken());
    }

    @Test public void oldUsbStopCannotEndAReplacementSessionOrWifiSession() {
        TrafficSessionState state = new TrafficSessionState();
        Object first = new Object();
        Object second = new Object();
        state.wifiClients(List.of(MAC));
        String wifi = state.wifiTokens().get(MAC);
        state.usbStarted(first);
        String token = state.usbToken();
        state.usbStarted(second);
        assertNotEquals(token, state.usbToken());
        String current = state.usbToken();
        state.usbStopped(first);
        assertEquals(current, state.usbToken());
        assertEquals(wifi, state.wifiTokens().get(MAC));
    }
}
