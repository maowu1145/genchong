package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

/** First-run play area uses the same spring/gravity solver as the actual pets. */
public final class PlaygroundView extends View implements Runnable {
  private final PhysicsWorld world = new PhysicsWorld();
  private final PhysicsWorld.Body pet;
  private final PetCatalog.Entry entry;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private float size;
  private BalloonRenderer renderer;
  private BalloonBody renderedBody;
  private final RectF spriteBounds = new RectF();
  private VelocityTracker tracker;
  private float offsetX, offsetY;
  private boolean attached;

  public PlaygroundView(Context context) {
    this(context,PetCatalog.get(context,"doro"));
  }

  public PlaygroundView(Context context, PetCatalog.Entry selected) {
    super(context);
    size = UiKit.dp(context, 104);
    spriteBounds.set(-size / 2, -size / 2, size / 2, size / 2);
    pet = new PhysicsWorld.Body("preview", size, size / 2, size * .39f);
    pet.softness=PetStore.DEFAULT_SOFTNESS;
    pet.edgeX = pet.edgeY = size * .5f;
    world.add(pet);
    world.setPixelsPerMeter(UiKit.dp(context, 125));
    entry = selected;
    setBackground(UiKit.card(context));
    setContentDescription("拖动角色，松手看看回弹；无需悬浮窗权限");
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    if (entry != null) PetCatalog.preload(context, entry, this::invalidate);
  }

  public void setPetSize(float dp) {
    size=UiKit.dp(getContext(),dp);
    if(getWidth()>0 && entry!=null) world.configureSoft(pet,entry.contourUV,size);
    wake();
  }

  public void setSoftness(float value) { pet.softness=value; pet.wake(); wake(); }
  public void setGravityEnabled(boolean value) { pet.gravity=value; pet.wake(); wake(); }
  public void setPinned(boolean value) { pet.pinned=value; pet.wake(); wake(); }

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    world.setBounds(
        UiKit.dp(getContext(), 5),
        UiKit.dp(getContext(), 5),
        w - UiKit.dp(getContext(), 5),
        h - UiKit.dp(getContext(), 5));
    if (oldW == 0) {
      pet.x = w * .5f;
      pet.y = size * .6f;
    }
    if(entry!=null) world.configureSoft(pet,entry.contourUV,size);
    wake();
  }

  private void wake() {
    removeCallbacks(this);
    if (attached && getWindowVisibility() == VISIBLE) {
      world.resetClock();
      post(this);
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    attached = true;
    wake();
  }

  @Override
  protected void onDetachedFromWindow() {
    attached = false;
    removeCallbacks(this);
    finishTouch(true);
    super.onDetachedFromWindow();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    if (world == null) return;
    if (visibility == VISIBLE) wake();
    else {
      removeCallbacks(this);
      finishTouch(true);
    }
  }

  @Override
  public void run() {
    if (!attached || getWindowVisibility() != VISIBLE) return;
    world.advance(System.nanoTime());
    invalidate();
    if (!world.isSettled())
      postOnAnimation(this);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (entry == null) return;
    Bitmap bitmap = PetCatalog.bitmapIfReady(entry.idle);
    if (bitmap == null) return;
    if(pet.soft!=null) {
      if(renderedBody!=pet.soft) { renderedBody=pet.soft;renderer=new BalloonRenderer(pet.soft,bitmap,new RectF(0,0,1,1)); }
      renderer.draw(canvas,pet.soft);
      return;
    }
    canvas.save();
    canvas.translate(pet.x, pet.y);
    float normal = (float) Math.toDegrees(Math.atan2(pet.normalY, pet.normalX));
    canvas.rotate(normal);
    canvas.scale(pet.normalScale(), 1 / pet.normalScale());
    canvas.rotate(-normal + (float) Math.toDegrees(pet.angle));
    canvas.drawBitmap(bitmap, null, spriteBounds, paint);
    canvas.restore();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (Math.abs(event.getX() - pet.x) > size / 2 || Math.abs(event.getY() - pet.y) > size / 2)
          return false;
        tracker = VelocityTracker.obtain();
        tracker.addMovement(event);
        offsetX = pet.x - event.getX();
        offsetY = pet.y - event.getY();
        pet.held = true;
        pet.wake();
        getParent().requestDisallowInterceptTouchEvent(true);
        wake();
        return true;
      case MotionEvent.ACTION_MOVE:
        if (tracker == null) return false;
        tracker.addMovement(event);
        pet.x = event.getX() + offsetX;
        pet.y = event.getY() + offsetY;
        world.constrain(pet);
        invalidate();
        return true;
      case MotionEvent.ACTION_UP:
        if (tracker == null) return false;
        tracker.addMovement(event);
        finishTouch(false);
        performClick();
        wake();
        return true;
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_POINTER_DOWN:
        finishTouch(true);
        wake();
        return true;
      default:
        return tracker != null;
    }
  }

  private void finishTouch(boolean canceled) {
    if (tracker == null) return;
    tracker.computeCurrentVelocity(1000);
    world.throwBody(
        pet, canceled ? 0 : tracker.getXVelocity(), canceled ? 0 : tracker.getYVelocity());
    tracker.recycle();
    tracker = null;
    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
