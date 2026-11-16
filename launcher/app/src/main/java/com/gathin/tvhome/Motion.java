package com.gathin.tvhome;

import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    private Motion() {
    }

    private static final OvershootInterpolator OVERSHOOT = new OvershootInterpolator(1.8f);
    // Reach the same peak earlier and leave the rest of the animation for settling.
    private static final Interpolator FOCUS_OVERSHOOT =
            time -> OVERSHOOT.getInterpolation(time + (5f / 7f) * time * (1f - time));
    private static final float FOCUS_PEAK_EXCESS = FOCUS_OVERSHOOT.getInterpolation(.4f) - 1f;
    private static final float CARD_PEAK_EXCESS = .05f;
    private static final float CARD_OVERSHOOT_COMPRESSION = 1f / CARD_PEAK_EXCESS - 1f / FOCUS_PEAK_EXCESS;
    private static final Interpolator CARD_OVERSHOOT = time -> {
        float progress = FOCUS_OVERSHOOT.getInterpolation(time);
        float excess = Math.max(0f, progress - 1f);
        // Attenuate the peak without changing velocity when crossing the target size.
        return progress - excess + excess / (1f + CARD_OVERSHOOT_COMPRESSION * excess);
    };
    private static final Interpolator FOCUS_SETTLE = new DecelerateInterpolator(1.5f);

    static Interpolator focusZoom(boolean overshoot) {
        return overshoot ? FOCUS_OVERSHOOT : FOCUS_SETTLE;
    }

    static Interpolator cardFocusZoom(boolean overshoot) {
        return overshoot ? CARD_OVERSHOOT : FOCUS_SETTLE;
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }
}
