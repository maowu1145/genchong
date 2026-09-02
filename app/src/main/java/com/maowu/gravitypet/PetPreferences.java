package com.maowu.gravitypet;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

public final class PetPreferences {
    public static final String NAME = "pet_preferences";
    public static final String KEY_COLOR = "ball_color";
    public static final String KEY_HUE = "color_hue";
    public static final String KEY_SATURATION = "color_saturation";
    public static final String KEY_BRIGHTNESS = "color_brightness";
    public static final String KEY_KEYBOARD_GUARD = "keyboard_guard";
    public static final String KEY_BALL_DIAMETER_DP = "ball_diameter_dp";
    public static final String KEY_GRAVITY_PERCENT = "gravity_percent";
    public static final String KEY_SKIN = "selected_skin";
    public static final String KEY_OPACITY_PERCENT = "opacity_percent";
    public static final String KEY_AI_PROVIDER = "ai_provider";
    public static final String KEY_MUSIC_DANCE_PERCENT = "music_dance_percent";
    public static final String KEY_CUSTOM_ANCHOR_X = "custom_anchor_x";
    public static final String KEY_CUSTOM_ANCHOR_Y = "custom_anchor_y";
    public static final String KEY_HIDDEN_SKINS = "hidden_skins";

    private PetPreferences() {}

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int getColor(Context context) {
        return get(context).getInt(KEY_COLOR, Color.WHITE);
    }

    public static String getPersona(Context context, int skin) {
        return get(context).getString("pet_persona_" + skin, "").trim();
    }

    public static void setPersona(Context context, int skin, String persona) {
        get(context).edit()
                .putString("pet_persona_" + skin,
                        persona == null ? "" : persona.trim())
                .apply();
    }

    public static String getPetName(Context context, int skin) {
        String customName = get(context).getString("pet_name_" + skin, "").trim();
        return customName.isEmpty() ? getPetName(skin) : customName;
    }

    public static void setPetName(Context context, int skin, String name) {
        String cleaned = name == null ? "" : name.trim();
        SharedPreferences.Editor editor = get(context).edit();
        if (cleaned.isEmpty()) editor.remove("pet_name_" + skin);
        else editor.putString("pet_name_" + skin, cleaned);
        editor.apply();
    }

    public static float getAnchorX(Context context, int skin, float fallback) {
        SharedPreferences preferences = get(context);
        if (preferences.contains("pet_anchor_x_" + skin)) {
            return preferences.getFloat("pet_anchor_x_" + skin, fallback);
        }
        return skin == PetMakerActivity.CUSTOM_SKIN_ID
                ? preferences.getFloat(KEY_CUSTOM_ANCHOR_X, fallback) : fallback;
    }

    public static float getAnchorY(Context context, int skin, float fallback) {
        SharedPreferences preferences = get(context);
        if (preferences.contains("pet_anchor_y_" + skin)) {
            return preferences.getFloat("pet_anchor_y_" + skin, fallback);
        }
        return skin == PetMakerActivity.CUSTOM_SKIN_ID
                ? preferences.getFloat(KEY_CUSTOM_ANCHOR_Y, fallback) : fallback;
    }

    public static void setAnchor(Context context, int skin, float x, float y) {
        get(context).edit()
                .putFloat("pet_anchor_x_" + skin, x)
                .putFloat("pet_anchor_y_" + skin, y)
                .apply();
    }

    public static String getPetName(int skin) {
        switch (skin) {
            case 1: return "\u5999\u8106\u89d2\u732b";
            case 2: return "法法";
            case 3: return "法国";
            case 4: return "巧克力";
            case 5: return "猫叫";
            case 6: return "比干";
            case 7: return "猫污";
            case 8: return "暮暮";
            case 9: return "plolt";
            case 10: return "浅绿";
            case 11: return "胖鼠";
            case 12: return "汉堡娘";
            case 13: return "我的小鼻嘎";
            default: return "小鼻嘎";
        }
    }
}
