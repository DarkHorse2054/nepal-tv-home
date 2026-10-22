package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Wallpaper-backed frosted shelf. Its resting bounds never follow icon magnification. */
public class DockDecoration extends RecyclerView.ItemDecoration {
    static final int TOP_INSET_DP = 14;
    static final int BOTTOM_INSET_DP = 34;
    static final int SIDE_GUTTER_DP = 8;
    private final int dockSize;
    private final float density;
    private final NepalBackdrop backdrop;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF shadowRect = new RectF();
    private final RectF innerRect = new RectF();
    private final RectF reflectionRect = new RectF();
    private final Path clip = new Path();
    private final Matrix placement = new Matrix();
    private Shader bandShader;
    private Shader edgeShader;
    private Shader reflectionShader;
    private float shaderWidth = -1;
    private float shaderHeight = -1;
    private int accent = Color.WHITE;

    DockDecoration(int dockSize, float density, NepalBackdrop backdrop) {
        this.dockSize = dockSize;
        this.density = density;
        this.backdrop = backdrop;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density * .8f);
        innerEdge.setStyle(Paint.Style.STROKE);
        innerEdge.setStrokeWidth(density * .6f);
        innerEdge.setColor(0x24FFFFFF);
        fallback.setColor(0xFF111C2A);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        outRect.set(0, 0, 0, 0);
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = Math.round(TOP_INSET_DP * density);
            outRect.bottom = Math.round(BOTTOM_INSET_DP * density);
        }
    }

    void setAccentColor(int color) {
        if (accent == color) return;
        accent = color;
        shaderWidth = -1;
    }

    private void prepareShaders() {
        if (shaderWidth != rect.width() || shaderHeight != rect.height()) {
            shaderWidth = rect.width();
            shaderHeight = rect.height();
            // Neutral glass stays the same while focus moves between app brands.
            bandShader = new LinearGradient(0, 0, 0, shaderHeight,
                    new int[]{0x401E3042, 0x50142030, 0x95121C29},
                    new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP);
            int glint = Color.rgb((Color.red(accent) + 3 * 255) / 4,
                    (Color.green(accent) + 3 * 255) / 4, (Color.blue(accent) + 3 * 255) / 4);
            edgeShader = new LinearGradient(0, 0, shaderWidth, shaderHeight,
                    new int[]{0x85000000 | (glint & 0xFFFFFF), 0x18FFFFFF, 0x46FFFFFF},
                    new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP);
            reflectionShader = new LinearGradient(0, 0, 0, shaderHeight * .58f,
                    0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            band.setShader(bandShader);
            edge.setShader(edgeShader);
            reflection.setShader(reflectionShader);
        }
        placement.setTranslate(rect.left, rect.top);
        bandShader.setLocalMatrix(placement);
        edgeShader.setLocalMatrix(placement);
        reflectionShader.setLocalMatrix(placement);
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float left = child.getLeft() + card.getLeft();
            float top = child.getTop() + card.getTop() + child.getTranslationY();
            float right = left + card.getWidth();
            float bottom = top + card.getHeight();
            if (!any) { rect.set(left, top, right, bottom); any = true; }
            else rect.union(left, top, right, bottom);
        }
        if (!any) return;
        rect.inset(-14 * density, -13 * density);
        float radius = 30 * density;
        prepareShaders();

        // Bounded soft shadow layers avoid a live blur or software-rendered shadow on this TV.
        for (int layer = 3; layer >= 1; layer--) {
            shadowRect.set(rect);
            float spread = layer * 1.5f * density;
            shadowRect.inset(-spread, -spread);
            shadowRect.offset(0, 3.5f * density);
            shadow.setColor((layer == 1 ? 0x20 : layer == 2 ? 0x10 : 0x08) << 24);
            canvas.drawRoundRect(shadowRect, radius + spread, radius + spread, shadow);
        }
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (!backdrop.drawFrostedScene(canvas, parent)) canvas.drawRect(rect, fallback);
        canvas.drawRect(rect, band);
        reflectionRect.set(rect.left - rect.width() * .10f, rect.top - rect.height() * .80f,
                rect.right + rect.width() * .20f, rect.top + rect.height() * .56f);
        canvas.drawOval(reflectionRect, reflection);
        canvas.restore();
        canvas.drawRoundRect(rect, radius, radius, edge);
        innerRect.set(rect);
        innerRect.inset(1.4f * density, 1.4f * density);
        canvas.drawRoundRect(innerRect, radius - 1.4f * density, radius - 1.4f * density, innerEdge);
    }
}
