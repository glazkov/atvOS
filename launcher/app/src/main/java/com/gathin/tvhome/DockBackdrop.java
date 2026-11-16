package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

/** A cached blurred wallpaper sampled along the dock's rounded contour. */
final class DockBackdrop {
    private static final int SAMPLE = 2;
    private static final long SETTLE_MS = 50;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF requested = new RectF();
    private final RectF cached = new RectF();
    private Drawable source;
    private int width, height;
    private int offsetX, offsetY, cachedOffsetX, cachedOffsetY;
    private long movedAt;
    private int[] blurred;
    private Bitmap lens;

    boolean draw(Canvas canvas, View host, ImageView wallpaper, RectF bounds,
                 float radius, float depth, float strength, int x, int y) {
        Drawable drawable = wallpaper.getDrawable();
        int w = (wallpaper.getWidth() + SAMPLE - 1) / SAMPLE;
        int h = (wallpaper.getHeight() + SAMPLE - 1) / SAMPLE;
        if (!(drawable instanceof BitmapDrawable) || w <= 0 || h <= 0) return false;
        if (source != drawable || w != width || h != height) {
            source = drawable;
            width = w;
            height = h;
            blurred = blur(wallpaper, (BitmapDrawable) drawable);
            lens = null;
        }
        if (blurred == null) return false;
        long now = SystemClock.uptimeMillis();
        if (!requested.equals(bounds) || x != offsetX || y != offsetY) {
            requested.set(bounds);
            offsetX = x;
            offsetY = y;
            movedAt = now;
        }
        if (lens == null || !cached.equals(bounds) || x != cachedOffsetX || y != cachedOffsetY) {
            if (now - movedAt < SETTLE_MS) {
                host.postInvalidateDelayed(SETTLE_MS);
                return false;
            }
            lens = render(bounds, radius, depth, strength);
            cached.set(bounds);
            cachedOffsetX = x;
            cachedOffsetY = y;
        }
        canvas.drawBitmap(lens, null, bounds, filter);
        return true;
    }

    private int[] blur(ImageView wallpaper, BitmapDrawable drawable) {
        Bitmap original = drawable.getBitmap();
        Bitmap bitmap = original.getConfig() == Bitmap.Config.HARDWARE
                ? original.copy(Bitmap.Config.ARGB_8888, false) : original;
        if (bitmap == null) return null;
        Bitmap sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sample);
        canvas.scale(1f / SAMPLE, 1f / SAMPLE);
        canvas.concat(wallpaper.getImageMatrix());
        canvas.drawBitmap(bitmap, null, drawable.getBounds(), filter);
        if (bitmap != original) bitmap.recycle();
        int[] pixels = new int[width * height];
        sample.getPixels(pixels, 0, width, 0, 0, width, height);
        sample.recycle();
        int[] horizontal = new int[pixels.length];
        int[] output = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                horizontal[row + x] = smooth(pixels[row + Math.max(0, x - 1)],
                        pixels[row + x], pixels[row + Math.min(width - 1, x + 1)]);
            }
        }
        for (int y = 0; y < height; y++) {
            int prev = Math.max(0, y - 1) * width;
            int next = Math.min(height - 1, y + 1) * width;
            for (int x = 0; x < width; x++) {
                output[y * width + x] = smooth(horizontal[prev + x],
                        horizontal[y * width + x], horizontal[next + x]);
            }
        }
        return output;
    }

    private Bitmap render(RectF bounds, float radius, float depth, float strength) {
        int w = Math.max(1, (int) Math.ceil(bounds.width() / SAMPLE));
        int h = Math.max(1, (int) Math.ceil(bounds.height() / SAMPLE));
        int[] pixels = new int[w * h];
        float stepX = bounds.width() / w, stepY = bounds.height() / h;
        float flatX = bounds.width() / 2 - radius, flatY = bounds.height() / 2 - radius;
        for (int y = 0; y < h; y++) {
            float sy = bounds.top + (y + .5f) * stepY;
            float dy = sy - bounds.centerY();
            float qy = Math.abs(dy) - flatY;
            for (int x = 0; x < w; x++) {
                float sx = bounds.left + (x + .5f) * stepX;
                float sampleY = sy;
                float dx = sx - bounds.centerX();
                float qx = Math.abs(dx) - flatX;
                if (qx > radius - depth || qy > radius - depth || (qx > 0 && qy > 0)) {
                    float cx = Math.max(qx, 0), cy = Math.max(qy, 0);
                    float length = (float) Math.hypot(cx, cy);
                    float distance = radius - length - Math.min(Math.max(qx, qy), 0);
                    float t = Math.max(0f, Math.min(1f, 1f - distance / depth));
                    if (length > 0f && t > 0f) {
                        float shift = strength * (1f - (float) Math.sqrt(1f - t * t));
                        sx += Math.copySign(cx / length * shift, dx);
                        sampleY += Math.copySign(cy / length * shift, dy);
                    }
                }
                pixels[y * w + x] = sample((sx - offsetX) / SAMPLE - .5f, (sampleY - offsetY) / SAMPLE - .5f);
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    private int sample(float x, float y) {
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        int ix = (int) x, iy = (int) y;
        int right = Math.min(width - 1, ix + 1), below = Math.min(height - 1, iy + 1);
        int a = mix(blurred[iy * width + ix], blurred[iy * width + right], x - ix);
        int b = mix(blurred[below * width + ix], blurred[below * width + right], x - ix);
        return mix(a, b, y - iy);
    }

    private static int smooth(int a, int b, int c) {
        return mix(mix(a, c, .5f), b, .5f);
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
