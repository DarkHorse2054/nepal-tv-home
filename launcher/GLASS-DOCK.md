# Glass dock - v16 / 4.4-glass-dock

This update adds a darker glass treatment around the compact dock while keeping the English interface, minimal text and existing Nepal home layout. The wallpaper palette stays fixed as focus moves between apps.

## Appearance and rendering

The dock combines a blurred wallpaper sample with neutral reflections and a fine rim. Blur is generated off the UI thread from a sample no larger than 240 × 135 pixels, then cached. This is a lightweight glass approximation for the Fire TV's Android 7 platform; it does not use Apple's Liquid Glass rendering engine or continuously blur the full screen.

App tiles use rounded-square corners with a radius equal to 25% of their width. The focused tile scales to 1.2× and lifts 6 dp. Focus-in takes 220 ms; focus-out takes 180 ms. Transform animation preserves the row's layout, and the dock reserves room for the enlarged end tiles.

Version 16 used each installed app's native artwork, requesting high-density resources where available. Version 17 adds two official compact-mark exceptions for Netflix and YouTube, documented in [BRAND-ICONS.md](BRAND-ICONS.md); other apps keep native artwork. Output rendering is capped at 384 pixels per side and preserves aspect ratio. Plain native raster icons are not enlarged to manufacture detail; vectors can render at the bounded output resolution. Layered artwork may still contain low-resolution raster children. Resource or memory failures retain a usable native icon. Focus does not recolor the background.

## Recorded verification

Version 16 / 4.4-glass-dock was installed on the owner's Fire TV. Actual idle, Disney+ focus and Hulu focus captures were checked; the dock edges and enlarged tiles were not clipped. A fixed wallpaper pixel region was identical between the Disney+ and Hulu captures. Pairing, appearance and app preferences were byte-identical before and after the update.

APK assembly and lint passed with zero errors and 44 warnings. The blur implementation passed ten independent comparisons against a reference implementation. These checks establish the recorded behavior on this device; they are not a performance benchmark or a compatibility guarantee for other Fire OS builds.

Glass-strength and Reduce Motion controls in Appearance are possible follow-up ideas. They are not implemented in this update.
