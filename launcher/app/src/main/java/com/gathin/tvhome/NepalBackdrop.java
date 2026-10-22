package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local scenes follow Pacific time. Normal and small frosted scenes load off the UI thread. */
public class NepalBackdrop extends View {
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static Bitmap sharedScene;
    private static Bitmap sharedFrostedScene;
    private static int sharedResource;
    private Bitmap scene;
    private Bitmap frostedScene;
    private int requestedResource;
    private int requestToken;
    private int dimPercent;
    private Runnable onSceneReadyListener;
    private final int[] backdropWindowLocation = new int[2];
    private final int[] hostWindowLocation = new int[2];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF target = new RectF(0, 0, 960, 540);
    private final Shader left = new LinearGradient(0, 0, 960, 0,
            new int[]{0xBD030916, 0x7A040A14, 0x08040A14}, new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP);
    private final Shader top = new LinearGradient(0, 0, 0, 150, 0x6C02060E, 0x0002060E, Shader.TileMode.CLAMP);
    private final Shader bottom = new LinearGradient(0, 300, 0, 540,
            new int[]{0x00030911, 0x96030911, 0xF0030810}, new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP);

    public NepalBackdrop(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshAppearance();
    }

    /** Called on return home, after an appearance change, and once per minute. */
    void refreshAppearance() {
        int nextDimPercent = HomePreferences.getDimPercent(getContext());
        boolean dimChanged = dimPercent != nextDimPercent;
        dimPercent = nextDimPercent;
        int hour = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")).get(Calendar.HOUR_OF_DAY);
        String mode = HomePreferences.getWallpaperMode(getContext());
        if ("auto".equals(mode)) mode = hour >= 6 && hour < 16 ? "morning" : hour >= 16 && hour < 20 ? "dusk" : "night";
        final int resource = "morning".equals(mode) ? R.drawable.nepal_cinematic_morning
                : "night".equals(mode) ? R.drawable.nepal_cinematic_night : R.drawable.nepal_cinematic_dusk;
        invalidate();
        if (dimChanged) notifySceneReady();
        if (resource == requestedResource) return;
        requestedResource = resource;
        // Until a matching pair arrives, never frost a new scene with another resource's sample.
        frostedScene = null;
        notifySceneReady();
        final int token = ++requestToken;
        synchronized (NepalBackdrop.class) {
            if (sharedResource == resource && sharedScene != null) {
                scene = sharedScene;
                frostedScene = sharedFrostedScene;
                invalidate();
                notifySceneReady();
                return;
            }
        }
        final android.content.res.Resources resources = getResources();
        LOADER.execute(() -> {
            Bitmap decoded = null;
            Bitmap blurred = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeResource(resources, resource, bounds);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inScaled = false;
                options.inSampleSize = 1;
                while (bounds.outWidth / options.inSampleSize > 1920) options.inSampleSize *= 2;
                decoded = BitmapFactory.decodeResource(resources, resource, options);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                // Keep the prior scene or the dark fallback if decoding fails.
            }
            if (decoded != null) {
                try {
                    blurred = createFrostedScene(decoded);
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    // The normal scene remains usable if a small blur allocation fails.
                }
            }
            final Bitmap result = decoded;
            final Bitmap frostedResult = blurred;
            post(() -> {
                if (token != requestToken) return;
                if (result != null) {
                    synchronized (NepalBackdrop.class) {
                        sharedScene = result;
                        sharedFrostedScene = frostedResult;
                        sharedResource = resource;
                    }
                    scene = result;
                    frostedScene = frostedResult;
                } else {
                    requestedResource = 0;
                }
                invalidate();
                notifySceneReady();
            });
        });
    }

    /** Main-thread notification lets the dock repaint when this view's scene or dim changes. */
    public void setOnSceneReadyListener(Runnable listener) {
        onSceneReadyListener = listener;
        if (listener != null) notifySceneReady();
    }

    private void notifySceneReady() {
        post(() -> {
            Runnable listener = onSceneReadyListener;
            if (listener != null) listener.run();
        });
    }

    /**
     * Draws the cached scene at its original window position into an already-clipped host canvas.
     * No screenshot, bitmap allocation, or blur work occurs here, including during focus animation.
     */
    public boolean drawFrostedScene(Canvas canvas, View host) {
        Bitmap sample = frostedScene;
        if (sample == null || host == null || getWidth() <= 0 || getHeight() <= 0) return false;
        getLocationInWindow(backdropWindowLocation);
        host.getLocationInWindow(hostWindowLocation);
        canvas.save();
        canvas.translate(backdropWindowLocation[0] - hostWindowLocation[0],
                backdropWindowLocation[1] - hostWindowLocation[1]);
        canvas.scale(getWidth() / 960f, getHeight() / 540f);
        drawScene(canvas, sample);
        canvas.restore();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / 960f, getHeight() / 540f);
        drawScene(canvas, scene);
        canvas.restore();
    }

    private void drawScene(Canvas canvas, Bitmap bitmap) {
        paint.setShader(null); paint.setColor(0xFF050B15); canvas.drawRect(target, paint);
        if (bitmap != null) canvas.drawBitmap(bitmap, null, target, paint);
        paint.setShader(left); canvas.drawRect(target, paint);
        paint.setShader(top); canvas.drawRect(0, 0, 960, 150, paint);
        paint.setShader(bottom); canvas.drawRect(0, 300, 960, 540, paint);
        paint.setShader(null);
        paint.setColor((dimPercent * 255 / 100) << 24);
        if (dimPercent > 0) canvas.drawRect(target, paint);
    }

    /** At most 32,400 pixels, two 126 KiB work arrays, and one 127 KiB bitmap per scene load. */
    private static Bitmap createFrostedScene(Bitmap source) {
        float scale = Math.min(1f, Math.min(240f / source.getWidth(), 135f / source.getHeight()));
        int width = Math.max(1, Math.min(240, Math.round(source.getWidth() * scale)));
        int height = Math.max(1, Math.min(135, Math.round(source.getHeight() * scale)));
        Bitmap small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas smallCanvas = new Canvas(small);
            Paint filtered = new Paint(Paint.FILTER_BITMAP_FLAG);
            smallCanvas.drawBitmap(source, null, new RectF(0, 0, width, height), filtered);
            int[] first = new int[width * height];
            int[] second = new int[width * height];
            small.getPixels(first, 0, width, 0, 0, width, height);
            // Two separable radius-four box blurs soften the scenery without a render-time effect.
            for (int pass = 0; pass < 2; pass++) {
                boxBlur(first, second, width, height, true);
                boxBlur(second, first, width, height, false);
            }
            small.setPixels(first, 0, width, 0, 0, width, height);
            return small;
        } catch (RuntimeException | OutOfMemoryError error) {
            small.recycle();
            throw error;
        }
    }

    /** Sliding sums keep each sweep O(width * height), with clamped edges and no per-pixel objects. */
    private static void boxBlur(int[] source, int[] output, int width, int height, boolean horizontal) {
        final int radius = 4;
        final int window = radius * 2 + 1;
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int step = horizontal ? 1 : width;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * width : line;
            int alpha = 0, red = 0, green = 0, blue = 0;
            for (int offset = -radius; offset <= radius; offset++) {
                int pixel = source[base + Math.min(length - 1, Math.max(0, offset)) * step];
                alpha += pixel >>> 24;
                red += (pixel >>> 16) & 255;
                green += (pixel >>> 8) & 255;
                blue += pixel & 255;
            }
            for (int position = 0; position < length; position++) {
                output[base + position * step] = (alpha / window) << 24 | (red / window) << 16
                        | (green / window) << 8 | blue / window;
                int remove = source[base + Math.max(0, position - radius) * step];
                int add = source[base + Math.min(length - 1, position + radius + 1) * step];
                alpha += (add >>> 24) - (remove >>> 24);
                red += ((add >>> 16) & 255) - ((remove >>> 16) & 255);
                green += ((add >>> 8) & 255) - ((remove >>> 8) & 255);
                blue += (add & 255) - (remove & 255);
            }
        }
    }
}
