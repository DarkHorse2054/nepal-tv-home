package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewConfiguration;
import android.view.animation.OvershootInterpolator;
import android.view.WindowManager;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites). Order, names and visibility are editable with the remote.
 * Installed apps and their data are never changed by these edits.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Refocus this component after an edit; null means no app remains. */
        default void onAppsChanged(String focusedKey) { }
    }

    static final class Entry {
        final String label;
        final String originalLabel;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, String originalLabel, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.originalLabel = originalLabel;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.20f;
    private static final int FOCUS_LIFT_DP = 6;

    /** Shared by the card mask and floating focus rim at every configured icon size. */
    static float cornerRadius(float widthPx) {
        return Math.max(0f, widthPx) * 0.25f;
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();
    private final List<Entry> catalog = new ArrayList<>();
    private final AppPreferences preferences;
    private RecyclerView recycler;
    private String selectedKey;
    private String movingKey;
    private List<Entry> beforeMove;
    private boolean editKeyHeld;
    private View confirmView;
    private String confirmKey;
    private boolean confirmDown;
    private boolean suppressConfirmUp;
    private Runnable pendingLongPress;
    private int tileWidthDp = 64;
    private int accentColor = 0xFFE2E8F0;
    private String labelsMode = "never";

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.preferences = new AppPreferences(ctx);
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    void reload() {
        clearConfirmPress(true);
        // Leaving Home during an unfinished move cancels it rather than silently saving it.
        movingKey = null;
        beforeMove = null;
        editKeyHeld = false;
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            String key = component.flattenToShortString();
            if (!seen.add(key)) continue;
            // Prefer the app's high-density artwork; loading a TV banner into a square loses
            // both its proportions and its real icon identity.
            Drawable banner = null;
            Drawable icon = AppIconLoader.load(ctx, pm, ri);
            String original = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(preferences.name(key, original), original, component, banner, icon));
        }
        List<String> order = preferences.order();
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < order.size(); i++) ranks.put(order.get(i), i);
        Collections.sort(fresh, (a, b) -> {
            Integer ra = ranks.get(key(a)), rb = ranks.get(key(b));
            if (ra != null || rb != null) {
                if (ra == null) return 1;
                if (rb == null) return -1;
                return Integer.compare(ra, rb);
            }
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                if (fa != fb) return Integer.compare(fa, fb);
            }
            return a.originalLabel.compareToIgnoreCase(b.originalLabel);
        });
        catalog.clear();
        catalog.addAll(fresh);
        Set<String> userHidden = preferences.hidden();
        items.clear();
        for (Entry entry : fresh) if (!userHidden.contains(key(entry))) items.add(entry);
        notifyDataSetChanged();
    }

    private static String key(Entry entry) {
        return entry.component.flattenToShortString();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView view) {
        super.onAttachedToRecyclerView(view);
        recycler = view;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView view) {
        clearConfirmPress(true);
        if (recycler == view) recycler = null;
        super.onDetachedFromRecyclerView(view);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        if (confirmView == holder.card) clearConfirmPress(true);
        super.onViewRecycled(holder);
    }

    int cardHeightDp() { return tileWidthDp; }
    int rowHeightDp() { return cardHeightDp() + ("never".equals(labelsMode) ? 24 : 45); }
    int focusLiftDp() { return FOCUS_LIFT_DP; }

    void setAppearance(int widthDp, int accent, String labels) {
        int width = Math.max(56, Math.min(72, widthDp));
        String mode = "always".equals(labels) ? "always" : "focus".equals(labels) ? "focus" : "never";
        if (tileWidthDp == width && accentColor == accent && labelsMode.equals(mode)) return;
        tileWidthDp = width;
        accentColor = accent;
        labelsMode = mode;
        notifyDataSetChanged();
    }

    private void bindDimensions(Holder holder) {
        float density = ctx.getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams row = holder.itemView.getLayoutParams();
        row.height = Math.round(rowHeightDp() * density);
        holder.itemView.setLayoutParams(row);
        FrameLayout.LayoutParams card = (FrameLayout.LayoutParams) holder.card.getLayoutParams();
        card.width = Math.round(tileWidthDp * density);
        card.height = Math.round(cardHeightDp() * density);
        holder.card.setLayoutParams(card);
        FrameLayout.LayoutParams name = (FrameLayout.LayoutParams) holder.name.getLayoutParams();
        name.width = Math.round(Math.max(80, tileWidthDp + 16) * density);
        holder.name.setTextSize(11f);
        name.topMargin = Math.round((cardHeightDp() + 7) * density);
        holder.name.setLayoutParams(name);
        FrameLayout.LayoutParams icon = (FrameLayout.LayoutParams) holder.icon.getLayoutParams();
        icon.width = ViewGroup.LayoutParams.MATCH_PARENT;
        icon.height = ViewGroup.LayoutParams.MATCH_PARENT;
        icon.gravity = android.view.Gravity.CENTER;
        icon.bottomMargin = 0;
        holder.icon.setLayoutParams(icon);
        holder.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    private void updateLabel(Holder holder, boolean focused) {
        boolean show = "always".equals(labelsMode) || ("focus".equals(labelsMode) && focused);
        holder.name.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        holder.name.setAlpha(show ? 1f : 0f);
        holder.name.setTextColor(focused ? accentColor : 0xFFC1CBD8);
        holder.name.setTranslationY(0f);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, this, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.card.setContentDescription(e.label);
        bindDimensions(h);
        h.card.animate().cancel();
        h.name.animate().cancel();
        boolean focused = h.card.isFocused();
        updateLabel(h, focused);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        h.card.setTranslationY(focused ? -focusLiftDp() * ctx.getResources().getDisplayMetrics().density : 0f);
        h.card.setTranslationZ(focused ? 8 * ctx.getResources().getDisplayMetrics().density : 0f);
        h.banner.setImageDrawable(null);
        h.banner.setVisibility(View.GONE);
        h.icon.setImageDrawable(e.icon);
        h.icon.setVisibility(View.VISIBLE);
        h.label.setVisibility(View.GONE);
        h.card.setOnClickListener(v -> {
            if (movingKey != null) finishMove(true);
            else if (!editKeyHeld) launch(e);
        });
        h.card.setOnLongClickListener(v -> {
            if (movingKey != null) return true;
            editKeyHeld = true;
            showOptions(e, v);
            return true;
        });
    }

    /** Menu entry point for the selected app; long OK and the remote Menu key also open it. */
    void showAppOptions(View anchor) {
        int position = positionOf(selectedKey);
        if (position < 0 && !items.isEmpty()) position = 0;
        if (position >= 0) showOptions(items.get(position), anchor);
    }

    private void showOptions(Entry entry, View anchor) {
        clearConfirmPress(true);
        if (movingKey != null) finishMove(true);
        final String entryKey = key(entry);
        final List<String> options = new ArrayList<>();
        options.add("Move");
        int position = positionOf(entryKey);
        if (position >= dockSize) options.add("Add to favorites");
        else if (items.size() > dockSize) options.add("Remove from favorites");
        options.add("Rename");
        options.add("Hide");
        options.add("App info");
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(entry.label)
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    editKeyHeld = false;
                    switch (options.get(which)) {
                        case "Move":
                            movingKey = entryKey;
                            beforeMove = new ArrayList<>(items);
                            Toast.makeText(ctx, "Move with arrows. OK to save, Back to cancel.", Toast.LENGTH_LONG).show();
                            restoreFocus(entryKey);
                            break;
                        case "Add to favorites":
                            moveEntry(entryKey, Math.min(dockSize - 1, items.size() - 1));
                            saveVisibleOrder();
                            reloadAndRestore(entryKey);
                            break;
                        case "Remove from favorites":
                            moveEntry(entryKey, Math.min(dockSize, items.size() - 1));
                            saveVisibleOrder();
                            reloadAndRestore(entryKey);
                            break;
                        case "Rename": rename(entry); break;
                        case "Hide":
                            if (items.size() <= 1) {
                                Toast.makeText(ctx, "Keep at least one app visible.", Toast.LENGTH_SHORT).show();
                                restoreFocus(entryKey);
                                break;
                            }
                            int oldPosition = positionOf(entryKey);
                            saveVisibleOrder();
                            preferences.setHidden(entryKey, true);
                            reload();
                            restoreFocus(keyAt(Math.min(oldPosition, items.size() - 1)));
                            break;
                        case "App info":
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + entry.pkg));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try { ctx.startActivity(intent); }
                            catch (ActivityNotFoundException ignored) {
                                Toast.makeText(ctx, "App info is unavailable.", Toast.LENGTH_SHORT).show();
                            }
                            break;
                    }
                })
                .setNegativeButton("Cancel", (d, which) -> restoreFocus(entryKey))
                .create();
        dialog.setOnCancelListener(d -> restoreFocus(entryKey));
        // The key-up that ended a long OK belongs to editing, never to launching the app.
        dialog.setOnDismissListener(d -> editKeyHeld = false);
        dialog.show();
    }

    private void rename(Entry entry) {
        EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(36)});
        input.setText(entry.label);
        input.selectAll();
        int padding = Math.round(24 * ctx.getResources().getDisplayMetrics().density);
        FrameLayout frame = new FrameLayout(ctx);
        frame.setPadding(padding, 0, padding, 0);
        frame.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        String entryKey = key(entry);
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Rename app")
                .setView(frame)
                .setPositiveButton("Save", (d, which) -> {
                    saveVisibleOrder();
                    preferences.setName(entryKey, input.getText().toString());
                    reloadAndRestore(entryKey);
                })
                .setNeutralButton("Reset name", (d, which) -> {
                    saveVisibleOrder();
                    preferences.setName(entryKey, null);
                    reloadAndRestore(entryKey);
                })
                .setNegativeButton("Cancel", (d, which) -> restoreFocus(entryKey))
                .create();
        dialog.setOnCancelListener(d -> restoreFocus(entryKey));
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        dialog.show();
    }

    /** Restore user-hidden apps. The configured system/helper exclusions remain excluded. */
    void showHiddenApps(View anchor) {
        Set<String> hiddenKeys = preferences.hidden();
        List<Entry> hiddenEntries = new ArrayList<>();
        for (Entry entry : catalog) if (hiddenKeys.contains(key(entry))) hiddenEntries.add(entry);
        if (hiddenEntries.isEmpty()) {
            Toast.makeText(ctx, "No hidden apps.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[hiddenEntries.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = hiddenEntries.get(i).label;
        new AlertDialog.Builder(ctx)
                .setTitle("Show hidden apps")
                .setItems(labels, (d, which) -> {
                    Entry entry = hiddenEntries.get(which);
                    preferences.setHidden(key(entry), false);
                    reloadAndRestore(key(entry));
                })
                .setPositiveButton("Show all", (d, which) -> {
                    for (Entry entry : hiddenEntries) preferences.setHidden(key(entry), false);
                    reloadAndRestore(selectedKey);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Lets MainActivity consume Back before its home-screen no-op. */
    boolean handleBack() {
        if (movingKey == null) return false;
        finishMove(false);
        return true;
    }

    /** Cancel a held OK when Home loses its window, even if the tile retains View focus. */
    void cancelPendingPress() {
        clearConfirmPress(true);
        editKeyHeld = false;
    }

    private void clearConfirmPress(boolean suppressRelease) {
        if (pendingLongPress != null && confirmView != null)
            confirmView.removeCallbacks(pendingLongPress);
        if (confirmView != null) confirmView.setPressed(false);
        if (suppressRelease && confirmDown) suppressConfirmUp = true;
        pendingLongPress = null;
        confirmView = null;
        confirmKey = null;
        confirmDown = false;
    }

    private void showHeldAppOptions() {
        if (!confirmDown || confirmView == null) return;
        int position = positionOf(confirmKey);
        RecyclerView.ViewHolder holder = recycler == null ? null
                : recycler.findContainingViewHolder(confirmView);
        boolean sameTile = holder != null && confirmKey.equals(keyAt(holder.getBindingAdapterPosition()));
        if (!confirmView.isFocused() || position < 0 || !confirmKey.equals(selectedKey) || !sameTile) {
            clearConfirmPress(true);
            return;
        }
        View anchor = confirmView;
        Entry entry = items.get(position);
        // Keep this suppression after the dialog takes focus and clears the pending press.
        suppressConfirmUp = true;
        editKeyHeld = true;
        showOptions(entry, anchor);
    }

    private boolean onConfirmKey(int position, View view, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (position < 0 || position >= items.size()) {
                clearConfirmPress(true);
                return true;
            }
            String pressedKey = keyAt(position);
            if (!confirmDown || confirmView != view || !pressedKey.equals(confirmKey)) {
                clearConfirmPress(false);
                suppressConfirmUp = false;
                editKeyHeld = false;
                confirmDown = true;
                confirmView = view;
                confirmKey = pressedKey;
                view.setPressed(true);
                pendingLongPress = this::showHeldAppOptions;
                view.postDelayed(pendingLongPress, ViewConfiguration.getLongPressTimeout());
            }
            // adb's --longpress sends a flagged repeat without waiting for the normal timeout.
            if (event.isLongPress()) showHeldAppOptions();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            String pressedKey = confirmKey;
            boolean launchShortPress = confirmDown && !suppressConfirmUp && !event.isCanceled();
            clearConfirmPress(false);
            suppressConfirmUp = false;
            editKeyHeld = false;
            if (launchShortPress) {
                int current = positionOf(pressedKey);
                if (current >= 0) launch(items.get(current));
            }
            return true;
        }
        return true;
    }

    private boolean onEditKey(int position, View view, int keyCode, KeyEvent event) {
        if (movingKey != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.getAction() == KeyEvent.ACTION_UP) finishMove(keyCode != KeyEvent.KEYCODE_BACK);
                return true;
            }
            int step;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) step = -1;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) step = 1;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) step = -dockSize;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) step = dockSize;
            else return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int from = positionOf(movingKey);
                moveEntry(movingKey, Math.max(0, Math.min(items.size() - 1, from + step)));
                restoreFocus(movingKey);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && position >= 0 && position < items.size()) showOptions(items.get(position), null);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            return onConfirmKey(position, view, event);
        return false;
    }

    private void moveEntry(String entryKey, int target) {
        int from = positionOf(entryKey);
        if (from < 0 || target < 0 || target >= items.size() || from == target) return;
        Entry entry = items.remove(from);
        items.add(target, entry);
        notifyItemMoved(from, target);
        notifyItemRangeChanged(Math.min(from, target), Math.abs(target - from) + 1);
    }

    private void finishMove(boolean save) {
        String focus = movingKey;
        if (focus == null) return;
        if (save) saveVisibleOrder();
        else if (beforeMove != null) {
            items.clear();
            items.addAll(beforeMove);
            notifyDataSetChanged();
        }
        movingKey = null;
        beforeMove = null;
        restoreFocus(focus);
    }

    private void saveVisibleOrder() {
        // Keep hidden entries in their previous slots while replacing the visible subsequence.
        Set<String> hiddenKeys = preferences.hidden();
        List<String> order = new ArrayList<>();
        int visible = 0;
        for (Entry entry : catalog) {
            if (hiddenKeys.contains(key(entry))) order.add(key(entry));
            else if (visible < items.size()) order.add(key(items.get(visible++)));
        }
        while (visible < items.size()) order.add(key(items.get(visible++)));
        preferences.setOrder(order);
    }

    private void reloadAndRestore(String focus) {
        reload();
        restoreFocus(focus);
    }

    private void restoreFocus(String focus) {
        selectedKey = focus;
        if (listener != null) listener.onAppsChanged(focus);
        if (recycler == null) return;
        final RecyclerView view = recycler;
        view.post(() -> {
            int position = positionOf(focus);
            if (position < 0 && !items.isEmpty()) position = 0;
            if (position < 0) return;
            final int target = position;
            view.scrollToPosition(target);
            view.post(() -> {
                // Rapid D-pad moves may supersede an earlier pending focus request.
                if (positionOf(focus) != target) return;
                RecyclerView.ViewHolder holder = view.findViewHolderForAdapterPosition(target);
                if (holder == null) return;
                View card = holder.itemView.findViewById(R.id.card);
                if (card.isFocused() && listener != null) listener.onTileFocused(target, card);
                else card.requestFocus();
            });
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.icon != null ? e.icon : e.banner);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, AppAdapter owner, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            cornerRadius(view.getWidth()));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                int pos = getBindingAdapterPosition();
                if (owner.onEditKey(pos, view, keyCode, event)) return true;
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                if (!focused && owner.confirmView == view) owner.clearConfirmPress(true);
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .translationY(focused ? -owner.focusLiftDp() * density : 0f)
                        .translationZ(focused ? 8 * density : 0f)
                        .setDuration(focused ? 220 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 0.35f : 0f))
                        .start();
                owner.updateLabel(this, focused);
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION) {
                    owner.selectedKey = owner.keyAt(pos);
                    if (listener != null) listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
