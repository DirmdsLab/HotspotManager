# Hotspot Manager v0.6.7

Target device and software:
- Infinix X1101B
- Android 16 / SDK 36
- Magisk 31.1
- Vector 2.2
- Wi-Fi downstream interface: `ap0`
- USB downstream interface: `rndis0`

## v0.6.7 changes

### Release build and UI deprecation cleanup
- Added an explicit non-debuggable `release` build type with code shrinking disabled
  so reflective Xposed hook entry points are not renamed or removed.
- Replaced deprecated platform window-insets accessors with AndroidX
  `WindowInsetsCompat` while preserving the same system-bars padding behavior.
- Removed redundant legacy system-UI and system-bar color calls; the existing
  `AppTheme` remains the source of the same status/navigation bar colors and icon style.
- No changes were made to the runtime tethering, upstream recovery, DNS, gateway,
  DHCP, MAC reservation, or static-IP logic.

## v0.6.3 changes

### USB upstream startup recovery
- Added a hook for the moment Android marks `rndis0` as actively tethered.
- The module queues one native `CMD_RETRY_UPSTREAM` message after 2.5 seconds
  on Android Tethering's own state-machine handler.
- This asks Android to re-evaluate its selected upstream and reapply the
  downstream forwarding and upstream DNS-forwarder state through the normal
  Tethering code path.
- Added `UPSTREAM_SELECTION` diagnostics so the selected upstream network and
  downstream interface set can be inspected.
- This is a runtime recovery trigger; it does not rewrite Android's routing
  tables or replace the Tethering implementation.

### USB and Wi-Fi DHCP DNS
- The DHCP DNS hook now applies to recognized downstream interfaces even if
  the gateway-address hook has not marked that interface as successfully
  patched yet.
- DHCP option 6 continues to advertise `1.1.1.1` and `8.8.8.8` on both `ap0`
  and `rndis0`.
- The DHCP DNS change does not by itself restore upstream connectivity. If a
  client cannot ping a public IP address, investigate upstream selection,
  forwarding, and NAT before treating the problem as DNS-only.

### App language
- All user-facing app text is now English.
- Source-code comments are written in English.

## Retained network configuration

| Interface | Gateway | Subnet | Dynamic pool |
|---|---|---|---|
| Wi-Fi `ap0` | `192.168.77.1` | `192.168.77.0/24` | `.100-.200` |
| USB `rndis0` | `192.168.78.1` | `192.168.78.0/24` | `.100-.200` |

DHCP clients are offered:
- Primary DNS: `1.1.1.1`
- Secondary DNS: `8.8.8.8`

## DHCP reservation configuration

Reservations remain stored in `Settings.Global` under:

`infinix_hotspot_reservations_v1`

The existing JSON schema remains compatible:

```json
{
  "version": 2,
  "reservations": []
}
```

Changing a reservation in the app does not require recompiling the app or
rebooting Android. The client must renew DHCP or reconnect to receive the new
address.

## Release build

The release variant is non-debuggable and keeps minification disabled to protect
reflection-based Xposed hooks. No release keystore is included in this source
package, so Gradle produces an unsigned release APK unless you configure your
own release signing key.

Build the release variant:

```bash
gradle :app:clean :app:assembleRelease
```

Unsigned output:

`app/build/outputs/apk/release/app-release-unsigned.apk`

To install on a device, sign the APK with your own persistent release keystore
using Android Studio's **Build > Generate Signed Bundle / APK** or `apksigner`,
then install the signed APK. For example:

```bash
apksigner sign --ks my-release-key.jks \
  --out app-release.apk app-release-unsigned.apk
apksigner verify --verbose app-release.apk
adb install -r app-release.apk
```

Keep the same keystore for all future updates.
A release APK signed with a different key cannot update an installation signed
with the previous key. If the currently installed v0.6.3 app was installed from
a debug build, it must be signed with the matching debug key to update in place;
otherwise Android requires uninstalling the old package first.

Keep Vector scope enabled for:
- `com.android.networkstack`
- `com.android.networkstack.tethering`

Grant root permission to Hotspot Manager.

After installing the v0.6.7 APK, reboot once so the Xposed hook code is loaded
cleanly into the NetworkStack and Tethering processes.
Do not remove the currently working Magisk module before verifying the update.

## USB-first test procedure

1. Reboot Android after installing the updated app.
2. Leave the Wi-Fi hotspot disabled.
3. Connect the PC using USB tethering only.
4. Wait approximately 10 seconds.
5. On the PC, test the gateway and public IP:

```bash
ping -c 3 192.168.78.1
ping -c 3 1.1.1.1
```

6. Test DNS directly:

```bash
dig @1.1.1.1 google.com +time=2 +tries=1
dig @8.8.8.8 google.com +time=2 +tries=1
```

7. If the first USB session still has no Internet, collect the Android logs
   before enabling the Wi-Fi hotspot:

```bash
adb logcat -c
```

Enable USB tethering, wait 10 seconds, then run:

```bash
adb logcat -d -s InfinixHotspotPatch:I '*:S' | grep -E \
'GATEWAY_|DNS_|USB_UPSTREAM_|UPSTREAM_SELECTION|DHCP_|RUNTIME_CONFIG'
```

Also collect:

```bash
adb shell su -c 'dumpsys tethering'
adb shell su -c 'ip -4 addr show rndis0; ip route'
adb shell su -c 'ip rule show'
```

## Interpreting the test

- If `ping 192.168.78.1` fails, investigate the USB downstream interface,
  address assignment, or local link first.
- If the gateway responds but `ping 1.1.1.1` fails, the problem is upstream
  selection, forwarding, or NAT; changing DNS servers will not fix it.
- If `ping 1.1.1.1` works but `dig @1.1.1.1` fails, investigate DNS packet
  forwarding or client DNS configuration.
- `DNS_DHCP_FIXED iface=rndis0` confirms the DHCP parameter hook ran. It does
  not prove that the PC received the DHCP option or that public DNS is reachable.
- `USB_UPSTREAM_RETRY_QUEUED` confirms that the recovery message was queued.
  `UPSTREAM_SELECTION` shows the upstream Android selected when the message
  was processed.

## Rollback

Keep the last known-working APK and Magisk module available. If the new build
causes tethering to stop, disable the Vector module and restore the last
known-working version before continuing.


## v0.6.7 changes

- Removed the two hardcoded PC MAC/IP reservations. A fresh configuration now starts empty.
- Runtime DHCP reservation lookup returns no static entries when Settings.Global is empty.
- Added an explicit root UID check to device refresh and configuration writes.
- The UI reports when Magisk Superuser access has not been granted.
- Manual target IP input starts blank; the interface-specific subnet is shown as a hint.
- The tethering, upstream recovery, gateway, DNS, and DHCP hook implementation is otherwise unchanged.

## Release signing

The release build reads the signing key from the sibling `/workspace/key` folder:

- `../key/infinix-hotspot-release.jks`
- `../key/keystore.properties`

Do not commit the keystore or properties file to source control.


## v0.6.7 package identity

- Application ID and namespace: `uwu.waguri.hotspotmanager`
- Launcher label: `Hotspot Manager`
- Xposed entry point: `uwu.waguri.hotspotmanager.HotspotProbe`
- The Wi-Fi/USB tethering, DHCP, DNS, reservation, and upstream-recovery implementation
  is carried forward from the working v0.6.5 source without intentional behavior changes.
- This package ID is different from the old `dev.infinix.hotspotprobe` package. Android
  treats it as a separate app; install it alongside the old app first, then enable its
  module entry in Vector and grant it root access in Magisk.


## v0.6.7 naming correction

- Launcher and in-app display name: `Hotspot Manager`.
- Application ID and namespace remain `uwu.waguri.hotspotmanager`.
- The runtime tethering and DHCP implementation is unchanged from v0.6.6.
