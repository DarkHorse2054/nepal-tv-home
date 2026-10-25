# Remote wallpaper control

Launcher version 19 / `4.5.0-remote-controls` adds a fixed ADB wallpaper command for the paired browser remote. The built-in scenes remain Automatic, Morning, Dusk and Night. Choosing one changes only `wallpaper_mode` in the launcher's existing private appearance preferences; dimming, tile settings, app order and Samsung pairing are untouched by this command.

```sh
adb -s YOUR_FIRE_TV:5555 shell am broadcast --user 0 \
  -n com.gathin.tvhome/.WallpaperCommandReceiver \
  -a com.gathin.tvhome.action.SET_WALLPAPER --es mode night
```

The only accepted `mode` values are `auto`, `morning`, `dusk` and `night`. The receiver requires the sender to hold `android.permission.DUMP`, a development permission held by Android's ADB shell. It does not expose a network listener or accept image URLs, file paths or arbitrary commands. Ordinary apps without that permission cannot send it commands.

A successful ordered broadcast returns `result=1, data="wallpaper_applied"`. An invalid action or mode returns `result=-1, data="wallpaper_rejected"` without changing preferences. Clients must require the positive result and expected data; Android's default `result=0` is not success and can mean the installed launcher has no receiver. A visible Home screen repaints through its preference listener. When Home is in the background, the new scene appears on its next resume; the receiver itself does not interrupt the foreground app.

The launcher still reads appearance only from its own private preferences. Starting its existing exported Home activity with extras does not change the selected wallpaper. Build and unit checks cover the input vocabulary; device installation, shell permission enforcement and on-screen appearance require separate verification on the target Fire TV.
