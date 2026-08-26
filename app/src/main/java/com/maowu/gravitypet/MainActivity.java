package com.maowu.gravitypet;

import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.PathInterpolator;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.File;

public class MainActivity extends Activity {
    private View actionButton;
    private View recallButton;
    private View skinButton;
    private GuardToggleView guardToggle;
    private SeekBar hueBar;
    private SeekBar saturationBar;
    private SeekBar brightnessBar;
    private SeekBar opacityBar;
    private SeekBar ballSizeBar;
    private SeekBar gravityBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContentView());

        actionButton.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                summonPet();
            }
        });
        recallButton.setOnClickListener(v -> {
            if (OverlayPetService.isRunning()) {
                stopService(new Intent(this, OverlayPetService.class));
                recallButton.postDelayed(this::refreshState, 150);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void summonPet() {
        Intent intent = new Intent(this, OverlayPetService.class);
        if (OverlayPetService.isRunning()) {
            intent.setAction(OverlayPetService.ACTION_SUMMON_PET);
        }
        try {
            startForegroundService(intent);
            actionButton.postDelayed(this::refreshState, 200);
        } catch (RuntimeException error) {
            Toast.makeText(this, "小鼻嘎启动失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshState() {
        boolean permitted = Settings.canDrawOverlays(this);
        boolean running = OverlayPetService.isRunning();
        int count = OverlayPetService.getPetCount();

        actionButton.setAlpha(count >= 9 ? 0.55f : 1f);
        actionButton.setContentDescription(!permitted
                ? "授予悬浮窗权限"
                : "召唤小鼻嘎，当前" + count + "只");
        recallButton.setAlpha(running ? 1f : 0.55f);
        recallButton.setContentDescription("收回全部小鼻嘎");
        if (guardToggle != null) {
            guardToggle.setChecked(PetPreferences.get(this).getBoolean(
                    PetPreferences.KEY_KEYBOARD_GUARD, false));
        }
    }

    private View buildContentView() {
        getWindow().setStatusBarColor(Color.rgb(174, 213, 241));
        getWindow().setNavigationBarColor(Color.rgb(174, 213, 241));

        PullParallaxLayout root = new PullParallaxLayout(this);
        root.setBackgroundColor(Color.rgb(174, 213, 241));

        View sky = new SkyView(this);
        root.addDesignChild(sky, 0, 0, 862, 1825, 0f);

        View clouds = new CloudsView(this);
        root.addDesignChild(clouds, 0, 0, 862, 2050, 0.2f);

        View titleDecoration = new TitleDecorationView(this);
        root.addDesignChild(titleDecoration, 0, 0, 862, 1825, 0.46f);

        TextView title = designText(R.string.main_title, Color.rgb(24, 105, 205));
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        title.setShadowLayer(2.5f, 0f, 2f, 0x33000000);
        root.addDesignChild(title, 200, 176, 627, 352, 0.46f);

        actionButton = new VectorButton(this, R.string.launch_pet, VectorButton.STYLE_LAUNCH);
        root.addDesignChild(actionButton, 150, 562, 424, 790, 1f);

        recallButton = new VectorButton(this, R.string.recall_pet, VectorButton.STYLE_LAUNCH);
        root.addDesignChild(recallButton, 457, 562, 731, 790, 1f);

        skinButton = new VectorButton(this, R.string.skin_picker, VectorButton.STYLE_MENU);
        skinButton.setContentDescription("小鼻嘎选择");
        skinButton.setOnClickListener(v -> showSkinPicker());
        root.addDesignChild(skinButton, 152, 802, 728, 906, 1f);

        View colorButton = new VectorButton(this, R.string.color_tuning, VectorButton.STYLE_MENU);
        colorButton.setContentDescription("制作小鼻嘎");
        colorButton.setOnClickListener(v -> showMakerOptions());
        root.addDesignChild(colorButton, 152, 925, 728, 1029, 1f);

        View islandButton = new VectorButton(this, R.string.biga_island, VectorButton.STYLE_MENU);
        islandButton.setContentDescription("进入鼻嘎小岛");
        islandButton.setOnClickListener(v ->
                startActivity(new Intent(this, BigaIslandActivity.class)));
        root.addDesignChild(islandButton, 152, 1048, 728, 1152, 1f);

        View physicsButton = new VectorButton(this, R.string.physics_settings, VectorButton.STYLE_MENU);
        physicsButton.setContentDescription("物理参数");
        physicsButton.setOnClickListener(v -> showMotionSettings());
        root.addDesignChild(physicsButton, 152, 1171, 728, 1275, 1f);

        View experimentalButton = new VectorButton(
                this, R.string.experimental_features, VectorButton.STYLE_MENU);
        experimentalButton.setContentDescription("实验性功能");
        experimentalButton.setOnClickListener(v -> showExperimentalFeatures());
        root.addDesignChild(experimentalButton, 152, 1294, 728, 1398, 1f);

        View guardButton = new VectorButton(this, R.string.operation_guard, VectorButton.STYLE_GUARD);
        guardButton.setContentDescription("操作防遮挡");
        guardButton.setOnClickListener(v -> toggleKeyboardGuard());
        root.addDesignChild(guardButton, 152, 1417, 728, 1533, 1f);

        guardToggle = new GuardToggleView(this);
        guardToggle.setCheckedImmediately(PetPreferences.get(this).getBoolean(
                PetPreferences.KEY_KEYBOARD_GUARD, false));
        guardToggle.setOnClickListener(v -> toggleKeyboardGuard());
        root.addDesignChild(guardToggle, 558, 1445, 662, 1503, 1f);

        View grass = new GrassView(this);
        root.addDesignChild(grass, 0, 0, 862, 2050, 0.72f);
        root.post(root::playEntrance);
        return root;
    }

    private TextView designText(int stringId, int color) {
        TextView view = new TextView(this);
        view.setText(stringId);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setAutoSizeTextTypeUniformWithConfiguration(
                20, 46, 1, TypedValue.COMPLEX_UNIT_SP);
        return view;
    }

    private void toggleKeyboardGuard() {
        SharedPreferences preferences = PetPreferences.get(this);
        boolean next = !preferences.getBoolean(PetPreferences.KEY_KEYBOARD_GUARD, false);
        preferences.edit().putBoolean(PetPreferences.KEY_KEYBOARD_GUARD, next).apply();
        guardToggle.setChecked(next);
    }

    private void showColorSettings() {
        SharedPreferences preferences = PetPreferences.get(this);
        LinearLayout content = dialogContent();

        hueBar = addSlider(content, "色相", 360,
                preferences.getInt(PetPreferences.KEY_HUE, 0));
        saturationBar = addSlider(content, "鲜艳度", 100,
                preferences.getInt(PetPreferences.KEY_SATURATION, 0));
        brightnessBar = addSlider(content, "亮度", 100,
                preferences.getInt(PetPreferences.KEY_BRIGHTNESS, 100));
        opacityBar = addSlider(content, "透明度", 100,
                preferences.getInt(PetPreferences.KEY_OPACITY_PERCENT, 100));
        opacityBar.setMin(10);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updateBallColor();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        hueBar.setOnSeekBarChangeListener(listener);
        saturationBar.setOnSeekBarChangeListener(listener);
        brightnessBar.setOnSeekBarChangeListener(listener);
        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    preferences.edit()
                            .putInt(PetPreferences.KEY_OPACITY_PERCENT, progress)
                            .apply();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        showSecondaryDialog("小鼻嘎调色", content, "关闭");
    }

    private void showMakerOptions() {
        LinearLayout content = dialogContent();
        Button draw = new Button(this);
        draw.setText("绘制");
        AppVisualStyle.stylePrimary(draw, getResources().getDisplayMetrics().density);
        Button importImage = new Button(this);
        importImage.setText("导入");
        AppVisualStyle.styleSecondary(importImage,
                getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        content.addView(draw, buttonParams);
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        importParams.topMargin = dp(12);
        content.addView(importImage, importParams);

        AlertDialog dialog = showSecondaryDialog("制作小鼻嘎", content, "取消");
        draw.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(PetMakerActivity.intent(this, PetMakerActivity.MODE_DRAW));
        });
        importImage.setOnClickListener(v -> {
            dialog.dismiss();
            startActivity(PetMakerActivity.intent(this, PetMakerActivity.MODE_IMPORT));
        });
    }

    private void showSkinPicker() {
        SharedPreferences preferences = PetPreferences.get(this);
        int selectedSkin = preferences.getInt(PetPreferences.KEY_SKIN, 0);
        Set<String> hiddenSkins = new HashSet<>(preferences.getStringSet(
                PetPreferences.KEY_HIDDEN_SKINS, java.util.Collections.emptySet()));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), 0);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        final AlertDialog[] pickerDialog = new AlertDialog[1];
        Map<Integer, ImageButton> skinChoices = new LinkedHashMap<>();
        final Button[] defaultPetReference = new Button[1];
        int[] skinIds = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        int[] skinResources = {
                R.drawable.skin_cat,
                R.drawable.skin_fafa,
                R.drawable.skin_france,
                R.drawable.skin_chocolate,
                R.drawable.skin_cat_mew,
                R.drawable.skin_bigan,
                R.drawable.skin_cat_dirty,
                R.drawable.skin_mumu,
                R.drawable.skin_plolt,
                R.drawable.skin_pale_green,
                R.drawable.skin_fat_mouse,
                R.drawable.skin_twin_tail
        };
        for (int i = 0; i < skinIds.length; i++) {
            int skinId = skinIds[i];
            if (hiddenSkins.contains(String.valueOf(skinId))) continue;
            String petName = PetPreferences.getPetName(this, skinId);
            ImageButton choice = new ImageButton(this);
            File editedSkin = PetMakerActivity.skinFile(this, skinId);
            Bitmap editedPreview = editedSkin != null && editedSkin.isFile()
                    ? BitmapFactory.decodeFile(editedSkin.getAbsolutePath()) : null;
            if (editedPreview != null) {
                Bitmap opaquePreview = PetMakerActivity.makeVisiblePixelsOpaque(editedPreview);
                editedPreview.recycle();
                choice.setImageBitmap(opaquePreview);
            } else {
                choice.setImageResource(skinResources[i]);
            }
            choice.setImageTintList(null);
            choice.clearColorFilter();
            choice.setScaleType(ImageView.ScaleType.FIT_CENTER);
            choice.setPadding(dp(3), dp(3), dp(3), dp(3));
            choice.setContentDescription(petName);
            choice.setBackground(AppVisualStyle.bordered(
                    selectedSkin == skinId ? 0xFFD8EEFC : AppVisualStyle.CARD,
                    selectedSkin == skinId ? AppVisualStyle.BLUE : 0xFFD4E4EE,
                    14f, getResources().getDisplayMetrics().density));
            AppVisualStyle.applyPressMotion(choice);
            skinChoices.put(skinId, choice);
            choice.setOnClickListener(v -> {
                preferences.edit().putInt(PetPreferences.KEY_SKIN, skinId).apply();
                updateSkinButtonText();
                refreshSkinPickerSelection(skinChoices,
                        defaultPetReference[0], skinId);
            });
            View skinCell = namedSkinCell(choice, petName);
            choice.setOnLongClickListener(v -> {
                showPetActions(skinId, PetPreferences.getPetName(this, skinId),
                        pickerDialog[0], skinCell);
                return true;
            });
            grid.addView(skinCell, skinCellParams());
        }
        File customSkin = PetMakerActivity.customSkinFile(this);
        if (customSkin.isFile()) {
            int skinId = PetMakerActivity.CUSTOM_SKIN_ID;
            ImageButton choice = new ImageButton(this);
            choice.setImageTintList(null);
            choice.clearColorFilter();
            Bitmap previewSource = BitmapFactory.decodeFile(customSkin.getAbsolutePath());
            if (previewSource != null) {
                Bitmap opaquePreview = PetMakerActivity.makeVisiblePixelsOpaque(previewSource);
                previewSource.recycle();
                choice.setImageBitmap(opaquePreview);
            }
            choice.setScaleType(ImageView.ScaleType.FIT_CENTER);
            choice.setPadding(dp(3), dp(3), dp(3), dp(3));
            String customPetName = PetPreferences.getPetName(this, skinId);
            choice.setContentDescription(customPetName);
            choice.setBackground(AppVisualStyle.bordered(
                    selectedSkin == skinId ? 0xFFD8EEFC : AppVisualStyle.CARD,
                    selectedSkin == skinId ? AppVisualStyle.BLUE : 0xFFD4E4EE,
                    14f, getResources().getDisplayMetrics().density));
            AppVisualStyle.applyPressMotion(choice);
            skinChoices.put(skinId, choice);
            choice.setOnClickListener(v -> {
                preferences.edit().putInt(PetPreferences.KEY_SKIN, skinId).apply();
                updateSkinButtonText();
                refreshSkinPickerSelection(skinChoices,
                        defaultPetReference[0], skinId);
            });
            View skinCell = namedSkinCell(choice, customPetName);
            choice.setOnLongClickListener(v -> {
                showPetActions(skinId, PetPreferences.getPetName(this, skinId),
                        pickerDialog[0], skinCell);
                return true;
            });
            grid.addView(skinCell, skinCellParams());
        }
        content.addView(grid);

        Button defaultPet = new Button(this);
        defaultPetReference[0] = defaultPet;
        defaultPet.setText("默认小鼻嘎");
        if (selectedSkin == 0) {
            AppVisualStyle.stylePrimary(defaultPet,
                    getResources().getDisplayMetrics().density);
        } else {
            AppVisualStyle.styleSecondary(defaultPet,
                    getResources().getDisplayMetrics().density);
        }
        defaultPet.setOnClickListener(v -> {
            preferences.edit().putInt(PetPreferences.KEY_SKIN, 0).apply();
            updateSkinButtonText();
            refreshSkinPickerSelection(skinChoices, defaultPet, 0);
        });
        defaultPet.setOnLongClickListener(v -> {
            showPersonaEditor(0);
            return true;
        });
        LinearLayout.LayoutParams defaultParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        defaultParams.topMargin = dp(12);
        content.addView(defaultPet, defaultParams);

        if (!hiddenSkins.isEmpty()) {
            Button restoreDeleted = new Button(this);
            restoreDeleted.setText("恢复已删除");
            AppVisualStyle.styleSecondary(restoreDeleted,
                    getResources().getDisplayMetrics().density);
            restoreDeleted.setOnClickListener(v -> {
                preferences.edit().remove(PetPreferences.KEY_HIDDEN_SKINS).apply();
                if (pickerDialog[0] != null) pickerDialog[0].dismiss();
                showSkinPicker();
            });
            LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
            restoreParams.topMargin = dp(10);
            content.addView(restoreDeleted, restoreParams);
        }

        pickerDialog[0] = showSecondaryDialog("选择小鼻嘎", content, "关闭",
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.56f));
    }

    private void refreshSkinPickerSelection(Map<Integer, ImageButton> choices,
                                            Button defaultPet, int selectedSkin) {
        float density = getResources().getDisplayMetrics().density;
        for (Map.Entry<Integer, ImageButton> entry : choices.entrySet()) {
            boolean selected = entry.getKey() == selectedSkin;
            entry.getValue().setBackground(AppVisualStyle.bordered(
                    selected ? 0xFFD8EEFC : AppVisualStyle.CARD,
                    selected ? AppVisualStyle.BLUE : 0xFFD4E4EE,
                    14f, density));
        }
        if (defaultPet != null) {
            if (selectedSkin == 0) AppVisualStyle.stylePrimary(defaultPet, density);
            else AppVisualStyle.styleSecondary(defaultPet, density);
        }
    }

    private void showPetActions(int skin, String petName, AlertDialog pickerDialog,
                                View skinCell) {
        LinearLayout content = dialogContent();
        EditText nameEditor = new EditText(this);
        nameEditor.setSingleLine(true);
        nameEditor.setHint("名称");
        nameEditor.setText(petName);
        nameEditor.setSelection(nameEditor.getText().length());
        nameEditor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        AppVisualStyle.styleInput(nameEditor, getResources().getDisplayMetrics().density);
        content.addView(nameEditor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        Button saveName = new Button(this);
        saveName.setText("保存名称");
        AppVisualStyle.styleSecondary(saveName, getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams saveNameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        saveNameParams.topMargin = dp(10);
        content.addView(saveName, saveNameParams);

        Button edit = new Button(this);
        edit.setText("编辑");
        AppVisualStyle.stylePrimary(edit, getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        editParams.topMargin = dp(12);
        content.addView(edit, editParams);

        Button delete = new Button(this);
        delete.setText("删除");
        delete.setTextColor(Color.WHITE);
        delete.setBackground(AppVisualStyle.rounded(
                0xFFE05B5B, 16f, getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        deleteParams.topMargin = dp(12);
        content.addView(delete, deleteParams);

        AlertDialog dialog = showSecondaryDialog(petName, content, "取消");
        saveName.setOnClickListener(v -> {
            String newName = nameEditor.getText().toString().trim();
            if (newName.isEmpty()) {
                nameEditor.setError("请输入名称");
                return;
            }
            PetPreferences.setPetName(this, skin, newName);
            if (skinCell instanceof ViewGroup && ((ViewGroup) skinCell).getChildCount() > 1
                    && ((ViewGroup) skinCell).getChildAt(1) instanceof TextView) {
                TextView label = (TextView) ((ViewGroup) skinCell).getChildAt(1);
                label.setText(newName);
                label.setTextSize(newName.length() > 3 ? 9 : 11);
            }
            if (skinCell instanceof ViewGroup && ((ViewGroup) skinCell).getChildCount() > 0) {
                ((ViewGroup) skinCell).getChildAt(0).setContentDescription(newName);
            }
            updateSkinButtonText();
            dialog.dismiss();
            Toast.makeText(this, "名称已保存", Toast.LENGTH_SHORT).show();
        });
        edit.setOnClickListener(v -> {
            dialog.dismiss();
            showPetEditorActions(skin, PetPreferences.getPetName(this, skin), pickerDialog);
        });
        delete.setOnClickListener(v -> {
            dialog.dismiss();
            showDeletePetConfirmation(skin, PetPreferences.getPetName(this, skin),
                    pickerDialog, skinCell);
        });
    }

    private void showPetEditorActions(int skin, String petName, AlertDialog pickerDialog) {
        LinearLayout content = dialogContent();
        Button draw = new Button(this);
        draw.setText("绘制");
        AppVisualStyle.stylePrimary(draw, getResources().getDisplayMetrics().density);
        content.addView(draw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        Button soul = new Button(this);
        soul.setText("灵魂");
        AppVisualStyle.styleSecondary(soul, getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams soulParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        soulParams.topMargin = dp(12);
        content.addView(soul, soulParams);

        AlertDialog dialog = showSecondaryDialog("编辑" + petName, content, "取消");
        draw.setOnClickListener(v -> {
            dialog.dismiss();
            if (pickerDialog != null) pickerDialog.dismiss();
            startActivity(PetMakerActivity.intentForEdit(this, skin));
        });
        soul.setOnClickListener(v -> {
            dialog.dismiss();
            showPersonaEditor(skin);
        });
    }

    private void showDeletePetConfirmation(int skin, String petName,
                                           AlertDialog pickerDialog, View skinCell) {
        LinearLayout content = dialogContent();
        Button confirm = new Button(this);
        confirm.setText("删除");
        confirm.setTextColor(Color.WHITE);
        confirm.setBackground(AppVisualStyle.rounded(
                0xFFE05B5B, 16f, getResources().getDisplayMetrics().density));
        content.addView(confirm, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
        AlertDialog dialog = showSecondaryDialog("删除" + petName + "？", content, "取消");
        confirm.setOnClickListener(v -> {
            deletePet(skin);
            dialog.dismiss();
            if (skinCell.getParent() instanceof ViewGroup) {
                ((ViewGroup) skinCell.getParent()).removeView(skinCell);
            }
        });
    }

    private void deletePet(int skin) {
        SharedPreferences preferences = PetPreferences.get(this);
        File file = PetMakerActivity.skinFile(this, skin);
        if (file != null && file.isFile()) file.delete();
        SharedPreferences.Editor editor = preferences.edit()
                .remove("pet_persona_" + skin)
                .remove("pet_name_" + skin)
                .remove("pet_anchor_x_" + skin)
                .remove("pet_anchor_y_" + skin);
        if (skin >= 1 && skin <= 12) {
            Set<String> hidden = new HashSet<>(preferences.getStringSet(
                    PetPreferences.KEY_HIDDEN_SKINS, java.util.Collections.emptySet()));
            hidden.add(String.valueOf(skin));
            editor.putStringSet(PetPreferences.KEY_HIDDEN_SKINS, hidden);
        }
        if (preferences.getInt(PetPreferences.KEY_SKIN, 0) == skin) {
            editor.putInt(PetPreferences.KEY_SKIN, 0);
        }
        editor.apply();
        if (OverlayPetService.isRunning()) {
            startService(new Intent(this, OverlayPetService.class)
                    .setAction(OverlayPetService.ACTION_DELETE_SKIN)
                    .putExtra(OverlayPetService.EXTRA_SKIN_ID, skin));
        }
        updateSkinButtonText();
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    private void showLegacySkinPicker() {
        SharedPreferences preferences = PetPreferences.get(this);
        int selectedSkin = preferences.getInt(PetPreferences.KEY_SKIN, 0);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), 0);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setRowCount(2);

        final AlertDialog[] dialogReference = new AlertDialog[1];
        ImageButton catSkin = new ImageButton(this);
        catSkin.setImageResource(R.drawable.skin_cat);
        catSkin.setScaleType(ImageView.ScaleType.FIT_CENTER);
        catSkin.setPadding(dp(3), dp(3), dp(3), dp(3));
        catSkin.setContentDescription("妙脆角猫");
        catSkin.setBackgroundColor(selectedSkin == 1
                ? Color.rgb(98, 85, 246)
                : Color.rgb(225, 222, 238));
        catSkin.setOnClickListener(v -> {
            preferences.edit().putInt(PetPreferences.KEY_SKIN, 1).apply();
            updateSkinButtonText();
            if (dialogReference[0] != null) dialogReference[0].dismiss();
        });
        grid.addView(namedSkinCell(catSkin, "妙脆角猫"), skinCellParams());

        catSkin.setOnLongClickListener(v -> {
            showPersonaEditor(1);
            return true;
        });

        ImageButton fafaSkin = new ImageButton(this);
        fafaSkin.setImageResource(R.drawable.skin_fafa);
        fafaSkin.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fafaSkin.setPadding(dp(3), dp(3), dp(3), dp(3));
        fafaSkin.setContentDescription("法法");
        fafaSkin.setTooltipText("法法");
        fafaSkin.setBackgroundColor(selectedSkin == 2
                ? Color.rgb(98, 85, 246)
                : Color.rgb(225, 222, 238));
        fafaSkin.setOnClickListener(v -> {
            preferences.edit().putInt(PetPreferences.KEY_SKIN, 2).apply();
            updateSkinButtonText();
            if (dialogReference[0] != null) dialogReference[0].dismiss();
        });
        grid.addView(namedSkinCell(fafaSkin, "法法"), skinCellParams());

        fafaSkin.setOnLongClickListener(v -> {
            showPersonaEditor(2);
            return true;
        });

        for (int i = 0; i < 8; i++) {
            Button empty = new Button(this);
            empty.setText("空");
            empty.setTextSize(12);
            empty.setEnabled(false);
            grid.addView(empty, skinCellParams());
        }
        content.addView(grid);

        Button restoreDefault = new Button(this);
        restoreDefault.setText("恢复默认小鼻嘎");
        restoreDefault.setAllCaps(false);
        restoreDefault.setOnClickListener(v -> {
            preferences.edit().putInt(PetPreferences.KEY_SKIN, 0).apply();
            updateSkinButtonText();
            if (dialogReference[0] != null) dialogReference[0].dismiss();
        });
        LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        restoreParams.topMargin = dp(12);
        content.addView(restoreDefault, restoreParams);

        dialogReference[0] = new AlertDialog.Builder(this)
                .setTitle("选择皮肤")
                .setView(content)
                .setNegativeButton("关闭", null)
                .create();
        dialogReference[0].show();
    }

    private GridLayout.LayoutParams skinCellParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(58);
        params.height = dp(72);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private View namedSkinCell(ImageButton imageButton, String name) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.addView(imageButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(name.length() > 3 ? 9 : 11);
        label.setTextColor(Color.rgb(20, 33, 61));
        label.setGravity(Gravity.CENTER);
        cell.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(20)));
        label.setOnClickListener(v -> imageButton.performClick());
        label.setOnLongClickListener(v -> imageButton.performLongClick());
        cell.setOnLongClickListener(v -> imageButton.performLongClick());
        return cell;
    }

    private void showPersonaEditor(int skin) {
        String petName = PetPreferences.getPetName(this, skin);
        LinearLayout content = dialogContent();
        EditText editor = new EditText(this);
        editor.setText(PetPreferences.getPersona(this, skin));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setMinLines(6);
        editor.setMaxLines(10);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        editor.setSelection(editor.getText().length());
        AppVisualStyle.styleInput(editor, getResources().getDisplayMetrics().density);
        content.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button clear = new Button(this);
        clear.setText("清空");
        AppVisualStyle.styleSecondary(clear, getResources().getDisplayMetrics().density);
        clear.setOnClickListener(v -> editor.setText(""));
        Button save = new Button(this);
        save.setText("保存");
        AppVisualStyle.stylePrimary(save, getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1f);
        half.setMargins(0, dp(14), dp(5), 0);
        actions.addView(clear, half);
        LinearLayout.LayoutParams otherHalf = new LinearLayout.LayoutParams(0, dp(48), 1f);
        otherHalf.setMargins(dp(5), dp(14), 0, 0);
        actions.addView(save, otherHalf);
        content.addView(actions);

        AlertDialog dialog = showSecondaryDialog(petName + "的灵魂",
                content, "取消");
        save.setOnClickListener(v -> {
            PetPreferences.setPersona(this, skin, editor.getText().toString());
            dialog.dismiss();
        });
    }

    private void updateSkinButtonText() {
        if (skinButton == null) return;
        int skin = PetPreferences.get(this).getInt(PetPreferences.KEY_SKIN, 0);
        skinButton.setContentDescription(skin == 0 ? "小鼻嘎选择"
                : "当前小鼻嘎：" + PetPreferences.getPetName(this, skin));
    }

    private void showMotionSettings() {
        SharedPreferences preferences = PetPreferences.get(this);
        LinearLayout content = dialogContent();

        ballSizeBar = addSlider(content, "小鼻嘎大小", 104,
                preferences.getInt(PetPreferences.KEY_BALL_DIAMETER_DP, 52));
        ballSizeBar.setMin(24);
        gravityBar = addSlider(content, "重力大小", 300,
                preferences.getInt(PetPreferences.KEY_GRAVITY_PERCENT, 100));
        gravityBar.setMin(25);

        ballSizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                preferences.edit()
                        .putInt(PetPreferences.KEY_BALL_DIAMETER_DP, progress)
                        .apply();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        gravityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    preferences.edit()
                            .putInt(PetPreferences.KEY_GRAVITY_PERCENT, progress)
                            .apply();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        showSecondaryDialog("物理参数", content, "关闭");
    }

    private void showExperimentalFeatures() {
        SharedPreferences preferences = PetPreferences.get(this);
        LinearLayout content = dialogContent();

        Button aiSettings = new Button(this);
        aiSettings.setText(R.string.ai_settings);
        AppVisualStyle.stylePrimary(aiSettings,
                getResources().getDisplayMetrics().density);
        aiSettings.setOnClickListener(v ->
                startActivity(new Intent(this, AiSettingsActivity.class)));
        LinearLayout.LayoutParams aiParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        aiParams.topMargin = dp(10);
        content.addView(aiSettings, aiParams);

        SeekBar musicDance = addSlider(content, "音乐律动", 100,
                preferences.getInt(PetPreferences.KEY_MUSIC_DANCE_PERCENT, 50));
        musicDance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    preferences.edit()
                            .putInt(PetPreferences.KEY_MUSIC_DANCE_PERCENT, progress)
                            .apply();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        showSecondaryDialog("实验性功能", content, "关闭");
    }

    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        return content;
    }

    private AlertDialog showSecondaryDialog(String titleText, View body,
                                            String closeText) {
        return showSecondaryDialog(titleText, body, closeText, 0);
    }

    private AlertDialog showSecondaryDialog(String titleText, View body,
                                            String closeText, int maxBodyHeight) {
        float density = getResources().getDisplayMetrics().density;
        int dialogWidth = Math.round(
                getResources().getDisplayMetrics().widthPixels * 0.91f);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(18), dp(18), dp(16));
        shell.setClipChildren(false);
        shell.setClipToPadding(false);
        shell.setBackground(AppVisualStyle.rounded(AppVisualStyle.PANEL, 26f, density));
        shell.setMinimumWidth(dialogWidth);
        // The dialog window itself appears immediately so its dim layer is
        // present on the first frame. Only the card animates afterwards.
        shell.setAlpha(0f);
        shell.setScaleX(0.975f);
        shell.setScaleY(0.975f);
        shell.setTranslationY(dp(14));

        TextView title = new TextView(this);
        title.setText(titleText);
        AppVisualStyle.styleTitle(title);
        title.setGravity(Gravity.CENTER_VERTICAL);
        shell.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        ScrollView scroll = maxBodyHeight > 0
                ? new BoundedScrollView(this, maxBodyHeight)
                : new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroll.setBackground(AppVisualStyle.rounded(AppVisualStyle.CARD, 20f, density));
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(8);
        shell.addView(scroll, scrollParams);

        Button close = new Button(this);
        close.setText(closeText);
        AppVisualStyle.styleSecondary(close, density);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        closeParams.topMargin = dp(12);
        shell.addView(close, closeParams);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(shell).create();
        close.setOnClickListener(v -> dialog.dismiss());
        Window preparedWindow = dialog.getWindow();
        if (preparedWindow != null) {
            preparedWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams preparedParams = preparedWindow.getAttributes();
            preparedParams.windowAnimations = R.style.SecondaryDialogAnimation;
            preparedParams.width = dialogWidth;
            preparedParams.dimAmount = 0.34f;
            preparedWindow.setAttributes(preparedParams);
            preparedWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.34f;
            window.setAttributes(params);
            shell.animate().cancel();
            shell.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(210L)
                    .setInterpolator(new PathInterpolator(0.16f, 0.82f, 0.22f, 1f))
                    .start();
        });
        dialog.show();
        return dialog;
    }

    private static final class BoundedScrollView extends ScrollView {
        private final int maxHeight;

        BoundedScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int available = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? maxHeight : Math.min(MeasureSpec.getSize(heightMeasureSpec), maxHeight);
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
        }
    }

    private SeekBar addSlider(LinearLayout root, String label, int max, int progress) {
        TextView labelView = bodyText(label);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(14);
        root.addView(labelView, labelParams);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max);
        seekBar.setProgress(progress);
        AppVisualStyle.styleSeekBar(seekBar);
        root.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        return seekBar;
    }

    private void updateBallColor() {
        int hue = hueBar.getProgress();
        int saturation = saturationBar.getProgress();
        int brightness = brightnessBar.getProgress();
        int color = Color.HSVToColor(new float[]{
                hue,
                saturation / 100f,
                Math.max(0.12f, brightness / 100f)});
        PetPreferences.get(this).edit()
                .putInt(PetPreferences.KEY_HUE, hue)
                .putInt(PetPreferences.KEY_SATURATION, saturation)
                .putInt(PetPreferences.KEY_BRIGHTNESS, brightness)
                .putInt(PetPreferences.KEY_COLOR, color)
                .apply();
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(77, 85, 111));
        view.setTextSize(14);
        view.setLineSpacing(0, 1.2f);
        return view;
    }

    private static final class PullParallaxLayout extends FrameLayout {
        private static final float DESIGN_WIDTH = 862f;
        private static final float DESIGN_HEIGHT = 1825f;
        private final Map<View, RectF> designBounds = new LinkedHashMap<>();
        private final Map<View, Float> parallaxFactors = new LinkedHashMap<>();
        private final int touchSlop;
        private float downX;
        private float downY;
        private float pullOffset;
        private float springVelocity;
        private long previousFrameNanos;
        private boolean dragging;
        private VelocityTracker velocityTracker;
        private final Choreographer.FrameCallback springFrame = this::runSpringFrame;

        PullParallaxLayout(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setClipChildren(false);
        }

        void addDesignChild(View child, float left, float top, float right, float bottom,
                            float parallaxFactor) {
            designBounds.put(child, new RectF(left, top, right, bottom));
            parallaxFactors.put(child, parallaxFactor);
            addView(child);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopSpring();
                    downX = event.getX();
                    downY = event.getY();
                    dragging = false;
                    obtainVelocityTracker(event);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    obtainVelocityTracker(event);
                    float dx = Math.abs(event.getX() - downX);
                    float dy = event.getY() - downY;
                    if (dy > touchSlop && dy > dx) {
                        dragging = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    recycleVelocityTracker();
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            obtainVelocityTracker(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopSpring();
                    downX = event.getX();
                    downY = event.getY();
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float distance = Math.max(0f, event.getY() - downY);
                    float maximum = getHeight() * 0.23f;
                    setPullOffset(maximum * (1f - (float) Math.exp(-distance / maximum)));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float releaseVelocity = 0f;
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000);
                        releaseVelocity = velocityTracker.getYVelocity();
                    }
                    recycleVelocityTracker();
                    dragging = false;
                    startSpring(Math.max(-2500f, Math.min(2500f, releaseVelocity * 0.32f)));
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void obtainVelocityTracker(MotionEvent event) {
            if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
            velocityTracker.addMovement(event);
        }

        private void recycleVelocityTracker() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        private void setPullOffset(float offset) {
            pullOffset = offset;
            for (Map.Entry<View, Float> entry : parallaxFactors.entrySet()) {
                float translation = offset * entry.getValue();
                if (Math.abs(entry.getKey().getTranslationY() - translation) > 0.08f) {
                    entry.getKey().setTranslationY(translation);
                }
            }
        }

        void playEntrance() {
            PathInterpolator easing = new PathInterpolator(0.16f, 0.82f, 0.22f, 1f);
            int animatedIndex = 0;
            for (int index = 2; index < getChildCount(); index++) {
                View child = getChildAt(index);
                child.animate().cancel();
                child.setAlpha(0f);
                child.setScaleX(0.985f);
                child.setScaleY(0.985f);
                child.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setStartDelay(Math.min(150L, animatedIndex * 18L))
                        .setDuration(260L)
                        .setInterpolator(easing)
                        .start();
                animatedIndex++;
            }
        }

        private void startSpring(float velocity) {
            springVelocity = velocity;
            previousFrameNanos = 0L;
            Choreographer.getInstance().removeFrameCallback(springFrame);
            Choreographer.getInstance().postFrameCallback(springFrame);
        }

        private void stopSpring() {
            Choreographer.getInstance().removeFrameCallback(springFrame);
            previousFrameNanos = 0L;
            springVelocity = 0f;
        }

        private void runSpringFrame(long frameTimeNanos) {
            if (dragging) return;
            if (previousFrameNanos == 0L) {
                previousFrameNanos = frameTimeNanos;
                Choreographer.getInstance().postFrameCallback(springFrame);
                return;
            }
            float dt = Math.min(0.040f,
                    (frameTimeNanos - previousFrameNanos) / 1_000_000_000f);
            previousFrameNanos = frameTimeNanos;
            float position = pullOffset;
            float velocity = springVelocity;
            float remaining = dt;
            while (remaining > 0f) {
                float step = Math.min(remaining, 1f / 120f);
                float acceleration = -190f * position - 24f * velocity;
                velocity += acceleration * step;
                position += velocity * step;
                remaining -= step;
            }
            springVelocity = velocity;
            setPullOffset(position);
            if (Math.abs(pullOffset) < 0.45f && Math.abs(springVelocity) < 5f) {
                setPullOffset(0f);
                stopSpring();
            } else {
                Choreographer.getInstance().postFrameCallback(springFrame);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stopSpring();
            recycleVelocityTracker();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(width, height);
            for (Map.Entry<View, RectF> entry : designBounds.entrySet()) {
                RectF bounds = entry.getValue();
                int childWidth = Math.max(1, Math.round(bounds.width() / DESIGN_WIDTH * width));
                int childHeight = Math.max(1, Math.round(bounds.height() / DESIGN_HEIGHT * height));
                entry.getKey().measure(
                        MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int height = bottom - top;
            for (Map.Entry<View, RectF> entry : designBounds.entrySet()) {
                RectF bounds = entry.getValue();
                int childLeft = Math.round(bounds.left / DESIGN_WIDTH * width);
                int childTop = Math.round(bounds.top / DESIGN_HEIGHT * height);
                int childRight = Math.round(bounds.right / DESIGN_WIDTH * width);
                int childBottom = Math.round(bounds.bottom / DESIGN_HEIGHT * height);
                entry.getKey().layout(childLeft, childTop, childRight, childBottom);
            }
        }
    }

    private static final class SkyView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SkyView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setShader(new LinearGradient(0f, 0f, 0f, getHeight(),
                    Color.rgb(173, 214, 243), Color.rgb(204, 231, 248),
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
            paint.setShader(null);
        }
    }

    private static final class CloudsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path cloudPath = new Path();

        CloudsView(Context context) {
            super(context);
            setLayerType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float sx = getWidth() / 862f;
            float sy = getHeight() / 2050f;
            canvas.save();
            canvas.scale(sx, sy);
            drawCloud(canvas, -64, -18, 360, 190, 0xB8F4FAFF);
            drawCloud(canvas, 698, 130, 275, 158, 0xC4F6FBFF);
            drawCloud(canvas, -310, 360, 820, 500, 0xC854A2E5);
            drawCloud(canvas, 610, 380, 545, 305, 0xB85CA8E7);
            canvas.restore();
        }

        private void drawCloud(Canvas canvas, float x, float y, float width, float height, int color) {
            cloudPath.reset();
            cloudPath.moveTo(x, y + height * 0.70f);
            cloudPath.cubicTo(x + width * 0.01f, y + height * 0.51f,
                    x + width * 0.11f, y + height * 0.43f,
                    x + width * 0.22f, y + height * 0.46f);
            cloudPath.cubicTo(x + width * 0.24f, y + height * 0.20f,
                    x + width * 0.39f, y + height * 0.08f,
                    x + width * 0.51f, y + height * 0.34f);
            cloudPath.cubicTo(x + width * 0.62f, y + height * 0.16f,
                    x + width * 0.79f, y + height * 0.23f,
                    x + width * 0.82f, y + height * 0.49f);
            cloudPath.cubicTo(x + width * 0.94f, y + height * 0.45f,
                    x + width, y + height * 0.56f,
                    x + width, y + height * 0.72f);
            cloudPath.cubicTo(x + width, y + height * 0.91f,
                    x + width * 0.91f, y + height,
                    x + width * 0.78f, y + height);
            cloudPath.lineTo(x + width * 0.20f, y + height);
            cloudPath.cubicTo(x + width * 0.07f, y + height,
                    x, y + height * 0.90f, x, y + height * 0.70f);
            cloudPath.close();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            paint.setShadowLayer(18f, 0f, 12f, 0x260E4D7B);
            canvas.drawPath(cloudPath, paint);
            paint.clearShadowLayer();
        }
    }

    private static final class TitleDecorationView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        TitleDecorationView(Context context) {
            super(context);
            setLayerType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(getWidth() / 862f, getHeight() / 1825f);
            path.reset();
            path.moveTo(177, 160);
            path.cubicTo(245, 130, 315, 152, 382, 139);
            path.cubicTo(459, 124, 523, 152, 633, 145);
            path.cubicTo(675, 185, 684, 244, 690, 307);
            path.cubicTo(665, 373, 574, 402, 498, 389);
            path.cubicTo(420, 410, 346, 390, 276, 402);
            path.cubicTo(217, 387, 166, 347, 160, 292);
            path.cubicTo(151, 238, 156, 191, 177, 160);
            path.close();
            paint.setColor(0xFFF3F6F7);
            paint.setStyle(Paint.Style.FILL);
            paint.setShadowLayer(18f, 0f, 13f, 0x3A1B4B68);
            canvas.drawPath(path, paint);
            paint.clearShadowLayer();

            drawStar(canvas, 198f, 190f, 43f, 0xFFFFE388);

            canvas.save();
            canvas.rotate(32f, 665f, 347f);
            paint.setColor(0xFFDCA965);
            canvas.drawRoundRect(625f, 305f, 705f, 390f, 38f, 38f, paint);
            paint.setColor(0xFFF0C982);
            paint.setStrokeWidth(7f);
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 4; i++) {
                float stripeX = 642f + i * 14f;
                canvas.drawLine(stripeX, 314f, stripeX - 8f, 344f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
            canvas.restore();
        }
    }

    private static final class GrassView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        GrassView(Context context) {
            super(context);
            setLayerType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.scale(getWidth() / 862f, getHeight() / 2050f);
            paint.setStyle(Paint.Style.FILL);
            paint.setShadowLayer(12f, 0f, 7f, 0x2B1C5C35);

            paint.setColor(0xFF67B978);
            drawGround(canvas, -24f, 302f, 1775f);
            drawGround(canvas, 570f, 886f, 1790f);
            for (int i = 0; i < 8; i++) {
                float x = -4f + i * 43f;
                drawLeaf(canvas, x, 2070f,
                        x + (i % 2 == 0 ? 34f : -24f), 1615f + (i % 3) * 54f, 18f);
            }
            for (int i = 0; i < 7; i++) {
                float x = 585f + i * 48f;
                drawLeaf(canvas, x, 2070f,
                        x + (i % 2 == 0 ? -36f : 25f), 1635f + (i % 3) * 49f, 18f);
            }

            paint.setColor(0xFF40965C);
            for (int i = 0; i < 9; i++) {
                float x = 10f + i * 31f;
                drawLeaf(canvas, x, 2070f,
                        x + (i % 2 == 0 ? 26f : -18f), 1640f + (i % 3) * 46f, 13f);
            }
            for (int i = 0; i < 8; i++) {
                float x = 610f + i * 34f;
                drawLeaf(canvas, x, 2070f,
                        x + (i % 2 == 0 ? -25f : 18f), 1660f + (i % 3) * 40f, 13f);
            }
            paint.clearShadowLayer();
            canvas.restore();
        }

        private void drawGround(Canvas canvas, float left, float right, float top) {
            path.reset();
            path.moveTo(left, 2070f);
            path.lineTo(left, top + 30f);
            path.cubicTo(left + (right - left) * 0.24f, top - 12f,
                    left + (right - left) * 0.70f, top - 5f, right, top + 28f);
            path.lineTo(right, 2070f);
            path.close();
            canvas.drawPath(path, paint);
        }

        private void drawLeaf(Canvas canvas, float bottomX, float bottomY,
                              float tipX, float tipY, float halfWidth) {
            path.reset();
            path.moveTo(bottomX - halfWidth, bottomY);
            path.cubicTo(bottomX - halfWidth * 0.9f, bottomY - 120f,
                    tipX - halfWidth * 1.35f, tipY + 62f,
                    tipX - 4f, tipY + 8f);
            path.quadTo(tipX, tipY - 3f, tipX + 5f, tipY + 8f);
            path.cubicTo(tipX + halfWidth * 1.25f, tipY + 66f,
                    bottomX + halfWidth, bottomY - 115f,
                    bottomX + halfWidth, bottomY);
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    private static final class VectorButton extends FrameLayout {
        static final int STYLE_MENU = 0;
        static final int STYLE_LAUNCH = 1;
        static final int STYLE_GUARD = 2;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final TextView label;
        private final int style;

        VectorButton(Context context, int textResource, int style) {
            super(context);
            this.style = style;
            setWillNotDraw(false);
            setLayerType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
            setClickable(true);
            setFocusable(true);
            AppVisualStyle.applyPressMotion(this);
            label = new TextView(context);
            label.setText(textResource);
            label.setTextColor(style == STYLE_LAUNCH ? Color.WHITE : Color.rgb(88, 93, 98));
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            label.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            label.setAutoSizeTextTypeUniformWithConfiguration(
                    style == STYLE_LAUNCH ? 22 : 13,
                    style == STYLE_LAUNCH ? 40 : 22,
                    1, TypedValue.COMPLEX_UNIT_SP);
            if (style == STYLE_GUARD) label.setPadding(0, 0, dp(context, 92), 0);
            addView(label, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float inset = Math.max(5f, getHeight() * 0.065f);
            float shadowRadius = getHeight() * 0.075f;
            float shadowOffset = getHeight() * 0.045f;
            float shadowSpace = shadowRadius + shadowOffset + Math.max(2f, getHeight() * 0.02f);
            rect.set(inset, inset, getWidth() - inset, getHeight() - shadowSpace);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(style == STYLE_LAUNCH
                    ? (isPressed() ? 0xFF286BB8 : 0xFF347ED6)
                    : (isPressed() ? 0xFFD8E1E8 : 0xFFE9EFF3));
            paint.setShadowLayer(shadowRadius, 0f, shadowOffset, 0x3D173E59);
            canvas.drawRoundRect(rect, rect.height() * 0.34f, rect.height() * 0.34f, paint);
            paint.clearShadowLayer();

            if (style == STYLE_LAUNCH || style == STYLE_GUARD) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(3f, getHeight() * 0.025f));
                paint.setColor(style == STYLE_LAUNCH ? 0xFFF4F7FA : 0xFF347ED6);
                paint.setPathEffect(new DashPathEffect(
                        new float[]{getHeight() * 0.10f, getHeight() * 0.055f}, 0f));
                float borderInset = inset + getHeight() * 0.07f;
                RectF border = new RectF(borderInset, borderInset,
                        getWidth() - borderInset, rect.bottom - getHeight() * 0.07f);
                canvas.drawRoundRect(border, border.height() * 0.34f, border.height() * 0.34f, paint);
                paint.setPathEffect(null);
                paint.setStyle(Paint.Style.FILL);
            }

            if (style == STYLE_MENU) {
                paint.setColor(0xFF111111);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(5f, getHeight() * 0.055f));
                paint.setStrokeCap(Paint.Cap.SQUARE);
                float x = getWidth() - getHeight() * 0.55f;
                float y = getHeight() * 0.50f;
                canvas.drawLine(x - getHeight() * 0.10f, y - getHeight() * 0.14f, x, y, paint);
                canvas.drawLine(x, y, x - getHeight() * 0.10f, y + getHeight() * 0.14f, paint);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStyle(Paint.Style.FILL);
            } else if (style == STYLE_LAUNCH) {
                drawStar(canvas, getHeight() * 0.22f, getHeight() * 0.77f,
                        getHeight() * 0.20f, 0xFFFFE388);
                drawStar(canvas, getWidth() - getHeight() * 0.17f, getHeight() * 0.24f,
                        getHeight() * 0.15f, 0xFFFFE388);
            }
        }
    }

    private static void drawStar(Canvas canvas, float centerX, float centerY,
                                 float radius, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        Path star = new Path();
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float r = i % 2 == 0 ? radius : radius * 0.48f;
            float x = centerX + (float) Math.cos(angle) * r;
            float y = centerY + (float) Math.sin(angle) * r;
            if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
        }
        star.close();
        canvas.drawPath(star, paint);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class GuardToggleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        private float checkedProgress;
        private ValueAnimator checkedAnimator;

        GuardToggleView(Context context) {
            super(context);
            setContentDescription("操作防遮挡");
            setClickable(true);
            setFocusable(true);
            setLayerType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
            AppVisualStyle.applyPressMotion(this);
        }

        void setChecked(boolean checked) {
            if (this.checked == checked) return;
            this.checked = checked;
            setSelected(checked);
            setContentDescription(checked ? "操作防遮挡，已开启" : "操作防遮挡，已关闭");
            if (checkedAnimator != null) checkedAnimator.cancel();
            checkedAnimator = ValueAnimator.ofFloat(checkedProgress, checked ? 1f : 0f);
            checkedAnimator.setDuration(220L);
            checkedAnimator.setInterpolator(new PathInterpolator(0.2f, 0.75f, 0.2f, 1f));
            checkedAnimator.addUpdateListener(animation -> {
                checkedProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            checkedAnimator.start();
        }

        void setCheckedImmediately(boolean checked) {
            if (checkedAnimator != null) checkedAnimator.cancel();
            checkedAnimator = null;
            this.checked = checked;
            checkedProgress = checked ? 1f : 0f;
            setSelected(checked);
            setContentDescription(checked ? "操作防遮挡，已开启" : "操作防遮挡，已关闭");
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float radius = height * 0.5f;
            paint.setColor(blendColor(
                    Color.rgb(155, 169, 181), Color.rgb(52, 126, 214), checkedProgress));
            paint.setStyle(Paint.Style.FILL);
            paint.setShadowLayer(Math.max(2f, height * 0.10f), 0f, height * 0.07f,
                    0x3A173E59);
            canvas.drawRoundRect(0f, 0f, width, height, radius, radius, paint);
            paint.clearShadowLayer();

            float travelProgress = checkedProgress * checkedProgress * (3f - 2f * checkedProgress);
            float knobRadius = height * 0.44f
                    * (1f - 0.055f * (float) Math.sin(Math.PI * checkedProgress));
            float knobX = radius + (width - radius * 2f) * travelProgress;
            paint.setColor(Color.rgb(235, 241, 246));
            paint.setShadowLayer(Math.max(1f, height * 0.045f), 0f, height * 0.025f,
                    0x3D000000);
            canvas.drawCircle(knobX, radius, knobRadius, paint);
            paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, height * 0.025f));
            paint.setColor(blendColor(
                    Color.rgb(105, 117, 128), Color.rgb(36, 98, 174), checkedProgress));
            canvas.drawCircle(knobX, radius, knobRadius, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private int blendColor(int from, int to, float progress) {
            float value = Math.max(0f, Math.min(1f, progress));
            int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * value);
            int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * value);
            int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * value);
            return Color.rgb(red, green, blue);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (checkedAnimator != null) checkedAnimator.cancel();
            checkedAnimator = null;
            super.onDetachedFromWindow();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
