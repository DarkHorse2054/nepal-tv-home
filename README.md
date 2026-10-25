# Nepal TV Home

A calm, dark home screen for Fire TV with Himalayan scenes, an English clock, a small Nepali date and a compact glass dock. Adapted from [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack).

**v18 / 4.4.2-compact-brand-icons - source preview**

![Nepal TV Home on the tested Fire TV](docs/images/home.png)

![Compact glass dock with a focused app](docs/images/dock-focus.png)

## Features

- Fixed wallpaper colors while browsing, with optional time-of-day scenes.
- A frosted dock with rounded tiles, gentle focus lift and compact official Netflix/YouTube marks; other apps use native artwork.
- English San Mateo time/date and a smaller Kathmandu clock and Bikram Sambat civil date.
- Local appearance settings, favorite-app controls and an included clock screensaver.
- **Sources on the TV:** pair a compatible Samsung once, then use the Fire remote to choose from Samsung's input picker. No computer needed.
- An optional phone/web remote with a glass D-pad, app dock, Type on TV, Samsung volume/mute/power-off, Sources and connection recovery.

Netflix, YouTube, Disney+, Paramount+ and Hulu are the default favorites. Install the apps separately; subscriptions are not included.

## Phone remote

The redesigned companion uses a warm Himalayan theme, a floating app dock and matching sheets for typing, Samsung Sources and pairing. It supports keyboard controls, visible focus, reduced motion and narrow phone layouts. Notifications appear inside the active sheet so confirmations and errors stay visible.

<img src="docs/images/remote.png" alt="Nepal Remote with Samsung controls, a sculpted directional pad and a compact app dock" width="360">

*UI preview with a simulated connection. The computer must stay awake while using the real remote.*

Follow [remote setup](remote/README.md) to configure your own devices and start the local server. NPL TV is an optional sixth remote shortcut; its separate app and any subscription are not included.

## Compatibility and setup

The tested setup is a **2018 Fire TV Stick 4K, AFTMM/mantis, Fire OS 6.7.1.1 (Android API 25)** with a **Samsung UN65TU7000BXZA** display. Other devices and firmware are unverified. Android minimum SDK 25 alone is not a compatibility guarantee.

Start with [manual build and setup](docs/SETUP.md). You need JDK 17, Android SDK 34 and Android platform-tools. This source preview has no automatic installer or guaranteed release APK.

Installing the app **does not root, debloat or automatically fix the physical Home button**. The tested owner's native Home changes were separate, device-specific work. No root payload, stock-Home disabling or system-removal workflow is included.

Clock locations currently default to San Mateo and Kathmandu in source. A first-run location wizard is not built. The offline civil calendar ends at BS 2083, then falls back to Gregorian; it is not a panchang. Samsung Sources opens the real picker, without claiming the selected HDMI port through API readback.

## Project notes

- [Samsung Sources and pairing](launcher/NATIVE-SOURCES.md)
- [Glass rendering and verification](launcher/GLASS-DOCK.md)
- [Official compact icon provenance](launcher/BRAND-ICONS.md)
- [Optional webpage remote](remote/README.md)

Code retains the upstream [MIT license](LICENSE). Fonts, calendar data, dependencies and official brand artwork have their own notices in [THIRD_PARTY.md](THIRD_PARTY.md); the project license does not relicense Netflix or YouTube marks. The Himalayan/temple scenes are fictional generated composites. No brand affiliation is implied.
