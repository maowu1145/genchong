package com.maowu.gravitypet.gengpet;

/**
 * Pure deliberate-recycle gesture gate. Coordinates are in one consistent screen-pixel space;
 * timestamps use a monotonic millisecond clock. Movement distance is cumulative path length.
 *
 * Dwell starts only once BOTH distance and inside conditions hold. Exiting cancels dwell and
 * disarms immediately. begin/reset never arm. The pointer-UP handler must only read armed()
 * and reset; it must not call move with its UP coordinates or timestamp to arm retrospectively.
 *
 * To support a completely stationary finger, the UI owner may call move with the last pointer
 * coordinates from a delayed dwell callback WHILE that pointer is still down. Such callbacks
 * must be cancelled on UP/CANCEL/reset and scoped to the current pointer/gesture generation.
 * Repeating an identical coordinate does not add distance.
 */
public final class RecycleGesture {
    private final double minDistancePixels;
    private final long dwellMs;
    private boolean active, armed, dwelling;
    private double lastX, lastY, distance;
    private long lastTime, dwellStart;

    public RecycleGesture(float minDistancePixels, long dwellMs) {
        if (!Float.isFinite(minDistancePixels) || minDistancePixels <= 0 || dwellMs < 0)
            throw new IllegalArgumentException("Distance must be finite and positive; dwell must be nonnegative");
        this.minDistancePixels = minDistancePixels;
        this.dwellMs = dwellMs;
    }

    public void begin(float downX, float downY, long now) {
        reset();
        if (!Float.isFinite(downX) || !Float.isFinite(downY)) return;
        active = true;
        lastX = downX;
        lastY = downY;
        lastTime = now;
    }

    public void move(float x, float y, boolean inside, long now) {
        if (!active) return;
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            reset();
            return;
        }
        if (now < lastTime) {
            // A stale event/clock rollback cannot inherit completed dwell.
            armed = false;
            dwelling = false;
            lastTime = now;
            lastX = x;
            lastY = y;
            return;
        }
        distance += Math.hypot((double) x - lastX, (double) y - lastY);
        lastX = x;
        lastY = y;
        lastTime = now;
        if (!inside || distance < minDistancePixels) {
            armed = false;
            dwelling = false;
            return;
        }
        if (!dwelling) {
            dwelling = true;
            dwellStart = now;
        }
        // Guard overflow as well as backward time; normal uptime values are far smaller.
        long elapsed = now - dwellStart;
        armed = elapsed >= 0 && elapsed >= dwellMs;
    }

    public boolean armed() { return active && armed; }

    public void reset() {
        active = false;
        armed = false;
        dwelling = false;
        lastX = lastY = distance = 0;
        lastTime = dwellStart = 0;
    }
}
