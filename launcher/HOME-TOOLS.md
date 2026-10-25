# On-TV tools

Version 20 / `4.6.0-home-tools` adds one four-square Tools icon to the left of Sources. Weather, Device and Wallpaper open from that menu using the Fire TV D-pad. Back returns to Tools; Back again returns Home. The existing English clock, small Nepal date, scene treatment and compact dock remain in place.

## Device

RAM uses Android's system-wide `ActivityManager.MemoryInfo`: used memory is total minus available, including memory the system can reclaim. Storage uses `StatFs` on the Fire TV's `/data` partition. Both show used / total, percent used and available space in GiB. They describe the Fire TV stick, not the Samsung display or the Mac.

Readings refresh every ten seconds while the Device panel is visible. Closing it, leaving Home or opening a different tool stops sampling. Missing or invalid readings show Unavailable. No root access, package changes or application-private data are involved.

## Weather

Weather fetches directly from Open-Meteo over HTTPS when its panel is opened or refreshed. The current source uses San Mateo's public city-center coordinates `37.563, -122.325`, Fahrenheit and the `America/Los_Angeles` timezone, matching Home's existing San Mateo clock. These are source defaults, not a precise household location or a GPS reading. There is no on-TV location editor in this release. To customize a build, update the explicit location, unit, timezone and fixed query in `NativeWeather.java` together.

The panel shows current conditions and a seven-day forecast, with high / low temperatures and rain probability. Today is labeled only when a forecast row matches the current date in the configured timezone. Missing values remain missing. A saved forecast is marked when it is stale or a refresh fails; no data produces an unavailable state.

Successful forecasts are cached in the launcher process for fifteen minutes. Failures back off for one minute. Requests have a three-second total deadline, a 64 KiB response limit, a nesting limit before JSON parsing and no redirects. Android's normal certificate and hostname validation remain enabled. Closing the panel or leaving Home cancels its outstanding call. The panel credits Open-Meteo. There are no accounts, API keys, background timers or dependencies on the Mac companion for these on-TV tools.

## Wallpaper

Automatic, Morning, Dusk and Night use the existing local wallpaper scenes and the existing appearance preference. Selection applies only after pressing OK; moving focus does not change the wallpaper. Automatic follows San Mateo time. The previous fixed, DUMP-protected remote wallpaper command remains available; see [REMOTE-WALLPAPER.md](REMOTE-WALLPAPER.md).

## Validation

The source includes pure JVM checks for system-total bounds, immutable complete seven-day forecasts, missing data, invalid dates and ranges, malformed numbers and JSON size/depth boundaries. Assembly, unit tests and Android lint are separate from device installation and on-screen verification. An in-place update must use the same signing identity as the installed launcher to preserve its private settings.
