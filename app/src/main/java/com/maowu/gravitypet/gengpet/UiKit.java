package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small, native design system shared by the gallery, tools and overlay menus. */
public final class UiKit {
  public static final int BG=0xFFFAFAF8, INK=0xFF252525, ACCENT=0xFF252525;
  public static final int SURFACE=0xFFFFFFFF, MUTED=0xFF696969, SOFT=0xFFF0F0ED, LINE=0xFFE5E5E0;
  public static final int DANGER=0xFFB23B4B;
  private UiKit() {}
  public static int dp(Context c,float n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
  public static TextView text(Context c,String value,float sp) {
    TextView t=new TextView(c); t.setText(value); t.setTextSize(sp); t.setTextColor(INK);
    t.setFontFeatureSettings("kern"); t.setLineSpacing(dp(c,2),1); return t;
  }
  public static TextView heading(Context c,String value,float sp) {
    TextView t=text(c,value,sp); t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); return t;
  }
  public static LinearLayout column(Context c,int paddingDp) {
    LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL);
    int p=dp(c,paddingDp); l.setPadding(p,p,p,p); return l;
  }
  public static GradientDrawable rounded(Context c,int color,float radius) {
    GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d;
  }
  public static GradientDrawable card(Context c) {
    GradientDrawable d=rounded(c,SURFACE,12); d.setStroke(dp(c,1),LINE); return d;
  }
  public static RippleDrawable ripple(Context c,int color,float radius) {
    return new RippleDrawable(ColorStateList.valueOf(0x18252525),rounded(c,color,radius),rounded(c,SURFACE,radius));
  }
  public static Button button(Context c,String title,Runnable action) { return styledButton(c,title,action,true); }
  public static Button secondaryButton(Context c,String title,Runnable action) { return styledButton(c,title,action,false); }
  private static Button styledButton(Context c,String title,Runnable action,boolean primary) {
    Button b=new Button(c); b.setText(title); b.setTextSize(14); b.setAllCaps(false);
    b.setTextColor(primary?SURFACE:ACCENT); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
    b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48)); b.setMinWidth(0); b.setMinimumWidth(0);
    b.setGravity(Gravity.CENTER); b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));
    b.setBackground(ripple(c,primary?INK:SOFT,10));
    b.setStateListAnimator(null);
    b.setOnClickListener(v->action.run());
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(c,6); p.bottomMargin=dp(c,6);
    b.setLayoutParams(p); return b;
  }
  /** Material Symbols Rounded, bundled vectors; no font or network dependency. */
  public static Drawable icon(Context c,String name,int color) {
    int id = switch (name) {
      case "add" -> com.maowu.gravitypet.R.drawable.ic_add;
      case "arrow_back" -> com.maowu.gravitypet.R.drawable.ic_arrow_back;
      case "battery_saver" -> com.maowu.gravitypet.R.drawable.ic_battery_saver;
      case "bedtime" -> com.maowu.gravitypet.R.drawable.ic_bedtime;
      case "celebration" -> com.maowu.gravitypet.R.drawable.ic_celebration;
      case "chevron_right" -> com.maowu.gravitypet.R.drawable.ic_chevron_right;
      case "close" -> com.maowu.gravitypet.R.drawable.ic_close;
      case "delete" -> com.maowu.gravitypet.R.drawable.ic_delete;
      case "download" -> com.maowu.gravitypet.R.drawable.ic_download;
      case "edit_note" -> com.maowu.gravitypet.R.drawable.ic_edit_note;
      case "expand_more" -> com.maowu.gravitypet.R.drawable.ic_expand_more;
      case "family_restroom" -> com.maowu.gravitypet.R.drawable.ic_family_restroom;
      case "favorite" -> com.maowu.gravitypet.R.drawable.ic_favorite;
      case "favorite_filled" -> com.maowu.gravitypet.R.drawable.ic_favorite_filled;
      case "mood" -> com.maowu.gravitypet.R.drawable.ic_mood;
      case "notifications" -> com.maowu.gravitypet.R.drawable.ic_notifications;
      case "pause" -> com.maowu.gravitypet.R.drawable.ic_pause;
      case "pets" -> com.maowu.gravitypet.R.drawable.ic_pets;
      case "pets_filled" -> com.maowu.gravitypet.R.drawable.ic_pets_filled;
      case "play_arrow" -> com.maowu.gravitypet.R.drawable.ic_play_arrow;
      case "search" -> com.maowu.gravitypet.R.drawable.ic_search;
      case "settings" -> com.maowu.gravitypet.R.drawable.ic_settings;
      case "settings_filled" -> com.maowu.gravitypet.R.drawable.ic_settings_filled;
      case "tune" -> com.maowu.gravitypet.R.drawable.ic_tune;
      case "water_drop" -> com.maowu.gravitypet.R.drawable.ic_water_drop;
      case "widgets" -> com.maowu.gravitypet.R.drawable.ic_widgets;
      case "widgets_filled" -> com.maowu.gravitypet.R.drawable.ic_widgets_filled;
      default -> throw new IllegalArgumentException("Missing bundled icon: " + name);
    };
    Drawable d=c.getDrawable(id).mutate(); d.setTint(color); d.setBounds(0,0,dp(c,24),dp(c,24)); return d;
  }
  public static ImageButton iconButton(Context c,String name,String description,Runnable action) {
    ImageButton b=new ImageButton(c); b.setImageDrawable(icon(c,name,ACCENT)); b.setContentDescription(description);
    b.setPadding(dp(c,12),dp(c,12),dp(c,12),dp(c,12)); b.setBackground(ripple(c,0x00000000,24));
    b.setMinimumWidth(dp(c,48)); b.setMinimumHeight(dp(c,48)); b.setOnClickListener(v->action.run()); return b;
  }
}
