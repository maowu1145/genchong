package com.maowu.gravitypet;

/** Distinguishes a real pair impact from the one-substep gravity speed of resting bodies. */
final class RestingPairWakePolicy {
    private static final float MINIMUM_IMPACT_SPEED = 100f;
    private static final float GRAVITY_ALLOWANCE = 35f;

    private RestingPairWakePolicy() {}

    static boolean shouldWake(boolean eitherDragging,
                              boolean entrySettling,
                              float closingSpeed,
                              float expectedGravityClosingSpeed) {
        if (eitherDragging) return true;
        if (entrySettling) return false;
        float threshold = Math.max(MINIMUM_IMPACT_SPEED,
                Math.max(0f, expectedGravityClosingSpeed) + GRAVITY_ALLOWANCE);
        return closingSpeed > threshold;
    }
}
