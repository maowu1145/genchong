package com.maowu.gravitypet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;

/** Native reconstruction of the user supplied Canvas 2 UI prototype. */
public final class Canvas2HomeView extends ScrollView {
    public interface Listener {
        void onIsland();
        void onPetSelection();
        void onImport();
        void onDraw();
        void onSummon();
        void onRecall();
        void onAiSettings();
        void onMusicChanged(boolean enabled);
        void onGuardChanged(boolean enabled);
        void onSizeChanged(int diameterDp);
        void onGravityChanged(int percent);
    }

    private static final int INK = 0xFF111111;
    private static final int BORDER = 0xFFBBBBBB;
    private static final int BLUE = 0xFF1778F2;
    private static final int GREEN = 0xFF68DE80;
    private final float density;
    private final Listener listener;
    private final TextView petName;
    private final ImageView petPreview;
    private final View defaultPreview;
    private final TextView experimentalChevron;
    private final LinearLayout experimentalBody;
    private final Switch musicSwitch;
    private final Switch guardSwitch;
    private final SeekBar sizeBar;
    private final SeekBar gravityBar;
    private final TextView sizeValue;
    private final TextView gravityValue;
    private final View summonButton;
    private final View recallButton;
    private boolean binding;

    public Canvas2HomeView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(9), dp(20), dp(9), dp(28));
        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        FrameLayout island = new FrameLayout(context);
        island.setBackground(card(22, BORDER));
        island.setElevation(dp(4));
        island.setContentDescription("选择小鼻嘎并登岛");
        island.setOnClickListener(v -> listener.onIsland());
        LinearLayout.LayoutParams islandParams = wide(dp(160));
        root.addView(island, islandParams);

        defaultPreview = new BlobPreview(context);
        defaultPreview.setContentDescription("选择小鼻嘎");
        defaultPreview.setOnClickListener(v -> listener.onPetSelection());
        FrameLayout.LayoutParams blobParams = new FrameLayout.LayoutParams(dp(148), dp(102));
        blobParams.gravity = Gravity.BOTTOM | Gravity.START;
        blobParams.leftMargin = dp(15);
        blobParams.bottomMargin = dp(9);
        island.addView(defaultPreview, blobParams);

        petPreview = new ImageView(context);
        petPreview.setContentDescription("选择小鼻嘎");
        petPreview.setOnClickListener(v -> listener.onPetSelection());
        petPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        petPreview.setVisibility(GONE);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(dp(142), dp(105));
        previewParams.gravity = Gravity.BOTTOM | Gravity.START;
        previewParams.leftMargin = dp(16);
        previewParams.bottomMargin = dp(8);
        island.addView(petPreview, previewParams);

        petName = label("登岛", 31, true);
        petName.setGravity(Gravity.END | Gravity.TOP);
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(dp(175), dp(74));
        nameParams.gravity = Gravity.TOP | Gravity.END;
        nameParams.topMargin = dp(18);
        nameParams.rightMargin = dp(21);
        island.addView(petName, nameParams);

        LinearLayout makerRow = new LinearLayout(context);
        makerRow.setOrientation(LinearLayout.HORIZONTAL);
        makerRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams makerParams = wide(dp(62));
        makerParams.topMargin = dp(20);
        root.addView(makerRow, makerParams);
        View importButton = action("⇩  导入", listener::onImport);
        View drawButton = action("◉  绘制", listener::onDraw);
        makerRow.addView(importButton, half(0));
        makerRow.addView(drawButton, half(dp(12)));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(17), dp(17), dp(17), dp(17));
        actions.setBackground(card(22, BORDER));
        actions.setElevation(dp(3));
        LinearLayout.LayoutParams actionsParams = wide(dp(154));
        actionsParams.topMargin = dp(20);
        root.addView(actions, actionsParams);
        summonButton = largeAction("召唤", listener::onSummon);
        recallButton = largeAction("回收", listener::onRecall);
        actions.addView(summonButton, new LinearLayout.LayoutParams(-1, dp(54)));
        LinearLayout.LayoutParams recallParams = new LinearLayout.LayoutParams(-1, dp(54));
        recallParams.topMargin = dp(12);
        actions.addView(recallButton, recallParams);

        LinearLayout experimental = new LinearLayout(context);
        experimental.setOrientation(LinearLayout.VERTICAL);
        experimental.setBackground(card(20, BORDER));
        experimental.setElevation(dp(3));
        LinearLayout.LayoutParams experimentalParams = wide(-2);
        experimentalParams.topMargin = dp(20);
        root.addView(experimental, experimentalParams);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), 0, dp(16), 0);
        TextView headerText = label("实验性功能", 21, true);
        header.addView(headerText, new LinearLayout.LayoutParams(0, dp(62), 1f));
        experimentalChevron = label("⌄", 28, false);
        experimentalChevron.setGravity(Gravity.CENTER);
        header.addView(experimentalChevron, new LinearLayout.LayoutParams(dp(38), dp(62)));
        experimental.addView(header);

        experimentalBody = new LinearLayout(context);
        experimentalBody.setOrientation(LinearLayout.VERTICAL);
        experimentalBody.setPadding(dp(15), 0, dp(15), dp(15));
        experimentalBody.setVisibility(GONE);
        experimental.addView(experimentalBody, new LinearLayout.LayoutParams(-1, -2));
        header.setOnClickListener(v -> toggleExperimental());

        navigationRow("◇", "AI配置", listener::onAiSettings);
        musicSwitch = switchRow("♫", "音乐律动");
        guardSwitch = switchRow("▣", "防遮挡");
        sizeValue = valueLabel();
        sizeBar = sliderRow("↗", "小鼻嘎大小", sizeValue, 80);
        gravityValue = valueLabel();
        gravityBar = sliderRow("◴", "重力大小", gravityValue, 275);

        musicSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) listener.onMusicChanged(checked);
        });
        guardSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!binding) listener.onGuardChanged(checked);
        });
        sizeBar.setOnSeekBarChangeListener(seekListener(value -> {
            int diameter = value + 24;
            sizeValue.setText(diameter + " dp");
            if (!binding) listener.onSizeChanged(diameter);
        }));
        gravityBar.setOnSeekBarChangeListener(seekListener(value -> {
            int percent = value + 25;
            gravityValue.setText(percent + "%");
            if (!binding) listener.onGravityChanged(percent);
        }));

        Space bottom = new Space(context);
        root.addView(bottom, new LinearLayout.LayoutParams(1, dp(18)));
    }

    public View getSummonButton() { return summonButton; }
    public View getRecallButton() { return recallButton; }

    public void bind(boolean music, boolean guard, int sizeDp, int gravityPercent) {
        binding = true;
        musicSwitch.setChecked(music);
        guardSwitch.setChecked(guard);
        sizeBar.setProgress(Math.max(0, Math.min(80, sizeDp - 24)));
        gravityBar.setProgress(Math.max(0, Math.min(275, gravityPercent - 25)));
        sizeValue.setText(sizeDp + " dp");
        gravityValue.setText(gravityPercent + "%");
        binding = false;
    }

    public void bindSelectedPet(int skin) {
        petName.setText("登岛");
        Bitmap bitmap = null;
        File edited = PetMakerActivity.skinFile(getContext(), skin);
        if (edited != null && edited.isFile()) bitmap = BitmapFactory.decodeFile(edited.getAbsolutePath());
        if (bitmap == null && skin > 0) {
            int resource = PetMakerActivity.builtInSkinResource(skin);
            if (resource != 0) bitmap = BitmapFactory.decodeResource(getResources(), resource);
        }
        if (bitmap == null) {
            petPreview.setImageDrawable(null);
            petPreview.setVisibility(GONE);
            defaultPreview.setVisibility(VISIBLE);
        } else {
            petPreview.setImageBitmap(bitmap);
            petPreview.setVisibility(VISIBLE);
            defaultPreview.setVisibility(GONE);
        }
    }

    private void toggleExperimental() {
        boolean opening = experimentalBody.getVisibility() != VISIBLE;
        experimentalBody.setVisibility(opening ? VISIBLE : GONE);
        experimentalChevron.animate().rotation(opening ? 180f : 0f).setDuration(180).start();
    }

    private View navigationRow(String icon, String text, Runnable click) {
        LinearLayout row = baseRow();
        TextView iconView = label(icon, 23, false);
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView, new LinearLayout.LayoutParams(dp(34), dp(56)));
        TextView textView = label(text, 18, true);
        row.addView(textView, new LinearLayout.LayoutParams(0, dp(56), 1f));
        TextView chevron = label("›", 29, false);
        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(34), dp(56)));
        row.setOnClickListener(v -> click.run());
        experimentalBody.addView(row);
        return row;
    }

    private Switch switchRow(String icon, String text) {
        LinearLayout row = baseRow();
        TextView iconView = label(icon, 22, false);
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView, new LinearLayout.LayoutParams(dp(34), dp(56)));
        TextView textView = label(text, 18, true);
        row.addView(textView, new LinearLayout.LayoutParams(0, dp(56), 1f));
        Switch toggle = new Switch(getContext());
        toggle.setShowText(false);
        toggle.setButtonTintList(null);
        toggle.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.WHITE, 0xFFF3F3F3}));
        toggle.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{GREEN, 0xFFC7C7C7}));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(56)));
        experimentalBody.addView(row);
        return toggle;
    }

    private SeekBar sliderRow(String icon, String text, TextView value, int max) {
        LinearLayout shell = new LinearLayout(getContext());
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(8), dp(2), dp(8), dp(7));
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView iconView = label(icon, 21, false);
        iconView.setGravity(Gravity.CENTER);
        top.addView(iconView, new LinearLayout.LayoutParams(dp(34), dp(38)));
        TextView textView = label(text, 18, true);
        top.addView(textView, new LinearLayout.LayoutParams(0, dp(38), 1f));
        top.addView(value, new LinearLayout.LayoutParams(dp(62), dp(38)));
        shell.addView(top);
        SeekBar bar = new SeekBar(getContext());
        bar.setMax(max);
        bar.setProgressTintList(ColorStateList.valueOf(BLUE));
        bar.setThumbTintList(ColorStateList.valueOf(BLUE));
        shell.addView(bar, new LinearLayout.LayoutParams(-1, dp(34)));
        experimentalBody.addView(shell);
        return bar;
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(5), 0);
        return row;
    }

    private SeekBar.OnSeekBarChangeListener seekListener(IntConsumer consumer) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                consumer.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
    }

    private TextView valueLabel() {
        TextView view = label("", 13, false);
        view.setTextColor(0xFF666666);
        view.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        return view;
    }

    private View action(String text, Runnable click) {
        TextView view = label(text, 19, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(card(18, BORDER));
        view.setElevation(dp(3));
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private View largeAction(String text, Runnable click) {
        TextView view = label(text, 24, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(card(17, BORDER));
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(INK);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    private GradientDrawable card(int radiusDp, int borderColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), borderColor);
        return drawable;
    }

    private LinearLayout.LayoutParams wide(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(350), height);
        return params;
    }

    private LinearLayout.LayoutParams half(int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        params.leftMargin = leftMargin;
        return params;
    }

    private int dp(int value) { return Math.round(value * density); }

    private interface IntConsumer { void accept(int value); }

    private static final class BlobPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BlobPreview(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            Path body = new Path();
            body.moveTo(w * .08f, h * .72f);
            body.cubicTo(w * .02f, h * .37f, w * .18f, h * .12f, w * .43f, h * .2f);
            body.cubicTo(w * .62f, h * .02f, w * .91f, h * .18f, w * .88f, h * .52f);
            body.cubicTo(w * .96f, h * .73f, w * .72f, h * .9f, w * .48f, h * .84f);
            body.cubicTo(w * .31f, h * .94f, w * .13f, h * .88f, w * .08f, h * .72f);
            paint.setColor(0xFFF7F7F7);
            canvas.drawPath(body, paint);
            paint.setColor(INK);
            canvas.drawCircle(w * .38f, h * .54f, dpStatic(getContext(), 4), paint);
            canvas.drawCircle(w * .59f, h * .52f, dpStatic(getContext(), 4), paint);
        }
        private static float dpStatic(Context context, int value) {
            return value * context.getResources().getDisplayMetrics().density;
        }
    }
}
