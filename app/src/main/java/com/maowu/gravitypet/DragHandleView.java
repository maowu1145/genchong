package com.maowu.gravitypet;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

/** A small transparent overlay that captures touches only around the visible pet. */
public class DragHandleView extends View {
    public interface DoubleTapListener {
        void onDoubleTap();
    }
    public interface DismissGestureListener {
        void onDismissZoneChanged(boolean visible);
        boolean onDismissRelease(float rawX, float rawY,
                                 float velocityX, float velocityY);
    }

    private final PetView petView;
    private final DoubleTapListener doubleTapListener;
    private final DismissGestureListener dismissGestureListener;
    private final int touchSlop;
    private final int doubleTapSlop;
    private VelocityTracker velocityTracker;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float downRawX;
    private float downRawY;
    private float lastTapRawX;
    private float lastTapRawY;
    private long lastTapUpTime;
    private boolean moved;
    private boolean interactionGuardEnabled;
    private boolean dismissArmed;
    private float stillAnchorRawX;
    private float stillAnchorRawY;
    private final Runnable armDismissRunnable;

    public DragHandleView(Context context, PetView petView,
                          DoubleTapListener doubleTapListener,
                          DismissGestureListener dismissGestureListener) {
        super(context);
        this.petView = petView;
        this.doubleTapListener = doubleTapListener;
        this.dismissGestureListener = dismissGestureListener;
        armDismissRunnable = () -> {
            if (activePointerId == MotionEvent.INVALID_POINTER_ID
                    || interactionGuardEnabled) return;
            dismissArmed = true;
            if (this.dismissGestureListener != null) {
                this.dismissGestureListener.onDismissZoneChanged(true);
            }
        };
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        doubleTapSlop = configuration.getScaledDoubleTapSlop();
        setBackgroundColor(Color.TRANSPARENT);
        setContentDescription("拖动或双击小鼻嘎");
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                moved = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                stillAnchorRawX = downRawX;
                stillAnchorRawY = downRawY;
                activePointerId = event.getPointerId(0);
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                if (!interactionGuardEnabled) {
                    petView.beginDrag(event.getRawX(), event.getRawY());
                    scheduleDismissArm();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false;
                if (Math.hypot(event.getRawX() - downRawX,
                        event.getRawY() - downRawY) > touchSlop) {
                    moved = true;
                }
                if (interactionGuardEnabled) return true;
                if (!dismissArmed && Math.hypot(
                        event.getRawX() - stillAnchorRawX,
                        event.getRawY() - stillAnchorRawY) > touchSlop * 1.5f) {
                    stillAnchorRawX = event.getRawX();
                    stillAnchorRawY = event.getRawY();
                    scheduleDismissArm();
                }
                velocityTracker.addMovement(event);
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex >= 0) {
                    petView.dragTo(event.getRawX(), event.getRawY());
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (interactionGuardEnabled) {
                    recycleTracker();
                    return true;
                }
                float velocityX = 0f;
                float velocityY = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    velocityX = velocityTracker.getXVelocity(activePointerId);
                    velocityY = velocityTracker.getYVelocity(activePointerId);
                }
                petView.endDrag(velocityX, velocityY);
                boolean dismissed = dismissArmed && dismissGestureListener != null
                        && dismissGestureListener.onDismissRelease(
                        event.getRawX(), event.getRawY(), velocityX, velocityY);
                clearDismissGesture();
                if (dismissed) {
                    recycleTracker();
                    return true;
                }
                if (!moved) {
                    performClick();
                    handleTap(event);
                } else {
                    lastTapUpTime = 0L;
                }
                recycleTracker();
                return true;

            case MotionEvent.ACTION_CANCEL:
                lastTapUpTime = 0L;
                if (!interactionGuardEnabled) petView.endDrag(0f, 0f);
                clearDismissGesture();
                recycleTracker();
                return true;

            default:
                return true;
        }
    }

    private void handleTap(MotionEvent event) {
        long now = event.getEventTime();
        float x = event.getRawX();
        float y = event.getRawY();
        boolean withinTime = lastTapUpTime > 0L
                && now - lastTapUpTime <= ViewConfiguration.getDoubleTapTimeout();
        boolean withinDistance = Math.hypot(x - lastTapRawX, y - lastTapRawY)
                <= doubleTapSlop;
        if (withinTime && withinDistance) {
            lastTapUpTime = 0L;
            if (doubleTapListener != null) doubleTapListener.onDoubleTap();
            return;
        }
        lastTapUpTime = now;
        lastTapRawX = x;
        lastTapRawY = y;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void recycleTracker() {
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void scheduleDismissArm() {
        removeCallbacks(armDismissRunnable);
        postDelayed(armDismissRunnable, 1_500L);
    }

    private void clearDismissGesture() {
        removeCallbacks(armDismissRunnable);
        if (dismissArmed && dismissGestureListener != null) {
            dismissGestureListener.onDismissZoneChanged(false);
        }
        dismissArmed = false;
    }

    public void cancelCurrentDrag() {
        petView.endDrag(0f, 0f);
        clearDismissGesture();
        recycleTracker();
    }

    public void setInteractionGuardEnabled(boolean enabled) {
        if (interactionGuardEnabled == enabled) return;
        cancelCurrentDrag();
        interactionGuardEnabled = enabled;
        setContentDescription(enabled
                ? "小鼻嘎双击感应区"
                : "拖动或双击小鼻嘎");
    }
}
