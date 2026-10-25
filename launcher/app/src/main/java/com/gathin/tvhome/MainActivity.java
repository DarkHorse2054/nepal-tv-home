package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Nepal home with a time-of-day wallpaper, a compact app dock, and a spring-driven focus highlight.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private NepalBackdrop backdrop;
    private DockDecoration dockDecoration;
    private TextView btnWifi;
    private SamsungSourcesDialog sourcesDialog;
    private HomeToolsDialog toolsDialog;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Retained while visible so an ADB wallpaper command repaints Home immediately. */
    private final SharedPreferences.OnSharedPreferenceChangeListener wallpaperListener = (preferences, key) -> {
        if (HomePreferences.isWallpaperPreference(key) && backdrop != null) backdrop.refreshAppearance();
    };
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateClocks();
            updateWifiLabel();
            backdrop.refreshAppearance();
            handler.postDelayed(this, MINUTE_MS - System.currentTimeMillis() % MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyFont(findViewById(R.id.root));
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        backdrop = findViewById(R.id.backdrop);
        ((TextView) findViewById(R.id.clock)).setShadowLayer(9 * density, 0, 2 * density, 0xAA000000);
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, density, backdrop);
        grid.addItemDecoration(dockDecoration);
        backdrop.setOnSceneReadyListener(() -> grid.invalidate());
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            updateDockPadding();
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(300f).setDampingRatio(0.85f));
        }

        View btnSettings = findViewById(R.id.btnSettings);
        View btnTools = findViewById(R.id.btnTools);
        btnTools.setOnClickListener(view -> {
            if (toolsDialog != null && toolsDialog.isShowing()) return;
            toolsDialog = new HomeToolsDialog(this);
            toolsDialog.show();
        });
        View btnSources = findViewById(R.id.btnSources);
        btnSources.setOnClickListener(v -> showSources(false));
        btnSources.setOnLongClickListener(v -> {
            showSources(true);
            return true;
        });
        btnWifi.setOnClickListener(v -> {
            Intent wifi = new Intent(Settings.ACTION_WIFI_SETTINGS);
            if (canOpen(wifi)) open(wifi);
            else showFireSettings(btnWifi);
        });
        btnSettings.setOnClickListener(this::showHomeOptions);

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnTools) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnWifi.setOnKeyListener(pillKeys);
        btnTools.setOnKeyListener(pillKeys);
        btnSources.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomePreferences.registerWallpaperListener(this, wallpaperListener);
        glassShown = false;
        glass.setAlpha(0f);
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        applyAppearance();
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateClocks();
        updateWifiLabel();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the app we left from, or the same slot if that app is gone. Always refocus: after
        // reload() the view that holds focus may no longer show the app it had.
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
        });
    }

    private void showSources(boolean manageConnection) {
        if (sourcesDialog != null && sourcesDialog.isShowing()) return;
        sourcesDialog = new SamsungSourcesDialog(this, manageConnection);
        sourcesDialog.show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        HomePreferences.unregisterWallpaperListener(this, wallpaperListener);
        if (sourcesDialog != null) sourcesDialog.dismiss();
        if (toolsDialog != null) toolsDialog.dismiss();
        handler.removeCallbacks(minuteTick);
        adapter.cancelPendingPress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home returns to the launcher, even when Samsung navigation was open before changing inputs.
        if (sourcesDialog != null) sourcesDialog.dismiss();
        if (toolsDialog != null) toolsDialog.dismiss();
        // Intent extras never change appearance. Re-read only our private saved mode.
        if (backdrop != null) backdrop.refreshAppearance();
    }

    @Override
    public void onBackPressed() {
        if (adapter.handleBack()) return;
        // Home screen: Back goes nowhere.
    }

    @Override
    public void onAppsChanged(String focusedKey) {
        final int fallback = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(focusedKey);
            focusTile(pos >= 0 ? pos : fallback);
        });
    }

    private void updateDockPadding() {
        int dockRow = Math.round((adapter.rowHeightDp() + DockDecoration.TOP_INSET_DP + DockDecoration.BOTTOM_INSET_DP) * density);
        // Start the next row beyond the viewport so no stray icon edges peek below the dock.
        int want = Math.max(0, grid.getHeight() - dockRow);
        if (grid.getPaddingTop() != want) {
            grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
        }
    }

    private void applyAppearance() {
        int color = HomePreferences.getAccentColor(this);
        int iconWidth = HomePreferences.getTileWidthDp(this);
        adapter.setAppearance(iconWidth, color, HomePreferences.getLabelMode(this));
        FrameLayout.LayoutParams gridLayout = (FrameLayout.LayoutParams) grid.getLayoutParams();
        int dockWidth = Math.round(((iconWidth + 24) * COLUMNS + 2 * DockDecoration.SIDE_GUTTER_DP) * density);
        if (gridLayout.width != dockWidth) {
            gridLayout.width = dockWidth;
            gridLayout.gravity = Gravity.CENTER_HORIZONTAL;
            grid.setLayoutParams(gridLayout);
        }
        glass.setAccentColor(color);
        dockDecoration.setAccentColor(color);
        ((TextView) findViewById(R.id.omSymbol)).setTextColor(color);
        backdrop.refreshAppearance();
        grid.invalidateItemDecorations();
        updateDockPadding();
        View focused = getCurrentFocus();
        if (focused != null && focused.getId() == R.id.card) onAppsChanged(lastFocusedKey);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - adapter.focusLiftDp() * density;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth()) * AppAdapter.FOCUS_SCALE);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Keep the dock parked at the bottom while the selected icon lifts above its shelf.
            int decoratedTop = item.getTop() - Math.round(DockDecoration.TOP_INSET_DP * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
        } else {
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        focusTile(position, 0);
    }

    private void focusTile(int position, int attempt) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocusFromTouch();
            return;
        }
        // A first layout or a restored dock can require no scrolling at all. Retry
        // after layout instead of relying on an idle event that may never fire.
        grid.smoothScrollToPosition(pos);
        if (attempt < 12) grid.postDelayed(() -> focusTile(pos, attempt + 1), 100);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void applyFont(View view) {
        if (view instanceof TextView) {
            try {
                ((TextView) view).setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, view.getId() == R.id.omSymbol ? R.font.nepali : R.font.outfit_modern));
            } catch (android.content.res.Resources.NotFoundException ignored) {
                ((TextView) view).setTypeface(android.graphics.Typeface.DEFAULT);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyFont(group.getChildAt(i));
        }
    }

    private void showFireSettings(View anchor) {
        Intent nativeSettings = new Intent()
                .setClassName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (canOpen(nativeSettings)) {
            open(nativeSettings);
            return;
        }
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        String[] labels = {"Network", "Apps", "My Fire TV", "Preferences", "Remotes & Bluetooth", "Display & Sound"};
        String[] activities = {"tv.network.NetworkActivity", "tv.applications.ApplicationsActivity", "tv.device.DeviceActivity", "tv.preferences.PreferencesActivity", "tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity", "tv.display_sounds.DisplayAndSoundsActivity"};
        List<Intent> available = new ArrayList<>();
        for (int index = 0; index < labels.length; index++) {
            Intent intent = new Intent().setClassName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2." + activities[index]);
            // Some categories require Amazon-only permissions. Keep only launchable entries.
            if (canOpen(intent)) {
                menu.getMenu().add(0, available.size(), available.size(), labels[index]);
                available.add(intent);
            }
        }
        if (available.isEmpty()) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        menu.setOnMenuItemClickListener(item -> {
            open(available.get(item.getItemId()));
            return true;
        });
        menu.show();
    }

    private void showHomeOptions(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "Appearance");
        menu.getMenu().add(0, 1, 1, "Fire TV settings");
        Intent tvControls = new Intent(Intent.ACTION_MAIN)
                .setClassName("com.amazon.tv.devicecontrolsettings",
                        "com.amazon.tv.devicecontrolsettings.screens.main_menu.OzOobeSettingsActivity")
                .setData(android.net.Uri.parse("devicecontrolsettings://"));
        if (canOpen(tvControls)) menu.getMenu().add(0, 2, 2, "TV controls");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 0) {
                HomePreferences.showAppearanceMenu(this, anchor, this::applyAppearance,
                        () -> adapter.showHiddenApps(anchor));
            } else if (item.getItemId() == 2) {
                open(tvControls);
            } else {
                showFireSettings(anchor);
            }
            return true;
        });
        menu.show();
    }

    private void updateClocks() {
        Calendar local = Calendar.getInstance(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
        boolean fullDay = android.text.format.DateFormat.is24HourFormat(this);
        ((TextView) findViewById(R.id.clock)).setText(NepaliCalendar.clockEnglish(local, fullDay));
        ((TextView) findViewById(R.id.ampm)).setText(fullDay ? "" : (local.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM"));
        java.text.SimpleDateFormat localDate = new java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US);
        localDate.setTimeZone(local.getTimeZone());
        ((TextView) findViewById(R.id.localDate)).setText(localDate.format(local.getTime()));
        Calendar nepal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kathmandu"));
        nepal.setTimeInMillis(local.getTimeInMillis());
        ((TextView) findViewById(R.id.nepalDate)).setText(NepaliCalendar.get(this).dateEnglish(nepal));
        String phase = nepal.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        ((TextView) findViewById(R.id.kathmanduClock)).setText("Kathmandu · " + NepaliCalendar.clockEnglish(nepal, fullDay) + (fullDay ? "" : " " + phase));
    }

    /** Keep connection details accessible without adding text to the home screen. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                text = "Wi-Fi connected";
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText("");
        btnWifi.setContentDescription(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean canOpen(Intent intent) {
        android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(intent, 0);
        if (resolved == null || resolved.activityInfo == null) return false;
        android.content.pm.ActivityInfo activity = resolved.activityInfo;
        return activity.enabled && activity.applicationInfo.enabled && activity.exported
                && (activity.permission == null || getPackageManager().checkPermission(
                        activity.permission, getPackageName()) == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

}
