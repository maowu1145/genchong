package com.maowu.gravitypet.gengpet;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Static display scenery. Shaders and paths are built only when bounds change. */
final class SceneBackdrop extends Drawable {
  static final int STAGE = 0, HERO = 1, PAGE = 2;
  private final int mode, tint;
  private final float radius;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF frame = new RectF(), oval = new RectF();
  private final Path clip = new Path();
  private Shader base, platform;

  SceneBackdrop(int mode, int tint, float radius) {
    this.mode = mode; this.tint = tint; this.radius = radius;
  }

  @Override protected void onBoundsChange(Rect b) {
    frame.set(b); clip.reset(); clip.addRoundRect(frame, radius, radius, Path.Direction.CW);
    int top = mode == HERO ? 0xFF5545B9 : mode == PAGE ? 0xFFECE8F8 : tint;
    int bottom = mode == HERO ? 0xFF8874DB : mode == PAGE ? 0xFFF6F7FC : 0xFFFBFAFF;
    base = new LinearGradient(b.left,b.top,b.right,b.bottom,top,bottom,Shader.TileMode.CLAMP);
    platform = new LinearGradient(0,b.top+b.height()*.76f,0,b.bottom,
        0xFFFFFFFF,mode==HERO?0xFFBAA9F0:0xFFDED8EF,Shader.TileMode.CLAMP);
  }

  @Override public void draw(Canvas c) {
    float w=frame.width(), h=frame.height(), x=frame.left, y=frame.top;
    c.save(); c.clipPath(clip);
    paint.setShader(base); paint.setStyle(Paint.Style.FILL); c.drawRect(frame,paint); paint.setShader(null);
    if(mode==PAGE) { c.restore(); return; }
    float cx=x+w*(mode==HERO?.86f:.5f), cy=y+h*.40f, r=Math.min(w,h)*(mode==HERO?.82f:.46f);
    paint.setColor(mode==HERO?0x12FFFFFF:0x70FFFFFF); c.drawCircle(cx,cy,r,paint);
    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1,w*.004f));
    paint.setColor(mode==HERO?0x30FFFFFF:0x99FFFFFF); c.drawCircle(cx,cy,r*.77f,paint);
    paint.setStyle(Paint.Style.FILL);
    if(mode==STAGE) {
      // Contact shadow, raised plinth, then its bright top surface.
      paint.setColor(0x16685694); oval.set(x+w*.13f,y+h*.79f,x+w*.87f,y+h*.99f); c.drawOval(oval,paint);
      paint.setColor(0xFFCFC7E4); oval.set(x+w*.17f,y+h*.78f,x+w*.83f,y+h*.94f); c.drawOval(oval,paint);
      paint.setShader(platform); oval.offset(0,-h*.045f); c.drawOval(oval,paint); paint.setShader(null);
      paint.setColor(0x14634F90); oval.set(x+w*.30f,y+h*.79f,x+w*.70f,y+h*.86f); c.drawOval(oval,paint);
    }
    paint.setColor(mode==HERO?0xA6FFFFFF:0xA69983BD);
    star(c,x+w*(mode==HERO?.57f:.16f),y+h*.25f,Math.min(w,h)*.025f);
    star(c,x+w*.87f,y+h*.57f,Math.min(w,h)*.018f);
    paint.setColor(mode==HERO?0x60FFFFFF:0x607F6CA5);
    c.drawCircle(x+w*.83f,y+h*.15f,Math.min(w,h)*.012f,paint);
    c.restore();
  }

  private void star(Canvas c,float x,float y,float r) {
    paint.setStrokeWidth(r*.55f); paint.setStrokeCap(Paint.Cap.ROUND);
    c.drawLine(x-r,y,x+r,y,paint); c.drawLine(x,y-r,x,y+r,paint);
  }
  @Override public void getOutline(Outline outline) { outline.setRoundRect(getBounds(),radius); }
  @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
  @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
  @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
