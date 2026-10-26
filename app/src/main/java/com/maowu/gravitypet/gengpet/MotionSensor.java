package com.maowu.gravitypet.gengpet;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Surface;

/** Gravity and comfort motion are deliberately separate signals. No vehicle-motion claim. */
public final class MotionSensor implements SensorEventListener {
  public interface Listener {
    void onMotion(float gravityX, float gravityY, float cueX, float cueY);
  }

  private final SensorManager manager;
  private final DisplayManager displays;
  private final Listener listener;
  private final Sensor gravitySource, linearSource;
  private final boolean raw;
  private final float[] filtered = new float[3];
  private boolean running, initialized;
  private float cueX, cueY, referenceX, referenceY, gravityX, gravityY = 9.81f;
  private long lastTime;

  public MotionSensor(Context context, Listener listener) {
    this.listener = listener;
    manager = context.getSystemService(SensorManager.class);
    displays = context.getSystemService(DisplayManager.class);
    Sensor acceleration =
        manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    raw = acceleration != null;
    gravitySource =
        raw ? acceleration : manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    linearSource =
        manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
  }

  public boolean available() {
    return gravitySource != null;
  }

  public void start(boolean lowPower) {
    if (running) return;
    initialized = false;
    lastTime = 0;
    if (manager == null || gravitySource == null) {
      listener.onMotion(0, 9.81f, 0, 0);
      return;
    }
    running = manager.registerListener(this, gravitySource, lowPower ? 100_000 : 33_333);
    if (running && linearSource != null)
      manager.registerListener(this, linearSource, lowPower ? 100_000 : 33_333);
    if (!running) listener.onMotion(0, 9.81f, 0, 0);
  }

  public void stop() {
    if (manager != null) manager.unregisterListener(this);
    running = false;
    lastTime = 0;
  }

  public void resetReference() {
    referenceX = cueX;
    referenceY = cueY;
  }

  private float[] screen(float x, float y) {
    Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
    int rotation = display == null ? Surface.ROTATION_0 : display.getRotation();
    switch (rotation) {
      case Surface.ROTATION_90:
        return new float[] {-y, -x};
      case Surface.ROTATION_180:
        return new float[] {x, -y};
      case Surface.ROTATION_270:
        return new float[] {y, x};
      default:
        return new float[] {-x, y};
    }
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (!running) return;
    if (event.sensor == gravitySource) {
      float dt =
          lastTime == 0
              ? .033f
              : PhysicsWorld.clamp((event.timestamp - lastTime) / 1e9f, .005f, .2f);
      lastTime = event.timestamp;
      float alpha = (float) Math.exp(-dt / .18f);
      for (int i = 0; i < 3; i++)
        filtered[i] =
            !initialized || !raw
                ? event.values[i]
                : alpha * filtered[i] + (1 - alpha) * event.values[i];
      initialized = true;
      float[] gravity = screen(filtered[0], filtered[1]);
      gravityX = gravity[0];
      gravityY = gravity[1];
      if (linearSource == null && raw) {
        float[] linear = screen(event.values[0] - filtered[0], event.values[1] - filtered[1]);
        filterCue(linear[0], linear[1]);
      }
    } else if (event.sensor == linearSource) {
      float[] linear = screen(event.values[0], event.values[1]);
      filterCue(linear[0], linear[1]);
    }
    listener.onMotion(gravityX, gravityY, deadzone(cueX - referenceX), deadzone(cueY - referenceY));
  }

  private void filterCue(float x, float y) {
    cueX += .15f * (PhysicsWorld.clamp(x, -3, 3) - cueX);
    cueY += .15f * (PhysicsWorld.clamp(y, -3, 3) - cueY);
  }

  private float deadzone(float v) {
    return Math.abs(v) < .12f ? 0 : PhysicsWorld.clamp(v / 3, -1, 1);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
