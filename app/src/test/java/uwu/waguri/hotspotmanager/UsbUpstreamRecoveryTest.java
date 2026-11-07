package uwu.waguri.hotspotmanager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public final class UsbUpstreamRecoveryTest {
    private final UsbUpstreamRecovery recovery = new UsbUpstreamRecovery();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final Object owner = new Object();
    private final Object usb = new Object();
    private final List<Integer> attempts = new ArrayList<>();
    private final AtomicBoolean ready = new AtomicBoolean();

    private void start() {
        recovery.start(owner, usb, scheduler, attempts::add, ready::get);
    }

    @Test
    public void usbFirstRetriesAtBoundedStartupTimes() {
        start();
        scheduler.advanceTo(2499);
        assertTrue(attempts.isEmpty());
        scheduler.advanceTo(2500);
        assertEquals(List.of(1), attempts);
        scheduler.advanceTo(5000);
        assertEquals(List.of(1, 2), attempts);
        scheduler.advanceTo(10000);
        assertEquals(List.of(1, 2, 3), attempts);
        scheduler.advanceTo(60000);
        assertEquals(3, attempts.size());
        assertTrue(scheduler.queue.isEmpty());
    }

    @Test
    public void existingUpstreamStillGetsOneDnsAndForwardingRefresh() {
        ready.set(true);
        start();
        scheduler.advanceTo(60000);
        assertEquals(List.of(1), attempts);
        assertTrue(scheduler.queue.isEmpty());
    }

    @Test
    public void upstreamBecomingReadyStopsFurtherRetries() {
        start();
        scheduler.advanceTo(2500);
        ready.set(true);
        scheduler.advanceTo(60000);
        assertEquals(List.of(1), attempts);
    }

    @Test
    public void repeatedActiveNotificationsDoNotRestartTheSequence() {
        start();
        start();
        assertEquals(1, scheduler.queue.size());
        scheduler.advanceTo(60000);
        start();
        scheduler.advanceTo(120000);
        assertEquals(List.of(1, 2, 3), attempts);
        assertTrue(scheduler.queue.isEmpty());
    }

    @Test
    public void usbDisabledBeforeFirstRetryCancelsPendingWork() {
        start();
        recovery.stop(usb);
        scheduler.advanceTo(60000);
        assertTrue(attempts.isEmpty());
        assertTrue(scheduler.queue.isEmpty());
    }

    @Test
    public void disablingWifiDoesNotCancelUsbRecovery() {
        start();
        recovery.stop(new Object());
        scheduler.advanceTo(2500);
        assertEquals(List.of(1), attempts);
    }

    @Test
    public void reconnectStartsFreshAndIgnoresStaleCallbacks() {
        start();
        Runnable stale = scheduler.queue.get(0).task;
        scheduler.advanceTo(2500);
        recovery.stop(usb);
        start();
        stale.run();
        assertEquals(List.of(1), attempts);
        scheduler.advanceTo(5000);
        assertEquals(List.of(1, 1), attempts);
    }

    @Test
    public void replacementDownstreamCancelsPreviousSession() {
        start();
        Runnable stale = scheduler.queue.get(0).task;
        Object replacement = new Object();
        recovery.start(owner, replacement, scheduler, attempts::add, ready::get);
        recovery.stop(usb);
        stale.run();
        scheduler.advanceTo(2500);
        assertEquals(List.of(1), attempts);
        assertEquals(1, scheduler.queue.size());
    }

    @Test
    public void rejectedScheduleCanBeRetriedByANewActiveNotification() {
        scheduler.accept = false;
        start();
        scheduler.accept = true;
        start();
        scheduler.advanceTo(2500);
        assertEquals(List.of(1), attempts);
    }

    @Test
    public void stoppingDuringRetryDoesNotScheduleMoreWork() {
        recovery.start(owner, usb, scheduler, attempt -> {
            attempts.add(attempt);
            recovery.stop(usb);
        }, ready::get);
        scheduler.advanceTo(60000);
        assertEquals(List.of(1), attempts);
        assertTrue(scheduler.queue.isEmpty());
    }

    private static final class FakeScheduler implements UsbUpstreamRecovery.Scheduler {
        private static final class Entry {
            final Runnable task;
            final long time;

            Entry(Runnable task, long time) {
                this.task = task;
                this.time = time;
            }
        }

        final List<Entry> queue = new ArrayList<>();
        long now;
        boolean accept = true;

        @Override
        public boolean postDelayed(Runnable task, long delayMs) {
            if (!accept) return false;
            queue.add(new Entry(task, now + delayMs));
            return true;
        }

        @Override
        public void removeCallbacks(Runnable task) {
            queue.removeIf(entry -> entry.task == task);
        }

        void advanceTo(long time) {
            while (!queue.isEmpty() && queue.get(0).time <= time) {
                Entry entry = queue.remove(0);
                now = entry.time;
                entry.task.run();
            }
            now = time;
        }
    }
}
