package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;

/** Native-pixel mechanical dial, owned exclusively by the screensaver render thread. */
final class MechanicalClockRenderer {
    private static final float WIDTH = MechanicalDialGeometry.WIDTH, HEIGHT = MechanicalDialGeometry.HEIGHT;
    private static final float PIVOT_X = MechanicalDialGeometry.PIVOT_X, PIVOT_Y = MechanicalDialGeometry.PIVOT_Y;
    private static final float HEART_X = 835f, HEART_Y = 659f, HEART_RADIUS = 139f;

    private final MechanicalClockTime clock = new MechanicalClockTime();
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = fill(0x88000000);
    private final Paint softShadow = fill(0x30000000);
    private final Paint metalLeft = gradient(-63, -680, 8, -220,
            new int[]{0xFFF1D9B9, 0xFFB68F61, 0xFFFFEBD0, 0xFFE9CAA0, 0xFFAD8050},
            new float[]{0, .32f, .43f, .62f, 1});
    private final Paint metalRight = gradient(0, -760, 63, -110,
            new int[]{0xFFD5B38D, 0xFF79552F, 0xFFC19B6D, 0xFF9C7448, 0xFF48331F},
            new float[]{0, .35f, .47f, .66f, 1});
    private final Paint minuteLeft = gradient(-43, -900, 15, -150,
            new int[]{0xFFE7CAA6, 0xFFD1AE7F, 0xFFF4DEC0, 0xFFB99161},
            new float[]{0, .34f, .49f, 1});
    private final Paint minuteRight = gradient(40, -920, -10, -120,
            new int[]{0xFFB38B59, 0xFF79532E, 0xFFD8B382, 0xFF916B42},
            new float[]{0, .35f, .51f, 1});
    private final Paint minuteInlayPaint = fill(0xFFDCCDAD);
    private final Paint hourInlayPaint = fill(0xFFEADAC0);
    private final Paint fineGold = fill(0xFFFFE1B8);
    private final Paint darkGold = fill(0xFF74502D);
    private final Paint darkBevel = stroke(0xFF30251A, 2.8f);
    private final Paint lightBevel = stroke(0xFFCCB38B, 1.1f);
    private final Paint fineMachining = stroke(0x44EED8AF, .5f);
    private final Paint secondPaint = stroke(0xFFF5D1A0, 2.6f);
    private final Paint secondShade = stroke(0xA6000000, 4f);
    private final Paint digitalPaint = gradient(0, 180, 0, 243,
            new int[]{0xFFF5DEC0, 0xFFE5C49C, 0xFFB38659}, null);
    private final Paint periodPaint = fill(0xFFE9CEAA);
    private final Paint datePaint = gradient(0, 415, 0, 463,
            new int[]{0xFFF5DFBF, 0xFFCBA375}, null);
    private final Paint hubPaint = radial(-12, -16, 48,
            new int[]{0xFFFFE7C4, 0xFFE6B77B, 0xFF94602E, 0xFF33200F}, new float[]{0, .49f, .81f, 1});
    private final Paint capPaint = gradient(-25, -24, 27, 26,
            new int[]{0xFF63482B, 0xFFFBE0B5, 0xFFDAB580, 0xFFA57A43, 0xFFE3C491, 0xFF6C4B27},
            new float[]{0, .23f, .37f, .68f, .82f, 1});
    private final Paint machinedRing = metalRing();
    private final Paint pinPaint = radial(-2, -3, 10,
            new int[]{0xFFE6D7B9, 0xFF6B614D, 0xFF171914}, new float[]{0, .5f, 1});

    private final Path hourOutline = polygon(0, -1000, -55, -440, -45, -180, -18, -60, 0, 30, 18, -60, 45, -180, 55, -440);
    private final Path hourLeft = polygon(0, -1000, -55, -440, -45, -180, -18, -60, 0, 30);
    private final Path hourRight = polygon(0, -1000, 55, -440, 45, -180, 18, -60, 0, 30);
    private final Path hourInlay = polygon(0, -800, -12, -430, -10, -240, 10, -240, 12, -430);
    private final Path minuteOutline = polygon(0, -1000, -20, -770, -43, -360, -27, -90, 0, 30, 27, -90, 43, -360, 20, -770);
    private final Path minuteFacetLeft = polygon(0, -1000, -20, -770, -43, -360, -27, -90, 0, 30);
    private final Path minuteFacetRight = polygon(0, -1000, 20, -770, 43, -360, 27, -90, 0, 30);
    private final Path minuteInlay = polygon(0, -820, -4, -640, -6, -285, 6, -285, 4, -640);
    private final Path heartClip = new Path();
    private final MechanicalClockFace face = new MechanicalClockFace();
    private final MechanicalClockMovement movement = new MechanicalClockMovement();
    private Bitmap plate;
    private int plateWidth, plateHeight;
    private boolean smoothMotion = true;
    private float digitalX, periodX;
    private final Matrix pixelMatrix = new Matrix();
    private final Matrix identity = new Matrix();
    private final Path pixelPath = new Path();

    MechanicalClockRenderer(Context context) {
        Typeface serif = Typeface.create("serif", Typeface.NORMAL);
        digitalPaint.setTypeface(serif);
        digitalPaint.setTextSize(61);
        periodPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        periodPaint.setTextSize(22);
        datePaint.setTypeface(serif);
        datePaint.setTextSize(56);
        datePaint.setTextAlign(Paint.Align.CENTER);
        heartClip.addCircle(0, 0, HEART_RADIUS, Path.Direction.CW);
    }

    /** Rasterize vector geometry once at the surface's actual pixel dimensions, off the UI thread. */
    void prepare(int width, int height) {
        if (plate != null && width == plateWidth && height == plateHeight) return;
        if (width < 1 || height < 1 || width > 3840 || height > 2160) {
            throw new IllegalArgumentException("Unsupported clock surface size");
        }
        // Let a previous large plate be reclaimed if the controller is lowering resolution.
        plate = null;
        plateWidth = plateHeight = 0;
        Bitmap next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        next.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(next);
        applyDialTransform(canvas, width, height);
        face.draw(canvas);
        // Previously submitted hardware display lists may still refer to the old bitmap.
        plate = next;
        plateWidth = width;
        plateHeight = height;
        movement.prepare(Math.max(width / WIDTH, height / HEIGHT));
    }

    void releaseResources() {
        movement.releaseResources();
        plate = null;
        plateWidth = plateHeight = 0;
    }

    String getContentDescription() { return clock.description; }

    void draw(Canvas canvas, int width, int height, long now, boolean smooth) {
        smoothMotion = smooth;
        if (clock.update(now)) {
            float mainWidth = digitalPaint.measureText(clock.digital);
            float periodWidth = periodPaint.measureText(clock.period);
            digitalX = 836f - (mainWidth + periodWidth + 10f) / 2f;
            periodX = digitalX + mainWidth + 10f;
        }
        if (plate == null || width != plateWidth || height != plateHeight) {
            throw new IllegalStateException("Prepare the clock face before locking the surface");
        }
        // Surface buffers are not preserved: repaint every pixel on every submitted frame.
        canvas.drawBitmap(plate, 0, 0, platePaint);
        int saved = canvas.save();
        applyDialTransform(canvas, width, height);
        canvas.drawText(clock.digital, digitalX, 233, digitalPaint);
        canvas.drawText(clock.period, periodX, 230, periodPaint);
        canvas.drawText(clock.day, 1409.5f, 457, datePaint);
        double phaseSeconds = smoothMotion ? SystemClock.uptimeMillis() / 1000.0 : 0;
        drawMechanism(canvas, phaseSeconds);
        float hour = clock.hourDegrees(smoothMotion);
        float minute = clock.minuteDegrees(smoothMotion);
        float second = clock.secondDegrees(smoothMotion);
        drawHand(canvas, hour, MechanicalDialGeometry.HOUR_HAND_LENGTH, .60f, true);
        drawHand(canvas, minute, MechanicalDialGeometry.MINUTE_HAND_LENGTH, .38f, false);
        drawSecond(canvas, second, MechanicalDialGeometry.secondHandLength(second));
        drawHub(canvas);
        canvas.restoreToCount(saved);
    }

    private static void applyDialTransform(Canvas canvas, int width, int height) {
        float scale = Math.max(width / WIDTH, height / HEIGHT);
        canvas.translate((width - WIDTH * scale) * .5f, (height - HEIGHT * scale) * .5f);
        canvas.scale(scale, scale);
    }

    /** API 25 caches scaled paths at low resolution. Submit their physical-pixel geometry instead. */
    private void drawPath(Canvas canvas, Path path, Paint paint) {
        canvas.getMatrix(pixelMatrix);
        path.transform(pixelMatrix, pixelPath);
        Shader shader = paint.getShader();
        float strokeWidth = paint.getStrokeWidth();
        if (shader != null) shader.setLocalMatrix(pixelMatrix);
        paint.setStrokeWidth(strokeWidth * pixelMatrix.mapRadius(1f));
        int saved = canvas.save();
        canvas.setMatrix(identity);
        canvas.drawPath(pixelPath, paint);
        canvas.restoreToCount(saved);
        paint.setStrokeWidth(strokeWidth);
        if (shader != null) shader.setLocalMatrix(identity);
    }

    private void drawHand(Canvas canvas, float angle, float length, float width, boolean isHour) {
        Path outline = isHour ? hourOutline : minuteOutline;
        int state = canvas.save();
        canvas.translate(PIVOT_X + 6, PIVOT_Y + 9);
        canvas.rotate(angle);
        canvas.scale(width * 1.1f, length / 1000f);
        drawPath(canvas, outline, softShadow);
        canvas.restoreToCount(state);
        state = canvas.save();
        canvas.translate(PIVOT_X + 3, PIVOT_Y + 6);
        canvas.rotate(angle);
        canvas.scale(width, length / 1000f);
        drawPath(canvas, outline, shadow);
        canvas.restoreToCount(state);
        state = canvas.save();
        canvas.translate(PIVOT_X, PIVOT_Y);
        canvas.rotate(angle);
        canvas.scale(width, length / 1000f);
        drawPath(canvas, isHour ? hourLeft : minuteFacetLeft, isHour ? metalLeft : minuteLeft);
        drawPath(canvas, isHour ? hourRight : minuteFacetRight, isHour ? metalRight : minuteRight);
        drawPath(canvas, isHour ? hourInlay : minuteInlay, isHour ? hourInlayPaint : minuteInlayPaint);
        canvas.restoreToCount(state);
    }

    private void drawSecond(Canvas canvas, float angle, float length) {
        int state = canvas.save();
        canvas.translate(PIVOT_X, PIVOT_Y);
        canvas.rotate(angle);
        canvas.drawLine(2, 76, 2, -length + 3, secondShade);
        canvas.drawLine(0, 75, 0, -length, secondPaint);
        canvas.drawCircle(1.7f, 62, 10, shadow);
        canvas.drawCircle(0, 60, 9.3f, darkGold);
        canvas.drawCircle(-1, 59, 8, fineGold);
        canvas.restoreToCount(state);
    }

    private void drawHub(Canvas canvas) {
        int state = canvas.save();
        canvas.translate(PIVOT_X, PIVOT_Y);
        canvas.drawCircle(4, 8, 47, shadow);
        canvas.drawCircle(0, 0, 46, hubPaint);
        canvas.drawCircle(0, 0, 43, machinedRing);
        canvas.drawCircle(0, 0, 38, darkBevel);
        canvas.drawCircle(0, 0, 37, lightBevel);
        canvas.drawCircle(0, 0, 31, darkGold);
        canvas.drawCircle(-1, -2, 28, capPaint);
        for (int r = 14; r < 28; r += 2) canvas.drawCircle(-1, -2, r, fineMachining);
        canvas.drawCircle(-1, -2, 28, machinedRing);
        canvas.drawCircle(0, 0, 10, fineGold);
        canvas.drawCircle(0, 0, 9, pinPaint);
        canvas.restoreToCount(state);
    }

    private void drawMechanism(Canvas canvas, double seconds) {
        int saved = canvas.save();
        canvas.translate(HEART_X, HEART_Y);
        canvas.clipPath(heartClip);
        movement.draw(canvas, seconds, smoothMotion);
        canvas.restoreToCount(saved);
    }

    private static Paint fill(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setColor(color);
        return paint;
    }

    private static Paint stroke(int color, float width) {
        Paint paint = fill(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        return paint;
    }

    private static Paint gradient(float x0, float y0, float x1, float y1, int[] colors, float[] stops) {
        Paint paint = fill(Color.WHITE);
        paint.setShader(new LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP));
        return paint;
    }

    private static Paint radial(float x, float y, float radius, int[] colors, float[] stops) {
        Paint paint = fill(Color.WHITE);
        paint.setShader(new RadialGradient(x, y, radius, colors, stops, Shader.TileMode.CLAMP));
        return paint;
    }

    private static Paint metalRing() {
        Paint paint = stroke(Color.WHITE, 2.2f);
        paint.setShader(new SweepGradient(0, 0,
                new int[]{0xFF78542D, 0xFFEFD4A8, 0xFF7D5C37, 0xFFFFE8C7, 0xFF9D7644, 0xFF78542D},
                new float[]{0, .19f, .47f, .66f, .86f, 1}));
        return paint;
    }

    private static Path polygon(float... points) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.close();
        return path;
    }
}
