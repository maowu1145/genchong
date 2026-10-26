package com.maowu.gravitypet.gengpet;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One non-touchable render layer, with bounded transparent touch handles as in boxob. */
public final class GengOverlayService extends Service implements PetSpriteView.Interaction {
  public static final String ACTION_SHOW = "gengpet.SHOW",
      ACTION_REMOVE = "gengpet.REMOVE",
      ACTION_PAUSE = "gengpet.PAUSE",
      ACTION_RESUME = "gengpet.RESUME",
      ACTION_STOP = "gengpet.STOP",
      ACTION_RELOAD = "gengpet.RELOAD",
      ACTION_RESTORE_PREVIOUS = "gengpet.RESTORE_PREVIOUS",
      ACTION_COMFORT = "gengpet.COMFORT";
  public static final String ACTION_FEEDBACK = "com.maowu.gengpet.FEEDBACK",
      ACTION_RELOAD_SETTINGS = "com.maowu.gengpet.RELOAD_SETTINGS";
  private static final String CHANNEL = "gengpet_overlay";
  private static volatile boolean running, paused;
  private static volatile int activeCount;
  private static volatile Set<String> feedbackTargets = Collections.emptySet();

  public static boolean isRunning() {
    return running;
  }

  public static boolean isPaused() {
    return paused;
  }

  public static int count() {
    return activeCount;
  }

  /**
   * Read-only readiness; never creates/restarts a service. Business reminders may use this before
   * consuming their pending timestamp, including when called from another Activity.
   */
  public static boolean canReceiveFeedback(String characterId) {
    return running && !paused && feedbackTargets.contains(characterId);
  }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final PhysicsWorld world = new PhysicsWorld();
  private final LinkedHashMap<String, Slot> slots = new LinkedHashMap<>();
  private final Set<View> registeredWindows=Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  private WindowManager windows;
  private DisplayManager displays;
  private MotionSensor motion;
  private AppOpsManager appOps;
  private SharedPreferences prefs;
  private Rect safe = new Rect(0, 0, 1080, 1920);
  private int screenWidth = 1080, screenHeight = 1920;
  private float density, cueX, cueY, comfortStrength = .35f, comfortOpacity = .35f;
  private boolean screenOn = true,
      lowPower,
      comfort,
      destroyed,
      stopping,
      scheduled,
      registered,
      sensorNoticeShown;
  private long lastRender, lastSave, resetReferenceToken;
  private long profilePhysics, profileRender, profileFrames, profileAt;
  private volatile String performanceSummary="Waiting for frames";
  private boolean initialCommandHandled;
  private View menu, recycle, bubble;
  private View petLayer;
  private WindowManager.LayoutParams layerParams;
  private Slot menuOwner;
  private PetSpriteView gestureOwner;
  private final Rect recycleBounds = new Rect();
  private EdgeView leftCue, rightCue;
  private float dragOffsetX, dragOffsetY;
  private boolean recycleHit;
  private RecycleGesture recycleIntent;
  private float dragRawX,dragRawY;
  private final Choreographer.FrameCallback tick = ignored -> frame();
  private final Runnable pendingFeedback =
      () -> {
        publishFeedbackTargets();
        if (canReceiveFeedback("doro")) ReminderScheduler.deliverPendingFeedback(this);
      };
  private final Runnable hideBubble =
      () -> {
        removeWindow(bubble);
        bubble = null;
      };
  private final Runnable closeMenuLater = this::closeMenu;
  private final Runnable quietBoundary =
      () -> {
        if (!destroyed && screenOn && !paused) reload();
      };
  private final AppOpsManager.OnOpChangedListener overlayPermission =
      (op, packageName) ->
          handler.post(
              () -> {
                if (!destroyed && !Settings.canDrawOverlays(this)) fail("悬浮窗权限已关闭");
              });

  private static final class Slot {
    PetStore.Instance instance;
    PhysicsWorld.Body body;
    PetSpriteView view;
    WindowManager.LayoutParams params;
    boolean awaitingToolStart;
    long lastHandleUpdate;
    int toolLaunchGeneration;

    Slot(PetStore.Instance i) {
      instance = i;
    }
  }

  private final BroadcastReceiver receiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (destroyed || stopping) return;
          String action = intent.getAction();
          if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            screenOn = false;
            feedbackTargets = Collections.emptySet();
            cancelTouches();
            closeMenu();
            hideBubble.run();
            persistPositions();
            refreshLoop();
          } else if (Intent.ACTION_SCREEN_ON.equals(action)
              || Intent.ACTION_USER_PRESENT.equals(action)) {
            screenOn = true;
            updateBounds();
            reload();
          } else if (ACTION_RELOAD_SETTINGS.equals(action)) reload();
          else if (ACTION_FEEDBACK.equals(action)) feedback(intent);
          else if (Intent.ACTION_DATE_CHANGED.equals(action)
              || Intent.ACTION_TIME_CHANGED.equals(action)
              || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) reload();
        }
      };
  private final DisplayManager.DisplayListener displayListener =
      new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {}

        @Override
        public void onDisplayRemoved(int id) {
          if (id == android.view.Display.DEFAULT_DISPLAY) requestStop();
        }

        @Override
        public void onDisplayChanged(int id) {
          if (!destroyed && !stopping && id == android.view.Display.DEFAULT_DISPLAY) {
            cancelTouches();
            updateBounds();
            render();
            schedule();
          }
        }
      };
  private final Application.ActivityLifecycleCallbacks activities =
      new Application.ActivityLifecycleCallbacks() {
        private void panel(Activity activity, boolean open) {
          if (destroyed || stopping || !(activity instanceof FeatureActivity)) return;
          Slot slot = slots.get(activity.getIntent().getStringExtra("instanceId"));
          if (slot != null) {
            slot.awaitingToolStart = false;
            slot.body.toolOpen = open;
            slot.body.held = false;
            slot.body.wake();
            render();
            schedule();
          }
        }

        @Override
        public void onActivityStarted(Activity a) {
          panel(a, true);
        }

        @Override
        public void onActivityStopped(Activity a) {
          panel(a, false);
          reload();
        }

        @Override
        public void onActivityDestroyed(Activity a) {
          panel(a, false);
        }

        @Override
        public void onActivityCreated(Activity a, Bundle b) {}

        @Override
        public void onActivityResumed(Activity a) {
          panel(a, true);
        }

        @Override
        public void onActivityPaused(Activity a) {}

        @Override
        public void onActivitySaveInstanceState(Activity a, Bundle b) {}
      };

  @Override
  public void onCreate() {
    super.onCreate();
    feedbackTargets = Collections.emptySet();
    windows = getSystemService(WindowManager.class);
    displays = getSystemService(DisplayManager.class);
    density = getResources().getDisplayMetrics().density;
    recycleIntent=new RecycleGesture(dp(72),350);
    prefs = PetStore.prefs(this);
    PowerManager power = getSystemService(PowerManager.class);
    screenOn = power == null || power.isInteractive();
    world.setPixelsPerMeter(110 * density);
    motion =
        new MotionSensor(
            this,
            (gx, gy, cx, cy) -> {
              if (destroyed || stopping || !screenOn || paused) return;
              world.setGravity(gx, gy);
              cueX = cx;
              cueY = cy;
              if (comfort) {
                if (leftCue != null) leftCue.invalidate();
                if (rightCue != null) rightCue.invalidate();
              }
              if (!world.isSettled() || comfort) schedule();
            });
    IntentFilter filter = new IntentFilter();
    for (String action :
        new String[] {
          Intent.ACTION_SCREEN_OFF,
          Intent.ACTION_SCREEN_ON,
          Intent.ACTION_USER_PRESENT,
          Intent.ACTION_DATE_CHANGED,
          Intent.ACTION_TIME_CHANGED,
          Intent.ACTION_TIMEZONE_CHANGED,
          ACTION_FEEDBACK,
          ACTION_RELOAD_SETTINGS
        }) filter.addAction(action);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(receiver, filter);
    registered = true;
    if (displays != null) displays.registerDisplayListener(displayListener, handler);
    getApplication().registerActivityLifecycleCallbacks(activities);
    appOps = getSystemService(AppOpsManager.class);
    if (appOps != null)
      appOps.startWatchingMode(
          AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, getPackageName(), overlayPermission);
    updateBounds();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "梗宠运行状态", NotificationManager.IMPORTANCE_LOW));
    try {
      if (Build.VERSION.SDK_INT >= 34)
        startForeground(2401, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      else startForeground(2401, notification());
      running = true;
    } catch (RuntimeException e) {
      fail("暂时无法启动桌宠，请回到应用重试");
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (destroyed || stopping || !running) return START_NOT_STICKY;
    String action = intent == null ? ACTION_RELOAD : intent.getAction();
    if (ACTION_STOP.equals(action)) {
      requestStop();
      return START_NOT_STICKY;
    }
    if (!Settings.canDrawOverlays(this)) {
      fail("悬浮窗权限未开启，请回到应用设置");
      return START_NOT_STICKY;
    }
    if (ACTION_SHOW.equals(action)) {
      String id = intent.getStringExtra("characterId");
      PetCatalog.Entry entry = PetCatalog.get(this, id);
      if (entry == null) {
        toast("这个角色的图片暂时不可用");
        if(!initialCommandHandled) requestStop();
        return START_NOT_STICKY;
      }
      else {
        if(!initialCommandHandled) PetStore.startNewSession(this);
        persistPositions();
        if (PetStore.add(this, id) == null) toast("最多同时放出 6 只桌宠");
      }
    } else if (ACTION_RESTORE_PREVIOUS.equals(action)) {
      prefs.edit().putBoolean("paused",false).apply();
      persistPositions();
      PetStore.restorePrevious(this);
    } else if (ACTION_REMOVE.equals(action)) {
      persistPositions();
      PetStore.remove(this, intent.getStringExtra("instanceId"));
    } else if (ACTION_PAUSE.equals(action) || ACTION_RESUME.equals(action)) {
      prefs.edit().putBoolean("paused", ACTION_PAUSE.equals(action)).apply();
    } else if (ACTION_COMFORT.equals(action))
      prefs.edit().putBoolean("comfort_enabled", intent.getBooleanExtra("enabled", false)).apply();
    initialCommandHandled=true;
    reload();
    return START_NOT_STICKY;
  }

  private void reload() {
    if (destroyed || stopping || windows == null) return;
    feedbackTargets = Collections.emptySet();
    if (!Settings.canDrawOverlays(this)) {
      fail("悬浮窗权限已关闭");
      return;
    }
    boolean wasPaused = paused;
    paused = prefs.getBoolean("paused", false);
    boolean oldPower = lowPower;
    lowPower = prefs.getBoolean("low_power", false);
    boolean oldComfort = comfort;
    comfort = prefs.getBoolean("comfort_enabled", false);
    world.comfort = comfort;
    comfortStrength = PhysicsWorld.clamp(prefs.getFloat("comfort_strength", .35f), 0, 1);
    comfortOpacity = PhysicsWorld.clamp(prefs.getFloat("comfort_opacity", .35f), .05f, .6f);
    long reset = prefs.getLong("comfort_reset_token", 0);
    if (reset != resetReferenceToken || (comfort && !oldComfort)) {
      resetReferenceToken = reset;
      motion.resetReference();
    }
    ArrayList<PetStore.Instance> current = PetStore.loadInstances(this);
    if(!current.isEmpty() && !ensurePetLayer())return;
    Map<String, PetStore.Instance> wanted = new LinkedHashMap<>();
    for (PetStore.Instance i : current) if (wanted.size() < 6) wanted.put(i.uid, i);
    for (String id : new ArrayList<>(slots.keySet()))
      if (!wanted.containsKey(id)) detach(slots.get(id));
    for (PetStore.Instance i : wanted.values()) {
      PetCatalog.Entry entry = PetCatalog.get(this, i.characterId);
      if (entry == null) continue;
      Slot slot = slots.get(i.uid);
      if (slot == null) {

        slot = new Slot(i);
        slot.body =
            new PhysicsWorld.Body(
                i.uid,
                i.x * screenWidth,
                i.y * screenHeight,
                PhysicsWorld.clamp(i.sizeDp, 56, 144) * density * .42f);
        slot.view = new PetSpriteView(this, slot.body, this);
        slot.view.setInputOnly(true);
        slot.view.configure(entry, i, density);
        PetCatalog.preload(
            this,
            entry,
            () -> {
              if (!destroyed) {
                render();
                schedule();
              }
            });
        slot.params = params(1, 1, true);
        if (!world.add(slot.body)) continue;
        slots.put(i.uid, slot);
        // addView attaches on a later traversal. Do not consume pending reminders
        // or assume a LayoutParams mutation was applied before that traversal.
        final Slot added = slot;
        slot.view.addOnAttachStateChangeListener(
            new View.OnAttachStateChangeListener() {
              @Override
              public void onViewAttachedToWindow(View view) {
                handler.post(
                    () -> {
                      if (destroyed || stopping || slots.get(added.instance.uid) != added) return;
                      render();
                      updateWindow(added.view, added.params);
                      queuePendingFeedback();
                    });
              }

              @Override
              public void onViewDetachedFromWindow(View view) {
                publishFeedbackTargets();
              }
            });
        if (!attach(slot.view, slot.params)) {
          detach(slot);
          return;
        }
        slot.view.setOnApplyWindowInsetsListener(
            (view, insets) -> {
              if (Build.VERSION.SDK_INT >= 30) {
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                int floor = screenHeight - (insets.isVisible(WindowInsets.Type.ime()) ? ime.bottom : 0);
                if (floor != safe.bottom) {
                  safe.bottom = Math.max(safe.top + 100, floor);
                  applyBounds();
                  schedule();
                }
              }
              return insets;
            });
      }
      boolean changed =
          slot.body.pinned != i.pinned
              || slot.body.gravity != i.gravity
              || slot.instance.sizeDp != i.sizeDp;
      slot.instance = i;
      slot.body.pinned = i.pinned;
      slot.body.gravity = i.gravity;
      slot.body.softness = i.softness;
      slot.body.radius = PhysicsWorld.clamp(i.sizeDp, 56, 144) * density * .42f;
      world.configureSoft(slot.body, entry.contourUV, PhysicsWorld.clamp(i.sizeDp, 56, 144) * density);
      if (changed || wasPaused != paused || oldComfort != comfort) slot.body.wake();
      slot.view.configure(entry, i, density);
      slot.view.setCalm(comfort || lowPower || quietNow(), paused);
      if ("thursday_bucket_cat".equals(i.characterId))
        slot.view.setRestingState(ThursdayRules.state(this));
    }
    activeCount = slots.size();
    if (slots.isEmpty()) {
      requestStop();
      return;
    }
    if (!motion.available() && !sensorNoticeShown) {
      sensorNoticeShown = true;
      toast("设备没有重力传感器，桌宠将使用向下重力");
    }
    if (paused) {
      cancelTouches();
      closeMenu();
      hideBubble.run();
    }
    if (oldPower != lowPower) motion.stop();
    refreshCues();
    render();
    refreshLoop();
    if (destroyed || stopping) return;
    if (screenOn && !paused && !comfort && !quietNow())
      for (Slot slot : slots.values()) {
        if ("thursday_bucket_cat".equals(slot.instance.characterId)
            && ThursdayRules.claimCelebration(this)) {
          slot.view.feedback("crazy", SystemClock.uptimeMillis() + 8000);
          showBubble(slot, ThursdayRules.status());
          schedule();
          break;
        }
      }
    queuePendingFeedback();
    // This is reached by screen-on, explicit resume, settings reload and initial SHOW.
    // Do not call reschedule(): it emits RELOAD_SETTINGS and would recurse into reload.
    getSystemService(NotificationManager.class).notify(2401, notification());
  }

  private void queuePendingFeedback() {
    if (destroyed || stopping || !running || !screenOn || paused) return;
    handler.removeCallbacks(pendingFeedback);
    handler.post(pendingFeedback);
  }

  private void publishFeedbackTargets() {
    if (!running || destroyed || stopping || !screenOn || paused) {
      feedbackTargets = Collections.emptySet();
      return;
    }
    Set<String> ready = new HashSet<>();
    for (Slot slot : slots.values())
      if (slot.view.isAttachedToWindow() && slot.view.getWindowVisibility() == View.VISIBLE)
        ready.add(slot.instance.characterId);
    feedbackTargets = Collections.unmodifiableSet(ready);
  }

  private void refreshLoop() {
    Choreographer.getInstance().removeFrameCallback(tick);
    scheduled = false;
    world.resetClock();
    handler.removeCallbacks(quietBoundary);
    if (screenOn && !paused && !destroyed && !stopping) {
      motion.start(lowPower);
      schedule();
      // A minute-scale settings wake allows quiet-hour transitions even when all pets
      // sleep. It performs no hidden high-frequency drawing and is canceled at screen-off.
      handler.postDelayed(quietBoundary, 60_000 - System.currentTimeMillis() % 60_000);
    } else {
      motion.stop();
      refreshCues();
    }
  }

  private void schedule() {
    if (!scheduled && !destroyed && !stopping && screenOn && !paused && !slots.isEmpty()) {
      scheduled = true;
      Choreographer.getInstance().postFrameCallback(tick);
    }
  }

  private void frame() {
    scheduled = false;
    if (destroyed || stopping || !screenOn || paused) return;
    long now = SystemClock.uptimeMillis();
    if(gestureOwner!=null && recycle!=null)updateRecycleIntent();
    long started=System.nanoTime();
    world.advance(started);
    long physicsEnd=System.nanoTime();
    boolean dragging = false, animated = false;
    for (Slot slot : slots.values()) {
      dragging |= slot.body.held;
      animated |= slot.view.animated();
    }
    // Active physical motion follows display vsync even in low power. Savings come
    // from sleeping bodies and suspended decorative animations, not jerky dragging.
    render();
    lastRender = now;
    profilePhysics+=physicsEnd-started;
    profileRender+=System.nanoTime()-physicsEnd;
    profileFrames++;
    if(now-profileAt>=5000) {
      performanceSummary="pets="+slots.size()+" lowPower="+lowPower+" frames="+profileFrames+" physicsMs="+(profilePhysics/1e6/profileFrames)+" renderMs="+(profileRender/1e6/profileFrames)+" attempts="+world.softPhysicsAttempts()+" rollbacks="+world.softRollbacks()+" budgets="+world.softBudgetExhaustions();
      profilePhysics=profileRender=profileFrames=0;profileAt=now;
    }
    if (now - lastSave > 5000) {
      if (!Settings.canDrawOverlays(this)) { fail("悬浮窗权限已关闭"); return; }
      persistPositions();
      lastSave = now;
      for (Slot slot : slots.values()) slot.view.setCalm(comfort || lowPower || quietNow(), paused);
    }
    if (!world.isSettled() || animated || comfort || dragging) {
      scheduled = true;
      Choreographer.getInstance().postFrameCallback(tick);
    } else {
      render();
      persistPositions();
      world.resetClock();
    }
  }

  private void render() {
    if (destroyed || stopping || !screenOn) return;
    syncLayerOpacity();
    boolean redraw=false;
    long now=SystemClock.uptimeMillis();
    for (Slot slot : slots.values()) {
      RectF rect = slot.view.worldBounds();
      // Keep a stable buffer. Deformation now changes vertices, not Surface dimensions every frame.
      int quantum = dp(16);
      int needW = Math.max(dp(48), (int)Math.ceil(rect.width()) + 4);
      int needH = Math.max(dp(48), (int)Math.ceil(rect.height()) + 4);
      int w = Math.min(screenWidth, Math.max(slot.params.width, ((needW+quantum-1)/quantum)*quantum));
      int h = Math.min(screenHeight, Math.max(slot.params.height, ((needH+quantum-1)/quantum)*quantum));
      int x = Math.round(PhysicsWorld.clamp(rect.centerX()-w/2f, 0, screenWidth-w));
      int y = Math.round(PhysicsWorld.clamp(rect.centerY()-h/2f, 0, screenHeight-h));
      if (!slot.body.held && (slot.params.x != x || slot.params.y != y || slot.params.width != w || slot.params.height != h)) {
        boolean urgent=slot.body.held || slot.params.width!=w || slot.params.height!=h
            ||Math.abs(x-slot.params.x)>dp(6)||Math.abs(y-slot.params.y)>dp(6);
        if(urgent||now-slot.lastHandleUpdate>=150){
          slot.params.x=x;slot.params.y=y;slot.params.width=w;slot.params.height=h;
          updateWindow(slot.view,slot.params);slot.lastHandleUpdate=now;
          if(stopping)return;
        }
      }
      slot.view.setWindowOrigin(slot.params.x,slot.params.y);
      redraw|=slot.view.invalidateIfChanged();
    }
    if(petLayer!=null && redraw)petLayer.invalidate();
  }

  private boolean ensurePetLayer(){
    if(petLayer!=null)return true;
    petLayer=new View(this){
      @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        for(Slot slot:slots.values())slot.view.drawInWorld(canvas);
      }
    };
    petLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    layerParams=params(screenWidth,screenHeight,false);
    layerParams.x=layerParams.y=0;
    layerParams.alpha=Build.VERSION.SDK_INT>=31?.79f:1f;
    return attach(petLayer,layerParams);
  }

  private void syncLayerOpacity(){
    if(petLayer==null||layerParams==null||Build.VERSION.SDK_INT<31)return;
    // Compose obscuring opacity with the optional cue/recycle/bubble overlays.
    float transmission=1f;
    if(comfort)transmission*=1-comfortOpacity;
    if(bubble!=null)transmission*=.65f;
    if(recycle!=null)transmission*=.65f;
    float alpha=Math.max(0,Math.min(.79f,1-.21f/transmission));
    if(layerParams.alpha!=alpha){layerParams.alpha=alpha;updateWindow(petLayer,layerParams);}
  }

  @Override
  protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args) {
    writer.println(performanceSummary);
    writer.println("windows="+registeredWindows.size()+" recycle="+(recycle!=null)+" dragging="+(gestureOwner!=null));
  }
  private void updateBounds() {
    if (windows == null) return;
    int oldWidth = screenWidth, oldHeight = screenHeight;
    if (Build.VERSION.SDK_INT >= 30) {
      android.view.WindowMetrics metrics = windows.getCurrentWindowMetrics();
      Rect rect = metrics.getBounds();
      screenWidth = rect.width();
      screenHeight = rect.height();
      Insets insets =
          metrics
              .getWindowInsets()
              .getInsetsIgnoringVisibility(
                  WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      safe.set(insets.left, insets.top, screenWidth - insets.right, screenHeight - metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout()).bottom);
    } else {
      DisplayMetrics dm = new DisplayMetrics();
      windows.getDefaultDisplay().getRealMetrics(dm);
      screenWidth = dm.widthPixels;
      screenHeight = dm.heightPixels;
      int status = resourceDimension("status_bar_height"),
          nav = resourceDimension("navigation_bar_height");
      safe.set(0, status, screenWidth, screenHeight);
    }
    if (oldWidth != screenWidth || oldHeight != screenHeight)
      for (Slot slot : slots.values()) {
        slot.body.x *= screenWidth / (float) oldWidth;
        slot.body.y *= screenHeight / (float) oldHeight;
      }
    applyBounds();
    if(petLayer!=null && (layerParams.width!=screenWidth||layerParams.height!=screenHeight)){
      layerParams.width=screenWidth;layerParams.height=screenHeight;updateWindow(petLayer,layerParams);
    }
    closeMenu();
    hideBubble.run();
    refreshCues();
  }

  private int systemBottomInset() {
    if (Build.VERSION.SDK_INT >= 30)
      return windows
          .getCurrentWindowMetrics()
          .getWindowInsets()
          .getInsetsIgnoringVisibility(
              WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout())
          .bottom;
    return resourceDimension("navigation_bar_height");
  }

  private int resourceDimension(String name) {
    int id = getResources().getIdentifier(name, "dimen", "android");
    return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
  }

  private void applyBounds() {
    world.setBounds(safe.left, safe.top, safe.right, safe.bottom);
  }

  private WindowManager.LayoutParams params(int w, int h, boolean touchable) {
    int flags =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    WindowManager.LayoutParams p =
        new WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT);
    p.gravity = Gravity.TOP | Gravity.LEFT;
    // Bubble is removed before dragging/recycle. At most one .35 UI window and
    // one .6 edge cue overlap: combined opacity 1 - .65 * .4 = .74 < .8.
    p.alpha = touchable ? 1 : .35f;
    if (Build.VERSION.SDK_INT >= 30) { p.setFitInsetsTypes(0); p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; }
    return p;
  }

  private boolean attach(View view, WindowManager.LayoutParams p) {
    if (destroyed || stopping) return false;
    syncLayerOpacity();
    try {
      windows.addView(view, p);
      registeredWindows.add(view);
      return true;
    } catch (RuntimeException e) {
      fail("桌宠窗口暂时无法显示，请检查悬浮窗权限");
      return false;
    }
  }

  private void updateWindow(View view, WindowManager.LayoutParams p) {
    if (view == null || !view.isAttachedToWindow() || destroyed || stopping) return;
    try {
      windows.updateViewLayout(view, p);
    } catch (RuntimeException e) {
      fail("桌宠窗口已关闭，请回到应用重试");
    }
  }

  private void removeWindow(View view) {
    if (view == null) return;
    if(!registeredWindows.remove(view))return;
    try {
      // addView registers a root before the first traversal attaches its View.
      // Removing during that gap must still remove the registered window.
      windows.removeViewImmediate(view);
    } catch (IllegalArgumentException ignored) {
    }
  }

  private void detach(Slot slot) {
    if (slot == null) return;
    if (slot == menuOwner) closeMenu();
    slots.remove(slot.instance.uid);
    if(petLayer!=null)petLayer.invalidate();
    removeWindow(slot.view);
    world.remove(slot.body);
    activeCount = slots.size();
    publishFeedbackTargets();
  }

  private void persistPositions() {
    if (prefs == null || slots.isEmpty()) return;
    // Merge only position into a fresh list; never overwrite mood, pin or tool edits.
    ArrayList<PetStore.Instance> current = PetStore.loadInstances(this);
    for (PetStore.Instance instance : current) {
      Slot slot = slots.get(instance.uid);
      if (slot == null) continue;
      instance.x = PhysicsWorld.clamp(slot.body.x / screenWidth, 0, 1);
      instance.y = PhysicsWorld.clamp(slot.body.y / screenHeight, 0, 1);
    }
    PetStore.saveInstances(this, current);
  }

  private Slot slot(PetSpriteView view) {
    return slots.get(view.body.id);
  }

  @Override
  public void press(PetSpriteView view) {
    if (destroyed || stopping || !screenOn) return;
    if (gestureOwner != null && gestureOwner != view) gestureOwner.cancelGesture();
    gestureOwner = view;
    closeMenu();
    hideBubble.run();
    view.body.held = true;
    view.body.wake();
    dragOffsetX = view.body.x - view.downRawX();
    dragOffsetY = view.body.y - view.downRawY();
    dragRawX=view.downRawX();dragRawY=view.downRawY();
    recycleIntent.begin(dragRawX,dragRawY,SystemClock.uptimeMillis());
    schedule();
  }

  @Override
  public void drag(PetSpriteView view, float rawX, float rawY) {
    if (destroyed || stopping || !screenOn || gestureOwner != view) return;
    if (recycle == null && Math.hypot(rawX-view.downRawX(),rawY-view.downRawY())>=dp(72)) showRecycle();
    view.body.x = rawX + dragOffsetX;
    view.body.y = rawY + dragOffsetY;
    world.constrain(view.body);
    dragRawX=rawX;dragRawY=rawY;updateRecycleIntent();
    schedule();
  }

  @Override
  public void release(PetSpriteView view, float vx, float vy, boolean dragged, boolean canceled) {
    view.body.held = false;
    if (gestureOwner != view) return;
    gestureOwner = null;
    boolean recall = dragged && !canceled && recycleHit;
    recycleHit = false;
    recycleIntent.reset();
    removeWindow(recycle);
    recycle = null;
    recycleBounds.setEmpty();
    if (destroyed || stopping) return;
    if (recall) {
      recall(slot(view));
      return;
    }
    world.throwBody(
        view.body,
        canceled || !dragged || paused ? 0 : vx,
        canceled || !dragged || paused ? 0 : vy);
    persistPositions();
    schedule();
  }

  @Override
  public void finishDrag(PetSpriteView view,float rawX,float rawY){
    boolean wasArmed=recycleHit;
    drag(view,rawX,rawY);
    recycleHit &= wasArmed; // UP can cancel a recall, but cannot arm it for the first time.
  }

  private void updateRecycleIntent(){
    boolean inside=recycleBounds.contains(Math.round(dragRawX),Math.round(dragRawY));
    recycleIntent.move(dragRawX,dragRawY,inside,SystemClock.uptimeMillis());
    boolean armed=recycleIntent.armed();
    if(recycle instanceof TextView){
      String label=armed?"松手收回":inside?"停留一下，再松手收回":"拖到这里收回";
      if(!label.contentEquals(((TextView)recycle).getText()))((TextView)recycle).setText(label);
    }
    recycleHit=armed;
  }

  @Override
  public void tap(PetSpriteView view) {
    showMenu(slot(view));
  }

  @Override
  public void longPress(PetSpriteView view) {
    openTools(slot(view));
  }

  private void openTools(Slot slot) {
    if (slot == null || destroyed || stopping || !screenOn) return;
    gestureOwner = null;
    closeMenu();
    slot.body.held = false;
    slot.body.toolOpen = true;
    render();
    slot.body.vx = slot.body.vy = 0;
    persistPositions();
    slot.awaitingToolStart = true;
    int generation = ++slot.toolLaunchGeneration;
    try {
      FeatureActivity.open(this, slot.instance.characterId, slot.instance.uid);
      // Some ROMs silently reject an Activity launch from an overlay. An absent
      // lifecycle callback must not leave that pet permanently frozen in toolOpen.
      handler.postDelayed(
          () -> {
            if (destroyed
                || stopping
                || slots.get(slot.instance.uid) != slot
                || !slot.awaitingToolStart
                || slot.toolLaunchGeneration != generation) return;
            slot.awaitingToolStart = false;
            slot.body.toolOpen = false;
            slot.body.wake();
            toast("请从梗宠应用中打开专属功能");
            render();
            schedule();
          },
          2000);
    } catch (RuntimeException e) {
      slot.awaitingToolStart = false;
      slot.body.toolOpen = false;
      slot.body.wake();
      toast("请从梗宠应用中打开专属功能");
      render();
      schedule();
    }
  }

  private void showMenu(Slot slot) {
    if (slot == null || destroyed || stopping || !screenOn) return;
    closeMenu();
    menuOwner = slot;
    slot.body.toolOpen = true;
    render();
    LinearLayout content = UiKit.column(this, 10);
    content.setBackground(UiKit.card(this));
    String title = PetCatalog.get(this, slot.instance.characterId).name;
    if (slot.instance.moodText != null && !slot.instance.moodText.isEmpty())
      title += " · " + slot.instance.moodText;
    content.addView(UiKit.text(this, title, 15));
    content.addView(UiKit.button(this, "专属功能", () -> openTools(slot)));
    content.addView(
        UiKit.button(
            this,
            slot.body.pinned ? "取消固定" : "固定位置",
            () -> {
              persistPositions();
              PetStore.Instance fresh = PetStore.find(this, slot.instance.uid);
              if (fresh != null) {
                fresh.pinned = !fresh.pinned;
                PetStore.update(this, fresh);
              }
              closeMenu();
              reload();
            }));
    content.addView(UiKit.button(this, "调整大小", () -> showSizeMenu(slot)));
    content.addView(UiKit.button(this, "调整柔软度", () -> showSoftnessMenu(slot)));
    content.addView(UiKit.button(this, "收回这只", () -> recall(slot)));
    content.addView(
        UiKit.button(
            this,
            paused ? "继续全部" : "暂停全部",
            () -> {
              prefs.edit().putBoolean("paused", !paused).apply();
              closeMenu();
              reload();
            }));
    content.addView(UiKit.button(this, "关闭菜单", this::closeMenu));
    showPanel(content, slot);
    handler.postDelayed(closeMenuLater, 15000);
  }

  private void showSizeMenu(Slot slot) {
    closeMenu();
    menuOwner = slot;
    slot.body.toolOpen = true;
    LinearLayout content = UiKit.column(this, 10);
    content.setBackground(UiKit.card(this));
    content.addView(UiKit.text(this, "桌宠大小", 16));
    for (int size : new int[] {56, 88, 112, 144})
      content.addView(
          UiKit.button(
              this,
              size + " dp",
              () -> {
                persistPositions();
                PetStore.Instance fresh = PetStore.find(this, slot.instance.uid);
                if (fresh != null) {
                  fresh.sizeDp = size;
                  PetStore.update(this, fresh);
                }
                closeMenu();
                reload();
              }));
    content.addView(UiKit.button(this, "关闭", this::closeMenu));
    showPanel(content, slot);
    handler.postDelayed(closeMenuLater, 15000);
  }

  private void showSoftnessMenu(Slot slot) {
    closeMenu();
    menuOwner=slot;
    slot.body.toolOpen=true;
    LinearLayout content=UiKit.column(this,10);
    content.setBackground(UiKit.card(this));
    TextView label=UiKit.text(this,"柔软度 · "+Math.round(slot.body.softness*100),16);
    content.addView(label);
    content.addView(UiKit.text(this,"左边偏硬，右边更软。保存后生效。",13));
    android.widget.SeekBar seek=new android.widget.SeekBar(this);
    seek.setMax(100);seek.setProgress(Math.round(slot.body.softness*100));
    seek.setContentDescription("柔软度，0 最硬，100 最软");
    content.addView(seek,new LinearLayout.LayoutParams(-1,dp(48)));
    seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(android.widget.SeekBar b,int n,boolean user){label.setText("柔软度 · "+n);}
      public void onStartTrackingTouch(android.widget.SeekBar b){handler.removeCallbacks(closeMenuLater);}
      public void onStopTrackingTouch(android.widget.SeekBar b){}
    });
    for(int value:new int[]{30,55,85}) content.addView(UiKit.button(this,
        value==30?"偏硬 · 30":value==55?"适中 · 55":"很软 · 85",()->seek.setProgress(value)));
    content.addView(UiKit.button(this,"保存",()->{
      persistPositions();
      PetStore.Instance fresh=PetStore.find(this,slot.instance.uid);
      if(fresh!=null){fresh.softness=seek.getProgress()/100f;PetStore.update(this,fresh);}
      closeMenu();reload();
    }));
    content.addView(UiKit.button(this,"取消",this::closeMenu));
    showPanel(content,slot);
  }

  private void showPanel(View content, Slot slot) {
    android.widget.ScrollView scroller = new android.widget.ScrollView(this);
    scroller.setFillViewport(false);
    scroller.addView(content);
    content = scroller;
    int width = Math.min(dp(220), safe.width()), height;
    content.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(safe.height(), View.MeasureSpec.AT_MOST));
    height = Math.min(content.getMeasuredHeight(), safe.height());
    WindowManager.LayoutParams p = params(width, height, true);
    p.x = (int) PhysicsWorld.clamp(slot.body.x - width / 2f, safe.left, safe.right - width);
    p.y =
        (int)
            PhysicsWorld.clamp(
                slot.body.y - slot.body.radius - height, safe.top, safe.bottom - height);
    menu = content;
    attach(menu, p);
  }

  private void closeMenu() {
    handler.removeCallbacks(closeMenuLater);
    removeWindow(menu);
    menu = null;
    if (menuOwner != null) {
      menuOwner.body.toolOpen = false;
      menuOwner.body.wake();
      menuOwner = null;
      schedule();
    }
  }

  private void recall(Slot slot) {
    if (slot == null) return;
    persistPositions();
    PetStore.remove(this, slot.instance.uid);
    detach(slot);
    if (slots.isEmpty()) requestStop();
    else reload();
  }

  private void showRecycle() {
    removeWindow(recycle);
    TextView label = UiKit.text(this, "拖到这里收回", 16);
    label.setGravity(Gravity.CENTER);
    label.setBackground(UiKit.card(this));
    recycle = label;
    WindowManager.LayoutParams p = params(Math.min(dp(164), safe.width()), dp(64), false);
    p.x = safe.centerX() - p.width / 2;
    p.y = safe.bottom - dp(88);
    recycleBounds.set(p.x, p.y, p.x + p.width, p.y + p.height);
    attach(recycle, p);
  }

  private void feedback(Intent intent) {
    if (destroyed || stopping || !running) return;
    String uid = intent.getStringExtra("instanceId"),
        character = intent.getStringExtra("characterId");
    Slot target = uid == null || uid.isEmpty() ? null : slots.get(uid);
    if (target == null && (uid == null || uid.isEmpty()))
      for (Slot candidate : slots.values())
        if (candidate.instance.characterId.equals(character)) {
          target = candidate;
          break;
        }
    if (target == null || !screenOn || paused) return;
    String state = intent.getStringExtra("state");
    if ("reset_reference".equals(state)) {
      motion.resetReference();
      return;
    }
    target.view.feedback(state, SystemClock.uptimeMillis() + 8000);
    String text = intent.getStringExtra("text");
    if (recycle == null && text != null && !text.isEmpty()) showBubble(target, text);
    target.view.invalidate();
    schedule();
    handler.postDelayed(
        () -> {
          if (!destroyed && !stopping && screenOn && !paused) {
            render();
            schedule();
          }
        },
        8100);
  }

  private void showBubble(Slot slot, String message) {
    handler.removeCallbacks(hideBubble);
    hideBubble.run();
    TextView label =
        UiKit.text(this, message.length() > 80 ? message.substring(0, 80) + "…" : message, 14);
    label.setPadding(dp(10), dp(6), dp(10), dp(6));
    label.setMaxLines(3);
    label.setBackground(UiKit.card(this));
    int width = Math.min(dp(220), safe.width());
    label.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(dp(100), View.MeasureSpec.AT_MOST));
    WindowManager.LayoutParams p =
        params(width, Math.max(dp(36), label.getMeasuredHeight()), false);
    p.x = (int) PhysicsWorld.clamp(slot.body.x - width / 2f, safe.left, safe.right - width);
    p.y =
        (int)
            PhysicsWorld.clamp(
                slot.body.y - slot.body.radius - p.height - dp(8),
                safe.top,
                safe.bottom - p.height);
    bubble = label;
    attach(bubble, p);
    handler.postDelayed(hideBubble, 5000);
  }

  private void refreshCues() {
    removeWindow(leftCue);
    removeWindow(rightCue);
    leftCue = rightCue = null;
    if (!comfort || paused || !screenOn || destroyed || stopping || slots.isEmpty()) return;
    int width = dp(16), height = Math.min(dp(300), safe.height() / 2);
    leftCue = new EdgeView(this);
    rightCue = new EdgeView(this);
    WindowManager.LayoutParams left = params(width, height, false),
        right = params(width, height, false);
    // Opacity is a WINDOW property, not only a Canvas alpha, to preserve touch passage.
    left.alpha = right.alpha = comfortOpacity;
    left.x = safe.left;
    right.x = safe.right - width;
    left.y = right.y = safe.centerY() - height / 2;
    attach(leftCue, left);
    attach(rightCue, right);
  }

  private final class EdgeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    EdgeView(Context context) {
      super(context);
      paint.setColor(UiKit.ACCENT);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float dx = cueX * comfortStrength * dp(3), dy = cueY * comfortStrength * dp(22);
      for (int i = 1; i <= 5; i++)
        canvas.drawCircle(getWidth() / 2f + dx, getHeight() * i / 6f + dy, dp(3), paint);
    }
  }

  private boolean quietNow() {
    java.util.Calendar c = java.util.Calendar.getInstance();
    int minute = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    int start = prefs.getInt("quiet_start", 1320), end = prefs.getInt("quiet_end", 480);
    return start != end
        && (start < end ? minute >= start && minute < end : minute >= start || minute < end);
  }

  private void cancelTouches() {
    for (Slot slot : new ArrayList<>(slots.values())) slot.view.cancelGesture();
    gestureOwner = null;
    recycleHit = false;
    recycleBounds.setEmpty();
    removeWindow(recycle);
    recycle = null;
  }

  private int dp(float value) {
    return Math.round(value * density);
  }

  private Notification notification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            2401,
            new Intent(this, GengMainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            2402,
            new Intent(this, GengOverlayService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.btn_star)
        .setContentTitle(paused ? "梗宠已暂停" : "梗宠正在陪伴")
        .setContentText(activeCount + " 只桌宠 · 点击管理")
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "关闭全部", stop).build())
        .build();
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }

  private void requestStop() {
    if (destroyed || stopping) return;
    stopping = true;
    running = false;
    feedbackTargets = Collections.emptySet();
    persistPositions();
    Choreographer.getInstance().removeFrameCallback(tick);
    handler.removeCallbacksAndMessages(null);
    scheduled = false;
    if (motion != null) motion.stop();
    cancelTouches();
    closeMenu();
    hideBubble.run();
    removeWindow(leftCue);
    removeWindow(rightCue);
    leftCue = rightCue = null;
    stopSelf();
  }

  private void fail(String message) {
    if (!destroyed && !stopping) {
      toast(message);
      requestStop();
    }
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    cancelTouches();
    updateBounds();
    render();
    schedule();
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    persistPositions();
    super.onTaskRemoved(rootIntent);
  }

  @Override
  public void onDestroy() {
    persistPositions();
    destroyed = true;
    stopping = true;
    running = false;
    paused = false;
    activeCount = 0;
    feedbackTargets = Collections.emptySet();
    Choreographer.getInstance().removeFrameCallback(tick);
    handler.removeCallbacksAndMessages(null);
    motion.stop();
    cancelTouches();
    closeMenu();
    hideBubble.run();
    removeWindow(leftCue);
    removeWindow(rightCue);
    leftCue = rightCue = null;
    for (Slot slot : new ArrayList<>(slots.values())) detach(slot);
    removeWindow(petLayer);petLayer=null;layerParams=null;
    for(View remaining:new ArrayList<>(registeredWindows))removeWindow(remaining);
    if (registered) unregisterReceiver(receiver);
    if (displays != null) displays.unregisterDisplayListener(displayListener);
    if (appOps != null) appOps.stopWatchingMode(overlayPermission);
    getApplication().unregisterActivityLifecycleCallbacks(activities);
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
