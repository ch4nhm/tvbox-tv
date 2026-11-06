package com.ch4nhm.tvapp;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {

    private static final String PREFS = "tvbox_app_settings";
    private static final String KEY_THEME = "theme";
    private static final String KEY_AUTO_NEXT = "player_auto_next";
    private static final String KEY_RESUME = "player_resume";
    private static final String KEY_SEEK_STEP = "player_seek_step";
    private static final String KEY_VIDEO_SCALE = "player_video_scale";
    private static final String KEY_DECODER_FALLBACK = "player_decoder_fallback";
    private static final String KEY_LAST_LIVE_SOURCE = "last_live_source";
    private static final String KEY_OVERSCAN_PERCENT = "display_overscan_percent";

    public static final int[] SEEK_OPTIONS = {10, 30, 60};
    /**
     * Stored in tenths of a percent: 25 means 2.5% and 50 means 5%.
     */
    public static final int[] OVERSCAN_OPTIONS_PERMILLE = {0, 25, 50};
    public static final String[] OVERSCAN_OPTION_NAMES = {"0%", "2.5%", "5%"};
    public static final int VIDEO_SCALE_FIT = 0;
    public static final int VIDEO_SCALE_CROP = 1;
    public static final int VIDEO_SCALE_STRETCH = 2;
    public static final String[] VIDEO_SCALE_NAMES = {"适应画面", "裁切铺满", "拉伸铺满"};

    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getThemeKey() {
        return preferences.getString(KEY_THEME, ThemeManager.GOLD);
    }

    public void setThemeKey(String value) {
        preferences.edit().putString(KEY_THEME,
                ThemeManager.normalize(value)).commit();
    }

    public boolean isAutoNextEpisode() {
        return preferences.getBoolean(KEY_AUTO_NEXT, true);
    }

    public void setAutoNextEpisode(boolean value) {
        preferences.edit().putBoolean(KEY_AUTO_NEXT, value).apply();
    }

    public boolean isResumePlayback() {
        return preferences.getBoolean(KEY_RESUME, true);
    }

    public void setResumePlayback(boolean value) {
        preferences.edit().putBoolean(KEY_RESUME, value).apply();
    }

    public int getSeekStepSeconds() {
        int value = preferences.getInt(KEY_SEEK_STEP, 10);
        for (int option : SEEK_OPTIONS) {
            if (option == value) return value;
        }
        return 10;
    }

    public void setSeekStepSeconds(int value) {
        preferences.edit().putInt(KEY_SEEK_STEP, value).apply();
    }

    public int getVideoScale() {
        return Math.max(VIDEO_SCALE_FIT,
                Math.min(VIDEO_SCALE_STRETCH, preferences.getInt(KEY_VIDEO_SCALE, VIDEO_SCALE_FIT)));
    }

    public void setVideoScale(int value) {
        preferences.edit().putInt(KEY_VIDEO_SCALE,
                Math.max(VIDEO_SCALE_FIT, Math.min(VIDEO_SCALE_STRETCH, value))).apply();
    }

    public boolean isDecoderFallback() {
        return preferences.getBoolean(KEY_DECODER_FALLBACK, true);
    }

    public void setDecoderFallback(boolean value) {
        preferences.edit().putBoolean(KEY_DECODER_FALLBACK, value).apply();
    }

    public String getLastLiveSourceUrl() {
        return preferences.getString(KEY_LAST_LIVE_SOURCE, "");
    }

    public void setLastLiveSourceUrl(String value) {
        preferences.edit().putString(KEY_LAST_LIVE_SOURCE,
                value == null ? "" : value.trim()).apply();
    }

    public int getOverscanPermille() {
        int value = preferences.getInt(KEY_OVERSCAN_PERCENT, 0);
        for (int option : OVERSCAN_OPTIONS_PERMILLE) {
            if (option == value) return value;
        }
        return 0;
    }

    public void setOverscanPermille(int value) {
        int normalized = OVERSCAN_OPTIONS_PERMILLE[0];
        for (int option : OVERSCAN_OPTIONS_PERMILLE) {
            if (option == value) {
                normalized = value;
                break;
            }
        }
        preferences.edit().putInt(KEY_OVERSCAN_PERCENT, normalized).apply();
    }
}
