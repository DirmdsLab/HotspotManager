package uwu.waguri.hotspotmanager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class TetheringControlTest {
    private final FakeClock clock = new FakeClock();
    private final FakeBackend backend = new FakeBackend();

    @Test
    public void statusNeverChangesEitherInterface() throws Exception {
        backend.state = state(true, true);
        TetheringControl.State result = execute("status");
        assertTrue(result.hotspot);
        assertTrue(result.usb);
        assertEquals(0, backend.changes);
    }

    @Test
    public void enablingUsbPreservesAnActiveHotspot() throws Exception {
        backend.state = state(true, false);
        TetheringControl.State result = execute("usb", "on");
        assertTrue(result.hotspot);
        assertTrue(result.usb);
        assertEquals(TetheringControl.Target.USB, backend.target);
        assertEquals(1, backend.changes);
    }

    @Test
    public void stoppingHotspotPreservesUsb() throws Exception {
        backend.state = state(true, true);
        TetheringControl.State result = execute("hotspot", "off");
        assertFalse(result.hotspot);
        assertTrue(result.usb);
        assertEquals(TetheringControl.Target.HOTSPOT, backend.target);
    }

    @Test
    public void alreadyEnabledDoesNotRestartTheHotspot() throws Exception {
        backend.state = state(true, false);
        execute("hotspot", "on");
        assertEquals(0, backend.changes);
    }

    @Test
    public void alreadyDisabledDoesNotIssueAStopRequest() throws Exception {
        execute("usb", "off");
        assertEquals(0, backend.changes);
    }

    @Test
    public void nativeAcknowledgmentIsNotTreatedAsAnActiveInterface() throws Exception {
        backend.delay = 1000;
        TetheringControl.State result = execute("usb", "on");
        assertTrue(result.usb);
        assertEquals(1000L, clock.now);
        assertTrue(backend.reads > 2);
    }

    @Test
    public void missingUsbCableTimesOutInsteadOfReportingSuccess() {
        backend.delay = Long.MAX_VALUE;
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> execute("usb", "on"));
        assertTrue(error.getMessage().contains("USB data cable"));
        assertEquals(15000L, clock.now);
        assertFalse(backend.state.usb);
        assertEquals(1, backend.changes);
    }

    @Test
    public void stoppingAlsoWaitsForActualInterfaceRemoval() throws Exception {
        backend.state = state(true, true);
        backend.delay = 600;
        assertFalse(execute("usb", "off").usb);
        assertEquals(600L, clock.now);
        assertTrue(backend.state.hotspot);
    }

    @Test
    public void nativePermissionFailureIsPropagated() {
        backend.reject = true;
        SecurityException error = assertThrows(SecurityException.class,
                () -> execute("hotspot", "on"));
        assertEquals("Permission denied", error.getMessage());
        assertEquals(0, clock.now);
        assertFalse(backend.state.hotspot);
    }

    @Test
    public void unknownTargetsAndShellInputAreRejected() {
        for (String target : new String[] {"wifi", "", "usb; id", "$(id)", "hotspot on"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TetheringControl.Request.parse(target, "on"));
        }
    }

    @Test
    public void unknownActionsAndMissingArgumentsAreRejected() {
        for (String action : new String[] {"enable", "toggle", "on; id", "", "ON"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TetheringControl.Request.parse("usb", action));
        }
        assertThrows(IllegalArgumentException.class, () -> TetheringControl.Request.parse());
        assertThrows(IllegalArgumentException.class, () -> TetheringControl.Request.parse("on"));
    }

    @Test
    public void supportedCommandsRoundTripWithoutAddingShellSyntax() {
        for (String target : new String[] {"hotspot", "usb"}) {
            for (String action : new String[] {"on", "off", "status"}) {
                assertArrayEquals(new String[] {target, action},
                        TetheringControl.Request.parse(target, action).arguments());
            }
        }
        assertArrayEquals(new String[] {"status"}, TetheringControl.Request.parse("status").arguments());
    }

    @Test
    public void interruptedConfirmationDoesNotReturnSuccess() {
        backend.delay = 1000;
        clock.interrupt = true;
        assertThrows(InterruptedException.class, () -> execute("hotspot", "on"));
        assertFalse(backend.state.hotspot);
    }

    @Test
    public void snapshotCannotBeChangedByTheBackendAfterPublication() {
        List<String> interfaces = new ArrayList<>();
        interfaces.add("rndis0");
        TetheringControl.State snapshot = new TetheringControl.State(false, true, interfaces);
        interfaces.clear();
        assertEquals(Collections.singletonList("rndis0"), snapshot.interfaces);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.interfaces.clear());
    }

    private TetheringControl.State execute(String... args) throws Exception {
        return TetheringControl.execute(TetheringControl.Request.parse(args), backend, clock);
    }

    private static TetheringControl.State state(boolean hotspot, boolean usb) {
        return new TetheringControl.State(hotspot, usb, Collections.emptyList());
    }

    private final class FakeBackend implements TetheringControl.Backend {
        TetheringControl.State state = state(false, false);
        TetheringControl.Target target;
        boolean enabled;
        boolean reject;
        int changes;
        int reads;
        long delay;
        long changedAt;

        public TetheringControl.State readState() {
            reads++;
            if (target != null && clock.now - changedAt >= delay) {
                state = target == TetheringControl.Target.USB
                        ? state(state.hotspot, enabled) : state(enabled, state.usb);
            }
            return state;
        }

        public void setEnabled(TetheringControl.Target target, boolean enabled) {
            if (reject) throw new SecurityException("Permission denied");
            this.target = target;
            this.enabled = enabled;
            changedAt = clock.now;
            changes++;
        }
    }

    private static final class FakeClock implements TetheringControl.Clock {
        long now;
        boolean interrupt;
        public long now() { return now; }
        public void pause(long milliseconds) throws InterruptedException {
            if (interrupt) throw new InterruptedException();
            now += milliseconds;
        }
    }
}
