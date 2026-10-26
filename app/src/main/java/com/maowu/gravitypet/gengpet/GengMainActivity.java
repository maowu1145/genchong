package com.maowu.gravitypet.gengpet;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local-first gallery, settings and explicit entry point for the overlay service. */
public final class GengMainActivity extends Activity {
  private static final int OVERLAY = 40, EXPORT = 41, NOTIFICATIONS = 42;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LinearLayout outer, body, cards;
  private Button allFilter, favoriteFilter;
  private int tab = 0;
  private ScrollView pageScroll;
  private final int[] scrollPositions = new int[3];
  private Dialog detailDialog;
  private boolean favoritesOnly;
  private String query = "", pendingCharacter;
  private final Runnable refreshUi =
      () -> {
        if (!isFinishing()) show();
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (saved != null) {
      tab = saved.getInt("tab");
      pendingCharacter = saved.getString("pendingCharacter");
      query = saved.getString("query", "");
      favoritesOnly = saved.getBoolean("favoritesOnly");
      int[] positions = saved.getIntArray("scrollPositions");
      if (positions != null && positions.length == 3) System.arraycopy(positions, 0, scrollPositions, 0, 3);
    }
    getWindow().setStatusBarColor(UiKit.BG);
    getWindow().setNavigationBarColor(UiKit.BG);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  }

  @Override
  protected void onResume() {
    super.onResume();
    ReminderScheduler.reschedule(this);
    show();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("tab", tab);
    out.putString("pendingCharacter", pendingCharacter);
    out.putString("query", query);
    out.putBoolean("favoritesOnly", favoritesOnly);
    if (pageScroll != null) scrollPositions[tab] = pageScroll.getScrollY();
    out.putIntArray("scrollPositions", scrollPositions);
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (detailDialog != null) detailDialog.dismiss();
    super.onDestroy();
  }

  private void show() {
    if (pageScroll != null) scrollPositions[tab] = pageScroll.getScrollY();
    outer = UiKit.column(this, 0);
    outer.setBackgroundColor(UiKit.BG);
    setContentView(outer);
    outer.setOnApplyWindowInsetsListener((v, insets) -> {
      v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
          insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
      return insets;
    });
    pageScroll = new ScrollView(this);
    pageScroll.setFillViewport(true);
    pageScroll.setClipToPadding(false);
    pageScroll.setVerticalScrollBarEnabled(false);
    outer.addView(pageScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    body = UiKit.column(this, 16);
    pageScroll.addView(body);
    cards = null;
    if (tab == 0) gallery(); else if (tab == 1) tools(); else settings();
    space(body, 8);
    View divider = new View(this); divider.setBackgroundColor(UiKit.LINE);
    outer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    LinearLayout nav = row(outer);
    nav.setBackgroundColor(UiKit.SURFACE);
    nav.setPadding(dp(16), dp(5), dp(16), dp(5));
    nav.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52 + 18 * getResources().getConfiguration().fontScale)));
    String[] labels = {"角色", "工具", "设置"};
    String[] icons = {"pets", "widgets", "settings"};
    for (int i = 0; i < labels.length; i++) {
      final int selected = i;
      LinearLayout item = UiKit.column(this, 3); item.setGravity(Gravity.CENTER);
      item.setBackground(UiKit.ripple(this, 0x00000000, 16));
      item.setContentDescription(labels[i]); item.setSelected(i == tab);
      item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      ImageView icon = new ImageView(this);
      icon.setImageDrawable(UiKit.icon(this, icons[i] + (i == tab ? "_filled" : ""), i == tab ? UiKit.ACCENT : UiKit.MUTED));
      icon.setPadding(dp(16), dp(4), dp(16), dp(4));
      if (i == tab) icon.setBackground(UiKit.rounded(this, UiKit.SOFT, 16));
      item.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(32)));
      TextView label = UiKit.heading(this, labels[i], 12);
      label.setGravity(Gravity.CENTER);
      label.setTextColor(i == tab ? UiKit.ACCENT : UiKit.MUTED);
      item.addView(label);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      item.setOnClickListener(v -> {
        scrollPositions[tab] = pageScroll.getScrollY(); pageScroll = null;
        tab = selected; show();
      });
      nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }
    final ScrollView current = pageScroll;
    int y = scrollPositions[tab]; current.post(() -> current.scrollTo(0, y));
  }

  private void title(String eyebrow, String heading, String detail) {
    LinearLayout line = row(body);
    line.addView(UiKit.heading(this, heading, 26), new LinearLayout.LayoutParams(0, -2, 1));
    TextView badge = UiKit.text(this, eyebrow, 12);
    badge.setTextColor(UiKit.ACCENT); badge.setPadding(dp(10), dp(5), dp(10), dp(5));
    badge.setBackground(UiKit.rounded(this, UiKit.SOFT, 12)); line.addView(badge);
    if (!detail.isEmpty()) {
      TextView subtitle = UiKit.text(this, detail, 14); subtitle.setTextColor(UiKit.MUTED);
      subtitle.setPadding(0, dp(5), 0, 0); body.addView(subtitle);
    }
    space(body, 16);
  }

  private void gallery() {
    title("6 位伙伴", "梗宠", "一点小陪伴，随时在身边。");
    ArrayList<PetStore.Instance> active = PetStore.loadInstances(this);
    LinearLayout status = card(body);
    LinearLayout top = row(status);
    LinearLayout copy = UiKit.column(this, 0);
    top.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    boolean running = GengOverlayService.isRunning();
    copy.addView(UiKit.heading(this, running ? "屏幕上的伙伴 · " + active.size() : "准备好，认识新伙伴", 16));
    String state = !Settings.canDrawOverlays(this) ? "允许悬浮窗后，就能召唤到桌面"
        : active.isEmpty() ? "选一只喜欢的，点击下方召唤"
        : !running ? "已收起 " + active.size() + " 只 · 可在管理中恢复"
        : GengOverlayService.isPaused() ? "已暂停 · 位置保留" : "正在陪伴你 · 点头像调节";
    TextView stateText = UiKit.text(this, state, 12); stateText.setTextColor(UiKit.MUTED); copy.addView(stateText);
    top.addView(UiKit.iconButton(this, "tune", "管理屏幕上的伙伴", this::managePets), new LinearLayout.LayoutParams(dp(48), dp(48)));
    if (!Settings.canDrawOverlays(this)) status.addView(UiKit.secondaryButton(this, "开启悬浮窗", () -> requestOverlay(null)));
    else if (!active.isEmpty()) {
      HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false);
      LinearLayout avatars = new LinearLayout(this); avatars.setOrientation(LinearLayout.HORIZONTAL);
      strip.addView(avatars); status.addView(strip);
      for (PetStore.Instance instance : active) {
        PetCatalog.Entry e = PetCatalog.get(this, instance.characterId); if (e == null) continue;
        PetThumb avatar = new PetThumb(this, e); avatar.animate = false;
        avatar.setBackground(UiKit.ripple(this, tint(e.id), 18));
        avatar.setContentDescription("调节" + e.name + (instance.pinned ? "，已固定" : ""));
        avatar.setOnClickListener(v -> detail(e, instance.uid));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(dp(52), dp(52));
        a.setMargins(0, dp(6), dp(8), 0); avatars.addView(avatar, a);
      }
    }
    EditText search = new EditText(this);
    search.setSingleLine(true); search.setTextSize(14); search.setHint("搜索角色或小工具");
    search.setTextColor(UiKit.INK); search.setHintTextColor(UiKit.MUTED);
    search.setBackground(UiKit.rounded(this, UiKit.SURFACE, 16));
    search.setPadding(dp(14), dp(12), dp(14), dp(12));
    search.setCompoundDrawables(UiKit.icon(this, "search", UiKit.MUTED), null, null, null);
    search.setCompoundDrawablePadding(dp(8)); search.setText(query);
    search.setContentDescription("搜索角色或小工具");
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    body.addView(search, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout filters = row(body);
    allFilter = UiKit.secondaryButton(this, "全部", () -> { favoritesOnly = false; renderCards(); });
    favoriteFilter = UiKit.secondaryButton(this, "收藏", () -> { favoritesOnly = true; renderCards(); });
    weighted(filters, allFilter); weighted(filters, favoriteFilter);
    Button trial = UiKit.secondaryButton(this, "试玩", () -> detail(PetCatalog.get(this, "doro"), null));
    weighted(filters, trial);
    cards = UiKit.column(this, 0); body.addView(cards); renderCards();
    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
      public void onTextChanged(CharSequence s, int st, int before, int count) { query = s.toString(); renderCards(); }
      public void afterTextChanged(Editable e) {}
    });
  }

  private void managePets() {
    LinearLayout content = UiKit.column(this, 16);
    ScrollView scroll = new ScrollView(this); scroll.addView(content);
    ArrayList<PetStore.Instance> active = PetStore.loadInstances(this);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle("屏幕上的伙伴").setView(scroll).setNegativeButton("完成", null).create();
    if (!active.isEmpty()) {
      boolean running = GengOverlayService.isRunning(), paused = GengOverlayService.isPaused();
      content.addView(UiKit.secondaryButton(this, !running ? "恢复这组伙伴" : paused ? "继续陪伴" : "暂停所有伙伴", () -> {
        dialog.dismiss(); control(!running ? GengOverlayService.ACTION_RELOAD : paused ? GengOverlayService.ACTION_RESUME : GengOverlayService.ACTION_PAUSE, null);
      }));
      for (PetStore.Instance pet : active) {
        PetCatalog.Entry e = PetCatalog.get(this, pet.characterId); if(e == null) continue;
        LinearLayout line = row(content);
        line.addView(UiKit.text(this, e.name, 15), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(UiKit.iconButton(this, "tune", "调节" + e.name, () -> {dialog.dismiss(); detail(e, pet.uid);}), new LinearLayout.LayoutParams(dp(48), dp(48)));
        line.addView(UiKit.iconButton(this, "close", "收回" + e.name, () -> {dialog.dismiss(); remove(pet.uid);}), new LinearLayout.LayoutParams(dp(48), dp(48)));
      }
    } else content.addView(UiKit.text(this, "还没有伙伴，去角色馆召唤一只吧。", 14));
    int previous = PetStore.previousCount(this);
    if(previous > 0) content.addView(UiKit.secondaryButton(this, "恢复上一组 · " + previous + " 只", () -> {dialog.dismiss(); control(GengOverlayService.ACTION_RESTORE_PREVIOUS, null);}));
    dialog.show();
  }

  private boolean singleColumn() {
    return getResources().getConfiguration().screenWidthDp < 340 || getResources().getConfiguration().fontScale > 1.25f;
  }

  private void renderCards() {
    if (cards == null) return;
    allFilter.setBackground(UiKit.ripple(this, favoritesOnly ? UiKit.BG : UiKit.SOFT, 14));
    favoriteFilter.setBackground(UiKit.ripple(this, favoritesOnly ? UiKit.SOFT : UiKit.BG, 14));
    allFilter.setTextColor(favoritesOnly ? UiKit.MUTED : UiKit.ACCENT);
    favoriteFilter.setTextColor(favoritesOnly ? UiKit.ACCENT : UiKit.MUTED);
    allFilter.setSelected(!favoritesOnly); favoriteFilter.setSelected(favoritesOnly);
    cards.removeAllViews(); int matches = 0; LinearLayout gridRow = null;
    int columns = singleColumn() ? 1 : 2;
    for (PetCatalog.Entry e : PetCatalog.all(this)) {
      if (favoritesOnly && !favorite(e.id)) continue;
      if (!query.isEmpty() && !(e.name + e.description + featureName(e.id)).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
      if(matches % columns == 0) { gridRow = row(cards); gridRow.setGravity(Gravity.TOP); }
      LinearLayout cell = UiKit.column(this, 10); cell.setBackground(UiKit.card(this));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
      cp.bottomMargin = dp(12); if(columns == 2) {cp.leftMargin = matches % 2 == 0 ? 0 : dp(6); cp.rightMargin = matches % 2 == 0 ? dp(6) : 0;}
      gridRow.addView(cell, cp); matches++;
      FrameLayout stage = new FrameLayout(this); stage.setBackground(UiKit.rounded(this, tint(e.id), 14));
      PetThumb thumb = new PetThumb(this, e); thumb.previewScale = 1.1f;
      thumb.setContentDescription("查看和调节" + e.name); thumb.setOnClickListener(v -> detail(e, null));
      stage.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
      ImageButton fav = UiKit.iconButton(this, favorite(e.id) ? "favorite_filled" : "favorite", (favorite(e.id) ? "取消收藏" : "收藏") + e.name, () -> {
        PetStore.prefs(this).edit().putBoolean("favorite_" + e.id, !favorite(e.id)).apply(); renderCards();
      });
      FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END); stage.addView(fav, fp);
      cell.addView(stage, new LinearLayout.LayoutParams(-1, dp(114)));
      TextView name = UiKit.heading(this, e.name, 16); name.setPadding(dp(2), dp(9), 0, 0); cell.addView(name);
      TextView feature = UiKit.text(this, featureName(e.id), 12); feature.setTextColor(UiKit.MUTED);
      feature.setPadding(dp(2), dp(3), 0, dp(6)); cell.addView(feature);
      Button summon = UiKit.button(this, "召唤", () -> summon(e.id));
      summon.setContentDescription("召唤" + e.name); cell.addView(summon);
    }
    if(columns == 2 && matches % 2 != 0) gridRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    if(matches == 0) {
      LinearLayout empty = card(cards); space(empty, 20);
      TextView title = UiKit.heading(this, favoritesOnly ? "还没有收藏的伙伴" : "暂时没找到", 18); title.setGravity(Gravity.CENTER); empty.addView(title);
      TextView hint = UiKit.text(this, favoritesOnly ? "点击角色右上角的爱心，留住喜欢。" : "试试角色名字，或者“提醒”“心情”。", 14); hint.setTextColor(UiKit.MUTED); hint.setGravity(Gravity.CENTER); empty.addView(hint); space(empty, 20);
    }
  }

  private String featureName(String id) {
    switch(id) {
      case "doro": return "喝水 · 水果提醒";
      case "grudge_panda": return "文字 · 语音记录";
      case "spinning_cat": return "乘车舒适辅助";
      case "sweat_emoji": return "记录今日心情";
      case "kinship_ride": return "亲属称谓计算";
      default: return "星期四小庆祝";
    }
  }

  private String featureIcon(String id) {
    switch(id) {
      case "doro": return "water_drop";
      case "grudge_panda": return "edit_note";
      case "spinning_cat": return "widgets";
      case "sweat_emoji": return "mood";
      case "kinship_ride": return "family_restroom";
      default: return "celebration";
    }
  }

  private int tint(String id) {
    switch(id) {
      case "doro": return 0xFFFCEEF5;
      case "grudge_panda": return 0xFFEEF0F6;
      case "spinning_cat": return 0xFFEEEAFB;
      case "sweat_emoji": return 0xFFFFF4D8;
      case "kinship_ride": return 0xFFE7F3F1;
      default: return 0xFFFFEDE4;
    }
  }

  private boolean favorite(String id) {
    return PetStore.prefs(this).getBoolean("favorite_" + id, false);
  }

  private void tools() {
    title("随手用", "日常小工具", "提醒、记录和一点好心情。");
    LinearLayout banner = card(body);
    banner.setBackground(UiKit.rounded(this, UiKit.SOFT, 20));
    banner.addView(UiKit.heading(this, "每位伙伴，都有一点拿手好戏", 16));
    TextView note = UiKit.text(this, "不召唤也能使用，记录保存在本机。", 13); note.setTextColor(UiKit.MUTED); banner.addView(note);
    LinearLayout gridRow = null; int n = 0, columns = singleColumn() ? 1 : 2;
    for (PetCatalog.Entry e : PetCatalog.all(this)) {
      if(n % columns == 0) {gridRow = row(body); gridRow.setGravity(Gravity.TOP);}
      LinearLayout tile = UiKit.column(this, 16); tile.setBackground(UiKit.ripple(this, UiKit.SURFACE, 20));
      ImageView icon = new ImageView(this); icon.setImageDrawable(UiKit.icon(this, featureIcon(e.id), UiKit.ACCENT));
      icon.setPadding(dp(10),dp(10),dp(10),dp(10)); icon.setBackground(UiKit.rounded(this,tint(e.id),16));
      tile.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48))); space(tile,14);
      tile.addView(UiKit.heading(this,featureName(e.id),16));
      TextView caption = UiKit.text(this, e.name + "的小工具", 12); caption.setTextColor(UiKit.MUTED); caption.setPadding(0,dp(6),0,0); tile.addView(caption);
      tile.setOnClickListener(v -> FeatureActivity.open(this,e.id,""));
      tile.setContentDescription("打开" + featureName(e.id) + "，" + e.name);
      tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-2,1); lp.bottomMargin=dp(12);
      if(columns==2){lp.leftMargin=n%2==0?0:dp(6); lp.rightMargin=n%2==0?dp(6):0;}
      gridRow.addView(tile,lp); n++;
    }
  }

  private void settings() {
    title("按你的节奏", "陪伴设置", "什么时候出现，由你决定。");
    section("桌宠状态");
    LinearLayout controls = card(body);
    boolean running = GengOverlayService.isRunning(), paused = GengOverlayService.isPaused();
    actionRow(controls, !running || paused ? "play_arrow" : "pause",
        !running ? "恢复这组伙伴" : paused ? "继续陪伴" : "暂停所有伙伴",
        !running ? "恢复上次收起的角色" : paused ? "让伙伴继续活动" : "停止动作，保留当前位置", () -> control(!running ? GengOverlayService.ACTION_RELOAD : paused ? GengOverlayService.ACTION_RESUME : GengOverlayService.ACTION_PAUSE,null));
    actionRow(controls,"close","收起悬浮桌宠","暂时离开屏幕，保留这组伙伴",()->{stopService(new Intent(this,GengOverlayService.class));handler.postDelayed(refreshUi,180);});
    actionRow(controls,"pets","收回全部伙伴","清空当前这组，下次重新挑选",this::recallAll);
    section("节奏与安静时段");
    LinearLayout prefs = card(body);
    check(prefs,"低功耗模式",PetStore.prefs(this).getBoolean("low_power",false),value->{PetStore.prefs(this).edit().putBoolean("low_power",value).apply();reload();});
    mutedText(prefs,"减少角色动效；熄屏和暂停时停止不必要的采样。");
    timeSetting(prefs,"安静开始","quiet_start",1320); timeSetting(prefs,"安静结束","quiet_end",480);
    mutedText(prefs,"两个时间相同即关闭。提醒时间可在工具里单独设置。");
    section("权限与数据");
    LinearLayout permissions = card(body);
    actionRow(permissions,"widgets","悬浮窗权限",Settings.canDrawOverlays(this)?"已允许 · 可在其他应用上显示":"未允许 · 点击开启",()->requestOverlay(null));
    boolean notification = ReminderReceiver.notificationsAllowed(this);
    actionRow(permissions,"notifications","通知权限",notification?"已允许":"未允许 · 桌宠气泡仍可使用",()->{
      if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);
      else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
    });
    actionRow(permissions,"download","导出记录与设置","文字、录音和设置打包保存",this::exportData);
    actionRow(permissions,"delete","删除本机全部数据","此操作不可撤销，建议先导出",()->new AlertDialog.Builder(this).setTitle("删除所有记录和设置？")
        .setMessage("文字、录音、收藏和桌宠设置会从这台手机删除。建议先导出一份备份。")
        .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->deleteData()).show());
    LinearLayout about = card(body);
    about.addView(UiKit.heading(this,"梗宠 · 0.1.2",16));
    mutedText(about,"6 位伙伴，陪你过日常。所有记录保存在这台手机里。");
    mutedText(about,"麦克风仅在录音时申请。乘车辅助为可随时关闭的实验功能。");
  }

  private void section(String label) {
    TextView t = UiKit.heading(this,label,14); t.setTextColor(UiKit.MUTED); t.setPadding(dp(2),dp(4),0,dp(10)); body.addView(t);
  }

  private void mutedText(LinearLayout parent,String text) {
    TextView t = UiKit.text(this,text,13); t.setTextColor(UiKit.MUTED); t.setPadding(0,dp(4),0,dp(6)); parent.addView(t);
  }

  private void actionRow(LinearLayout parent,String icon,String label,String description,Runnable action) {
    LinearLayout line = row(parent); line.setPadding(0,dp(10),0,dp(10)); line.setMinimumHeight(dp(64));
    line.setBackground(UiKit.ripple(this,UiKit.SURFACE,12)); line.setOnClickListener(v->action.run());
    ImageView glyph = new ImageView(this); glyph.setImageDrawable(UiKit.icon(this,icon,icon.equals("delete")?UiKit.DANGER:UiKit.ACCENT));
    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(24),dp(24)); ip.rightMargin=dp(12); line.addView(glyph,ip);
    LinearLayout texts = UiKit.column(this,0); line.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
    TextView title=UiKit.heading(this,label,15); if(icon.equals("delete"))title.setTextColor(UiKit.DANGER); texts.addView(title);
    TextView hint=UiKit.text(this,description,12); hint.setTextColor(UiKit.MUTED); texts.addView(hint);
    ImageView arrow=new ImageView(this); arrow.setImageDrawable(UiKit.icon(this,"chevron_right",UiKit.MUTED)); line.addView(arrow,new LinearLayout.LayoutParams(dp(20),dp(20)));
    line.setContentDescription(label+"，"+description); line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  private void detail(PetCatalog.Entry e, String uid) {
    if (e == null) return;
    PetStore.Instance existing = uid == null ? null : PetStore.find(this, uid);
    PetStore.Instance draft = existing == null ? new PetStore.Instance() : existing.copy();
    draft.characterId = e.id;
    if (existing == null) {
      SharedPreferences p = PetStore.prefs(this);
      draft.sizeDp = p.getFloat("default_size_" + e.id, 88);
      draft.softness = p.getFloat("default_softness_" + e.id, PetStore.DEFAULT_SOFTNESS);
      draft.gravity = p.getBoolean("default_gravity_" + e.id, true);
      draft.pinned = p.getBoolean("default_pinned_" + e.id, false);
      draft.muted = p.getBoolean("default_muted_" + e.id, true);
    }
    Dialog dialog = new Dialog(this);
    detailDialog = dialog;
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout sheet = UiKit.column(this, 16);
    sheet.setBackground(UiKit.rounded(this, UiKit.SURFACE, 24));
    LinearLayout header = row(sheet);
    LinearLayout labels = UiKit.column(this, 0);
    labels.addView(UiKit.heading(this, e.name, 22));
    TextView target = UiKit.text(this, existing == null ? "新召唤的默认设置" : "仅调节屏幕上的这只", 12);
    target.setTextColor(UiKit.MUTED); labels.addView(target);
    header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(UiKit.iconButton(this, "close", "关闭调节，不保存", dialog::dismiss), new LinearLayout.LayoutParams(dp(48), dp(48)));
    ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false);
    sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout box = UiKit.column(this, 0); box.setPadding(0, dp(12), 0, dp(12));
    scroll.addView(box);
    PlaygroundView preview = new PlaygroundView(this, e);
    preview.setPetSize(draft.sizeDp);
    preview.setSoftness(draft.softness);
    preview.setGravityEnabled(draft.gravity);
    preview.setPinned(draft.pinned);
    preview.setBackground(UiKit.rounded(this, tint(e.id), 20));
    box.addView(preview, new LinearLayout.LayoutParams(-1, dp(150)));
    mutedText(box, "拖动上方角色，试试回弹手感。");
    space(box, 8);
    slider(
        box,
        "大小",
        56,
        144,
        Math.round(draft.sizeDp),
        value -> {
          draft.sizeDp = value;
          preview.setPetSize(value);
        });
    SeekBar softnessSlider=slider(
        box,
        "柔软度（0 硬 → 100 软）",
        0,
        100,
        Math.round(draft.softness * 100),
        value -> {
          draft.softness = value / 100f;
          preview.setSoftness(draft.softness);
        });
    LinearLayout presets=row(box);
    for(int value:new int[]{30,55,85}) weighted(presets,UiKit.secondaryButton(this,
        value==30?"偏硬":value==55?"适中":"很软",()->softnessSlider.setProgress(value)));
    mutedText(box,"重心偏下，松手后会慢慢回正。");
    check(box, "跟随手机重力", draft.gravity, value -> {draft.gravity = value;preview.setGravityEnabled(value);});
    check(box, "固定位置", draft.pinned, value -> {draft.pinned = value;preview.setPinned(value);});
    check(box, "保持安静", draft.muted, value -> draft.muted = value);
    box.addView(UiKit.secondaryButton(this, "打开专属工具", () -> {
      dialog.dismiss(); FeatureActivity.open(this, e.id, uid == null ? "" : uid);
    }));
    if (existing != null) box.addView(UiKit.secondaryButton(this, "收回这只伙伴", () -> {dialog.dismiss(); remove(uid);}));
    Button save = UiKit.button(this, existing == null ? "保存默认设置" : "应用到这只伙伴", () -> {
                  if (existing != null) {
                    PetStore.Instance latest = PetStore.find(this, uid);
                    if (latest == null) { message("这只伙伴已收回，请重新选择。"); dialog.dismiss(); return; }
                    {
                      latest.sizeDp = draft.sizeDp;
                      latest.softness = draft.softness;
                      latest.gravity = draft.gravity;
                      latest.pinned = draft.pinned;
                      latest.muted = draft.muted;
                      PetStore.update(this, latest);
                    }
                  } else
                    PetStore.prefs(this)
                        .edit()
                        .putFloat("default_size_" + e.id, draft.sizeDp)
                        .putFloat("default_softness_" + e.id, draft.softness)
                        .putBoolean("default_gravity_" + e.id, draft.gravity)
                        .putBoolean("default_pinned_" + e.id, draft.pinned)
                        .putBoolean("default_muted_" + e.id, draft.muted)
                        .apply();
                  reload();
                  message(existing == null ? "已保存，以后召唤时使用" : "已应用到这只伙伴");
                  dialog.dismiss();
    });
    sheet.addView(save);
    dialog.setContentView(sheet);
    dialog.setOnDismissListener(d -> { if(detailDialog == dialog) detailDialog = null; });
    Window window = dialog.getWindow();
    if(window != null) {
      window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams attrs = window.getAttributes(); attrs.dimAmount=.32f; window.setAttributes(attrs);
      window.setGravity(Gravity.BOTTOM);
    }
    dialog.show();
    if(window != null) {
      android.graphics.Rect visible = new android.graphics.Rect();
      outer.getWindowVisibleDisplayFrame(visible);
      window.setLayout(Math.min(visible.width(), dp(560)), Math.round(visible.height() * .92f));
    }
  }

  private void summon(String id) {
    if (GengOverlayService.isRunning() && PetStore.loadInstances(this).size() >= 6) {
      message("同时最多放出 6 只，先收回一只吧。");
      return;
    }
    if (!Settings.canDrawOverlays(this)) {
      requestOverlay(id);
      return;
    }
    PetStore.prefs(this).edit().putBoolean("paused", false).apply();
    control(GengOverlayService.ACTION_SHOW, id);
  }

  private void control(String action, String id) {
    if (!Settings.canDrawOverlays(this)) {
      requestOverlay(null);
      return;
    }
    if (!GengOverlayService.ACTION_SHOW.equals(action) && !GengOverlayService.ACTION_RESTORE_PREVIOUS.equals(action) && PetStore.loadInstances(this).isEmpty()) {
      message("先在桌宠馆放出一个伙伴吧。");
      return;
    }
    if (GengOverlayService.ACTION_RELOAD.equals(action) && !GengOverlayService.isRunning())
      PetStore.prefs(this).edit().putBoolean("paused", false).apply();
    Intent i = new Intent(this, GengOverlayService.class).setAction(action);
    if (id != null) i.putExtra("characterId", id);
    try {
      startForegroundService(i);
      handler.removeCallbacks(refreshUi);
      handler.postDelayed(refreshUi, 450);
    } catch (RuntimeException e) {
      message("暂时无法启动，请保持应用在前台后重试。");
    }
  }

  private void requestOverlay(String id) {
    pendingCharacter = id;
    try {
      startActivityForResult(
          new Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
          OVERLAY);
    } catch (RuntimeException e) {
      startActivity(
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + getPackageName())));
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == OVERLAY) {
      String id = pendingCharacter;
      pendingCharacter = null;
      if (id != null && Settings.canDrawOverlays(this)) summon(id);
      else if (!Settings.canDrawOverlays(this)) message("未开启悬浮窗，仍可使用应用内工具。");
    } else if (request == EXPORT && result == RESULT_OK && data != null && data.getData() != null)
      writeExport(data.getData());
  }

  private void remove(String uid) {
    if (GengOverlayService.isRunning())
      try {
        startService(
            new Intent(this, GengOverlayService.class)
                .setAction(GengOverlayService.ACTION_REMOVE)
                .putExtra("instanceId", uid));
      } catch (RuntimeException e) {
        message("收回失败，请重试。");
      }
    else PetStore.remove(this, uid);
    handler.postDelayed(refreshUi, 250);
  }

  private void afterOverlayStopped(Runnable action) {
    stopService(new Intent(this, GengOverlayService.class));
    if (GengOverlayService.isRunning()) {
      handler.postDelayed(() -> afterOverlayStopped(action), 60);
    } else action.run();
  }

  private void recallAll() {
    afterOverlayStopped(
        () -> {
          PetStore.clear(this);
          show();
        });
  }

  private void reload() {
    sendBroadcast(new Intent("com.maowu.gengpet.RELOAD_SETTINGS").setPackage(getPackageName()));
  }

  private void exportData() {
    Intent i =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("application/zip")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(
                Intent.EXTRA_TITLE,
                "梗宠备份-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT)
                        .format(new Date())
                    + ".zip");
    startActivityForResult(i, EXPORT);
  }

  private void writeExport(Uri uri) {
    message("正在导出，请稍候。");
    Context app = getApplicationContext();
    new Thread(
            () -> {
              try {
                try (NotesStore store = new NotesStore(app);
                    OutputStream output = app.getContentResolver().openOutputStream(uri);
                    ZipOutputStream zip = new ZipOutputStream(Objects.requireNonNull(output))) {
                  JSONObject root = new JSONObject();
                  root.put("version", 1);
                  root.put("exportedAt", System.currentTimeMillis());
                  JSONObject settings = new JSONObject();
                  for (Map.Entry<String, ?> pair : PetStore.prefs(app).getAll().entrySet())
                    settings.put(pair.getKey(), JSONObject.wrap(pair.getValue()));
                  root.put("settings", settings);
                  JSONArray notes = new JSONArray();
                  for (NotesStore.Note n : store.list("")) {
                    JSONObject item = new JSONObject();
                    item.put("id", n.id);
                    item.put("title", n.title);
                    item.put("body", n.body);
                    item.put("created", n.created);
                    item.put("updated", n.updated);
                    item.put("duration", n.duration);
                    item.put("audio", n.audio);
                    notes.put(item);
                    if (n.isAudio()) {
                      zip.putNextEntry(new ZipEntry("recordings/" + n.audio));
                      try (InputStream in = new FileInputStream(store.audioFile(n))) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) zip.write(buffer, 0, len);
                      }
                      zip.closeEntry();
                    }
                  }
                  root.put("notes", notes);
                  zip.putNextEntry(new ZipEntry("gengpet.json"));
                  zip.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                  zip.closeEntry();
                }
                handler.post(() -> message("记录、录音和设置已导出。"));
              } catch (Exception e) {
                handler.post(() -> message("导出未完成，请检查目标位置和剩余空间。"));
              }
            },
            "gengpet-export")
        .start();
  }

  private void deleteData() {
    afterOverlayStopped(
        () -> {
          try (NotesStore s = new NotesStore(this)) {
            for (NotesStore.Note n : s.list("")) s.delete(n);
            for (File f : s.drafts()) if (f.exists() && !f.delete()) throw new IOException("draft");
            PetStore.prefs(this).edit().clear().commit();
            ReminderScheduler.reschedule(this);
            message("本机数据已删除");
            show();
          } catch (Exception e) {
            message("部分数据未删除，请停止录音或播放后重试。");
          }
        });
  }

  private void timeSetting(LinearLayout p, String label, String key, int fallback) {
    int m = PetStore.prefs(this).getInt(key, fallback);
    p.addView(
        UiKit.secondaryButton(
            this,
            label + " · " + String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60),
            () ->
                new TimePickerDialog(
                        this,
                        (v, h, min) -> {
                          PetStore.prefs(this).edit().putInt(key, h * 60 + min).apply();
                          reload();
                          show();
                        },
                        m / 60,
                        m % 60,
                        true)
                    .show()));
  }

  private interface BoolChange {
    void set(boolean value);
  }

  private interface IntChange {
    void set(int value);
  }

  private void check(LinearLayout parent, String label, boolean value, BoolChange fn) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextColor(UiKit.INK);
    s.setChecked(value);
    s.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{UiKit.ACCENT,0xFF858D99}));
    s.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xFFBDB6F3,UiKit.LINE}));
    s.setPadding(0, dp(12), 0, dp(12));
    s.setTextSize(15); s.setMinimumHeight(dp(48));
    parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
    s.setOnCheckedChangeListener((b, v) -> fn.set(v));
  }

  private SeekBar slider(
      LinearLayout parent, String label, int min, int max, int current, IntChange fn) {
    TextView t = UiKit.text(this, label + " · " + current, 15);
    parent.addView(t);
    SeekBar s = new SeekBar(this);
    s.setContentDescription(label);
    s.setMax(max - min);
    s.setProgress(current - min);
    s.setProgressTintList(ColorStateList.valueOf(UiKit.ACCENT));
    s.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{UiKit.ACCENT,0xFF858D99}));
    parent.addView(s, new LinearLayout.LayoutParams(-1, dp(48)));
    s.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int n, boolean user) {
            t.setText(label + " · " + (n + min));
            fn.set(n + min);
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}
        });
    return s;
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout c = UiKit.column(this, 14);
    c.setBackground(UiKit.card(this));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(14);
    parent.addView(c, p);
    return c;
  }

  private LinearLayout row(LinearLayout parent) {
    LinearLayout r = new LinearLayout(this);
    r.setOrientation(LinearLayout.HORIZONTAL);
    r.setBaselineAligned(false);
    r.setGravity(Gravity.CENTER_VERTICAL);
    parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
    return r;
  }

  private void weighted(LinearLayout row, View v) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.setMargins(dp(3), dp(5), dp(3), dp(5));
    row.addView(v, p);
  }

  private void space(LinearLayout parent, int height) {
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private int dp(float n) {
    return UiKit.dp(this, n);
  }

  private void message(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private static final class PetThumb extends View implements Runnable {
    final PetCatalog.Entry entry;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final RectF bounds = new RectF();
    final android.graphics.Rect visibleRect = new android.graphics.Rect();
    final android.view.ViewTreeObserver.OnScrollChangedListener scrollListener = this::scheduleAnimation;
    long epoch = SystemClock.uptimeMillis();
    boolean ready, attached;
    boolean animate = true;
    float previewScale = 1, softness = 0;

    PetThumb(Context c, PetCatalog.Entry e) {
      super(c);
      entry = e;
      setContentDescription(e.name + "预览");
      PetCatalog.preload(
          c,
          e,
          () -> {
            ready = true;
            invalidate();
            scheduleAnimation();
          });
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      attached = true;
      getViewTreeObserver().addOnScrollChangedListener(scrollListener);
      scheduleAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
      attached = false;
      if(getViewTreeObserver().isAlive())getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
      removeCallbacks(this);
      super.onDetachedFromWindow();
    }

    private void scheduleAnimation() {
      removeCallbacks(this);
      if (attached && animate
          && !PetStore.prefs(getContext()).getBoolean("low_power", false)
          && getWindowVisibility() == VISIBLE
          && getGlobalVisibleRect(visibleRect)
          && (!entry.animationFrames.isEmpty() || softness > 0))
        postDelayed(this, Math.max(33, entry.frameDurationMs));
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
      super.onWindowVisibilityChanged(visibility);
      if (entry != null) scheduleAnimation();
    }

    @Override
    public void run() {
      if (attached && getWindowVisibility() == VISIBLE) {
        invalidate();
        scheduleAnimation();
      }
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (!ready) return;
      String path =
          (!animate || PetStore.prefs(getContext()).getBoolean("low_power", false) || entry.animationFrames.isEmpty())
              ? entry.idle
              : entry.animationFrame(SystemClock.uptimeMillis() - epoch);
      Bitmap b = PetCatalog.bitmapIfReady(path);
      if (b == null) return;
      float size = Math.min(getWidth(), getHeight()) * .94f * Math.min(1.35f, previewScale);
      float cx = getWidth() / 2f, cy = getHeight() / 2f;
      canvas.save();
      float deform =
          .035f * softness * (float) Math.sin((SystemClock.uptimeMillis() - epoch) / 250.0);
      canvas.scale(1 + deform, 1 - deform, cx, cy);
      bounds.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
      canvas.drawBitmap(b, null, bounds, paint);
      canvas.restore();
    }
  }
}
