package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** Compact official marks for two known apps; packaged original assets remain unmodified. */
final class DockBrandIcons {
    private static final int SIZE = 384;

    private DockBrandIcons() { }

    static Drawable load(Context context, String packageName) {
        boolean netflix = "com.netflix.ninja".equals(packageName)
                || "com.netflix.mediaclient".equals(packageName);
        boolean youtube = "com.amazon.firetv.youtube".equals(packageName)
                || "com.google.android.youtube.tv".equals(packageName)
                || "com.google.android.youtube".equals(packageName);
        if (!netflix && !youtube) return null;
        int resource = netflix ? R.drawable.netflix_official_symbol : R.drawable.youtube_official_play;
        Bitmap original = null;
        Bitmap rendered = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(context.getResources(), resource, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inSampleSize = 1;
            // The original Netflix file has generous transparent export margins. Sample for
            // its 900px-tall visible mark so those margins do not reduce the real detail.
            float desired = netflix ? SIZE * .72f : SIZE;
            int meaningfulSide = netflix ? 900 : Math.max(bounds.outWidth, bounds.outHeight);
            while (meaningfulSide / (options.inSampleSize * 2) >= desired) {
                options.inSampleSize *= 2;
            }
            original = BitmapFactory.decodeResource(context.getResources(), resource, options);
            if (original == null) return null;
            rendered = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(rendered);
            canvas.drawColor(netflix ? 0xFF08080A : 0xFF101318);
            // Exact alpha bounds in the unchanged 991x1801 Netflix source are
            // [247,451..743,1351]. Position that visible mark, retaining its shape and colors.
            // YouTube keeps the complete original file, including its clear-space margin.
            Rect source = netflix ? new Rect(
                    Math.round(original.getWidth() * 247f / 991f),
                    Math.round(original.getHeight() * 451f / 1801f),
                    Math.round(original.getWidth() * 743f / 991f),
                    Math.round(original.getHeight() * 1351f / 1801f))
                    : new Rect(0, 0, original.getWidth(), original.getHeight());
            float available = netflix ? SIZE * .72f : SIZE;
            float scale = Math.min(available / source.width(), available / source.height());
            float width = source.width() * scale;
            float height = source.height() * scale;
            RectF target = new RectF((SIZE - width) / 2f, (SIZE - height) / 2f,
                    (SIZE + width) / 2f, (SIZE + height) / 2f);
            canvas.drawBitmap(original, source, target, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            rendered.setDensity(context.getResources().getDisplayMetrics().densityDpi);
            BitmapDrawable result = new BitmapDrawable(context.getResources(), rendered);
            result.setFilterBitmap(true);
            rendered = null; // Ownership passes to the drawable.
            return result;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null; // AppIconLoader retains the installed app's own icon as its fallback.
        } finally {
            if (original != null) original.recycle();
            if (rendered != null) rendered.recycle();
        }
    }
}
