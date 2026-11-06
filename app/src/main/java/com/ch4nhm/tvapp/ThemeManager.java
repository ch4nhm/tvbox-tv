package com.ch4nhm.tvapp;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

public final class ThemeManager {

    public static final String GOLD = "gold";
    public static final String TEAL = "teal";
    public static final String CORAL = "coral";
    public static final String BLUE = "blue";
    public static final String GREEN = "green";

    public static final Preset[] PRESETS = {
            new Preset(GOLD, "银白", R.style.Theme_TvApp_Gold, R.color.accent_gold),
            new Preset(TEAL, "海蓝", R.style.Theme_TvApp_Teal, R.color.accent_teal),
            new Preset(CORAL, "暖珊瑚", R.style.Theme_TvApp_Coral, R.color.accent_coral),
            new Preset(BLUE, "系统蓝", R.style.Theme_TvApp_Blue, R.color.accent_blue),
            new Preset(GREEN, "薄荷绿", R.style.Theme_TvApp_Green, R.color.accent_green)
    };

    private ThemeManager() {
    }

    public static void apply(Activity activity) {
        String key = new AppSettings(activity).getThemeKey();
        activity.setTheme(styleFor(key));
    }

    public static int styleFor(String key) {
        return switch (normalize(key)) {
            case TEAL -> R.style.Theme_TvApp_Teal;
            case CORAL -> R.style.Theme_TvApp_Coral;
            case BLUE -> R.style.Theme_TvApp_Blue;
            case GREEN -> R.style.Theme_TvApp_Green;
            default -> R.style.Theme_TvApp_Gold;
        };
    }

    public static String normalize(String key) {
        if (key == null) return GOLD;
        return switch (key) {
            case TEAL, CORAL, BLUE, GREEN -> key;
            default -> GOLD;
        };
    }

    public static Preset preset(String key) {
        String normalized = normalize(key);
        for (Preset preset : PRESETS) {
            if (preset.key.equals(normalized)) return preset;
        }
        return PRESETS[0];
    }

    @ColorInt
    public static int color(Context context, @AttrRes int attribute) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) return 0xFFFFFFFF;
        if (value.resourceId != 0) return ContextCompat.getColor(context, value.resourceId);
        return value.data;
    }

    @ColorInt
    public static int accent(Context context) {
        return color(context, R.attr.tvAccent);
    }

    @ColorInt
    public static int accentSoft(Context context) {
        return color(context, R.attr.tvAccentSoft);
    }

    @ColorInt
    public static int onAccent(Context context) {
        return color(context, R.attr.tvAccentOn);
    }

    @ColorInt
    public static int appContextColor(Context context, int colorResource) {
        return ContextCompat.getColor(context, colorResource);
    }

    public static final class Preset {
        public final String key;
        public final String label;
        public final int style;
        public final int swatchColor;

        Preset(String key, String label, int style, int swatchColor) {
            this.key = key;
            this.label = label;
            this.style = style;
            this.swatchColor = swatchColor;
        }
    }
}
