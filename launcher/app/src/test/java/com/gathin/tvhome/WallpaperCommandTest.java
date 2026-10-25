package com.gathin.tvhome;

import static org.junit.Assert.*;
import org.junit.Test;

/** Input-boundary checks run without Android, ADB, network access or preference files. */
public class WallpaperCommandTest {
    @Test public void acceptsOnlyTheFourBuiltInScenes() {
        for (String mode : new String[] {"auto", "morning", "dusk", "night"}) {
            assertTrue(mode, WallpaperCommand.accepts(WallpaperCommand.ACTION, mode));
        }
    }

    @Test public void rejectsMissingOrUnrecognizedModesWithoutFallback() {
        for (String mode : new String[] {null, "", "Morning", "auto ", " night", "night\n",
                "random", "https://example.com/photo.jpg", "../../preferences", "night; input keyevent 3"}) {
            assertFalse(String.valueOf(mode), WallpaperCommand.accepts(WallpaperCommand.ACTION, mode));
        }
    }

    @Test public void rejectsOtherActionsEvenWithAValidMode() {
        for (String action : new String[] {null, "", "android.intent.action.MAIN",
                WallpaperCommand.ACTION + ".extra", WallpaperCommand.ACTION.toLowerCase(java.util.Locale.ROOT)}) {
            assertFalse(String.valueOf(action), WallpaperCommand.accepts(action, "night"));
        }
    }
}
