package uwu.waguri.hotspotmanager;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TrafficRulesTest {
    private static final String MAC = "02:11:22:33:44:55";
    private static final String COOKIE = "0123456789abcdef0123456789abcdef";
    private static final String FILTER = "filter protocol ip pref 50000 u32 chain 0\n"
            + "filter protocol ip pref 50000 u32 chain 0 fh 800: ht divisor 1\n"
            + "filter protocol ip pref 50000 u32 chain 0 fh 800::800 order 2048 key ht 800 bkt 0 flowid ???\n"
            + "  match c0a84d02/ffffffff at 16\n"
            + "  action order 1: police 0x1 rate 1Mbit burst 64Kb mtu 65535b action drop/pipe overhead 0b\n"
            + "  Sent 15000000 bytes 10000 pkt (dropped 4000, overlimits 4000 requeues 0)\n"
            + "  action order 2: police 0x2 rate 1Gbit burst 1Mb action continue/continue overhead 0b\n"
            + "  Sent 1000000 bytes 6000 pkt (dropped 0, overlimits 0 requeues 0)\n"
            + "  cookie len 16 " + COOKIE + " \n";

    @Test public void separateIpSamplesUseActualByteDeltasAndElapsedTime() {
        assertEquals(40.0, TrafficRules.mbps(1000, 10001000, 2000), 0.000001);
        assertEquals(0.1, TrafficRules.mbps(5000, 30000, 2000), 0.000001);
        assertEquals(1.0, TrafficRules.mbps(0, 375000, 3000), 0.000001);
    }

    @Test public void missingBaselineResetOrInvalidClockIsUnavailable() {
        assertTrue(Double.isNaN(TrafficRules.mbps(-1, 100, 2000)));
        assertTrue(Double.isNaN(TrafficRules.mbps(100, 10, 2000)));
        assertTrue(Double.isNaN(TrafficRules.mbps(100, 100, 0)));
        assertEquals(0, TrafficRules.mbps(100, 100, 2000), 0);
        assertTrue(TrafficRules.displayRate(Double.NaN, 1).contains("sampling"));
    }

    @Test public void countersMeasureAcceptedBytesAfterTheDroppingAction() {
        assertEquals(1000000, TrafficRules.acceptedBytes(FILTER));
        assertThrows(IllegalStateException.class, () -> TrafficRules.acceptedBytes("Sent 0 bytes 0 pkt"));
        assertThrows(IllegalStateException.class, () -> TrafficRules.acceptedBytes(FILTER + "Sent 1 bytes 1 pkt"));
    }

    @Test public void supportedOldAndNewTcCookieFormatsAreExact() {
        assertTrue(TrafficRules.hasCookie(FILTER, COOKIE));
        assertTrue(TrafficRules.hasCookie("  cookie " + COOKIE + "\n", COOKIE));
        assertFalse(TrafficRules.hasCookie("cookie " + COOKIE + "00", COOKIE));
        assertFalse(TrafficRules.hasCookie("cookie len 15 " + COOKIE, COOKIE));
        assertFalse(TrafficRules.hasCookie("", COOKIE));
    }

    @Test public void sharedPriorityCannotBeDeletedWithTheOwnedFilter() {
        assertTrue(TrafficRules.ownsPreference(FILTER, COOKIE));
        assertFalse(TrafficRules.ownsPreference(FILTER + "filter protocol ip pref 50000 u32 fh 800::801\n", COOKIE));
        assertFalse(TrafficRules.ownsPreference(FILTER.replace(COOKIE, "ffffffffffffffffffffffffffffffff"), COOKIE));
    }

    @Test public void sharedClsactDumpIsSplitByLeafCookieBeforeReadingCounters() {
        String other = "fedcba9876543210fedcba9876543210";
        String down = FILTER.replace(COOKIE, other).replace("800:", "801:").replace("1000000 bytes", "2000000 bytes");
        String both = "filter protocol ip pref 50000 u32 chain 0 fh 0:\n" + down + FILTER;
        assertThrows(IllegalStateException.class, () -> TrafficRules.acceptedBytes(both));
        assertEquals(1000000, TrafficRules.acceptedBytes(TrafficRules.ownedLeaf(both, COOKIE)));
        assertEquals(2000000, TrafficRules.acceptedBytes(TrafficRules.ownedLeaf(both, other)));
        assertEquals("800::800", TrafficRules.leafHandle(TrafficRules.ownedLeaf(both, COOKIE)));
        assertEquals("801::800", TrafficRules.leafHandle(TrafficRules.ownedLeaf(both, other)));
    }

    @Test public void sharedPriorityCleanupDeletesOnlyTheSelectedLeaf() {
        String other = FILTER.replace(COOKIE, "fedcba9876543210fedcba9876543210").replace("800:", "801:");
        String both = other + FILTER;
        assertFalse(TrafficRules.ownsPreference(both, COOKIE));
        String delete = TrafficRules.deleteLeaf("rndis0", "ingress", 50000, TrafficRules.ownedLeaf(both, COOKIE));
        assertEquals("tc filter del dev rndis0 ingress protocol ip pref 50000 handle 800::800 u32", delete);
        assertFalse(delete.contains("801::800"));
    }

    @Test public void missingAndDuplicateLeafOwnershipFailClosed() {
        assertThrows(IllegalStateException.class, () -> TrafficRules.ownedLeaf(FILTER, "ffffffffffffffffffffffffffffffff"));
        assertThrows(IllegalStateException.class, () -> TrafficRules.ownedLeaf(FILTER + FILTER.replace("800:", "801:"), COOKIE));
        assertEquals(1000000, TrafficRules.acceptedBytes(TrafficRules.ownedLeaf(FILTER.trim(), COOKIE)));
    }

    @Test public void directionsUseDifferentPrioritiesWithinEachReservedPair() {
        assertEquals(50000, TrafficRules.directionPref(50000, "ingress"));
        assertEquals(50001, TrafficRules.directionPref(50000, "egress"));
        assertEquals(50127, TrafficRules.directionPref(50126, "egress"));
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.directionPref(50001, "ingress"));
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.directionPref(50128, "egress"));
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.directionPref(50000, "root;id"));
    }

    @Test public void sharedTableHeadersAndOtherDirectionDoNotChangeAnOwnedFingerprint() {
        String other = FILTER.replace(COOKIE, "fedcba9876543210fedcba9876543210").replace("800:", "801:");
        assertEquals(TrafficRules.fingerprint(TrafficRules.ownedLeaf(FILTER, COOKIE)),
                TrafficRules.fingerprint(TrafficRules.ownedLeaf(other + FILTER, COOKIE)));
    }

    @Test public void preferencesKeepNativeBpfAndOtherFiltersSeparate() {
        String all = "filter protocol ip pref 3 bpf chain 0 handle 0x1\n  Sent 5 bytes 1 pkt\n"
                + FILTER + "filter protocol ip pref 50001 u32 fh 801::800\n";
        assertEquals(FILTER, TrafficRules.selectedFilter(all, 50000));
        assertEquals("", TrafficRules.selectedFilter(all, 50002));
    }

    @Test public void fingerprintsIgnoreCountersButDetectRuleChanges() {
        assertEquals(TrafficRules.fingerprint(FILTER), TrafficRules.fingerprint(FILTER.replace("15000000 bytes", "20000000 bytes")));
        assertNotEquals(TrafficRules.fingerprint(FILTER), TrafficRules.fingerprint(FILTER.replace("1Mbit", "2Mbit")));
        assertNotEquals(TrafficRules.fingerprint(FILTER), TrafficRules.fingerprint(FILTER.replace(COOKIE, "ffffffffffffffffffffffffffffffff")));
    }

    @Test public void limitsUseDecimalMbpsAndRejectInvalidOrUnsafeInput() {
        assertEquals(0, TrafficRules.parseMbps(" "));
        assertEquals(1000, TrafficRules.parseMbps("1"));
        assertEquals(100, TrafficRules.parseMbps("0.1"));
        assertEquals(1234, TrafficRules.parseMbps("1.234"));
        assertEquals(500000, TrafficRules.parseMbps("500"));
        for (String value : new String[] {"0", "-1", "0.001", "1.2345", "501", "NaN", "1; id"}) {
            assertThrows(IllegalArgumentException.class, () -> TrafficRules.parseMbps(value));
        }
    }

    @Test public void rulesBindBothIpAndMacInTheCorrectDirection() {
        TrafficRules.Client client = new TrafficRules.Client("ap0", "192.168.77.2", MAC);
        String up = TrafficRules.filter(client, "ingress", 50000, 1000, COOKIE);
        String down = TrafficRules.filter(client, "egress", 50000, 1000, COOKIE);
        assertTrue(up.contains("match ip src 192.168.77.2/32 match ether src " + MAC));
        assertTrue(down.contains("match ip dst 192.168.77.2/32 match ether dst " + MAC));
        assertTrue(up.contains("1000kbit burst 65536 mtu 65535 conform-exceed drop/pipe"));
        assertTrue(up.endsWith("continue/continue cookie " + COOKIE));
        assertFalse(TrafficRules.filter(client, "ingress", 50000, 0, COOKIE).contains("drop"));
    }

    @Test public void untrustedClientsCannotGenerateShellCommands() {
        for (String ip : new String[] {"192.168.77.1", "192.168.77.255", "192.168.77.02", "192.168.78.2", "192.168.77.2;id"}) {
            assertThrows(IllegalArgumentException.class, () -> new TrafficRules.Client("ap0", ip, MAC));
        }
        assertThrows(IllegalArgumentException.class, () -> new TrafficRules.Client("ap0;id", "192.168.77.2", MAC));
        assertThrows(IllegalArgumentException.class, () -> new TrafficRules.Client("ap0", "192.168.77.2", MAC + ";id"));
        TrafficRules.Client client = new TrafficRules.Client("rndis0", "192.168.78.2", MAC);
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.filter(client, "root", 50000, 1000, COOKIE));
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.filter(client, "egress", 3, 1000, COOKIE));
        assertThrows(IllegalArgumentException.class, () -> TrafficRules.filter(client, "egress", 50000, 1000, COOKIE + ";id"));
    }

    @Test public void neighborRowsNeedAValidMacAndManagedClientAddress() {
        String rows = "192.168.77.2 dev ap0 lladdr " + MAC + " REACHABLE\n"
                + "192.168.77.3 INCOMPLETE\n192.168.77.1 lladdr " + MAC + " STALE\n"
                + "192.168.77.4 lladdr ff:ff:ff:ff:ff:ff STALE\n";
        assertEquals(1, TrafficRules.neighbors("ap0", rows).size());
    }

    @Test public void offloadConfirmationFailsClosedForActiveOrUnknownPaths() {
        assertFalse(TrafficRules.offloadDisabled("Hardware offload:\nOffload HALs started\nmIsBpfEnabled: true"));
        assertFalse(TrafficRules.offloadDisabled("Hardware offload:\nOffload disabled\nmIsBpfEnabled: true"));
        assertFalse(TrafficRules.offloadDisabled("mIsBpfEnabled: false"));
        assertTrue(TrafficRules.offloadDisabled("Hardware offload:\nOffload disabled\nmIsBpfEnabled: false"));
    }

    @Test public void readinessRequiresARebootFlagsAndNativeConfirmationTogether() {
        String boot = "01234567-0123-0123-0123-012345678901";
        String previous = "01234567-0123-0123-0123-012345678902";
        String snapshot = "__HM_BOOT__" + boot + "\n__HM_HW__1\n__HM_BPF__false\n__HM_DUMP__\n"
                + "Hardware offload:\nOffload disabled\nmIsBpfEnabled: false\n";
        TrafficRules.verifyReadySnapshot(snapshot, previous);
        assertThrows(IllegalStateException.class, () -> TrafficRules.verifyReadySnapshot(snapshot, boot));
        assertThrows(IllegalStateException.class, () -> TrafficRules.verifyReadySnapshot(snapshot.replace("__HM_HW__1", "__HM_HW__0"), previous));
        assertThrows(IllegalStateException.class, () -> TrafficRules.verifyReadySnapshot(snapshot.replace("__HM_BPF__false", "__HM_BPF__true"), previous));
        assertThrows(IllegalStateException.class, () -> TrafficRules.verifyReadySnapshot(snapshot.replace("mIsBpfEnabled: false", "mIsBpfEnabled: true"), previous));
        assertThrows(IllegalStateException.class, () -> TrafficRules.verifyReadySnapshot("Permission denied", previous));
        assertThrows(IllegalStateException.class, () -> TrafficRules.verifyReadySnapshot(snapshot, ""));
    }
}
