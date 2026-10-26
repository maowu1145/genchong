package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

/** A local-coordinate sprite and mutually exclusive tap/hold/drag state machine. */
public final class PetSpriteView extends View {
  public interface Interaction {
    void press(PetSpriteView view);

    void drag(PetSpriteView view, float rawX, float rawY);

    void release(PetSpriteView view, float vx, float vy, boolean dragged, boolean canceled);

    void tap(PetSpriteView view);

    void longPress(PetSpriteView view);
  }

  private static final int IDLE = 0, PENDING = 1, DRAGGING = 2, LONG_PRESS = 3;
  public final PhysicsWorld.Body body;
  private final Interaction interaction;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Matrix transform = new Matrix();
  private final RectF artRect = new RectF(), transformed = new RectF();
  private PetCatalog.Entry entry;
  private String mood = "sweating", temporaryState, restingState = "idle";
  private long stateUntil, animationStart = SystemClock.uptimeMillis();
  private int gesture = IDLE, pointer = -1;
  private float downX, downY, size;
  private float localCenterX, localCenterY;
  private final int slop;
  private VelocityTracker tracker;
  private boolean calm, paused;
  private final Runnable hold = this::fireHold;

  private void fireHold() {
    if (gesture != PENDING) return;
    gesture = LONG_PRESS;
    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    interaction.longPress(this);
  }

  public PetSpriteView(Context context, PhysicsWorld.Body body, Interaction interaction) {
    super(context);
    this.body = body;
    this.interaction = interaction;
    slop = ViewConfiguration.get(context).getScaledTouchSlop();
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setClickable(true);
    setLongClickable(true);
  }

  public void configure(PetCatalog.Entry entry, PetStore.Instance instance, float density) {
    this.entry = entry;
    this.mood = instance.mood;
    size = PhysicsWorld.clamp(instance.sizeDp, 56, 144) * density;
    setContentDescription(entry.name + "，点按菜单，长按专属功能，可拖动");
    invalidate();
  }

  public void setCalm(boolean calm, boolean paused) {
    this.calm = calm;
    this.paused = paused;
  }

  public float downRawX() {
    return downX;
  }

  public float downRawY() {
    return downY;
  }

  public void setRestingState(String state) {
    restingState = state == null ? "idle" : state;
  }

  public void feedback(String state, long until) {
    temporaryState = state;
    stateUntil = until;
    animationStart = SystemClock.uptimeMillis();
    invalidate();
  }

  public boolean animated() {
    return !calm && !paused && !body.toolOpen && entry != null && (crazy() || usesFrames());
  }

  private boolean crazy() {
    return "crazy".equals(temporaryState) && SystemClock.uptimeMillis() < stateUntil;
  }

  /** Returns the exact transformed rectangular art bounds, with a 2px anti-alias margin. */
  public RectF worldBounds() {
    updateTransform();
    transformed.set(artRect);
    transform.mapRect(transformed);
    body.edgeX = Math.max(Math.abs(transformed.left), Math.abs(transformed.right)) + 2;
    body.edgeY = Math.max(Math.abs(transformed.top), Math.abs(transformed.bottom)) + 2;
    transformed.offset(body.x, body.y);
    transformed.inset(-2, -2);
    return transformed;
  }

  public void setWindowOrigin(float x, float y) {
    localCenterX = body.x - x;
    localCenterY = body.y - y;
  }

  private void updateTransform() {
    // Align the full animation canvas to the static foot anchor. In particular,
    // the approved 256px ride/cat loops use a different foot line from the 512px idle.
    float offsetX = 0, offsetY = 0;
    if (entry != null && usesFrames()) {
      offsetX = (entry.anchorX - entry.animationAnchorX) * size;
      offsetY = (entry.anchorY - entry.animationAnchorY) * size;
    } else if (entry != null) {
      PetCatalog.Anchor anchor = entry.stateAnchor(currentState());
      offsetX = (entry.anchorX - anchor.x) * size;
      offsetY = (entry.anchorY - anchor.y) * size;
    }
    artRect.set(-size / 2 + offsetX, -size / 2 + offsetY, size / 2 + offsetX, size / 2 + offsetY);
    float degrees = (float) Math.toDegrees(Math.atan2(body.normalY, body.normalX));
    transform.reset();
    if (crazy() && !calm && !paused && !body.toolOpen) {
      float time = (SystemClock.uptimeMillis() - animationStart) / 1000f;
      transform.postRotate((float) Math.sin(time * 16) * 9);
      transform.postTranslate(0, -(float) Math.abs(Math.sin(time * 10)) * size * .045f);
    }
    transform.postRotate((float) Math.toDegrees(body.angle));
    transform.postRotate(-degrees);
    transform.postScale(body.normalScale(), 1 / body.normalScale());
    transform.postRotate(degrees);
  }

  private String currentState() {
    if (entry == null) return "idle";
    if (calm && entry.states.containsKey("calm")) return "calm";
    if (temporaryState != null
        && SystemClock.uptimeMillis() < stateUntil
        && entry.states.containsKey(temporaryState)) return temporaryState;
    if ("sweat_emoji".equals(entry.id)) return "wronged".equals(mood) ? "sad" : mood;
    return restingState;
  }

  private boolean usesFrames() {
    return entry != null
        && !calm
        && !paused
        && !body.toolOpen
        && entry.animatesState(currentState());
  }

  private String imagePath() {
    if (entry == null) return null;
    return usesFrames()
        ? entry.animationFrame(SystemClock.uptimeMillis() - animationStart)
        : entry.statePath(currentState());
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    updateTransform();
    String path = imagePath();
    Bitmap bitmap = path == null ? null : PetCatalog.bitmap(getContext(), path);
    if (bitmap == null && entry != null && entry.idle != null)
      bitmap = PetCatalog.bitmap(getContext(), entry.idle);
    if (bitmap == null)
      return; // Missing art is reported by service; never substitute a fake character.
    canvas.save();
    canvas.translate(localCenterX, localCenterY);
    canvas.concat(transform);
    // drawBitmap preserves the original anti-aliased alpha; no thresholding or pixel rewrite.
    float scale = size / Math.max(bitmap.getWidth(), bitmap.getHeight());
    float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
    canvas.drawBitmap(
        bitmap,
        null,
        new RectF(
            artRect.centerX() - w / 2,
            artRect.centerY() - h / 2,
            artRect.centerX() + w / 2,
            artRect.centerY() + h / 2),
        paint);
    canvas.restore();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        cancelGesture();
        pointer = event.getPointerId(0);
        gesture = PENDING;
        downX = event.getRawX();
        downY = event.getRawY();
        tracker = VelocityTracker.obtain();
        addRawMovement(event);
        interaction.press(this);
        postDelayed(hold, ViewConfiguration.getLongPressTimeout());
        return true;
      case MotionEvent.ACTION_MOVE:
        if (pointer < 0 || event.findPointerIndex(pointer) < 0) {
          cancelGesture();
          return true;
        }
        addRawMovement(event);
        if (gesture == PENDING
            && Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > slop) {
          gesture = DRAGGING;
          removeCallbacks(hold);
        }
        if (gesture == DRAGGING) interaction.drag(this, event.getRawX(), event.getRawY());
        return true;
      case MotionEvent.ACTION_POINTER_DOWN:
        cancelGesture();
        return true;
      case MotionEvent.ACTION_UP:
        removeCallbacks(hold);
        addRawMovement(event);
        int previous = gesture;
        gesture = IDLE;
        float vx = 0, vy = 0;
        if (tracker != null) {
          tracker.computeCurrentVelocity(1000, 6000);
          vx = tracker.getXVelocity(pointer);
          vy = tracker.getYVelocity(pointer);
        }
        cleanupTracker();
        if (previous == PENDING) {
          interaction.release(this, 0, 0, false, false);
          performClick();
        } else if (previous == DRAGGING) {
          // UP may carry a final position without a preceding MOVE. Test the
          // actual release point against the visible recycle rectangle.
          interaction.drag(this, event.getRawX(), event.getRawY());
          interaction.release(this, vx, vy, true, false);
        }
        // LONG_PRESS's release must not throw, tap, delete or close the feature.
        return true;
      case MotionEvent.ACTION_CANCEL:
        cancelGesture();
        return true;
      default:
        return true;
    }
  }

  private void addRawMovement(MotionEvent event) {
    if (tracker == null) return;
    // The overlay moves under the finger, so local coordinates cannot estimate throw speed.
    MotionEvent raw = MotionEvent.obtain(event);
    raw.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
    tracker.addMovement(raw);
    raw.recycle();
  }

  @Override
  public boolean performClick() {
    super.performClick();
    interaction.tap(this);
    return true;
  }

  @Override
  public boolean performLongClick() {
    removeCallbacks(hold);
    gesture = LONG_PRESS;
    interaction.longPress(this);
    return true;
  }

  public void cancelGesture() {
    removeCallbacks(hold);
    int previous = gesture;
    gesture = IDLE;
    cleanupTracker();
    if (previous == PENDING || previous == DRAGGING) interaction.release(this, 0, 0, false, true);
  }

  private void cleanupTracker() {
    if (tracker != null) tracker.recycle();
    tracker = null;
    pointer = -1;
  }

  @Override
  protected void onDetachedFromWindow() {
    cancelGesture();
    super.onDetachedFromWindow();
  }
}
