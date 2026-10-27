package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import okhttp3.Call;

/** Small on-demand utilities; Home stays a clock, a scene and the curated dock. */
final class HomeToolsDialog extends Dialog {
    private enum Page { MENU, DEVICE, WEATHER, WALLPAPER, AIRPLAY }
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout panel;
    private Page page = Page.MENU;
    private TextView memoryValue, memoryAvailable, storageValue, storageAvailable;
    private ProgressBar memoryBar, storageBar;
    private Call weatherCall;
    private int weatherIntent;
    private boolean finished;
    private final Runnable sampleTick = new Runnable() {
        @Override public void run() {
            if (finished || !isShowing() || page != Page.DEVICE) return;
            sampleDevice();
            handler.postDelayed(this, 10_000);
        }
    };

    HomeToolsDialog(Activity activity) {
        super(activity);
        this.activity = activity;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(false);
        setOnDismissListener(dialog -> {
            finished = true;
            leavePage();
            handler.removeCallbacksAndMessages(null);
        });
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(20), dp(24), dp(20));
        panel.setBackground(surface(0xF5111925, 0x60546579, 22));
        setContentView(panel);
        showMenu();
    }

    @Override public void show() {
        super.show();
        arrangeWindow();
    }

    private void arrangeWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.30f);
        window.setLayout(dp(page == Page.WEATHER ? 520 : 400), WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void leavePage() {
        handler.removeCallbacks(sampleTick);
        weatherIntent++;
        NativeWeather.cancel(weatherCall);
        weatherCall = null;
    }

    private void begin(Page next, String title) {
        leavePage();
        page = next;
        panel.removeAllViews();
        text(title, 23, 0xFFF3F5F9, 0);
        arrangeWindow();
    }

    private void showMenu() {
        Page previous = page;
        begin(Page.MENU, "Tools");
        TextView weather = action("Weather", () -> showWeather(true));
        TextView device = action("Device", this::showDevice);
        TextView wallpaper = action("Wallpaper", this::showWallpaper);
        TextView airplay = action("AirPlay", this::showAirPlay);
        if (previous == Page.AIRPLAY) airplay.requestFocus();
        else if (previous == Page.DEVICE) device.requestFocus();
        else if (previous == Page.WALLPAPER) wallpaper.requestFocus();
        else weather.requestFocus();
    }

    private void showAirPlay() {
        begin(Page.AIRPLAY, "AirPlay");
        text("Use the same Wi-Fi as your TV.", 15, 0xFFBAC8D8, 14);
        text("iPhone / iPad", 14, 0xFFBAC8D8, 19);
        text("Control Center → Screen Mirroring", 16, 0xFFF3F5F9, 5);
        text("Mac", 14, 0xFFBAC8D8, 16);
        text("Control Center → Screen Mirroring", 16, 0xFFF3F5F9, 5);
        text("Choose your TV and enter its on-screen code.", 16, 0xFFF3F5F9, 19);
        footer("On Samsung, choose the Samsung TV, not Fire TV.");
        footer("Requires a TV with built-in AirPlay turned on. It receives directly; no Mac relay needed.");
        action("Back", this::showMenu).requestFocus();
    }

    private void showWallpaper() {
        begin(Page.WALLPAPER, "Wallpaper");
        String selected = HomePreferences.getWallpaperMode(activity);
        String[] values = {"auto", "morning", "dusk", "night", "annapurna", "pashupati"};
        String[] names = {"Automatic", "Morning", "Dusk", "Night", "Annapurna", "Pashupati"};
        TextView initial = null;
        for (int i = 0; i < values.length; i++) {
            final String mode = values[i];
            TextView choice = action(names[i] + (mode.equals(selected) ? "  ✓" : ""), () -> {
                HomePreferences.setWallpaperMode(activity, mode);
                dismiss();
            });
            if (mode.equals(selected)) initial = choice;
        }
        footer("Automatic follows San Mateo time.");
        if (initial != null) initial.requestFocus();
    }

    private void showDevice() {
        begin(Page.DEVICE, "Device");
        footer("Fire TV · live every 10 seconds");
        text("RAM", 14, 0xFFBAC8D8, 18);
        memoryValue = text("—", 22, 0xFFF3F5F9, 4);
        memoryBar = progress();
        memoryAvailable = text("", 12, 0xFFB0BDCE, 5);
        text("Storage", 14, 0xFFBAC8D8, 18);
        storageValue = text("—", 22, 0xFFF3F5F9, 4);
        storageBar = progress();
        storageAvailable = text("", 12, 0xFFB0BDCE, 5);
        action("Back", this::showMenu).requestFocus();
        sampleDevice();
        handler.postDelayed(sampleTick, 10_000);
    }

    private void sampleDevice() {
        DeviceUsage ram = null, disk = null;
        try {
            ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(memory);
                ram = new DeviceUsage(memory.totalMem, memory.availMem);
            }
        } catch (RuntimeException unavailable) { /* Report missing totals instead of inventing zero. */ }
        try {
            StatFs storage = new StatFs(Environment.getDataDirectory().getPath());
            disk = new DeviceUsage(storage.getTotalBytes(), storage.getAvailableBytes());
        } catch (RuntimeException unavailable) { /* No root or private application data is read. */ }
        bindUsage(ram, memoryValue, memoryAvailable, memoryBar);
        bindUsage(disk, storageValue, storageAvailable, storageBar);
    }

    private void bindUsage(DeviceUsage usage, TextView value, TextView available, ProgressBar bar) {
        if (usage == null) {
            value.setText("Unavailable");
            available.setText("Try again in a moment.");
            bar.setVisibility(View.INVISIBLE);
            return;
        }
        value.setText(String.format(Locale.US, "%.2f / %.2f GiB   ·   %d%%", gib(usage.used), gib(usage.total), usage.percent));
        available.setText(String.format(Locale.US, "%.2f GiB available", gib(usage.available)));
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(usage.percent);
    }

    private static double gib(long bytes) { return bytes / 1_073_741_824.0; }

    private ProgressBar progress() {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(HomePreferences.getAccentColor(activity)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2B394A));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(5));
        layout.topMargin = dp(9);
        panel.addView(bar, layout);
        return bar;
    }

    private void showWeather(boolean fetch) {
        begin(Page.WEATHER, NativeWeather.LOCATION);
        renderWeather();
        if (fetch) requestWeather();
    }

    private void requestWeather() {
        final int intent = weatherIntent;
        weatherCall = NativeWeather.fetch(() -> handler.post(() -> {
            if (!finished && isShowing() && page == Page.WEATHER && intent == weatherIntent) {
                weatherCall = null;
                renderWeather();
            }
        }));
        renderWeather();
    }

    private void renderWeather() {
        // Rebuild this small fixed table in place; no focus or layout animation runs on a network update.
        View focused = getCurrentFocus();
        Object focusedTag = focused == null ? null : focused.getTag();
        panel.removeAllViews();
        text(NativeWeather.LOCATION, 23, 0xFFF3F5F9, 0);
        NativeWeather.State state = NativeWeather.state();
        WeatherData forecast = state.data;
        if (forecast == null) {
            text(state.loading ? "Loading weather…" : "Weather unavailable", 21, 0xFFE3E9F2, 20);
            footer(state.loading ? "" : "Try again shortly.");
        } else {
            text(temperature(forecast.temperature) + NativeWeather.UNIT + "   " + WeatherData.condition(forecast.code),
                    25, 0xFFF3F5F9, 12);
            String today = localDay();
            for (WeatherData.Day day : forecast.days) {
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(-1, dp(29));
                rowLayout.topMargin = dp(3);
                panel.addView(row, rowLayout);
                rowText(row, day.date.equals(today) ? "Today" : dayLabel(day.date), 82, 0xFFDAE3EE);
                rowText(row, WeatherData.condition(day.code), 162, 0xFFADBCD0);
                rowText(row, temperature(day.high) + "° / " + temperature(day.low) + "°", 92, 0xFFF0F4FA);
                rowText(row, day.rain == null ? "—" : Math.round(day.rain) + "%", 62, 0xFFADBCD0);
            }
            footer("High / low · rain chance");
        }
        String age = state.ageSeconds < 60 ? "Just updated" : "Updated " + state.ageSeconds / 60 + " min ago";
        if (forecast != null) footer((state.stale ? "Saved forecast · " : "") + age + (state.failed ? " · update unavailable" : ""));
        footer("Weather by Open-Meteo · open-meteo.com");
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(40));
        layout.topMargin = dp(12);
        panel.addView(actions, layout);
        TextView refresh = actionIn(actions, state.loading ? "Updating…" : "Refresh", "refresh", () -> {
            requestWeather();
        });
        refresh.setEnabled(!state.loading);
        refresh.setAlpha(state.loading ? .55f : 1f);
        TextView back = actionIn(actions, "Back", "back", this::showMenu);
        if ("back".equals(focusedTag) || state.loading) back.requestFocus();
        else refresh.requestFocus();
        arrangeWindow();
    }

    private static String localDay() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone(NativeWeather.TIMEZONE));
        return format.format(new Date());
    }

    private static String dayLabel(String day) {
        SimpleDateFormat format = new SimpleDateFormat("EEE", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(WeatherData.dateMillis(day)));
    }

    private static String temperature(Double number) { return number == null ? "—" : Long.toString(Math.round(number)); }

    private void rowText(LinearLayout row, String value, int width, int color) {
        TextView view = label(value, 14, color);
        view.setSingleLine(true);
        row.addView(view, new LinearLayout.LayoutParams(dp(width), -1));
    }

    private TextView text(String value, int size, int color, int margin) {
        TextView label = label(value, size, color);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(margin);
        panel.addView(label, layout);
        return label;
    }

    private void footer(String value) {
        if (!value.isEmpty()) text(value, 11, 0xFFA9B8CA, 8);
    }

    private TextView label(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        try { text.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.outfit_modern)); }
        catch (RuntimeException unavailable) { text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); }
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setIncludeFontPadding(false);
        return text;
    }

    private TextView action(String value, Runnable callback) {
        TextView action = label(value, 16, 0xFFE8EDF5);
        action.setPadding(dp(13), 0, dp(13), 0);
        action.setBackground(actionBackground());
        action.setFocusable(true);
        action.setClickable(true);
        action.setOnClickListener(view -> callback.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(43));
        layout.topMargin = dp(10);
        panel.addView(action, layout);
        return action;
    }

    private TextView actionIn(LinearLayout row, String value, String tag, Runnable callback) {
        TextView action = label(value, 14, 0xFFE8EDF5);
        action.setGravity(Gravity.CENTER);
        action.setTag(tag);
        action.setBackground(actionBackground());
        action.setFocusable(true);
        action.setClickable(true);
        action.setOnClickListener(view -> callback.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -1, 1f);
        if (row.getChildCount() > 0) layout.leftMargin = dp(10);
        row.addView(action, layout);
        return action;
    }

    private StateListDrawable actionBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, surface(0xFF283548, HomePreferences.getAccentColor(activity), 12));
        states.addState(new int[] {android.R.attr.state_pressed}, surface(0xFF283548, HomePreferences.getAccentColor(activity), 12));
        states.addState(new int[] {}, surface(0xFF192331, 0x404E6077, 12));
        return states;
    }

    private GradientDrawable surface(int fill, int stroke, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill);
        result.setCornerRadius(dp(radius));
        result.setStroke(dp(1), stroke);
        return result;
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (page == Page.MENU) super.onBackPressed();
        else showMenu();
    }
}
