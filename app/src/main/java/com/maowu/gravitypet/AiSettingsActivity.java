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
import android.view.WindowManager;
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
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildContent());
    }

    private View buildContent() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("‹");
        canvasButton(back, false);
        back.setTextSize(28);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(58), dp(48)));
        TextView title = text("AI配置", 25, true);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        View balance = new View(this);
        header.addView(balance, new LinearLayout.LayoutParams(dp(58), dp(48)));
        root.addView(header, wrapParams());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(22), dp(18), dp(22));
        card.setBackground(AppVisualStyle.bordered(Color.WHITE, 0xFFBBBBBB, 24f,
                getResources().getDisplayMetrics().density));
        card.setElevation(dp(3));
        LinearLayout.LayoutParams cardParams = wrapParams();
        cardParams.topMargin = dp(26);
        root.addView(card, cardParams);

        card.addView(infoRow("免费模型", "暂未接入"), wrapParams());
        card.addView(infoRow("模型厂商", "小米"), wrapParams());

        TextView keyLabel = text("API Key", 17, true);
        LinearLayout.LayoutParams labelParams = wrapParams();
        labelParams.topMargin = dp(18);
        card.addView(keyLabel, labelParams);

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("粘贴小米 MiMo API Key");
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyInput.setTextColor(Color.BLACK);
        apiKeyInput.setHintTextColor(0xFF8B8B8B);
        apiKeyInput.setPadding(dp(16), 0, dp(16), 0);
        apiKeyInput.setBackground(AppVisualStyle.bordered(Color.WHITE, 0xFFBBBBBB, 15f,
                getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        keyParams.topMargin = dp(12);
        card.addView(apiKeyInput, keyParams);

        if (ApiKeyStore.isConfigured(this, "mimo")) {
            apiKeyInput.setHint("已配置；输入新 Key 才会替换");
        }

        card.addView(infoRow("模型选择", "MiMo"), wrapParams());

        Button save = new Button(this);
        save.setText("保存 AI 设置");
        canvasButton(save, true);
        save.setOnClickListener(v -> saveSettings());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        saveParams.topMargin = dp(24);
        card.addView(save, saveParams);

        Button delete = new Button(this);
        delete.setText("删除已保存的 Key");
        canvasButton(delete, false);
        delete.setOnClickListener(v -> {
            ApiKeyStore.delete(this, "mimo");
            apiKeyInput.setText("");
            apiKeyInput.setHint("粘贴小米 MiMo API Key");
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        deleteParams.topMargin = dp(10);
        card.addView(delete, deleteParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout infoRow(String left, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(left, 17, true);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(54), 1f));
        TextView value = text(right, 15, false);
        value.setTextColor(0xFF666666);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(value, new LinearLayout.LayoutParams(dp(120), dp(54)));
        return row;
    }

    private void canvasButton(Button button, boolean primary) {
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : Color.BLACK);
        button.setTextSize(16);
        button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        button.setBackground(AppVisualStyle.bordered(
                primary ? Color.BLACK : Color.WHITE,
                primary ? Color.BLACK : 0xFFBBBBBB, 16f,
                getResources().getDisplayMetrics().density));
    }

    private void saveSettings() {
        String key = apiKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, ApiKeyStore.isConfigured(this, "mimo")
                    ? "Key 未改变" : "请先填写 API Key", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ApiKeyStore.save(this, "mimo", key);
            PetPreferences.get(this).edit()
                    .putString(PetPreferences.KEY_AI_PROVIDER, "mimo")
                    .apply();
            Toast.makeText(this, "已加密保存", Toast.LENGTH_SHORT).show();
            apiKeyInput.setText("");
            apiKeyInput.setHint("已配置；输入新 Key 才会替换");
        } catch (Exception error) {
            Toast.makeText(this, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String content, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.BLACK);
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
