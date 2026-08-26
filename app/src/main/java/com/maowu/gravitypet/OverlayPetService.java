package com.maowu.gravitypet;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class OverlayPetService extends Service implements SensorEventListener {
    public static final String ACTION_SHOW_BUBBLE =
            "com.maowu.gravitypet.action.SHOW_BUBBLE";
    public static final String ACTION_START_CONVERSATION =
            "com.maowu.gravitypet.action.START_CONVERSATION";
    public static final String ACTION_STOP_CONVERSATION =
            "com.maowu.gravitypet.action.STOP_CONVERSATION";
    public static final String ACTION_SHOW_CONVERSATION_BUTTON =
            "com.maowu.gravitypet.action.SHOW_CONVERSATION_BUTTON";
    public static final String ACTION_SUMMON_PET =
            "com.maowu.gravitypet.action.SUMMON_PET";
    public static final String ACTION_RECALL_ALL =
            "com.maowu.gravitypet.action.RECALL_ALL";
    public static final String ACTION_REFRESH_SKIN =
            "com.maowu.gravitypet.action.REFRESH_SKIN";
    public static final String ACTION_DELETE_SKIN =
            "com.maowu.gravitypet.action.DELETE_SKIN";
    public static final String EXTRA_BUBBLE_TEXT = "bubble_text";
    public static final String EXTRA_SKIN_ID = "skin_id";
    private static final String TAG = "GravityPet";
    private static final String CHANNEL_ID = "gravity_pet_active";
    private static final int NOTIFICATION_ID = 42;
    private static final int MAX_PETS = 9;
    private static volatile boolean running;
    private static volatile int petCount;

    private WindowManager windowManager;
    private FrameLayout petLayer;
    private PetView petView;
    private WindowManager.LayoutParams petWindowParams;
    private final List<PetSlot> petSlots = new ArrayList<>();
    private PetSlot activePetSlot;
    private View bubbleTouchView;
    private WindowManager.LayoutParams bubbleTouchParams;
    private boolean bubbleTouchAttached;
    private ConversationBackView conversationBackView;
    private WindowManager.LayoutParams conversationBackParams;
    private View outsideTouchObserver;
    private WindowManager.LayoutParams outsideTouchObserverParams;
    private boolean outsideTouchObserverAttached;
    private long lastObservedTapTime;
    private float lastObservedTapX;
    private float lastObservedTapY;
    private Button conversationButton;
    private WindowManager.LayoutParams conversationButtonParams;
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final Runnable removeConversationRunnable = this::removeConversationButton;
    private final Runnable removeBubbleRunnable = this::removeSpeechBubble;
    private float latestCenterX;
    private float latestCenterY;
    private float latestTouchDiameter;
    private final int[] petLocationOnScreen = new int[2];
    private SensorManager sensorManager;
    private Sensor activeSensor;
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private boolean overlayAttached;
    private boolean interactionGuardEnabled;
    private final float[] filteredAcceleration = new float[3];
    private boolean usingRawAccelerometer;
    private boolean receivedSensorEvent;
    private ContinuousConversationController conversationController;
    private MusicBeatDetector musicBeatDetector;
    private int musicBeatIndex;
    private final boolean[] crowdContacts = new boolean[MAX_PETS];
    private static final float WORLD_STEP_SECONDS = 1f / 120f;
    private static final int MAX_WORLD_SUBSTEPS = 4;
    private static final int CONTACT_SOLVER_PASSES = 3;
    private long lastWorldFrameNanos;
    private float worldAccumulatorSeconds;
    private long crowdStabilizeUntilMillis;
    private final Choreographer.FrameCallback petCollisionFrame = this::runPetWorld;

    private void runPetWorld(long frameTimeNanos) {
        if (!overlayAttached) return;
        if (lastWorldFrameNanos == 0L) lastWorldFrameNanos = frameTimeNanos;
        float elapsed = Math.min(0.05f,
                (frameTimeNanos - lastWorldFrameNanos) / 1_000_000_000f);
        lastWorldFrameNanos = frameTimeNanos;
        worldAccumulatorSeconds = Math.min(0.05f,
                worldAccumulatorSeconds + Math.max(0f, elapsed));
        boolean crowdStabilizing = SystemClock.uptimeMillis()
                < crowdStabilizeUntilMillis;
        for (PetSlot slot : petSlots) {
            slot.view.setCrowdStabilizing(crowdStabilizing);
        }
        Arrays.fill(crowdContacts, 0, petSlots.size(), false);
        int substeps = 0;
        while (worldAccumulatorSeconds >= WORLD_STEP_SECONDS
                && substeps < MAX_WORLD_SUBSTEPS) {
            for (PetSlot slot : petSlots) slot.view.stepWorld(WORLD_STEP_SECONDS);
            // Integrate every body first, then solve the whole contact graph.
            // No child is redrawn between these passes, so a dense pile cannot
            // expose the alternating corrected/uncorrected positions.
            for (int iteration = 0; iteration < CONTACT_SOLVER_PASSES; iteration++) {
                for (int first = 0; first < petSlots.size(); first++) {
                    for (int second = first + 1; second < petSlots.size(); second++) {
                        boolean touching = petSlots.get(first).view.resolveCollisionWith(
                                petSlots.get(second).view);
                        if (touching) {
                            crowdContacts[first] = true;
                            crowdContacts[second] = true;
                        }
                    }
                }
            }
            worldAccumulatorSeconds -= WORLD_STEP_SECONDS;
            substeps++;
        }
        if (substeps == MAX_WORLD_SUBSTEPS
                && worldAccumulatorSeconds >= WORLD_STEP_SECONDS) {
            worldAccumulatorSeconds %= WORLD_STEP_SECONDS;
        }
        for (int index = 0; index < petSlots.size(); index++) {
            petSlots.get(index).view.updateCrowdContact(crowdContacts[index]);
            petSlots.get(index).view.finishWorldFrame();
        }
        Choreographer.getInstance().postFrameCallback(petCollisionFrame);
    }

    public static boolean isRunning() {
        return running;
    }

    public static int getPetCount() {
        return petCount;
    }

    private static final class PetSlot {
        final PetView view;
        final int skinId;
        DragHandleView handle;
        WindowManager.LayoutParams handleParams;
        boolean handleAttached;
        float centerX;
        float centerY;
        float touchDiameter;

        PetSlot(PetView view, int skinId) {
            this.view = view;
            this.skinId = skinId;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startPetForeground(false);

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        preferences = PetPreferences.get(this);
        // The opacity control no longer exists. Remove its legacy value so it
        // cannot make newly summoned pets translucent after an app update.
        preferences.edit().remove(PetPreferences.KEY_OPACITY_PERCENT).apply();
        interactionGuardEnabled = preferences.getBoolean(
                PetPreferences.KEY_KEYBOARD_GUARD, false);
        preferenceListener = (sharedPreferences, key) -> {
            if (PetPreferences.KEY_COLOR.equals(key)) {
                int color = sharedPreferences.getInt(PetPreferences.KEY_COLOR, Color.WHITE);
                overlayHandler.post(() -> {
                    for (PetSlot slot : petSlots) slot.view.setBallColor(color);
                });
            } else if (PetPreferences.KEY_KEYBOARD_GUARD.equals(key)) {
                interactionGuardEnabled = sharedPreferences.getBoolean(
                        PetPreferences.KEY_KEYBOARD_GUARD, false);
                overlayHandler.post(this::updateDragHandleInteractionMode);
            } else if (PetPreferences.KEY_BALL_DIAMETER_DP.equals(key)) {
                int diameter = sharedPreferences.getInt(
                        PetPreferences.KEY_BALL_DIAMETER_DP, 52);
                overlayHandler.post(() -> {
                    for (PetSlot slot : petSlots) slot.view.setBallDiameterDp(diameter);
                });
            } else if (PetPreferences.KEY_GRAVITY_PERCENT.equals(key)) {
                int percent = sharedPreferences.getInt(
                        PetPreferences.KEY_GRAVITY_PERCENT, 100);
                overlayHandler.post(() -> {
                    for (PetSlot slot : petSlots) slot.view.setGravityStrengthPercent(percent);
                });
            }
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);

        petWindowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.RGBA_8888);
        petWindowParams.gravity = Gravity.TOP | Gravity.START;
        petWindowParams.alpha = 1f;
        petWindowParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        useFullScreenCoordinates(petWindowParams);
        petLayer = new FrameLayout(this);
        petLayer.setAlpha(1f);
        try {
            windowManager.addView(petLayer, petWindowParams);
            overlayAttached = true;
        } catch (RuntimeException error) {
            petLayer = null;
            stopSelf();
            return;
        }

        summonSelectedPet();
        ensureMusicBeatDetector();
        conversationController = new ContinuousConversationController(this,
                new ContinuousConversationController.Listener() {
                    @Override
                    public void showStatus(String text) {
                        updateConversationStatus(text);
                    }

                    @Override
                    public void showUserMessage(String text) {
                        addConversationMessage(text, true);
                    }

                    @Override
                    public void showPetMessage(String text) {
                        addConversationMessage(text, false);
                    }

                    @Override
                    public void onConversationStopped() {
                        removeConversationBackCapture();
                        startPetForeground(false);
                    }
                });

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        // The physical accelerometer streams reliably on more OEM devices than the
        // virtual gravity sensor. A low-pass filter below isolates its gravity part.
        activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        usingRawAccelerometer = activeSensor != null;
        if (activeSensor == null) {
            activeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }
        if (activeSensor != null) {
            boolean registered = sensorManager.registerListener(
                    this, activeSensor, SensorManager.SENSOR_DELAY_GAME);
            Log.i(TAG, "Sensor registered=" + registered + ", name=" + activeSensor.getName());
        } else {
            Log.e(TAG, "No accelerometer or gravity sensor is available");
        }
        running = true;
        Choreographer.getInstance().postFrameCallback(petCollisionFrame);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureMusicBeatDetector();
        if (intent != null && ACTION_SHOW_BUBBLE.equals(intent.getAction())) {
            showSpeechBubble(intent.getStringExtra(EXTRA_BUBBLE_TEXT));
        } else if (intent != null && ACTION_START_CONVERSATION.equals(intent.getAction())) {
            startContinuousConversation();
        } else if (intent != null && ACTION_STOP_CONVERSATION.equals(intent.getAction())) {
            stopContinuousConversation();
        } else if (intent != null
                && ACTION_SHOW_CONVERSATION_BUTTON.equals(intent.getAction())) {
            showConversationButton();
        } else if (intent != null && ACTION_SUMMON_PET.equals(intent.getAction())) {
            summonSelectedPet();
        } else if (intent != null && ACTION_RECALL_ALL.equals(intent.getAction())) {
            stopSelf();
        } else if (intent != null && ACTION_REFRESH_SKIN.equals(intent.getAction())) {
            int skinId = intent.getIntExtra(EXTRA_SKIN_ID, -1);
            for (PetSlot slot : petSlots) {
                if (slot.skinId == skinId) slot.view.reloadSkin();
            }
        } else if (intent != null && ACTION_DELETE_SKIN.equals(intent.getAction())) {
            int skinId = intent.getIntExtra(EXTRA_SKIN_ID, -1);
            List<PetSlot> snapshot = new ArrayList<>(petSlots);
            for (PetSlot slot : snapshot) {
                if (slot.skinId == skinId) removePet(slot);
            }
        }
        return overlayAttached && Settings.canDrawOverlays(this)
                ? START_STICKY
                : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        petCount = 0;
        Choreographer.getInstance().removeFrameCallback(petCollisionFrame);
        if (conversationController != null) {
            conversationController.shutdown();
            conversationController = null;
        }
        if (musicBeatDetector != null) {
            musicBeatDetector.release();
            musicBeatDetector = null;
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (preferences != null && preferenceListener != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        }
        removeConversationButton();
        removeSpeechBubble();
        removeBubbleTouchView();
        removeConversationBackCapture();
        removeOutsideTouchObserver();
        if (windowManager != null) {
            for (PetSlot slot : new ArrayList<>(petSlots)) {
                if (slot.handle != null && slot.handleAttached) {
                    try { windowManager.removeView(slot.handle); }
                    catch (RuntimeException ignored) {}
                }
                slot.handleAttached = false;
            }
        }
        petSlots.clear();
        if (petLayer != null && windowManager != null) {
            try {
                windowManager.removeView(petLayer);
            } catch (RuntimeException ignored) {
                // The system may already have removed the overlay after permission changes.
            }
        }
        petLayer = null;
        petView = null;
        activePetSlot = null;
        overlayAttached = false;
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!receivedSensorEvent) {
            receivedSensorEvent = true;
            Log.i(TAG, "First sensor event received");
        }
        float x;
        float y;
        float z;
        if (usingRawAccelerometer) {
            // Low-pass filtering removes most hand tremor and isolates gravity.
            final float alpha = 0.82f;
            for (int i = 0; i < 3; i++) {
                filteredAcceleration[i] = alpha * filteredAcceleration[i]
                        + (1f - alpha) * event.values[i];
            }
            x = filteredAcceleration[0];
            y = filteredAcceleration[1];
            z = filteredAcceleration[2];
        } else {
            x = event.values[0];
            y = event.values[1];
            z = event.values[2];
        }

        float screenSensorX;
        float screenSensorY;
        int rotation = getDisplayRotation();
        switch (rotation) {
            case Surface.ROTATION_90:
                screenSensorX = y;
                screenSensorY = -x;
                break;
            case Surface.ROTATION_180:
                screenSensorX = -x;
                screenSensorY = -y;
                break;
            case Surface.ROTATION_270:
                screenSensorX = -y;
                screenSensorY = x;
                break;
            case Surface.ROTATION_0:
            default:
                screenSensorX = x;
                screenSensorY = y;
                break;
        }

        // Sensor X points right but measures support force, hence the minus sign.
        for (PetSlot slot : petSlots) {
            slot.view.setGravity(-screenSensorX, screenSensorY, z);
        }
    }

    private int getDisplayRotation() {
        DisplayManager displayManager = getSystemService(DisplayManager.class);
        Display display = displayManager == null
                ? null
                : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return display == null ? Surface.ROTATION_0 : display.getRotation();
    }

    private void ensureMusicBeatDetector() {
        if (musicBeatDetector != null
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        musicBeatDetector = new MusicBeatDetector(this, strength -> {
            float amplitudeScale = preferences.getInt(
                    PetPreferences.KEY_MUSIC_DANCE_PERCENT, 50) / 50f;
            if (amplitudeScale <= 0f) return;
            musicBeatIndex++;
            for (int index = 0; index < petSlots.size(); index++) {
                float sway = ((index + musicBeatIndex) & 1) == 0 ? -1f : 1f;
                petSlots.get(index).view.applyMusicBeat(
                        strength, sway, amplitudeScale);
            }
        });
        musicBeatDetector.start();
    }

    private void updateBubbleTouchRegion(float left, float top, float right, float bottom,
                                         boolean visible) {
        if (!visible || petView == null || windowManager == null) {
            removeBubbleTouchView();
            return;
        }
        petView.getLocationOnScreen(petLocationOnScreen);
        int nextX = petLocationOnScreen[0] + (int) Math.floor(left);
        int nextY = petLocationOnScreen[1] + (int) Math.floor(top);
        int nextWidth = Math.max(1, (int) Math.ceil(right - left));
        int nextHeight = Math.max(1, (int) Math.ceil(bottom - top));

        if (bubbleTouchView == null) {
            bubbleTouchView = new View(this);
            bubbleTouchView.setBackgroundColor(Color.TRANSPARENT);
            bubbleTouchView.setContentDescription("展开或收起对话气泡");
            bubbleTouchView.setOnClickListener(v -> { });
            bubbleTouchView.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_UP && petView != null) {
                    petView.getLocationOnScreen(petLocationOnScreen);
                    petView.toggleExpandableBubbleAt(
                            event.getRawX() - petLocationOnScreen[0],
                            event.getRawY() - petLocationOnScreen[1]);
                    view.performClick();
                }
                return true;
            });
            bubbleTouchParams = new WindowManager.LayoutParams(
                    nextWidth, nextHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            bubbleTouchParams.gravity = Gravity.TOP | Gravity.START;
            bubbleTouchParams.softInputMode =
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            useFullScreenCoordinates(bubbleTouchParams);
        }
        bubbleTouchParams.x = nextX;
        bubbleTouchParams.y = nextY;
        bubbleTouchParams.width = nextWidth;
        bubbleTouchParams.height = nextHeight;
        try {
            if (!bubbleTouchAttached) {
                windowManager.addView(bubbleTouchView, bubbleTouchParams);
                bubbleTouchAttached = true;
            } else {
                windowManager.updateViewLayout(bubbleTouchView, bubbleTouchParams);
            }
        } catch (RuntimeException ignored) {
            bubbleTouchAttached = false;
        }
    }

    private void removeBubbleTouchView() {
        if (bubbleTouchAttached && bubbleTouchView != null && windowManager != null) {
            try { windowManager.removeViewImmediate(bubbleTouchView); }
            catch (RuntimeException ignored) {}
        }
        bubbleTouchAttached = false;
        bubbleTouchView = null;
        bubbleTouchParams = null;
    }

    private void summonSelectedPet() {
        if (petLayer == null || windowManager == null || preferences == null) return;
        if (petSlots.size() >= MAX_PETS) {
            Toast.makeText(this, "最多只能召唤9只小鼻嘎", Toast.LENGTH_SHORT).show();
            return;
        }
        PetView view = new PetView(this);
        // Rapid repeated summons share one short settling window. Existing pets
        // become a quiet foundation while newcomers find non-overlapping places.
        crowdStabilizeUntilMillis = SystemClock.uptimeMillis() + 850L;
        view.setManagedByWorld(true);
        view.setBallColor(PetPreferences.getColor(this));
        view.setBallDiameterDp(preferences.getInt(
                PetPreferences.KEY_BALL_DIAMETER_DP, 52));
        view.setGravityStrengthPercent(preferences.getInt(
                PetPreferences.KEY_GRAVITY_PERCENT, 100));
        int selectedSkinId = preferences.getInt(PetPreferences.KEY_SKIN, 0);
        view.setSkin(selectedSkinId);
        view.setOpacityPercent(100);
        int index = petSlots.size();
        view.setSpawnFraction(0.14f + (index % 6) * 0.144f,
                0.18f + (index / 6) * 0.075f);
        PetSlot slot = new PetSlot(view, selectedSkinId);
        petSlots.add(slot);
        petLayer.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        view.setBubbleRegionListener((left, top, right, bottom, visible) -> {
            if (activePetSlot == slot) {
                updateBubbleTouchRegion(left, top, right, bottom, visible);
            }
        });
        addDragHandle(slot);
        activatePet(slot);
        petCount = petSlots.size();
    }

    private void addDragHandle(PetSlot slot) {
        int touchSize = Math.round(96f * getResources().getDisplayMetrics().density);
        slot.handle = new DragHandleView(this, slot.view, () -> {
            activatePet(slot);
            handlePetDoubleTap();
        },
                new DragHandleView.DismissGestureListener() {
                    @Override
                    public void onDismissZoneChanged(boolean visible) {
                        slot.view.setDismissZoneVisible(visible);
                    }

                    @Override
                    public boolean onDismissRelease(float rawX, float rawY,
                                                    float velocityX, float velocityY) {
                        return handleDismissRelease(slot, rawX, velocityX);
                    }
                });
        slot.handleParams = new WindowManager.LayoutParams(
                touchSize,
                touchSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        slot.handleParams.gravity = Gravity.TOP | Gravity.START;
        slot.handleParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        useFullScreenCoordinates(slot.handleParams);

        try {
            windowManager.addView(slot.handle, slot.handleParams);
            slot.handleAttached = true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to add drag handle", error);
            slot.handle = null;
            return;
        }
        updateDragHandleInteractionMode(slot);

        slot.view.setPositionListener((centerX, centerY, touchDiameter, dragging) -> {
            // PetView can start below the status bar while auxiliary overlay
            // windows use full-screen coordinates. Convert the pet's local
            // centre to screen coordinates so the invisible hit target is
            // exactly over the pixels the user sees.
            slot.view.getLocationOnScreen(petLocationOnScreen);
            slot.centerX = centerX + petLocationOnScreen[0];
            slot.centerY = centerY + petLocationOnScreen[1];
            slot.touchDiameter = touchDiameter;
            if (activePetSlot == slot) {
                syncActivePetPosition();
                updateAuxiliaryPositions();
            }
            int keyboardInset = getKeyboardInsetBottom();
            slot.view.setKeyboardInsetBottom(keyboardInset);
            if (slot.handle == null || slot.handleParams == null) return;
            boolean needsUpdate = false;
            if (interactionGuardEnabled || !slot.handleAttached) return;
            if (dragging) return;

            int nextSize = Math.round(touchDiameter);
            if (slot.handleParams.width != nextSize || slot.handleParams.height != nextSize) {
                slot.handleParams.width = nextSize;
                slot.handleParams.height = nextSize;
                needsUpdate = true;
            }
            int nextX = Math.round(centerX - slot.handleParams.width * 0.5f);
            int nextY = Math.round(centerY - slot.handleParams.height * 0.5f);
            if (slot.handleParams.x != nextX || slot.handleParams.y != nextY) {
                slot.handleParams.x = nextX;
                slot.handleParams.y = nextY;
                needsUpdate = true;
            }
            if (!needsUpdate) return;
            try {
                windowManager.updateViewLayout(slot.handle, slot.handleParams);
            } catch (RuntimeException ignored) {
                // A concurrent service shutdown can remove the handle first.
            }
        });
    }

    private boolean handleDismissRelease(PetSlot slot, float rawX, float velocityX) {
        if (windowManager == null || slot == null) return false;
        float screenWidth = slot.view.getWidth() > 0
                ? slot.view.getWidth() : getResources().getDisplayMetrics().widthPixels;
        float zoneStart = screenWidth * 0.76f;
        float projectedX = rawX + Math.max(0f, velocityX) * 0.18f;
        boolean enteredZone = rawX >= zoneStart
                || (velocityX >= 900f && projectedX >= screenWidth * 0.82f);
        if (!enteredZone) return false;
        slot.view.setDismissZoneVisible(false);
        overlayHandler.postDelayed(() -> removePet(slot), 150L);
        return true;
    }

    private void activatePet(PetSlot slot) {
        if (slot == null || !petSlots.contains(slot)) return;
        activePetSlot = slot;
        petView = slot.view;
        syncActivePetPosition();
        updateAuxiliaryPositions();
    }

    private void syncActivePetPosition() {
        if (activePetSlot == null) return;
        latestCenterX = activePetSlot.centerX;
        latestCenterY = activePetSlot.centerY;
        latestTouchDiameter = activePetSlot.touchDiameter;
    }

    private void removePet(PetSlot slot) {
        if (slot == null || !petSlots.remove(slot)) return;
        if (slot.handle != null && slot.handleAttached && windowManager != null) {
            try { windowManager.removeViewImmediate(slot.handle); }
            catch (RuntimeException ignored) {}
        }
        slot.handleAttached = false;
        if (petLayer != null) petLayer.removeView(slot.view);
        if (activePetSlot == slot) {
            if (conversationController != null && conversationController.isActive()) {
                stopContinuousConversation();
            }
            slot.view.clearConversation();
            activePetSlot = petSlots.isEmpty() ? null : petSlots.get(petSlots.size() - 1);
            petView = activePetSlot == null ? null : activePetSlot.view;
            syncActivePetPosition();
            removeConversationButton();
        }
        petCount = petSlots.size();
        if (petSlots.isEmpty()) stopSelf();
    }

    private void updateDragHandleInteractionMode() {
        if (windowManager == null) return;
        for (PetSlot slot : petSlots) updateDragHandleInteractionMode(slot);
        if (interactionGuardEnabled) {
            removeConversationButton();
            removeSpeechBubble();
            addOutsideTouchObserver();
        } else {
            removeOutsideTouchObserver();
        }
    }

    private void updateDragHandleInteractionMode(PetSlot slot) {
        if (slot == null || slot.handle == null || slot.handleParams == null
                || windowManager == null) return;
        slot.handle.setInteractionGuardEnabled(interactionGuardEnabled);
        if (interactionGuardEnabled) {
            slot.handleParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            slot.handleParams.alpha = 0f;
            if (slot.handleAttached) {
                try {
                    windowManager.removeViewImmediate(slot.handle);
                } catch (RuntimeException ignored) {
                    // The system may already have detached it.
                }
                slot.handleAttached = false;
            }
            return;
        }
        slot.handleParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        slot.handleParams.alpha = 1f;
        int normalSize = Math.max(1, Math.round(slot.touchDiameter));
        if (normalSize > 1) {
            slot.handleParams.width = normalSize;
            slot.handleParams.height = normalSize;
            slot.handleParams.x = Math.round(slot.centerX - normalSize * 0.5f);
            slot.handleParams.y = Math.round(slot.centerY - normalSize * 0.5f);
        }
        if (!slot.handleAttached) {
            try {
                windowManager.addView(slot.handle, slot.handleParams);
                slot.handleAttached = true;
            } catch (RuntimeException ignored) {
                return;
            }
        }
        try {
            windowManager.updateViewLayout(slot.handle, slot.handleParams);
        } catch (RuntimeException ignored) {
            // The overlay may be removed while the preference is changing.
        }
    }

    /**
     * Installs a one-pixel non-modal window. Android reports outside touch-downs to
     * this window without routing the gesture away from the app beneath it.
     */
    private void addOutsideTouchObserver() {
        if (outsideTouchObserverAttached || windowManager == null) return;
        if (outsideTouchObserver == null) {
            outsideTouchObserver = new View(this) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent event) {
                    observeOutsideTouch(event);
                    return true;
                }
            };
            outsideTouchObserver.setBackgroundColor(Color.TRANSPARENT);
        }
        outsideTouchObserverParams = new WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        outsideTouchObserverParams.gravity = Gravity.TOP | Gravity.START;
        outsideTouchObserverParams.x = 0;
        outsideTouchObserverParams.y = 0;
        outsideTouchObserverParams.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        useFullScreenCoordinates(outsideTouchObserverParams);
        try {
            windowManager.addView(outsideTouchObserver, outsideTouchObserverParams);
            outsideTouchObserverAttached = true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to add outside-touch observer", error);
        }
    }

    private void removeOutsideTouchObserver() {
        lastObservedTapTime = 0L;
        if (!outsideTouchObserverAttached || outsideTouchObserver == null
                || windowManager == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(outsideTouchObserver);
        } catch (RuntimeException ignored) {
            // The system may already have detached the observer.
        }
        outsideTouchObserverAttached = false;
        outsideTouchObserverParams = null;
    }

    private void observeOutsideTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_OUTSIDE && action != MotionEvent.ACTION_DOWN) return;
        float x = event.getRawX();
        float y = event.getRawY();
        PetSlot hit = null;
        double nearest = Double.MAX_VALUE;
        for (PetSlot slot : petSlots) {
            float radius = Math.max(dp(28), slot.touchDiameter * 0.5f);
            double distance = Math.hypot(x - slot.centerX, y - slot.centerY);
            if (distance <= radius && distance < nearest) {
                hit = slot;
                nearest = distance;
            }
        }
        if (hit == null) {
            lastObservedTapTime = 0L;
            return;
        }
        activatePet(hit);

        long now = event.getEventTime();
        boolean withinTime = lastObservedTapTime > 0L
                && now - lastObservedTapTime <= ViewConfiguration.getDoubleTapTimeout();
        boolean withinDistance = Math.hypot(x - lastObservedTapX, y - lastObservedTapY)
                <= dp(64);
        if (withinTime && withinDistance) {
            lastObservedTapTime = 0L;
            overlayHandler.post(this::handlePetDoubleTap);
            return;
        }
        lastObservedTapTime = now;
        lastObservedTapX = x;
        lastObservedTapY = y;
    }

    private void handlePetDoubleTap() {
        // Always expose the button. While a conversation is active it becomes
        // an explicit "end conversation" action instead of silently stopping.
        showConversationButton();
    }

    private void showConversationButton() {
        removeConversationButton();
        conversationButton = new Button(this);
        boolean conversationActive = conversationController != null
                && conversationController.isActive();
        conversationButton.setText(conversationActive ? "结束对话" : "对话");
        conversationButton.setAllCaps(false);
        conversationButton.setOnClickListener(v -> {
            removeConversationButton();
            if (conversationController != null && conversationController.isActive()) {
                stopContinuousConversation();
                return;
            }
            if (!hasConfiguredApiKey()) {
                showSpeechBubble("请先在 AI 设置中填写 API Key");
                Intent settingsIntent = new Intent(this, AiSettingsActivity.class);
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(settingsIntent);
                return;
            }
            // A transparent bridge activity grants Android's foreground microphone
            // eligibility without replacing the app currently visible to the user.
            Intent intent = new Intent(this, VoiceChatActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NO_HISTORY
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        });
        conversationButtonParams = new WindowManager.LayoutParams(
                dp(92), dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        conversationButtonParams.gravity = Gravity.TOP | Gravity.START;
        useFullScreenCoordinates(conversationButtonParams);
        positionConversationButton();
        try {
            windowManager.addView(conversationButton, conversationButtonParams);
            overlayHandler.postDelayed(removeConversationRunnable, 5000L);
        } catch (RuntimeException error) {
            conversationButton = null;
        }
    }

    private void startContinuousConversation() {
        if (conversationController == null || conversationController.isActive()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            showSpeechBubble("需要麦克风权限才能开始对话");
            return;
        }
        if (!startPetForeground(true)) return;
        addConversationBackCapture();
        conversationController.setConversationSkinId(
                activePetSlot == null ? 0 : activePetSlot.skinId);
        conversationController.start();
    }

    private void stopContinuousConversation() {
        if (conversationController != null) conversationController.stop();
        removeConversationBackCapture();
        clearConversationDisplay();
        startPetForeground(false);
    }

    private void addConversationBackCapture() {
        if (conversationBackView != null || windowManager == null) return;
        conversationBackView = new ConversationBackView(
                this, this::stopContinuousConversation);
        conversationBackParams = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        conversationBackParams.gravity = Gravity.TOP | Gravity.START;
        conversationBackParams.x = 0;
        conversationBackParams.y = 0;
        conversationBackParams.alpha = 0.01f;
        useFullScreenCoordinates(conversationBackParams);
        try {
            windowManager.addView(conversationBackView, conversationBackParams);
            conversationBackView.requestFocus();
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to add conversation back listener", error);
            conversationBackView = null;
            conversationBackParams = null;
        }
    }

    private void removeConversationBackCapture() {
        if (conversationBackView != null && windowManager != null) {
            try { windowManager.removeViewImmediate(conversationBackView); }
            catch (RuntimeException ignored) {}
        }
        conversationBackView = null;
        conversationBackParams = null;
    }

    private boolean hasConfiguredApiKey() {
        try {
            return !ApiKeyStore.load(this, "mimo").isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean startPetForeground(boolean withMicrophone) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (withMicrophone) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NOTIFICATION_ID, buildNotification(), type);
            } else if (withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            return true;
        } catch (SecurityException error) {
            Log.e(TAG, "Unable to enable foreground microphone", error);
            showSpeechBubble("系统暂时不允许后台使用麦克风，请重试");
            return false;
        }
    }

    private void showSpeechBubble(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String displayText = text.trim();
        updateConversationStatus(displayText);
        overlayHandler.removeCallbacks(removeBubbleRunnable);
        long duration = Math.min(18_000L, Math.max(7000L, displayText.length() * 120L));
        overlayHandler.postDelayed(removeBubbleRunnable, duration);
    }

    private void updateAuxiliaryPositions() {
        if (conversationButton != null && conversationButtonParams != null) {
            positionConversationButton();
            try {
                windowManager.updateViewLayout(conversationButton, conversationButtonParams);
            } catch (RuntimeException ignored) {}
        }
    }

    private void positionConversationButton() {
        if (conversationButtonParams == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        conversationButtonParams.x = Math.max(0, Math.min(
                screenWidth - conversationButtonParams.width,
                Math.round(latestCenterX - conversationButtonParams.width * 0.5f)));
        int above = Math.round(latestCenterY - latestTouchDiameter * 0.5f - dp(56));
        conversationButtonParams.y = above >= 0 ? above
                : Math.round(latestCenterY + latestTouchDiameter * 0.5f + dp(8));
    }

    private void removeConversationButton() {
        overlayHandler.removeCallbacks(removeConversationRunnable);
        if (conversationButton != null && windowManager != null) {
            try { windowManager.removeView(conversationButton); } catch (RuntimeException ignored) {}
        }
        conversationButton = null;
        conversationButtonParams = null;
    }

    private void removeSpeechBubble() {
        overlayHandler.removeCallbacks(removeBubbleRunnable);
        updateConversationStatus("");
    }

    private void updateConversationStatus(String text) {
        PetView current = petView;
        if (current == null) return;
        current.post(() -> current.setConversationStatus(text));
    }

    private void addConversationMessage(String text, boolean fromUser) {
        PetView current = petView;
        if (current == null || text == null || text.trim().isEmpty()) return;
        current.post(() -> current.addChatMessage(text, fromUser));
    }

    private void clearConversationDisplay() {
        PetView current = petView;
        if (current == null) return;
        current.post(current::clearConversation);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void useFullScreenCoordinates(WindowManager.LayoutParams params) {
        params.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
        }
    }

    private int getKeyboardInsetBottom() {
        if (petView == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            WindowInsets metricInsets = metrics.getWindowInsets();
            if (metricInsets.isVisible(WindowInsets.Type.ime())) {
                Insets ime = metricInsets.getInsets(WindowInsets.Type.ime());
                return Math.max(0, ime.bottom);
            }
            WindowInsets insets = petView.getRootWindowInsets();
            if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                return Math.max(0, ime.bottom);
            }
        }

        Rect visibleFrame = new Rect();
        petView.getWindowVisibleDisplayFrame(visibleFrame);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int obscuredHeight = Math.max(0, screenHeight - visibleFrame.bottom);
        return screenHeight > 0 && obscuredHeight > screenHeight * 0.20f
                ? obscuredHeight
                : 0;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No special handling is needed; the physics naturally smooths small changes.
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "小鼻嘎运行状态",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("小鼻嘎需要持续运行才能感受手机方向");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent startConversation = new Intent(this, VoiceChatActivity.class);
        startConversation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        PendingIntent conversationIntent = PendingIntent.getActivity(
                this,
                1,
                startConversation,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("小鼻嘎正在活动")
                .setContentText("转动手机，让小鼻嘎在屏幕上滚动")
                .setContentIntent(pendingIntent)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_launcher, "对话", conversationIntent).build())
                .setOngoing(true)
                .setColor(Color.rgb(98, 85, 246))
                .build();
    }
}
