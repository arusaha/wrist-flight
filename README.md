# Wrist Flight — Automatic Airplane Mode for Galaxy Watch

**Wear your watch. Let airplane mode follow.**

Wrist Flight automates airplane mode on **Samsung Galaxy Watch and compatible Wear OS smartwatches**. Put your watch on and airplane mode turns on. Take it off or place it on its charger and airplane mode turns off. If you prefer airplane mode while wearing your watch, Wrist Flight saves you the repeated trips to Quick Settings.

**[Download the APK](https://github.com/arusaha/wrist-flight/releases/tag/v1.1)** · **[Installation guide](#install-from-windows)** · **[Compatibility and testing](#compatibility-and-testing)**

A sideloadable Android watch app. **No root required; Shizuku must be installed and running on the watch.**

## Why Wrist Flight?

- **Automatic airplane / flight mode:** uses the watch's wrist-detection sensor to follow when you put it on and take it off.
- **Charging takes priority:** airplane mode stays off while docked, including when fully charged.
- **Control on the watch:** a compact Home screen keeps the full-size start/pause button above the fold at standard text size. The airplane is filled while monitoring and outlined when paused; brief toggle animations respect the system animation setting. Setup and recent activity remain a scroll away.
- **Local operation:** once Shizuku is running, airplane-mode commands stay on the watch; a live computer connection is not required for each change.
- **No account, ads or analytics:** Wrist Flight has no Internet permission and does not collect GPS or heart-rate readings.
- **Ready to sideload:** download the APK and checksum, or build it yourself. Source code, tests and Windows setup scripts are included.

## How it works

| Watch state, while automation is enabled | Airplane mode |
| --- | --- |
| On wrist, disconnected from charger, confirmed for 3 seconds | On |
| Removed from wrist | Off |
| On charger, including fully charged or charging paused | Off |
| Wrist or charger state unknown | Off until confirmed |

Charging always takes priority. There is no deliberate delay before turning airplane mode off; the sensor, Android and the system command still take time. After undocking, the app requests a fresh wrist reading before it can enable airplane mode again. **Pause & turn off** disables automation and requests airplane mode off.

## Compatibility and testing

Wrist Flight requires a Wear OS watch running Android API 30 or later, a wake-up off-body sensor, Sensors permissions, and Shizuku running on the watch. **The maintainer confirmed version 1.0 works on their Samsung watch on October 10, 2026.** The setup was identified as **Samsung SM-L350, Android API 37**, with Shizuku 13.6.0 and the `offbody_detect Wakeup` sensor. Version 1.1 updates the Home layout and visual feedback; its new UI has not yet been checked on the physical watch.

The build and all 20 automated policy tests pass. Results for every [physical verification scenario](#physical-verification), long-term reliability and battery use have not been individually recorded. Other watch models and firmware versions are not yet verified. A phone companion app is not required.

## What you need to know first

An ordinary Android app cannot change airplane mode. Sideloading alone, or granting `WRITE_SECURE_SETTINGS`, is insufficient. Wrist Flight uses a Shizuku UserService to invoke Android's privileged connectivity command and check the result. You must install and start **Shizuku on the watch itself**; having it on your phone does not provide watch access.

**Check that Shizuku is running after each watch reboot; use the restart script when needed.** This setup uses the computer-started service. Shizuku 13.6 also advertises optional automatic startup on a trusted WLAN; that feature has not been configured or verified here. Once started, commands travel locally over Binder, not over Wi-Fi. A live computer connection is not used for each change. However, Samsung firmware can stop Shizuku or the monitor, and this device-specific behavior must be checked with the screen off and radios disconnected. Do not disable Developer options or ADB debugging after setup; Shizuku's guide warns this can stop it.

## Install from Windows

Keep the watch **off your wrist and on its charger** during setup.

1. Download the Shizuku APK using the links on its [official download page](https://shizuku.rikka.app/download/).
2. On the watch, enable Developer options by repeatedly tapping **Software version** under **Settings → About watch → Software information**. The wording can vary by One UI version. Enable **ADB debugging** and **Wireless debugging**, and connect the watch and computer to the same Wi-Fi network.
   - Both debugging switches are required. If the Wireless debugging page shows an IP without a port, check the separate ADB debugging switch.
   - In Developer options, enable **Turn off automatic Wi-Fi** during setup to prevent the watch dropping the connection while paired to your phone.
3. In Wireless debugging, choose **Pair new device / Pair device with pairing code**. Note the IP address and pairing port. Also note the separate connection port on the main Wireless debugging screen. These ports are usually different.
4. Open PowerShell in this project and run the command below, replacing both addresses and the Shizuku file path with the actual values. ADB will ask for the code shown on your watch.

```powershell
.\scripts\Install-Watch.ps1 `
  -PairAddress '192.168.1.20:37123' `
  -ConnectAddress '192.168.1.20:41867' `
  -ShizukuApk "$env:USERPROFILE\Downloads\shizuku.apk"
```

Install [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools). The script finds ADB on your `PATH`, through `ANDROID_HOME`, or through the project's untracked `local.properties`. You can also specify `-AdbPath 'C:\path\to\adb.exe'`. If already paired, omit `-PairAddress`. If already connected, use `-Serial 'YOUR_WATCH_DEVICE_ID'` instead. The script refuses devices that do not report themselves as watches.

The script installs both APKs, grants Sensors/background Sensors and notification permissions where supported, starts Shizuku, and opens Wrist Flight. It supports the native starter introduced in Shizuku 13.6 as well as the older `start.sh` method. It does **not** start airplane-mode automation. Android's background Sensors permission is restricted; if the grant is rejected, use the app's Setup screen and the troubleshooting section below.

5. On the watch, open **Wrist Flight → Setup → Grant Shizuku access** and approve the request. Verify Sensors and Background both say **allowed**. If necessary, use **Allow Sensors**, then **Allow background Sensors** separately. In app permissions, select **Allow all the time** for Sensors if offered.
6. Allow background running for both Wrist Flight and Shizuku in the watch's battery settings, if your firmware exposes those controls. Allow Wrist Flight notifications for its persistent pause control.
7. Tap **Check airplane control**. In **Status & activity**, verify the connection check returns `OK: disabled` while on the charger. This checks read access and command availability, not permission to change airplane mode; the physical test confirms changes.
8. Return home and tap **Start automation**, then complete the checks below.

### After reboot

If airplane mode stayed on, first turn it off manually using the watch's Quick Settings. Enable Wireless debugging and check its current connection port. Reconnect and restart Shizuku:

```powershell
.\scripts\Install-Watch.ps1 -ConnectAddress '192.168.1.20:41867' -RestartOnly
```

Pairing usually does not need repeating. The monitor attempts to resume at boot and when opened, but elevated control remains unavailable until Shizuku is running. After an update, open Wrist Flight again. Pause and turn off airplane mode before updating or uninstalling.

### Manual ADB alternative

After pairing and connecting, use `adb -s YOUR_WATCH_DEVICE_ID` in place of `adb` if several devices are connected:

```text
adb install -r path/to/shizuku.apk
adb install -r dist/WristFlight-1.1.apk
adb shell pm grant dev.wristflight android.permission.BODY_SENSORS
adb shell pm grant dev.wristflight android.permission.BODY_SENSORS_BACKGROUND
adb shell pm grant dev.wristflight android.permission.POST_NOTIFICATIONS
```

The last two permissions require Android API 33 or later. Open Shizuku explicitly:

```text
adb shell am start -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity
```

For **Shizuku 13.6+**, use the current `adb shell /data/app/.../lib/.../libshizuku.so` command shown by Shizuku, or run the installer with `-RestartOnly` to resolve it automatically. Its installation path changes after updates. Older Shizuku versions use `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`. Then open Wrist Flight:

```text
adb shell am start -n dev.wristflight/.MainActivity
```

Grant Shizuku access inside Wrist Flight, then start automation. Do not run `cmd connectivity airplane-mode enable` remotely during setup: it can drop the ADB connection before you have verified restoration.

## Physical verification

| Check | Expected result |
| --- | --- |
| Start while on the charger | Airplane mode off |
| Undock and leave on a table | Airplane mode stays off |
| Put on wrist and wait for sensor detection plus 3 seconds | Airplane mode on |
| Remove, including while the screen is off | Airplane mode off |
| Put on charger while monitor reports worn | Airplane mode off |
| Fully charged and still docked | Airplane mode remains off |
| Remove during the 3-second confirmation | Airplane mode never turns on |
| Put on/remove rapidly while a command is running | Final mode matches the latest wrist/charger state |
| Pause while airplane mode is on | Airplane mode off, automation paused |
| Leave worn with screen off for 15–30 minutes, then remove | Off-body event still restores airplane mode |
| Disconnect computer / lose Wi-Fi, then remove watch | Local control still restores airplane mode |
| Restart Shizuku or reboot watch | App reports missing access; control returns after Shizuku is restarted |

Check the watch's airplane icon and actual Wi-Fi/Bluetooth/LTE behavior, not just the app's text. Radio reconnection can take additional time. Use **Status & activity → Recent activity** to inspect the last 12 status transitions. These checks are not marked completed by the build tests.

## Behavior and limitations

- While enabled, this app controls airplane mode according to your two rules, including overriding a manual airplane-mode change. To take manual control, first tap **Pause & turn off**.
- Samsung may remember that Wi-Fi or Bluetooth should remain enabled in airplane mode. Wrist Flight changes airplane mode; it does not force separate radio preferences. Cellular calls/data can be interrupted while airplane mode is on.
- A foreground service and the wake-up off-body sensor handle screen-off monitoring. Only brief, timed CPU wake locks are used during confirmation and commands. No continuous accelerometer, heart-rate sampling or polling of a remote server.
- Commands are serialized. Removal, charging or pause during an enable command results in an off request as soon as that command completes. Errors get up to three attempts per target before waiting for an input change or **Retry / refresh**.
- A recovery marker is saved before enabling airplane mode. Restarting the app with unknown sensor state requests airplane mode off before permitting entry again. Automation preferences and that marker are not backed up.
- If Shizuku is stopped, permission is revoked, the app is force-stopped, or the OS kills it, automatic restoration can fail. **Turn airplane mode off in Quick Settings**, restart Shizuku and reopen the app. An APK cannot guarantee persistent privileged operation on an unrooted watch.
- No Internet permission, account, analytics, GPS, heart-rate readings or cloud storage. Only preferences and a bounded activity log are stored privately on the watch. The Shizuku dependency has its own permissions and behavior.

## Troubleshooting

**Background Sensors cannot be granted:** Install through ordinary `adb install -r`, without `--restrict-permissions`. Android requires the installer to allowlist this permission. Grant foreground Sensors first, then background Sensors separately. Check **App settings → Permissions → Sensors → Allow all the time**. If the firmware still refuses background access, the app will block starting instead of claiming reliable screen-off monitoring.

**No wake-up wrist sensor:** The watch firmware does not expose the sensor this build uses. The app blocks starting. It does not substitute heart rate, screen lock or motion as guesses for wrist presence.

**Waiting for wrist sensor:** Inspect **Status & activity** and physically wear/remove the watch. Check the Sensors permissions and background restrictions. A new sensor event is required after undocking. “Unknown” never turns airplane mode on.

**Shizuku starts but commands fail:** Inspect the error in Recent activity. Shizuku's shell permissions and Samsung's connectivity command may differ by firmware. A successful read check is not proof that writes work. Use Quick Settings to restore connectivity.

**Shizuku's permission buttons are clipped:** Its phone layout may not fit the round watch screen. A temporary ADB display-density reduction can expose the buttons. Record `adb shell wm density` first, use `adb shell wm density 200`, approve **Allow all the time**, then restore the original override or run `adb shell wm density reset` if there was no override. On the verified SM-L350, the original physical density was 340 and was restored after authorization.

**ADB disconnects when worn:** Airplane mode can disconnect wireless ADB; that is expected. Off-body restoration uses local Binder IPC, but must be verified on your watch. Put the watch on its charger and reconnect once Wi-Fi returns.

## Build and checks

Requires JDK 17, Android SDK platform 35 and build-tools 35.0.0. Set `JAVA_HOME`, plus `ANDROID_HOME` or an untracked `local.properties` containing `sdk.dir=...`.

```powershell
.\scripts\Build.ps1
```

Or use `gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` (`sh ./gradlew` on macOS/Linux). Gradle 8.11.1 and dependencies are downloaded on the first build. The build script copies the signed APK and its SHA-256 checksum into `dist`.

- Package: `dev.wristflight`; version: `1.1` (version code 2).
- Requires Wear OS / Android API 30+. Targets API 35 and uses the legacy body-sensor permission compatibility path on newer OS versions. This does not certify compatibility with every later Samsung release.
- The published release APK is non-debuggable and signed with the maintainer's Android development key. Rebuilding elsewhere uses your local development key and cannot update the published APK without uninstalling. Keep your key for future updates. Private signing keys are not included in this repository.
- Tests: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Android lint: `app/build/reports/lint-results-debug.html`.
- Policy tests cover charging priority, startup uncertainty, invalid readings, undocking, debounce, repeat readings, pause/resume and target changes during an in-flight command. Binder/process lifecycle and hardware behavior require the physical checks above.

## Platform references

- [Samsung: detecting whether a Galaxy Watch is worn](https://developer.samsung.com/sdp/blog/en/2023/06/14/detect-when-a-galaxy-watch-is-being-worn)
- [Android global settings restrictions](https://developer.android.com/reference/android/provider/Settings.Global)
- [Shizuku setup and reboot limitation](https://shizuku.rikka.app/guide/setup/)
- [Shizuku 13.6 startup-command changes](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)
- [Shizuku UserService API](https://github.com/RikkaApps/Shizuku-API)
- [Background body-sensor access](https://developer.android.com/health-and-fitness/health-services/background-body-sensors)
- [Wear OS permission compatibility](https://developer.android.com/health-and-fitness/health-services/permissions)
- [Android special-use foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)

See `THIRD_PARTY_NOTICES.md` for dependency notices.
