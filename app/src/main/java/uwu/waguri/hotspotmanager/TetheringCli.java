package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Root-only app_process entry point shared by the GUI and terminal receiver. */
public final class TetheringCli {
    public static void main(String[] args) {
        int exitCode = 0;
        JSONObject result = new JSONObject();
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root access is required.");
            TetheringControl.Request request = TetheringControl.Request.parse(args);
            NativeBackend backend = new NativeBackend(systemContext());
            TetheringControl.State state = TetheringControl.execute(request, backend,
                    new TetheringControl.Clock() {
                        public long now() { return SystemClock.elapsedRealtime(); }
                        public void pause(long milliseconds) throws InterruptedException {
                            Thread.sleep(milliseconds);
                        }
                    });
            result.put("ok", true);
            result.put("hotspot", state.hotspot);
            result.put("usb", state.usb);
            result.put("interfaces", new JSONArray(state.interfaces));
        } catch (Throwable error) {
            exitCode = 1;
            Throwable cause = error;
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            try {
                result.put("ok", false);
                result.put("error", cause.getClass().getSimpleName() + ": "
                        + String.valueOf(cause.getMessage()));
            } catch (Exception ignored) { }
        }
        System.out.println(TetheringControl.RESULT_PREFIX + result);
        System.out.flush();
        System.exit(exitCode);
    }

    static Context systemContext() throws Exception {
        // ActivityThread uses this thread's looper for the system Context.
        // Tethering callbacks use explicit executors and do not need a process main looper.
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Method systemMain = threadClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getContext = threadClass.getDeclaredMethod("getSystemContext");
        getContext.setAccessible(true);
        return (Context) getContext.invoke(thread);
    }

    private static final class NativeBackend implements TetheringControl.Backend {
        final Object manager;
        final Class<?> managerClass;
        final Method getIfaces;
        final String[] wifiRegexes;
        final String[] usbRegexes;

        NativeBackend(Context context) throws Exception {
            managerClass = Class.forName("android.net.TetheringManager");
            manager = context.getSystemService(managerClass);
            if (manager == null) throw new IllegalStateException("Android tethering service is unavailable.");
            // Do not report both switches as off if the service never supplies an initial state.
            CountDownLatch initialState = new CountDownLatch(1);
            Class<?> events = Class.forName("android.net.TetheringManager$TetheringEventCallback");
            Object observer = Proxy.newProxyInstance(events.getClassLoader(), new Class<?>[] {events},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "onTetheredInterfacesChanged": initialState.countDown(); return null;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == arguments[0];
                            case "toString": return "HotspotManagerStateCallback";
                            default: return null;
                        }
                    });
            managerClass.getMethod("registerTetheringEventCallback", Executor.class, events)
                    .invoke(manager, (Executor) Runnable::run, observer);
            if (!initialState.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Android did not provide the initial tethering state.");
            }
            getIfaces = managerClass.getMethod("getTetheredIfaces");
            wifiRegexes = (String[]) managerClass.getMethod("getTetherableWifiRegexs").invoke(manager);
            usbRegexes = (String[]) managerClass.getMethod("getTetherableUsbRegexs").invoke(manager);
        }

        @Override
        public TetheringControl.State readState() throws Exception {
            String[] interfaces = (String[]) getIfaces.invoke(manager);
            boolean hotspot = false;
            boolean usb = false;
            for (String iface : interfaces) {
                hotspot |= "ap0".equals(iface) || matches(iface, wifiRegexes);
                usb |= "rndis0".equals(iface) || matches(iface, usbRegexes);
            }
            return new TetheringControl.State(hotspot, usb, Arrays.asList(interfaces));
        }

        @Override
        public void setEnabled(TetheringControl.Target target, boolean enabled) throws Exception {
            int type = managerClass.getField(target == TetheringControl.Target.USB
                    ? "TETHERING_USB" : "TETHERING_WIFI").getInt(null);
            if (!enabled) {
                // The type-based API also stops sessions started by Android Settings.
                managerClass.getMethod("stopTethering", int.class).invoke(manager, type);
                return;
            }
            Class<?> callbackClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
            CountDownLatch completion = new CountDownLatch(1);
            AtomicInteger code = new AtomicInteger(-1);
            Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                    new Class<?>[] {callbackClass}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "onTetheringStarted":
                                code.set(0);
                                completion.countDown();
                                return null;
                            case "onTetheringFailed":
                                code.set((Integer) arguments[0]);
                                completion.countDown();
                                return null;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == arguments[0];
                            case "toString": return "HotspotManagerStartCallback";
                            default: return null;
                        }
                    });
            Executor direct = Runnable::run;
            // The type-based overload builds a native request using the saved hotspot configuration.
            managerClass.getMethod("startTethering", int.class, Executor.class, callbackClass)
                    .invoke(manager, type, direct, callback);
            if (!completion.await(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Android did not acknowledge the tethering request.");
            }
            if (code.get() != 0) {
                throw new IllegalStateException("Android rejected the tethering request: error=" + code.get());
            }
        }

        private static boolean matches(String iface, String[] regexes) {
            for (String regex : regexes) if (iface.matches(regex)) return true;
            return false;
        }
    }

    private TetheringCli() {}
}
