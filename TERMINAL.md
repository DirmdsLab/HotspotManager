# Hotspot and USB control

Version 0.6.9 adds two switches above Network Status: USB on the left and Hotspot
on the right. Both controls use Android's tethering service through a root-only
helper. The stored hotspot SSID/password are used; no replacement configuration
is written. The existing gateway, DNS, DHCP reservation, and USB-first recovery
hooks remain enabled.

## First setup

1. Sign and install the 0.6.9 APK using the same signing key as the installed app.
2. Open Hotspot Manager and grant its root request in Magisk.
3. Keep the existing Vector scopes enabled and reboot after updating the module.
4. Connect a USB data cable when testing USB tethering.

## ADB commands

These commands work without opening the GUI. The receiver runs the same root
helper used by the switches, so Hotspot Manager must already have Magisk root
permission. The ADB shell itself does not need to run as root for this route.

Enable hotspot:

```bash
adb shell am broadcast -n uwu.waguri.hotspotmanager/.TetheringReceiver --es target hotspot --es action on
```

Disable hotspot:

```bash
adb shell am broadcast -n uwu.waguri.hotspotmanager/.TetheringReceiver --es target hotspot --es action off
```

Enable USB tethering:

```bash
adb shell am broadcast -n uwu.waguri.hotspotmanager/.TetheringReceiver --es target usb --es action on
```

Disable USB tethering:

```bash
adb shell am broadcast -n uwu.waguri.hotspotmanager/.TetheringReceiver --es target usb --es action off
```

Read both states:

```bash
adb shell am broadcast -n uwu.waguri.hotspotmanager/.TetheringReceiver --es action status
```

The broadcast result contains JSON. A successful state query may return:

```json
{"ok":true,"hotspot":true,"usb":false,"interfaces":["ap0"]}
```

`ok:true` after an on/off request means that the requested downstream state was
observed. It does not prove upstream Internet or DNS reachability. Errors return
`ok:false` with an English error message and a nonzero broadcast result code.
The `am broadcast` process exit code alone is not the operation result; inspect
its `result=` and JSON `data=` fields.

Use `on`/`off` rather than a blind toggle in scripts. Repeating a command for an
already matching active state does not restart that downstream. Operations from
the GUI and receiver are serialized; a concurrent request is rejected with an
operation-in-progress error instead of being queued indefinitely.

USB changes can briefly reconnect the USB/ADB transport. If ADB disconnects,
wait for it to reconnect and issue the status command. Disabling USB tethering
from a terminal reached through that same tethering link also disconnects the
link being used by the terminal.

## Direct root-shell mode

For a root terminal, the APK also exposes an app_process entry point. This does
not depend on the receiver or app-specific Magisk authorization when the shell
already has root permission:

```sh
adb shell
su
apk_path="$(pm path uwu.waguri.hotspotmanager | sed -n 's/^package://p' | head -n 1)"
CLASSPATH="$apk_path" app_process /system/bin uwu.waguri.hotspotmanager.TetheringCli hotspot on
CLASSPATH="$apk_path" app_process /system/bin uwu.waguri.hotspotmanager.TetheringCli usb on
CLASSPATH="$apk_path" app_process /system/bin uwu.waguri.hotspotmanager.TetheringCli status
```

Replace `on` with `off` to disable the selected downstream. The helper only
accepts `status`, `hotspot on|off|status`, and `usb on|off|status`. Direct helper
output starts with `__HOTSPOT_MANAGER_RESULT__`, followed by JSON. It exits with
code 0 for success and code 1 for failure. Requests without root are rejected.
Resolve the APK path again after an app update.

## Status and confirmation

The GUI reads state when resumed and after tethering state broadcasts, including
changes made in Android Settings or through ADB. Refresh on the Devices tab also
reloads switch state. No permanent polling service is installed.

Switches show Unknown and remain disabled until state is available. During a
change, both switches are disabled to prevent overlapping commands. Native start
acknowledgment has an 8-second deadline; interface-state confirmation waits up
to 15 seconds. The enclosing root command has a 35-second deadline.

A missing cable or failure to activate USB produces an error rather than a false
On state. A status-query failure is shown as Unknown, rather than assuming both
interfaces are Off. Carrier/firmware restrictions remain enforced by Android.

The exported receiver requires `android.permission.DUMP`, which the Android
ADB shell holds. Ordinary installed apps cannot invoke this root control entry
point without that privileged permission. No arbitrary shell commands are
accepted as extras.

The new controls use the Android 16 TetheringManager signatures checked against
AOSP. They still require verification on the physical Infinix firmware. Follow
the USB-first DNS tests in README.md after trying both GUI and terminal control.
