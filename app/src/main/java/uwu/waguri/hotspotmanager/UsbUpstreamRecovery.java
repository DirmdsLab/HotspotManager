package uwu.waguri.hotspotmanager;

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
            IntConsumer retry) {
        if (current != null && current.owner == owner && current.downstream == downstream) return;
        if (current != null) current.cancel();
        current = new Session(owner, downstream, scheduler, retry);
        current.schedule();
    }

    boolean rebindOnce(Object owner, Object downstream, Runnable rebind) {
        Session session = current;
        if (session == null || session.owner != owner || session.downstream != downstream
                || session.attempts == 0 || session.rebound) return false;
        rebind.run();
        session.rebound = true;
        return true;
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
        int attempts;
        boolean rebound;

        Session(Object owner, Object downstream, Scheduler scheduler,
                IntConsumer retry) {
            this.owner = owner;
            this.downstream = downstream;
            this.scheduler = scheduler;
            this.retry = retry;
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
            // A selected upstream is not proof that USB forwarding/NAT is installed.
            retry.accept(++attempts);
            if (current == this && attempts < DELAYS_MS.length) schedule();
        }
    }
}
