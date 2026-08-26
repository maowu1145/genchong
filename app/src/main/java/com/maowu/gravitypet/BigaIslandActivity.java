package com.maowu.gravitypet;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;

import java.io.File;

public class BigaIslandActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int sea = Color.rgb(102, 194, 226);
        getWindow().setStatusBarColor(sea);
        getWindow().setNavigationBarColor(Color.rgb(71, 167, 205));

        FrameLayout root = new FrameLayout(this);
        IslandView islandView = new IslandView(this);
        root.addView(islandView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        Button back = new Button(this);
        back.setText("返回");
        AppVisualStyle.styleSecondary(back, getResources().getDisplayMetrics().density);
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(82), dp(44));
        backParams.gravity = Gravity.START | Gravity.TOP;
        backParams.setMargins(dp(14), dp(12), 0, 0);
        root.addView(back, backParams);

        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            islandView.setSystemInsets(top, bottom);
            backParams.setMargins(dp(14), top + dp(12), 0, 0);
            back.setLayoutParams(backParams);
            return insets;
        });
        root.post(root::requestApplyInsets);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class IslandView extends View {
        private static final int NO_POINTER = -1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        private Bitmap petBitmap;
        private float petBottomTransparentRatio;
        private int topInset;
        private int bottomInset;

        private float islandX;
        private float islandY;
        private float islandRadius;
        private float grassRadius;
        private float petNX;
        private float petNY;
        private float petRadius;

        private float joystickX;
        private float joystickY;
        private float joystickRadius;
        private float knobX;
        private float knobY;
        private int joystickPointer = NO_POINTER;

        private float interactButtonX;
        private float interactButtonY;
        private float actionRadius;
        private int jumpPointer = NO_POINTER;
        private int interactPointer = NO_POINTER;
        private boolean interactPressed;
        private boolean interactionAvailable;

        private float jumpHeight;
        private float jumpVelocity;
        private float walkPhase;
        private float walkIntensity;
        private long lastFrameNanos;
        private long sceneStartNanos;

        IslandView(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            paint.setFilterBitmap(true);
            loadSelectedPet(context);
            setFocusable(true);
            setClickable(true);
            setContentDescription("鼻嘎小岛，左下区域拖动移动，右半屏点击跳跃");
            sceneStartNanos = System.nanoTime();
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            configureLayout(width, height);
        }

        void setSystemInsets(int top, int bottom) {
            if (topInset == top && bottomInset == bottom) return;
            topInset = Math.max(0, top);
            bottomInset = Math.max(0, bottom);
            configureLayout(getWidth(), getHeight());
            invalidate();
        }

        private void configureLayout(int width, int height) {
            if (width <= 0 || height <= 0) return;
            boolean landscape = width > height;
            float safeHeight = Math.max(1f, height - topInset - bottomInset);
            if (landscape) {
                islandX = width * 0.50f;
                islandY = topInset + safeHeight * 0.43f;
                islandRadius = Math.min(safeHeight * 0.34f, width * 0.28f);
                interactButtonX = width - 64f * density;
                interactButtonY = height - bottomInset - 72f * density;
            } else {
                islandX = width * 0.50f;
                islandY = topInset + safeHeight * 0.37f;
                islandRadius = Math.min(width * 0.47f, safeHeight * 0.30f);
                interactButtonX = width - 71f * density;
                interactButtonY = height - bottomInset - 48f * density;
            }
            grassRadius = islandRadius * 0.91f;
            petRadius = Math.min(27f * density, islandRadius * 0.115f);
            joystickRadius = Math.min(58f * density, Math.min(width, height) * 0.14f);
            actionRadius = Math.min(35f * density, Math.min(width, height) * 0.085f);
            clampPetToIsland();
        }

        private void loadSelectedPet(Context context) {
            int skin = PetPreferences.get(context).getInt(PetPreferences.KEY_SKIN, 0);
            if (skin == 0) return;
            Bitmap source = null;
            File file = PetMakerActivity.skinFile(context, skin);
            if (file != null && file.isFile()) {
                source = BitmapFactory.decodeFile(file.getAbsolutePath());
            }
            if (source == null) {
                int resource = PetMakerActivity.builtInSkinResource(skin);
                if (resource != 0) source = BitmapFactory.decodeResource(
                        getResources(), resource);
            }
            if (source != null) {
                petBitmap = PetMakerActivity.makeVisiblePixelsOpaque(source);
                petBottomTransparentRatio = findBottomTransparentRatio(petBitmap);
                source.recycle();
            }
        }

        /** Returns the transparent space below the lowest visible outline row. */
        private float findBottomTransparentRatio(Bitmap bitmap) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            for (int row = height - 1; row >= 0; row--) {
                int rowStart = row * width;
                for (int column = 0; column < width; column++) {
                    if (((pixels[rowStart + column] >>> 24) & 0xff) != 0) {
                        return 1f - ((row + 1f) / height);
                    }
                }
            }
            return 0f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = System.nanoTime();
            if (lastFrameNanos != 0L) {
                float dt = Math.min(0.034f, (now - lastFrameNanos) / 1_000_000_000f);
                updateScene(dt);
            }
            lastFrameNanos = now;

            drawSea(canvas, now);
            drawIsland(canvas);
            drawPet(canvas);
            if (interactionAvailable) {
                drawActionButton(canvas, interactButtonX, interactButtonY, "互动",
                        interactPressed, 0xFF56A96A);
            }
            postInvalidateOnAnimation();
        }

        private void updateScene(float dt) {
            float inputX = knobX;
            float inputY = knobY;
            float inputLength = (float) Math.hypot(inputX, inputY);
            float oldX = petNX;
            float oldY = petNY;
            if (inputLength > 0.04f) {
                float speed = 0.62f;
                petNX += inputX * speed * dt;
                petNY += inputY * speed * dt;
                clampPetToIsland();
            }
            float travel = (float) Math.hypot(petNX - oldX, petNY - oldY) * grassRadius;
            boolean grounded = jumpHeight == 0f && jumpVelocity == 0f;
            float expectedTravel = Math.max(0.001f, 0.62f * grassRadius * dt);
            float movementRatio = Math.min(1f, travel / expectedTravel);
            float targetWalk = grounded ? movementRatio : 0f;
            float response = Math.min(1f, dt * (targetWalk > walkIntensity ? 14f : 9f));
            walkIntensity += (targetWalk - walkIntensity) * response;
            if (grounded && travel > 0f) {
                walkPhase += travel / Math.max(1f, petRadius * 0.55f);
            }
            if (jumpHeight > 0f || jumpVelocity > 0f) {
                jumpVelocity -= 930f * density * dt;
                jumpHeight += jumpVelocity * dt;
                if (jumpHeight <= 0f) {
                    jumpHeight = 0f;
                    jumpVelocity = 0f;
                }
            }
        }

        private void clampPetToIsland() {
            float max = 0.78f;
            float distance = (float) Math.hypot(petNX, petNY);
            if (distance > max) {
                petNX = petNX / distance * max;
                petNY = petNY / distance * max;
            }
        }

        private void drawSea(Canvas canvas, long now) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0f, 0f, 0f, getHeight(),
                    0xFF7CD0EA, 0xFF45A8CF, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
            paint.setShader(null);

            float time = (now - sceneStartNanos) / 1_000_000_000f;
            stroke.setStrokeWidth(1.6f * density);
            stroke.setColor(0x3DFFFFFF);
            float spacingY = 46f * density;
            float spacingX = 92f * density;
            float drift = (time * 12f * density) % spacingX;
            for (float y = 52f * density; y < getHeight(); y += spacingY) {
                float rowOffset = (((int) (y / spacingY)) & 1) == 0 ? 0f : spacingX * 0.5f;
                for (float x = -spacingX + rowOffset + drift;
                     x < getWidth() + spacingX; x += spacingX) {
                    rect.set(x, y, x + 42f * density, y + 12f * density);
                    canvas.drawArc(rect, 205f, 130f, false, stroke);
                }
            }
        }

        private void drawIsland(Canvas canvas) {
            paint.setShader(null);
            paint.setColor(0x350E5367);
            canvas.drawCircle(islandX + 5f * density, islandY + 12f * density,
                    islandRadius * 1.035f, paint);

            paint.setAlpha(255);
            paint.setShader(new RadialGradient(
                    islandX - islandRadius * 0.25f,
                    islandY - islandRadius * 0.28f,
                    islandRadius * 1.35f,
                    new int[]{0xFFFFE6A3, 0xFFE9BE68}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(islandX, islandY, islandRadius, paint);

            paint.setShader(new RadialGradient(
                    islandX - grassRadius * 0.28f,
                    islandY - grassRadius * 0.30f,
                    grassRadius * 1.32f,
                    new int[]{0xFF78CB72, 0xFF3E9E58}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(islandX, islandY, grassRadius, paint);
            paint.setShader(null);

            stroke.setColor(0x50FFFFFF);
            stroke.setStrokeWidth(2.2f * density);
            rect.set(islandX - islandRadius * 1.06f, islandY - islandRadius * 1.06f,
                    islandX + islandRadius * 1.06f, islandY + islandRadius * 1.06f);
            canvas.drawArc(rect, 15f, 130f, false, stroke);
            canvas.drawArc(rect, 202f, 105f, false, stroke);
        }

        private void drawPet(Canvas canvas) {
            float groundX = islandX + petNX * grassRadius;
            float groundY = islandY + petNY * grassRadius;
            float lift = jumpHeight;
            float step = Math.abs((float) Math.sin(walkPhase));
            float bob = step * petRadius * 0.10f * walkIntensity;
            float scaleY = 1f - step * 0.10f * walkIntensity;
            float scaleX = 1f + step * 0.065f * walkIntensity;
            float sway = (float) Math.sin(walkPhase) * 7.5f * walkIntensity;

            float shadowScale = Math.max(0.46f, 1f - lift / (115f * density));
            paint.setShader(null);
            paint.setColor((int) (0x45 * shadowScale) << 24);
            rect.set(groundX - petRadius * shadowScale,
                    groundY + petRadius * 0.70f - petRadius * 0.28f * shadowScale,
                    groundX + petRadius * shadowScale,
                    groundY + petRadius * 0.70f + petRadius * 0.28f * shadowScale);
            canvas.drawOval(rect, paint);

            float y = groundY - lift - bob;
            if (petBitmap != null && !petBitmap.isRecycled()) {
                float spriteSize = petRadius * 2.55f;
                paint.setAlpha(255);
                paint.setColor(Color.WHITE);
                canvas.save();
                canvas.translate(groundX, y + petRadius * 0.72f);
                canvas.rotate(sway);
                canvas.scale(scaleX, scaleY);
                // The canvas edge is not the character's foot. Shift the full
                // texture down by its transparent bottom padding so the lowest
                // visible alpha-outline pixel stays exactly on the ground pivot.
                float contactOffset = spriteSize * petBottomTransparentRatio;
                rect.set(-spriteSize * 0.5f, -spriteSize + contactOffset,
                        spriteSize * 0.5f, contactOffset);
                canvas.drawBitmap(petBitmap, null, rect, paint);
                canvas.restore();
                return;
            }
            canvas.save();
            canvas.translate(groundX, y + petRadius);
            canvas.rotate(sway);
            canvas.scale(scaleX, scaleY);
            paint.setColor(0xFFF8FBFC);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(0f, -petRadius, petRadius, paint);
            stroke.setColor(0xFF4E6571);
            stroke.setStrokeWidth(Math.max(2f, petRadius * 0.075f));
            canvas.drawCircle(0f, -petRadius, petRadius, stroke);

            float eyeY = -petRadius * 1.15f;
            paint.setColor(0xFF263840);
            canvas.drawCircle(-petRadius * 0.32f, eyeY,
                    petRadius * 0.105f, paint);
            canvas.drawCircle(petRadius * 0.32f, eyeY,
                    petRadius * 0.105f, paint);
            stroke.setStrokeWidth(Math.max(1.5f, petRadius * 0.06f));
            stroke.setColor(0xFF60747D);
            rect.set(-petRadius * 0.22f, -petRadius * 0.96f,
                    petRadius * 0.22f, -petRadius * 0.66f);
            canvas.drawArc(rect, 18f, 144f, false, stroke);
            canvas.restore();
        }

        private void drawActionButton(Canvas canvas, float x, float y, String label,
                                      boolean pressed, int color) {
            paint.setShader(null);
            paint.setColor(pressed ? darken(color) : color);
            canvas.drawCircle(x, y, actionRadius, paint);
            stroke.setColor(0xDDF4FBFF);
            stroke.setStrokeWidth(2.2f * density);
            canvas.drawCircle(x, y, actionRadius - 2f * density, stroke);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(Math.max(12f * density, actionRadius * 0.42f));
            Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(label, x, y - (metrics.ascent + metrics.descent) * 0.5f, paint);
        }

        private int darken(int color) {
            return Color.rgb((int) (Color.red(color) * 0.78f),
                    (int) (Color.green(color) * 0.78f),
                    (int) (Color.blue(color) * 0.78f));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            int actionIndex = event.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                handlePointerDown(event.getPointerId(actionIndex),
                        event.getX(actionIndex), event.getY(actionIndex));
            } else if (action == MotionEvent.ACTION_MOVE) {
                updateJoystickPointer(event);
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_POINTER_UP) {
                handlePointerUp(event.getPointerId(actionIndex));
                if (action == MotionEvent.ACTION_UP) performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                resetTouches();
            }
            invalidate();
            return true;
        }

        private void handlePointerDown(int pointerId, float x, float y) {
            // 互动物体出现后，互动按钮优先于右半屏的跳跃操作。
            if (interactionAvailable && interactPointer == NO_POINTER
                    && distance(x, y, interactButtonX, interactButtonY) <= actionRadius * 1.22f) {
                interactPointer = pointerId;
                interactPressed = true;
                return;
            }
            // 左下区域是一整块隐形移动区，按下位置就是本次轮盘中心。
            if (joystickPointer == NO_POINTER && isMovementControlArea(x, y)) {
                joystickPointer = pointerId;
                joystickX = x;
                joystickY = y;
                knobX = 0f;
                knobY = 0f;
                return;
            }
            // 右半屏不显示按钮，点击任意位置即跳跃。
            if (jumpPointer == NO_POINTER && x >= getWidth() * 0.50f) {
                jumpPointer = pointerId;
                if (jumpHeight == 0f && jumpVelocity == 0f) {
                    jumpVelocity = 395f * density;
                }
            }
        }

        private boolean isMovementControlArea(float x, float y) {
            float safeTop = topInset;
            float safeBottom = getHeight() - bottomInset;
            float safeHeight = Math.max(1f, safeBottom - safeTop);
            return x < getWidth() * 0.50f
                    && y >= safeTop + safeHeight * 0.50f
                    && y <= safeBottom;
        }

        private void updateJoystickPointer(MotionEvent event) {
            if (joystickPointer == NO_POINTER) return;
            int index = event.findPointerIndex(joystickPointer);
            if (index >= 0) setJoystickPosition(event.getX(index), event.getY(index));
        }

        private void setJoystickPosition(float x, float y) {
            float dx = (x - joystickX) / joystickRadius;
            float dy = (y - joystickY) / joystickRadius;
            float length = (float) Math.hypot(dx, dy);
            if (length > 1f) {
                dx /= length;
                dy /= length;
            }
            knobX = dx;
            knobY = dy;
        }

        private void handlePointerUp(int pointerId) {
            if (pointerId == joystickPointer) {
                joystickPointer = NO_POINTER;
                knobX = 0f;
                knobY = 0f;
            }
            if (pointerId == jumpPointer) {
                jumpPointer = NO_POINTER;
            }
            if (pointerId == interactPointer) {
                interactPointer = NO_POINTER;
                interactPressed = false;
            }
        }

        private void resetTouches() {
            joystickPointer = NO_POINTER;
            jumpPointer = NO_POINTER;
            interactPointer = NO_POINTER;
            knobX = 0f;
            knobY = 0f;
            interactPressed = false;
        }

        private float distance(float x1, float y1, float x2, float y2) {
            return (float) Math.hypot(x1 - x2, y1 - y2);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onDetachedFromWindow() {
            lastFrameNanos = 0L;
            if (petBitmap != null && !petBitmap.isRecycled()) petBitmap.recycle();
            petBitmap = null;
            super.onDetachedFromWindow();
        }
    }
}
