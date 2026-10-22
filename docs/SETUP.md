# Manual setup

This is a source preview of v18 / 4.4.2-compact-brand-icons. The recorded hardware is a 2018 Fire TV Stick 4K (AFTMM/mantis), Fire OS 6.7.1.1 / Android API 25, connected to a Samsung UN65TU7000BXZA. Other devices and firmware need their own compatibility checks.

The launcher can run without the webpage remote. Native Samsung Sources also connects directly from the stick to the display. A computer is needed to build/install the APK, and must stay awake only if you choose to run the optional webpage remote.

## 1. Build the launcher

Download or clone this repository. Install:

- JDK 17.
- Android SDK platform 34 and build-tools 34.0.0.
- Android SDK platform-tools, including `adb`, on your command path.

Set `ANDROID_HOME` to your SDK directory, or create the ignored `launcher/local.properties` with `sdk.dir=/absolute/path/to/android-sdk`. Use your own actual SDK path.

From the repository root:

```sh
cd launcher
./gradlew assembleDebug
```

The APK is `launcher/app/build/outputs/apk/debug/app-debug.apk`, relative to the repository root. It is locally debug-signed. The source declares compile SDK 34, minimum SDK 25 and target SDK 29. This build does not install anything or change a device's default Home app. No production signing key or compiled APK is included in the source tree.

Optional checks without a TV, from `launcher/`:

```sh
./gradlew testDebugUnitTest lintDebug
```

## 2. Connect your Fire TV

Enable ADB debugging through the Fire TV's normal developer options. Find the stick's local IP in its network information. Put the computer and stick on the same trusted LAN, then connect using the stick's actual address:

```sh
adb connect YOUR_FIRE_TV_IP:5555
adb devices
```

Accept the computer's normal debugging authorization prompt on your television. A device shown as `unauthorized` is not ready for installation. The Fire TV stick's address is separate from the Samsung display's address; do not substitute the display's address here.

## 3. Install and open

The application ID is **`com.gathin.tvhome`**, inherited from upstream. If that ID is already installed, an in-place update needs the same signing key. A different debug key can produce `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

Keep an existing working launcher installed if signatures differ. Do not uninstall it to bypass this error: uninstalling removes its local preferences and pairing, and could remove your working Home app. Use the original signing key or plan a separate application-ID migration before replacing it. Neither is automated here.

For a new installation or a compatible same-signature update, run from the repository root:

```sh
adb install -r launcher/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.gathin.tvhome/.MainActivity
```

Opening the launcher does not establish that the physical Home button selects it. Fire OS behavior depends on the exact device and firmware. This repository does not root devices, disable stock Home, debloat packages or replace Fire OS. The owner's successful native Home change was separate work for one exact build.

Install your streaming apps normally. The default favorites are Netflix, YouTube, Disney+, Paramount+ and Hulu; the launcher does not supply those apps or subscriptions. Appearance and app preferences are stored locally. Hiding a tile does not uninstall its app. A clock screensaver is included; its activation depends on the device's screensaver configuration.

## 4. Pair native Samsung Sources

Use the **Sources** icon immediately left of Wi-Fi at the top right. Long-press it to open **Inputs** management, then choose **TV connection**.

1. Enter the Samsung display's private IPv4 address and exact model code.
2. Choose **Pair TV**, then use the Samsung remote to Allow **Nepal Home** on the display.
3. Choose **Open inputs**. After pairing, a normal press on Sources opens Samsung's picker directly.
4. Use the Fire remote's arrows and OK to select an input. Back sends Samsung Return and exits. Menu sends Return and opens Inputs management.

The Samsung must be on and reachable on the same LAN, with compatible token-authorized TLS WebSocket control on port 8002. Shared source contains no owner's target, token or certificate pin. Pairing stores credentials in private, non-backed-up Android preferences. The identity check requires the configured model; the HTTPS and WebSocket connections must use the same pinned certificate. A changed certificate is rejected. Verify the TV before using **Forget pairing** to renew legitimate pairing.

Keep the Samsung remote available to return to the Fire TV input during initial testing. Switching away hides the Fire TV picture. This flow uses Samsung's visible picker; it does not promise one-click HDMI 1/2 commands or read back the active input. See [native Sources details](../launcher/NATIVE-SOURCES.md).

## 5. Optional webpage remote

The companion runs on macOS or Linux with Python 3.9+ and `adb`. A phone/tablet on the same LAN can use its browser. From the repository root:

```sh
mkdir -p remote/state
cp remote/config.example.json remote/state/config.json
chmod 700 remote/state
chmod 600 remote/state/config.json
```

Edit that private JSON with your own Fire TV address/model and clock labels/timezones. Set `samsung` to `null` if unused, or configure your Samsung separately. The template is not a working device configuration.

```sh
./remote/start.sh --config remote/state/config.json --adb /absolute/path/to/adb
```

Use your actual `adb` path. Open the printed LAN URL on your phone and enter the newly generated terminal pairing code. Keep the computer awake and server running. Optional Samsung webpage controls need `websockets>=15,<16` in the server's Python environment and their own on-TV approval. See [the remote README](../remote/README.md) for installation and control details.

The remote uses HTTP for a trusted LAN; keep it off public tunnels and router forwarding. Keep runtime configuration, PINs, Samsung tokens, ADB authorization, signing keys and recovery data out of commits and shared archives. Native Sources does not need this server.

## Current defaults and limits

The Android launcher defaults to San Mateo and Kathmandu. Its location-selection wizard is not implemented; changing the companion's clock configuration does not change Android launcher defaults. The offline Bikram Sambat civil table ends at BS 2083, with Gregorian fallback outside its range. No panchang accuracy is claimed.

The project has no automatic installer, update watchdog or generic device-rooting/Home-fix workflow. Review [third-party notices](../THIRD_PARTY.md) before redistributing source or artwork; Netflix and YouTube marks are not relicensed under the project's MIT license.
