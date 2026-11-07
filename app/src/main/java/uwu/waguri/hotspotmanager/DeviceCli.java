package uwu.waguri.hotspotmanager;

import android.content.Context;
import android.net.MacAddress;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Root-only native device inspection and capability-gated Wi-Fi MAC blocking. */
public final class DeviceCli {
    static final String PREFIX = "__HOTSPOT_DEVICE_RESULT__";
    private static final Executor DIRECT = Runnable::run;

    public static void main(String[] args) {
        JSONObject result = new JSONObject();
        int code = 0;
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root access is required.");
            if (args.length != 2 || !("inspect".equals(args[0])
                    || "block".equals(args[0]) || "unblock".equals(args[0]))) {
                throw new IllegalArgumentException("Usage: inspect|block|unblock MAC");
            }
            String mac = DeviceProfile.normalizeMac(args[1]);
            Context context = TetheringCli.systemContext();
            result.put("mac", mac);
            if ("inspect".equals(args[0])) {
                try { result.put("client", readClient(context, mac)); }
                catch (Exception error) { result.put("clientError", message(error)); }
                try { result.put("wifi", inspectWifi(context, mac)); }
                catch (Exception error) { result.put("wifiError", message(error)); }
            } else {
                changeBlock(context, mac, "block".equals(args[0]));
                result.put("blocked", "block".equals(args[0]));
            }
            result.put("ok", true);
        } catch (Throwable error) {
            code = 1;
            try { result.put("ok", false); result.put("error", message(error)); }
            catch (Exception ignored) { }
        }
        System.out.println(PREFIX + result);
        System.out.flush();
        System.exit(code);
    }

    private static JSONObject readClient(Context context, String mac) throws Exception {
        Class<?> managerType = Class.forName("android.net.TetheringManager");
        Object manager = context.getSystemService(managerType);
        if (manager == null) throw new IllegalStateException("Tethering service is unavailable.");
        Class<?> callbackType = Class.forName("android.net.TetheringManager$TetheringEventCallback");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Collection<?>> clients = new AtomicReference<>();
        Object callback = callback(callbackType, (proxy, method, args) -> {
            if ("onClientsChanged".equals(method.getName()) && args[0] instanceof Collection<?>) {
                clients.set(new ArrayList<>((Collection<?>) args[0]));
                ready.countDown();
            }
            return null;
        });
        managerType.getMethod("registerTetheringEventCallback", Executor.class, callbackType)
                .invoke(manager, DIRECT, callback);
        try {
            if (!ready.await(4, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Android did not report tethered-client metadata.");
            }
            JSONObject result = new JSONObject();
            result.put("reported", false);
            JSONArray addresses = new JSONArray();
            result.put("addresses", addresses);
            for (Object client : clients.get()) {
                Class<?> type = client.getClass();
                if (!mac.equalsIgnoreCase(String.valueOf(type.getMethod("getMacAddress").invoke(client)))) continue;
                result.put("reported", true);
                for (Object address : (Collection<?>) type.getMethod("getAddresses").invoke(client)) {
                    Class<?> info = address.getClass();
                    Object link = info.getMethod("getAddress").invoke(address);
                    JSONObject item = new JSONObject();
                    item.put("address", String.valueOf(link));
                    Object hostname = info.getMethod("getHostname").invoke(address);
                    if (hostname != null) item.put("hostname", String.valueOf(hostname));
                    try {
                        long expires = ((Number) info.getMethod("getExpirationTime").invoke(address)).longValue();
                        if (expires > 0 && expires < Long.MAX_VALUE) {
                            item.put("expiresAt", System.currentTimeMillis()
                                    + (expires - SystemClock.elapsedRealtime()));
                        }
                    } catch (ReflectiveOperationException ignored) {
                        // Lease expiration is optional; it is never used as a connection timestamp.
                    }
                    addresses.put(item);
                }
            }
            return result;
        } finally {
            managerType.getMethod("unregisterTetheringEventCallback", callbackType).invoke(manager, callback);
        }
    }

    private static JSONObject inspectWifi(Context context, String mac) throws Exception {
        Object wifi = wifiManager(context);
        Object config = wifi.getClass().getMethod("getSoftApConfiguration").invoke(wifi);
        if (config == null) throw new IllegalStateException("Saved hotspot configuration is unavailable.");
        JSONObject result = new JSONObject();
        result.put("blocked", blockedMacs(config).contains(mac));
        result.put("blockSupported", supportsBlocking(wifi));
        return result;
    }

    private static void changeBlock(Context context, String mac, boolean blocked) throws Exception {
        Object wifi = wifiManager(context);
        Class<?> configType = Class.forName("android.net.wifi.SoftApConfiguration");
        Object config = wifi.getClass().getMethod("getSoftApConfiguration").invoke(wifi);
        if (config == null) throw new IllegalStateException("Saved hotspot configuration is unavailable.");
        List<String> before = blockedMacs(config);
        if (before.contains(mac) == blocked) return;
        if (blocked && !supportsBlocking(wifi)) {
            throw new UnsupportedOperationException("This firmware does not report native Wi-Fi client blocking support.");
        }
        if (blocked) {
            for (Object allowed : (Collection<?>) configType.getMethod("getAllowedClientList").invoke(config)) {
                if (mac.equalsIgnoreCase(String.valueOf(allowed))) {
                    throw new IllegalStateException("This MAC is in Android's allowed-client list. Remove that entry in hotspot settings first.");
                }
            }
        }
        List<MacAddress> updated = new ArrayList<>();
        for (String value : DeviceProfile.updateBlocked(before, mac, blocked)) {
            updated.add(MacAddress.fromString(value));
        }
        Class<?> builderType = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Object builder = builderType.getConstructor(configType).newInstance(config);
        builderType.getMethod("setBlockedClientList", List.class).invoke(builder, updated);
        Object replacement = builderType.getMethod("build").invoke(builder);
        boolean accepted = (Boolean) wifi.getClass().getMethod("setSoftApConfiguration", configType)
                .invoke(wifi, replacement);
        if (!accepted) throw new IllegalStateException("Android rejected the hotspot blocklist update.");
        Object verified = wifi.getClass().getMethod("getSoftApConfiguration").invoke(wifi);
        if (verified == null || blockedMacs(verified).contains(mac) != blocked) {
            throw new IllegalStateException("Android did not retain the requested blocklist entry.");
        }
    }

    private static Object wifiManager(Context context) {
        Object wifi = context.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) throw new IllegalStateException("Wi-Fi service is unavailable.");
        return wifi;
    }

    private static List<String> blockedMacs(Object config) throws Exception {
        List<String> result = new ArrayList<>();
        for (Object value : (Collection<?>) config.getClass().getMethod("getBlockedClientList").invoke(config)) {
            result.add(String.valueOf(value).toLowerCase(java.util.Locale.ROOT));
        }
        return result;
    }

    private static boolean supportsBlocking(Object wifi) throws Exception {
        Class<?> callbackType = Class.forName("android.net.wifi.WifiManager$SoftApCallback");
        Class<?> capabilityType = Class.forName("android.net.wifi.SoftApCapability");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Object> capability = new AtomicReference<>();
        Object callback = callback(callbackType, (proxy, method, args) -> {
            if ("onCapabilityChanged".equals(method.getName()) && args[0] != null) {
                capability.set(args[0]);
                ready.countDown();
            }
            return null;
        });
        wifi.getClass().getMethod("registerSoftApCallback", Executor.class, callbackType)
                .invoke(wifi, DIRECT, callback);
        try {
            if (!ready.await(3, TimeUnit.SECONDS)) return false;
            long feature = capabilityType.getField("SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT").getLong(null);
            return (Boolean) capabilityType.getMethod("areFeaturesSupported", long.class)
                    .invoke(capability.get(), feature);
        } finally {
            wifi.getClass().getMethod("unregisterSoftApCallback", callbackType).invoke(wifi, callback);
        }
    }

    private static Object callback(Class<?> type, InvocationHandler delegate) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "HotspotManagerDeviceCallback";
                default: return delegate.invoke(proxy, method, args);
            }
        });
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage());
    }

    private DeviceCli() {}
}
