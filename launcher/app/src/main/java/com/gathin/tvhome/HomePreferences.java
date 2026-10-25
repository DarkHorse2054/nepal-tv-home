package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

/** Persisted appearance choices, independent of the launcher layout and app list. */
final class HomePreferences {
    private static final String FILE = "nepal_home_appearance";
    private static final String WALLPAPER = "wallpaper_mode";
    private static final String DIM = "dim_percent";
    private static final String TILE = "tile_size";
    private static final String ACCENT = "accent";
    private static final String LABELS = "label_mode";
    private static final String[] WALLPAPER_VALUES = {"auto", "morning", "dusk", "night"};
    private static final String[] WALLPAPER_NAMES = {"Automatic", "Morning", "Dusk", "Night"};
    private static final String[] TILE_VALUES = {"small", "medium", "large"};
    private static final String[] TILE_NAMES = {"Small", "Medium", "Large"};
    private static final String[] ACCENT_VALUES = {"gold", "blue", "teal"};
    private static final String[] ACCENT_NAMES = {"Gold", "Blue", "Teal"};
    private static final String[] LABEL_VALUES = {"focus", "always", "never"};
    private static final String[] LABEL_NAMES = {"On focus", "Always", "Hidden"};
    private static final int[] DIM_VALUES = {0, 10, 20, 30, 40, 50, 60, 70, 80};

    private HomePreferences() { }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String valid(String value, String[] allowed, String fallback) {
        for (String candidate : allowed) if (candidate.equals(value)) return candidate;
        return fallback;
    }

    private static String read(Context context, String key, String[] allowed, String fallback) {
        try {
            return valid(preferences(context).getString(key, fallback), allowed, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    static String getWallpaperMode(Context context) {
        return read(context, WALLPAPER, WALLPAPER_VALUES, "auto");
    }

    /** Additional dimming over the wallpaper's built-in scrims; zero keeps its original brightness. */
    static int getDimPercent(Context context) {
        try {
            return Math.max(0, Math.min(80, preferences(context).getInt(DIM, 0)));
        } catch (ClassCastException ignored) {
            return 0;
        }
    }

    static String getTileSize(Context context) {
        return read(context, TILE, TILE_VALUES, "medium");
    }

    static int getTileWidthDp(Context context) {
        String size = getTileSize(context);
        return "small".equals(size) ? 56 : "large".equals(size) ? 72 : 64;
    }

    static float getTileScale(Context context) {
        return getTileWidthDp(context) / 64f;
    }

    static String getAccent(Context context) {
        return read(context, ACCENT, ACCENT_VALUES, "gold");
    }

    static int getAccentColor(Context context) {
        String accent = getAccent(context);
        return "blue".equals(accent) ? 0xFF96BDDC : "teal".equals(accent) ? 0xFF88C9BB : 0xFFCDBCA5;
    }

    static String getLabelMode(Context context) {
        return read(context, LABELS, LABEL_VALUES, "never");
    }

    static void setWallpaperMode(Context context, String value) {
        preferences(context).edit().putString(WALLPAPER, valid(value, WALLPAPER_VALUES, "auto")).apply();
    }

    static void registerWallpaperListener(Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences(context).registerOnSharedPreferenceChangeListener(listener);
    }

    static void unregisterWallpaperListener(Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener);
    }

    static boolean isWallpaperPreference(String key) {
        return WALLPAPER.equals(key);
    }

    static void setDimPercent(Context context, int value) {
        preferences(context).edit().putInt(DIM, Math.max(0, Math.min(80, value))).apply();
    }

    static void setTileSize(Context context, String value) {
        preferences(context).edit().putString(TILE, valid(value, TILE_VALUES, "medium")).apply();
    }

    static void setAccent(Context context, String value) {
        preferences(context).edit().putString(ACCENT, valid(value, ACCENT_VALUES, "gold")).apply();
    }

    static void setLabelMode(Context context, String value) {
        preferences(context).edit().putString(LABELS, valid(value, LABEL_VALUES, "never")).apply();
    }

    private static int index(String value, String[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    private static String dimName(int percent) {
        return percent == 0 ? "Original brightness" : percent + "% darker";
    }

    /**
     * TV-friendly choices: selecting an option or pressing Back returns to this menu.
     * onChanged should refresh appearance in place. Hidden apps belongs to the app-list owner.
     */
    static void showAppearanceMenu(Activity activity, View anchor, Runnable onChanged, Runnable showHiddenApps) {
        if (activity.isFinishing() || activity.isDestroyed() || !anchor.isAttachedToWindow()) return;
        PopupMenu popup = new PopupMenu(activity, anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 0, 0, "Appearance").setEnabled(false);
        menu.add(0, 1, 1, "Wallpaper · " + WALLPAPER_NAMES[index(getWallpaperMode(activity), WALLPAPER_VALUES)]);
        menu.add(0, 2, 2, "Wallpaper dim · " + dimName(getDimPercent(activity)));
        menu.add(0, 3, 3, "Tile size · " + TILE_NAMES[index(getTileSize(activity), TILE_VALUES)]);
        menu.add(0, 4, 4, "Focus accent · " + ACCENT_NAMES[index(getAccent(activity), ACCENT_VALUES)]);
        menu.add(0, 5, 5, "App labels · " + LABEL_NAMES[index(getLabelMode(activity), LABEL_VALUES)]);
        menu.add(0, 6, 6, "Hidden apps").setEnabled(showHiddenApps != null);
        menu.add(0, 7, 7, "Back");
        Runnable returnToMenu = () -> anchor.post(() -> showAppearanceMenu(activity, anchor, onChanged, showHiddenApps));
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showChoices(activity, "Wallpaper", WALLPAPER_NAMES, index(getWallpaperMode(activity), WALLPAPER_VALUES),
                            selected -> setWallpaperMode(activity, WALLPAPER_VALUES[selected]), onChanged, returnToMenu);
                    return true;
                case 2:
                    String[] names = new String[DIM_VALUES.length];
                    int selectedDim = -1;
                    for (int i = 0; i < DIM_VALUES.length; i++) {
                        names[i] = dimName(DIM_VALUES[i]);
                        if (DIM_VALUES[i] == getDimPercent(activity)) selectedDim = i;
                    }
                    showChoices(activity, "Wallpaper dim", names, selectedDim,
                            selected -> setDimPercent(activity, DIM_VALUES[selected]), onChanged, returnToMenu);
                    return true;
                case 3:
                    showChoices(activity, "Tile size", TILE_NAMES, index(getTileSize(activity), TILE_VALUES),
                            selected -> setTileSize(activity, TILE_VALUES[selected]), onChanged, returnToMenu);
                    return true;
                case 4:
                    showChoices(activity, "Focus accent", ACCENT_NAMES, index(getAccent(activity), ACCENT_VALUES),
                            selected -> setAccent(activity, ACCENT_VALUES[selected]), onChanged, returnToMenu);
                    return true;
                case 5:
                    showChoices(activity, "App labels", LABEL_NAMES, index(getLabelMode(activity), LABEL_VALUES),
                            selected -> setLabelMode(activity, LABEL_VALUES[selected]), onChanged, returnToMenu);
                    return true;
                case 6:
                    if (showHiddenApps != null) showHiddenApps.run();
                    return true;
                case 7:
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private interface Selection { void choose(int selected); }

    private static void showChoices(Activity activity, String title, String[] names, int selected,
                                    Selection selection, Runnable onChanged, Runnable returnToMenu) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(names, selected, (choice, which) -> {
                    selection.choose(which);
                    choice.dismiss();
                    if (onChanged != null) onChanged.run();
                    returnToMenu.run();
                })
                .setNegativeButton("Back", (choice, which) -> returnToMenu.run())
                .create();
        dialog.setOnCancelListener(choice -> returnToMenu.run());
        dialog.show();
    }
}
