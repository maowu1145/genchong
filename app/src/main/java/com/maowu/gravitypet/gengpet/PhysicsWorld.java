package com.maowu.gravitypet.gengpet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared, Android-independent fixed-step physics. Coordinates and velocities are screen px. Adapted
 * from upstream PetView's impulse/friction/spring solver and OverlayPetService's clock. Collision
 * shapes deliberately represent the main body, not thin ears/tails or transparent art.
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
  public boolean comfort;

  public List<Body> bodies() {
    return Collections.unmodifiableList(bodies);
  }

  public boolean add(Body b) {
    if (b == null || bodies.size() >= MAX_BODIES) return false;
    for (Body current : bodies) if (current.id.equals(b.id)) return false;
    bodies.add(b);
    constrain(b);
    wakeAll();
    return true;
  }

  public void remove(Body b) {
    bodies.remove(b);
    wakeAll();
  }

  public void wakeAll() {
    for (Body b : bodies) b.wake();
  }

  public void resetClock() {
    lastNanos = 0;
    accumulator = 0;
  }

  public void setPixelsPerMeter(float value) {
    pixelsPerMeter = Math.max(50, value);
  }

  public void setBounds(float l, float t, float r, float b) {
    left = l;
    top = t;
    right = Math.max(l + 2, r);
    bottom = Math.max(t + 2, b);
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
    float elapsed = clamp((nanos - lastNanos) / 1_000_000_000f, 0, .05f);
    lastNanos = nanos;
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
  }

  public void constrain(Body b) {
    float rx = Math.min(Math.max(b.radius, b.edgeX), (right - left) / 2),
        ry = Math.min(Math.max(b.radius, b.edgeY), (bottom - top) / 2);
    b.x = clamp(b.x, left + rx, right - rx);
    b.y = clamp(b.y, top + ry, bottom - ry);
  }

  public boolean isSettled() {
    for (Body b : bodies)
      if ((!b.sleeping && !b.fixed()) || Math.abs(b.deformation) > .001f) return false;
    return true;
  }

  /** Public for deterministic desktop tests; callers normally use advance(). */
  public void step(float dt) {
    dt = clamp(dt, 0, STEP);
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
    if ((b.y <= top + ry + .5f && gy < 0) || (b.y >= bottom - ry - .5f && gy > 0))
      b.vx = friction(b.vx, gx, gy, dt);
    if ((b.x <= left + rx + .5f && gx < 0) || (b.x >= right - rx - .5f && gx > 0))
      b.vy = friction(b.vy, gy, gx, dt);
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
