package com.maowu.gravitypet.gengpet;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/** Visible tool surface. Inputs and microphone never live inside an invisible overlay. */
public final class FeatureActivity extends Activity {
  public static final int MIC_PERMISSION = 501, NOTIFICATION_PERMISSION = 502;
  String instanceId = "";
  private String characterId = "";
  private LinearLayout content;
  private NotesPanel notes;
  private final KinshipEngine.Chain chain = new KinshipEngine.Chain();
  private KinshipEngine.Sex sex = KinshipEngine.Sex.UNKNOWN;
  private String selectedMood = "sweating";
  private android.media.ToneGenerator celebrationTone;

  public static void open(Context context, String characterId, String instanceId) {
    Intent i =
        new Intent(context, FeatureActivity.class)
            .putExtra("characterId", characterId)
            .putExtra("instanceId", instanceId == null ? "" : instanceId);
    if (!(context instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(i);
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    characterId = getIntent().getStringExtra("characterId");
    instanceId = getIntent().getStringExtra("instanceId");
    if (instanceId == null) instanceId = "";
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    getWindow().setStatusBarColor(UiKit.BG);
    getWindow().setNavigationBarColor(UiKit.BG);
    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(UiKit.BG));
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (state != null) {
      characterId = state.getString("characterId", characterId);
      instanceId = state.getString("instanceId", instanceId);
      String[] saved = state.getStringArray("chain");
      if (saved != null) for (String token : saved) chain.add(token);
      sex = KinshipEngine.Sex.valueOf(state.getString("sex", "UNKNOWN"));
    }
    ReminderScheduler.reschedule(this);
    show();
  }

  private void show() {
    if (notes != null) {
      notes.close();
      notes = null;
    }
    LinearLayout outer = UiKit.column(this, 16);
    outer.setBackgroundColor(UiKit.BG);
    LinearLayout toolbar = new LinearLayout(this);
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton back = new ImageButton(this);
    back.setImageDrawable(UiKit.icon(this, "arrow_back", UiKit.INK));
    back.setContentDescription("返回小工具");
    back.setBackground(UiKit.ripple(this,0x00000000,8));
    back.setOnClickListener(v -> finish());
    toolbar.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
    LinearLayout title = UiKit.column(this, 0);
    title.setPadding(UiKit.dp(this, 12), 0, 0, 0);
    title.addView(UiKit.heading(this, "小工具", 18));
    TextView subtitle = UiKit.text(this, "记录保存在本机", 12);
    subtitle.setTextColor(UiKit.MUTED);
    title.addView(subtitle);
    toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(-1, -2);
    toolbarParams.bottomMargin = UiKit.dp(this, 16);
    outer.addView(toolbar, toolbarParams);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    content = UiKit.column(this, 0);
    scroll.addView(content);
    setContentView(outer);
    // Respect system bars and the keyboard even on target-36 edge-to-edge windows.
    outer.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int p = UiKit.dp(this, 16);
          v.setPadding(
              p + insets.getSystemWindowInsetLeft(),
              p + insets.getSystemWindowInsetTop(),
              p + insets.getSystemWindowInsetRight(),
              p + insets.getSystemWindowInsetBottom());
          return insets;
        });
    if ("doro".equals(characterId)) showReminders();
    else if ("grudge_panda".equals(characterId)) {
      notes = new NotesPanel(this);
      notes.show(content);
    } else if ("spinning_cat".equals(characterId)) showComfort();
    else if ("sweat_emoji".equals(characterId)) showMood();
    else if ("kinship_ride".equals(characterId)) showKinship();
    else if ("thursday_bucket_cat".equals(characterId)) showThursday();
    else showTools();
  }

  private void showTools() {
    FeatureUi.text(this, content, "工具与记录", 24);
    String[] ids = {
      "doro", "grudge_panda", "spinning_cat", "sweat_emoji", "kinship_ride", "thursday_bucket_cat"
    };
    String[] names = {"喝水 / 水果提醒", "文字与录音", "乘车舒适辅助", "黄豆心情", "辈分计算器", "星期四状态"};
    for (int i = 0; i < ids.length; i++) {
      final String id = ids[i];
      FeatureUi.button(
          this,
          content,
          names[i],
          () -> {
            characterId = id;
            instanceId = "";
            show();
          });
    }
  }

  private void showReminders() {
    FeatureUi.text(this, content, "喝水与水果提醒", 24);
    FeatureUi.text(this, content, "两个提醒独立保存。系统节电可能延迟；过期提醒不会集中补发。默认仅在桌宠显示时反馈，后台通知需主动开启。", 15);
    FeatureUi.text(
        this,
        content,
        ReminderReceiver.notificationsAllowed(this) ? "系统通知：已允许" : "系统通知：未允许或已关闭；桌宠气泡仍可使用",
        15);
    FeatureUi.button(
        this,
        content,
        "开启 / 管理通知权限",
        () -> {
          if (Build.VERSION.SDK_INT >= 33
              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(
                new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
          else
            startActivity(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
    reminderCard(ReminderScheduler.WATER, "喝水");
    reminderCard(ReminderScheduler.FRUIT, "水果");
  }

  private void reminderCard(String kind, String label) {
    ReminderRules.Config config = ReminderScheduler.config(this, kind);
    LinearLayout card = UiKit.column(this, 12);
    card.setBackground(UiKit.card(this));
    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
    cardParams.topMargin = UiKit.dp(this, 12);
    cardParams.bottomMargin = UiKit.dp(this, 4);
    content.addView(card, cardParams);
    FeatureUi.text(this, card, label + "提醒", 20);
    CheckBox enabled = FeatureUi.check(this, card, "启用" + label + "提醒", config.enabled);
    CheckBox daily = FeatureUi.check(this, card, "每天指定时刻（关闭则按间隔）", config.daily);
    CheckBox background = FeatureUi.check(this, card, "关闭桌宠后仍发送通知", config.background);
    EditText interval =
        FeatureUi.input(this, card, "间隔分钟，15—720", Integer.toString(config.intervalMinutes), 3);
    interval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    timeButton(card, "每天时刻", config.dailyMinute, v -> config.dailyMinute = v);
    timeButton(card, "开始时刻", config.startMinute, v -> config.startMinute = v);
    timeButton(card, "结束时刻", config.endMinute, v -> config.endMinute = v);
    timeButton(card, "安静开始", config.quietStart, v -> config.quietStart = v);
    timeButton(card, "安静结束", config.quietEnd, v -> config.quietEnd = v);
    FeatureUi.text(this, card, "开始与结束相同表示全天；安静开始与结束相同表示不设置安静时段。", 13);
    EditText snooze =
        FeatureUi.input(this, card, "稍后分钟，1—120", Integer.toString(config.snoozeMinutes), 3);
    snooze.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    long due = ReminderScheduler.nextDue(this, kind);
    FeatureUi.text(
        this,
        card,
        due > 0
            ? "下一次约在 "
                + DateTimeFormatter.ofPattern("MM-dd HH:mm")
                    .format(Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()))
            : "目前没有待执行提醒",
        14);
    FeatureUi.button(
        this,
        card,
        "保存" + label + "配置",
        () -> {
          try {
            int minutes = Integer.parseInt(interval.getText().toString()),
                later = Integer.parseInt(snooze.getText().toString());
            if (minutes < 15 || minutes > 720 || later < 1 || later > 120)
              throw new IllegalArgumentException();
            config.enabled = enabled.isChecked();
            config.daily = daily.isChecked();
            config.background = background.isChecked();
            config.intervalMinutes = minutes;
            config.snoozeMinutes = later;
            if (config.enabled
                && ReminderRules.next(config, System.currentTimeMillis(), ZoneId.systemDefault())
                    < 0) {
              FeatureUi.message(this, "时刻不在可提醒时段内，请调整时段或安静配置");
              return;
            }
            ReminderScheduler.save(this, kind, config);
            FeatureUi.message(
                this,
                "已保存"
                    + (config.background && !ReminderReceiver.notificationsAllowed(this)
                        ? "；系统通知尚未允许"
                        : ""));
            show();
          } catch (NumberFormatException e) {
            FeatureUi.message(this, "请填写数字分钟");
          } catch (IllegalArgumentException e) {
            FeatureUi.message(this, "间隔为 15—720 分钟，稍后为 1—120 分钟");
          }
        });
    long pending = FeaturePreferences.prefs(this).getLong("reminder_" + kind + "_pending", 0);
    if (pending > 0 && System.currentTimeMillis() - pending <= 2 * 60 * 60 * 1000L) {
      FeatureUi.text(this, card, "处理最近一次" + label + "提醒", 16);
      LinearLayout row = FeatureUi.row(this, card);
      FeatureUi.rowButton(this, row, "记住了", () -> respond(kind, "done"));
      FeatureUi.rowButton(this, row, "稍后", () -> respond(kind, "snooze"));
      FeatureUi.rowButton(this, row, "今天不再", () -> respond(kind, "today"));
    }
  }

  private void respond(String kind, String action) {
    ReminderScheduler.respond(this, kind, action);
    show();
  }

  private interface MinuteValue {
    void set(int value);
  }

  private void timeButton(LinearLayout parent, String label, int initial, MinuteValue value) {
    final int[] minute = {initial};
    Button b = FeatureUi.button(this, parent, label + " · " + time(initial), () -> {});
    b.setOnClickListener(
        v ->
            new TimePickerDialog(
                    this,
                    (picker, h, m) -> {
                      minute[0] = h * 60 + m;
                      value.set(minute[0]);
                      b.setText(label + " · " + time(minute[0]));
                    },
                    minute[0] / 60,
                    minute[0] % 60,
                    true)
                .show());
  }

  private static String time(int minute) {
    return String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
  }

  private void showComfort() {
    FeatureUi.text(this, content, "乘车舒适辅助 · 实验性", 24);
    FeatureUi.text(this, content, "仅供乘客使用。低强度边缘运动提示，不保证改善晕车；出现不适请立即停止。先从主页面放出桌宠，再开启可看到边缘提示。", 16);
    CheckBox on =
        FeatureUi.check(
            this,
            content,
            "开启轻量边缘提示",
            FeaturePreferences.prefs(this).getBoolean("comfort_enabled", false));
    on.setOnCheckedChangeListener(
        (b, enabled) -> {
          FeaturePreferences.prefs(this).edit().putBoolean("comfort_enabled", enabled).apply();
          FeaturePreferences.reload(this);
        });
    slider("强度", "comfort_strength", .35f);
    slider("透明度", "comfort_opacity", .35f);
    FeatureUi.button(
        this,
        content,
        "重置参考方向",
        () -> {
          FeaturePreferences.prefs(this)
              .edit()
              .putLong("comfort_reset_token", System.currentTimeMillis())
              .apply();
          FeaturePreferences.reload(this);
          FeatureUi.message(this, "已请求重新校准参考方向");
        });
    FeatureUi.button(
        this,
        content,
        "立即停止",
        () -> {
          on.setChecked(false);
          FeaturePreferences.prefs(this).edit().putBoolean("comfort_enabled", false).apply();
          FeaturePreferences.reload(this);
        });
    android.hardware.SensorManager sm = getSystemService(android.hardware.SensorManager.class);
    if (sm == null
        || (sm.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION) == null
            && sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) == null))
      FeatureUi.text(this, content, "本设备没有可用运动传感器，不能显示动态提示。", 15);
  }

  private void slider(String label, String key, float fallback) {
    TextView t = FeatureUi.text(this, content, label, 17);
    SeekBar bar = new SeekBar(this);
    bar.setContentDescription(label);
    bar.setMax(100);
    bar.setProgress(Math.round(FeaturePreferences.prefs(this).getFloat(key, fallback) * 100));
    bar.setProgressTintList(ColorStateList.valueOf(UiKit.ACCENT));
    bar.setThumbTintList(ColorStateList.valueOf(UiKit.ACCENT));
    content.addView(bar, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
    t.setText(label + " " + bar.getProgress() + "%");
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}

          public void onProgressChanged(SeekBar b, int value, boolean user) {
            t.setText(label + " " + value + "%");
            if (user) {
              FeaturePreferences.prefs(FeatureActivity.this)
                  .edit()
                  .putFloat(key, value / 100f)
                  .apply();
              FeaturePreferences.reload(FeatureActivity.this);
            }
          }
        });
  }

  private void showMood() {
    FeatureUi.text(this, content, "黄豆心情", 24);
    ArrayList<PetStore.Instance> beans = new ArrayList<>();
    for (PetStore.Instance p : PetStore.loadInstances(this))
      if ("sweat_emoji".equals(p.characterId)) beans.add(p);
    PetStore.Instance selected = PetStore.find(this, instanceId);
    if (selected == null || !"sweat_emoji".equals(selected.characterId)) {
      selected = beans.isEmpty() ? null : beans.get(0);
      if (selected != null) instanceId = selected.uid;
    }
    if (beans.size() > 1) {
      FeatureUi.text(this, content, "选择要修改的黄豆实例", 15);
      for (int i = 0; i < beans.size(); i++) {
        PetStore.Instance bean = beans.get(i);
        FeatureUi.button(
            this,
            content,
            "黄豆 " + (i + 1) + (bean.uid.equals(instanceId) ? " · 当前" : ""),
            () -> {
              instanceId = bean.uid;
              show();
            });
      }
    }
    selectedMood =
        selected == null
            ? FeaturePreferences.prefs(this).getString("mood_default", "sweating")
            : selected.mood;
    if ("wronged".equals(selectedMood)) selectedMood = "sad";
    String text =
        selected == null
            ? FeaturePreferences.prefs(this).getString("mood_text_default", "")
            : selected.moodText;
    FeatureUi.text(this, content, selected == null ? "当前没有黄豆，设置会作为新黄豆的默认心情。" : "心情只改变当前选中的黄豆。", 15);
    TextView label = FeatureUi.text(this, content, "当前：" + moodName(selectedMood), 18);
    EditText moodText = FeatureUi.input(this, content, "心情文字，最多 12 字", text, 12);
    for (int i = 0; i < 8; i += 2) {
      LinearLayout moodRow = FeatureUi.row(this, content);
      for (int j = i; j < Math.min(i + 2, 8); j++) {
        final String mood = FeaturePreferences.MOODS[j];
        FeatureUi.rowButton(
            this,
            moodRow,
            FeaturePreferences.MOOD_NAMES[j],
            () -> {
              selectedMood = mood;
              label.setText("当前：" + moodName(mood));
              persistMood(moodText.getText().toString());
            });
      }
    }
    FeatureUi.button(
        this,
        content,
        "保存心情文字",
        () -> {
          persistMood(moodText.getText().toString());
          FeatureUi.message(this, "心情已保存");
        });
  }

  private String moodName(String mood) {
    for (int i = 0; i < 8; i++)
      if (FeaturePreferences.MOODS[i].equals(mood)) return FeaturePreferences.MOOD_NAMES[i];
    return "流汗";
  }

  private void persistMood(String text) {
    PetStore.Instance current = PetStore.find(this, instanceId);
    if (current != null && "sweat_emoji".equals(current.characterId)) {
      current.mood = selectedMood;
      current.moodText = text;
      PetStore.update(this, current);
    } else
      FeaturePreferences.prefs(this)
          .edit()
          .putString("mood_default", selectedMood)
          .putString("mood_text_default", text)
          .apply();
    FeaturePreferences.reload(this);
    FeaturePreferences.feedback(
        this,
        "sweat_emoji",
        instanceId,
        selectedMood,
        text.isEmpty() ? moodName(selectedMood) : text);
  }

  private void showKinship() {
    FeatureUi.text(this, content, "辈分计算器", 24);
    FeatureUi.text(this, content, "普通话常见称谓；默认同父同母兄弟姐妹及当前婚姻关系。收养、重组家庭、地区称谓需另行确认。年龄不明时保留候选。", 15);
    LinearLayout gender = FeatureUi.row(this, content);
    for (KinshipEngine.Sex option : KinshipEngine.Sex.values()) {
      String label =
          option == KinshipEngine.Sex.UNKNOWN
              ? "性别未知"
              : option == KinshipEngine.Sex.MALE ? "我是男" : "我是女";
      FeatureUi.rowButton(
          this,
          gender,
          label + (sex == option ? " ✓" : ""),
          () -> {
            sex = option;
            show();
          });
    }
    FeatureUi.text(this, content, KinshipEngine.describe(chain.values()), 20);
    for (int i = 0; i < KinshipEngine.TOKENS.length; i += 2) {
      LinearLayout row = FeatureUi.row(this, content);
      for (int j = i; j < Math.min(i + 2, KinshipEngine.TOKENS.length); j++) {
        String token = KinshipEngine.TOKENS[j];
        FeatureUi.rowButton(
            this,
            row,
            KinshipEngine.LABELS[j],
            () -> {
              if (!chain.add(token)) FeatureUi.message(this, "最多 8 步，请先撤回或清空");
              show();
            });
      }
    }
    LinearLayout row = FeatureUi.row(this, content);
    FeatureUi.rowButton(
        this,
        row,
        "撤回",
        () -> {
          chain.undo();
          show();
        });
    FeatureUi.rowButton(
        this,
        row,
        "清空",
        () -> {
          chain.clear();
          show();
        });
    TextView result = FeatureUi.text(this, content, "选择关系后点击计算", 20);
    FeatureUi.button(
        this,
        content,
        "计算称谓",
        () -> {
          KinshipEngine.Result answer = KinshipEngine.calculate(sex, chain.values());
          result.setText(answer.text);
          FeaturePreferences.feedback(this, "kinship_ride", instanceId, "result", answer.text);
        });
  }

  private void showThursday() {
    FeatureUi.text(this, content, "星期四状态", 24);
    FeatureUi.text(this, content, ThursdayRules.status(), 22);
    FeatureUi.text(this, content, "按手机当前时区判断。周四首次出现时短暂庆祝，当天多个实例和重启不会重复首次提醒。", 16);
    FeatureUi.text(
        this,
        content,
        ThursdayRules.quiet(this)
            ? "当前：安静一会"
            : "当前：" + (ThursdayRules.isThursday() ? "兴奋" : "平日陪伴"),
        16);
    FeatureUi.button(
        this,
        content,
        "再庆祝一下",
        () -> {
          if (!ThursdayRules.isThursday()) {
            FeatureUi.message(this, "今天不是周四，正常状态保持不变");
            return;
          }
          FeaturePreferences.prefs(this).edit().remove("thursday_quiet_until").apply();
          FeaturePreferences.feedback(this, "thursday_bucket_cat", instanceId, "crazy", "星期四到啦！");
          FeaturePreferences.reload(this);
          if (!FeaturePreferences.prefs(this).getBoolean("thursday_muted", true))
            playCelebrationTone();
        });
    FeatureUi.button(
        this,
        content,
        "安静一会（1 小时）",
        () -> {
          ThursdayRules.quietForHour(this);
          show();
        });
    CheckBox mute =
        FeatureUi.check(
            this,
            content,
            "关闭庆祝声音",
            FeaturePreferences.prefs(this).getBoolean("thursday_muted", true));
    mute.setOnCheckedChangeListener(
        (b, value) -> {
          FeaturePreferences.prefs(this).edit().putBoolean("thursday_muted", value).apply();
          FeaturePreferences.reload(this);
        });
    FeatureUi.text(this, content, "关闭静音后，手动庆祝会有一声短提示音；不会循环播放歌曲。", 13);
    FeatureUi.button(
        this,
        content,
        "刷新本地日期",
        () -> {
          ThursdayRules.refresh(this);
          show();
        });
  }

  private void playCelebrationTone() {
    releaseTone();
    try {
      celebrationTone =
          new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 25);
      celebrationTone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 180);
      new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::releaseTone, 250);
    } catch (RuntimeException e) {
      releaseTone();
    }
  }

  private void releaseTone() {
    if (celebrationTone != null) {
      celebrationTone.release();
      celebrationTone = null;
    }
  }

  @Override
  protected void onPause() {
    if (notes != null) notes.pause();
    releaseTone();
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (notes != null) notes.resume();
    if ("thursday_bucket_cat".equals(characterId) && content != null) {
      content.removeAllViews();
      showThursday();
    }
  }

  @Override
  protected void onDestroy() {
    if (notes != null) {
      notes.close();
      notes = null;
    }
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putString("characterId", characterId);
    out.putString("instanceId", instanceId);
    out.putStringArray("chain", chain.values().toArray(new String[0]));
    out.putString("sex", sex.name());
    super.onSaveInstanceState(out);
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == MIC_PERMISSION) {
      FeatureUi.message(
          this,
          results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
              ? "麦克风已允许，请点击开始录音"
              : "未获得麦克风权限，仍可文字记录；可在应用设置中恢复");
    } else if (request == NOTIFICATION_PERMISSION) {
      show();
    }
  }
}
