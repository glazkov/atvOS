package com.gathin.tvhome;

import android.graphics.Path;
import android.graphics.RectF;

/** Figma-style continuous corners with a circular center and tangent Bézier transitions. */
final class SmoothCorners {
    private static final float SMOOTHING = 0.60f;

    private SmoothCorners() {}

    static void add(Path path, RectF rect, float radius) {
        float halfSide = Math.min(rect.width(), rect.height()) / 2f;
        radius = Math.min(radius, halfSide);
        if (radius <= 0f) {
            path.addRect(rect, Path.Direction.CW);
            return;
        }
        // Limit the transition length for small shapes while retaining the circular radius.
        float smoothing = Math.min(SMOOTHING, halfSide / radius - 1f);
        if (smoothing <= 0f) {
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            return;
        }
        float reach = radius * (1f + smoothing);
        float angle = 45f * smoothing;
        double radians = Math.toRadians(angle);
        float arcInset = radius * (1f - (float) Math.sin(radians));
        float arcDepth = radius * (1f - (float) Math.cos(radians));
        float tangentInset = radius * (1f - (float) Math.tan(radians / 2));
        float firstControl = reach - (reach - tangentInset) * (2f / 3f);

        float l = rect.left, t = rect.top, r = rect.right, b = rect.bottom;
        path.moveTo(l + reach, t);
        corner(path, r, t, 1, 0, 0, 1, -90, radius, reach, angle,
                arcInset, arcDepth, tangentInset, firstControl);
        corner(path, r, b, 0, 1, -1, 0, 0, radius, reach, angle,
                arcInset, arcDepth, tangentInset, firstControl);
        corner(path, l, b, -1, 0, 0, -1, 90, radius, reach, angle,
                arcInset, arcDepth, tangentInset, firstControl);
        corner(path, l, t, 0, -1, 1, 0, 180, radius, reach, angle,
                arcInset, arcDepth, tangentInset, firstControl);
        path.close();
    }

    private static void corner(Path path, float x, float y, int ax, int ay, int bx, int by,
                               float startAngle, float radius, float reach, float angle,
                               float arcInset, float arcDepth, float tangentInset, float firstControl) {
        path.lineTo(x - ax * reach, y - ay * reach);
        path.cubicTo(x - ax * firstControl, y - ay * firstControl,
                x - ax * tangentInset, y - ay * tangentInset,
                x - ax * arcInset + bx * arcDepth, y - ay * arcInset + by * arcDepth);
        float cx = x + (bx - ax) * radius, cy = y + (by - ay) * radius;
        path.arcTo(cx - radius, cy - radius, cx + radius, cy + radius,
                startAngle + angle, 90f - 2f * angle, false);
        path.cubicTo(x + bx * tangentInset, y + by * tangentInset,
                x + bx * firstControl, y + by * firstControl,
                x + bx * reach, y + by * reach);
    }
}
