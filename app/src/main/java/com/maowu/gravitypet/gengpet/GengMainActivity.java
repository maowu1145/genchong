package com.maowu.gravitypet.gengpet;

import android.Manifest;
import android.app.Activity;
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
    }
    getWindow().setStatusBarColor(UiKit.BG);
    getWindow().setNavigationBarColor(UiKit.BG);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    show();
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
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  private void show() {
    outer = UiKit.column(this, 0);
    outer.setBackgroundColor(UiKit.BG);
    setContentView(outer);
    outer.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    ScrollView sc = new ScrollView(this);
    sc.setFillViewport(true);
    outer.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
    body = UiKit.column(this, 20);
    sc.addView(body);
    if (tab == 0) gallery();
    else if (tab == 1) tools();
    else settings();
    LinearLayout nav = row(outer);
    nav.setPadding(dp(12), dp(6), dp(12), dp(6));
    String[] labels = {"桌宠馆", "工具与记录", "设置"};
    for (int i = 0; i < labels.length; i++) {
      final int selected = i;
      Button b =
          UiKit.button(
              this,
              labels[i],
              () -> {
                tab = selected;
                show();
              });
      if (i != tab) b.setBackground(UiKit.card(this));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
      lp.setMargins(dp(3), 0, dp(3), 0);
      nav.addView(b, lp);
    }
  }

  private void title(String eyebrow, String heading, String detail) {
    TextView small = UiKit.text(this, eyebrow, 13);
    body.addView(small);
    TextView h = UiKit.heading(this, heading, 29);
    h.setPadding(0, dp(6), 0, dp(8));
    body.addView(h);
    if (!detail.isEmpty()) body.addView(UiKit.text(this, detail, 15));
    space(body, 16);
  }

  private void gallery() {
    title("梗宠  /  陪你过日常", "把快乐，放到屏幕上。", "拖一拖、晃一晃，长按打开它的小工具。");
    body.addView(new PlaygroundView(this), new LinearLayout.LayoutParams(-1, dp(160)));
    body.addView(UiKit.text(this, "试着拖动上面的 Doro，松手看看回弹。", 13));
    space(body, 16);
    ArrayList<PetStore.Instance> active = PetStore.loadInstances(this);
    LinearLayout status = card(body);
    status.addView(UiKit.heading(this, (GengOverlayService.isRunning()?"屏幕上的伙伴 · ":"已收起的伙伴 · ") + active.size() + " / 6", 18));
    if (!Settings.canDrawOverlays(this)) {
      status.addView(UiKit.text(this, "先允许悬浮窗，桌宠就能陪你使用其他应用。", 14));
      status.addView(UiKit.button(this, "开启悬浮窗权限", () -> requestOverlay(null)));
    } else if (active.isEmpty()) status.addView(UiKit.text(this, "挑一只喜欢的，点「放到屏幕上」。", 14));
    else {
      String state =
          GengOverlayService.isRunning()
              ? (GengOverlayService.isPaused() ? "已暂停，位置保留" : "正在陪伴你")
              : "已收起，之前的选择已保存";
      status.addView(UiKit.text(this, state, 14));
      LinearLayout controls = row(status);
      weighted(
          controls,
          UiKit.button(
              this,
              GengOverlayService.isRunning()
                  ? (GengOverlayService.isPaused() ? "继续陪伴" : "全部暂停")
                  : "恢复桌宠",
              () ->
                  control(
                      !GengOverlayService.isRunning()
                          ? GengOverlayService.ACTION_RELOAD
                          : (GengOverlayService.isPaused()
                              ? GengOverlayService.ACTION_RESUME
                              : GengOverlayService.ACTION_PAUSE),
                      null)));
      weighted(controls, UiKit.button(this, "收回全部", this::recallAll));
      for (PetStore.Instance instance : active) {
        PetCatalog.Entry e = PetCatalog.get(this, instance.characterId);
        if (e == null) continue;
        LinearLayout line = row(status);
        TextView name = UiKit.text(this, e.name + (instance.pinned ? " · 已固定" : ""), 14);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button tune = UiKit.button(this, "调节", () -> detail(e, instance.uid));
        line.addView(tune, new LinearLayout.LayoutParams(dp(66), dp(40)));
        Button remove = UiKit.button(this, "收回", () -> remove(instance.uid));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(66), dp(40));
        p.leftMargin = dp(5);
        line.addView(remove, p);
      }
    }
    if(PetStore.previousCount(this)>0) status.addView(UiKit.button(this,
        "恢复上一组桌宠 · "+PetStore.previousCount(this)+" 只",
        ()->control(GengOverlayService.ACTION_RESTORE_PREVIOUS,null)));
    space(body, 16);
    EditText search = new EditText(this);
    search.setSingleLine(true);
    search.setHint("找一个角色或小工具");
    search.setTextColor(UiKit.INK);
    search.setHintTextColor(UiKit.INK);
    search.setBackground(UiKit.card(this));
    search.setPadding(dp(14), dp(10), dp(14), dp(10));
    search.setText(query);
    body.addView(search, new LinearLayout.LayoutParams(-1, dp(50)));
    LinearLayout filters = row(body);
    allFilter =
        UiKit.button(
            this,
            "全部角色",
            () -> {
              favoritesOnly = false;
              renderCards();
            });
    favoriteFilter =
        UiKit.button(
            this,
            "我的收藏",
            () -> {
              favoritesOnly = true;
              renderCards();
            });
    weighted(filters, allFilter);
    weighted(filters, favoriteFilter);
    cards = UiKit.column(this, 0);
    body.addView(cards);
    renderCards();
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            query = s.toString();
            renderCards();
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  private void renderCards() {
    if (cards == null) return;
    allFilter.setText(favoritesOnly ? "全部角色" : "全部角色 ✓");
    favoriteFilter.setText(favoritesOnly ? "我的收藏 ✓" : "我的收藏");
    cards.removeAllViews();
    int matches = 0;
    for (PetCatalog.Entry e : PetCatalog.all(this)) {
      if (favoritesOnly && !favorite(e.id)) continue;
      if (!query.isEmpty()
          && !(e.name + e.description)
              .toLowerCase(Locale.ROOT)
              .contains(query.toLowerCase(Locale.ROOT))) continue;
      matches++;
      LinearLayout card = card(cards);
      LinearLayout top = row(card);
      PetThumb thumb = new PetThumb(this, e);
      top.addView(thumb, new LinearLayout.LayoutParams(dp(112), dp(112)));
      LinearLayout info = UiKit.column(this, 10);
      top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
      info.addView(UiKit.heading(this, e.name, 21));
      info.addView(UiKit.text(this, e.description, 14));
      LinearLayout actions = row(card);
      weighted(actions, UiKit.button(this, "放到屏幕上", () -> summon(e.id)));
      weighted(actions, UiKit.button(this, "看看 / 调节", () -> detail(e, null)));
      Button fav =
          UiKit.button(
              this,
              favorite(e.id) ? "已收藏 · 取消收藏" : "收藏这个伙伴",
              () -> {
                PetStore.prefs(this).edit().putBoolean("favorite_" + e.id, !favorite(e.id)).apply();
                renderCards();
              });
      fav.setBackground(UiKit.card(this));
      card.addView(fav);
    }
    if (matches == 0)
      cards.addView(UiKit.text(this, favoritesOnly ? "这里还没有收藏，先去全部角色看看吧。" : "没有找到，换个关键词试试。", 16));
  }

  private boolean favorite(String id) {
    return PetStore.prefs(this).getBoolean("favorite_" + id, false);
  }

  private void tools() {
    title("小工具", "有事，找它们帮忙。", "提醒、记录、心情与称谓，都留在这台手机里。");
    for (PetCatalog.Entry e : PetCatalog.all(this)) {
      LinearLayout box = card(body);
      LinearLayout line = row(box);
      PetThumb thumb = new PetThumb(this, e);
      line.addView(thumb, new LinearLayout.LayoutParams(dp(78), dp(78)));
      LinearLayout texts = UiKit.column(this, 6);
      line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
      texts.addView(UiKit.heading(this, e.name, 19));
      texts.addView(UiKit.text(this, e.description, 14));
      box.addView(UiKit.button(this, "打开小工具", () -> FeatureActivity.open(this, e.id, "")));
    }
  }

  private void settings() {
    title("陪伴设置", "按你的节奏来。", "随时暂停，随时把它们收起来。");
    LinearLayout controls = card(body);
    controls.addView(
        UiKit.button(
            this,
            GengOverlayService.isRunning() ? "暂停 / 继续所有桌宠" : "恢复上次的桌宠",
            () ->
                control(
                    !GengOverlayService.isRunning()
                        ? GengOverlayService.ACTION_RELOAD
                        : (GengOverlayService.isPaused()
                            ? GengOverlayService.ACTION_RESUME
                            : GengOverlayService.ACTION_PAUSE),
                    null)));
    controls.addView(
        UiKit.button(
            this,
            "关闭悬浮桌宠",
            () -> {
              stopService(new Intent(this, GengOverlayService.class));
              handler.postDelayed(refreshUi, 180);
            }));
    controls.addView(UiKit.button(this, "收回全部伙伴", this::recallAll));
    LinearLayout prefs = card(body);
    prefs.addView(UiKit.heading(this, "节奏与安静时段", 18));
    check(
        prefs,
        "低功耗模式",
        PetStore.prefs(this).getBoolean("low_power", false),
        value -> {
          PetStore.prefs(this).edit().putBoolean("low_power", value).apply();
          reload();
        });
    prefs.addView(UiKit.text(this, "低功耗会减少动效；熄屏和暂停时停止动画与不必要的传感器采样。", 13));
    timeSetting(prefs, "安静开始", "quiet_start", 1320);
    timeSetting(prefs, "安静结束", "quiet_end", 480);
    prefs.addView(UiKit.text(this, "安静时段减少大幅动作；两个时间相同表示关闭。提醒有各自的时间设置。", 13));
    LinearLayout permissions = card(body);
    permissions.addView(UiKit.heading(this, "权限", 18));
    permissions.addView(
        UiKit.text(this, "悬浮窗：" + (Settings.canDrawOverlays(this) ? "已允许" : "未允许"), 15));
    permissions.addView(UiKit.button(this, "管理悬浮窗权限", () -> requestOverlay(null)));
    boolean notification =
        Build.VERSION.SDK_INT < 33
            || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    permissions.addView(UiKit.text(this, "通知：" + (notification ? "已允许" : "未允许"), 15));
    permissions.addView(
        UiKit.button(
            this,
            "管理通知权限",
            () -> {
              if (Build.VERSION.SDK_INT >= 33 && !notification)
                requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
              else
                startActivity(
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }));
    permissions.addView(UiKit.text(this, "麦克风在首次录音时申请；不需要无障碍、定位或读取其他应用内容。", 13));
    LinearLayout data = card(body);
    data.addView(UiKit.heading(this, "本机数据", 18));
    data.addView(UiKit.button(this, "导出记录与设置", this::exportData));
    data.addView(
        UiKit.button(
            this,
            "删除本机全部数据",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("删除所有记录和设置？")
                    .setMessage("文字、录音、收藏和桌宠设置会从这台手机删除。建议先导出一份备份。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (d, w) -> deleteData())
                    .show()));
    LinearLayout about = card(body);
    about.addView(UiKit.heading(this, "梗宠 · 0.1", 18));
    about.addView(UiKit.text(this, "当前有 6 个核心伙伴。乘车舒适辅助是可随时停止的实验功能，供乘客选择使用。", 14));
    about.addView(UiKit.text(this, "有些系统页面会隐藏悬浮窗；回到允许覆盖的应用后可恢复。", 13));
  }

  private void detail(PetCatalog.Entry e, String uid) {
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
    ScrollView scroll = new ScrollView(this);
    LinearLayout box = UiKit.column(this, 18);
    scroll.addView(box);
    PlaygroundView preview = new PlaygroundView(this, e);
    preview.setPetSize(draft.sizeDp);
    preview.setSoftness(draft.softness);
    preview.setGravityEnabled(draft.gravity);
    preview.setPinned(draft.pinned);
    box.addView(preview, new LinearLayout.LayoutParams(-1, dp(170)));
    box.addView(UiKit.text(this, e.description, 15));
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
    for(int value:new int[]{30,55,85}) weighted(presets,UiKit.button(this,
        value==30?"偏硬":value==55?"适中":"很软",()->softnessSlider.setProgress(value)));
    box.addView(UiKit.text(this,"上方可以拖拽试手感；重心偏下，松手后会慢慢回正。",13));
    check(box, "跟随手机重力", draft.gravity, value -> {draft.gravity = value;preview.setGravityEnabled(value);});
    check(box, "固定位置", draft.pinned, value -> {draft.pinned = value;preview.setPinned(value);});
    check(box, "保持安静", draft.muted, value -> draft.muted = value);
    box.addView(
        UiKit.button(
            this, "打开专属工具", () -> FeatureActivity.open(this, e.id, uid == null ? "" : uid)));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(e.name)
            .setView(scroll)
            .setNegativeButton("返回", null)
            .setPositiveButton(
                existing == null ? "保存默认设置" : "应用到这只",
                (d, w) -> {
                  if (existing != null) {
                    PetStore.Instance latest = PetStore.find(this, uid);
                    if (latest != null) {
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
                  message("已保存");
                })
            .create();
    dialog.show();
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
        UiKit.button(
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
    s.setThumbTintList(ColorStateList.valueOf(UiKit.INK));
    s.setTrackTintList(ColorStateList.valueOf(UiKit.ACCENT));
    s.setPadding(0, dp(12), 0, dp(12));
    parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
    s.setOnCheckedChangeListener((b, v) -> fn.set(v));
  }

  private SeekBar slider(
      LinearLayout parent, String label, int min, int max, int current, IntChange fn) {
    TextView t = UiKit.text(this, label + " · " + current, 15);
    parent.addView(t);
    SeekBar s = new SeekBar(this);
    s.setMax(max - min);
    s.setProgress(current - min);
    s.setProgressTintList(ColorStateList.valueOf(UiKit.ACCENT));
    s.setThumbTintList(ColorStateList.valueOf(UiKit.INK));
    parent.addView(s, new LinearLayout.LayoutParams(-1, dp(44)));
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
      if (attached
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
          entry.animationFrames.isEmpty()
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
