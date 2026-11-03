package app.nepal.framextv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
    public static final String EXTRA_START_SECONDS = "app.nepal.framextv.extra.START_SECONDS";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EmbedSpec spec;
    private WebView web;
    private FrameLayout playerHost, fullscreenHost;
    private LinearLayout controls;
    private TextView status;
    private Button playButton, pauseButton, rewindButton, forwardButton, retryButton, subtitlesButton;
    private AlertDialog subtitlesDialog;
    private SharedPreferences playerPreferences;
    private WatchHistory history;
    private double resumeSeconds;
    private boolean subtitlesEnabled, playbackInstalled, playbackAssetRead, progressReadInFlight;
    private boolean progressReady, resumeFailureReported, hasLoadedPlayer;
    private String playbackScript;
    private JSONObject latestPlaybackState;
    private long featureGeneration, progressGeneration;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private boolean foreground, clearedInBackground, loaded, destroyed, pageFailed, webReady;
    private boolean controlsPinned, revealingTouch, autoplayPending, providerNavigation;
    private int chromeGeneration, playbackGeneration, revealKey = -1, autoplayChecks;
    private long qualityGeneration;
    private String qualityScript;
    private boolean qualityAssetRead, qualityInstalled;
    private boolean initialLoadPending = true;
    private final Runnable hideControlsTask = () -> hideControls(true);
    private final Runnable autoplayTask = this::checkAutoplay;
    private final Runnable progressTask = new Runnable() {
        @Override public void run() {
            captureProgress();
            if (foreground && loaded && !destroyed) handler.postDelayed(this, 5000);
        }
    };
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
        history = new WatchHistory(this);
        playerPreferences = getSharedPreferences("theater_player", MODE_PRIVATE);
        subtitlesEnabled = playerPreferences.getBoolean("subtitles_enabled", true);
        resumeSeconds = getIntent().getDoubleExtra(EXTRA_START_SECONDS, 0);
        if (Double.isNaN(resumeSeconds) || Double.isInfinite(resumeSeconds)
                || resumeSeconds < 15 || resumeSeconds > 604800) resumeSeconds = 0;
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
        subtitlesButton = addButton("Subtitles", this::showSubtitleOptions);
        subtitlesButton.setId(R.id.player_subtitles);
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
                boolean replacingPlayer = loaded && foreground && spec.originFor(address) != null;
                stopQualityPreference();
                stopPlaybackFeatures();
                playbackGeneration++;
                handler.removeCallbacks(autoplayTask);
                progressGeneration++;
                progressReadInFlight = false;
                if (!foreground || "about:blank".equals(address)) return;
                if (spec.originFor(address) == null) {
                    view.stopLoading();
                    failPage("Only this title's embedded player can open here.");
                    return;
                }
                if (replacingPlayer) {
                    // Provider-driven document replacements need the same restore gate as Retry.
                    WatchHistory.Entry saved = history.find(spec);
                    resumeSeconds = saved == null ? 0 : saved.positionSeconds;
                    progressReady = resumeSeconds == 0;
                    resumeFailureReported = false;
                    latestPlaybackState = null;
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
                startQualityPreference();
                startPlaybackFeatures();
                handler.removeCallbacks(progressTask);
                handler.post(progressTask);
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
        stopQualityPreference();
        stopPlaybackFeatures();
        if (hasLoadedPlayer) {
            WatchHistory.Entry saved = history.find(spec);
            resumeSeconds = saved == null ? 0 : saved.positionSeconds;
        }
        hasLoadedPlayer = true;
        progressReady = resumeSeconds == 0;
        resumeFailureReported = false;
        latestPlaybackState = null;
        progressGeneration++;
        progressReadInFlight = false;
        handler.removeCallbacks(progressTask);
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
        for (Button button : new Button[] {playButton, pauseButton, rewindButton, forwardButton, subtitlesButton}) {
            if (button == null) continue;
            button.setEnabled(available);
            button.setAlpha(available ? 1f : .45f);
        }
    }

    private void failPage(String message) {
        stopQualityPreference();
        stopPlaybackFeatures();
        handler.removeCallbacks(progressTask);
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
                && (subtitlesDialog == null || !subtitlesDialog.isShowing())
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
        if (origin == null || ++autoplayChecks > 90) { autoplayPending = false; return; }
        final int revision = playbackGeneration;
        String script = "(function(){if(window.location.origin!=='" + origin + "')return null;"
                + "var v=document.querySelector('video'),p=window.__nepalTheaterPlayback,s=p&&p.getState?p.getState():null;"
                + "return {ready:v?v.readyState:0,paused:v?v.paused:true,ended:v?v.ended:false,resume:s?s.resumePhase:null};})()";
        web.evaluateJavascript(script, value -> {
            if (!foreground || destroyed || !loaded || !autoplayPending || revision != playbackGeneration) return;
            try {
                if (value != null && value.length() <= 256 && !"null".equals(value)) {
                    JSONObject state = new JSONObject(value);
                    String resume = state.optString("resume", "");
                    if ("waiting".equals(resume) || "seeking".equals(resume)
                            || resumeSeconds > 0 && !progressReady && resume.isEmpty()) {
                        // Restore position before sending Play, without changing a user's Pause.
                    } else if ("unavailable".equals(resume) && resumeSeconds > 0) {
                        reportResumeFailure();
                    } else if (state.optBoolean("ended", false)) {
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
        if ("seekBy".equals(action)) {
            cancelPendingResume();
            progressReady = true;
        }
        if (("play".equals(action) || "togglePlay".equals(action)) && resumeFailureReported) {
            history.restart(spec);
            resumeSeconds = 0;
            progressReady = true;
            resumeFailureReported = false;
            controlsPinned = false;
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

    private void startPlaybackFeatures() {
        if (!foreground || destroyed || !loaded || pageFailed || web == null || !webReady || playbackInstalled) return;
        String origin = spec.originFor(web.getUrl());
        if (origin == null) return;
        if (!playbackAssetRead) {
            playbackAssetRead = true;
            try (InputStream source = getAssets().open("theater-playback.js");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (bytes.size() + count > 65_536) throw new IOException("Playback asset exceeds limit");
                    bytes.write(buffer, 0, count);
                }
                playbackScript = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException unavailable) {
                if (resumeSeconds > 0) reportResumeFailure();
                return;
            }
        }
        if (playbackScript == null) return;
        try {
            JSONObject config = new JSONObject();
            config.put("origin", origin);
            config.put("path", spec.path);
            config.put("generation", ++featureGeneration);
            config.put("resumeSeconds", progressReady ? 0 : resumeSeconds);
            config.put("subtitlesEnabled", subtitlesEnabled);
            config.put("preferredLanguage", "en");
            playbackInstalled = true;
            web.evaluateJavascript("(" + playbackScript + ")(" + config + ");", null);
        } catch (JSONException ignored) { /* Fixed bounded primitive configuration only. */ }
    }

    private void stopPlaybackFeatures() {
        featureGeneration++;
        if (!playbackInstalled) return;
        playbackInstalled = false;
        if (web == null || destroyed || spec.originFor(web.getUrl()) == null) return;
        web.evaluateJavascript("(function(){var p=window.__nepalTheaterPlayback;if(p&&p.stop)p.stop();})()", null);
    }

    private void cancelPendingResume() {
        resumeSeconds = 0;
        if (web == null || destroyed || spec.originFor(web.getUrl()) == null) return;
        web.evaluateJavascript("(function(){var p=window.__nepalTheaterPlayback;if(p&&p.cancelResume)p.cancelResume();})()", null);
    }

    private void reportResumeFailure() {
        if (resumeFailureReported || destroyed || !foreground) return;
        resumeFailureReported = true;
        autoplayPending = false;
        handler.removeCallbacks(autoplayTask);
        showError("Could not restore position. Retry or press Play to start.");
    }

    /** Capture timing only; provider URLs, credentials and subtitle contents never leave the page. */
    private void captureProgress() {
        if (web == null || destroyed || !loaded || pageFailed || progressReadInFlight) return;
        String origin = spec.originFor(web.getUrl());
        if (origin == null) return;
        final long revision = progressGeneration;
        final long observedAt = System.currentTimeMillis();
        final EmbedSpec observedSpec = spec;
        progressReadInFlight = true;
        String script = "(function(){if(window.location.origin!==" + JSONObject.quote(origin)
                + "||(window.location.pathname!==" + JSONObject.quote(spec.path)
                + "&&window.location.pathname!==" + JSONObject.quote(spec.path + "/") + "))return null;"
                + "var v=document.querySelector('video'),p=window.__nepalTheaterPlayback;"
                + "return {playback:p&&p.getState?p.getState():null,video:v?{position:v.currentTime,duration:v.duration,"
                + "ended:v.ended,ready:v.readyState}:null};})()";
        web.evaluateJavascript(script, value -> {
            if (revision != progressGeneration) return;
            progressReadInFlight = false;
            try {
                if (value == null || value.length() > 2048 || "null".equals(value)) return;
                JSONObject snapshot = new JSONObject(value);
                JSONObject state = snapshot.optJSONObject("playback");
                latestPlaybackState = state;
                String phase = state == null ? "" : state.optString("resumePhase", "");
                if ("done".equals(phase) || "skipped".equals(phase) || "cancelled".equals(phase)) progressReady = true;
                if (!destroyed) {
                    String subtitlePhase = state == null ? "" : state.optString("subtitlePhase", "");
                    subtitlesButton.setContentDescription("Subtitles, " + (!subtitlesEnabled ? "off"
                            : "selected".equals(subtitlePhase) ? "on" : "unavailable".equals(subtitlePhase)
                            ? "not available from this source" : "on when available"));
                    if ("unavailable".equals(phase) && resumeSeconds > 0 && !progressReady) reportResumeFailure();
                }
                if (!progressReady || "waiting".equals(phase) || "seeking".equals(phase)) return;
                JSONObject video = snapshot.optJSONObject("video");
                if (video == null || video.optInt("ready", 0) < 1) return;
                history.record(observedSpec, video.optDouble("position", Double.NaN),
                        video.optDouble("duration", Double.NaN), video.optBoolean("ended", false), observedAt);
            } catch (JSONException ignored) { /* A document replacement can invalidate a pending sample. */ }
        });
    }

    private void showSubtitleOptions() {
        if (!foreground || destroyed || !loaded || isFinishing()) return;
        String phase = latestPlaybackState == null ? "" : latestPlaybackState.optString("subtitlePhase", "");
        String label = latestPlaybackState == null ? "" : latestPlaybackState.optString("subtitleLabel", "");
        if (label.length() > 100) label = "";
        String detail = !subtitlesEnabled ? "Off" : "selected".equals(phase) && !label.isEmpty()
                ? label : "unavailable".equals(phase) ? "No tracks yet" : "On when available";
        if (subtitlesDialog != null) subtitlesDialog.dismiss();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Subtitles")
                .setSingleChoiceItems(new String[] {"On (English preferred)", "Off"}, subtitlesEnabled ? 0 : 1,
                        (selected, which) -> {
                            if (!foreground || destroyed) return;
                            subtitlesEnabled = which == 0;
                            playerPreferences.edit().putBoolean("subtitles_enabled", subtitlesEnabled).apply();
                            if (playbackInstalled && web != null && spec.originFor(web.getUrl()) != null) {
                                web.evaluateJavascript("(function(){var p=window.__nepalTheaterPlayback;if(p&&p.setSubtitles)p.setSubtitles("
                                        + subtitlesEnabled + ");})()", null);
                            } else startPlaybackFeatures();
                            selected.dismiss();
                        }).setNegativeButton("Back", null).create();
        // A separate title keeps the native choice list visible on older Fire OS AlertDialog layouts.
        dialog.setTitle("Subtitles · " + detail);
        subtitlesDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (subtitlesDialog == dialog) subtitlesDialog = null;
            if (foreground && !destroyed) {
                subtitlesButton.requestFocus();
                scheduleControlsHide();
            }
        });
        handler.removeCallbacks(hideControlsTask);
        dialog.show();
        if (dialog.getListView() != null) dialog.getListView().requestFocus();
    }

    /** Prefer the provider's highest offered quality through its own settings, independently of Play. */
    private void startQualityPreference() {
        if (!foreground || destroyed || !loaded || pageFailed || web == null || !webReady || qualityInstalled) return;
        String origin = spec.originFor(web.getUrl());
        if (origin == null) return;
        if (!qualityAssetRead) {
            qualityAssetRead = true;
            // A small packaged asset, bounded for API 25 without readAllBytes or network I/O.
            try (InputStream source = getAssets().open("theater-highest-quality.js");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (bytes.size() + count > 65_536) throw new IOException("Quality asset exceeds limit");
                    bytes.write(buffer, 0, count);
                }
                qualityScript = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException unavailable) {
                // Playback remains available if the optional provider integration cannot be loaded.
                return;
            }
        }
        if (qualityScript == null) return;
        try {
            JSONObject config = new JSONObject();
            config.put("origin", origin);
            config.put("path", spec.path);
            config.put("generation", ++qualityGeneration);
            qualityInstalled = true;
            web.evaluateJavascript("(" + qualityScript + ")(" + config + ");", null);
        } catch (JSONException ignored) { /* Fixed primitive configuration only. */ }
    }

    private void stopQualityPreference() {
        qualityGeneration++;
        if (!qualityInstalled) return;
        qualityInstalled = false;
        if (web == null || destroyed || spec.originFor(web.getUrl()) == null) return;
        web.evaluateJavascript("(function(){var q=window.__nepalTheaterQuality;if(q&&typeof q.stop==='function')q.stop();})()", null);
    }

    private void yieldQualityMenu() {
        if (!qualityInstalled || web == null || destroyed || spec.originFor(web.getUrl()) == null) return;
        // Native Play/Pause while waiting must not prevent a later quality choice. Yield only
        // active settings work so user navigation wins; this never sends a playback command.
        web.evaluateJavascript("(function(){var q=window.__nepalTheaterQuality;"
                + "if(q&&typeof q.getState==='function'&&q.getState().phase==='selecting'"
                + "&&typeof q.stop==='function')q.stop();})()", null);
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
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) yieldQualityMenu();
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
        if (event.getAction() == KeyEvent.ACTION_DOWN) yieldQualityMenu();
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
            } else if (loaded) {
                if (controls.getVisibility() == View.VISIBLE) showControls(false);
                startQualityPreference();
                startPlaybackFeatures();
                handler.removeCallbacks(progressTask);
                handler.post(progressTask);
            }
        }
    }

    @Override protected void onPause() {
        captureProgress();
        stopPlaybackFeatures();
        stopQualityPreference();
        command("pause", 0);
        foreground = false;
        if (subtitlesDialog != null) subtitlesDialog.dismiss();
        handler.removeCallbacks(progressTask);
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
        stopPlaybackFeatures();
        stopQualityPreference();
        handler.removeCallbacks(progressTask);
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
        stopPlaybackFeatures();
        stopQualityPreference();
        destroyed = true;
        if (subtitlesDialog != null) subtitlesDialog.dismiss();
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
