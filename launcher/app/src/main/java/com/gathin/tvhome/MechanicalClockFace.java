package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

import java.util.Random;

/**
 * Resolution-independent fixed metalwork of the mechanical clock.
 *
 * Coordinates describe the approved 1672 by 941 composition, not a raster image. The caller
 * scales the Canvas to its actual output size and may cache this static face at that size.
 * Paths, material shaders and deterministic brushed-metal grain are constructed once. Drawing
 * performs no bitmap decoding, path construction, text layout or other per-frame allocation.
 */
final class MechanicalClockFace {
    static final float WIDTH = MechanicalDialGeometry.WIDTH;
    static final float HEIGHT = MechanicalDialGeometry.HEIGHT;
    private static final float HEART_X = 835f;
    private static final float HEART_Y = 659f;

    private final RectF caseBounds = new RectF(0, 0, WIDTH, HEIGHT);
    private final RectF dialBounds = new RectF(18, 18, 1654, 911);
    private final RectF innerPanel = new RectF(65, 57, 1608, 866);
    private final RectF bezelBounds = new RectF(13, 13, 1659, 918);
    private final RectF bezelOuter = new RectF(8, 8, 1664, 923);
    private final RectF bezelInside = new RectF(18, 18, 1654, 913);
    private final Path dialClip = new Path();
    private final Path caseClip = new Path();
    private final Path minuteStrokes = new Path();
    private final Path minuteHighlights = new Path();
    private final Path minuteShadows = new Path();
    private final Path[] grain = new Path[8];
    private final Paint[] grainPaint = new Paint[8];
    private final Path caseGrain = new Path();
    private final HourIndex[] indices = new HourIndex[13];

    private final Paint caseMetal = gradient(0, 0, 1672, 941,
            new int[]{0xFF383A39, 0xFF171A1A, 0xFF0D0F10, 0xFF242728, 0xFF101213},
            new float[]{0, .25f, .60f, .89f, 1});
    private final Paint caseHighlight = stroke(0xFF4D4E4B, .7f);
    private final Paint caseGrainPaint = stroke(0x134D5353, .24f);
    private final Paint dialMetal = gradient(0, 0, 0, 941,
            new int[]{0xFF252626, 0xFF1C1E1E, 0xFF141717, 0xFF0B0E0E, 0xFF080A0A},
            new float[]{0, .22f, .51f, .84f, 1});
    private final Paint upperReflection = radial(253, 68, 738,
            new int[]{0x4AACA08A, 0x157E7C72, 0x006A6C64}, new float[]{0, .42f, 1});
    private final Paint centreReflection = radial(1240, 577, 610,
            new int[]{0x126B7470, 0x006B7470}, null);
    private final Paint leftShade = gradient(18, 0, 123, 0,
            new int[]{0xD5000000, 0x28000000, 0x00000000}, new float[]{0, .38f, 1});
    private final Paint rightShade = gradient(1469, 0, 1654, 0,
            new int[]{0x00000000, 0x30000000, 0xE4000000}, new float[]{0, .66f, 1});
    private final Paint topShade = gradient(0, 18, 0, 81,
            new int[]{0xF0000000, 0x58000000, 0x00000000}, new float[]{0, .34f, 1});
    private final Paint bottomShade = gradient(0, 802, 0, 913,
            new int[]{0x00000000, 0x3B000000, 0xF3000000}, new float[]{0, .64f, 1});
    private final Paint panelEdge = stroke(0xA0040606, .75f);
    private final Paint panelHighlight = stroke(0x126E716A, .65f);
    private final Paint bezelShadow = stroke(0xE0000000, 13f);
    private final Paint bezelMetal = stroke(Color.WHITE, 6.4f);
    private final Paint bezelBrightEdge = stroke(0xFFD8B489, .9f);
    private final Paint bezelDarkEdge = stroke(0xFF36271A, 1.3f);
    private final Paint bezelHairline = stroke(0xFF81715B, .65f);
    private final Paint tickPaint = gradient(0, 0, 0, 941,
            new int[]{0xFFBB9B73, 0xFFD3AE7E, 0xFFB69266}, null);
    private final Paint tickHighlight = stroke(0xB5E8CAA2, .48f);
    private final Paint tickShadow = stroke(0xB0000000, 2.4f);
    private final Paint heartShadow = fill(0xE6000000);
    private final Paint heartRim = fill(Color.WHITE);
    private final Paint heartFineRim = stroke(0xFFD8B38B, .7f);
    private final Paint heartOuterLine = stroke(0xFF34251A, 1f);
    private final Paint heartMachining = stroke(0x795E4832, .4f);
    private final Paint heartRecess = radial(HEART_X - 28, HEART_Y - 32, 162,
            new int[]{0xFF252725, 0xFF090B0B, 0xFF010202}, new float[]{0, .77f, 1});
    private final Paint heartWell = fill(0xFF030505);
    private final Aperture digital = new Aperture(717, 177, 955, 244, 14, 13);
    private final Aperture date = new Aperture(1374, 412, 1445, 463, 10, 13);

    MechanicalClockFace() {
        dialClip.addRoundRect(dialBounds, 17, 17, Path.Direction.CW);
        caseClip.addRoundRect(caseBounds, 23, 23, Path.Direction.CW);
        bezelMetal.setShader(new SweepGradient(835, 465,
                new int[]{0xFF88613D, 0xFFEAC79B, 0xFFB98F62, 0xFFE8C39A,
                        0xFFA47A50, 0xFFF5D9B5, 0xFF9D7144, 0xFF88613D},
                new float[]{0, .13f, .25f, .41f, .53f, .67f, .85f, 1}));
        heartRim.setShader(new SweepGradient(HEART_X, HEART_Y,
                new int[]{0xFF8F633C, 0xFFF5D1A7, 0xFFAC7C4E, 0xFFF6D6B1,
                        0xFF82562F, 0xFFE1BA8C, 0xFF4E311B, 0xFF8F633C},
                new float[]{0, .13f, .27f, .42f, .56f, .68f, .87f, 1}));
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(1.55f);
        createGrain();
        createIndices();
    }

    /** Draws the complete, opaque face in model coordinates. */
    void draw(Canvas canvas) {
        canvas.drawColor(Color.rgb(5, 7, 7));
        canvas.drawRoundRect(caseBounds, 23, 23, caseMetal);
        int saved = canvas.save();
        canvas.clipPath(caseClip);
        canvas.drawPath(caseGrain, caseGrainPaint);
        canvas.restoreToCount(saved);
        canvas.drawRoundRect(caseBounds, 23, 23, caseHighlight);

        canvas.drawRoundRect(dialBounds, 17, 17, dialMetal);
        saved = canvas.save();
        canvas.clipPath(dialClip);
        canvas.drawRect(dialBounds, upperReflection);
        canvas.drawRect(dialBounds, centreReflection);
        for (int i = 0; i < grain.length; i++) canvas.drawPath(grain[i], grainPaint[i]);
        canvas.drawRect(dialBounds, leftShade);
        canvas.drawRect(dialBounds, rightShade);
        canvas.drawRect(dialBounds, topShade);
        canvas.drawRect(dialBounds, bottomShade);
        canvas.drawRoundRect(innerPanel, 5, 5, panelHighlight);
        canvas.translate(.55f, .65f);
        canvas.drawRoundRect(innerPanel, 5, 5, panelEdge);
        canvas.restoreToCount(saved);

        canvas.drawRoundRect(bezelBounds, 23, 23, bezelShadow);
        canvas.drawRoundRect(bezelBounds, 23, 23, bezelMetal);
        canvas.drawRoundRect(bezelOuter, 28, 28, bezelHairline);
        canvas.drawRoundRect(bezelInside, 18, 18, bezelDarkEdge);
        canvas.drawRoundRect(bezelBounds, 23, 23, bezelBrightEdge);
        canvas.drawPath(minuteShadows, tickShadow);
        canvas.drawPath(minuteStrokes, tickPaint);
        canvas.drawPath(minuteHighlights, tickHighlight);
        for (HourIndex index : indices) index.draw(canvas);
        digital.draw(canvas);
        date.draw(canvas);
        drawHeartRim(canvas);
    }

    private void drawHeartRim(Canvas canvas) {
        canvas.drawCircle(HEART_X + 1.5f, HEART_Y + 4, 157.5f, heartShadow);
        canvas.drawCircle(HEART_X, HEART_Y, 155.4f, heartRim);
        canvas.drawCircle(HEART_X, HEART_Y, 155.3f, heartOuterLine);
        canvas.drawCircle(HEART_X, HEART_Y, 153.3f, heartFineRim);
        canvas.drawCircle(HEART_X, HEART_Y, 152.1f, heartMachining);
        canvas.drawCircle(HEART_X, HEART_Y, 150.8f, heartMachining);
        canvas.drawCircle(HEART_X, HEART_Y, 148.2f, heartRecess);
        canvas.drawCircle(HEART_X, HEART_Y, 147.7f, heartOuterLine);
        canvas.drawCircle(HEART_X, HEART_Y, 140.4f, heartWell);
        canvas.drawCircle(HEART_X, HEART_Y, 139.4f, heartOuterLine);
    }

    private void createGrain() {
        Random random = new Random(0x434c4f434bL);
        for (int i = 0; i < grain.length; i++) {
            grain[i] = new Path();
            int alpha = 3 + i;
            // Interleaved light and dark scratches retain the fine, brushed-metal finish at 4K.
            grainPaint[i] = stroke((alpha << 24) | ((i & 1) == 0 ? 0xD7D8CD : 0x010404),
                    .13f + (i % 3) * .075f);
        }
        for (int i = 0; i < 14400; i++) {
            float x = 17 + random.nextFloat() * 1639;
            float y = 18 + random.nextFloat() * 895;
            float length = 10 + random.nextFloat() * 225;
            Path path = grain[i % grain.length];
            path.moveTo(x, y);
            path.lineTo(Math.min(1655, x + length), y + (random.nextFloat() - .5f) * .18f);
        }
        for (int i = 0; i < 1400; i++) {
            float y = random.nextFloat() * HEIGHT;
            float x = random.nextFloat() * WIDTH;
            caseGrain.moveTo(x, y);
            caseGrain.lineTo(Math.min(WIDTH, x + 35 + random.nextFloat() * 220), y);
        }
    }

    private void createIndices() {
        int hourIndex = 0;
        for (int minute = 0; minute < MechanicalDialGeometry.MINUTE_COUNT; minute++) {
            float angle = MechanicalDialGeometry.minuteDegrees(minute);
            float directionX = MechanicalDialGeometry.directionX(angle);
            float directionY = MechanicalDialGeometry.directionY(angle);
            float x = MechanicalDialGeometry.trackX(angle);
            float y = MechanicalDialGeometry.trackY(angle);
            float tickLength = MechanicalDialGeometry.tickLength(minute);
            float innerX = x - directionX * tickLength;
            float innerY = y - directionY * tickLength;
            // All sixty marks remain visible, including the exact central mark above twin XII.
            minuteStrokes.moveTo(x, y);
            minuteStrokes.lineTo(innerX, innerY);
            minuteShadows.moveTo(x + .8f, y + 1.1f);
            minuteShadows.lineTo(innerX + .8f, innerY + 1.1f);
            minuteHighlights.moveTo(x - .3f, y - .35f);
            minuteHighlights.lineTo(innerX - .3f, innerY - .35f);
            if (minute % 5 == 0) {
                int hour = minute / 5;
                x = MechanicalDialGeometry.hourIndexX(hour);
                y = MechanicalDialGeometry.hourIndexY(hour);
                if (minute == 0) {
                    indices[hourIndex++] = new HourIndex(x - 16, y, angle, 111, 22);
                    indices[hourIndex++] = new HourIndex(x + 16, y, angle, 111, 22);
                } else {
                    // Preserve clearance between the VI bar and the mechanism's outer rim.
                    float length = minute == 15 || minute == 45 ? 148
                            : minute == 30 ? 75 - MechanicalDialGeometry.HOUR_INDEX_INSET : 126;
                    float width = minute == 15 || minute == 45 ? 34 : minute == 30 ? 29 : 23;
                    indices[hourIndex++] = new HourIndex(x, y, angle, length, width);
                }
            }
        }
    }

    /** Raised, four-facet champagne index; local y runs from its outer tip into the dial. */
    private static final class HourIndex {
        private final float x, y, angle;
        private final Path silhouette, top, left, right, end, machining;
        private final Paint shadowWide = stroke(0x2D000000, 9);
        private final Paint shadowNear = fill(0xC9000000);
        private final Paint topMetal;
        private final Paint leftMetal;
        private final Paint rightMetal;
        private final Paint endMetal;
        private final Paint brightEdge = stroke(0xFFF4DCC0, .62f);
        private final Paint darkEdge = stroke(0xFF45301E, .62f);
        private final Paint etching = stroke(0x1BC9A476, .18f);

        HourIndex(float x, float y, float angle, float length, float width) {
            this.x = x;
            this.y = y;
            this.angle = angle;
            float half = width * .5f;
            float bevel = Math.max(3.1f, width * .19f);
            silhouette = polygon(-half, 0, half, 0, half, length, -half, length);
            top = polygon(-half + bevel, bevel, half - bevel, bevel,
                    half - bevel, length - bevel, -half + bevel, length - bevel);
            left = polygon(-half, 0, -half + bevel, bevel,
                    -half + bevel, length - bevel, -half, length);
            right = polygon(half - bevel, bevel, half, 0, half, length, half - bevel, length - bevel);
            end = polygon(-half, length, -half + bevel, length - bevel,
                    half - bevel, length - bevel, half, length);
            topMetal = gradient(-half, 0, half, length,
                    new int[]{0xFFF9E4CA, 0xFFE4C199, 0xFFD4AB7C, 0xFFEDCCA5},
                    new float[]{0, .2f, .72f, 1});
            leftMetal = gradient(-half, 0, half, length,
                    new int[]{0xFFFFF1DB, 0xFFF2D5AE, 0xFFBE9467}, null);
            rightMetal = gradient(half, 0, -half, length,
                    new int[]{0xFFB18455, 0xFF73502C, 0xFF997049}, null);
            endMetal = gradient(0, length - bevel, 0, length,
                    new int[]{0xFFBD966C, 0xFF6A4829}, null);
            machining = new Path();
            for (float line = -half + bevel + .9f; line < half - bevel; line += .85f) {
                machining.moveTo(line, bevel + 1);
                machining.lineTo(line, length - bevel - 1);
            }
        }

        void draw(Canvas canvas) {
            int saved = canvas.save();
            canvas.translate(x + 2, y + 5);
            canvas.rotate(angle);
            canvas.drawPath(silhouette, shadowWide);
            canvas.drawPath(silhouette, shadowNear);
            canvas.restoreToCount(saved);
            saved = canvas.save();
            canvas.translate(x, y);
            canvas.rotate(angle);
            canvas.drawPath(silhouette, leftMetal);
            canvas.drawPath(top, topMetal);
            canvas.drawPath(left, leftMetal);
            canvas.drawPath(right, rightMetal);
            canvas.drawPath(end, endMetal);
            canvas.drawPath(left, brightEdge);
            canvas.drawPath(right, darkEdge);
            canvas.drawPath(machining, etching);
            canvas.restoreToCount(saved);
        }
    }

    /** Deep black aperture with a thin, polished four-sided inset frame. */
    private static final class Aperture {
        private final RectF outer, outerShadow, inner, recess;
        private final Path left, top, right, bottom;
        private final Paint shadow = fill(0xD9000000);
        private final Paint frame = fill(0xFF967047);
        private final Paint leftMetal, topMetal, rightMetal, bottomMetal, well;
        private final Paint frameHighlight = stroke(0xFFDABC96, .7f);
        private final Paint outerEdge = stroke(0xFF130E09, 1.1f);
        private final Paint insetEdge = stroke(0xFF302A20, .65f);

        Aperture(float l, float t, float r, float b, float side, float vertical) {
            inner = new RectF(l, t, r, b);
            outer = new RectF(l - side, t - vertical, r + side, b + vertical);
            outerShadow = new RectF(outer.left - 2, outer.top - 1, outer.right + 2.5f, outer.bottom + 3.5f);
            recess = new RectF(l - 4, t - 4, r + 4, b + 4);
            float edge = 4;
            left = polygon(outer.left, outer.top, outer.left + edge, outer.top + edge,
                    outer.left + edge, outer.bottom - edge, outer.left, outer.bottom);
            top = polygon(outer.left, outer.top, outer.right, outer.top,
                    outer.right - edge, outer.top + edge, outer.left + edge, outer.top + edge);
            right = polygon(outer.right, outer.top, outer.right, outer.bottom,
                    outer.right - edge, outer.bottom - edge, outer.right - edge, outer.top + edge);
            bottom = polygon(outer.left, outer.bottom, outer.left + edge, outer.bottom - edge,
                    outer.right - edge, outer.bottom - edge, outer.right, outer.bottom);
            leftMetal = gradient(outer.left, 0, outer.left + edge, 0,
                    new int[]{0xFFF3D3AE, 0xFFB78C5C, 0xFF5E3B20}, null);
            topMetal = gradient(0, outer.top, 0, outer.top + edge,
                    new int[]{0xFFD5AD7E, 0xFFBA8C58, 0xFF644122}, null);
            rightMetal = gradient(outer.right - edge, 0, outer.right, 0,
                    new int[]{0xFF57371D, 0xFFA37548, 0xFFE1BE94}, null);
            bottomMetal = gradient(0, outer.bottom - edge, 0, outer.bottom,
                    new int[]{0xFF6E492A, 0xFFF1CDA4, 0xFFC79C6D}, null);
            well = gradient(l, t, r, b,
                    new int[]{0xFF030504, 0xFF080B0A, 0xFF040605}, null);
        }

        void draw(Canvas canvas) {
            canvas.drawRoundRect(outerShadow, 3, 3, shadow);
            canvas.drawRoundRect(outer, 2, 2, frame);
            canvas.drawRect(recess, shadow);
            canvas.drawPath(top, topMetal);
            canvas.drawPath(left, leftMetal);
            canvas.drawPath(right, rightMetal);
            canvas.drawPath(bottom, bottomMetal);
            canvas.drawRoundRect(outer, 2, 2, frameHighlight);
            canvas.drawRoundRect(outerShadow, 3, 3, outerEdge);
            canvas.drawRoundRect(inner, 1.2f, 1.2f, well);
            canvas.drawRoundRect(inner, 1.2f, 1.2f, insetEdge);
        }
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

    private static Path polygon(float... points) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.close();
        return path;
    }
}
