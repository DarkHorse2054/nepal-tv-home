package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/** Decorative open movement, drawn in the dial's local 278-unit aperture. Render-thread owned. */
final class MechanicalClockMovement {
    private static final float RADIUS = 139f;
    private static final float BALANCE_X = -16f, BALANCE_Y = 47f;
    private final RectF layerBounds = new RectF(-RADIUS, -RADIUS, RADIUS, RADIUS);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = fill(0xB8000000);
    private final Paint softShadow = fill(0x58000000);
    private final Paint recess = radial(-38, -54, 190,
            new int[]{0xFF282C2B, 0xFF101615, 0xFF030706}, null);
    private final Paint plate = gradient(-100, -116, 103, 118,
            new int[]{0xFF41413A, 0xFF1F2826, 0xFF333631, 0xFF111A18},
            new float[]{0, .33f, .6f, 1});
    private final Paint pocket = radial(-18, -24, 100,
            new int[]{0xFF181B17, 0xFF050A09}, null);
    private final Paint perlageLight = stroke(0x235F7167, .5f);
    private final Paint perlageDark = stroke(0xA508100E, .5f);
    private final Paint brass = gradient(-50, -65, 60, 70,
            new int[]{0xFF71502D, 0xFFE1CCA1, 0xFFB49B6D, 0xFF645337},
            new float[]{0, .32f, .57f, 1});
    private final Paint steel = gradient(-120, -95, 115, 90,
            new int[]{0xFF555E59, 0xFFD9DED0, 0xFF909C91, 0xFFD2D7C7, 0xFF48544D},
            new float[]{0, .19f, .43f, .7f, 1});
    private final Paint steelDark = fill(0xFF343D37);
    private final Paint chamferDark = stroke(0xFF202720, 2.7f);
    private final Paint chamferLight = stroke(0xFFD3D9CA, .85f);
    private final Paint brush = stroke(0x355D685E, .25f);
    private final Paint brightBrush = stroke(0x32ECF0E1, .25f);
    private final Paint goldEdge = stroke(0xFFD9C399, .55f);
    private final Paint goldDarkEdge = stroke(0xFF5A4427, 1.25f);
    private final Paint machining = stroke(0x668D826A, .38f);
    private final Paint springPaint = stroke(0xFF879697, .47f);
    private final Paint springShadow = stroke(0xB0000504, .95f);
    private final Paint screw = gradient(-5, -6, 6, 7,
            new int[]{0xFF162B37, 0xFF75919E, 0xFF324F5D, 0xFF12272F},
            new float[]{0, .28f, .53f, 1});
    private final Paint slot = stroke(0xFF0D171B, 1.15f);
    private final Paint slotEdge = stroke(0xFFB1C0B8, .38f);
    private final Paint ruby = radial(-2, -2.7f, 7,
            new int[]{0xFFE8A4B1, 0xFF922D4D, 0xFF450E2C, 0xFF170B16},
            new float[]{0, .18f, .55f, 1});
    private final Paint rubyDark = fill(0xFF340B20);
    private final Paint rubyLight = fill(0xA6EAC6C4);
    private final Paint goldRing = sweepStroke(1.1f,
            new int[]{0xFF8C6C43, 0xFFF6E5C3, 0xFF665337, 0xFFE3CFAB, 0xFF8C6C43});
    private final Paint steelRing = sweepStroke(.7f,
            new int[]{0xFF515F57, 0xFFF2F2E2, 0xFF48564D, 0xFFD3DAC9, 0xFF515F57});
    private final Path perlage = new Path();
    private final Path trainBridge = new Path();
    private final Path balanceCock = new Path();
    private final Path escapeCock = new Path();
    private final Path lowerBridge = new Path();
    private final Path brushedLines = new Path();
    private final Path brushedHighlights = new Path();
    private final Path balanceRim = new Path();
    private final Path balanceSpokes = new Path();
    private final Path fork = new Path();
    private final Path[] springFrames = new Path[17];
    private final Gear[] train = new Gear[4];
    private final Matrix pixelMatrix = new Matrix();
    private final Matrix identity = new Matrix();
    private final Path pixelPath = new Path();
    private Bitmap backLayer, frontLayer;
    private int layerSize;

    MechanicalClockMovement() {
        // Shared module gives matching tooth spacing. Each adjacent pair counter-rotates by tooth count.
        train[0] = new Gear(-54, -58, 72, 6, .9f, 0);
        train[1] = new Gear(12.85f, -43.54f, 42, 5, -.9f * 72 / 42,
                meshPhase(train[0], 12.85f, -43.54f, 42));
        train[2] = new Gear(50.50f, -20.02f, 32, 5, .9f * 72 / 32,
                meshPhase(train[1], 50.50f, -20.02f, 32));
        train[3] = new Gear(69, 5.10f, 20, 5, -.9f * 72 / 20,
                meshPhase(train[2], 69, 5.10f, 20));
        createPlateDetails();
        createBridges();
        createBalance();
    }

    /** Optional preflight outside the Surface lock. Two small layers are reused at native pixel density. */
    void prepare(float pixelsPerDialUnit) {
        int size = Math.min(640, Math.max(278, (int) Math.ceil(278f * pixelsPerDialUnit)));
        size = (size + 1) & ~1;
        if (layerSize == size && backLayer != null && frontLayer != null) return;
        backLayer = frontLayer = null;
        layerSize = 0;
        Bitmap nextBack = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Bitmap nextFront = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        nextBack.setDensity(Bitmap.DENSITY_NONE);
        nextFront.setDensity(Bitmap.DENSITY_NONE);
        Canvas back = new Canvas(nextBack);
        Canvas front = new Canvas(nextFront);
        float scale = size / 278f;
        back.translate(size * .5f, size * .5f);
        back.scale(scale, scale);
        front.translate(size * .5f, size * .5f);
        front.scale(scale, scale);
        drawBackLayer(back);
        drawFrontLayer(front);
        backLayer = nextBack;
        frontLayer = nextFront;
        layerSize = size;
    }

    void releaseResources() {
        // Submitted hardware display lists may retain a layer; allow their references to expire safely.
        backLayer = frontLayer = null;
        layerSize = 0;
    }

    void draw(Canvas canvas, double seconds, boolean smooth) {
        if (backLayer == null || frontLayer == null) {
            throw new IllegalStateException("Prepare the movement before drawing into its surface");
        }
        canvas.drawBitmap(backLayer, null, layerBounds, bitmapPaint);
        double phase = smooth ? seconds : 0;
        for (Gear gear : train) gear.draw(canvas, phase);
        float beat = smooth ? (float) Math.sin(phase * Math.PI * 2.1) : 0;
        drawBalance(canvas, beat);
        drawEscapement(canvas, beat);
        canvas.drawBitmap(frontLayer, null, layerBounds, bitmapPaint);
    }

    private void drawBackLayer(Canvas canvas) {
        canvas.drawCircle(0, 0, RADIUS, recess);
        canvas.drawCircle(0, 0, 128, plate);
        canvas.drawCircle(0, 0, 130, goldDarkEdge);
        canvas.drawCircle(0, 0, 127.5f, steelRing);
        canvas.drawCircle(0, 0, 123.5f, machining);
        canvas.drawPath(perlage, perlageDark);
        canvas.save();
        canvas.translate(-.35f, -.5f);
        canvas.drawPath(perlage, perlageLight);
        canvas.restore();
        for (Gear gear : train) {
            canvas.drawCircle(gear.x + .5f, gear.y + 1, gear.pitch + 4, softShadow);
            canvas.drawCircle(gear.x, gear.y, gear.pitch + 2.4f, pocket);
            canvas.drawCircle(gear.x, gear.y, gear.pitch + 2.4f, machining);
        }
        canvas.drawCircle(BALANCE_X + 1, BALANCE_Y + 2, 66, softShadow);
        canvas.drawCircle(BALANCE_X, BALANCE_Y, 64.5f, pocket);
        canvas.drawCircle(BALANCE_X, BALANCE_Y, 64.5f, goldDarkEdge);
        for (int i = 0; i < 14; i++) {
            float a = (float) (i * Math.PI * 2 / 14);
            float x = (float) Math.cos(a) * 118, y = (float) Math.sin(a) * 118;
            canvas.drawCircle(x, y, 1.2f, steelDark);
            canvas.drawCircle(x - .25f, y - .25f, .65f, machining);
        }
    }

    private void drawFrontLayer(Canvas canvas) {
        drawBridge(canvas, trainBridge);
        drawBridge(canvas, balanceCock);
        drawBridge(canvas, escapeCock);
        drawBridge(canvas, lowerBridge);
        drawScrew(canvas, -101, -81, 5.1f, -24);
        drawScrew(canvas, 100, -77, 5.3f, 36);
        drawScrew(canvas, -108, 39, 5.7f, -31);
        drawScrew(canvas, 111, 44, 4.8f, 16);
        drawScrew(canvas, -63, 111, 4.1f, 53);
        drawScrew(canvas, 79, 103, 4.2f, -15);
        drawJewel(canvas, train[0].x, train[0].y, 8.2f);
        drawJewel(canvas, train[1].x, train[1].y, 6.7f);
        drawJewel(canvas, train[2].x, train[2].y, 5.8f);
        drawJewel(canvas, train[3].x, train[3].y, 4.8f);
        drawJewel(canvas, BALANCE_X, BALANCE_Y, 9.4f);
        // Small regulator and its fixed outer hairspring stud.
        canvas.drawArc(-60, 7, 5, 72, 211, 51, false, goldDarkEdge);
        canvas.drawArc(-60, 7, 5, 72, 211, 51, false, goldEdge);
        canvas.drawLine(-51, 21, -32, 33, chamferDark);
        canvas.drawLine(-51.5f, 20, -32.5f, 32, slotEdge);
        drawScrew(canvas, -49, 22, 2.3f, 21);
        canvas.drawCircle(-34, 32, 1.6f, brass);
        // A fine outline on the polished balance-cock shoulder shows its hand-finished angle.
        canvas.drawLine(-94, 30, -40, 42, slotEdge);
        canvas.drawLine(-89, 49, -43, 53, machining);
    }

    private void drawBridge(Canvas canvas, Path path) {
        canvas.save();
        canvas.translate(1.7f, 2.8f);
        canvas.drawPath(path, softShadow);
        canvas.translate(-.4f, -.6f);
        canvas.drawPath(path, shadow);
        canvas.restore();
        canvas.drawPath(path, chamferDark);
        canvas.drawPath(path, steel);
        canvas.save();
        canvas.clipPath(path);
        canvas.drawPath(brushedLines, brush);
        canvas.drawPath(brushedHighlights, brightBrush);
        canvas.restore();
        canvas.drawPath(path, chamferLight);
    }

    private void drawBalance(Canvas canvas, float beat) {
        int saved = canvas.save();
        canvas.translate(BALANCE_X, BALANCE_Y);
        canvas.save();
        canvas.translate(1.5f, 2.2f);
        canvas.rotate(beat * 27f);
        drawPath(canvas, balanceRim, shadow);
        drawPath(canvas, balanceSpokes, shadow);
        canvas.restore();
        canvas.save();
        canvas.rotate(beat * 27f);
        drawPath(canvas, balanceRim, brass);
        drawPath(canvas, balanceRim, goldEdge);
        drawPath(canvas, balanceSpokes, brass);
        drawPath(canvas, balanceSpokes, goldEdge);
        canvas.drawCircle(0, 0, 61.5f, goldDarkEdge);
        canvas.drawCircle(0, 0, 58.2f, goldRing);
        canvas.drawCircle(0, 0, 56.6f, machining);
        for (int i = 0; i < 12; i++) {
            canvas.save();
            canvas.rotate(i * 30f);
            canvas.drawCircle(0, -59.6f, 1.4f, steel);
            canvas.drawLine(-.8f, -59.9f, .8f, -59.3f, slot);
            canvas.restore();
        }
        canvas.drawCircle(0, 0, 8, brass);
        canvas.drawCircle(0, 0, 7.4f, goldRing);
        canvas.restore();
        Path hairspring = springFrames[Math.max(0, Math.min(16, Math.round((beat + 1) * 8)))];
        canvas.save();
        canvas.translate(.45f, .65f);
        drawPath(canvas, hairspring, springShadow);
        canvas.restore();
        drawPath(canvas, hairspring, springPaint);
        canvas.drawCircle(0, 0, 5, steelDark);
        canvas.drawCircle(0, 0, 4.3f, steelRing);
        canvas.restoreToCount(saved);
    }

    private void drawEscapement(Canvas canvas, float beat) {
        int saved = canvas.save();
        canvas.translate(48, 24);
        canvas.rotate(beat * 4f);
        canvas.save();
        canvas.translate(.8f, 1.2f);
        drawPath(canvas, fork, shadow);
        canvas.restore();
        drawPath(canvas, fork, steel);
        drawPath(canvas, fork, chamferLight);
        canvas.drawCircle(0, 0, 2.5f, brass);
        canvas.drawCircle(0, 0, 1.3f, rubyDark);
        canvas.drawRect(13, -11.5f, 17.5f, -9.1f, rubyDark);
        canvas.drawRect(20.2f, -5.8f, 23.8f, -3.7f, rubyDark);
        canvas.drawLine(13.4f, -11.4f, 17.2f, -11.4f, slotEdge);
        canvas.restoreToCount(saved);
    }

    private void drawScrew(Canvas canvas, float x, float y, float radius, float angle) {
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angle);
        canvas.drawCircle(.6f, .9f, radius + 1.4f, shadow);
        canvas.drawCircle(0, 0, radius + 1, steel);
        canvas.drawCircle(0, 0, radius + .7f, steelRing);
        canvas.drawCircle(0, 0, radius, screw);
        canvas.drawCircle(0, 0, radius - .4f, machining);
        canvas.drawLine(-radius + 1.1f, .2f, radius - 1.1f, .2f, slot);
        canvas.drawLine(-radius + 1.4f, -.5f, radius - 1.4f, -.5f, slotEdge);
        canvas.restore();
    }

    private void drawJewel(Canvas canvas, float x, float y, float radius) {
        canvas.save();
        canvas.translate(x, y);
        canvas.drawCircle(.6f, 1, radius + .8f, shadow);
        canvas.drawCircle(0, 0, radius, brass);
        canvas.drawCircle(0, 0, radius - .45f, goldRing);
        canvas.drawCircle(0, 0, radius - 1.8f, goldDarkEdge);
        canvas.drawCircle(0, 0, radius - 2.1f, ruby);
        canvas.drawCircle(-1.2f, -1.6f, Math.max(.65f, (radius - 2) * .23f), rubyLight);
        canvas.drawCircle(0, 0, .95f, rubyDark);
        canvas.drawCircle(.1f, -.25f, .45f, steel);
        canvas.restore();
    }

    private void createPlateDetails() {
        RectF arc = new RectF();
        for (int row = -7; row <= 7; row++) {
            for (int column = -8; column <= 8; column++) {
                float x = column * 15 + (row % 2 == 0 ? 0 : 7.5f), y = row * 14;
                if (x * x + y * y > 115 * 115) continue;
                for (int ring = 0; ring < 3; ring++) {
                    float r = 7.2f - ring * 1.3f;
                    arc.set(x - r, y - r, x + r, y + r);
                    perlage.addArc(arc, 199, 247);
                }
            }
        }
        for (int i = -180; i < 180; i++) {
            float y = i * .9f;
            brushedLines.moveTo(-160, y);
            brushedLines.lineTo(160, y + 33);
            if (i % 3 == 0) {
                brushedHighlights.moveTo(-160, y + .4f);
                brushedHighlights.lineTo(160, y + 33.4f);
            }
        }
    }

    private void createBridges() {
        trainBridge.moveTo(-110, -84);
        trainBridge.cubicTo(-91, -101, -72, -107, -58, -97);
        trainBridge.lineTo(-44, -77);
        trainBridge.quadTo(-36, -68, -23, -63);
        trainBridge.lineTo(-4, -56);
        trainBridge.quadTo(13, -70, 24, -54);
        trainBridge.lineTo(48, -35);
        trainBridge.quadTo(64, -40, 70, -24);
        trainBridge.lineTo(91, -43);
        trainBridge.lineTo(91, -90);
        trainBridge.quadTo(107, -99, 114, -83);
        trainBridge.lineTo(121, -48);
        trainBridge.quadTo(121, -28, 104, -18);
        trainBridge.lineTo(80, -9);
        trainBridge.quadTo(74, 0, 66, -7);
        trainBridge.lineTo(55, -10);
        trainBridge.quadTo(43, -5, 35, -17);
        trainBridge.lineTo(14, -27);
        trainBridge.quadTo(0, -27, -8, -38);
        trainBridge.lineTo(-35, -46);
        trainBridge.quadTo(-53, -42, -64, -54);
        trainBridge.lineTo(-84, -68);
        trainBridge.lineTo(-106, -66);
        trainBridge.close();
        balanceCock.moveTo(-118, 21);
        balanceCock.quadTo(-100, 13, -88, 22);
        balanceCock.lineTo(-37, 36);
        balanceCock.quadTo(-14, 25, 1, 43);
        balanceCock.lineTo(8, 47);
        balanceCock.quadTo(8, 66, -12, 69);
        balanceCock.lineTo(-83, 55);
        balanceCock.quadTo(-104, 68, -122, 55);
        balanceCock.close();
        escapeCock.moveTo(65, -4);
        escapeCock.quadTo(74, -9, 82, 1);
        escapeCock.lineTo(107, 31);
        escapeCock.quadTo(127, 33, 119, 52);
        escapeCock.quadTo(108, 64, 99, 49);
        escapeCock.lineTo(74, 17);
        escapeCock.quadTo(57, 16, 65, -4);
        escapeCock.close();
        lowerBridge.moveTo(-78, 109);
        lowerBridge.quadTo(-69, 102, -58, 105);
        lowerBridge.quadTo(5, 121, 70, 96);
        lowerBridge.quadTo(87, 89, 93, 101);
        lowerBridge.quadTo(22, 143, -74, 119);
        lowerBridge.close();
        fork.moveTo(-3, 0);
        fork.lineTo(-17, 12);
        fork.lineTo(-16, 15);
        fork.lineTo(-11, 14);
        fork.lineTo(-10, 17);
        fork.lineTo(-5, 12);
        fork.lineTo(2, 3);
        fork.lineTo(21, -1);
        fork.lineTo(24, -5);
        fork.lineTo(20, -7);
        fork.lineTo(16, -5);
        fork.lineTo(12, -9);
        fork.lineTo(16, -12);
        fork.lineTo(13, -15);
        fork.lineTo(6, -8);
        fork.close();
    }

    private void createBalance() {
        balanceRim.setFillType(Path.FillType.EVEN_ODD);
        balanceRim.addCircle(0, 0, 62, Path.Direction.CW);
        balanceRim.addCircle(0, 0, 56, Path.Direction.CW);
        Path spoke = new Path();
        spoke.moveTo(-3.5f, -7);
        spoke.cubicTo(-7, -22, -5, -42, -3, -57);
        spoke.lineTo(3, -57);
        spoke.cubicTo(1, -40, 1, -21, 4, -7);
        spoke.close();
        Matrix rotation = new Matrix();
        Path rotated = new Path();
        for (int i = 0; i < 3; i++) {
            rotation.setRotate(i * 120);
            spoke.transform(rotation, rotated);
            balanceSpokes.addPath(rotated);
        }
        for (int frame = 0; frame < springFrames.length; frame++) {
            Path spring = new Path();
            double displacement = (frame / 8.0 - 1) * Math.toRadians(27);
            for (int point = 0; point <= 440; point++) {
                double t = point / 440.0;
                // The outer stud stays fixed while the inner spring end follows the balance staff.
                double angle = t * Math.PI * 16.2 - 2.23 + displacement * (1 - t) * (1 - t);
                double radius = 7 + t * 42;
                float x = (float) (Math.cos(angle) * radius), y = (float) (Math.sin(angle) * radius);
                if (point == 0) spring.moveTo(x, y); else spring.lineTo(x, y);
            }
            springFrames[frame] = spring;
        }
    }

    private final class Gear {
        final float x, y, pitch, speed, phase;
        final int teeth;
        final Path rim = new Path();
        final Path spokes = new Path();
        final Path radialCuts = new Path();
        final Paint gold;

        Gear(float x, float y, int teeth, int spokeCount, float speed, float phase) {
            this.x = x; this.y = y; this.teeth = teeth; this.speed = speed; this.phase = phase;
            pitch = teeth * .6f;
            float root = pitch - 1.1f, tip = pitch + 1.25f, inner = Math.max(6, pitch - 4.8f);
            gold = gradient(-pitch, -pitch, pitch, pitch,
                    new int[]{0xFFE6D2AA, 0xFF8B7047, 0xFFD3BB8F, 0xFF594B31},
                    new float[]{0, .3f, .58f, 1});
            rim.setFillType(Path.FillType.EVEN_ODD);
            // Rounded-root, narrowed-tip tooth flanks rather than rectangular cog blocks.
            float[] toothRadius = {root, root, pitch - .35f, tip, tip, pitch - .35f, root, root};
            for (int i = 0; i < teeth * 8; i++) {
                double angle = (i / 8.0 - .5) * Math.PI * 2 / teeth;
                float r = toothRadius[i % 8];
                float px = (float) Math.cos(angle) * r, py = (float) Math.sin(angle) * r;
                if (i == 0) rim.moveTo(px, py); else rim.lineTo(px, py);
            }
            rim.close();
            rim.addCircle(0, 0, inner, Path.Direction.CW);
            Path oneSpoke = new Path();
            float half = pitch > 30 ? 2.4f : 1.55f;
            oneSpoke.moveTo(-half, -3);
            oneSpoke.cubicTo(-half - 1, -pitch * .4f, -half - 1.2f, -pitch * .62f, -half, -inner - .6f);
            oneSpoke.lineTo(half, -inner - .6f);
            oneSpoke.cubicTo(half - 1.2f, -pitch * .6f, half - .5f, -pitch * .38f, half, -3);
            oneSpoke.close();
            Matrix rotation = new Matrix();
            Path rotated = new Path();
            for (int i = 0; i < spokeCount; i++) {
                rotation.setRotate(i * 360f / spokeCount);
                oneSpoke.transform(rotation, rotated);
                spokes.addPath(rotated);
            }
            for (int i = 0; i < teeth; i++) {
                double angle = i * Math.PI * 2 / teeth;
                float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
                radialCuts.moveTo(dx * (inner + .7f), dy * (inner + .7f));
                radialCuts.lineTo(dx * (root - .7f), dy * (root - .7f));
            }
        }

        void draw(Canvas canvas, double seconds) {
            int saved = canvas.save();
            canvas.translate(x, y);
            canvas.rotate((float) ((seconds * speed + phase) % 360));
            canvas.save();
            canvas.translate(.9f, 1.5f);
            drawPath(canvas, rim, shadow);
            drawPath(canvas, spokes, shadow);
            canvas.restore();
            drawPath(canvas, rim, goldDarkEdge);
            drawPath(canvas, rim, gold);
            drawPath(canvas, rim, goldEdge);
            drawPath(canvas, spokes, gold);
            drawPath(canvas, spokes, goldEdge);
            drawPath(canvas, radialCuts, machining);
            canvas.drawCircle(0, 0, pitch - 2.2f, machining);
            canvas.drawCircle(0, 0, pitch - 3.2f, goldEdge);
            canvas.drawCircle(0, 0, 4.5f, gold);
            canvas.drawCircle(0, 0, 4.1f, goldRing);
            canvas.drawCircle(0, 0, 2, steelDark);
            canvas.restoreToCount(saved);
        }
    }

    private static float meshPhase(Gear previous, float nextX, float nextY, int nextTeeth) {
        double contact = Math.toDegrees(Math.atan2(nextY - previous.y, nextX - previous.x));
        return (float) (contact + 180 - (180 - (contact - previous.phase) * previous.teeth) / nextTeeth);
    }

    /** API 25 otherwise rasterizes a scaled path at its small logical size. */
    private void drawPath(Canvas canvas, Path path, Paint paint) {
        if (!canvas.isHardwareAccelerated()) {
            canvas.drawPath(path, paint);
            return;
        }
        canvas.getMatrix(pixelMatrix);
        path.transform(pixelMatrix, pixelPath);
        Shader shader = paint.getShader();
        float width = paint.getStrokeWidth();
        if (shader != null) shader.setLocalMatrix(pixelMatrix);
        paint.setStrokeWidth(width * pixelMatrix.mapRadius(1));
        int saved = canvas.save();
        canvas.setMatrix(identity);
        canvas.drawPath(pixelPath, paint);
        canvas.restoreToCount(saved);
        paint.setStrokeWidth(width);
        if (shader != null) shader.setLocalMatrix(identity);
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

    private static Paint sweepStroke(float width, int[] colors) {
        Paint paint = stroke(Color.WHITE, width);
        paint.setShader(new SweepGradient(0, 0, colors, null));
        return paint;
    }
}
