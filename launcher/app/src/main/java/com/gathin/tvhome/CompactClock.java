package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.TextClock;

/** A single line of digits without the font space reserved for accents and descenders. */
public final class CompactClock extends TextClock {
    private int digitBaseline;

    public CompactClock(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        Rect digits = new Rect();
        getPaint().getTextBounds("0123456789:", 0, 11, digits);
        // Keep one pixel for antialiasing; measure all digits so minute changes cannot clip.
        digitBaseline = 1 - digits.top;
        setMeasuredDimension(getMeasuredWidth(), digits.height() + 2);
    }

    @Override public int getBaseline() {
        return digitBaseline;
    }

    @Override protected void onDraw(Canvas canvas) {
        // TextView clips its internal layout against its original font metrics. Draw this
        // single digit line directly at the measured baseline so no part is clipped.
        getPaint().setColor(getCurrentTextColor());
        canvas.drawText(getText().toString(), getCompoundPaddingLeft(), digitBaseline, getPaint());
    }
}
