package com.gathin.tvhome;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import androidx.annotation.RequiresApi;
import android.widget.ImageView;

/** Glass surfaces that refract the launcher's ambient backdrop at the rim. */
final class PreviewGlass {
    static final int ROUND_BUTTON_DP = 40;
    static final int FOCUSED_ROUND_BUTTON_DP = 48;

    private final View backdrop;
    private final ImageView wallpaper;
    private final int background;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path rim = new Path();
    private final DockBackdrop dockBackdrop = new DockBackdrop();
    private final Path surface = new Path();
    private final Path edge = new Path();
    private final WallpaperBlur buttonBlur;
    private final WallpaperBlur dockBlur;
    private final RectF inner = new RectF();
    private final int[] hostLocation = new int[2], backdropLocation = new int[2];
    private final float density;

    PreviewGlass(View backdrop, ImageView wallpaper, int background, float density) {
        this.backdrop = backdrop;
        this.wallpaper = wallpaper;
        this.background = background;
        this.density = density;
        buttonBlur = Build.VERSION.SDK_INT >= 31 ? new WallpaperBlur(5 * density) : null;
        dockBlur = Build.VERSION.SDK_INT >= 31 ? new WallpaperBlur(density) : null;
    }

    static Drawable panel(float density, View host, View source) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path outline = new Path();
            private final RectF rect = new RectF();
            private final PanelBlur blur = Build.VERSION.SDK_INT >= 31 ? new PanelBlur(source, 20 * density) : null;

            @Override public void draw(Canvas canvas) {
                rect.set(getBounds());
                outline.rewind();
                SmoothCorners.add(outline, rect, 20 * density);
                if (Build.VERSION.SDK_INT >= 31 && blur != null && canvas.isHardwareAccelerated()) {
                    int save = canvas.save();
                    canvas.clipPath(outline);
                    blur.draw(canvas, host);
                    canvas.restoreToCount(save);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                        0x18FFFFFF, 0x0CFFFFFF, Shader.TileMode.CLAMP));
                canvas.drawPath(outline, paint);
                rect.inset(.5f * density, .5f * density);
                outline.rewind();
                SmoothCorners.add(outline, rect, 19.5f * density);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(.75f * density);
                paint.setShader(edgeSheen(getBounds().top, getBounds().bottom));
                canvas.drawPath(outline, paint);
            }

            @Override public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
                invalidateSelf();
            }
            @Override public void setColorFilter(ColorFilter filter) {
                paint.setColorFilter(filter);
                invalidateSelf();
            }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    Drawable button(View button) {
        RectF bounds = new RectF();
        return new Drawable() {
            @Override public void draw(Canvas canvas) {
                bounds.set(getBounds());
                drawGlass(canvas, button, bounds, bounds.height() / 2f, button.isFocused(), false);
            }
            @Override public ConstantState getConstantState() {
                return new ConstantState() {
                    @Override public Drawable newDrawable() { return PreviewGlass.this.button(button); }
                    @Override public int getChangingConfigurations() { return 0; }
                };
            }
            @Override public boolean isStateful() { return true; }
            @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter filter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    void drawGlass(Canvas canvas, View host, RectF bounds, float radius, boolean focused, boolean dock) {
        boolean roundButton = !dock && bounds.width() == bounds.height();
        float depth = (dock ? 3 : 2) * density;
        inner.set(bounds);
        inner.inset(depth, depth);
        rim.rewind();
        rim.setFillType(Path.FillType.EVEN_ODD);
        if (dock) {
            SmoothCorners.add(rim, bounds, radius);
            SmoothCorners.add(rim, inner, radius - depth);
        } else {
            rim.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            rim.addRoundRect(inner, radius - depth, radius - depth, Path.Direction.CW);
        }
        surface.rewind();
        if (dock) SmoothCorners.add(surface, bounds, radius);
        else surface.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        Drawable glow = backdrop.getBackground();
        float backdropAlpha = backdrop.getAlpha();
        boolean hasWallpaper = wallpaper.getParent() != null && wallpaper.getVisibility() == View.VISIBLE
                && wallpaper.getDrawable() != null;
        if (Build.VERSION.SDK_INT >= 31 && hasWallpaper && canvas.isHardwareAccelerated()) {
            host.getLocationInWindow(hostLocation);
            wallpaper.getLocationInWindow(backdropLocation);
            canvas.save();
            canvas.clipPath(surface);
            int offsetX = backdropLocation[0] - hostLocation[0];
            int offsetY = backdropLocation[1] - hostLocation[1];
            if (!dock || !dockBackdrop.draw(canvas, host, wallpaper, bounds, radius,
                    20 * density, 12 * density, offsetX, offsetY)) {
                canvas.translate(offsetX, offsetY);
                (dock ? dockBlur : buttonBlur).draw(canvas, wallpaper);
            }
            canvas.restore();
        }
        canvas.save();
        canvas.clipPath(rim);
        if ((!dock || !hasWallpaper) && (hasWallpaper || (glow != null && backdropAlpha > 0f))) {
            host.getLocationInWindow(hostLocation);
            // Keep the rim mask fixed while increasing the backdrop displacement on the dock.
            float displacement = dock ? 6 * density : depth;
            canvas.scale(1f - displacement / bounds.width(), 1f - displacement / bounds.height(),
                    bounds.centerX(), bounds.centerY());
            if (hasWallpaper) {
                wallpaper.getLocationInWindow(backdropLocation);
                canvas.save();
                canvas.translate(backdropLocation[0] - hostLocation[0], backdropLocation[1] - hostLocation[1]);
                if (dock && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                    dockBlur.draw(canvas, wallpaper);
                } else {
                    wallpaper.draw(canvas);
                }
                canvas.restore();
            } else {
                canvas.drawColor(background);
            }
            if (glow != null && backdropAlpha > 0f) {
                if (backdropAlpha < 1f) {
                    canvas.saveLayerAlpha(bounds.left - depth, bounds.top - depth,
                            bounds.right + depth, bounds.bottom + depth, Math.round(backdropAlpha * 255));
                } else {
                    canvas.save();
                }
                backdrop.getLocationInWindow(backdropLocation);
                canvas.translate(backdropLocation[0] - hostLocation[0], backdropLocation[1] - hostLocation[1]);
                glow.draw(canvas);
                canvas.restore();
            }
        }
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
        if (roundButton) {
            int edgeFill = focused ? 0x33FFFFFF : 0x0FFFFFFF;
            int centerFill = focused ? 0x1FFFFFFF : 0x08FFFFFF;
            paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                    new int[]{edgeFill, centerFill, edgeFill}, null, Shader.TileMode.CLAMP));
        } else {
            paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                    focused ? 0x42FFFFFF : dock ? 0x18FFFFFF : 0x2EFFFFFF,
                    focused ? 0x2EFFFFFF : dock ? 0x0CFFFFFF : 0x1AFFFFFF, Shader.TileMode.CLAMP));
        }
        canvas.drawPath(surface, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * .75f);
        inner.set(bounds);
        inner.inset(density * .5f, density * .5f);
        float edgeRadius = radius - density * .5f;
        if (dock) {
            paint.setShader(edgeSheen(bounds.top, bounds.bottom));
            edge.rewind();
            SmoothCorners.add(edge, inner, edgeRadius);
            canvas.drawPath(edge, paint);
        } else if (roundButton) {
            float scale = host.getScaleX();
            inner.set(bounds);
            inner.inset(.5f * density / scale, .5f * density / scale);
            paint.setStrokeWidth(.75f * density / scale);
            paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                    new int[]{0x66FFFFFF, 0x1AFFFFFF, 0x1AFFFFFF, 0x1FFFFFFF},
                    new float[]{0f, .28f, .72f, 1f}, Shader.TileMode.CLAMP));
            float focusScale = Math.round(FOCUSED_ROUND_BUTTON_DP * density) / bounds.width();
            float focus = Math.max(0f, Math.min(1f, (scale - 1f) / (focusScale - 1f)));
            paint.setAlpha(Math.round(edgeAlpha(1f) * (.8f + .4f * focus)));
            float roundRadius = inner.height() / 2f;
            canvas.drawRoundRect(inner, roundRadius, roundRadius, paint);
            paint.setAlpha(255);
        } else {
            int bright = focused ? 0x32FFFFFF : 0x15FFFFFF;
            float highlightRadius = 23 * density;
            for (int side = 0; side < 2; side++) {
                float x = bounds.centerX() + (side == 0 ? -.25f : .25f) * bounds.width();
                float y = side == 0 ? bounds.top : bounds.bottom;
                paint.setShader(new RadialGradient(x, y, highlightRadius, bright, 0x00FFFFFF, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(inner, edgeRadius, edgeRadius, paint);
            }
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    static int edgeAlpha(float focus) {
        return Math.round(51 + 115 * focus);
    }

    static Shader edgeSheen(float top, float bottom) {
        return new LinearGradient(0, top, 0, bottom,
                new int[]{0x66FFFFFF, 0x33FFFFFF, 0x33FFFFFF, 0x1FFFFFFF},
                new float[]{0f, .28f, .72f, 1f}, Shader.TileMode.CLAMP);
    }

    /** A popup lives in a separate window, so its backdrop can capture the source without capturing itself. */
    @RequiresApi(31)
    private static final class PanelBlur {
        private static final int SAMPLE = 2;
        private final View source;
        private final RenderNode node = new RenderNode("Popup backdrop blur");
        private final int[] sourceLocation = new int[2];
        private final int[] hostLocation = new int[2];
        private int width, height;

        PanelBlur(View source, float radius) {
            this.source = source;
            node.setRenderEffect(RenderEffect.createBlurEffect(radius / SAMPLE, radius / SAMPLE, Shader.TileMode.CLAMP));
        }

        void draw(Canvas canvas, View host) {
            int w = source.getWidth(), h = source.getHeight();
            if (w <= 0 || h <= 0) return;
            if (width != w || height != h) {
                width = w;
                height = h;
                node.setPosition(0, 0, (w + SAMPLE - 1) / SAMPLE, (h + SAMPLE - 1) / SAMPLE);
                Canvas recording = node.beginRecording();
                try {
                    recording.scale(1f / SAMPLE, 1f / SAMPLE);
                    source.draw(recording);
                } finally {
                    node.endRecording();
                }
            }
            source.getLocationOnScreen(sourceLocation);
            host.getLocationOnScreen(hostLocation);
            canvas.translate(sourceLocation[0] - hostLocation[0], sourceLocation[1] - hostLocation[1]);
            canvas.scale(SAMPLE, SAMPLE);
            canvas.drawRenderNode(node);
        }
    }

    /** Cache a reduced-resolution backdrop for each blur radius. */
    @RequiresApi(31)
    private static final class WallpaperBlur {
        private static final int DOWNSAMPLE = 4;
        private final RenderNode node = new RenderNode("Glass wallpaper blur");
        private Drawable recorded;
        private int width, height;

        WallpaperBlur(float radius) {
            float smallRadius = radius / DOWNSAMPLE;
            node.setRenderEffect(RenderEffect.createBlurEffect(smallRadius, smallRadius, Shader.TileMode.CLAMP));
        }

        void draw(Canvas canvas, ImageView wallpaper) {
            int w = wallpaper.getWidth();
            int h = wallpaper.getHeight();
            if (w <= 0 || h <= 0) return;
            if (recorded != wallpaper.getDrawable() || width != w || height != h) {
                width = w;
                height = h;
                recorded = wallpaper.getDrawable();
                int smallWidth = (w + DOWNSAMPLE - 1) / DOWNSAMPLE;
                int smallHeight = (h + DOWNSAMPLE - 1) / DOWNSAMPLE;
                node.setPosition(0, 0, smallWidth, smallHeight);
                Canvas recording = node.beginRecording();
                recording.scale(1f / DOWNSAMPLE, 1f / DOWNSAMPLE);
                wallpaper.draw(recording);
                node.endRecording();
            }
            canvas.scale(DOWNSAMPLE, DOWNSAMPLE);
            canvas.drawRenderNode(node);
        }
    }

}
