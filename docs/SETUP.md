# Manual setup

This is a source preview of launcher v22 / 4.7.0-wallpapers, with six wallpaper choices and optional Theater 2.2.1. The recorded hardware is a 2018 Fire TV Stick 4K (AFTMM/mantis), Fire OS 6.7.1.1 / Android API 25, connected to a Samsung UN65TU7000BXZA. Other devices and firmware need their own compatibility checks.

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

The first build downloads the pinned Gradle distribution and build dependencies. Use `--offline` only after those artifacts are cached on your computer.

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

The companion runs on macOS or Linux with a maintained Python 3.11+ release and `adb`. A phone/tablet on the same LAN can use its browser. From the repository root:

```sh
python3 -m venv .venv
.venv/bin/python -m pip --isolated install --require-hashes --only-binary=:all: --index-url https://pypi.org/simple -r remote/requirements.txt
export NEPAL_REMOTE_PYTHON="$PWD/.venv/bin/python"
umask 077
mkdir -p remote/state
cp remote/config.example.json remote/state/config.json
chmod 700 remote/state
chmod 600 remote/state/config.json
```

Edit that private JSON with your own Fire TV address/model and clock labels/timezones. Set `samsung` to `null` if unused, or configure your Samsung separately. The template is not a working device configuration.

```sh
./remote/start.sh --config remote/state/config.json --adb /absolute/path/to/adb
```

Use your actual `adb` path. Open the printed LAN URL on your phone and enter the six-digit pairing code shown in the terminal. The companion generates a unique code once per installation and saves it privately in `remote/state/pairing-code.json`; updates and restarts reuse it. Preserve private state when updating, and keep the computer awake and server running.

**Remember this device for 30 days** is checked by default. The same browser at the same remote URL can obtain a fresh eight-hour session after expiry or a companion restart without re-entering the code. Uncheck it on shared devices. Use **Settings → Forget this device** to revoke that browser's saved trust; reconnecting does not extend its absolute thirty-day expiry. Invalid private pairing-code state stops startup rather than silently changing the code. See [pairing and local reset instructions](../remote/README.md#remember-this-browser).

Optional Samsung webpage controls need their own on-TV approval and pin the approved TV certificate. Legacy unpinned pairings must be paired explicitly again. Optional fast keys use the separately installed, pinned scrcpy 5.0.1 server; FFmpeg is needed only for Live video. Browser haptics depend on phone support. See [the remote README](../remote/README.md) for setup and limits.

The remote uses HTTP for a trusted LAN; keep it off public tunnels and router forwarding. Keep runtime configuration, PINs, Samsung tokens, ADB authorization, signing keys and recovery data out of commits and shared archives. Native Sources does not need this server.

## 6. Home Tools and ESPN

Select the four-square **Tools** icon immediately left of Sources. Weather shows San Mateo current conditions and seven days; Device shows live Fire TV RAM/storage; Wallpaper offers Automatic, Morning, Dusk, Night, Annapurna and Pashupati. Automatic follows the existing time-of-day schedule; the two cinematic scenes are fixed manual choices. Native tools work directly on the Fire TV. See [Home Tools](../launcher/HOME-TOOLS.md) for cache, network and location defaults, and [wallpaper artwork](WALLPAPER-ARTWORK.md) for the new scenes.

The phone remote has matching Device, Weather and Wallpaper sheets. Its weather is optional: configure the `weather` object described in [remote setup](../remote/README.md). Original wallpaper modes require launcher v19+; Annapurna and Pashupati require v22. An in-place launcher update must use the same signing identity as your installed copy.

Install **ESPN for Fire TV** from Amazon’s official Appstore. Its store page can also be opened using the documented Amazon deep link:

```sh
adb shell am start -a android.intent.action.VIEW -d 'amzn://apps/android?asin=B00E81O27Y'
```

Choose Get on your own device. Once installed, ESPN appears in the launcher app drawer and opens through **remote Settings → Open ESPN**. Signing in, subscriptions and content availability are handled by ESPN; this source provides no service access. No third-party ESPN APK is bundled.

## 7. Optional Theater

Build and install Theater separately using its [portable build instructions](../framextv/README.md#build-and-install). Phone title requests require Theater 2.2.1 or later. Open it from the launcher app drawer or **remote Settings → Other apps → Open Theater**. The app provides native movie/show search and season/episode selection, then opens the provider's web player inside the app.

For phone requests, choose **Play on Theater** and enter `play Inception (2010)` or `play Breaking Bad s2e3`. A bare title or Search/Find request shows results without autoplaying. The companion wakes Fire TV before the handoff; it does not power on a separate display. Correct-player opening and the empty/loading-grid crash fixes were checked on the tested device, but recent continuous movie playback stalled. Read [Theater's known issues](../framextv/README.md#known-issues) before relying on playback.

## Current defaults and limits

The Android launcher defaults to San Mateo and Kathmandu. Its location-selection wizard is not implemented; changing the companion's clock configuration does not change Android launcher defaults. The offline Bikram Sambat civil table ends at BS 2083, with Gregorian fallback outside its range. No panchang accuracy is claimed.

The project has no automatic installer, update watchdog or generic device-rooting/Home-fix workflow. Review [third-party notices](../THIRD_PARTY.md) before redistributing source or artwork; Netflix and YouTube marks are not relicensed under the project's MIT license.
