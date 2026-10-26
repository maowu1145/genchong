package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class UiKit {
  public static final int BG = 0xFFF6F0E7, INK = 0xFF302A27, ACCENT = 0xFFA8B8A2;

  private UiKit() {}

  public static int dp(Context c, float n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  public static TextView text(Context c, String value, float sp) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(sp);
    t.setTextColor(INK);
    t.setFontFeatureSettings("kern");
    t.setLineSpacing(dp(c, 3), 1);
    return t;
  }

  public static LinearLayout column(Context c, int paddingDp) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    int p = dp(c, paddingDp);
    l.setPadding(p, p, p, p);
    return l;
  }

  public static GradientDrawable card(Context c) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(BG);
    d.setCornerRadii(
        new float[] {
          dp(c, 22), dp(c, 18), dp(c, 16), dp(c, 21), dp(c, 24), dp(c, 18), dp(c, 18), dp(c, 22)
        });
    d.setStroke(dp(c, 1.4f), INK);
    return d;
  }

  public static Button button(Context c, String title, Runnable action) {
    Button b = new Button(c);
    b.setText(title);
    b.setTextSize(15);
    b.setTextColor(INK);
    b.setAllCaps(false);
    b.setMinHeight(dp(c, 46));
    b.setMinimumHeight(dp(c, 46));
    b.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
    GradientDrawable d = card(c);
    d.setColor(ACCENT);
    b.setBackground(d);
    b.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(c, 7);
    p.bottomMargin = dp(c, 7);
    b.setLayoutParams(p);
    return b;
  }

  public static TextView heading(Context c, String value, float sp) {
    TextView t = text(c, value, sp);
    t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return t;
  }
}
