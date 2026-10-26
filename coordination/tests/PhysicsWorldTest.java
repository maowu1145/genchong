package com.maowu.gravitypet.gengpet;

/** Standalone Java 17 tests; no Gradle, Android runtime or test library required. */
public final class PhysicsWorldTest {
    private static int assertions;
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static PhysicsWorld world() { PhysicsWorld w = new PhysicsWorld(); w.setBounds(0, 0, 600, 800); return w; }
    private static PhysicsWorld.Body body(String id, float x, float y) { return new PhysicsWorld.Body(id, x, y, 30); }
    private static void run(PhysicsWorld w, int steps) { for (int i = 0; i < steps; i++) w.step(PhysicsWorld.STEP); }
    public static void main(String[] args) {
        PhysicsWorld w = world(); PhysicsWorld.Body a = body("a", 300, 200); w.add(a); run(w, 30);
        check(a.y > 200 && a.vy > 0, "gravity accelerates downward");
        a.y = 769; a.vy = 500; w.step(PhysicsWorld.STEP);
        check(a.vy < 0, "floor collision bounces"); check(a.deformationVelocity > 0, "impact excites spring");
        float maxDeformation = 0; for (int i = 0; i < 60; i++) { w.step(PhysicsWorld.STEP); maxDeformation = Math.max(maxDeformation, a.deformation); }
        check(maxDeformation > .01f && maxDeformation <= .301f, "compression is visible and clamped for medium softness");
        run(w, 1800); check(a.sleeping && a.vx == 0 && a.vy == 0, "single pet eventually sleeps on floor");
        float rested = a.y; run(w, 300); check(a.sleeping && Math.abs(a.y - rested) < .1f, "sleep persists without jitter");
        w.setGravity(7, 4); check(!a.sleeping, "tilt wakes sleeping pet"); run(w, 15); check(a.x > 300, "tilt changes direction");
        a.pinned = true; float px = a.x, py = a.y; w.throwBody(a, 2000, -500); run(w, 100);
        check(a.x == px && a.y == py, "pinned pet cannot be thrown or fall");

        w = world(); a = body("a", 250, 300); PhysicsWorld.Body b = body("b", 309, 300);
        a.gravity = b.gravity = false; a.vx = 350; b.vx = -350; w.add(a); w.add(b); w.step(PhysicsWorld.STEP);
        check(a.vx < 0 && b.vx > 0, "head-on collision exchanges momentum");
        check(a.deformationVelocity > 0 && b.deformationVelocity > 0, "both pets deform on contact");
        check(Math.abs(a.vx + b.vx) < .01f, "equal mass pair conserves linear momentum before shared drag");
        run(w, 2400); check(Math.abs(a.deformation) < .001f, "spring returns to neutral");

        w = world(); a = body("held", 250, 300); b = body("free", 280, 300); a.held = true; a.gravity = b.gravity = false; w.add(a); w.add(b); run(w, 10);
        check(a.x == 250, "held pet remains kinematic"); check(b.x > 300, "held pet pushes free neighbor");
        w.throwBody(a, 1_000_000, -1_000_000); check(Math.hypot(a.vx, a.vy) <= 1650.01, "throw speed is bounded");
        a.toolOpen = true; px = a.x; py = a.y; run(w, 100); check(a.x == px && a.y == py, "tool-open pet remains paused");

        w = world(); for (int i = 0; i < 6; i++) check(w.add(body("pet" + i, 300, 100 + i * 60)), "six supported bodies");
        check(!w.add(body("seventh", 100, 100)), "seventh body rejected");
        check(!w.add(body("pet0", 100, 100)), "duplicate instance ID rejected");
        run(w, 3600);
        for (PhysicsWorld.Body pet : w.bodies()) {
            check(Float.isFinite(pet.x) && Float.isFinite(pet.y) && pet.x >= 30 && pet.y <= 770, "six-body simulation remains bounded");
        }
        check(w.isSettled(), "six-body stack settles");
        for (int i = 0; i < w.bodies().size(); i++) for (int j = i + 1; j < w.bodies().size(); j++) {
            PhysicsWorld.Body p = w.bodies().get(i), q = w.bodies().get(j);
            check(Math.hypot(p.x - q.x, p.y - q.y) >= p.radius + q.radius - 3, "stack bodies do not collapse into each other");
        }
        w.remove(w.bodies().get(5)); check(!w.isSettled(), "removing support wakes stack");
        w.setBounds(0, 0, 250, 200); for (PhysicsWorld.Body pet : w.bodies()) check(pet.x <= 220 && pet.y <= 170, "rotation clamps all positions");

        w = world(); a = body("clock", 300, 100); w.add(a); check(w.advance(1_000_000_000L) == 0, "first frame resets timing");
        check(w.advance(9_000_000_000L) == 4, "long frame is capped to four substeps");
        check(a.y < 105, "resume does not teleport"); w.resetClock(); check(w.advance(10_000_000_000L) == 0, "explicit clock reset excludes paused time");
        PhysicsWorld normal = world(), calm = world(); calm.comfort = true;
        PhysicsWorld.Body n = body("normal", 200, 200), c = body("calm", 200, 200); normal.add(n); calm.add(c); run(normal, 30); run(calm, 30);
        check(c.y < n.y, "comfort mode reduces movement");
        calm.throwBody(c, 2000, 0); check(c.vx <= 413, "comfort mode damps throws");
        w = world(); a = body("edge", 580, 780); a.edgeX = 50; a.edgeY = 60; w.add(a);
        check(a.x <= 550 && a.y <= 740, "art bounds stay within screen independently of body collision radius");
        check(PhysicsWorld.clamp(Float.NaN, 0, 1) == 0, "corrupt input does not propagate NaN");
        System.out.println("PASS: " + assertions + " physics assertions");
    }
}
