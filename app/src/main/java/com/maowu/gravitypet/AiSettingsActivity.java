package com.maowu.gravitypet;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class AiSettingsActivity extends Activity {
    private EditText apiKeyInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(174, 213, 241));
        window.setNavigationBarColor(Color.rgb(174, 213, 241));
        setContentView(buildContent());
    }

    private View buildContent() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        GradientDrawable sky = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(174, 213, 241), Color.rgb(210, 235, 249)});
        root.setBackground(sky);

        TextView title = text("AI 设置", 27, true);
        title.setTextColor(AppVisualStyle.BLUE_DARK);
        root.addView(title);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(AppVisualStyle.rounded(AppVisualStyle.CARD, 22f,
                getResources().getDisplayMetrics().density));
        card.setElevation(dp(4));
        LinearLayout.LayoutParams cardParams = wrapParams();
        cardParams.topMargin = dp(18);
        root.addView(card, cardParams);

        TextView keyLabel = text("小米 MiMo API Key", 16, true);
        card.addView(keyLabel, wrapParams());

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("粘贴小米 MiMo API Key");
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AppVisualStyle.styleInput(apiKeyInput, getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        keyParams.topMargin = dp(12);
        card.addView(apiKeyInput, keyParams);

        try {
            apiKeyInput.setText(ApiKeyStore.load(this, "mimo"));
        } catch (Exception ignored) {}

        Button save = new Button(this);
        save.setText("保存 AI 设置");
        AppVisualStyle.stylePrimary(save, getResources().getDisplayMetrics().density);
        save.setOnClickListener(v -> saveSettings());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        saveParams.topMargin = dp(24);
        card.addView(save, saveParams);

        Button back = new Button(this);
        back.setText("返回");
        AppVisualStyle.styleSecondary(back, getResources().getDisplayMetrics().density);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        backParams.topMargin = dp(10);
        card.addView(back, backParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private void saveSettings() {
        String key = apiKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ApiKeyStore.save(this, "mimo", key);
            PetPreferences.get(this).edit()
                    .putString(PetPreferences.KEY_AI_PROVIDER, "mimo")
                    .apply();
            Toast.makeText(this, "已加密保存", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String content, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(20, 33, 61));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
