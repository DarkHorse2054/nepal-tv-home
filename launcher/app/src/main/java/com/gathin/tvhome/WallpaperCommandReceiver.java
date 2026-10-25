package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A fixed ADB command; the manifest requires the sender to hold android.permission.DUMP. */
public final class WallpaperCommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // A positive result plus this fixed data distinguishes an applied command from
        // Android's default result when an older launcher has no matching receiver.
        setResult(-1, "wallpaper_rejected", null);
        if (intent == null) return;
        final String mode;
        try {
            mode = intent.getStringExtra(WallpaperCommand.EXTRA_MODE);
        } catch (RuntimeException malformedExtra) {
            return;
        }
        if (!WallpaperCommand.accepts(intent.getAction(), mode)) return;
        HomePreferences.setWallpaperMode(context, mode);
        setResult(1, "wallpaper_applied", null);
    }
}
