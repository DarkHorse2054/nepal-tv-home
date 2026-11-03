package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** A dedicated physical-resolution surface; the rest of the launcher keeps its normal UI size. */
public final class MechanicalClockSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "MechanicalClock";
    private static final long FRAME_MS = 34L;
    private static final long RESIZE_TIMEOUT_MS = 3000L;
    private static final int MAX_RENDER_FAILURES = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object surfaceLock = new Object();
    private final MechanicalClockTime accessibleClock = new MechanicalClockTime();
    private volatile boolean requested;
    private volatile boolean attached;
    private volatile boolean visible = true;
    private volatile boolean released;
    private volatile boolean smoothMotion = true;
    private volatile boolean surfaceReady;
    private volatile boolean waitingForResize;
    private volatile boolean renderingFailed;
    private Surface drawingSurface;
    private int surfaceWidth, surfaceHeight;
    private long surfaceGeneration;
    private int fixedWidth, fixedHeight;
    private volatile boolean fallbackRequested;
    private HandlerThread renderThread;
    private Handler render;
    private Runnable onRenderingFailure;

    // These fields are confined to the render thread.
    private MechanicalClockRenderer renderer;
    private long loggedGeneration = -1;
    private long statsStart;
    private long frameCount, frameTotalMs, frameMaxMs;
    private boolean initialStats;
    private int consecutiveFailures;

    private final Runnable failed = new Runnable() {
        @Override public void run() {
            if (released || !requested) return;
            stopClock();
            if (onRenderingFailure != null) onRenderingFailure.run();
        }
    };

    private final Runnable resizeTimeout = new Runnable() {
        @Override public void run() {
            if (released || !requested || !waitingForResize) return;
            // Some firmware does not report a failed resize. Resume bounded retries instead of
            // leaving the clock permanently paused waiting for a callback that will never arrive.
            Log.w(TAG, "Clock fallback resize did not complete within " + RESIZE_TIMEOUT_MS + "ms");
            waitingForResize = false;
            wakeRenderer();
        }
    };

    private final Runnable preferences = new Runnable() {
        @Override public void run() {
            if (!requested || released) return;
            try {
                smoothMotion = Settings.Global.getFloat(getContext().getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
            } catch (SecurityException ignored) { smoothMotion = true; }
            long now = System.currentTimeMillis();
            if (accessibleClock.update(now)) setContentDescription(accessibleClock.description);
            main.postDelayed(this, 60_000L - Math.floorMod(now, 60_000L));
        }
    };

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!canRender()) return;
            long started = SystemClock.uptimeMillis();
            int width, height;
            long generation;
            synchronized (surfaceLock) {
                if (!canRender() || drawingSurface == null) return;
                width = surfaceWidth;
                height = surfaceHeight;
                generation = surfaceGeneration;
            }
            try {
                if (renderer == null) renderer = new MechanicalClockRenderer(getContext());
                // Build the static plate without holding the surface lifetime lock.
                renderer.prepare(width, height);
                synchronized (surfaceLock) {
                    if (!canRender() || generation != surfaceGeneration || drawingSurface == null) return;
                    Canvas canvas = null;
                    try {
                        // Surface's method is public since API 23; SurfaceHolder's version needs API 26.
                        canvas = drawingSurface.lockHardwareCanvas();
                        if (loggedGeneration != generation) {
                            Log.i(TAG, "Rendering " + canvas.getWidth() + "x" + canvas.getHeight()
                                    + ", hardware=" + canvas.isHardwareAccelerated()
                                    + ", requested=" + fixedWidth + "x" + fixedHeight);
                            loggedGeneration = generation;
                            statsStart = SystemClock.uptimeMillis();
                            frameCount = frameTotalMs = frameMaxMs = 0;
                            initialStats = true;
                        }
                        renderer.draw(canvas, canvas.getWidth(), canvas.getHeight(),
                                System.currentTimeMillis(), smoothMotion);
                    } finally {
                        if (canvas != null) drawingSurface.unlockCanvasAndPost(canvas);
                    }
                }
                long duration = SystemClock.uptimeMillis() - started;
                consecutiveFailures = 0;
                frameCount++;
                frameTotalMs += duration;
                frameMaxMs = Math.max(frameMaxMs, duration);
                long elapsed = SystemClock.uptimeMillis() - statsStart;
                if (elapsed >= (initialStats ? 10_000L : 60_000L)) {
                    Log.i(TAG, "Frames=" + frameCount + " in " + elapsed + "ms, mean="
                            + (frameTotalMs / Math.max(1, frameCount)) + "ms, max=" + frameMaxMs + "ms");
                    statsStart = SystemClock.uptimeMillis();
                    frameCount = frameTotalMs = frameMaxMs = 0;
                    initialStats = false;
                }
            } catch (RuntimeException | OutOfMemoryError failure) {
                // Surface loss during normal dismissal is not a renderer failure.
                if (!canRender()) return;
                Log.w(TAG, "Clock rendering failed at " + width + "x" + height, failure);
                if (renderer != null) renderer.releaseResources();
                renderer = null;
                if (requestFallback(width, height)) return;
                if (++consecutiveFailures >= MAX_RENDER_FAILURES) {
                    Log.e(TAG, "Clock rendering failed repeatedly; ending the screensaver");
                    renderingFailed = true;
                    main.post(failed);
                    return;
                }
                if (canRender()) render.postDelayed(this, 1000L);
                return;
            }
            if (!canRender()) return;
            long next = smoothMotion ? started + FRAME_MS
                    : SystemClock.uptimeMillis() + 1000L - Math.floorMod(System.currentTimeMillis(), 1000L);
            // Never catch up missed frames in a burst.
            render.postAtTime(this, Math.max(next, SystemClock.uptimeMillis() + 1L));
        }
    };

    public MechanicalClockSurfaceView(Context context) { this(context, null); }
    public MechanicalClockSurfaceView(Context context, AttributeSet attributes) { this(context, attributes, 0); }
    public MechanicalClockSurfaceView(Context context, AttributeSet attributes, int style) {
        super(context, attributes, style);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        getHolder().setFormat(PixelFormat.RGBX_8888);
        getHolder().addCallback(this);
    }

    public void startClock() {
        if (released || renderingFailed) return;
        requested = true;
        main.removeCallbacks(preferences);
        preferences.run();
        configureSurface(false);
        wakeRenderer();
    }

    public void stopClock() {
        requested = false;
        waitingForResize = false;
        main.removeCallbacks(preferences);
        main.removeCallbacks(resizeTimeout);
        main.removeCallbacks(failed);
        if (render != null) render.removeCallbacks(frame);
    }

    /** The owning DreamService can return to Home if the device cannot provide a working surface. */
    public void setOnRenderingFailure(Runnable listener) {
        onRenderingFailure = listener;
    }

    public void releaseResources() {
        if (released) return;
        released = true;
        stopClock();
        onRenderingFailure = null;
        final Handler worker = render;
        final HandlerThread thread = renderThread;
        if (worker == null) return;
        worker.removeCallbacksAndMessages(null);
        // Queue cleanup after an in-flight frame; never join the render thread from the UI thread.
        worker.post(() -> {
            if (renderer != null) renderer.releaseResources();
            renderer = null;
            thread.quitSafely();
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        configureSurface(false);
        wakeRenderer();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        releaseResources();
        super.onDetachedFromWindow();
    }

    @Override public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        visible = isVisible;
        if (isVisible) wakeRenderer();
        else if (render != null) render.removeCallbacks(frame);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        synchronized (surfaceLock) {
            drawingSurface = holder.getSurface();
            surfaceGeneration++;
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        main.removeCallbacks(resizeTimeout);
        synchronized (surfaceLock) {
            drawingSurface = holder.getSurface();
            surfaceWidth = width;
            surfaceHeight = height;
            surfaceReady = width > 0 && height > 0;
            waitingForResize = false;
            surfaceGeneration++;
        }
        wakeRenderer();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        // Set the stop flag before waiting for a short in-flight GPU submission to finish.
        surfaceReady = false;
        main.removeCallbacks(resizeTimeout);
        if (render != null) render.removeCallbacks(frame);
        synchronized (surfaceLock) {
            drawingSurface = null;
            surfaceGeneration++;
        }
    }

    private boolean canRender() {
        return requested && attached && visible && !released && !renderingFailed
                && surfaceReady && !waitingForResize;
    }

    private void wakeRenderer() {
        if (!canRender()) return;
        if (render == null) {
            renderThread = new HandlerThread("mechanical-clock-render");
            renderThread.start();
            render = new Handler(renderThread.getLooper());
        }
        render.post(() -> {
            render.removeCallbacks(frame);
            if (canRender()) render.post(frame);
        });
    }

    private void configureSurface(boolean fallback) {
        if (released) return;
        Display display = getDisplay();
        if (display == null) return;
        Display.Mode mode = display.getMode();
        int width = Math.max(1, mode.getPhysicalWidth());
        int height = Math.max(1, mode.getPhysicalHeight());
        int maxWidth = fallback || fallbackRequested ? 1920 : 3840;
        int maxHeight = fallback || fallbackRequested ? 1080 : 2160;
        double scale = Math.min(1d, Math.min((double) maxWidth / width, (double) maxHeight / height));
        width = Math.max(1, (int) Math.round(width * scale));
        height = Math.max(1, (int) Math.round(height * scale));
        if (width == fixedWidth && height == fixedHeight) return;
        fixedWidth = width;
        fixedHeight = height;
        getHolder().setFixedSize(width, height);
    }

    private boolean requestFallback(int width, int height) {
        if ((width <= 1920 && height <= 1080) || fallbackRequested) return false;
        // Set this on the single render thread before posting the resize. A repeated failure must
        // not pause again after a fallback has already been requested.
        fallbackRequested = true;
        waitingForResize = true;
        main.post(() -> {
            if (released || !requested) return;
            Log.w(TAG, "Using bounded 1080p clock fallback");
            main.removeCallbacks(resizeTimeout);
            main.postDelayed(resizeTimeout, RESIZE_TIMEOUT_MS);
            try {
                configureSurface(true);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Could not request clock fallback surface", failure);
                main.removeCallbacks(resizeTimeout);
                waitingForResize = false;
                wakeRenderer();
            }
        });
        return true;
    }
}
