package com.gathin.tvhome;

/** The complete, fixed vocabulary accepted by the shell-only wallpaper command. */
final class WallpaperCommand {
    static final String ACTION = "com.gathin.tvhome.action.SET_WALLPAPER";
    static final String EXTRA_MODE = "mode";

    private WallpaperCommand() { }

    static boolean accepts(String action, String mode) {
        return ACTION.equals(action) && ("auto".equals(mode) || "morning".equals(mode)
                || "dusk".equals(mode) || "night".equals(mode));
    }
}
