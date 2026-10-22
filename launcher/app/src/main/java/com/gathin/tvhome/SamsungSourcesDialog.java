package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * An on-TV entry point to the Samsung's real source picker. While it is open, the Fire remote's
 * D-pad is forwarded directly to the paired display; the Mac companion is not involved.
 * A successful WebSocket send is never presented as proof that a source became active.
 */
final class SamsungSourcesDialog extends Dialog implements SamsungTvClient.Listener {
    private final Activity activity;
    private final boolean manageConnection;
    private SamsungTvClient client;
    private LinearLayout panel;
    private TextView status;
    private TextView openSources;
    private AlertDialog setupDialog;
    private boolean forwarding;
    private boolean finished;
    private boolean awaitingSelection;
    private boolean compactPill;
    private boolean returnToMenu;

    SamsungSourcesDialog(Activity activity) {
        this(activity, false);
    }

    SamsungSourcesDialog(Activity activity, boolean manageConnection) {
        super(activity);
        this.activity = activity;
        this.manageConnection = manageConnection;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setOnDismissListener(ignored -> {
            finished = true;
            if (setupDialog != null) setupDialog.dismiss();
            if (client != null) client.close();
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        setContentView(panel);
        setCanceledOnTouchOutside(false);
        client = new SamsungTvClient(activity, this);
        if (!manageConnection && SamsungTvSettings.load(activity).isPaired()) openInputs();
        else showMenu();
    }

    @Override
    public void show() {
        super.show();
        arrangeWindow();
    }

    private void showMenu() {
        forwarding = false;
        awaitingSelection = false;
        returnToMenu = false;
        compactPill = false;
        panel.removeAllViews();
        panel.setPadding(dp(22), dp(19), dp(22), dp(21));
        panel.setBackground(surface(0xF5111925, 0x50485A70, 20));
        addText("Inputs", 22, 0xFFF3F5F9, 0);
        SamsungTvSettings settings = SamsungTvSettings.load(activity);
        status = addText("", 13, 0xFFA8B8CB, 8);
        setStatus(settings.isPaired() ? "" : "Connect your Samsung TV once.");
        openSources = addAction("Open inputs", () -> {
            if (!SamsungTvSettings.load(activity).isPaired()) {
                showSetup();
                return;
            }
            openInputs();
        });
        addAction("TV connection", this::showSetup);
        openSources.requestFocus();
        arrangeWindow();
    }

    private void openInputs() {
        forwarding = false;
        awaitingSelection = false;
        returnToMenu = false;
        showPill("Opening inputs…");
        client.send(SamsungTvClient.Key.SOURCE);
    }

    private void beginNavigation() {
        forwarding = true;
        awaitingSelection = false;
        showPill("Arrows  ·  OK  ·  Back");
    }

    private void showPill(String hint) {
        compactPill = true;
        openSources = null;
        panel.removeAllViews();
        panel.setPadding(dp(18), dp(10), dp(18), dp(10));
        panel.setBackground(surface(0xED101925, 0x3D526074, 24));
        status = addText(hint, 13, 0xFFD4DEEA, 0);
        status.setSingleLine(true);
        status.setGravity(Gravity.CENTER);
        arrangeWindow();
    }

    private void arrangeWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(compactPill ? 0f : 0.30f);
        window.setLayout(dp(compactPill ? 328 : 400), WindowManager.LayoutParams.WRAP_CONTENT);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = compactPill ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
        attributes.y = compactPill ? dp(22) : 0;
        window.setAttributes(attributes);
    }

    private void setStatus(String text) {
        status.setText(text);
        status.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showSetup() {
        SamsungTvSettings settings = SamsungTvSettings.load(activity);
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        TextView explanation = fieldLabel("Choose Allow on your Samsung TV.");
        fields.addView(explanation);
        fields.addView(fieldLabel("TV IP address"));
        EditText address = new EditText(activity);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("192.168.1.100");
        address.setText(settings.address);
        fields.addView(address);
        fields.addView(fieldLabel("Samsung model"));
        EditText model = new EditText(activity);
        model.setSingleLine(true);
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        model.setHint("UN65TU7000BXZA");
        model.setText(settings.model);
        fields.addView(model);
        TextView error = fieldLabel("");
        error.setTextColor(0xFFFFB1A8);
        fields.addView(error);
        AlertDialog.Builder setup = new AlertDialog.Builder(activity)
                .setTitle("TV connection")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pair TV", null);
        if (settings.isPaired()) setup.setNeutralButton("Forget pairing", (dialog, which) -> {
            client.close();
            SamsungTvSettings.forgetPairing(activity);
            client = new SamsungTvClient(activity, this);
            showMenu();
            setStatus("Pairing cleared. Connect again to continue.");
        });
        setupDialog = setup.create();
        setupDialog.setOnShowListener(ignored -> {
            setupDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    SamsungTvSettings.saveTarget(activity, address.getText().toString().trim(),
                            model.getText().toString().trim());
                    // Pair again only after an explicit action. Changing a target clears its old trust.
                    if (client != null) client.close();
                    client = new SamsungTvClient(activity, this);
                    setupDialog.dismiss();
                    setStatus("Connecting…");
                    openSources.setEnabled(false);
                    openSources.setAlpha(0.45f);
                    client.pair();
                } catch (IllegalArgumentException invalid) {
                    error.setText(invalid.getMessage());
                }
            });
        });
        setupDialog.show();
    }

    @Override
    public void onState(SamsungTvClient.State state, String message) {
        if (finished || status == null) return;
        if (state == SamsungTvClient.State.ERROR) {
            if (compactPill) showMenu();
            setStatus(message);
        } else if (!compactPill) {
            if (state == SamsungTvClient.State.CONNECTING) setStatus("Connecting…");
            else if (state == SamsungTvClient.State.PAIRING) setStatus("Choose Allow on your Samsung TV.");
            else if (state == SamsungTvClient.State.READY) setStatus("Connected.");
        }
        // Connection events never replace the one-line physical-remote hint.
        if (!forwarding && openSources != null) {
            boolean busy = state == SamsungTvClient.State.CONNECTING || state == SamsungTvClient.State.PAIRING;
            openSources.setEnabled(!busy);
            openSources.setAlpha(busy ? 0.45f : 1f);
        }
    }

    @Override
    public void onSent(SamsungTvClient.Key key) {
        if (finished) return;
        if (key == SamsungTvClient.Key.SOURCE) {
            beginNavigation();
        } else if (key == SamsungTvClient.Key.ENTER || key == SamsungTvClient.Key.RETURN) {
            // onSent means the request was queued, not that a particular HDMI input is active.
            if (key == SamsungTvClient.Key.RETURN && returnToMenu) showMenu();
            else dismiss();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!forwarding) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        SamsungTvClient.Key key = forwardedKey(code);
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !awaitingSelection) {
                awaitingSelection = true;
                returnToMenu = true;
                client.send(SamsungTvClient.Key.RETURN);
            }
            return true;
        }
        if (key == null) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !awaitingSelection) {
            awaitingSelection = key == SamsungTvClient.Key.ENTER || key == SamsungTvClient.Key.RETURN;
            client.send(key);
        }
        return true;
    }

    private SamsungTvClient.Key forwardedKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return SamsungTvClient.Key.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return SamsungTvClient.Key.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return SamsungTvClient.Key.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return SamsungTvClient.Key.RIGHT;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return SamsungTvClient.Key.ENTER;
            case KeyEvent.KEYCODE_BACK: return SamsungTvClient.Key.RETURN;
            default: return null;
        }
    }

    private TextView addText(String text, int sp, int color, int topMargin) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(dialogTypeface());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        panel.addView(view, params);
        return view;
    }

    private TextView addAction(String label, Runnable action) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextSize(17);
        button.setTypeface(dialogTypeface());
        button.setTextColor(0xFFE2EAF3);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackground(surface(0xFF1C2A3B, 0x334A6179, 12));
        button.setOnFocusChangeListener((view, focused) -> {
            button.setTextColor(focused ? Color.WHITE : 0xFFE2EAF3);
            button.setBackground(surface(focused ? 0xFF2B4057 : 0xFF1C2A3B,
                    focused ? HomePreferences.getAccentColor(activity) : 0x334A6179, 12));
        });
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        params.topMargin = dp(14);
        panel.addView(button, params);
        return button;
    }

    private TextView fieldLabel(String label) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(14);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    private GradientDrawable surface(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private Typeface dialogTypeface() {
        try {
            return androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.outfit_modern);
        } catch (android.content.res.Resources.NotFoundException ignored) {
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
