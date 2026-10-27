package app.nepal.framextv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;

/** Native controls around the documented provider web embed, not a native media decoder. */
public final class EmbedPlayerActivity extends Activity {
    private static final long CONTROLS_HIDE_DELAY_MS = 4000;
    private static final int IMMERSIVE_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    public static final String EXTRA_MEDIA_TYPE = "app.nepal.framextv.extra.MEDIA_TYPE";
    public static final String EXTRA_TMDB_ID = "app.nepal.framextv.extra.TMDB_ID";
    public static final String EXTRA_SEASON = "app.nepal.framextv.extra.SEASON";
    public static final String EXTRA_EPISODE = "app.nepal.framextv.extra.EPISODE";
    public static final String EXTRA_TITLE = "app.nepal.framextv.extra.TITLE";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EmbedSpec spec;
    private WebView web;
    private FrameLayout playerHost, fullscreenHost;
    private LinearLayout controls;
    private TextView status;
    private Button playButton, pauseButton, rewindButton, forwardButton, retryButton;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private boolean foreground, clearedInBackground, loaded, destroyed, pageFailed, webReady;
    private boolean controlsPinned, revealingTouch, autoplayPending, providerNavigation;
    private int chromeGeneration, playbackGeneration, revealKey = -1, autoplayChecks;
    private boolean initialLoadPending = true;
    private final Runnable hideControlsTask = () -> hideControls(true);
    private final Runnable autoplayTask = this::checkAutoplay;
    private final Runnable loadTimeout = () -> {
        if (foreground && !loaded) showError("Player is taking longer than expected. Retry.");
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent request = getIntent();
            spec = new EmbedSpec(request.getStringExtra(EXTRA_MEDIA_TYPE), request.getIntExtra(EXTRA_TMDB_ID, -1),
                    request.getIntExtra(EXTRA_SEASON, -1), request.getIntExtra(EXTRA_EPISODE, -1),
                    request.getStringExtra(EXTRA_TITLE));
        } catch (RuntimeException invalid) {
            finish();
            return;
        }
        createViews();
        status.setText("Loading embedded player…");
        updateActions();
        try {
            web = new WebView(this);
            playerHost.addView(web, new FrameLayout.LayoutParams(-1, -1));
            configureWebView();
            webReady = true;
            // The first request starts in onResume, after foreground becomes true.
        } catch (RuntimeException unavailable) {
            showError("The embedded player is unavailable on this Fire TV.");
            retryButton.setEnabled(false);
        }
    }

    private void createViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerHost = new FrameLayout(this);
        root.addView(playerHost, new FrameLayout.LayoutParams(-1, -1));
        controls = new LinearLayout(this);
        controls.setId(R.id.player_controls);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(16), dp(8), dp(16), dp(8));
        controls.setBackgroundColor(0xF20A111C);
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(0, 0, dp(16), 0);
        TextView title = new TextView(this);
        title.setText(spec.title);
        title.setTextSize(16);
        title.setTextColor(0xFFF2F5FA);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        identity.addView(title, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setId(R.id.player_status);
        status.setTextSize(11);
        status.setTextColor(0xFFBCC9D9);
        status.setMaxLines(2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        identity.addView(status, new LinearLayout.LayoutParams(-1, -2));
        controls.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        addButton("Back", this::backFromPlayer);
        playButton = addButton("Play", () -> command("play", 0));
        pauseButton = addButton("Pause", () -> command("pause", 0));
        rewindButton = addButton("−10s", () -> command("seekBy", -10));
        forwardButton = addButton("+10s", () -> command("seekBy", 10));
        retryButton = addButton("Retry", this::loadPlayer);
        root.addView(controls, new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP));
        fullscreenHost = new FrameLayout(this);
        fullscreenHost.setBackgroundColor(Color.BLACK);
        fullscreenHost.setVisibility(View.GONE);
        root.addView(fullscreenHost, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        playButton.requestFocus();
    }

    private Button addButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(dp(62));
        button.setMinimumWidth(dp(62));
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setTextColor(0xFFF2F5FA);
        button.setBackgroundResource(R.drawable.dialog_action);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(48));
        layout.leftMargin = dp(8);
        controls.addView(button, layout);
        return button;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        // Opening this activity follows the user's Play action; provider autoplay is documented.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setLongClickable(false);
        web.setOnLongClickListener(view -> true);
        web.setDownloadListener((url, agent, disposition, mime, length) -> showError("Downloads are unavailable."));
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return !"https".equalsIgnoreCase(request.getUrl().getScheme());
                return rejectNavigation(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String address) {
                return rejectNavigation(address);
            }
            @Override public void onPageStarted(WebView view, String address, android.graphics.Bitmap icon) {
                if (!foreground || "about:blank".equals(address)) return;
                if (spec.originFor(address) == null) {
                    view.stopLoading();
                    failPage("Only this title's embedded player can open here.");
                    return;
                }
                pageFailed = false;
                loaded = false;
                status.setText("Loading embedded player…");
                updateActions();
            }
            @Override public void onPageFinished(WebView view, String address) {
                if (!foreground || destroyed || pageFailed || spec.originFor(address) == null || !address.equals(view.getUrl())) return;
                handler.removeCallbacks(loadTimeout);
                loaded = true;
                controlsPinned = false;
                status.setText("Menu or D-pad: controls");
                updateActions();
                scheduleControlsHide();
                handler.removeCallbacks(autoplayTask);
                handler.post(autoplayTask);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (foreground && request.isForMainFrame() && !"about:blank".equals(request.getUrl().toString())) {
                    failPage("Player could not load. Retry.");
                }
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (foreground && request.isForMainFrame()) failPage("Player page unavailable (" + response.getStatusCode() + ").");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler ssl, SslError error) {
                ssl.cancel();
                if (foreground) failPage("Player connection could not be verified.");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message result) {
                return false;
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback,
                                                       FileChooserParams parameters) {
                callback.onReceiveValue(null);
                return true;
            }
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                result.cancel();
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null || !foreground) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCallback = callback;
                fullscreenHost.addView(view, new FrameLayout.LayoutParams(-1, -1));
                fullscreenHost.setVisibility(View.VISIBLE);
                handler.removeCallbacks(hideControlsTask);
                chromeGeneration++;
                controls.animate().cancel();
                controls.setVisibility(View.GONE);
                playerHost.setVisibility(View.GONE);
                view.requestFocus();
            }
            @Override public void onHideCustomView() { leaveFullscreen(); }
        });
    }

    private boolean rejectNavigation(String address) {
        if ("about:blank".equals(address) && clearedInBackground) return false;
        if (spec.originFor(address) != null) return false;
        if (foreground) showError("Only this title's embedded player can open here.");
        return true;
    }

    private void loadPlayer() {
        if (web == null || destroyed || !webReady) return;
        if (!foreground) { initialLoadPending = true; return; }
        loaded = false;
        pageFailed = false;
        controlsPinned = false;
        autoplayPending = true;
        autoplayChecks = 0;
        playbackGeneration++;
        handler.removeCallbacks(autoplayTask);
        showControls(false);
        status.setText("Loading embedded player…");
        updateActions();
        handler.removeCallbacks(loadTimeout);
        handler.postDelayed(loadTimeout, 30_000);
        web.loadUrl(spec.address());
    }

    private void updateActions() {
        boolean available = loaded && web != null && webReady && !destroyed;
        for (Button button : new Button[] {playButton, pauseButton, rewindButton, forwardButton}) {
            if (button == null) continue;
            button.setEnabled(available);
            button.setAlpha(available ? 1f : .45f);
        }
    }

    private void failPage(String message) {
        pageFailed = true;
        loaded = false;
        autoplayPending = false;
        handler.removeCallbacks(autoplayTask);
        updateActions();
        showError(message);
    }

    private void showError(String message) {
        if (destroyed || status == null) return;
        if (fullscreenView != null) leaveFullscreen();
        handler.removeCallbacks(loadTimeout);
        controlsPinned = true;
        status.setText(message);
        showControls(false);
        if (retryButton != null) retryButton.requestFocus();
    }

    private boolean touchExplorationEnabled() {
        AccessibilityManager accessibility = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        return accessibility != null && accessibility.isTouchExplorationEnabled();
    }

    private void scheduleControlsHide() {
        handler.removeCallbacks(hideControlsTask);
        if (foreground && loaded && !pageFailed && !controlsPinned && !destroyed && fullscreenView == null
                && controls.getVisibility() == View.VISIBLE && !touchExplorationEnabled()) {
            handler.postDelayed(hideControlsTask, CONTROLS_HIDE_DELAY_MS);
        }
    }

    private void showControls(boolean focus) {
        if (destroyed || controls == null || fullscreenView != null) return;
        providerNavigation = false;
        chromeGeneration++;
        controls.animate().cancel();
        controls.setAlpha(1f);
        controls.setVisibility(View.VISIBLE);
        if (focus) {
            if (playButton.isEnabled() && !controlsPinned) playButton.requestFocus();
            else retryButton.requestFocus();
        }
        scheduleControlsHide();
    }

    private void hideControls(boolean fade) {
        handler.removeCallbacks(hideControlsTask);
        if (!foreground || destroyed || !loaded || controlsPinned || pageFailed || fullscreenView != null
                || touchExplorationEnabled() || controls.getVisibility() != View.VISIBLE) return;
        final int revision = ++chromeGeneration;
        controls.animate().cancel();
        Runnable finish = () -> {
            if (!foreground || destroyed || revision != chromeGeneration) return;
            controls.setVisibility(View.GONE);
            controls.setAlpha(1f);
            if (web != null) web.requestFocus();
        };
        if (fade) controls.animate().alpha(0f).setDuration(180).withEndAction(finish).start();
        else finish.run();
    }

    /** Check only readiness flags; never read media URLs, cookies or provider credentials. */
    private void checkAutoplay() {
        if (!foreground || destroyed || !loaded || pageFailed || !autoplayPending || web == null) return;
        String origin = spec.originFor(web.getUrl());
        if (origin == null || ++autoplayChecks > 45) { autoplayPending = false; return; }
        final int revision = playbackGeneration;
        String script = "(function(){if(window.location.origin!=='" + origin + "')return null;"
                + "var v=document.querySelector('video');return v?{ready:v.readyState,paused:v.paused,ended:v.ended}:null;})()";
        web.evaluateJavascript(script, value -> {
            if (!foreground || destroyed || !loaded || !autoplayPending || revision != playbackGeneration) return;
            try {
                if (value != null && value.length() <= 256 && !"null".equals(value)) {
                    JSONObject state = new JSONObject(value);
                    if (state.optBoolean("ended", false)) {
                        autoplayPending = false;
                    } else if (state.optInt("ready", 0) >= 2 && !state.optBoolean("paused", true)) {
                        autoplayPending = false;
                    } else if (state.optInt("ready", 0) >= 1 && state.optBoolean("paused", true)) {
                        // Retry while metadata/source loading settles. Explicit Pause cancels this loop.
                        postPlayerMessage("{action:'play'}");
                    }
                }
            } catch (JSONException ignored) { /* The provider may replace its player document. */ }
            if (autoplayPending) handler.postDelayed(autoplayTask, 2000);
        });
    }

    private void command(String action, int seconds) {
        if (web == null || destroyed || !webReady) return;
        if (!loaded) {
            if ("pause".equals(action)) autoplayPending = false;
            else if ("play".equals(action)) autoplayPending = true;
            else if ("togglePlay".equals(action)) autoplayPending = !autoplayPending;
            if (!autoplayPending) handler.removeCallbacks(autoplayTask);
            return;
        }
        autoplayPending = "play".equals(action);
        if (autoplayPending) autoplayChecks = 0;
        handler.removeCallbacks(autoplayTask);
        String origin = spec.originFor(web.getUrl());
        if (origin == null) return;
        final String message;
        if ("play".equals(action) || "pause".equals(action) || "togglePlay".equals(action)) {
            message = "{action:'" + action + "'}";
        } else if ("seekBy".equals(action) && (seconds == -10 || seconds == 10)) {
            message = "{action:'seekBy',seconds:" + seconds + "}";
        } else return;
        postPlayerMessage(message);
        if (autoplayPending && foreground) handler.postDelayed(autoplayTask, 2000);
        if (foreground) {
            status.setText("play".equals(action) ? "Play requested" : "pause".equals(action)
                    ? "Pause requested" : "togglePlay".equals(action) ? "Play / pause requested"
                    : seconds > 0 ? "+10 seconds requested" : "−10 seconds requested");
            showControls(controls.getVisibility() != View.VISIBLE);
        }
    }

    private void postPlayerMessage(String message) {
        if (web == null || destroyed || !loaded) return;
        String origin = spec.originFor(web.getUrl());
        if (origin == null) return;
        // Callers supply fixed commands only; no media URLs or provider credentials are extracted.
        String script = "if(window.location.origin==='" + origin + "'){window.postMessage(" + message + ",'" + origin + "');}";
        web.evaluateJavascript(script, null);
    }

    private void leaveFullscreen() {
        if (fullscreenView == null) return;
        fullscreenHost.removeAllViews();
        fullscreenHost.setVisibility(View.GONE);
        fullscreenView = null;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        playerHost.setVisibility(View.VISIBLE);
        if (callback != null) callback.onCustomViewHidden();
        if (foreground && !destroyed) showControls(true);
    }

    private void backFromPlayer() {
        if (fullscreenView != null) leaveFullscreen();
        else finish();
    }

    @Override public void onBackPressed() { backFromPlayer(); }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        if (controls != null && controls.getVisibility() == View.VISIBLE) showControls(false);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && foreground && fullscreenView == null
                && !providerNavigation && controls.getVisibility() != View.VISIBLE && !touchExplorationEnabled()) {
            revealingTouch = true;
            showControls(true);
        }
        if (revealingTouch) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                revealingTouch = false;
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == revealKey) {
            if (event.getAction() == KeyEvent.ACTION_UP) revealKey = -1;
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) backFromPlayer();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && web != null) {
                if (fullscreenView != null) leaveFullscreen();
                else if (controls.getVisibility() != View.VISIBLE || web.hasFocus()) showControls(true);
                else {
                    hideControls(false);
                    providerNavigation = controls.getVisibility() != View.VISIBLE;
                }
            }
            return true;
        }
        boolean dpad = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (dpad && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && foreground && fullscreenView == null && !providerNavigation && controls.getVisibility() != View.VISIBLE) {
            revealKey = key;
            showControls(true);
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY
                || key == KeyEvent.KEYCODE_MEDIA_PAUSE || key == KeyEvent.KEYCODE_MEDIA_REWIND
                || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (key == KeyEvent.KEYCODE_MEDIA_REWIND) command("seekBy", -10);
                else if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) command("seekBy", 10);
                else if (key == KeyEvent.KEYCODE_MEDIA_PLAY) command("play", 0);
                else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) command("pause", 0);
                else command("togglePlay", 0);
            }
            return true;
        }
        // D-pad center activates native buttons normally; provider focus retains its own D-pad handling.
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
        if (web != null) {
            web.onResume();
            if (webReady) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (webReady && (initialLoadPending || clearedInBackground)) {
                initialLoadPending = false;
                clearedInBackground = false;
                loadPlayer();
            } else if (loaded && controls.getVisibility() == View.VISIBLE) showControls(false);
        }
    }

    @Override protected void onPause() {
        command("pause", 0);
        foreground = false;
        revealKey = -1;
        revealingTouch = false;
        handler.removeCallbacks(hideControlsTask);
        handler.removeCallbacks(autoplayTask);
        chromeGeneration++;
        if (controls != null) controls.animate().cancel();
        if (web != null) {
            if (!loaded && webReady) {
                initialLoadPending = true;
                web.stopLoading();
            }
            web.onPause();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onStop() {
        foreground = false;
        handler.removeCallbacks(loadTimeout);
        handler.removeCallbacks(hideControlsTask);
        handler.removeCallbacks(autoplayTask);
        autoplayPending = false;
        playbackGeneration++;
        leaveFullscreen();
        loaded = false;
        if (web != null) {
            clearedInBackground = true;
            web.stopLoading();
            web.loadUrl("about:blank");
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        leaveFullscreen();
        if (web != null) {
            playerHost.removeView(web);
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE_FLAGS);
    }
}
