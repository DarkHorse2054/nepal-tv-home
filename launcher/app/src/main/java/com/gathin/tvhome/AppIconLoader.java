package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;

/**
 * Uses compact official marks for Netflix/YouTube and otherwise the installed app's artwork,
 * rendering at most 384 x 384 output pixels.
 * That is an output-resolution bound, not a claim of additional detail: layered drawables may
 * contain lower-resolution raster children. On allocation failure, retain the native fallback.
 */
public final class AppIconLoader {
    private static final int MAX_ICON_PX = 384;
    private static final int REQUEST_DENSITY = DisplayMetrics.DENSITY_XXXHIGH;

    private AppIconLoader() { }

    public static Drawable load(Context context, PackageManager packageManager, ResolveInfo entry) {
        ActivityInfo activity = entry == null ? null : entry.activityInfo;
        ApplicationInfo application = activity == null ? null : activity.applicationInfo;
        if (application != null) {
            Drawable compactMark = DockBrandIcons.load(context, application.packageName);
            if (compactMark != null) return compactMark;
            int resourceId = activity.icon != 0 ? activity.icon : application.icon;
            if (resourceId != 0) {
                try {
                    Resources appResources = packageManager.getResourcesForApplication(application);
                    Drawable artwork = appResources.getDrawableForDensity(resourceId, REQUEST_DENSITY, null);
                    if (artwork != null) {
                        TypedValue selected = new TypedValue();
                        appResources.getValueForDensity(resourceId, REQUEST_DENSITY, selected, true);
                        return bounded(context.getResources(), appResources, artwork, selected.density);
                    }
                } catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError unavailable) {
                    // Packages can disappear during reload, or contain an incompatible drawable.
                    // Keep the native application-icon fallback rather than inventing a logo.
                }
            }
            try {
                Drawable fallback = application.loadIcon(packageManager);
                if (fallback != null) {
                    try {
                        return bounded(context.getResources(), context.getResources(), fallback, -1);
                    } catch (RuntimeException | OutOfMemoryError unavailable) {
                        // This native drawable is already available; don't allocate another raster
                        // or request a different resource while recovering from memory pressure.
                        return fallback;
                    }
                }
            } catch (RuntimeException | OutOfMemoryError unavailable) {
                // The package manager's own default is the final native fallback below.
            }
        }
        Drawable fallback = packageManager.getDefaultActivityIcon();
        try {
            return bounded(context.getResources(), context.getResources(), fallback, -1);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            // Preserve a usable system icon even if a vendor drawable cannot be rasterized.
            return fallback;
        }
    }

    private static Drawable bounded(Resources outputResources, Resources artworkResources,
            Drawable artwork, int resourceDensity) {
        Drawable.ConstantState state = artwork.getConstantState();
        Drawable drawable = state == null ? artwork : state.newDrawable(artworkResources).mutate();
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        boolean bitmapArtwork = drawable instanceof BitmapDrawable;
        if (bitmapArtwork) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("Unavailable icon bitmap");
            width = bitmap.getWidth();
            height = bitmap.getHeight();
            // getDrawableForDensity may enlarge a lower-density resource during decoding. Cap
            // that raster back to its original pixel size; resampling never creates real detail.
            double nativeScale = 1.0;
            if (resourceDensity == TypedValue.DENSITY_DEFAULT) {
                // ResourcesImpl skips requested-density substitution when the density is 0.
                // BitmapFactory then treats 0 as mdpi and scales to the resource display density,
                // not REQUEST_DENSITY. Undo only that concrete default-density enlargement.
                int displayDensity = artworkResources.getDisplayMetrics().densityDpi;
                if (displayDensity > DisplayMetrics.DENSITY_DEFAULT) {
                    nativeScale = DisplayMetrics.DENSITY_DEFAULT / (double) displayDensity;
                }
            } else if (resourceDensity > 0 && resourceDensity < REQUEST_DENSITY) {
                nativeScale = resourceDensity / (double) REQUEST_DENSITY;
            }
            if (nativeScale < 1.0) {
                width = Math.max(1, (int) Math.round(width * nativeScale));
                height = Math.max(1, (int) Math.round(height * nativeScale));
            }
            ((BitmapDrawable) drawable).setFilterBitmap(true);
        }
        if (width <= 0 || height <= 0) {
            width = MAX_ICON_PX;
            height = MAX_ICON_PX;
        }
        // Vectors and layered resources render at the bounded final resolution. Plain rasters
        // are only reduced, preserving aspect ratio and avoiding a fabricated "HD" enlargement.
        double scale = MAX_ICON_PX / (double) Math.max(width, height);
        if (bitmapArtwork) scale = Math.min(1.0, scale);
        int targetWidth = Math.max(1, Math.min(MAX_ICON_PX, (int) Math.round(width * scale)));
        int targetHeight = Math.max(1, Math.min(MAX_ICON_PX, (int) Math.round(height * scale)));
        Rect originalBounds = new Rect(drawable.getBounds());
        Bitmap rendered = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(rendered);
            drawable.setBounds(0, 0, targetWidth, targetHeight);
            drawable.draw(canvas);
            rendered.setDensity(outputResources.getDisplayMetrics().densityDpi);
            BitmapDrawable result = new BitmapDrawable(outputResources, rendered);
            result.setFilterBitmap(true);
            return result;
        } catch (RuntimeException | OutOfMemoryError failure) {
            rendered.recycle();
            throw failure;
        } finally {
            drawable.setBounds(originalBounds);
        }
    }
}
