package com.maowu.gravitypet.gengpet;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.InputFilter;
import android.widget.*;

/** Tool chrome uses only the three shared design colors, including control tints. */
final class FeatureUi {
  private FeatureUi() {}

  static TextView text(Activity a, LinearLayout parent, String value, int size) {
    TextView t = UiKit.text(a, value, size);
    t.setTextColor(UiKit.INK);
    t.setPadding(0, UiKit.dp(a, 8), 0, UiKit.dp(a, 8));
    parent.addView(t);
    return t;
  }

  static Button button(Activity a, LinearLayout parent, String value, Runnable action) {
    Button b = UiKit.button(a, value, action);
    b.setTextColor(UiKit.INK);
    b.setBackgroundTintList(ColorStateList.valueOf(UiKit.ACCENT));
    parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    return b;
  }

  static EditText input(Activity a, LinearLayout parent, String hint, String value, int limit) {
    EditText e = new EditText(a);
    e.setTextColor(UiKit.INK);
    e.setHintTextColor(UiKit.INK);
    e.setBackgroundTintList(ColorStateList.valueOf(UiKit.ACCENT));
    e.setHint(hint);
    e.setText(value);
    e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(limit)});
    parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  static CheckBox check(Activity a, LinearLayout parent, String label, boolean checked) {
    CheckBox b = new CheckBox(a);
    b.setText(label);
    b.setTextColor(UiKit.INK);
    b.setButtonTintList(ColorStateList.valueOf(UiKit.INK));
    b.setChecked(checked);
    parent.addView(b);
    return b;
  }

  static LinearLayout row(Activity a, LinearLayout parent) {
    LinearLayout r = new LinearLayout(a);
    r.setOrientation(LinearLayout.HORIZONTAL);
    parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
    return r;
  }

  static void rowButton(Activity a, LinearLayout row, String label, Runnable run) {
    Button b = UiKit.button(a, label, run);
    b.setTextColor(UiKit.INK);
    b.setBackgroundTintList(ColorStateList.valueOf(UiKit.ACCENT));
    row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
  }

  static void message(Activity a, String text) {
    Toast.makeText(a, text, Toast.LENGTH_LONG).show();
  }
}
