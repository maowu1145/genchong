package com.maowu.gravitypet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

public class PetMakerActivity extends Activity {
    public static final String MODE_DRAW = "draw";
    public static final String MODE_IMPORT = "import";
    public static final int CUSTOM_SKIN_ID = 13;
    private static final String EXTRA_MODE = "maker_mode";
    private static final String EXTRA_EDIT_SKIN_ID = "edit_skin_id";
    private static final int REQUEST_IMAGE = 701;
    private static final String CUSTOM_FILE_NAME = "custom_pet_300.png";

    private MakerCanvasView makerCanvas;
    private HsvColorPickerView colorPicker;
    private TextView thicknessValue;
    private TextView thicknessLabel;
    private SeekBar thicknessBar;
    private final Map<Integer, Button> toolButtons = new LinkedHashMap<>();
    private boolean importMode;
    private int editingSkinId = -1;
    private int pencilThickness;
    private int eraserThickness;

    private static final String KEY_PENCIL_THICKNESS = "maker_pencil_thickness";
    private static final String KEY_ERASER_THICKNESS = "maker_eraser_thickness";
    private static final String KEY_SAVED_COLOR_PREFIX = "maker_saved_color_";

    public static Intent intent(Context context, String mode) {
        return new Intent(context, PetMakerActivity.class).putExtra(EXTRA_MODE, mode);
    }

    public static Intent intentForEdit(Context context, int skinId) {
        return new Intent(context, PetMakerActivity.class)
                .putExtra(EXTRA_MODE, MODE_DRAW)
                .putExtra(EXTRA_EDIT_SKIN_ID, skinId);
    }

    public static File customSkinFile(Context context) {
        return new File(context.getFilesDir(), CUSTOM_FILE_NAME);
    }

    public static File skinFile(Context context, int skinId) {
        if (skinId == CUSTOM_SKIN_ID) return customSkinFile(context);
        if (skinId >= 1 && skinId <= 12) {
            return new File(context.getFilesDir(), "edited_skin_" + skinId + ".png");
        }
        return null;
    }

    public static int builtInSkinResource(int skinId) {
        switch (skinId) {
            case 1: return R.drawable.skin_cat;
            case 2: return R.drawable.skin_fafa;
            case 3: return R.drawable.skin_france;
            case 4: return R.drawable.skin_chocolate;
            case 5: return R.drawable.skin_cat_mew;
            case 6: return R.drawable.skin_bigan;
            case 7: return R.drawable.skin_cat_dirty;
            case 8: return R.drawable.skin_mumu;
            case 9: return R.drawable.skin_plolt;
            case 10: return R.drawable.skin_pale_green;
            case 11: return R.drawable.skin_fat_mouse;
            case 12: return R.drawable.skin_twin_tail;
            default: return 0;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importMode = MODE_IMPORT.equals(getIntent().getStringExtra(EXTRA_MODE));
        editingSkinId = getIntent().getIntExtra(EXTRA_EDIT_SKIN_ID, -1);
        pencilThickness = PetPreferences.get(this).getInt(KEY_PENCIL_THICKNESS, 14);
        eraserThickness = PetPreferences.get(this).getInt(KEY_ERASER_THICKNESS, 24);
        getWindow().setStatusBarColor(Color.rgb(174, 213, 241));
        getWindow().setNavigationBarColor(Color.rgb(174, 213, 241));
        setContentView(buildContent());
        if (editingSkinId > 0 && savedInstanceState == null) {
            makerCanvas.post(this::loadSkinForEditing);
        } else if (importMode && savedInstanceState == null) {
            makerCanvas.post(this::openImagePicker);
        }
    }

    private View buildContent() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(24));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        GradientDrawable sky = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(174, 213, 241), Color.rgb(213, 237, 250)});
        content.setBackground(sky);

        TextView title = new TextView(this);
        title.setText(editingSkinId > 0
                ? "编辑" + PetPreferences.getPetName(this, editingSkinId)
                : importMode ? "导入小鼻嘎" : "绘制小鼻嘎");
        AppVisualStyle.styleTitle(title);
        title.setGravity(Gravity.CENTER);
        content.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        FrameLayout canvasCard = new FrameLayout(this);
        canvasCard.setClipChildren(false);
        canvasCard.setClipToPadding(false);
        canvasCard.setPadding(dp(7), dp(7), dp(7), dp(7));
        canvasCard.setBackground(AppVisualStyle.bordered(
                AppVisualStyle.CARD, 0xFFB9D5E7, 22f, density));
        canvasCard.setElevation(dp(5));
        makerCanvas = new MakerCanvasView(this, !importMode);
        canvasCard.addView(makerCanvas, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        int availableWidth = getResources().getDisplayMetrics().widthPixels - dp(48);
        int canvasSize = Math.min(availableWidth, dp(360));
        LinearLayout.LayoutParams canvasParams = new LinearLayout.LayoutParams(
                canvasSize, canvasSize);
        canvasParams.topMargin = dp(10);
        content.addView(canvasCard, canvasParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(14), dp(12), dp(14), dp(14));
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        controls.setBackground(AppVisualStyle.rounded(AppVisualStyle.CARD, 22f, density));
        controls.setElevation(dp(3));
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        controlsParams.topMargin = dp(14);
        content.addView(controls, controlsParams);

        addTools(controls);
        addThickness(controls);
        if (!importMode) addPalette(controls);

        Button back = new Button(this);
        back.setText("返回");
        AppVisualStyle.styleSecondary(back, density);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        backParams.topMargin = dp(12);
        content.addView(back, backParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.addView(content);
        return scroll;
    }

    private void addPalette(LinearLayout root) {
        TextView label = sectionLabel("调色板");
        root.addView(label);
        colorPicker = new HsvColorPickerView(this, makerCanvas.getPaintColor());
        colorPicker.setOnColorChangedListener(makerCanvas::setPaintColor);
        LinearLayout holder = new LinearLayout(this);
        holder.setGravity(Gravity.CENTER);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        holder.addView(colorPicker, new LinearLayout.LayoutParams(dp(210), dp(210)));
        LinearLayout.LayoutParams holderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        holderParams.topMargin = dp(4);
        root.addView(holder, holderParams);
        addSavedColors(root);
    }

    private void addSavedColors(LinearLayout root) {
        TextView label = sectionLabel("收藏颜色");
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28));
        labelParams.topMargin = dp(6);
        root.addView(label, labelParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        for (int i = 0; i < 6; i++) {
            final int slot = i;
            TextView colorSlot = new TextView(this);
            colorSlot.setGravity(Gravity.CENTER);
            colorSlot.setTextSize(22f);
            colorSlot.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            refreshSavedColorSlot(colorSlot, slot);
            colorSlot.setOnClickListener(v -> {
                String key = KEY_SAVED_COLOR_PREFIX + slot;
                if (PetPreferences.get(this).contains(key)) {
                    int color = PetPreferences.get(this).getInt(key, makerCanvas.getPaintColor());
                    makerCanvas.setPaintColor(color);
                    colorPicker.setColor(color);
                } else {
                    saveColorToSlot(colorSlot, slot);
                }
            });
            colorSlot.setOnLongClickListener(v -> {
                saveColorToSlot(colorSlot, slot);
                return true;
            });
            AppVisualStyle.applyPressMotion(colorSlot);
            LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            slotParams.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(colorSlot, slotParams);
        }
        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
    }

    private void saveColorToSlot(TextView view, int slot) {
        PetPreferences.get(this).edit()
                .putInt(KEY_SAVED_COLOR_PREFIX + slot, makerCanvas.getPaintColor())
                .apply();
        refreshSavedColorSlot(view, slot);
        Toast.makeText(this, "颜色已保存", Toast.LENGTH_SHORT).show();
    }

    private void refreshSavedColorSlot(TextView view, int slot) {
        String key = KEY_SAVED_COLOR_PREFIX + slot;
        boolean saved = PetPreferences.get(this).contains(key);
        int color = saved ? PetPreferences.get(this).getInt(key, Color.WHITE) : 0xFFF4F7F9;
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        background.setStroke(dp(2), saved ? 0xFF708391 : AppVisualStyle.TRACK);
        view.setBackground(background);
        view.setText(saved ? "" : "+");
        view.setTextColor(AppVisualStyle.BLUE_DARK);
        view.setContentDescription(saved
                ? "收藏颜色" + (slot + 1) + "，点击使用，长按替换"
                : "空颜色位" + (slot + 1) + "，点击保存");
    }

    private void addThickness(LinearLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        thicknessLabel = sectionLabel("粗细");
        thicknessValue = sectionLabel("14");
        thicknessValue.setGravity(Gravity.END);
        header.addView(thicknessLabel, new LinearLayout.LayoutParams(0, dp(28), 1f));
        header.addView(thicknessValue, new LinearLayout.LayoutParams(dp(52), dp(28)));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(10);
        root.addView(header, headerParams);

        thicknessBar = new SeekBar(this);
        thicknessBar.setMin(2);
        thicknessBar.setMax(60);
        AppVisualStyle.styleSeekBar(thicknessBar);
        thicknessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                makerCanvas.setBrushSize(progress);
                thicknessValue.setText(String.valueOf(progress));
                if (!fromUser) return;
                int tool = makerCanvas.getTool();
                if (tool == MakerCanvasView.TOOL_PENCIL) {
                    pencilThickness = progress;
                    PetPreferences.get(PetMakerActivity.this).edit()
                            .putInt(KEY_PENCIL_THICKNESS, progress).apply();
                } else if (tool == MakerCanvasView.TOOL_ERASER
                        || tool == MakerCanvasView.TOOL_RESTORE) {
                    eraserThickness = progress;
                    PetPreferences.get(PetMakerActivity.this).edit()
                            .putInt(KEY_ERASER_THICKNESS, progress).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(thicknessBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        syncThicknessForTool();
    }

    private void addTools(LinearLayout root) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        if (importMode) {
            addToolButton(grid, "橡皮擦", MakerCanvasView.TOOL_ERASER);
            addToolButton(grid, "恢复擦", MakerCanvasView.TOOL_RESTORE);
            addToolButton(grid, "锚点", MakerCanvasView.TOOL_ANCHOR);
            addActionButton(grid, "撤回", v -> makerCanvas.undo());
            addActionButton(grid, "重新导入", v -> openImagePicker());
            addActionButton(grid, "保存", v -> savePet());
            makerCanvas.setTool(MakerCanvasView.TOOL_ERASER);
        } else {
            addToolButton(grid, "铅笔", MakerCanvasView.TOOL_PENCIL);
            addToolButton(grid, "橡皮擦", MakerCanvasView.TOOL_ERASER);
            addToolButton(grid, "填色", MakerCanvasView.TOOL_FILL);
            addToolButton(grid, "锚点", MakerCanvasView.TOOL_ANCHOR);
            addActionButton(grid, "撤回", v -> makerCanvas.undo());
            addActionButton(grid, "保存", v -> savePet());
            makerCanvas.setTool(MakerCanvasView.TOOL_PENCIL);
        }
        root.addView(grid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshToolButtons();
    }

    private void addToolButton(GridLayout grid, String text, int tool) {
        Button button = toolButton(text);
        toolButtons.put(tool, button);
        button.setOnClickListener(v -> {
            makerCanvas.setTool(tool);
            syncThicknessForTool();
            refreshToolButtons();
        });
        grid.addView(button, toolParams());
    }

    private void addActionButton(GridLayout grid, String text, View.OnClickListener listener) {
        Button button = toolButton(text);
        button.setOnClickListener(listener);
        grid.addView(button, toolParams());
    }

    private Button toolButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        AppVisualStyle.styleSecondary(button, getResources().getDisplayMetrics().density);
        return button;
    }

    private GridLayout.LayoutParams toolParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(48);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(4), dp(5), dp(4), dp(5));
        return params;
    }

    private void refreshToolButtons() {
        float density = getResources().getDisplayMetrics().density;
        for (Map.Entry<Integer, Button> entry : toolButtons.entrySet()) {
            boolean selected = entry.getKey() == makerCanvas.getTool();
            Button button = entry.getValue();
            button.setTextColor(selected ? Color.WHITE : AppVisualStyle.BLUE_DARK);
            button.setBackground(selected
                    ? AppVisualStyle.rounded(AppVisualStyle.BLUE, 16f, density)
                    : AppVisualStyle.bordered(AppVisualStyle.CARD,
                            AppVisualStyle.TRACK, 16f, density));
        }
    }

    private void syncThicknessForTool() {
        if (thicknessBar == null || thicknessLabel == null || makerCanvas == null) return;
        int tool = makerCanvas.getTool();
        if (tool == MakerCanvasView.TOOL_PENCIL) {
            thicknessLabel.setText("画笔粗细");
            thicknessBar.setEnabled(true);
            thicknessBar.setAlpha(1f);
            thicknessBar.setProgress(pencilThickness);
            makerCanvas.setBrushSize(pencilThickness);
        } else if (tool == MakerCanvasView.TOOL_ERASER
                || tool == MakerCanvasView.TOOL_RESTORE) {
            thicknessLabel.setText("橡皮擦粗细");
            thicknessBar.setEnabled(true);
            thicknessBar.setAlpha(1f);
            thicknessBar.setProgress(eraserThickness);
            makerCanvas.setBrushSize(eraserThickness);
        } else {
            thicknessLabel.setText("粗细");
            thicknessBar.setEnabled(false);
            thicknessBar.setAlpha(0.42f);
        }
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(AppVisualStyle.TEXT);
        label.setTextSize(15f);
        label.setGravity(Gravity.CENTER_VERTICAL);
        return label;
    }

    private void openImagePicker() {
        Intent picker;
        if (Build.VERSION.SDK_INT >= 33) {
            picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
            picker.setType("image/*");
        } else {
            picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("image/*");
        }
        try {
            startActivityForResult(picker, REQUEST_IMAGE);
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try {
            Bitmap source = decodeImage(data.getData());
            if (source == null) throw new IllegalArgumentException("图片无法读取");
            makerCanvas.setImportedBitmap(source);
            source.recycle();
        } catch (Exception error) {
            Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap decodeImage(Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) ->
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        }
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(stream);
        }
    }

    private void loadSkinForEditing() {
        Bitmap source = null;
        File file = skinFile(this, editingSkinId);
        if (file != null && file.isFile()) {
            source = BitmapFactory.decodeFile(file.getAbsolutePath());
        }
        if (source == null) {
            int resource = builtInSkinResource(editingSkinId);
            if (resource != 0) source = BitmapFactory.decodeResource(getResources(), resource);
        }
        if (source == null) {
            Toast.makeText(this, "无法读取小鼻嘎", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        makerCanvas.setImportedBitmap(source);
        makerCanvas.setAnchor(
                PetPreferences.getAnchorX(this, editingSkinId, 0.5f),
                PetPreferences.getAnchorY(this, editingSkinId,
                        editingSkinId == CUSTOM_SKIN_ID ? 0.82f : 1f));
        source.recycle();
    }

    private void savePet() {
        Bitmap bitmap = makerCanvas.getBitmap();
        if (bitmap == null) {
            Toast.makeText(this, "请先导入图片", Toast.LENGTH_SHORT).show();
            return;
        }
        int targetSkinId = editingSkinId > 0 ? editingSkinId : CUSTOM_SKIN_ID;
        File targetFile = skinFile(this, targetSkinId);
        Bitmap exportBitmap = makeVisiblePixelsOpaque(bitmap);
        try (FileOutputStream output = new FileOutputStream(targetFile)) {
            if (!exportBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("PNG 保存失败");
            }
            PetPreferences.get(this).edit()
                    .putInt(PetPreferences.KEY_SKIN, targetSkinId)
                    .apply();
            PetPreferences.setAnchor(this, targetSkinId,
                    makerCanvas.getAnchorX(), makerCanvas.getAnchorY());
            if (OverlayPetService.isRunning()) {
                startService(new Intent(this, OverlayPetService.class)
                        .setAction(OverlayPetService.ACTION_REFRESH_SKIN)
                        .putExtra(OverlayPetService.EXTRA_SKIN_ID, targetSkinId));
            }
            Toast.makeText(this, "已保存并选中", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
        } finally {
            exportBitmap.recycle();
        }
    }

    static Bitmap makeVisiblePixelsOpaque(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        int[] pixels = new int[width * height];
        result.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int alpha = (pixels[i] >>> 24) & 0xff;
            pixels[i] = alpha < 64 ? Color.TRANSPARENT : pixels[i] | 0xff000000;
        }
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class HsvColorPickerView extends View {
        interface OnColorChangedListener {
            void onColorChanged(int color);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF saturationValueRect = new RectF();
        private final RectF hueRect = new RectF();
        private final float density;
        private float hue;
        private float saturation;
        private float value;
        private OnColorChangedListener listener;

        HsvColorPickerView(Context context, int initialColor) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            float[] hsv = new float[3];
            Color.colorToHSV(initialColor, hsv);
            hue = hsv[0];
            saturation = hsv[1];
            value = hsv[2];
            setLayerType(LAYER_TYPE_HARDWARE, null);
            setContentDescription("连续调色板");
        }

        void setOnColorChangedListener(OnColorChangedListener listener) {
            this.listener = listener;
        }

        void setColor(int color) {
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            hue = hsv[0];
            saturation = hsv[1];
            value = hsv[2];
            if (listener != null) listener.onColorChanged(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float inset = 8f * density;
            float hueWidth = 24f * density;
            float gap = 10f * density;
            saturationValueRect.set(inset, inset,
                    getWidth() - inset - hueWidth - gap, getHeight() - inset);
            hueRect.set(saturationValueRect.right + gap, inset,
                    getWidth() - inset, getHeight() - inset);

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
            canvas.drawRoundRect(saturationValueRect, 10f * density, 10f * density, paint);

            paint.setShader(new LinearGradient(
                    saturationValueRect.left, 0f, saturationValueRect.right, 0f,
                    Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(saturationValueRect, 10f * density, 10f * density, paint);
            paint.setShader(new LinearGradient(
                    0f, saturationValueRect.top, 0f, saturationValueRect.bottom,
                    0x00000000, Color.BLACK, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(saturationValueRect, 10f * density, 10f * density, paint);

            int[] hueColors = {
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                    0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
            };
            paint.setShader(new LinearGradient(0f, hueRect.top, 0f, hueRect.bottom,
                    hueColors, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(hueRect, hueWidth * 0.5f, hueWidth * 0.5f, paint);
            paint.setShader(null);

            float selectorX = saturationValueRect.left
                    + saturation * saturationValueRect.width();
            float selectorY = saturationValueRect.top
                    + (1f - value) * saturationValueRect.height();
            markerPaint.setStyle(Paint.Style.STROKE);
            markerPaint.setStrokeWidth(4f * density);
            markerPaint.setColor(0xCC000000);
            canvas.drawCircle(selectorX, selectorY, 9f * density, markerPaint);
            markerPaint.setStrokeWidth(2f * density);
            markerPaint.setColor(Color.WHITE);
            canvas.drawCircle(selectorX, selectorY, 8f * density, markerPaint);

            float hueY = hueRect.top + hue / 360f * hueRect.height();
            markerPaint.setStyle(Paint.Style.STROKE);
            markerPaint.setStrokeWidth(3f * density);
            markerPaint.setColor(Color.WHITE);
            canvas.drawRoundRect(hueRect.left - 3f * density, hueY - 5f * density,
                    hueRect.right + 3f * density, hueY + 5f * density,
                    5f * density, 5f * density, markerPaint);
            markerPaint.setStrokeWidth(1f * density);
            markerPaint.setColor(0xCC000000);
            canvas.drawRoundRect(hueRect.left - 4f * density, hueY - 6f * density,
                    hueRect.right + 4f * density, hueY + 6f * density,
                    6f * density, 6f * density, markerPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (action != MotionEvent.ACTION_DOWN
                    && action != MotionEvent.ACTION_MOVE) return true;
            float x = event.getX();
            float y = event.getY();
            if (x >= hueRect.left - 6f * density) {
                hue = clamp01((y - hueRect.top) / hueRect.height()) * 360f;
                if (hue >= 360f) hue = 359.999f;
            } else {
                saturation = clamp01((x - saturationValueRect.left)
                        / saturationValueRect.width());
                value = 1f - clamp01((y - saturationValueRect.top)
                        / saturationValueRect.height());
            }
            if (listener != null) {
                listener.onColorChanged(Color.HSVToColor(
                        new float[]{hue, saturation, value}));
            }
            invalidate();
            return true;
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }

    private static final class MakerCanvasView extends View {
        static final int TOOL_PENCIL = 1;
        static final int TOOL_ERASER = 2;
        static final int TOOL_RESTORE = 3;
        static final int TOOL_FILL = 4;
        static final int TOOL_ANCHOR = 5;
        private static final int SIZE = 300;
        private static final int MAX_UNDO = 16;

        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkerPaint = new Paint();
        private final RectF destination = new RectF();
        private final ArrayDeque<UndoState> undo = new ArrayDeque<>();
        private final boolean drawingMode;
        private Bitmap bitmap;
        private Bitmap original;
        private Canvas bitmapCanvas;
        private int tool = TOOL_PENCIL;
        private int paintColor = 0xFF3F4A52;
        private float brushSize = 14f;
        private float lastX;
        private float lastY;
        private float anchorX = 0.5f;
        private float anchorY = 0.82f;
        private boolean singleTouchPending;
        private boolean scaling;
        private boolean ignoreUntilAllPointersUp;
        private float downX;
        private float downY;
        private float scaleStartSpan;
        private float scaleStartFocusX;
        private float scaleStartFocusY;
        private float scaleStartAnchorX;
        private float scaleStartAnchorY;
        private UndoState scaleStartState;

        MakerCanvasView(Context context, boolean drawingMode) {
            super(context);
            this.drawingMode = drawingMode;
            setLayerType(LAYER_TYPE_HARDWARE, null);
            brushPaint.setStrokeCap(Paint.Cap.ROUND);
            brushPaint.setStrokeJoin(Paint.Join.ROUND);
            brushPaint.setStyle(Paint.Style.STROKE);
            guidePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            if (drawingMode) {
                bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.TRANSPARENT);
                bitmapCanvas = new Canvas(bitmap);
            }
        }

        Bitmap getBitmap() { return bitmap; }
        int getTool() { return tool; }
        int getPaintColor() { return paintColor; }
        float getAnchorX() { return anchorX; }
        float getAnchorY() { return anchorY; }

        void setTool(int tool) {
            this.tool = tool;
            invalidate();
        }

        void setPaintColor(int color) {
            paintColor = color;
            invalidate();
        }

        void setBrushSize(float size) {
            brushSize = Math.max(2f, Math.min(60f, size));
        }

        void setAnchor(float x, float y) {
            anchorX = Math.max(0f, Math.min(1f, x));
            anchorY = Math.max(0f, Math.min(1f, y));
            invalidate();
        }

        void setImportedBitmap(Bitmap source) {
            clearUndo();
            recycleBitmap(bitmap);
            recycleBitmap(original);
            bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            float scale = Math.min((float) SIZE / source.getWidth(),
                    (float) SIZE / source.getHeight());
            float width = source.getWidth() * scale;
            float height = source.getHeight() * scale;
            RectF target = new RectF((SIZE - width) * 0.5f, (SIZE - height) * 0.5f,
                    (SIZE + width) * 0.5f, (SIZE + height) * 0.5f);
            canvas.drawBitmap(source, null, target, bitmapPaint);
            original = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            bitmapCanvas = new Canvas(bitmap);
            invalidate();
        }

        void undo() {
            if (undo.isEmpty()) return;
            recycleBitmap(bitmap);
            recycleBitmap(original);
            UndoState state = undo.removeLast();
            bitmap = state.bitmap;
            original = state.original;
            anchorX = state.anchorX;
            anchorY = state.anchorY;
            bitmapCanvas = new Canvas(bitmap);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight());
            float left = (getWidth() - size) * 0.5f;
            float top = (getHeight() - size) * 0.5f;
            destination.set(left, top, left + size, top + size);
            drawCanvasBackground(canvas);
            if (bitmap != null) canvas.drawBitmap(bitmap, null, destination, bitmapPaint);
            if (tool == TOOL_ANCHOR || bitmap != null) drawAnchor(canvas);
        }

        private void drawCanvasBackground(Canvas canvas) {
            if (drawingMode) {
                checkerPaint.setColor(Color.rgb(183, 189, 178));
                canvas.drawRect(destination, checkerPaint);
                return;
            }
            int cells = 12;
            float cell = destination.width() / cells;
            for (int row = 0; row < cells; row++) {
                for (int column = 0; column < cells; column++) {
                    checkerPaint.setColor(((row + column) & 1) == 0
                            ? 0xFFF4F7F4 : 0xFFE3E9E4);
                    canvas.drawRect(destination.left + column * cell,
                            destination.top + row * cell,
                            destination.left + (column + 1) * cell,
                            destination.top + (row + 1) * cell, checkerPaint);
                }
            }
        }

        private void drawAnchor(Canvas canvas) {
            float x = destination.left + anchorX * destination.width();
            float y = destination.top + anchorY * destination.height();
            float radius = Math.max(10f, destination.width() * 0.028f);
            guidePaint.setStyle(Paint.Style.FILL);
            guidePaint.setColor(0xE8E94D49);
            canvas.drawCircle(x, y, radius, guidePaint);
            guidePaint.setStyle(Paint.Style.STROKE);
            guidePaint.setStrokeWidth(Math.max(2f, destination.width() * 0.007f));
            guidePaint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, radius * 0.58f, guidePaint);
            guidePaint.setStyle(Paint.Style.FILL);
            guidePaint.setTextSize(Math.max(13f, destination.width() * 0.038f));
            guidePaint.setColor(0xE83B4650);
            canvas.drawText("锚点", x + radius * 1.3f, y + radius * 0.45f, guidePaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (bitmap == null) return true;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    ignoreUntilAllPointersUp = false;
                    singleTouchPending = destination.contains(event.getX(), event.getY());
                    downX = toBitmapX(event.getX());
                    downY = toBitmapY(event.getY());
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2) {
                        singleTouchPending = false;
                        startScaleGesture(event);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (scaling && event.getPointerCount() >= 2) {
                        updateScaleGesture(event);
                        return true;
                    }
                    if (ignoreUntilAllPointersUp || event.getPointerCount() != 1) return true;
                    float x = toBitmapX(event.getX());
                    float y = toBitmapY(event.getY());
                    if (singleTouchPending) beginSingleTouch(downX, downY);
                    if (tool == TOOL_ANCHOR) {
                        anchorX = x / SIZE;
                        anchorY = y / SIZE;
                    } else if (tool != TOOL_FILL) {
                        paintStroke(lastX, lastY, x, y);
                        lastX = x;
                        lastY = y;
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (scaling) finishScaleGesture();
                    ignoreUntilAllPointersUp = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!ignoreUntilAllPointersUp && singleTouchPending) {
                        beginSingleTouch(downX, downY);
                    }
                    finishScaleGesture();
                    singleTouchPending = false;
                    ignoreUntilAllPointersUp = false;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    finishScaleGesture();
                    singleTouchPending = false;
                    ignoreUntilAllPointersUp = false;
                    return true;
                default:
                    return true;
            }
        }

        private void beginSingleTouch(float x, float y) {
            singleTouchPending = false;
            pushUndo();
            if (tool == TOOL_ANCHOR) {
                anchorX = x / SIZE;
                anchorY = y / SIZE;
                return;
            }
            if (tool == TOOL_FILL) {
                floodFill(Math.round(x), Math.round(y), paintColor);
                return;
            }
            lastX = x;
            lastY = y;
            paintStroke(x, y, x, y);
        }

        private void startScaleGesture(MotionEvent event) {
            if (scaling || event.getPointerCount() < 2) return;
            scaleStartState = pushUndo();
            if (scaleStartState == null) return;
            scaling = true;
            scaleStartSpan = Math.max(1f, pointerSpan(event));
            scaleStartFocusX = toBitmapX(pointerFocusX(event));
            scaleStartFocusY = toBitmapY(pointerFocusY(event));
            scaleStartAnchorX = anchorX;
            scaleStartAnchorY = anchorY;
        }

        private void updateScaleGesture(MotionEvent event) {
            if (!scaling || scaleStartState == null || event.getPointerCount() < 2) return;
            float scale = Math.max(0.2f,
                    Math.min(5f, pointerSpan(event) / scaleStartSpan));
            float focusX = toBitmapX(pointerFocusX(event));
            float focusY = toBitmapY(pointerFocusY(event));
            renderTransformed(bitmapCanvas, scaleStartState.bitmap,
                    scale, scaleStartFocusX, scaleStartFocusY, focusX, focusY);
            if (original != null && scaleStartState.original != null) {
                renderTransformed(new Canvas(original), scaleStartState.original,
                        scale, scaleStartFocusX, scaleStartFocusY, focusX, focusY);
            }
            anchorX = clampCoordinate((focusX
                    + (scaleStartAnchorX * SIZE - scaleStartFocusX) * scale) / SIZE);
            anchorY = clampCoordinate((focusY
                    + (scaleStartAnchorY * SIZE - scaleStartFocusY) * scale) / SIZE);
            invalidate();
        }

        private void renderTransformed(Canvas target, Bitmap source, float scale,
                                       float startFocusX, float startFocusY,
                                       float currentFocusX, float currentFocusY) {
            target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            target.save();
            target.translate(currentFocusX, currentFocusY);
            target.scale(scale, scale);
            target.translate(-startFocusX, -startFocusY);
            target.drawBitmap(source, 0f, 0f, bitmapPaint);
            target.restore();
        }

        private void finishScaleGesture() {
            scaling = false;
            scaleStartState = null;
        }

        private float toBitmapX(float viewX) {
            return clamp((viewX - destination.left) / destination.width() * SIZE);
        }

        private float toBitmapY(float viewY) {
            return clamp((viewY - destination.top) / destination.height() * SIZE);
        }

        private static float pointerFocusX(MotionEvent event) {
            float total = 0f;
            for (int i = 0; i < event.getPointerCount(); i++) total += event.getX(i);
            return total / event.getPointerCount();
        }

        private static float pointerFocusY(MotionEvent event) {
            float total = 0f;
            for (int i = 0; i < event.getPointerCount(); i++) total += event.getY(i);
            return total / event.getPointerCount();
        }

        private static float pointerSpan(MotionEvent event) {
            if (event.getPointerCount() < 2) return 1f;
            return (float) Math.hypot(
                    event.getX(0) - event.getX(1),
                    event.getY(0) - event.getY(1));
        }

        private void paintStroke(float fromX, float fromY, float toX, float toY) {
            brushPaint.setStrokeWidth(brushSize);
            brushPaint.setShader(null);
            brushPaint.setXfermode(null);
            if (tool == TOOL_ERASER) {
                brushPaint.setColor(Color.TRANSPARENT);
                brushPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            } else if (tool == TOOL_RESTORE && original != null) {
                brushPaint.setShader(new BitmapShader(
                        original, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                brushPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            } else {
                brushPaint.setColor(paintColor);
            }
            brushPaint.setStyle(Paint.Style.STROKE);
            bitmapCanvas.drawLine(fromX, fromY, toX, toY, brushPaint);
            brushPaint.setStyle(Paint.Style.FILL);
            bitmapCanvas.drawCircle(toX, toY, brushSize * 0.5f, brushPaint);
            brushPaint.setStyle(Paint.Style.STROKE);
            brushPaint.setShader(null);
            brushPaint.setXfermode(null);
        }

        private void floodFill(int startX, int startY, int replacement) {
            startX = Math.max(0, Math.min(SIZE - 1, startX));
            startY = Math.max(0, Math.min(SIZE - 1, startY));
            int[] pixels = new int[SIZE * SIZE];
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
            int target = pixels[startY * SIZE + startX];
            if (target == replacement) return;
            int[] queue = new int[SIZE * SIZE];
            int head = 0;
            int tail = 0;
            queue[tail++] = startY * SIZE + startX;
            pixels[startY * SIZE + startX] = replacement;
            while (head < tail) {
                int index = queue[head++];
                int x = index % SIZE;
                int y = index / SIZE;
                if (x > 0) tail = enqueueFill(pixels, queue, tail, index - 1, target, replacement);
                if (x < SIZE - 1) tail = enqueueFill(pixels, queue, tail, index + 1, target, replacement);
                if (y > 0) tail = enqueueFill(pixels, queue, tail, index - SIZE, target, replacement);
                if (y < SIZE - 1) tail = enqueueFill(pixels, queue, tail, index + SIZE, target, replacement);
            }
            bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        }

        private int enqueueFill(int[] pixels, int[] queue, int tail, int index,
                                int target, int replacement) {
            if (pixels[index] != target) return tail;
            pixels[index] = replacement;
            queue[tail] = index;
            return tail + 1;
        }

        private UndoState pushUndo() {
            if (bitmap == null) return null;
            if (undo.size() >= MAX_UNDO) undo.removeFirst().recycle();
            UndoState state = new UndoState(
                    bitmap.copy(Bitmap.Config.ARGB_8888, true),
                    original == null ? null : original.copy(Bitmap.Config.ARGB_8888, true),
                    anchorX, anchorY);
            undo.addLast(state);
            return state;
        }

        private void clearUndo() {
            while (!undo.isEmpty()) undo.removeFirst().recycle();
        }

        private static float clamp(float value) {
            return Math.max(0f, Math.min(SIZE - 1f, value));
        }

        private static float clampCoordinate(float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        private static void recycleBitmap(Bitmap value) {
            if (value != null && !value.isRecycled()) value.recycle();
        }

        @Override
        protected void onDetachedFromWindow() {
            clearUndo();
            super.onDetachedFromWindow();
        }

        private static final class UndoState {
            final Bitmap bitmap;
            final Bitmap original;
            final float anchorX;
            final float anchorY;

            UndoState(Bitmap bitmap, Bitmap original, float anchorX, float anchorY) {
                this.bitmap = bitmap;
                this.original = original;
                this.anchorX = anchorX;
                this.anchorY = anchorY;
            }

            void recycle() {
                recycleBitmap(bitmap);
                recycleBitmap(original);
            }
        }
    }
}
