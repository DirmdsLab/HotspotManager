package uwu.waguri.hotspotmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validated commands and bounded confirmation of the actual downstream state. */
final class TetheringControl {
    static final String RESULT_PREFIX = "__HOTSPOT_MANAGER_RESULT__";
    static final String RECEIVER = "uwu.waguri.hotspotmanager/.TetheringReceiver";

    enum Target { HOTSPOT, USB }
    enum Action { ON, OFF, STATUS }

    static final class Request {
        final Target target;
        final Action action;

        Request(Target target, Action action) {
            this.target = target;
            this.action = action;
        }

        static Request parse(String... args) {
            if (args.length == 1 && "status".equals(args[0])) {
                return new Request(null, Action.STATUS);
            }
            if (args.length != 2) throw new IllegalArgumentException(usage());
            Target target;
            if ("hotspot".equals(args[0])) target = Target.HOTSPOT;
            else if ("usb".equals(args[0])) target = Target.USB;
            else throw new IllegalArgumentException(usage());
            Action action;
            if ("on".equals(args[1])) action = Action.ON;
            else if ("off".equals(args[1])) action = Action.OFF;
            else if ("status".equals(args[1])) action = Action.STATUS;
            else throw new IllegalArgumentException(usage());
            return new Request(target, action);
        }

        String[] arguments() {
            if (target == null) return new String[] {"status"};
            return new String[] {target == Target.USB ? "usb" : "hotspot",
                    action == Action.ON ? "on" : action == Action.OFF ? "off" : "status"};
        }

        static String usage() {
            return "Usage: status | hotspot on|off|status | usb on|off|status";
        }
    }

    static final class State {
        final boolean hotspot;
        final boolean usb;
        final List<String> interfaces;

        State(boolean hotspot, boolean usb, List<String> interfaces) {
            this.hotspot = hotspot;
            this.usb = usb;
            this.interfaces = Collections.unmodifiableList(new ArrayList<>(interfaces));
        }

        boolean enabled(Target target) {
            return target == Target.USB ? usb : hotspot;
        }
    }

    interface Backend {
        State readState() throws Exception;
        void setEnabled(Target target, boolean enabled) throws Exception;
    }

    interface Clock {
        long now();
        void pause(long milliseconds) throws InterruptedException;
    }

    static State execute(Request request, Backend backend, Clock clock) throws Exception {
        State state = backend.readState();
        if (request.action == Action.STATUS) return state;
        boolean desired = request.action == Action.ON;
        if (state.enabled(request.target) == desired) return state;
        backend.setEnabled(request.target, desired);
        long deadline = clock.now() + 15000L;
        while (true) {
            state = backend.readState();
            if (state.enabled(request.target) == desired) return state;
            if (clock.now() >= deadline) {
                throw new IllegalStateException(request.target == Target.USB && desired
                        ? "USB tethering did not become active. Connect a USB data cable and retry."
                        : "Tethering did not reach the requested state within 15 seconds.");
            }
            clock.pause(200L);
        }
    }

    private TetheringControl() {}
}
