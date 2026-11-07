package uwu.waguri.hotspotmanager;

import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.MacAddress;
import android.content.Context;
import android.provider.Settings;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public final class HotspotProbe extends XposedModule {
    private static final String TAG = "WaguriHotspotPatch";

    private static final String TETHER_PKG = "com.android.networkstack.tethering";
    private static final String STACK_PKG = "com.android.networkstack";

    private static final String IP_SERVER = "android.net.ip.IpServer";
    private static final String LEASE_REPOSITORY =
            "com.android.networkstack.android.net.dhcp.DhcpLeaseRepository";

    private static final String CONFIG_KEY = "waguri_hotspot_reservations_v1";
    private static volatile Context cachedContext;
    private static final ConcurrentHashMap<String, ReservationSnapshot> RESERVATION_CACHE =
            new ConcurrentHashMap<>();
    private final UsbUpstreamRecovery usbRecovery = new UsbUpstreamRecovery();

    private static final class ReservationSnapshot {
        final String raw;
        final Map<String, Integer> reservations;

        ReservationSnapshot(String raw, Map<String, Integer> reservations) {
            this.raw = raw;
            this.reservations = Collections.unmodifiableMap(new HashMap<>(reservations));
        }
    }

    private static final int TARGET_MASK = 0xFFFFFF00;
    private static final int POOL_START = 100;
    private static final int POOL_END = 200;

    // Independent downstream networks. Each has its own gateway and DHCP pool.
    private static final TargetConfig WIFI = new TargetConfig(
            "ap0", 0xC0A84D00, 0xC0A84D01, "192.168.77.0/24");
    private static final TargetConfig USB = new TargetConfig(
            "rndis0", 0xC0A84E00, 0xC0A84E01, "192.168.78.0/24");
    private static final TargetConfig[] TARGETS = new TargetConfig[] { WIFI, USB };

    // Per-interface state: original Android prefix and whether the replacement was applied.
    private static final ConcurrentHashMap<String, String> ORIGINAL_PREFIXES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> GATEWAY_APPLIED =
            new ConcurrentHashMap<>();

    // Advertised to all IPv4 tethering clients through DHCP option 6.
    private static final Inet4Address DNS_PRIMARY = ipv4FromOctets(1, 1, 1, 1);
    private static final Inet4Address DNS_SECONDARY = ipv4FromOctets(8, 8, 8, 8);

    private static final class TargetConfig {
        final String iface;
        final int network;
        final int mask = TARGET_MASK;
        final int gateway;
        final String prefixString;
        final IpPrefix prefix;

        TargetConfig(String iface, int network, int gateway, String prefixString) {
            this.iface = iface;
            this.network = network;
            this.gateway = gateway;
            this.prefixString = prefixString;
            this.prefix = createPrefix(network);
        }
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Log.i(TAG, "MODULE_LOADED process=" + param.getProcessName()
                + " framework=" + getFrameworkName()
                + " frameworkVersion=" + getFrameworkVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        final String packageName = param.getPackageName();
        final ClassLoader loader = param.getDefaultClassLoader();

        if (TETHER_PKG.equals(packageName)) {
            installGatewayHook(loader);
            installDnsHook(loader);
            installUsbUpstreamRecoveryHook(loader);
        } else if (STACK_PKG.equals(packageName)) {
            installDhcpHooks(loader);
        }
    }

    /**
     * Recover USB-first startup through Android's own upstream-selection state machine.
     * All callbacks run on the native state-machine handler.
     */
    private void installUsbUpstreamRecoveryHook(ClassLoader loader) {
        try {
            Class<?> tetheringClass = Class.forName(
                    "com.android.networkstack.tethering.Tethering", false, loader);
            Class<?> stateMachineClass = Class.forName(
                    "com.android.networkstack.tethering.Tethering$TetherMainSM", false, loader);
            Class<?> ipServerClass = Class.forName(IP_SERVER, false, loader);

            // These methods belong to TetherMainSM, not the outer Tethering class.
            Method servingActive = stateMachineClass.getDeclaredMethod(
                    "handleInterfaceServingStateActive", int.class, ipServerClass);
            Method servingInactive = stateMachineClass.getDeclaredMethod(
                    "handleInterfaceServingStateInactive", ipServerClass);
            Method interfaceName = ipServerClass.getDeclaredMethod("interfaceName");
            Method getHandler = stateMachineClass.getMethod("getHandler");
            Field retryField = stateMachineClass.getDeclaredField("CMD_RETRY_UPSTREAM");
            Field outerField = stateMachineClass.getDeclaredField("this$0");
            Field upstreamField = tetheringClass.getDeclaredField("mTetherUpstream");
            Field upstreamIfacesField = tetheringClass.getDeclaredField("mCurrentUpstreamIfaceSet");
            servingActive.setAccessible(true);
            servingInactive.setAccessible(true);
            interfaceName.setAccessible(true);
            getHandler.setAccessible(true);
            retryField.setAccessible(true);
            outerField.setAccessible(true);
            upstreamField.setAccessible(true);
            upstreamIfacesField.setAccessible(true);
            final int retryMessage = retryField.getInt(null);
            final int tetheredMode = getStaticInt(ipServerClass, "STATE_TETHERED", 2);

            hook(servingInactive).intercept(chain -> {
                Object result = chain.proceed();
                usbRecovery.stop(chain.getArg(0));
                return result;
            });

            hook(servingActive).intercept(chain -> {
                Object result = chain.proceed();
                Object ipServer = chain.getArg(1);
                // Module failures must never escape into the native tethering state machine.
                try {
                    if (!USB.iface.equals(interfaceName.invoke(ipServer))) return result;
                    if ((Integer) chain.getArg(0) != tetheredMode) {
                        usbRecovery.stop(ipServer);
                        return result;
                    }

                    Object stateMachine = chain.getThisObject();
                    Object tethering = outerField.get(stateMachine);
                    Object handlerValue = getHandler.invoke(stateMachine);
                    if (!(handlerValue instanceof Handler)) {
                        Log.w(TAG, "USB_UPSTREAM_RETRY_SKIPPED handlerUnavailable=true");
                        return result;
                    }
                    Handler handler = (Handler) handlerValue;
                    UsbUpstreamRecovery.Scheduler scheduler = new UsbUpstreamRecovery.Scheduler() {
                        @Override
                        public boolean postDelayed(Runnable task, long delayMs) {
                            boolean queued = handler.postDelayed(task, delayMs);
                            Log.i(TAG, "USB_UPSTREAM_RETRY_QUEUED iface=rndis0 delayMs="
                                    + delayMs + " queued=" + queued);
                            return queued;
                        }

                        @Override
                        public void removeCallbacks(Runnable task) {
                            handler.removeCallbacks(task);
                        }
                    };
                    usbRecovery.start(stateMachine, ipServer, scheduler, attempt -> {
                        boolean queued = handler.sendEmptyMessage(retryMessage);
                        Log.i(TAG, "USB_UPSTREAM_RETRY_SENT iface=rndis0 attempt="
                                + attempt + " queued=" + queued);
                    }, () -> {
                        try {
                            return upstreamField.get(tethering) != null
                                    && upstreamIfacesField.get(tethering) != null;
                        } catch (ReflectiveOperationException e) {
                            Log.w(TAG, "USB_UPSTREAM_STATE_READ_FAILED", e);
                            return false;
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "USB_UPSTREAM_RETRY_SCHEDULE_FAILED", t);
                }
                return result;
            });

            // Private-method callers may already have been compiled before hook installation.
            deoptimizeServingStateCallers(stateMachineClass);
            Log.i(TAG, "USB_UPSTREAM_RECOVERY_HOOK_INSTALLED " + servingActive);
        } catch (Throwable t) {
            Log.e(TAG, "USB_UPSTREAM_RECOVERY_HOOK_INSTALL_FAILED", t);
        }

        installUpstreamSelectionDiagnostics(loader);
    }

    private void deoptimizeServingStateCallers(Class<?> stateMachineClass) {
        for (Class<?> nested : stateMachineClass.getDeclaredClasses()) {
            if (!"InitialState".equals(nested.getSimpleName())
                    && !"TetherModeAliveState".equals(nested.getSimpleName())) continue;
            try {
                Method processMessage = nested.getDeclaredMethod(
                        "processMessage", android.os.Message.class);
                deoptimize(processMessage);
            } catch (Throwable t) {
                Log.w(TAG, "USB_UPSTREAM_CALLER_DEOPTIMIZE_SKIPPED class=" + nested.getName(), t);
            }
        }
    }

    private void installUpstreamSelectionDiagnostics(ClassLoader loader) {
        try {
            Class<?> stateMachineClass = Class.forName(
                    "com.android.networkstack.tethering.Tethering$TetherMainSM",
                    false, loader);
            Method chooseUpstream = stateMachineClass.getDeclaredMethod(
                    "chooseUpstreamType", boolean.class);
            chooseUpstream.setAccessible(true);

            hook(chooseUpstream).intercept(chain -> {
                Object result = chain.proceed();
                Object stateMachine = chain.getThisObject();
                Object tethering = getOuterInstance(stateMachine);

                Object upstream = readFieldValue(tethering, "mTetherUpstream");
                Object upstreamIfaces = readFieldValue(tethering, "mCurrentUpstreamIfaceSet");

                Log.i(TAG, "UPSTREAM_SELECTION tryCell=" + chain.getArg(0)
                        + " upstream=" + upstream
                        + " upstreamIfaces=" + upstreamIfaces);
                return result;
            });

            Log.i(TAG, "UPSTREAM_SELECTION_DIAGNOSTICS_INSTALLED " + chooseUpstream);
        } catch (Throwable t) {
            Log.e(TAG, "UPSTREAM_SELECTION_DIAGNOSTICS_INSTALL_FAILED", t);
        }
    }

    private static int getStaticInt(Class<?> type, String fieldName, int fallback) {
        try {
            Field field = type.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object getOuterInstance(Object innerInstance) {
        if (innerInstance == null) return null;
        try {
            Field outerField = innerInstance.getClass().getDeclaredField("this$0");
            outerField.setAccessible(true);
            return outerField.get(innerInstance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readFieldValue(Object instance, String fieldName) {
        if (instance == null) return "unavailable";
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(instance);
        } catch (Throwable t) {
            return "unavailable:" + t.getClass().getSimpleName();
        }
    }

    private void installGatewayHook(ClassLoader loader) {
        try {
            Class<?> ipServerClass = Class.forName(IP_SERVER, false, loader);
            Method requestIpv4 = ipServerClass.getDeclaredMethod(
                    "requestIpv4Address", int.class, boolean.class);
            requestIpv4.setAccessible(true);

            hook(requestIpv4).intercept(chain -> {
                Object original = chain.proceed();
                if (!(original instanceof LinkAddress)) return original;

                String iface = getInterfaceName(ipServerClass, chain.getThisObject());
                TargetConfig config = configForInterface(iface);
                if (config == null) return original;

                LinkAddress originalAddress = (LinkAddress) original;
                String originalPrefix = createIpPrefix(
                        originalAddress.getAddress(),
                        originalAddress.getPrefixLength()).toString();
                ORIGINAL_PREFIXES.put(iface, originalPrefix);

                if (hasUpstreamPrefixConflict(config)) {
                    GATEWAY_APPLIED.put(iface, false);
                    Log.w(TAG, "GATEWAY_CONFLICT_FALLBACK iface=" + iface
                            + " original=" + originalAddress
                            + " target=" + config.prefixString);
                    return original;
                }

                LinkAddress fixed = createLinkAddress(ipv4FromInt(config.gateway), 24);
                GATEWAY_APPLIED.put(iface, true);
                Log.i(TAG, "GATEWAY_FIXED iface=" + iface
                        + " original=" + originalAddress
                        + " fixed=" + fixed
                        + " originalPrefix=" + originalPrefix);
                return fixed;
            });

            Log.i(TAG, "GATEWAY_HOOK_INSTALLED " + requestIpv4);
        } catch (Throwable t) {
            Log.e(TAG, "GATEWAY_HOOK_INSTALL_FAILED", t);
        }
    }

    /** Advertise public DNS through DHCP option 6 without changing the default router. */
    private void installDnsHook(ClassLoader loader) {
        try {
            Class<?> ipServerClass = Class.forName(IP_SERVER, false, loader);
            Method makeServingParams = ipServerClass.getDeclaredMethod(
                    "makeServingParams", Inet4Address.class, Inet4Address.class,
                    LinkAddress.class, Inet4Address.class);
            makeServingParams.setAccessible(true);
            Field dnsServers = makeServingParams.getReturnType().getField("dnsServers");
            dnsServers.setAccessible(true);
            Method interfaceName = ipServerClass.getDeclaredMethod("interfaceName");
            interfaceName.setAccessible(true);

            hook(makeServingParams).intercept(chain -> {
                Object parcel = chain.proceed();
                if (parcel == null) return null;
                try {
                    String iface = String.valueOf(interfaceName.invoke(chain.getThisObject()));
                    if (configForInterface(iface) == null) return parcel;
                    dnsServers.set(parcel, new int[] { toInt(DNS_PRIMARY), toInt(DNS_SECONDARY) });
                    Log.i(TAG, "DNS_DHCP_FIXED iface=" + iface
                            + " dnsServers=[1.1.1.1, 8.8.8.8] defaultRouter=" + chain.getArg(0));
                } catch (Throwable t) {
                    Log.e(TAG, "DNS_DHCP_PATCH_FAILED; preserving native parameters", t);
                }
                return parcel;
            });

            Log.i(TAG, "DNS_HOOK_INSTALLED makeServingParams " + makeServingParams);
        } catch (Throwable t) {
            Log.e(TAG, "DNS_HOOK_INSTALL_FAILED", t);
        }
    }

    private void installDhcpHooks(ClassLoader loader) {
        final Class<?> repo;
        try {
            repo = Class.forName(LEASE_REPOSITORY, false, loader);
            Log.i(TAG, "DHCP_CLASS_FOUND " + repo);
        } catch (Throwable t) {
            Log.e(TAG, "DHCP_CLASS_LOAD_FAILED", t);
            return;
        }

        // Exact 5-argument signature observed in this device's NetworkStack.apk.
        try {
            Method updateParams = repo.getDeclaredMethod(
                    "updateParams",
                    IpPrefix.class, Set.class, long.class, Inet4Address.class, int.class);
            updateParams.setAccessible(true);
            hook(updateParams).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                String incomingPrefix = String.valueOf(args[0]);
                TargetConfig config = configForIncomingPrefix(incomingPrefix);
                if (config == null || !isGatewayApplied(config)) {
                    return chain.proceed();
                }

                Set<Inet4Address> reserved = new HashSet<>();
                reserved.add(ipv4FromInt(config.gateway));

                args[0] = config.prefix;
                args[1] = reserved;
                // Keep lease duration, server address, and the final integer parameter
                // exactly as supplied by the Android build.
                Object result = chain.proceed(args);

                Log.i(TAG, "DHCP_PREFIX_FIXED iface=" + config.iface
                        + " old=" + incomingPrefix
                        + " new=" + config.prefixString
                        + " reserved=" + reserved
                        + " leaseMs=" + args[2]
                        + " server=" + args[3]
                        + " finalArg=" + args[4]);
                return result;
            });
            Log.i(TAG, "DHCP_HOOK_INSTALLED updateParams " + updateParams);
        } catch (Throwable t) {
            Log.e(TAG, "DHCP_HOOK_INSTALL_FAILED updateParams", t);
        }

        try {
            Method firstClient = repo.getDeclaredMethod(
                    "getFirstClientAddress", MacAddress.class);
            firstClient.setAccessible(true);
            hook(firstClient).intercept(chain -> {
                TargetConfig config = targetRepositoryConfig(repo, chain.getThisObject());
                if (config == null) return chain.proceed();

                MacAddress mac = (MacAddress) chain.getArg(0);
                Inet4Address staticAddress = getReservedAddress(mac, config);
                int candidate;

                if (staticAddress != null) {
                    candidate = toInt(staticAddress);
                    Log.i(TAG, "DHCP_STATIC_CANDIDATE iface=" + config.iface
                            + " mac=" + mac + " ip=" + staticAddress.getHostAddress());
                } else {
                    int original = (Integer) chain.proceed();
                    int host = POOL_START + Math.floorMod(original & 0xFF,
                            POOL_END - POOL_START + 1);
                    candidate = nextUnreservedDynamicAddress(config, config.network | host);
                }

                Log.i(TAG, "DHCP_CANDIDATE iface=" + config.iface + " mac=" + mac
                        + " candidate=" + ipv4FromInt(candidate).getHostAddress());
                return candidate;
            });
            Log.i(TAG, "DHCP_HOOK_INSTALLED getFirstClientAddress " + firstClient);
        } catch (Throwable t) {
            Log.e(TAG, "DHCP_HOOK_INSTALL_FAILED getFirstClientAddress", t);
        }

        try {
            Method nextAddress = repo.getDeclaredMethod("getNextAddress", int.class);
            nextAddress.setAccessible(true);
            hook(nextAddress).intercept(chain -> {
                TargetConfig config = targetRepositoryConfig(repo, chain.getThisObject());
                if (config == null) return chain.proceed();

                int current = (Integer) chain.getArg(0);
                if ((current & config.mask) != config.network) return chain.proceed();
                int host = current & 0xFF;
                // If an unavailable static address is encountered, fall back into
                // the dynamic pool instead of walking through .2-.99/.201-.254.
                int nextHost = (host >= POOL_START && host <= POOL_END)
                        ? host : POOL_START - 1;
                Set<Integer> staticIps = new HashSet<>(
                        getStaticReservations(config).values());
                for (int i = 0; i <= (POOL_END - POOL_START); i++) {
                    nextHost = (nextHost >= POOL_END) ? POOL_START : nextHost + 1;
                    int candidate = config.network | nextHost;
                    if (!staticIps.contains(candidate)) return candidate;
                }
                return config.network | POOL_START;
            });
            Log.i(TAG, "DHCP_HOOK_INSTALLED getNextAddress " + nextAddress);
            try {
                Method makeNewOffer = repo.getDeclaredMethod(
                        "makeNewOffer", byte[].class, MacAddress.class, long.class, String.class);
                deoptimize(makeNewOffer);
                Log.i(TAG, "DEOPTIMIZED makeNewOffer to preserve getNextAddress hook");
            } catch (Throwable deoptError) {
                Log.w(TAG, "MAKE_NEW_OFFER_DEOPTIMIZE_SKIPPED", deoptError);
            }
        } catch (Throwable t) {
            Log.e(TAG, "DHCP_HOOK_INSTALL_FAILED getNextAddress", t);
        }

        try {
            Method getOffer = repo.getDeclaredMethod(
                    "getOffer",
                    byte[].class, MacAddress.class, Inet4Address.class,
                    Inet4Address.class, String.class);
            getOffer.setAccessible(true);
            hook(getOffer).intercept(chain -> {
                TargetConfig config = targetRepositoryConfig(repo, chain.getThisObject());
                if (config == null) return chain.proceed();

                Object[] args = chain.getArgs().toArray();
                MacAddress mac = (MacAddress) args[1];

                Inet4Address staticAddress = getReservedAddress(mac, config);
                if (staticAddress != null) {
                    // getOffer() returns an existing committed lease before it considers
                    // reqAddr. Release a different old lease first so the new static IP
                    // can actually be offered.
                    releaseCurrentLeaseIfNeeded(repo, chain.getThisObject(),
                            mac, staticAddress, config, "STATIC_UPDATE");
                    args[3] = staticAddress;
                } else {
                    // If a client was static outside the dynamic pool, remove that old
                    // lease before offering a dynamic address again.
                    releaseCurrentLeaseIfNeeded(repo, chain.getThisObject(),
                            mac, null, config, "RETURN_TO_DYNAMIC");
                    if (args[3] instanceof Inet4Address
                            && !isPoolAddress((Inet4Address) args[3], config)) {
                        args[3] = null;
                    }
                }

                Object result = chain.proceed(args);
                Log.i(TAG, "DHCP_OFFER iface=" + config.iface + " mac=" + mac + " lease=" + result);
                return result;
            });
            Log.i(TAG, "DHCP_HOOK_INSTALLED getOffer " + getOffer);
        } catch (Throwable t) {
            Log.e(TAG, "DHCP_HOOK_INSTALL_FAILED getOffer", t);
        }

        try {
            Method requestLease = repo.getDeclaredMethod(
                    "requestLease",
                    byte[].class, MacAddress.class,
                    Inet4Address.class, Inet4Address.class, Inet4Address.class,
                    boolean.class, String.class);
            requestLease.setAccessible(true);
            hook(requestLease).intercept(chain -> {
                TargetConfig config = targetRepositoryConfig(repo, chain.getThisObject());
                if (config == null) return chain.proceed();

                Object[] args = chain.getArgs().toArray();
                MacAddress mac = (MacAddress) args[1];

                Inet4Address staticAddress = getReservedAddress(mac, config);
                if (staticAddress != null) {
                    releaseCurrentLeaseIfNeeded(repo, chain.getThisObject(),
                            mac, staticAddress, config, "STATIC_REQUEST");
                    args[2] = staticAddress;
                    args[4] = staticAddress;
                } else {
                    releaseCurrentLeaseIfNeeded(repo, chain.getThisObject(),
                            mac, null, config, "RETURN_TO_DYNAMIC");
                    Inet4Address requested = (Inet4Address) args[4];
                    Inet4Address clientAddr = (Inet4Address) args[2];
                    if (requested != null && !isPoolAddress(requested, config)) {
                        args[4] = ipv4FromInt(dynamicCandidate(mac, config));
                    } else if (requested == null && !isPoolAddress(clientAddr, config)) {
                        args[2] = ipv4FromInt(dynamicCandidate(mac, config));
                    }
                }

                Object result = chain.proceed(args);
                Log.i(TAG, "DHCP_LEASE iface=" + config.iface
                        + " mac=" + mac + " lease=" + result);
                return result;
            });
            Log.i(TAG, "DHCP_HOOK_INSTALLED requestLease " + requestLease);
        } catch (Throwable t) {
            Log.e(TAG, "DHCP_HOOK_INSTALL_FAILED requestLease", t);
        }

        Log.i(TAG, "DHCP_HOOK_SETUP_FINISHED");
    }

    private static TargetConfig targetRepositoryConfig(Class<?> repoClass,
            Object repository) {
        try {
            Field prefixField = repoClass.getDeclaredField("mPrefix");
            prefixField.setAccessible(true);
            Object prefix = prefixField.get(repository);
            return configForPrefix(String.valueOf(prefix));
        } catch (Throwable t) {
            return null;
        }
    }

    private static TargetConfig configForInterface(String iface) {
        for (TargetConfig config : TARGETS) {
            if (config.iface.equals(iface)) return config;
        }
        return null;
    }

    private static TargetConfig configForPrefix(String prefix) {
        for (TargetConfig config : TARGETS) {
            if (config.prefixString.equals(prefix)) return config;
        }
        return null;
    }

    private static TargetConfig configForIncomingPrefix(String prefix) {
        TargetConfig direct = configForPrefix(prefix);
        if (direct != null) return direct;

        for (TargetConfig config : TARGETS) {
            String original = ORIGINAL_PREFIXES.get(config.iface);
            if (isGatewayApplied(config) && original != null && original.equals(prefix)) {
                return config;
            }
        }
        return null;
    }

    private static boolean isGatewayApplied(TargetConfig config) {
        return Boolean.TRUE.equals(GATEWAY_APPLIED.get(config.iface));
    }

    private static String getInterfaceName(Class<?> ipServerClass, Object server) {
        try {
            Method method = ipServerClass.getDeclaredMethod("interfaceName");
            method.setAccessible(true);
            return String.valueOf(method.invoke(server));
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Runtime MAC -> IPv4 reservations stored in Settings.Global.
     *
     * Static addresses may be inside or outside the dynamic pool (.100-.200).
     * Do NOT add them to DhcpLeaseRepository.updateParams()
     * reserved-address set: Android's allocator must still be able to issue the
     * configured static address to its matching MAC.
     */
    private static Inet4Address getReservedAddress(MacAddress mac, TargetConfig config) {
        if (mac == null) return null;
        Integer value = getStaticReservations(config).get(normalizeMac(mac.toString()));
        if (value == null) return null;
        try {
            Inet4Address address = ipv4FromInt(value);
            Log.i(TAG, "RUNTIME_STATIC_MATCH iface=" + config.iface
                    + " mac=" + mac + " ip=" + address.getHostAddress());
            return address;
        } catch (Throwable t) {
            Log.e(TAG, "RUNTIME_CONFIG_IP_CONVERSION_FAILED iface=" + config.iface, t);
            return null;
        }
    }

    private static Map<String, Integer> getStaticReservations(TargetConfig config) {
        String raw = readRuntimeConfig();

        // Invalidate old snapshots when reservations are cleared.
        if (raw == null || raw.trim().isEmpty() || "null".equals(raw.trim())) {
            RESERVATION_CACHE.remove(config.iface);
            return Collections.emptyMap();
        }
        ReservationSnapshot cached = RESERVATION_CACHE.get(config.iface);
        if (cached != null && raw.equals(cached.raw)) return cached.reservations;
        Map<String, Integer> result = new HashMap<>();

        try {
            JSONObject root = new JSONObject(raw);
            int schemaVersion = root.optInt("version", 1);
            JSONArray reservations = root.optJSONArray("reservations");
            if (reservations == null) {
                Log.w(TAG, "RUNTIME_CONFIG_NO_RESERVATIONS_ARRAY iface=" + config.iface
                        + " schema=" + schemaVersion);
                ReservationSnapshot snapshot = new ReservationSnapshot(raw, result);
                RESERVATION_CACHE.put(config.iface, snapshot);
                return snapshot.reservations;
            }

            int matchingInterface = 0;
            int rejected = 0;
            for (int i = 0; i < reservations.length(); i++) {
                JSONObject item = reservations.optJSONObject(i);
                if (item == null) {
                    rejected++;
                    continue;
                }
                if (!config.iface.equals(item.optString("iface"))) continue;
                matchingInterface++;

                String mac = normalizeMac(item.optString("mac"));
                int ip = parseIpv4(item.optString("ip"));
                if (mac == null || !isValidStaticAddress(config, ip)) {
                    rejected++;
                    Log.w(TAG, "RUNTIME_CONFIG_ENTRY_REJECTED iface=" + config.iface
                            + " macValid=" + (mac != null)
                            + " ip=" + item.optString("ip"));
                    continue;
                }

                if (result.containsValue(ip) && !result.containsKey(mac)) {
                    rejected++;
                    Log.w(TAG, "RUNTIME_CONFIG_DUPLICATE_IP iface=" + config.iface
                            + " ip=" + item.optString("ip"));
                    continue;
                }
                result.put(mac, ip);
            }

            Log.i(TAG, "RUNTIME_CONFIG_LOADED iface=" + config.iface
                    + " schema=" + schemaVersion
                    + " matchingInterface=" + matchingInterface
                    + " reservations=" + result.size()
                    + " rejected=" + rejected);
        } catch (Throwable t) {
            Log.e(TAG, "RUNTIME_CONFIG_PARSE_FAILED; no static reservations applied", t);
            result.clear();
        }
        ReservationSnapshot snapshot = new ReservationSnapshot(raw, result);
        RESERVATION_CACHE.put(config.iface, snapshot);
        return snapshot.reservations;
    }

    private static String readRuntimeConfig() {
        try {
            Context context = cachedContext;
            if (context == null) {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");

                Method currentApplication =
                        activityThread.getDeclaredMethod("currentApplication");
                currentApplication.setAccessible(true);
                Object app = currentApplication.invoke(null);

                if (app instanceof Context) {
                    context = ((Context) app).getApplicationContext();
                }

                // Fallback for process startup ordering: use ActivityThread's
                // system context if the Application object is not available yet.
                if (context == null) {
                    Method currentThread =
                            activityThread.getDeclaredMethod("currentActivityThread");
                    currentThread.setAccessible(true);
                    Object thread = currentThread.invoke(null);
                    if (thread != null) {
                        Method getSystemContext =
                                activityThread.getDeclaredMethod("getSystemContext");
                        getSystemContext.setAccessible(true);
                        Object systemContext = getSystemContext.invoke(thread);
                        if (systemContext instanceof Context) {
                            context = (Context) systemContext;
                        }
                    }
                }

                if (context != null) cachedContext = context;
            }

            if (context == null) {
                Log.w(TAG, "RUNTIME_CONFIG_CONTEXT_UNAVAILABLE; using defaults");
                return null;
            }
            return Settings.Global.getString(
                    context.getContentResolver(), CONFIG_KEY);
        } catch (Throwable t) {
            Log.w(TAG, "RUNTIME_CONFIG_READ_FAILED; using defaults", t);
            return null;
        }
    }

    private static String normalizeMac(String value) {
        if (value == null) return null;
        String mac = value.trim().toLowerCase(Locale.ROOT);
        if (!mac.matches("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")) return null;
        return mac;
    }

    private static int parseIpv4(String value) {
        if (value == null) return -1;
        String[] parts = value.trim().split("\\.");
        if (parts.length != 4) return -1;
        int result = 0;
        try {
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) return -1;
                result = (result << 8) | octet;
            }
            return result;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isValidStaticAddress(TargetConfig config, int address) {
        // IPv4 addresses in 192.168/16 have the sign bit set when stored in a
        // Java int. Do not reject negative ints: compare the subnet bit pattern.
        if ((address & config.mask) != config.network) return false;
        int host = address & 0xFF;
        // Lock the client's current address, including addresses inside the
        // dynamic range. Dynamic allocation hooks skip all configured static IPs.
        return host >= 2 && host <= 254 && address != config.gateway;
    }

    private static int nextUnreservedDynamicAddress(TargetConfig config, int start) {
        int host = start & 0xFF;
        Set<Integer> staticIps = new HashSet<>(getStaticReservations(config).values());
        for (int i = 0; i <= (POOL_END - POOL_START); i++) {
            int candidate = config.network | host;
            if (!staticIps.contains(candidate)) return candidate;
            host = (host >= POOL_END) ? POOL_START : host + 1;
        }
        Log.e(TAG, "DHCP_DYNAMIC_POOL_EXHAUSTED_BY_RESERVATIONS iface=" + config.iface);
        return config.network | POOL_START;
    }

    /**
     * Remove an old committed lease when a MAC is moved to a different static IP,
     * or when a former static address lies outside the dynamic pool.
     *
     * AOSP getOffer() prefers a committed lease before looking at the requested IP.
     * Without this release, changing the reservation in the GUI cannot move a live
     * client's lease.
     */
    private static void releaseCurrentLeaseIfNeeded(Class<?> repoClass, Object repository,
            MacAddress mac, Inet4Address desiredStatic, TargetConfig config, String reason) {
        if (repository == null || mac == null) return;
        try {
            Method getLeases = repoClass.getDeclaredMethod("getCommittedLeases");
            getLeases.setAccessible(true);
            Object value = getLeases.invoke(repository);
            if (!(value instanceof Iterable)) return;

            Method release = repoClass.getDeclaredMethod(
                    "releaseLease", byte[].class, MacAddress.class, Inet4Address.class);
            release.setAccessible(true);

            for (Object lease : (Iterable<?>) value) {
                if (lease == null) continue;
                Method getHwAddr = lease.getClass().getDeclaredMethod("getHwAddr");
                Method getNetAddr = lease.getClass().getDeclaredMethod("getNetAddr");
                Method getClientId = lease.getClass().getDeclaredMethod("getClientId");
                getHwAddr.setAccessible(true);
                getNetAddr.setAccessible(true);
                getClientId.setAccessible(true);

                Object leaseMac = getHwAddr.invoke(lease);
                Object leaseAddress = getNetAddr.invoke(lease);
                if (!mac.equals(leaseMac) || !(leaseAddress instanceof Inet4Address)) continue;

                Inet4Address oldAddress = (Inet4Address) leaseAddress;
                boolean releaseOld;
                if (desiredStatic != null) {
                    releaseOld = !desiredStatic.equals(oldAddress);
                } else {
                    releaseOld = !isPoolAddress(oldAddress, config);
                }

                if (!releaseOld) continue;

                byte[] clientId = (byte[]) getClientId.invoke(lease);
                Object released = release.invoke(repository, clientId, mac, oldAddress);
                Log.i(TAG, "DHCP_OLD_LEASE_RELEASED iface=" + config.iface
                        + " mac=" + mac + " old=" + oldAddress.getHostAddress()
                        + " target=" + (desiredStatic == null
                            ? "DYNAMIC" : desiredStatic.getHostAddress())
                        + " reason=" + reason + " released=" + released);
            }
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "DHCP_OLD_LEASE_RELEASE_UNAVAILABLE iface=" + config.iface
                    + " reason=" + reason + " error=" + e);
        } catch (Throwable t) {
            Log.e(TAG, "DHCP_OLD_LEASE_RELEASE_FAILED iface=" + config.iface
                    + " mac=" + mac + " reason=" + reason, t);
        }
    }

    private static boolean isPoolAddress(Inet4Address address, TargetConfig config) {
        if (address == null) return false;
        int value = toInt(address);
        if ((value & config.mask) != config.network) return false;
        int host = value & 0xFF;
        return host >= POOL_START && host <= POOL_END;
    }

    private static int dynamicCandidate(MacAddress mac, TargetConfig config) {
        int hash = 0;
        if (mac != null) {
            for (byte b : mac.toByteArray()) {
                hash += b + (b << 8) + (b << 16);
            }
        }
        int index = hash & 0xFF;
        if (index == 0 || index == 255) index = 1;
        int host = POOL_START + (index % (POOL_END - POOL_START + 1));
        return nextUnreservedDynamicAddress(config, config.network | host);
    }

    private static Inet4Address ipv4FromOctets(int a, int b, int c, int d) {
        try {
            byte[] address = new byte[] {
                    (byte) a, (byte) b, (byte) c, (byte) d
            };
            return (Inet4Address) InetAddress.getByAddress(address);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static IpPrefix createPrefix(int network) {
        try {
            return createIpPrefix(ipv4FromInt(network), 24);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static IpPrefix createIpPrefix(InetAddress address, int prefixLength)
            throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT >= 33) return new IpPrefix(address, prefixLength);
        // This constructor existed as a hidden API before it entered the public SDK.
        java.lang.reflect.Constructor<IpPrefix> constructor =
                IpPrefix.class.getDeclaredConstructor(InetAddress.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(address, prefixLength);
    }

    private static LinkAddress createLinkAddress(Inet4Address address, int prefixLength)
            throws Exception {
        java.lang.reflect.Constructor<LinkAddress> constructor =
                LinkAddress.class.getDeclaredConstructor(InetAddress.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(address, prefixLength);
    }

    private static int toInt(Inet4Address address) {
        byte[] b = address.getAddress();
        return ((b[0] & 0xFF) << 24)
                | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);
    }

    private static Inet4Address ipv4FromInt(int value) throws Exception {
        byte[] b = new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
        return (Inet4Address) InetAddress.getByAddress(b);
    }

    private static boolean hasUpstreamPrefixConflict(TargetConfig config) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return true;

            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                String name = iface.getName();
                if (config.iface.equals(name) || "lo".equals(name)) continue;
                if (!iface.isUp()) continue;

                for (InterfaceAddress interfaceAddress : iface.getInterfaceAddresses()) {
                    if (!(interfaceAddress.getAddress() instanceof Inet4Address)) continue;
                    short prefixLength = interfaceAddress.getNetworkPrefixLength();
                    if (prefixLength <= 0 || prefixLength > 32) continue;

                    int address = toInt((Inet4Address) interfaceAddress.getAddress());
                    int otherMask = prefixLength == 32
                            ? 0xFFFFFFFF : (int) (0xFFFFFFFFL << (32 - prefixLength));
                    int otherNetwork = address & otherMask;

                    boolean overlaps = ((config.network & otherMask) == otherNetwork)
                            || ((otherNetwork & config.mask) == config.network);
                    if (overlaps) {
                        Log.w(TAG, "UPSTREAM_OVERLAP iface=" + name
                                + " address=" + interfaceAddress.getAddress().getHostAddress()
                                + "/" + prefixLength);
                        return true;
                    }
                }
            }

            // USB can start before mobile data has an IPv4 address. An empty
            // upstream list is not a prefix conflict; later startup must not
            // depend on another downstream already being active.
            return false;
        } catch (SocketException e) {
            Log.e(TAG, "UPSTREAM_CHECK_FAILED; using original Android address", e);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "UPSTREAM_CHECK_ERROR; using original Android address", t);
            return true;
        }
    }
}
