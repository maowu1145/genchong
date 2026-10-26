package com.maowu.gravitypet.gengpet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Shared, Android-independent fixed-step physics in screen pixels. Configured pets use boxob's
 * actual 24-node XPBD skin, continuous collision sweep and UV mesh. Unconfigured worlds retain the
 * earlier PetView-derived circle/impulse/spring solver for compatibility.
 */
public final class PhysicsWorld {
  public static final float STEP = 1f / 120f;
  public static final int MAX_BODIES = 6, MAX_SUBSTEPS = 4;

  public static final class Body {
    public final String id;
    public float x, y, vx, vy, radius, edgeX, edgeY, softness = .5f;
    public float angle, angularVelocity, deformation, deformationVelocity;
    public float normalX = 0, normalY = 1;
    public boolean gravity = true, pinned, held, toolOpen, sleeping;

    /** Actual boxob XPBD cage; all node positions are absolute screen pixels. */
    BalloonBody soft;

    private BalloonBody proxy;
    private BalloonCage softCage;
    private float[] contourUV;
    private float softSizePixels, publishedX, publishedY, publishedVx, publishedVy;
    private float targetX, targetY, appliedSoftness = Float.NaN;
    private boolean lastHeld, lastLocked, lastGravity;

    /** Null on success. A failed first configuration retains the legacy branch. */
    public String softFailure;

    private float settleTime;
    private boolean contact;

    public Body(String id, float x, float y, float radius) {
      this.id = id;
      this.x = x;
      this.y = y;
      this.radius = Math.max(1, radius);
    }

    public void wake() {
      sleeping = false;
      settleTime = 0;
    }

    public boolean fixed() {
      return pinned || held || toolOpen;
    }

    public float normalScale() {
      return Math.max(.58f, 1 - deformation);
    }
  }

  private final ArrayList<Body> bodies = new ArrayList<>();
  private float left, top, right = 1080, bottom = 1920, gx, gy = 9.81f;
  private float accumulator, pixelsPerMeter = 160;
  private long lastNanos;
  private BalloonWorld softWorld;
  private Body heldSoft;
  private final double[] softPoint = new double[2];
  private final boolean[] softSupported = new boolean[MAX_BODIES];
  private final boolean[][] softContact = new boolean[MAX_BODIES][MAX_BODIES];
  private long softSteps;
  public boolean comfort;

  public List<Body> bodies() {
    return Collections.unmodifiableList(bodies);
  }

  public boolean add(Body b) {
    if (b == null || bodies.size() >= MAX_BODIES) return false;
    for (Body current : bodies) if (current.id.equals(b.id)) return false;
    bodies.add(b);
    constrain(b);
    if (b.softCage != null) createSoft(b);
    wakeAll();
    return true;
  }

  public void remove(Body b) {
    BalloonBody nativeBody = nativeBody(b);
    if (nativeBody != null && softWorld != null) softWorld.remove(nativeBody.id);
    if (heldSoft == b) {
      heldSoft = null;
      if (softWorld != null) softWorld.endDrag();
    }
    b.soft = b.proxy = null;
    bodies.remove(b);
    if (!hasSoftBodies()) {
      for (Body remaining : bodies) remaining.proxy = null;
      softWorld = null;
      heldSoft = null;
    }
    wakeAll();
  }

  public void wakeAll() {
    for (Body b : bodies) b.wake();
  }

  public void resetClock() {
    lastNanos = 0;
    accumulator = 0;
    if (softWorld != null) softWorld.resetClock();
  }

  public void setPixelsPerMeter(float value) {
    pixelsPerMeter = Math.max(50, value);
  }

  public void setBounds(float l, float t, float r, float b) {
    boolean changed = left != l || top != t || right != r || bottom != b;
    left = l;
    top = t;
    right = Math.max(l + 2, r);
    bottom = Math.max(t + 2, b);
    if (changed && softWorld != null) rebuildSoftWorld();
    for (Body body : bodies) {
      constrain(body);
      body.wake();
    }
    resetClock();
  }

  public void setGravity(float x, float y) {
    x = clamp(x, -15, 15);
    y = clamp(y, -15, 15);
    // Accumulate direction change against the previous significant value, so slowly
    // tilting the phone also wakes a settled pile (not only sudden sensor changes).
    if (Math.hypot(x - gx, y - gy) > .45) {
      gx = x;
      gy = y;
      wakeAll();
    }
  }

  public int advance(long nanos) {
    if (lastNanos == 0) {
      lastNanos = nanos;
      return 0;
    }
    long elapsedNanos = nanos - lastNanos;
    float elapsed = clamp(elapsedNanos / 1_000_000_000f, 0, .05f);
    lastNanos = nanos;
    if (hasSoftBodies()) {
      // Like boxob's native entry: prepare/publish once per display frame. BalloonWorld
      // owns its 180 Hz accumulator; do not wrap it in the legacy 120 Hz catch-up loop.
      accumulator = 0;
      double softElapsed = Math.max(0, Math.min(elapsedNanos / 1_000_000_000d, 1d / 30));
      if (softElapsed <= 0) return 0;
      stepSoft(softElapsed);
      return 1;
    }
    accumulator = Math.min(.05f, accumulator + elapsed);
    int steps = 0;
    while (accumulator >= STEP && steps < MAX_SUBSTEPS) {
      step(STEP);
      accumulator -= STEP;
      steps++;
    }
    if (steps == MAX_SUBSTEPS && accumulator >= STEP) accumulator %= STEP;
    return steps;
  }

  public void throwBody(Body b, float vx, float vy) {
    b.held = false;
    b.wake();
    float limit = Math.min(6000, b.radius * 55) * (comfort ? .25f : 1);
    float speed = (float) Math.hypot(vx, vy), scale = speed > limit ? limit / speed : 1;
    b.vx = b.pinned ? 0 : vx * scale;
    b.vy = b.pinned ? 0 : vy * scale;
    BalloonBody mesh = nativeBody(b);
    if (mesh != null) {
      if (heldSoft == b) {
        softWorld.endDrag();
        heldSoft = null;
      }
      mesh.releaseVelocity(b.vx, b.vy);
      b.publishedVx = b.vx;
      b.publishedVy = b.vy;
    }
    if (softWorld != null) wakeAll();
  }

  public void constrain(Body b) {
    // The native node planes own the soft floor. Window/legacy circle extents never
    // feed back into the mesh; a render call must not modify an accepted soft pose.
    if (nativeBody(b) != null) return;
    float rx = Math.min(Math.max(b.radius, b.edgeX), (right - left) / 2),
        ry = Math.min(Math.max(b.radius, b.edgeY), (bottom - top) / 2);
    b.x = clamp(b.x, left + rx, right - rx);
    b.y = clamp(b.y, top + ry, bottom - ry);
  }

  public boolean isSettled() {
    for (Body b : bodies) {
      if (nativeBody(b) != null) {
        if (b.held || ((!b.sleeping || needsUpright(b)) && !b.pinned && !b.toolOpen)) return false;
      } else if ((!b.sleeping && !b.fixed()) || Math.abs(b.deformation) > .001f) return false;
    }
    return true;
  }

  /** Public for deterministic desktop tests; callers normally use advance(). */
  public void step(float dt) {
    dt = clamp(dt, 0, STEP);
    if (dt == 0) return;
    if (hasSoftBodies()) {
      stepSoft(dt);
      return;
    }
    for (Body b : bodies) {
      b.contact = false;
      spring(b, dt);
      if (b.fixed() || b.sleeping) {
        b.contact = b.sleeping;
        b.vx = b.vy = 0;
        continue;
      }
      float gain = comfort ? .22f : 1;
      if (b.gravity) {
        b.vx += gx * pixelsPerMeter * gain * dt;
        b.vy += gy * pixelsPerMeter * gain * dt;
      }
      float drag = (float) Math.pow(comfort ? .1f : .84f, dt);
      b.vx *= drag;
      b.vy *= drag;
      float maxSpeed = Math.min(6000, b.radius * 55);
      float speed = (float) Math.hypot(b.vx, b.vy);
      if (speed > maxSpeed) {
        b.vx *= maxSpeed / speed;
        b.vy *= maxSpeed / speed;
      }
      b.x += b.vx * dt;
      b.y += b.vy * dt;
      boundaries(b, dt);
      b.angularVelocity += (-18 * (float) Math.sin(b.angle) - 5.5f * b.angularVelocity) * dt;
      b.angle += b.angularVelocity * dt;
      b.angle = (float) Math.atan2(Math.sin(b.angle), Math.cos(b.angle));
    }
    for (int pass = 0; pass < 3; pass++) {
      for (int i = 0; i < bodies.size(); i++)
        for (int j = i + 1; j < bodies.size(); j++) collide(bodies.get(i), bodies.get(j));
      for (Body b : bodies) constrain(b);
    }
    for (Body b : bodies) {
      if (b.fixed()) continue;
      if (b.contact) {
        b.vx *= .92f;
        b.vy *= .92f;
        b.angularVelocity *= .9f;
      }
      boolean resting = b.contact || !b.gravity || Math.hypot(gx, gy) < .12;
      if (resting && Math.hypot(b.vx, b.vy) < 38 && Math.abs(b.angularVelocity) < .45f) {
        b.settleTime += dt;
        if (b.settleTime > .3f) {
          b.sleeping = true;
          b.vx = b.vy = b.angularVelocity = 0;
        }
      } else {
        b.settleTime = 0;
        b.sleeping = false;
      }
    }
  }

  /**
   * Opt an instance into boxob's real 24-node XPBD skin. UV is interleaved x,y in [0,1] of the
   * original image; sizePixels is the FULL texture width, not radius. Call after add(). Repeating
   * equal metadata/size is a strict no-op preserving object identity, deformed pose, momentum and
   * sleep. No Android dependency.
   */
  public void configureSoft(Body b, float[] contourUV, float sizePixels) {
    if (b == null) return;
    if (b.soft != null && b.softSizePixels == sizePixels && Arrays.equals(b.contourUV, contourUV))
      return;
    if (!Float.isFinite(sizePixels) || sizePixels < 8 || sizePixels > 4096) {
      b.softFailure = "Invalid full texture size";
      return;
    }
    BalloonCage cage;
    try {
      cage = BalloonCage.fromUV(contourUV, BalloonWorld.NODES);
    } catch (IllegalArgumentException invalid) {
      b.softFailure = invalid.getMessage();
      return;
    }
    BalloonCage oldCage = b.softCage;
    float[] oldContour = b.contourUV;
    float oldSize = b.softSizePixels;
    b.softCage = cage;
    b.contourUV = contourUV.clone();
    b.softSizePixels = sizePixels;
    if (!bodies.contains(b)) {
      b.softFailure = null;
      return;
    }
    if (!createSoft(b)) {
      b.softCage = oldCage;
      b.contourUV = oldContour;
      b.softSizePixels = oldSize;
    }
  }

  private BalloonBody nativeBody(Body b) {
    return b.soft != null ? b.soft : b.proxy;
  }

  private boolean hasSoftBodies() {
    for (Body b : bodies) if (b.soft != null) return true;
    return false;
  }

  private BalloonWorld newSoftWorld() {
    BalloonWorld world = new BalloonWorld(Math.max(30, right), Math.max(30, bottom));
    world.viewport(left, top, Math.max(left + 30, right), Math.max(top + 30, bottom));
    return world;
  }

  private boolean createSoft(Body b) {
    if (softWorld == null) softWorld = newSoftWorld();
    BalloonBody old = nativeBody(b);
    if (old != null) softWorld.remove(old.id);
    if (heldSoft == b) {
      heldSoft = null;
      softWorld.endDrag();
    }
    BalloonBody replacement = null;
    try {
      replacement = softWorld.addNear(b.x, b.y, b.softSizePixels * .5, b.softCage, b.angle);
    } catch (IllegalArgumentException invalid) {
      b.softFailure = invalid.getMessage();
    }
    if (replacement == null) {
      if (old != null) softWorld.restoreBody(old);
      b.softFailure = "No non-overlapping space for soft contour";
      return false;
    }
    b.soft = replacement;
    b.proxy = null;
    b.softFailure = null;
    replacement.driven = true;
    replacement.setSoftness(effectiveSoftness(b.softness));
    replacement.lowerCenterOfMass();
    replacement.releaseVelocity(b.vx, b.vy);
    b.appliedSoftness = b.softness;
    b.lastHeld = false;
    b.lastLocked = false;
    b.lastGravity = b.gravity;
    publishSoft(b);
    b.targetX = b.x;
    b.targetY = b.y;
    wakeAll();
    return true;
  }

  private void ensureProxies() {
    // Mixed worlds are uncommon (e.g. one failed asset); a rigid circle proxy still
    // collides with soft neighbors. Entirely legacy worlds use the old solver unchanged.
    for (Body b : bodies)
      if (nativeBody(b) == null) {
        BalloonBody proxy =
            softWorld.addNear(b.x, b.y, b.radius, BalloonCage.circle(BalloonWorld.NODES), b.angle);
        if (proxy == null) {
          b.softFailure = "No room for mixed-world collision proxy";
          continue;
        }
        b.proxy = proxy;
        proxy.rigid = true;
        proxy.driven = true;
        proxy.releaseVelocity(b.vx, b.vy);
        b.lastGravity = b.gravity;
        b.appliedSoftness = b.softness;
        publishSoft(b);
        b.targetX = b.x;
        b.targetY = b.y;
      }
  }

  private void rebuildSoftWorld() {
    // Only viewport changes rebuild; normal reload/configure never does. The texture
    // center, fitted rotation and mean momentum are retained, and placement is validated.
    softWorld = newSoftWorld();
    heldSoft = null;
    for (Body b : bodies) {
      b.soft = b.proxy = null;
      b.lastHeld = false;
      b.lastLocked = false;
    }
    for (Body b : bodies) if (b.softCage != null) createSoft(b);
    if (hasSoftBodies()) ensureProxies();
  }

  private void stepSoft(double dt) {
    ensureProxies();
    boolean wake = false;
    for (Body b : bodies) {
      BalloonBody mesh = nativeBody(b);
      if (mesh == null) continue;
      boolean locked = (b.pinned || b.toolOpen) && !b.held;
      boolean moved = Math.abs(b.x - b.publishedX) > .01f || Math.abs(b.y - b.publishedY) > .01f;
      if (moved) {
        b.targetX = b.x;
        b.targetY = b.y;
        wake = true;
      }
      if (b.held != b.lastHeld
          || locked != b.lastLocked
          || b.gravity != b.lastGravity
          || b.softness != b.appliedSoftness) wake = true;
      if (b.softness != b.appliedSoftness) {
        mesh.setSoftness(effectiveSoftness(b.softness));
        b.appliedSoftness = b.softness;
      }
      if (locked != b.lastLocked) {
        mesh.setLocked(locked);
        Arrays.fill(mesh.vx, 0);
        Arrays.fill(mesh.vy, 0);
      }
      if (b.held && heldSoft != b) {
        softWorld.endDrag();
        heldSoft = b;
        softWorld.beginDrag(mesh.id, mesh.centerX(), mesh.centerY());
        if (!moved) {
          b.targetX = b.publishedX;
          b.targetY = b.publishedY;
        }
      } else if (!b.held && heldSoft == b) {
        softWorld.endDrag();
        heldSoft = null;
      }
      if (b.held) {
        mesh.mapUVExtended(.5, .5, softPoint);
        softWorld.dragTo(
            mesh.centerX() + b.targetX - softPoint[0], mesh.centerY() + b.targetY - softPoint[1]);
      } else if (moved) {
        // External repositioning (e.g. display change) is validated like a spawn,
        // never written directly into native nodes behind the continuous solver.
        if (b.soft != null) {
          createSoft(b);
          mesh = nativeBody(b);
        } else {
          b.x = b.publishedX;
          b.y = b.publishedY;
        }
      }
      mesh.setLocked(locked);
      if (!b.held && !locked && (b.vx != b.publishedVx || b.vy != b.publishedVy))
        mesh.releaseVelocity(b.vx, b.vy);
      b.lastHeld = b.held;
      b.lastLocked = locked;
      b.lastGravity = b.gravity;
      mesh.driven = true;
      float gain = b.gravity ? (comfort ? .22f : 1) : 0;
      mesh.accelerationX = gx * pixelsPerMeter * gain;
      mesh.accelerationY = gy * pixelsPerMeter * gain;
      if (!locked && !b.held && b.sleeping && needsUpright(b)) {
        b.wake();
        wake = true;
      }
      if (b.soft != null && !locked && !b.held && !b.sleeping && gain > 0 && gx * gx + gy * gy > .1)
        mesh.stabilizeUpright(dt, Math.atan2(-gx, gy), b.angle, touchesSupport(mesh));
      if (comfort) {
        double damping = Math.exp(-2 * dt);
        for (int i = 0; i < mesh.count; i++) {
          mesh.vx[i] *= damping;
          mesh.vy[i] *= damping;
        }
      }
    }
    if (wake) wakeAll();
    boolean active = false;
    for (Body b : bodies) if (b.held || (!b.sleeping && !b.pinned && !b.toolOpen)) active = true;
    if (!active) return;
    // Sleep is committed as a world group. A moving neighbor must never hit an
    // artificially infinite-mass sleeping body and lose the impact/wake impulse.
    for (Body b : bodies) if (b.sleeping) b.wake();
    softWorld.step(dt);
    softSteps++;
    boolean allQuiet = true;
    for (int i = 0; i < bodies.size(); i++) {
      Body b = bodies.get(i);
      BalloonBody mesh = nativeBody(b);
      if (mesh == null) {
        allQuiet = false;
        continue;
      }
      double maxSpeedSquared = 0;
      for (int j = 0; j < mesh.count; j++)
        maxSpeedSquared =
            Math.max(maxSpeedSquared, mesh.vx[j] * mesh.vx[j] + mesh.vy[j] * mesh.vy[j]);
      publishSoft(b);
      boolean locked = (b.pinned || b.toolOpen) && !b.held;
      // Density-scaled numerical contact jitter: 264 px pets allow 4.75 px/s
      // (about 1.6 dp/s on the target phone), still requiring 0.5 s and real support.
      double quietSpeed = Math.max(1.5, b.soft != null ? b.softSizePixels * .018 : b.radius * .036);
      if (!locked && (b.held || needsUpright(b) || maxSpeedSquared > quietSpeed * quietSpeed))
        b.settleTime = 0;
      else b.settleTime += dt;
      if (!locked && b.settleTime < .5f) allQuiet = false;
      softSupported[i] = locked || !b.gravity || Math.hypot(gx, gy) < .12 || touchesSupport(mesh);
      for (int j = 0; j < bodies.size(); j++) softContact[i][j] = false;
    }
    // Support graph is needed only when every mesh is already quiet enough to sleep.
    // Avoid expensive contour-distance checks throughout active motion.
    if (!allQuiet) return;
    for (int i = 0; i < bodies.size(); i++)
      for (int j = i + 1; j < bodies.size(); j++) {
        BalloonBody a = nativeBody(bodies.get(i)), b = nativeBody(bodies.get(j));
        if (a != null && b != null)
          softContact[i][j] =
              softContact[j][i] =
                  softWorld.touching(a, b) || BalloonWorld.conflict(a, b, BalloonWorld.SKIN + .35);
      }
    for (int pass = 0; pass < bodies.size(); pass++)
      for (int i = 0; i < bodies.size(); i++)
        if (softSupported[i])
          for (int j = 0; j < bodies.size(); j++) if (softContact[i][j]) softSupported[j] = true;
    for (int i = 0; i < bodies.size(); i++)
      if (!softSupported[i]) {
        bodies.get(i).settleTime = 0;
        allQuiet = false;
      }
    if (allQuiet)
      for (Body b : bodies) {
        BalloonBody mesh = nativeBody(b);
        if (mesh == null) continue;
        b.sleeping = true;
        b.vx = b.vy = b.angularVelocity = 0;
        b.publishedVx = b.publishedVy = 0;
        Arrays.fill(mesh.vx, 0);
        Arrays.fill(mesh.vy, 0);
      }
  }

  /** UI 0..1 remains monotonic; 50% is substantially firmer than the old direct mapping. */
  static double effectiveSoftness(float slider) {
    double value = Float.isFinite(slider) ? Math.max(0, Math.min(1, slider)) : .3;
    return .7 * value * value;
  }

  private boolean needsUpright(Body b) {
    if (b.soft == null || !b.gravity || gx * gx + gy * gy <= .1) return false;
    double error = Math.atan2(-gx, gy) - b.angle;
    return Math.abs(Math.atan2(Math.sin(error), Math.cos(error))) > Math.PI / 6;
  }

  private boolean touchesSupport(BalloonBody mesh) {
    double margin = BalloonWorld.SKIN + .35;
    for (int i = 0; i < mesh.count; i++) {
      if ((gx < -.12 && mesh.x[i] <= left + margin)
          || (gx > .12 && mesh.x[i] >= right - margin)
          || (gy < -.12 && mesh.y[i] <= top + margin)
          || (gy > .12 && mesh.y[i] >= bottom - margin)) return true;
    }
    return false;
  }

  private void publishSoft(Body b) {
    BalloonBody mesh = nativeBody(b);
    if (mesh == null) return;
    mesh.mapUVExtended(.5, .5, softPoint);
    b.x = (float) softPoint[0];
    b.y = (float) softPoint[1];
    double cx = mesh.centerX(),
        cy = mesh.centerY(),
        vx = 0,
        vy = 0,
        dot = 0,
        cross = 0,
        spin = 0,
        moment = 0;
    for (int i = 0; i < mesh.count; i++) {
      vx += mesh.vx[i];
      vy += mesh.vy[i];
      double rx = mesh.restU[i] - mesh.restCenterU,
          ry = mesh.restV[i] - mesh.restCenterV,
          px = mesh.x[i] - cx,
          py = mesh.y[i] - cy;
      dot += rx * px + ry * py;
      cross += rx * py - ry * px;
      spin += px * mesh.vy[i] - py * mesh.vx[i];
      moment += px * px + py * py;
    }
    b.vx = (float) (vx / mesh.count);
    b.vy = (float) (vy / mesh.count);
    b.angle = (float) Math.atan2(cross, dot);
    b.angularVelocity = (float) (spin / Math.max(1, moment));
    // Diagnostic only. Renderer must draw the cage once, with NO legacy squash transform.
    b.deformation = b.soft == null ? 0 : (float) mesh.principalDeformation();
    b.deformationVelocity = 0;
    b.publishedX = b.x;
    b.publishedY = b.y;
    b.publishedVx = b.vx;
    b.publishedVy = b.vy;
  }

  /** Allocation-free geometry read for window sizing. Does not constrain or mutate physics. */
  public boolean softBounds(Body b, float[] out) {
    if (b == null || b.soft == null || out == null || out.length < 4) return false;
    out[0] = out[1] = Float.POSITIVE_INFINITY;
    out[2] = out[3] = Float.NEGATIVE_INFINITY;
    for (int i = 0; i < b.soft.count; i++) {
      out[0] = Math.min(out[0], (float) b.soft.x[i]);
      out[1] = Math.min(out[1], (float) b.soft.y[i]);
      out[2] = Math.max(out[2], (float) b.soft.x[i]);
      out[3] = Math.max(out[3], (float) b.soft.y[i]);
    }
    return true;
  }

  long softPhysicsAttempts() {
    return softWorld == null ? 0 : softWorld.physicsAttempts;
  }

  long softRollbacks() {
    return softWorld == null ? 0 : softWorld.rollbacks;
  }

  long softBudgetExhaustions() {
    return softWorld == null ? 0 : softWorld.budgetExhaustions;
  }

  double softDiscardedSeconds() {
    return softWorld == null ? 0 : softWorld.discardedSeconds;
  }

  long softStepCount() {
    return softSteps;
  }

  private void boundaries(Body b, float dt) {
    float rx = Math.max(b.radius, b.edgeX),
        ry = Math.max(b.radius, b.edgeY),
        nx = 0,
        ny = 0,
        impact = 0;
    if (b.x <= left + rx) {
      b.contact = true;
      if (b.vx < 0) {
        impact = -b.vx;
        nx = 1;
        b.vx = bounce(b.vx);
      }
    }
    if (b.x >= right - rx) {
      b.contact = true;
      if (b.vx > 0) {
        impact = b.vx;
        nx = -1;
        b.vx = bounce(b.vx);
      }
    }
    if (b.y <= top + ry) {
      b.contact = true;
      if (b.vy < 0) {
        impact = Math.max(impact, -b.vy);
        ny = 1;
        b.vy = bounce(b.vy);
      }
    }
    if (b.y >= bottom - ry) {
      b.contact = true;
      if (b.vy > 0) {
        impact = Math.max(impact, b.vy);
        ny = -1;
        b.vy = bounce(b.vy);
      }
    }
    constrain(b);
    if ((b.y <= top + ry + .5f && gy < 0) || (b.y >= bottom - ry - .5f && gy > 0)) {
      b.vx = friction(b.vx, gx, gy, dt);
    }
    if ((b.x <= left + rx + .5f && gx < 0) || (b.x >= right - rx - .5f && gx > 0)) {
      b.vy = friction(b.vy, gy, gx, dt);
    }
    if (impact > 100) deform(b, nx, ny, impact);
  }

  private float bounce(float v) {
    return Math.abs(v) >= 70 ? -v * (comfort ? .12f : .55f) : 0;
  }

  private float friction(float v, float tangent, float normal, float dt) {
    if (Math.abs(v) < 18 && Math.abs(tangent) <= .45f * Math.abs(normal)) return 0;
    float reduction = .30f * Math.abs(normal) * pixelsPerMeter * dt;
    return Math.signum(v) * Math.max(0, Math.abs(v) - reduction);
  }

  private float inverseMass(Body b, float dx, float dy) {
    if (b.fixed() || b.sleeping) return 0;
    // A body trapped against an edge must not absorb positional correction.
    float rx = Math.max(b.radius, b.edgeX), ry = Math.max(b.radius, b.edgeY);
    if ((dx < -.1 && b.x <= left + rx + .8f)
        || (dx > .1 && b.x >= right - rx - .8f)
        || (dy < -.1 && b.y <= top + ry + .8f)
        || (dy > .1 && b.y >= bottom - ry - .8f)) return 0;
    return 1 / (b.radius * b.radius);
  }

  private void collide(Body a, Body b) {
    float dx = b.x - a.x, dy = b.y - a.y, dist = (float) Math.hypot(dx, dy);
    float penetration = a.radius + b.radius - dist;
    if (penetration < -3) return;
    a.contact = b.contact = true;
    if (penetration <= 0) return;
    float nx = dist < .0001f ? 1 : dx / dist, ny = dist < .0001f ? 0 : dy / dist;
    float rvx = b.vx - a.vx, rvy = b.vy - a.vy, speed = rvx * nx + rvy * ny;
    if (a.held || b.held || speed < -35) {
      a.wake();
      b.wake();
    }
    float ia = inverseMass(a, -nx, -ny), ib = inverseMass(b, nx, ny), sum = ia + ib;
    if (sum == 0) return;
    float correction = Math.max(0, penetration - .8f) * .62f / sum;
    a.x -= nx * correction * ia;
    a.y -= ny * correction * ia;
    b.x += nx * correction * ib;
    b.y += ny * correction * ib;
    if (speed >= 0) return;
    float restitution = -speed >= 70 ? (comfort ? .1f : .48f) : 0;
    float impulse = -(1 + restitution) * speed / sum;
    a.vx -= nx * impulse * ia;
    a.vy -= ny * impulse * ia;
    b.vx += nx * impulse * ib;
    b.vy += ny * impulse * ib;
    float tx = -ny, ty = nx, tangent = rvx * tx + rvy * ty;
    float f = clamp(-tangent / sum, -impulse * .3f, impulse * .3f);
    a.vx -= tx * f * ia;
    a.vy -= ty * f * ia;
    b.vx += tx * f * ib;
    b.vy += ty * f * ib;
    a.angularVelocity -= f * ia / a.radius * .35f;
    b.angularVelocity += f * ib / b.radius * .35f;
    if (-speed > 100) {
      deform(a, -nx, -ny, -speed);
      deform(b, nx, ny, -speed);
    }
  }

  private void deform(Body b, float nx, float ny, float speed) {
    float length = (float) Math.hypot(nx, ny);
    b.normalX = nx / Math.max(.001f, length);
    b.normalY = ny / Math.max(.001f, length);
    b.deformationVelocity =
        Math.min(18, b.deformationVelocity + speed / b.radius * .34f * (comfort ? .2f : 1));
    b.angularVelocity += (b.normalX * b.vy - b.normalY * b.vx) / b.radius * .24f;
  }

  private void spring(Body b, float dt) {
    float softness = clamp(b.softness, 0, 1);
    float stiffness = 420 - 320 * softness, damping = 24 - 12 * softness;
    b.deformationVelocity += (-stiffness * b.deformation - damping * b.deformationVelocity) * dt;
    b.deformation += b.deformationVelocity * dt;
    float max = .18f + .24f * softness;
    if (b.deformation > max) {
      b.deformation = max;
      if (b.deformationVelocity > 0) b.deformationVelocity *= -.18f;
    }
    if (b.deformation < -.12f) {
      b.deformation = -.12f;
      if (b.deformationVelocity < 0) b.deformationVelocity *= -.18f;
    }
    if (Math.abs(b.deformation) < .0005f && Math.abs(b.deformationVelocity) < .005f)
      b.deformation = b.deformationVelocity = 0;
  }

  public static float clamp(float v, float min, float max) {
    return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
  }
}
