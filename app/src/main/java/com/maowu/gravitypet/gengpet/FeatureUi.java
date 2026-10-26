package com.maowu.gravitypet.gengpet;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.InputFilter;
import android.view.Gravity;
import android.widget.*;

/** Shared typography, fields and action hierarchy for the local tools. */
final class FeatureUi {
  private FeatureUi() {}

  static TextView text(Activity a, LinearLayout parent, String value, int size) {
    TextView t = size >= 18 ? UiKit.heading(a, value, size) : UiKit.text(a, value, size);
    t.setTextColor(size >= 17 ? UiKit.INK : UiKit.MUTED);
    t.setPadding(0, UiKit.dp(a, 6), 0, UiKit.dp(a, 10));
    parent.addView(t);
    return t;
  }

  static Button button(Activity a, LinearLayout parent, String value, Runnable action) {
    // NotesPanel shares this entry point; preserve emphasis on its save and recording actions.
    boolean primary = value.startsWith("保存") || value.equals("计算称谓")
        || value.equals("开始录音") || value.equals("停止并试听");
    Button b = primary ? UiKit.button(a, value, action) : UiKit.secondaryButton(a, value, action);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.topMargin = UiKit.dp(a, 4);
    params.bottomMargin = UiKit.dp(a, 4);
    parent.addView(b, params);
    return b;
  }

  static EditText input(Activity a, LinearLayout parent, String hint, String value, int limit) {
    EditText e = new EditText(a);
    TextView label = UiKit.text(a, hint, 13);
    label.setTextColor(UiKit.MUTED);
    label.setPadding(0, UiKit.dp(a, 10), 0, UiKit.dp(a, 6));
    parent.addView(label);
    e.setTextColor(UiKit.INK);
    e.setHintTextColor(UiKit.MUTED);
    e.setTextSize(16);
    e.setBackground(UiKit.card(a));
    e.setPadding(UiKit.dp(a, 12), UiKit.dp(a, 12), UiKit.dp(a, 12), UiKit.dp(a, 12));
    e.setMinHeight(UiKit.dp(a, 48));
    e.setId(android.view.View.generateViewId());
    label.setLabelFor(e.getId());
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
    b.setTextSize(15);
    b.setMinHeight(UiKit.dp(a, 48));
    b.setPadding(0, UiKit.dp(a, 4), 0, UiKit.dp(a, 4));
    b.setButtonTintList(new ColorStateList(
        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
        new int[] {UiKit.ACCENT, UiKit.MUTED}));
    b.setChecked(checked);
    parent.addView(b);
    return b;
  }

  static LinearLayout row(Activity a, LinearLayout parent) {
    LinearLayout r = new LinearLayout(a);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setGravity(Gravity.CENTER_VERTICAL);
    parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
    return r;
  }

  static void rowButton(Activity a, LinearLayout row, String label, Runnable run) {
    Button b = UiKit.secondaryButton(a, label, run);
    b.setPadding(UiKit.dp(a, 6), UiKit.dp(a, 8), UiKit.dp(a, 6), UiKit.dp(a, 8));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMargins(UiKit.dp(a, 3), UiKit.dp(a, 4), UiKit.dp(a, 3), UiKit.dp(a, 4));
    row.addView(b, params);
  }

  static void message(Activity a, String text) {
    Toast.makeText(a, text, Toast.LENGTH_LONG).show();
  }
}
