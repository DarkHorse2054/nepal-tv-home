# Nepal TV Home

A calm, dark home screen for Fire TV with Himalayan scenes, an English clock, a small Nepali date and a compact glass dock. Adapted from [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack).

**v21 / 4.6.1-airplay-guide - source preview**

![Nepal TV Home on the tested Fire TV](docs/images/home.png)

![Compact glass dock with a focused app](docs/images/dock-focus.png)

## Features

- Fixed wallpaper colors while browsing, with optional time-of-day scenes.
- A frosted dock with rounded tiles, gentle focus lift and compact official Netflix/YouTube marks; other apps use native artwork.
- English San Mateo time/date and a smaller Kathmandu clock and Bikram Sambat civil date.
- Local appearance settings, favorite-app controls and an included clock screensaver.
- **Sources on the TV:** pair a compatible Samsung once, then use the Fire remote to choose from Samsung's input picker. No computer needed.
- On-TV Tools for live Fire TV RAM/storage, current weather and a seven-day forecast, and wallpaper choices.
- AirPlay connection help in TV Home and the phone remote, using a compatible television's built-in receiver.
- An optional phone/web remote with the same tools, a glass D-pad, app dock, Type on TV, Samsung controls and connection recovery.
- An ESPN launch shortcut in remote Settings; install the official app separately.

Netflix, YouTube, Disney+, Paramount+ and Hulu are the default favorites. Install the apps separately; subscriptions are not included.

## Phone remote

The redesigned companion uses a warm Himalayan theme, a floating app dock and matching sheets for typing, Samsung Sources and pairing. Device readings refresh every ten seconds while open. Weather shows current conditions, daily highs/lows and a seven-day forecast. Wallpaper applies Automatic, Morning, Dusk or Night through the v19+ launcher’s protected fixed command. AirPlay opens local connection instructions, available before pairing and while the TV connection is offline. It supports keyboard controls, visible focus, reduced motion and narrow phone layouts. Notifications appear inside the active sheet so confirmations and errors stay visible.

<img src="docs/images/remote.png" alt="Nepal Remote with Samsung controls, a sculpted directional pad and a compact app dock" width="360">

*Remote interface. The computer must stay awake while using the phone remote; on-TV Tools run directly on the Fire TV. Native AirPlay streams directly to a compatible television and does not require the companion computer.*

Follow [remote setup](remote/README.md) to configure your own devices and start the local server. NPL TV is an optional sixth dock shortcut. ESPN opens from remote Settings or the launcher’s app drawer after installation. These apps and their subscriptions are not included.

## Compatibility and setup

The tested setup is a **2018 Fire TV Stick 4K, AFTMM/mantis, Fire OS 6.7.1.1 (Android API 25)** with a **Samsung UN65TU7000BXZA** display. Other devices and firmware are unverified. Android minimum SDK 25 alone is not a compatibility guarantee.

Start with [manual build and setup](docs/SETUP.md). You need JDK 17, Android SDK 34 and Android platform-tools. This source preview has no automatic installer or guaranteed release APK.

Installing the app **does not root, debloat or automatically fix the physical Home button**. The tested owner's native Home changes were separate, device-specific work. No root payload, stock-Home disabling or system-removal workflow is included.

Clock locations currently default to San Mateo and Kathmandu in source. On-TV weather defaults to San Mateo’s city center in Fahrenheit; its location is currently edited in source. The companion’s optional weather location is configurable and disabled in the public example. A first-run location wizard is not built. The offline civil calendar ends at BS 2083, then falls back to Gregorian; it is not a panchang. Samsung Sources opens the real picker, without claiming the selected HDMI port through API readback.

## Project notes

- [On-TV Device, Weather and Wallpaper tools](launcher/HOME-TOOLS.md)
- [Native television AirPlay setup and verification](launcher/AIRPLAY.md)
- [Protected remote wallpaper command](launcher/REMOTE-WALLPAPER.md)
- [Samsung Sources and pairing](launcher/NATIVE-SOURCES.md)
- [Glass rendering and verification](launcher/GLASS-DOCK.md)
- [Official compact icon provenance](launcher/BRAND-ICONS.md)
- [Optional webpage remote](remote/README.md)

Code retains the upstream [MIT license](LICENSE). Fonts, calendar data, dependencies and official brand artwork have their own notices in [THIRD_PARTY.md](THIRD_PARTY.md); the project license does not relicense Netflix or YouTube marks. The Himalayan/temple scenes are fictional generated composites. No brand affiliation is implied.
