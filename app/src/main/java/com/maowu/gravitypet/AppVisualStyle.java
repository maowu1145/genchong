package com.maowu.gravitypet;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

final class AppVisualStyle {
    static final int SKY = Color.rgb(204, 232, 248);
    static final int PANEL = Color.rgb(228, 243, 252);
    static final int CARD = Color.rgb(249, 252, 254);
    static final int BLUE = Color.rgb(48, 145, 225);
    static final int BLUE_DARK = Color.rgb(23, 105, 190);
    static final int TEXT = Color.rgb(57, 70, 82);
    static final int MUTED = Color.rgb(100, 116, 128);
    static final int TRACK = Color.rgb(184, 211, 229);

    private AppVisualStyle() {}

    static GradientDrawable rounded(int color, float radiusDp, float density) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusDp * density);
        return drawable;
    }

    static GradientDrawable bordered(int color, int strokeColor,
                                     float radiusDp, float density) {
        GradientDrawable drawable = rounded(color, radiusDp, density);
        drawable.setStroke(Math.max(1, Math.round(2f * density)), strokeColor);
        return drawable;
    }

    static void stylePrimary(Button button, float density) {
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(BLUE, 16f, density));
        button.setElevation(3f * density);
        applyPressMotion(button);
    }

    static void styleSecondary(Button button, float density) {
        button.setAllCaps(false);
        button.setTextColor(BLUE_DARK);
        button.setTextSize(15f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(bordered(CARD, TRACK, 16f, density));
        button.setElevation(1.5f * density);
        applyPressMotion(button);
    }

    static void styleSeekBar(SeekBar seekBar) {
        float density = seekBar.getResources().getDisplayMetrics().density;
        int trackHeight = Math.round(18f * density);
        int thumbSize = Math.round(26f * density);

        GradientDrawable emptyTrack = rounded(Color.rgb(157, 174, 188), 9f, density);
        emptyTrack.setSize(1, trackHeight);

        GradientDrawable filledTrack = rounded(BLUE, 9f, density);
        filledTrack.setSize(1, trackHeight);
        ClipDrawable clippedProgress = new ClipDrawable(
                filledTrack, Gravity.START, ClipDrawable.HORIZONTAL);

        LayerDrawable progress = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                emptyTrack, clippedProgress
        });
        progress.setId(0, android.R.id.background);
        progress.setId(1, android.R.id.progress);
        progress.setLayerHeight(0, trackHeight);
        progress.setLayerHeight(1, trackHeight);
        progress.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        progress.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        seekBar.setProgressDrawable(progress);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.rgb(237, 243, 247));
        thumb.setStroke(Math.max(1, Math.round(1.5f * density)), BLUE_DARK);
        thumb.setSize(thumbSize, thumbSize);
        seekBar.setThumb(thumb);
        seekBar.setThumbOffset(thumbSize / 2);
        seekBar.setSplitTrack(false);
        int horizontalPadding = thumbSize / 2 + Math.round(2f * density);
        seekBar.setPadding(horizontalPadding, 0, horizontalPadding, 0);
    }

    static void styleInput(EditText input, float density) {
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(15f);
        input.setBackground(bordered(Color.WHITE, TRACK, 15f, density));
        input.setPadding(Math.round(16f * density), Math.round(12f * density),
                Math.round(16f * density), Math.round(12f * density));
    }

    static void styleTitle(TextView title) {
        title.setTextColor(BLUE_DARK);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }

    static void applyPressMotion(View view) {
        StateListAnimator states = new StateListAnimator();
        AnimatorSet pressed = new AnimatorSet();
        pressed.playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.975f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.975f));
        pressed.setDuration(75L);
        pressed.setInterpolator(new PathInterpolator(0.2f, 0f, 0.2f, 1f));

        AnimatorSet released = new AnimatorSet();
        released.playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f));
        released.setDuration(190L);
        released.setInterpolator(new PathInterpolator(0.18f, 0.85f, 0.24f, 1f));
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[0], released);
        view.setStateListAnimator(states);
    }
}
