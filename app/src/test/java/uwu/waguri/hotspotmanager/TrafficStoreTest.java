package uwu.waguri.hotspotmanager;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TrafficStoreTest {
    private static final String MAC = "02:11:22:33:44:55";
    private static final String FIRST = "11111111-1111-4111-8111-111111111111";
    private static final String NEXT = "22222222-2222-4222-8222-222222222222";
    private static final String SESSION = "session_ap0|" + MAC;
    private static final String RULE = "owned_ap0_egress_50001";

    /** An atomic in-memory preferences implementation; no Android methods are mocked as successes. */
    private static final class Memory {
        final Map<String, Object> values = new HashMap<>();
        boolean failCommit;
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAll": return new HashMap<>(values);
                        case "contains": return values.containsKey(args[0]);
                        case "getString": case "getLong": case "getInt": case "getBoolean":
                            return values.getOrDefault(args[0], args[1]);
                        case "edit": return editor();
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        private Object editor() {
            Map<String, Object> writes = new HashMap<>();
            return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "putString": case "putLong": case "putInt": case "putBoolean":
                                writes.put((String) args[0], args[1]); return proxy;
                            case "remove": writes.put((String) args[0], null); return proxy;
                            case "commit": case "apply":
                                if (failCommit) return false;
                                for (Map.Entry<String, Object> write : writes.entrySet()) {
                                    if (write.getValue() == null) values.remove(write.getKey());
                                    else values.put(write.getKey(), write.getValue());
                                }
                                return method.getName().equals("commit") ? true : null;
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }

    private static TrafficSessionSnapshot snapshot(String wifi, String usb) throws Exception {
        JSONObject clients = new JSONObject();
        if (wifi != null) clients.put(MAC, wifi);
        return TrafficSessionSnapshot.parse(new JSONObject().put("version", 1).put("boot", 7)
                .put("wifiKnown", true).put("wifi", clients).put("usbKnown", true)
                .put("usb", usb == null ? "" : usb).toString(), 7);
    }

    private static void rule(TrafficStore store, String key, String iface, String ip, String direction, String token) throws Exception {
        store.owned(key, new JSONObject().put("iface", iface).put("ip", ip).put("mac", MAC)
                .put("direction", direction).put("session", token).toString());
    }

    @Test public void reconnectResetsSessionAndRetainsLegacyMacTotal() throws Exception {
        Memory memory = new Memory();
        memory.values.put("usage_" + MAC, "{\"down\":9000,\"up\":100}");
        TrafficStore store = new TrafficStore(memory.prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST));
        rule(store, RULE, "ap0", "192.168.77.2", "egress", FIRST);
        store.account(RULE, 1000);
        assertEquals(1000, store.sessionUsage("ap0", MAC, snapshot(FIRST, null)).down);
        store.refreshSessionTokens(Map.of(SESSION, NEXT));
        assertEquals(0, store.sessionUsage("ap0", MAC, snapshot(NEXT, null)).down);
        assertEquals(10000, store.usage(MAC).down);
        assertEquals(100, store.usage(MAC).up);
    }

    @Test public void oldGenerationCountersOnlyContributeToLifetimeTotal() throws Exception {
        TrafficStore store = new TrafficStore(new Memory().prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST));
        rule(store, RULE, "ap0", "192.168.77.2", "egress", FIRST);
        store.account(RULE, 1000);
        store.refreshSessionTokens(Map.of(SESSION, NEXT));
        store.account(RULE, 1500);
        assertEquals(1500, store.usage(MAC).down);
        assertEquals(0, store.sessionUsage("ap0", MAC, snapshot(NEXT, null)).down);
    }

    @Test public void duplicateCheckpointsAndStoreRecreationPreserveBothTotals() throws Exception {
        Memory memory = new Memory();
        TrafficStore store = new TrafficStore(memory.prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST));
        rule(store, RULE, "ap0", "192.168.77.2", "egress", FIRST);
        store.account(RULE, 1000);
        store = new TrafficStore(memory.prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST));
        store.account(RULE, 1000);
        store.account(RULE, 1300);
        assertEquals(1300, store.usage(MAC).down);
        assertEquals(1300, store.sessionUsage("ap0", MAC, snapshot(FIRST, null)).down);
    }

    @Test public void ipAndRuleReplacementKeepTheSameConnectionSession() throws Exception {
        TrafficStore store = new TrafficStore(new Memory().prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST));
        rule(store, RULE, "ap0", "192.168.77.2", "egress", FIRST);
        store.account(RULE, 1000);
        rule(store, RULE, "ap0", "192.168.77.3", "egress", FIRST);
        store.account(RULE, 500);
        assertEquals(1500, store.sessionUsage("ap0", MAC, snapshot(FIRST, null)).down);
        assertEquals(1500, store.usage(MAC).down);
    }

    @Test public void interfacesHaveSeparateSessionsAndOneMacLifetimeTotal() throws Exception {
        TrafficStore store = new TrafficStore(new Memory().prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST, "session_rndis0|" + MAC, NEXT));
        rule(store, RULE, "ap0", "192.168.77.2", "egress", FIRST);
        rule(store, "owned_rndis0_ingress_50002", "rndis0", "192.168.78.2", "ingress", NEXT);
        store.account(RULE, 1000);
        store.account("owned_rndis0_ingress_50002", 700);
        assertEquals(1000, store.sessionUsage("ap0", MAC, snapshot(FIRST, NEXT)).down);
        assertEquals(700, store.sessionUsage("rndis0", MAC, snapshot(FIRST, NEXT)).up);
        assertEquals(0, store.sessionUsage("ap0", MAC, snapshot(FIRST, NEXT)).up);
        assertEquals(1000, store.usage(MAC).down);
        assertEquals(700, store.usage(MAC).up);
    }

    @Test public void explicitResetKeepsSessionGenerationAndSkipsTheOldCounter() throws Exception {
        TrafficStore store = new TrafficStore(new Memory().prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST));
        rule(store, RULE, "ap0", "192.168.77.2", "egress", FIRST);
        store.account(RULE, 1000);
        store.resetUsage(MAC);
        store.account(RULE, 2000);
        store.account(RULE, 2100);
        assertEquals(100, store.usage(MAC).down);
        assertEquals(100, store.sessionUsage("ap0", MAC, snapshot(FIRST, null)).down);
    }

    @Test public void failedCheckpointDoesNotAdvanceCursorOrEitherTotal() throws Exception {
        Memory memory = new Memory();
        TrafficStore store = new TrafficStore(memory.prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST));
        rule(store, RULE, "ap0", "192.168.77.2", "egress", FIRST);
        memory.failCommit = true;
        assertThrows(IllegalStateException.class, () -> store.account(RULE, 1000));
        memory.failCommit = false;
        store.account(RULE, 1000);
        assertEquals(1000, store.usage(MAC).down);
        assertEquals(1000, store.sessionUsage("ap0", MAC, snapshot(FIRST, null)).down);
    }

    @Test public void unknownNativeEventsNeverDisplayHistoricalIpTotalsAsSessionUsage() throws Exception {
        TrafficStore store = new TrafficStore(new Memory().prefs);
        assertNull(store.sessionUsage("ap0", MAC, TrafficSessionSnapshot.parse(null, 7)));
        assertNull(store.sessionUsage("ap0", MAC, TrafficSessionSnapshot.parse("{}", 7)));
        assertFalse(TrafficSessionSnapshot.parse("{\"version\":1,\"boot\":6,\"wifiKnown\":true}", 7).known("ap0"));
        assertEquals(0, store.sessionUsage("ap0", MAC, snapshot(null, null)).down);
    }

    @Test public void reconnectDisplaysZeroBeforeTheMonitorRefreshesItsFilters() throws Exception {
        TrafficStore store = new TrafficStore(new Memory().prefs);
        store.refreshSessionTokens(Map.of(SESSION, FIRST));
        rule(store, RULE, "ap0", "192.168.77.2", "egress", FIRST);
        store.account(RULE, 1000);
        assertEquals(0, store.sessionUsage("ap0", MAC, snapshot(NEXT, null)).down);
        assertEquals(0, store.sessionUsage("ap0", MAC, snapshot(null, null)).down);
        assertEquals(1000, store.usage(MAC).down);
    }

    @Test public void nativeSnapshotsRejectPreviousBootsDuplicatesAndOutOfOrderEvents() throws Exception {
        JSONObject previous = new JSONObject().put("boot", 7).put("publishedAt", 1000L);
        JSONObject incoming = new JSONObject().put("version", 1).put("boot", 7).put("publishedAt", 1001L);
        assertTrue(TrafficSessionSnapshot.newerThan(incoming, previous, 7));
        assertFalse(TrafficSessionSnapshot.newerThan(incoming, previous, 8));
        incoming.put("publishedAt", 1000L);
        assertFalse(TrafficSessionSnapshot.newerThan(incoming, previous, 7));
        incoming.put("publishedAt", 999L);
        assertFalse(TrafficSessionSnapshot.newerThan(incoming, previous, 7));
        previous.put("boot", 6);
        assertTrue(TrafficSessionSnapshot.newerThan(incoming, previous, 7));
    }

    @Test public void refreshUsesConnectionEventsInsteadOfStaleArpOrIpChanges() throws Exception {
        TrafficStore store = new TrafficStore(new Memory().prefs);
        TrafficRules.Client client = new TrafficRules.Client("ap0", "192.168.77.2", MAC);
        store.refreshSessions(snapshot(FIRST, null), java.util.List.of(client));
        rule(store, RULE, "ap0", client.ip, "egress", FIRST);
        store.account(RULE, 1000);
        store.refreshSessions(snapshot(null, null), java.util.List.of(client));
        store.refreshSessions(snapshot(NEXT, null), java.util.List.of(client));
        assertEquals(0, store.sessionUsage("ap0", MAC, snapshot(NEXT, null)).down);
        assertEquals(1000, store.usage(MAC).down);
    }
}
