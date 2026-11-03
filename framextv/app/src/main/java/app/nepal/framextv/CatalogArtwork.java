package app.nepal.framextv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Visible-only artwork with its own cancellable client. All public methods run on the main thread. */
final class CatalogArtwork implements AutoCloseable {
    private static final int CACHE_BYTES = 6 * 1024 * 1024;
    private static final int MAX_BINDINGS = 192, MAX_FAILURES = 64, MAX_DIMENSION = 2048;
    private static final long MAX_DECODE_PIXELS = 1536 * 1024;
    private static final long RETRY_DELAY_MS = 30_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CatalogClient client = new CatalogClient();
    private final ThreadPoolExecutor workers;
    private final List<Binding> bindings = new ArrayList<>();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, Long> failures = new LinkedHashMap<>();
    private final Rect visibleRect = new Rect();
    private final LruCache<String, Image> cache = new LruCache<String, Image>(CACHE_BYTES) {
        @Override protected int sizeOf(String url, Image image) {
            return image.bitmap.getAllocationByteCount();
        }
    };
    private boolean paused, refreshQueued;
    private volatile boolean closed;
    private long generation;
    private final Runnable refreshTask = () -> {
        refreshQueued = false;
        refreshVisible();
    };
    private final View.OnLayoutChangeListener layoutListener = (view, l, t, r, b, ol, ot, or, ob) -> scheduleRefresh();
    private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { scheduleRefresh(); }
        @Override public void onViewDetachedFromWindow(View view) { scheduleRefresh(); }
    };

    CatalogArtwork(Context context) {
        requireMainThread();
        if (context == null) throw new IllegalArgumentException("An application context is required.");
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "theater-artwork-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    void bind(ImageView view, String url, int maxWidthPx, int maxHeightPx) {
        requireMainThread();
        if (view == null) return;
        Binding existing = null;
        for (Iterator<Binding> iterator = bindings.iterator(); iterator.hasNext();) {
            Binding binding = iterator.next();
            ImageView bound = binding.view.get();
            if (bound == null) iterator.remove();
            else if (bound == view) { existing = binding; break; }
        }
        String address = url == null || url.length() > 512 ? "" : url.trim();
        if (closed || address.isEmpty()) {
            if (existing != null) { detach(existing); bindings.remove(existing); }
            view.setImageDrawable(null);
            return;
        }
        int width = Math.max(1, Math.min(MAX_DIMENSION, maxWidthPx));
        int height = Math.max(1, Math.min(MAX_DIMENSION, maxHeightPx));
        if (existing != null && existing.url.equals(address)) {
            existing.width = width;
            existing.height = height;
        } else {
            if (existing != null) { detach(existing); bindings.remove(existing); }
            while (bindings.size() >= MAX_BINDINGS) detach(bindings.remove(0));
            Binding binding = new Binding(view, address, width, height);
            bindings.add(binding);
            view.setImageDrawable(null);
            view.addOnLayoutChangeListener(layoutListener);
            view.addOnAttachStateChangeListener(attachListener);
        }
        if (paused) view.setImageDrawable(null);
        scheduleRefresh();
    }

    void refreshVisible() {
        requireMainThread();
        main.removeCallbacks(refreshTask);
        refreshQueued = false;
        if (closed || paused) return;
        List<Binding> visible = new ArrayList<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        Map<String, Image> available = new LinkedHashMap<>();
        for (Iterator<Binding> iterator = bindings.iterator(); iterator.hasNext();) {
            Binding binding = iterator.next();
            ImageView view = binding.view.get();
            if (view == null) { iterator.remove(); continue; }
            if (!view.isShown() || view.getWindowToken() == null || !view.getGlobalVisibleRect(visibleRect)
                    || visibleRect.width() <= 0 || visibleRect.height() <= 0) {
                clearImage(binding);
                continue;
            }
            visible.add(binding);
            Target target = targets.get(binding.url);
            if (target == null) targets.put(binding.url, new Target(binding.width, binding.height));
            else { target.width = Math.max(target.width, binding.width); target.height = Math.max(target.height, binding.height); }
            Image cached = cache.get(binding.url), displayed = binding.displayed(view);
            Image best = better(cached, displayed);
            available.put(binding.url, better(available.get(binding.url), best));
        }
        for (Binding binding : visible) {
            Image image = available.get(binding.url);
            ImageView view = binding.view.get();
            if (view != null && covers(image, binding.width, binding.height)) binding.show(view, image);
        }
        boolean wantedJob = false;
        for (String url : jobs.keySet()) if (targets.containsKey(url)) { wantedJob = true; break; }
        // Scrolling beyond every request should not leave two obsolete network calls in front of new artwork.
        if (!jobs.isEmpty() && !wantedJob) cancelJobs();
        long now = SystemClock.uptimeMillis();
        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            String url = entry.getKey();
            Target target = entry.getValue();
            Job pending = jobs.get(url);
            if (pending != null) {
                pending.width = Math.max(pending.width, target.width);
                pending.height = Math.max(pending.height, target.height);
                continue;
            }
            Long retryAt = failures.get(url);
            if (covers(available.get(url), target.width, target.height) || retryAt != null && retryAt > now
                    || jobs.size() >= 2) continue;
            Job job = new Job(url, target.width, target.height, generation);
            jobs.put(url, job);
            try {
                job.future = workers.submit(() -> load(job));
            } catch (RejectedExecutionException unavailable) {
                jobs.remove(url);
                rememberFailure(url, now + 1000);
            }
        }
    }

    void clearBindings() {
        requireMainThread();
        main.removeCallbacks(refreshTask);
        refreshQueued = false;
        for (Binding binding : bindings) detach(binding);
        bindings.clear();
        failures.clear();
        cancelJobs();
    }

    void pause() {
        requireMainThread();
        if (closed || paused) return;
        paused = true;
        main.removeCallbacks(refreshTask);
        refreshQueued = false;
        for (Binding binding : bindings) clearImage(binding);
        cancelJobs();
    }

    void resume() {
        requireMainThread();
        if (closed) return;
        paused = false;
        failures.clear();
        refreshVisible();
    }

    @Override public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        clearBindings();
        main.removeCallbacksAndMessages(null);
        workers.shutdownNow();
        client.close();
        cache.evictAll();
    }

    private void load(Job job) {
        Image result = null;
        try {
            if (!job.cancelled && !closed && !Thread.currentThread().isInterrupted()) {
                byte[] bytes = client.poster(job.url);
                if (!job.cancelled && !closed && !Thread.currentThread().isInterrupted()) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth <= 4096 && bounds.outHeight <= 4096) {
                        int sample = sampleFor(bounds.outWidth, bounds.outHeight, job.width, job.height);
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = sample;
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                        if (bitmap != null) {
                            if (bitmap.getAllocationByteCount() <= CACHE_BYTES) result = new Image(bitmap, bounds.outWidth, bounds.outHeight, sample);
                            else bitmap.recycle();
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Cards stay usable when artwork is unavailable. No upstream addresses or bodies are logged.
        }
        Image completed = result;
        if (closed || job.cancelled) { discard(completed); return; }
        main.post(() -> complete(job, completed));
    }

    private void complete(Job job, Image result) {
        if (closed || paused || job.cancelled || job.generation != generation || jobs.get(job.url) != job) {
            discard(result);
            return;
        }
        jobs.remove(job.url);
        if (result != null) { cache.put(job.url, result); failures.remove(job.url); }
        else rememberFailure(job.url, SystemClock.uptimeMillis() + RETRY_DELAY_MS);
        refreshVisible();
    }

    private void cancelJobs() {
        generation++;
        for (Job job : jobs.values()) {
            job.cancelled = true;
            if (job.future != null) job.future.cancel(true);
        }
        jobs.clear();
        workers.getQueue().clear();
        client.cancelAll();
    }

    private void scheduleRefresh() {
        if (closed || paused || refreshQueued) return;
        refreshQueued = true;
        main.post(refreshTask);
    }

    private void rememberFailure(String url, long retryAt) {
        failures.remove(url);
        failures.put(url, retryAt);
        while (failures.size() > MAX_FAILURES) failures.remove(failures.keySet().iterator().next());
    }

    private void clearImage(Binding binding) {
        ImageView view = binding.view.get();
        if (view != null) view.setImageDrawable(null);
        binding.bitmap = null;
    }

    private void detach(Binding binding) {
        ImageView view = binding.view.get();
        if (view != null) {
            view.removeOnLayoutChangeListener(layoutListener);
            view.removeOnAttachStateChangeListener(attachListener);
        }
        clearImage(binding);
    }

    private static Image better(Image first, Image second) {
        if (first == null) return second;
        if (second == null) return first;
        return second.sample < first.sample ? second : first;
    }

    private static boolean covers(Image image, int width, int height) {
        return image != null && !image.bitmap.isRecycled()
                && image.sample <= sampleFor(image.sourceWidth, image.sourceHeight, width, height);
    }

    private static int sampleFor(int width, int height, int maxWidth, int maxHeight) {
        int sample = 1;
        while ((width + sample - 1) / sample > maxWidth || (height + sample - 1) / sample > maxHeight
                || (long) ((width + sample - 1) / sample) * ((height + sample - 1) / sample) > MAX_DECODE_PIXELS) sample *= 2;
        return sample;
    }

    private static void discard(Image image) {
        if (image != null && !image.bitmap.isRecycled()) image.bitmap.recycle();
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Artwork bindings belong to the main thread.");
    }

    private static final class Target {
        int width, height;
        Target(int width, int height) { this.width = width; this.height = height; }
    }

    private static final class Job {
        final String url;
        final long generation;
        volatile int width, height;
        volatile boolean cancelled;
        Future<?> future;
        Job(String url, int width, int height, long generation) {
            this.url = url; this.width = width; this.height = height; this.generation = generation;
        }
    }

    private static final class Image {
        final Bitmap bitmap;
        final int sourceWidth, sourceHeight, sample;
        Image(Bitmap bitmap, int width, int height, int sample) {
            this.bitmap = bitmap; sourceWidth = width; sourceHeight = height; this.sample = sample;
        }
    }

    private static final class Binding {
        final WeakReference<ImageView> view;
        final String url;
        int width, height, sourceWidth, sourceHeight, sample;
        WeakReference<Bitmap> bitmap;
        Binding(ImageView view, String url, int width, int height) {
            this.view = new WeakReference<>(view); this.url = url; this.width = width; this.height = height;
        }
        Image displayed(ImageView view) {
            Bitmap value = bitmap == null ? null : bitmap.get();
            if (value == null || value.isRecycled() || !(view.getDrawable() instanceof BitmapDrawable)
                    || ((BitmapDrawable) view.getDrawable()).getBitmap() != value) return null;
            return new Image(value, sourceWidth, sourceHeight, sample);
        }
        void show(ImageView view, Image image) {
            if (bitmap == null || bitmap.get() != image.bitmap || !(view.getDrawable() instanceof BitmapDrawable)
                    || ((BitmapDrawable) view.getDrawable()).getBitmap() != image.bitmap) view.setImageBitmap(image.bitmap);
            bitmap = new WeakReference<>(image.bitmap);
            sourceWidth = image.sourceWidth; sourceHeight = image.sourceHeight; sample = image.sample;
        }
    }
}
