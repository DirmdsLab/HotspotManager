package uwu.waguri.hotspotmanager;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** A bounded, session-scoped retry sequence confined to the tethering handler. */
final class UsbUpstreamRecovery {
    interface Scheduler {
        boolean postDelayed(Runnable task, long delayMs);
        void removeCallbacks(Runnable task);
    }

    // Retry at 2.5, 5, and 10 seconds after USB becomes tethered.
    private static final long[] DELAYS_MS = {2500L, 2500L, 5000L};
    private Session current;

    void start(Object owner, Object downstream, Scheduler scheduler,
            IntConsumer retry, BooleanSupplier hasUpstream) {
        if (current != null && current.owner == owner && current.downstream == downstream) return;
        if (current != null) current.cancel();
        current = new Session(owner, downstream, scheduler, retry, hasUpstream);
        current.schedule();
    }

    void stop(Object downstream) {
        if (current == null || current.downstream != downstream) return;
        current.cancel();
        current = null;
    }

    private final class Session implements Runnable {
        final Object owner;
        final Object downstream;
        final Scheduler scheduler;
        final IntConsumer retry;
        final BooleanSupplier hasUpstream;
        int attempts;

        Session(Object owner, Object downstream, Scheduler scheduler,
                IntConsumer retry, BooleanSupplier hasUpstream) {
            this.owner = owner;
            this.downstream = downstream;
            this.scheduler = scheduler;
            this.retry = retry;
            this.hasUpstream = hasUpstream;
        }

        void schedule() {
            if (!scheduler.postDelayed(this, DELAYS_MS[attempts])) {
                if (current == this) current = null;
            }
        }

        void cancel() {
            scheduler.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (current != this) return;
            // Always perform the first refresh, even if upstream selection looks complete.
            // Further attempts are only needed while the upstream is still unavailable.
            if (attempts > 0 && hasUpstream.getAsBoolean()) return;
            retry.accept(++attempts);
            if (current == this && attempts < DELAYS_MS.length) schedule();
        }
    }
}
