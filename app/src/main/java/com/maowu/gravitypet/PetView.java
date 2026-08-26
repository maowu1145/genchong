package com.maowu.gravitypet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.io.File;

public class PetView extends View implements Runnable {
    private static final int SKIN_TEXTURE_SIZE = 300;
    private static final int MAX_CHAT_MESSAGES = 8;
    private static final int COLLAPSED_CHAT_LINES = 2;
    private static final int EXPANDED_CHAT_LINES = 10;
    private static final int EXPANDABLE_TEXT_LENGTH = 36;
    private static final long CHAT_ENTRY_DURATION_MS = 240L;
    public interface PositionListener {
        void onPositionChanged(float centerX, float centerY,
                               float touchDiameter, boolean dragging);
    }
    public interface BubbleRegionListener {
        void onBubbleRegionChanged(float left, float top, float right, float bottom,
                                   boolean visible);
    }

    private static final float BASE_PIXELS_PER_METER = 300f;
    private static final float BOUNCE = 0.55f;
    private static final float AIR_DRAG_PER_SECOND = 0.84f;
    private static final float STATIC_FRICTION = 0.45f;
    private static final float KINETIC_FRICTION = 0.30f;
    private static final float MIN_BOUNCE_SPEED = 70f;
    private static final float MIN_DEFORMATION_IMPACT_SPEED = 100f;
    // A one-dimensional soft-body mode along the latest collision normal.
    private static final float DEFORMATION_STIFFNESS = 260f;
    private static final float DEFORMATION_DAMPING = 18f;
    private static final float IMPULSE_TO_DEFORMATION = 0.34f;
    private static final float MAX_COMPRESSION = 0.38f;
    private static final float MAX_THROW_SPEED = 6000f;
    private static final float ROTATION_DAMPING = 5.5f;
    private static final float COLLISION_TORQUE = 0.24f;
    private static final float KEYBOARD_FLOOR_RESPONSE = 18f;
    private static final float MAX_KEYBOARD_FLOOR_SPEED = 4200f;
    private static final float RESTING_CONTACT_MARGIN = 3.0f;

    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint normalizationPaint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint chatBubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chatShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chatFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dismissZonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chatTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private String conversationStatus = "";
    private Bitmap skinBitmap;
    private float[] silhouetteX;
    private float[] silhouetteY;
    private final float[] collisionExtents = new float[4];
    private int currentSkinId;
    private float radius;
    private float pixelsPerMeter = BASE_PIXELS_PER_METER;

    private float x;
    private float y;
    private float velocityX;
    private float velocityY;
    private float gravityX;
    private float gravityY;
    private float deformation;
    private float deformationVelocity;
    private float impactNormalX = 0f;
    private float impactNormalY = 1f;
    private float rotationAngle;
    private float angularVelocity;
    private float uprightTargetAngle;
    private float centerOfMassAnchorX = 0.5f;
    private float centerOfMassAnchorY = 1f;
    private float centerOfMassOffsetRatio = 1f;
    private float keyboardInset;
    private float keyboardInsetTarget;
    private float keyboardFloorVelocity;
    private long lastFrameNanos;
    private boolean managedByWorld;
    private boolean positioned;
    private float spawnFractionX = 0.5f;
    private float spawnFractionY = 0.35f;
    private boolean dragging;
    private boolean dismissZoneVisible;
    private float dismissZoneAlpha;
    private int crowdSettleFrames;
    private boolean crowdSleeping;
    private boolean crowdStabilizing;
    private float sleepGravityX;
    private float sleepGravityY;
    private float dragOffsetX;
    private float dragOffsetY;
    private PositionListener positionListener;
    private BubbleRegionListener bubbleRegionListener;
    private boolean bubbleBoundsChanged;
    private final Runnable bubbleRegionNotification = this::notifyBubbleRegion;

    public PetView(Context context) {
        super(context);
        setWillNotDraw(false);
        radius = 26f * getResources().getDisplayMetrics().density;
        ballPaint.setColor(Color.WHITE);
        // Visible skin pixels are normalized to alpha 255, so SRC_OVER keeps
        // their exact color while transparent padding leaves other pets intact.
        skinPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        ballPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        chatTextPaint.setTextSize(14f * getResources().getDisplayMetrics().scaledDensity);
    }

    public void addChatMessage(String text, boolean fromUser) {
        if (text == null) return;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        if (trimmed.length() > 260) trimmed = trimmed.substring(0, 260) + "…";
        chatMessages.add(new ChatMessage(trimmed, fromUser, SystemClock.uptimeMillis()));
        while (chatMessages.size() > MAX_CHAT_MESSAGES) chatMessages.remove(0);
        bubbleBoundsChanged = true;
        requestLayout();
        invalidate();
    }

    public void setConversationStatus(String text) {
        conversationStatus = text == null ? "" : text.trim();
        if (conversationStatus.length() > 80) {
            conversationStatus = conversationStatus.substring(0, 80) + "…";
        }
        invalidate();
    }

    public void clearConversation() {
        conversationStatus = "";
        chatMessages.clear();
        bubbleBoundsChanged = true;
        removeCallbacks(bubbleRegionNotification);
        post(bubbleRegionNotification);
        requestLayout();
        invalidate();
    }

    public void setBubbleRegionListener(BubbleRegionListener listener) {
        bubbleRegionListener = listener;
    }

    public boolean toggleExpandableBubbleAt(float localX, float localY) {
        for (int index = chatMessages.size() - 1; index >= 0; index--) {
            ChatMessage message = chatMessages.get(index);
            if (!message.expandable
                    || !message.hitBounds.contains(localX, localY)) continue;
            message.expanded = !message.expanded;
            requestLayout();
            invalidate();
            return true;
        }
        return false;
    }

    private void notifyBubbleRegion() {
        BubbleRegionListener listener = bubbleRegionListener;
        if (listener == null) return;
        RectF union = new RectF();
        boolean found = false;
        for (ChatMessage message : chatMessages) {
            if (!message.expandable || message.hitBounds.isEmpty()) continue;
            if (!found) {
                union.set(message.hitBounds);
                found = true;
            } else {
                union.union(message.hitBounds);
            }
        }
        listener.onBubbleRegionChanged(
                union.left, union.top, union.right, union.bottom, found);
    }

    public void setGravity(float x, float y, float z) {
        if (crowdSleeping
                && Math.hypot(x - sleepGravityX, y - sleepGravityY) > 0.45f) {
            wakeFromCrowdSleep();
        }
        gravityX = x;
        gravityY = y;
        if (Math.hypot(x, y) > 0.8f) {
            float gravityAngle = (float) Math.atan2(-x, y);
            float anchorAngle = (float) Math.atan2(
                    centerOfMassAnchorX - 0.5f,
                    centerOfMassAnchorY - 0.5f);
            uprightTargetAngle = normalizeAngle(gravityAngle - anchorAngle);
        }
    }

    public void setBallColor(int color) {
        ballPaint.setColor(color);
        invalidate();
    }

    public void setOpacityPercent(int percent) {
        // Pet opacity controls were removed from the UI. Keep both rendering
        // paths explicitly opaque so a legacy saved preference cannot leak in.
        ballPaint.setAlpha(255);
        skinPaint.setAlpha(255);
        invalidate();
    }

    public void setKeyboardInsetBottom(int insetPixels) {
        keyboardInsetTarget = clamp(insetPixels, 0f, Math.max(0f, getHeight()));
    }

    public void setBallDiameterDp(int diameterDp) {
        radius = diameterDp * getResources().getDisplayMetrics().density * 0.5f;
        if (positioned) {
            clampCenterToVisibleBounds(getEffectiveFloorY());
        }
        invalidate();
    }

    public void setGravityStrengthPercent(int percent) {
        pixelsPerMeter = BASE_PIXELS_PER_METER * percent / 100f;
    }

    public void setSkin(int skinId) {
        if (skinId == currentSkinId && (skinId == 0 || skinBitmap != null)) return;
        currentSkinId = skinId;
        if (skinBitmap != null) {
            skinBitmap.recycle();
            skinBitmap = null;
        }
        silhouetteX = null;
        silhouetteY = null;
        centerOfMassAnchorX = 0.5f;
        centerOfMassAnchorY = 1f;
        centerOfMassOffsetRatio = 1f;
        int resourceId = PetMakerActivity.builtInSkinResource(skinId);
        Bitmap source = null;
        File editedSkin = PetMakerActivity.skinFile(getContext(), skinId);
        if (editedSkin != null && editedSkin.isFile()) {
            source = BitmapFactory.decodeFile(editedSkin.getAbsolutePath());
        }
        if (editedSkin != null && editedSkin.isFile()) {
            centerOfMassAnchorX = PetPreferences.getAnchorX(
                    getContext(), skinId, 0.5f);
            centerOfMassAnchorY = PetPreferences.getAnchorY(
                    getContext(), skinId,
                    skinId == PetMakerActivity.CUSTOM_SKIN_ID ? 0.82f : 1f);
            float anchorDx = centerOfMassAnchorX - 0.5f;
            float anchorDy = centerOfMassAnchorY - 0.5f;
            centerOfMassOffsetRatio = clamp(
                    (float) Math.hypot(anchorDx, anchorDy) * 2f, 0.08f, 1.4f);
        }
        if (source == null && resourceId != 0) {
            source = BitmapFactory.decodeResource(getResources(), resourceId);
        }
        if (source != null) {
            skinBitmap = normalizeSkinTexture(source);
            buildPixelSilhouette(skinBitmap);
            if (source != null && source != skinBitmap) source.recycle();
        }
        invalidate();
    }

    public void reloadSkin() {
        int skinId = currentSkinId;
        currentSkinId = -1;
        setSkin(skinId);
    }

    private Bitmap normalizeSkinTexture(Bitmap source) {
        if (source == null) return null;
        Bitmap normalized = Bitmap.createBitmap(
                SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE, Bitmap.Config.ARGB_8888);
        Canvas textureCanvas = new Canvas(normalized);
        float scale = Math.min(
                (float) SKIN_TEXTURE_SIZE / source.getWidth(),
                (float) SKIN_TEXTURE_SIZE / source.getHeight());
        float width = source.getWidth() * scale;
        float height = source.getHeight() * scale;
        float left = (SKIN_TEXTURE_SIZE - width) * 0.5f;
        // Keep transparent padding above the character, not underneath it, so
        // the visible feet/body touch the same floor as the circular collider.
        float top = SKIN_TEXTURE_SIZE - height;
        RectF destination = new RectF(left, top, left + width, top + height);
        textureCanvas.drawBitmap(source, null, destination, normalizationPaint);
        // Normalize the visible silhouette to opaque pixels. The opacity slider is
        // then the single source of truth, and 100% is genuinely fully opaque.
        int[] pixels = new int[SKIN_TEXTURE_SIZE * SKIN_TEXTURE_SIZE];
        normalized.getPixels(pixels, 0, SKIN_TEXTURE_SIZE,
                0, 0, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = (pixels[i] >>> 24) & 0xff;
            pixels[i] = alpha < 64 ? 0 : (pixels[i] | 0xff000000);
        }
        normalized.setPixels(pixels, 0, SKIN_TEXTURE_SIZE,
                0, 0, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE);
        return normalized;
    }

    private void buildPixelSilhouette(Bitmap bitmap) {
        if (bitmap == null) return;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int boundaryCount = 0;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                if (((pixels[index] >>> 24) & 0xff) == 0) continue;
                if (column == 0 || row == 0 || column == width - 1 || row == height - 1
                        || ((pixels[index - 1] >>> 24) & 0xff) == 0
                        || ((pixels[index + 1] >>> 24) & 0xff) == 0
                        || ((pixels[index - width] >>> 24) & 0xff) == 0
                        || ((pixels[index + width] >>> 24) & 0xff) == 0) {
                    boundaryCount++;
                }
            }
        }
        if (boundaryCount == 0) return;
        silhouetteX = new float[boundaryCount * 4];
        silhouetteY = new float[boundaryCount * 4];
        int output = 0;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                if (((pixels[index] >>> 24) & 0xff) == 0) continue;
                if (column == 0 || row == 0 || column == width - 1 || row == height - 1
                        || ((pixels[index - 1] >>> 24) & 0xff) == 0
                        || ((pixels[index + 1] >>> 24) & 0xff) == 0
                        || ((pixels[index - width] >>> 24) & 0xff) == 0
                        || ((pixels[index + width] >>> 24) & 0xff) == 0) {
                    float left = ((float) column / width) * 2f - 1f;
                    float right = ((float) (column + 1) / width) * 2f - 1f;
                    float top = ((float) row / height) * 2f - 1f;
                    float bottom = ((float) (row + 1) / height) * 2f - 1f;
                    silhouetteX[output] = left;
                    silhouetteY[output++] = top;
                    silhouetteX[output] = right;
                    silhouetteY[output++] = top;
                    silhouetteX[output] = right;
                    silhouetteY[output++] = bottom;
                    silhouetteX[output] = left;
                    silhouetteY[output++] = bottom;
                }
            }
        }
    }

    public void setPositionListener(PositionListener listener) {
        positionListener = listener;
    }

    /** Lets the overlay service advance every pet on one shared physics clock. */
    public void setManagedByWorld(boolean managed) {
        managedByWorld = managed;
        lastFrameNanos = 0L;
        if (managed) removeCallbacks(this);
    }

    public void setCrowdStabilizing(boolean stabilizing) {
        crowdStabilizing = stabilizing;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setDismissZoneVisible(boolean visible) {
        dismissZoneVisible = visible;
        invalidate();
    }

    public void setSpawnFraction(float fractionX, float fractionY) {
        if (positioned) return;
        spawnFractionX = clamp(fractionX, 0.08f, 0.92f);
        spawnFractionY = clamp(fractionY, 0.10f, 0.75f);
    }

    /** Resolves one physical contact using each skin's current pixel silhouette. */
    public boolean resolveCollisionWith(PetView other) {
        if (other == null || other == this || !positioned || !other.positioned) return false;
        float dx = other.x - x;
        float dy = other.y - y;
        float broadRadius = (radius + other.radius) * 1.45f;
        float distanceSquared = dx * dx + dy * dy;
        if (distanceSquared > broadRadius * broadRadius) return false;

        float distance = (float) Math.sqrt(Math.max(distanceSquared, 0.0001f));
        float normalX = distanceSquared < 0.0001f ? 1f : dx / distance;
        float normalY = distanceSquared < 0.0001f ? 0f : dy / distance;
        float separation = supportRadiusAlong(normalX, normalY)
                + other.supportRadiusAlong(-normalX, -normalY);
        float penetration = separation - distance;
        if (penetration <= 0f) {
            // Positional correction intentionally leaves a tiny gap/slop. Keep
            // that pair classified as resting contact so crowd sleep does not
            // alternate off and on every other frame.
            return penetration >= -RESTING_CONTACT_MARGIN;
        }

        float previewRelativeX = other.velocityX - velocityX;
        float previewRelativeY = other.velocityY - velocityY;
        float previewClosingSpeed = -(previewRelativeX * normalX
                + previewRelativeY * normalY);
        // Ordinary resting overlap is resolved by the shared solver and must not
        // wake a sleeping pile every frame. Only a real impact or a held pet does.
        boolean entrySettling = crowdStabilizing || other.crowdStabilizing;
        if (dragging || other.dragging
                || (!entrySettling && previewClosingSpeed > 35f)) {
            wakeFromCrowdSleep();
            other.wakeFromCrowdSleep();
        }

        float correctionDirectionAX = -normalX;
        float correctionDirectionAY = -normalY;
        float correctionDirectionBX = normalX;
        float correctionDirectionBY = normalY;
        float inverseMassA = dragging || crowdSleeping || isBlockedInDirection(
                correctionDirectionAX, correctionDirectionAY)
                ? 0f : 1f / Math.max(1f, radius * radius);
        float inverseMassB = other.dragging || other.crowdSleeping
                || other.isBlockedInDirection(
                correctionDirectionBX, correctionDirectionBY) ? 0f
                : 1f / Math.max(1f, other.radius * other.radius);
        float inverseMassSum = inverseMassA + inverseMassB;
        if (inverseMassSum <= 0f) return true;

        // Remove overlap first. A held pet behaves as a kinematic body and
        // pushes the free pet without being pulled away from the finger.
        float correction = Math.max(0f, penetration - 0.8f) * 0.62f
                / inverseMassSum;
        x -= normalX * correction * inverseMassA;
        y -= normalY * correction * inverseMassA;
        other.x += normalX * correction * inverseMassB;
        other.y += normalY * correction * inverseMassB;

        float relativeVelocityX = other.velocityX - velocityX;
        float relativeVelocityY = other.velocityY - velocityY;
        float normalSpeed = relativeVelocityX * normalX + relativeVelocityY * normalY;
        if (normalSpeed < 0f) {
            float closingSpeed = -normalSpeed;
            float restitution = !entrySettling && closingSpeed >= MIN_BOUNCE_SPEED
                    ? 0.48f : 0f;
            float impulse = -(1f + restitution) * normalSpeed / inverseMassSum;
            float impulseX = impulse * normalX;
            float impulseY = impulse * normalY;
            velocityX -= impulseX * inverseMassA;
            velocityY -= impulseY * inverseMassA;
            other.velocityX += impulseX * inverseMassB;
            other.velocityY += impulseY * inverseMassB;

            // Tangential impulse supplies surface friction and visible spin.
            float tangentX = -normalY;
            float tangentY = normalX;
            float tangentSpeed = relativeVelocityX * tangentX
                    + relativeVelocityY * tangentY;
            float frictionImpulse = clamp(
                    -tangentSpeed / inverseMassSum,
                    -impulse * KINETIC_FRICTION,
                    impulse * KINETIC_FRICTION);
            velocityX -= tangentX * frictionImpulse * inverseMassA;
            velocityY -= tangentY * frictionImpulse * inverseMassA;
            other.velocityX += tangentX * frictionImpulse * inverseMassB;
            other.velocityY += tangentY * frictionImpulse * inverseMassB;
            angularVelocity -= frictionImpulse * inverseMassA
                    / Math.max(radius, 1f) * 0.35f;
            other.angularVelocity += frictionImpulse * inverseMassB
                    / Math.max(other.radius, 1f) * 0.35f;

            if (closingSpeed > MIN_DEFORMATION_IMPACT_SPEED) {
                applyCollisionImpulse(-normalX * closingSpeed,
                        -normalY * closingSpeed, closingSpeed);
                other.applyCollisionImpulse(normalX * closingSpeed,
                        normalY * closingSpeed, closingSpeed);
            }
        }
        clampCenterToVisibleBounds(getEffectiveFloorY());
        other.clampCenterToVisibleBounds(other.getEffectiveFloorY());
        return true;
    }

    public void updateCrowdContact(boolean touchingAnotherPet) {
        if (dragging) {
            wakeFromCrowdSleep();
            return;
        }
        if (!touchingAnotherPet) {
            crowdSettleFrames = 0;
            crowdSleeping = false;
            return;
        }
        // A resting stack receives roughly one gravity increment before the
        // contact impulse cancels it. Dampen that tiny solver residue first;
        // otherwise it stays above the old sleep threshold forever.
        float contactDamping = crowdStabilizing ? 0.70f : 0.92f;
        velocityX *= contactDamping;
        velocityY *= contactDamping;
        angularVelocity *= crowdStabilizing ? 0.38f : 0.72f;
        deformationVelocity *= crowdStabilizing ? 0.48f : 0.76f;
        float linearSpeed = (float) Math.hypot(velocityX, velocityY);
        float resetSpeed = crowdStabilizing ? 260f : 110f;
        float resetAngularSpeed = crowdStabilizing ? 3.2f : 2.0f;
        if (linearSpeed > resetSpeed || Math.abs(angularVelocity) > resetAngularSpeed) {
            crowdSettleFrames = 0;
            crowdSleeping = false;
            return;
        }
        crowdSettleFrames++;
        velocityX *= crowdStabilizing ? 0.62f : 0.82f;
        velocityY *= crowdStabilizing ? 0.62f : 0.82f;
        angularVelocity *= crowdStabilizing ? 0.42f : 0.66f;
        deformationVelocity *= crowdStabilizing ? 0.50f : 0.68f;
        int settleFrames = crowdStabilizing ? 6 : 18;
        float sleepSpeed = crowdStabilizing ? 90f : 38f;
        float sleepAngularSpeed = crowdStabilizing ? 0.8f : 0.45f;
        if (crowdSettleFrames >= settleFrames
                && linearSpeed < sleepSpeed
                && Math.abs(angularVelocity) < sleepAngularSpeed) {
            crowdSleeping = true;
            sleepGravityX = gravityX;
            sleepGravityY = gravityY;
            velocityX = 0f;
            velocityY = 0f;
            angularVelocity = 0f;
            deformationVelocity = 0f;
        }
    }

    private void wakeFromCrowdSleep() {
        crowdSleeping = false;
        crowdSettleFrames = 0;
    }

    private boolean isBlockedInDirection(float directionX, float directionY) {
        computeCollisionExtents();
        float minX = -collisionExtents[0];
        float maxX = Math.max(minX, getWidth() - collisionExtents[1]);
        float minY = -collisionExtents[2];
        float maxY = Math.max(minY, getEffectiveFloorY() - collisionExtents[3]);
        float margin = 1.5f;
        return (directionX < -0.08f && x <= minX + margin)
                || (directionX > 0.08f && x >= maxX - margin)
                || (directionY < -0.08f && y <= minY + margin)
                || (directionY > 0.08f && y >= maxY - margin);
    }

    private float supportRadiusAlong(float directionX, float directionY) {
        if (skinBitmap == null || silhouetteX == null || silhouetteX.length == 0) {
            return radius;
        }
        float normalScale = Math.max(0.58f, 1f - deformation);
        float tangentScale = 1f / normalScale;
        float impactAngle = (float) Math.atan2(impactNormalY, impactNormalX);
        float cosImpact = (float) Math.cos(impactAngle);
        float sinImpact = (float) Math.sin(impactAngle);
        float cosRotation = (float) Math.cos(rotationAngle);
        float sinRotation = (float) Math.sin(rotationAngle);
        float maximum = 0f;
        // Boundary arrays can contain thousands of points. A uniform sample is
        // sufficient for a support mapping while keeping multi-pet collisions fast.
        int step = Math.max(1, silhouetteX.length / 192);
        for (int index = 0; index < silhouetteX.length; index += step) {
            float localX = silhouetteX[index] * radius;
            float localY = silhouetteY[index] * radius;
            float rotatedX = cosRotation * localX - sinRotation * localY;
            float rotatedY = sinRotation * localX + cosRotation * localY;
            float impactX = cosImpact * rotatedX + sinImpact * rotatedY;
            float impactY = -sinImpact * rotatedX + cosImpact * rotatedY;
            impactX *= normalScale;
            impactY *= tangentScale;
            float worldX = cosImpact * impactX - sinImpact * impactY;
            float worldY = sinImpact * impactX + cosImpact * impactY;
            maximum = Math.max(maximum, worldX * directionX + worldY * directionY);
        }
        return Math.max(radius * 0.12f, maximum);
    }

    /** Applies a real impulse opposite gravity so music beats become jumps. */
    public void applyMusicBeat(float strength, float swayDirection, float amplitudeScale) {
        if (!positioned || dragging || amplitudeScale <= 0f) return;
        wakeFromCrowdSleep();
        float magnitude = (float) Math.hypot(gravityX, gravityY);
        float directionX = magnitude > 0.35f ? -gravityX / magnitude : 0f;
        float directionY = magnitude > 0.35f ? -gravityY / magnitude : -1f;
        float impulse = (250f + clamp(strength, 0f, 1f) * 420f) * amplitudeScale;
        velocityX += directionX * impulse + swayDirection * (45f + strength * 55f);
        velocityY += directionY * impulse;
        float speed = (float) Math.hypot(velocityX, velocityY);
        if (speed > 1500f) {
            velocityX *= 1500f / speed;
            velocityY *= 1500f / speed;
        }
        angularVelocity += swayDirection * (0.7f + strength * 1.1f) * amplitudeScale;
        deformationVelocity += (0.35f + strength * 0.8f) * amplitudeScale;
        invalidate();
    }

    public void beginDrag(float screenX, float screenY) {
        wakeFromCrowdSleep();
        dragging = true;
        dragOffsetX = x - screenX;
        dragOffsetY = y - screenY;
        velocityX = 0f;
        velocityY = 0f;
    }

    public void dragTo(float screenX, float screenY) {
        if (!dragging) return;
        computeCollisionExtents();
        float minCenterX = -collisionExtents[0];
        float maxCenterX = Math.max(minCenterX, getWidth() - collisionExtents[1]);
        float minCenterY = -collisionExtents[2];
        float maxCenterY = Math.max(
                minCenterY, getEffectiveFloorY() - collisionExtents[3]);
        x = clamp(screenX + dragOffsetX, minCenterX, maxCenterX);
        y = clamp(screenY + dragOffsetY, minCenterY, maxCenterY);
        invalidate();
    }

    public void endDrag(float releaseVelocityX, float releaseVelocityY) {
        if (!dragging) return;
        dragging = false;
        velocityX = clamp(releaseVelocityX, -MAX_THROW_SPEED, MAX_THROW_SPEED);
        velocityY = clamp(releaseVelocityY, -MAX_THROW_SPEED, MAX_THROW_SPEED);
        angularVelocity += velocityX / Math.max(radius, 1f) * 0.08f;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (!positioned && width > 0 && height > 0) {
            x = width * spawnFractionX;
            y = height * spawnFractionY;
            positioned = true;
        } else {
            clampCenterToVisibleBounds(Math.max(0f, height - keyboardInset));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameNanos = 0L;
        if (!managedByWorld) postOnAnimation(this);
    }

    @Override
    public void run() {
        long frameTimeNanos = System.nanoTime();
        if (lastFrameNanos != 0L && positioned) {
            float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            // Limit a long frame so returning from sleep cannot teleport the pet.
            dt = Math.min(dt, 0.033f);
            stepKeyboardFloor(dt);
            if (dragging) {
                stepDeformation(dt);
                stepRotation(dt);
            } else {
                stepPhysics(dt);
            }
            float dismissTarget = dismissZoneVisible ? 1f : 0f;
            float dismissResponse = 1f - (float) Math.exp(-dt * 11f);
            dismissZoneAlpha += (dismissTarget - dismissZoneAlpha) * dismissResponse;
            if (Math.abs(dismissTarget - dismissZoneAlpha) < 0.004f) {
                dismissZoneAlpha = dismissTarget;
            }
            invalidate();
        }
        lastFrameNanos = frameTimeNanos;
        if (positioned && positionListener != null) {
            // The hit target grows with the physical radius and the maximum soft-body bulge.
            positionListener.onPositionChanged(x, y, radius * 3.5f, dragging);
        }
        if (isAttachedToWindow() && !managedByWorld) {
            postOnAnimation(this);
        }
    }

    /** Advances one fixed world substep without drawing an intermediate state. */
    public void stepWorld(float dt) {
        if (!positioned) return;
        stepKeyboardFloor(dt);
        if (dragging) {
            stepDeformation(dt);
            stepRotation(dt);
        } else {
            stepPhysics(dt);
        }
        float dismissTarget = dismissZoneVisible ? 1f : 0f;
        float dismissResponse = 1f - (float) Math.exp(-dt * 11f);
        dismissZoneAlpha += (dismissTarget - dismissZoneAlpha) * dismissResponse;
        if (Math.abs(dismissTarget - dismissZoneAlpha) < 0.004f) {
            dismissZoneAlpha = dismissTarget;
        }
    }

    /** Publishes only the fully solved state, once per display frame. */
    public void finishWorldFrame() {
        if (!positioned) return;
        invalidate();
        if (positionListener != null) {
            positionListener.onPositionChanged(x, y, radius * 3.5f, dragging);
        }
    }

    private void stepPhysics(float dt) {
        if (crowdSleeping) {
            velocityX = 0f;
            velocityY = 0f;
            angularVelocity = 0f;
            deformationVelocity = 0f;
            return;
        }
        velocityX += gravityX * pixelsPerMeter * dt;
        velocityY += gravityY * pixelsPerMeter * dt;

        float drag = (float) Math.pow(AIR_DRAG_PER_SECOND, dt);
        velocityX *= drag;
        velocityY *= drag;

        x += velocityX * dt;
        y += velocityY * dt;

        computeCollisionExtents();
        float minX = -collisionExtents[0];
        float maxX = Math.max(minX, getWidth() - collisionExtents[1]);
        float minY = -collisionExtents[2];
        float maxY = Math.max(minY, getEffectiveFloorY() - collisionExtents[3]);

        float collisionX = 0f;
        float collisionY = 0f;

        if (x < minX) {
            x = minX;
            if (velocityX < 0) {
                collisionX = -velocityX;
                velocityX = collisionX >= MIN_BOUNCE_SPEED
                        ? -velocityX * BOUNCE : 0f;
            }
        } else if (x > maxX) {
            x = maxX;
            if (velocityX > 0) {
                collisionX = -velocityX;
                velocityX = -collisionX >= MIN_BOUNCE_SPEED
                        ? -velocityX * BOUNCE : 0f;
            }
        }

        if (y < minY) {
            y = minY;
            if (velocityY < 0) {
                collisionY = -velocityY;
                velocityY = collisionY >= MIN_BOUNCE_SPEED
                        ? -velocityY * BOUNCE : 0f;
            }
        } else if (y > maxY) {
            y = maxY;
            float relativeVelocity = velocityY - keyboardFloorVelocity;
            if (relativeVelocity > 0) {
                collisionY = -relativeVelocity;
                velocityY = relativeVelocity >= MIN_BOUNCE_SPEED
                        ? keyboardFloorVelocity - relativeVelocity * BOUNCE
                        : keyboardFloorVelocity;
            }
        }

        float collisionSpeed = (float) Math.hypot(collisionX, collisionY);
        if (collisionSpeed > MIN_DEFORMATION_IMPACT_SPEED) {
            applyCollisionImpulse(collisionX, collisionY, collisionSpeed);
        }

        applyContactFriction(dt, minX, maxX, minY, maxY);
        stepDeformation(dt);
        stepRotation(dt);

        if (Math.abs(velocityX) < 1f && Math.abs(gravityX) < 0.04f) velocityX = 0f;
        if (Math.abs(velocityY) < 1f && Math.abs(gravityY) < 0.04f) velocityY = 0f;
    }

    private void stepKeyboardFloor(float dt) {
        float difference = keyboardInsetTarget - keyboardInset;
        if (Math.abs(difference) < 0.5f) {
            keyboardInset = keyboardInsetTarget;
            keyboardFloorVelocity = 0f;
            return;
        }

        float insetVelocity = clamp(
                difference * KEYBOARD_FLOOR_RESPONSE,
                -MAX_KEYBOARD_FLOOR_SPEED,
                MAX_KEYBOARD_FLOOR_SPEED);
        float delta = insetVelocity * dt;
        if (Math.abs(delta) > Math.abs(difference)) delta = difference;
        keyboardInset += delta;
        // Inset grows upward while screen Y grows downward, hence the minus sign.
        keyboardFloorVelocity = -delta / Math.max(dt, 0.001f);
    }

    private void clampCenterToVisibleBounds(float floorY) {
        computeCollisionExtents();
        float minCenterX = -collisionExtents[0];
        float maxCenterX = Math.max(minCenterX, getWidth() - collisionExtents[1]);
        float minCenterY = -collisionExtents[2];
        float maxCenterY = Math.max(minCenterY, floorY - collisionExtents[3]);
        x = clamp(x, minCenterX, maxCenterX);
        y = clamp(y, minCenterY, maxCenterY);
    }

    private void computeCollisionExtents() {
        if (skinBitmap == null || silhouetteX == null || silhouetteX.length == 0) {
            collisionExtents[0] = -radius;
            collisionExtents[1] = radius;
            collisionExtents[2] = -radius;
            collisionExtents[3] = radius;
            return;
        }

        float normalScale = Math.max(0.58f, 1f - deformation);
        float tangentScale = 1f / normalScale;
        float impactAngle = (float) Math.atan2(impactNormalY, impactNormalX);
        float cosImpact = (float) Math.cos(impactAngle);
        float sinImpact = (float) Math.sin(impactAngle);
        float cosRotation = (float) Math.cos(rotationAngle);
        float sinRotation = (float) Math.sin(rotationAngle);
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        for (int index = 0; index < silhouetteX.length; index++) {
            float localX = silhouetteX[index] * radius;
            float localY = silhouetteY[index] * radius;
            float rotatedX = cosRotation * localX - sinRotation * localY;
            float rotatedY = sinRotation * localX + cosRotation * localY;
            float impactLocalX = cosImpact * rotatedX + sinImpact * rotatedY;
            float impactLocalY = -sinImpact * rotatedX + cosImpact * rotatedY;
            impactLocalX *= normalScale;
            impactLocalY *= tangentScale;
            float worldX = cosImpact * impactLocalX - sinImpact * impactLocalY;
            float worldY = sinImpact * impactLocalX + cosImpact * impactLocalY;
            minX = Math.min(minX, worldX);
            maxX = Math.max(maxX, worldX);
            minY = Math.min(minY, worldY);
            maxY = Math.max(maxY, worldY);
        }
        collisionExtents[0] = minX;
        collisionExtents[1] = maxX;
        collisionExtents[2] = minY;
        collisionExtents[3] = maxY;
    }

    private float getEffectiveFloorY() {
        return Math.max(0f, getHeight() - keyboardInset);
    }

    private void applyCollisionImpulse(float normalImpulseX, float normalImpulseY,
                                       float collisionSpeed) {
        impactNormalX = normalImpulseX / collisionSpeed;
        impactNormalY = normalImpulseY / collisionSpeed;

        // Collision speed divided by radius is a scale-independent strain rate.
        float strainRateImpulse = collisionSpeed / radius * IMPULSE_TO_DEFORMATION;
        deformationVelocity += strainRateImpulse;

        float tangentSpeed = impactNormalX * velocityY - impactNormalY * velocityX;
        angularVelocity += tangentSpeed / Math.max(radius, 1f) * COLLISION_TORQUE;
    }

    private void applyContactFriction(float dt, float minX, float maxX,
                                      float minY, float maxY) {
        boolean horizontalContact = (y <= minY + 0.5f && gravityY < 0f)
                || (y >= maxY - 0.5f && gravityY > 0f);
        if (horizontalContact) {
            float normalAcceleration = Math.abs(gravityY * pixelsPerMeter);
            float tangentAcceleration = gravityX * pixelsPerMeter;
            velocityX = applyCoulombFriction(
                    velocityX, tangentAcceleration, normalAcceleration, dt);
        }

        boolean verticalContact = (x <= minX + 0.5f && gravityX < 0f)
                || (x >= maxX - 0.5f && gravityX > 0f);
        if (verticalContact) {
            float normalAcceleration = Math.abs(gravityX * pixelsPerMeter);
            float tangentAcceleration = gravityY * pixelsPerMeter;
            velocityY = applyCoulombFriction(
                    velocityY, tangentAcceleration, normalAcceleration, dt);
        }
    }

    private float applyCoulombFriction(float speed, float tangentAcceleration,
                                       float normalAcceleration, float dt) {
        if (Math.abs(speed) < 18f
                && Math.abs(tangentAcceleration) <= STATIC_FRICTION * normalAcceleration) {
            return 0f;
        }
        float reduction = KINETIC_FRICTION * normalAcceleration * dt;
        if (Math.abs(speed) <= reduction) return 0f;
        return speed - Math.signum(speed) * reduction;
    }

    private void stepRotation(float dt) {
        float error = normalizeAngle(uprightTargetAngle - rotationAngle);
        float planarAcceleration = (float) Math.hypot(gravityX, gravityY)
                * pixelsPerMeter;
        // Torque from a center of mass at the bottom edge, one full radius below center.
        // A solid disk's moment of inertia per unit mass is approximately 0.5*r^2.
        float restoringAcceleration = 2f * planarAcceleration
                * centerOfMassOffsetRatio / Math.max(radius, 1f)
                * (float) Math.sin(error);
        float angularAcceleration = restoringAcceleration
                - ROTATION_DAMPING * angularVelocity;
        angularVelocity += angularAcceleration * dt;
        rotationAngle = normalizeAngle(rotationAngle + angularVelocity * dt);
    }

    private void stepDeformation(float dt) {
        // m*x'' + c*x' + k*x = 0, with unit mass. Collision impulses modify x'.
        float acceleration = -DEFORMATION_STIFFNESS * deformation
                - DEFORMATION_DAMPING * deformationVelocity;
        deformationVelocity += acceleration * dt;
        deformation += deformationVelocity * dt;

        if (deformation > MAX_COMPRESSION) {
            deformation = MAX_COMPRESSION;
            if (deformationVelocity > 0f) deformationVelocity *= -0.18f;
        } else if (deformation < -0.12f) {
            deformation = -0.12f;
            if (deformationVelocity < 0f) deformationVelocity *= -0.18f;
        }

        if (Math.abs(deformation) < 0.0005f
                && Math.abs(deformationVelocity) < 0.005f) {
            deformation = 0f;
            deformationVelocity = 0f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (dismissZoneAlpha > 0.002f) {
            float startX = getWidth() * 0.68f;
            dismissZonePaint.setShader(new LinearGradient(
                    startX, 0f, getWidth(), 0f,
                    new int[]{Color.TRANSPARENT, 0x44FF2929, 0xB8D90000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            dismissZonePaint.setAlpha(Math.round(255f * dismissZoneAlpha));
            canvas.drawRect(startX, 0f, getWidth(), getHeight(), dismissZonePaint);
            dismissZonePaint.setShader(null);
        }
        float normalScale = Math.max(0.58f, 1f - deformation);
        // Preserve the circle's area as an approximation of an incompressible body.
        float tangentScale = 1f / normalScale;
        float angleDegrees = (float) Math.toDegrees(
                Math.atan2(impactNormalY, impactNormalX));

        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angleDegrees);
        canvas.scale(normalScale, tangentScale);
        canvas.rotate(-angleDegrees);
        canvas.rotate((float) Math.toDegrees(rotationAngle));

        if (skinBitmap != null) {
            RectF body = new RectF(-radius, -radius, radius, radius);
            skinPaint.setAlpha(255);
            canvas.drawBitmap(skinBitmap, null, body, skinPaint);
        } else {
            canvas.drawCircle(0f, 0f, radius, ballPaint);
        }
        canvas.restore();

        drawConversation(canvas);
    }

    private void drawConversation(Canvas canvas) {
        if (chatMessages.isEmpty() && conversationStatus.isEmpty()) {
            if (bubbleBoundsChanged) {
                bubbleBoundsChanged = false;
                removeCallbacks(bubbleRegionNotification);
                post(bubbleRegionNotification);
            }
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float sideMargin = 18f * density;
        float gap = 7f * density;
        float paddingX = 12f * density;
        float paddingY = 8f * density;
        float bubbleRadius = 15f * density;
        int contentMaxWidth = Math.max(1, Math.round(
                Math.min(getWidth() * 0.68f, 280f * density) - paddingX * 2f));
        // Anchor the listening/thinking pill to the pet's visible top instead of
        // the centre of the screen. Pixel-shaped skins do not necessarily fill
        // their square texture, so radius alone can make the pill look detached.
        computeCollisionExtents();
        float visibleTop = y + collisionExtents[2];
        float bottom = clamp(visibleTop - 12f * density,
                getHeight() * 0.62f, getHeight() - 40f * density);
        float cursor = bottom;

        if (!conversationStatus.isEmpty()) {
            int statusContentWidth = Math.max(1, Math.min(contentMaxWidth,
                    (int) Math.ceil(Layout.getDesiredWidth(
                            conversationStatus, chatTextPaint))));
            StaticLayout statusLayout = makeChatLayout(
                    conversationStatus, statusContentWidth, 1, true);
            float width = statusContentWidth + paddingX * 2f;
            float height = statusLayout.getHeight() + paddingY * 1.5f;
            float left = clamp(x - width * 0.5f,
                    sideMargin, getWidth() - sideMargin - width);
            float top = cursor - height;
            drawStatusPill(canvas, statusLayout, left, top, width, height,
                    paddingX, paddingY, bubbleRadius);
            cursor = top - gap;
        }

        for (int index = chatMessages.size() - 1; index >= 0; index--) {
            ChatMessage message = chatMessages.get(index);
            int maxLines = message.expandable
                    ? (message.expanded ? EXPANDED_CHAT_LINES : COLLAPSED_CHAT_LINES)
                    : 3;
            StaticLayout layout = makeChatLayout(
                    message.text, contentMaxWidth, maxLines, false);
            float lineWidth = getLayoutContentWidth(layout);
            float width = Math.min(contentMaxWidth, Math.max(30f * density, lineWidth))
                    + paddingX * 2f;
            float toggleHeight = message.expandable ? 17f * density : 0f;
            float height = layout.getHeight() + paddingY * 2f + toggleHeight;
            float left = message.fromUser
                    ? getWidth() - sideMargin - width
                    : sideMargin;
            float top = cursor - height;
            boolean fadeTopHalf = chatMessages.size() == MAX_CHAT_MESSAGES && index == 0;
            drawChatBubble(canvas, message, layout, left, top, width, height,
                    paddingX, paddingY, bubbleRadius, fadeTopHalf, toggleHeight);
            cursor = top - gap;
        }
        if (bubbleBoundsChanged) {
            bubbleBoundsChanged = false;
            removeCallbacks(bubbleRegionNotification);
            post(bubbleRegionNotification);
        }
    }

    private StaticLayout makeChatLayout(String text, int width, int maxLines,
                                        boolean centered) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), chatTextPaint, width)
                .setAlignment(centered ? Layout.Alignment.ALIGN_CENTER
                        : Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.08f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    private float getLayoutContentWidth(StaticLayout layout) {
        float width = 0f;
        for (int line = 0; line < layout.getLineCount(); line++) {
            width = Math.max(width, layout.getLineWidth(line));
        }
        return width;
    }

    private void drawStatusPill(Canvas canvas, StaticLayout layout, float left, float top,
                                float width, float height, float paddingX, float paddingY,
                                float radiusPx) {
        chatShadowPaint.setColor(0x26000000);
        canvas.drawRoundRect(left, top + dpFloat(2), left + width,
                top + height + dpFloat(2), radiusPx, radiusPx, chatShadowPaint);
        chatBubblePaint.setColor(0xDB1F2940);
        canvas.drawRoundRect(left, top, left + width, top + height,
                radiusPx, radiusPx, chatBubblePaint);
        chatTextPaint.setColor(Color.WHITE);
        canvas.save();
        canvas.translate(left + paddingX, top + paddingY * 0.75f);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawChatBubble(Canvas canvas, ChatMessage message, StaticLayout layout,
                                float left, float top, float width, float height,
                                float paddingX, float paddingY, float radiusPx,
                                boolean fadeTopHalf, float toggleHeight) {
        float elapsed = SystemClock.uptimeMillis() - message.createdAtMillis;
        float progress = clamp(elapsed / CHAT_ENTRY_DURATION_MS, 0f, 1f);
        float eased = 1f - (1f - progress) * (1f - progress);
        float slide = (1f - eased) * dpFloat(34) * (message.fromUser ? 1f : -1f);

        if (message.expandable) {
            RectF nextBounds = new RectF(
                    left + slide, top, left + width + slide, top + height);
            if (!message.hitBounds.equals(nextBounds)) {
                message.hitBounds.set(nextBounds);
                bubbleBoundsChanged = true;
            }
        } else if (!message.hitBounds.isEmpty()) {
            message.hitBounds.setEmpty();
            bubbleBoundsChanged = true;
        }

        canvas.save();
        canvas.translate(slide, 0f);
        int layer = -1;
        if (fadeTopHalf) {
            layer = canvas.saveLayer(left - dpFloat(5), top - dpFloat(5),
                    left + width + dpFloat(5), top + height + dpFloat(7), null);
        }

        chatShadowPaint.setColor(0x26000000);
        canvas.drawRoundRect(left, top + dpFloat(2), left + width,
                top + height + dpFloat(2), radiusPx, radiusPx, chatShadowPaint);
        chatBubblePaint.setColor(message.fromUser ? 0xFF347ED6 : 0xFFF7F8FC);
        canvas.drawRoundRect(left, top, left + width, top + height,
                radiusPx, radiusPx, chatBubblePaint);

        Path tail = new Path();
        float tailY = top + height - dpFloat(13);
        if (message.fromUser) {
            tail.moveTo(left + width - dpFloat(4), tailY - dpFloat(6));
            tail.lineTo(left + width + dpFloat(7), tailY + dpFloat(8));
            tail.lineTo(left + width - dpFloat(7), tailY + dpFloat(5));
        } else {
            tail.moveTo(left + dpFloat(4), tailY - dpFloat(6));
            tail.lineTo(left - dpFloat(7), tailY + dpFloat(8));
            tail.lineTo(left + dpFloat(7), tailY + dpFloat(5));
        }
        tail.close();
        canvas.drawPath(tail, chatBubblePaint);

        chatTextPaint.setColor(message.fromUser ? Color.WHITE : 0xFF18233B);
        canvas.save();
        canvas.translate(left + paddingX, top + paddingY);
        layout.draw(canvas);
        canvas.restore();

        if (message.expandable) {
            String toggle = message.expanded ? "收起 ▲" : "展开 ▼";
            float originalSize = chatTextPaint.getTextSize();
            chatTextPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
            chatTextPaint.setColor(message.fromUser ? 0xD9FFFFFF : 0xB35A657A);
            float toggleWidth = chatTextPaint.measureText(toggle);
            canvas.drawText(toggle, left + width - paddingX - toggleWidth,
                    top + height - paddingY * 0.65f, chatTextPaint);
            chatTextPaint.setTextSize(originalSize);
        }

        if (fadeTopHalf) {
            chatFadePaint.setShader(new LinearGradient(0f, top, 0f, top + height * 0.55f,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            chatFadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            canvas.drawRect(left - dpFloat(8), top - dpFloat(8),
                    left + width + dpFloat(8), top + height + dpFloat(8), chatFadePaint);
            chatFadePaint.setXfermode(null);
            chatFadePaint.setShader(null);
            canvas.restoreToCount(layer);
        }
        canvas.restore();
    }

    private float dpFloat(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class ChatMessage {
        final String text;
        final boolean fromUser;
        final long createdAtMillis;
        final boolean expandable;
        final RectF hitBounds = new RectF();
        boolean expanded;

        ChatMessage(String text, boolean fromUser, long createdAtMillis) {
            this.text = text;
            this.fromUser = fromUser;
            this.createdAtMillis = createdAtMillis;
            expandable = text.length() > EXPANDABLE_TEXT_LENGTH;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(this);
        super.onDetachedFromWindow();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float normalizeAngle(float angle) {
        while (angle > Math.PI) angle -= (float) (Math.PI * 2.0);
        while (angle < -Math.PI) angle += (float) (Math.PI * 2.0);
        return angle;
    }
}
