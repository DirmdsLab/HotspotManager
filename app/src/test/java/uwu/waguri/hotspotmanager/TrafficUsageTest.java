package uwu.waguri.hotspotmanager;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TrafficUsageTest {
    @Test public void repeatedCheckpointsAndRestartBaselinesDoNotDoubleCount() {
        TrafficUsage total = new TrafficUsage(0,0).add(false,TrafficUsage.delta(0,5000));
        total = total.add(false,TrafficUsage.delta(5000,5000));
        total = total.add(false,TrafficUsage.delta(5000,8000));
        assertEquals(8000,total.down); assertEquals(0,total.up);
    }
    @Test public void resetCountersKeepCollectedTotalsAndStartANewDelta() {
        TrafficUsage total = new TrafficUsage(8000,700).add(false,TrafficUsage.delta(8000,300));
        assertEquals(8300,total.down); assertEquals(700,total.up);
    }
    @Test public void explicitUsageResetSkipsTheOldCounterBaseline() {
        assertEquals(0,TrafficUsage.delta(-1,999999));
        assertEquals(1,TrafficUsage.delta(999999,1000000));
    }
    @Test public void directionsAndMacTotalsRemainIndependent() {
        TrafficUsage first = new TrafficUsage(0,0).add(false,1000).add(true,20);
        TrafficUsage second = new TrafficUsage(0,0).add(false,50000);
        assertEquals(1000,first.down); assertEquals(20,first.up); assertEquals(50000,second.down);
    }
    @Test public void negativeCountersAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class,()->TrafficUsage.delta(0,-1));
        assertThrows(IllegalArgumentException.class,()->new TrafficUsage(-1,0));
        assertThrows(ArithmeticException.class,()->new TrafficUsage(Long.MAX_VALUE,0).add(false,1));
    }
    @Test public void byteLabelsUseExplicitBinaryUnits() {
        assertEquals("1.00 MiB",TrafficUsage.bytes(1048576));
        assertEquals("1.00 GiB",TrafficUsage.bytes(1073741824));
        assertEquals("0 B",TrafficUsage.bytes(0));
    }
}
