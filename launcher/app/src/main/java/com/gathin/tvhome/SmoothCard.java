package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public final class SmoothCard extends FrameLayout {
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final float density = getResources().getDisplayMetrics().density;
    private float focusedRadius;
    private boolean glassSurface;
    private final Path edge = new Path();
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SmoothCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        rim.setStyle(Paint.Style.STROKE);
    }

    void setFocusedRadius(float radius) {
        focusedRadius = radius;
        invalidate();
    }

    void setGlassSurface(boolean enabled) {
        if (glassSurface == enabled) return;
        glassSurface = enabled;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h <= 0) return;
        sheen.setShader(new LinearGradient(0, 0, 0, h,
                0x18FFFFFF, 0x0CFFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(PreviewGlass.edgeSheen(0, h));
    }

    @Override public void draw(Canvas canvas) {
        float scale = getScaleX();
        float focus = focusedRadius > 0
                ? Math.max(0f, Math.min(1f, (scale - 1f) / (AppAdapter.ACTIVE_SCALE - 1f)))
                : (isFocused() ? 1f : 0f);
        float radius = AppAdapter.cornerRadius(getWidth(), density);
        if (focusedRadius > 0) radius += (focusedRadius - radius) * focus;
        bounds.set(0, 0, getWidth(), getHeight());
        clip.rewind();
        SmoothCorners.add(clip, bounds, radius);
        int save = canvas.save();
        canvas.clipPath(clip);
        super.draw(canvas);
        if (glassSurface) canvas.drawPath(clip, sheen);
        canvas.restoreToCount(save);
        float inset = .5f * density / scale;
        bounds.inset(inset, inset);
        edge.rewind();
        SmoothCorners.add(edge, bounds, radius - inset);
        rim.setStrokeWidth(.75f * density / scale);
        // Use the same progress as the zoom, including when focus reverses midway.
        rim.setAlpha(PreviewGlass.edgeAlpha(focus));
        canvas.drawPath(edge, rim);
    }
}
