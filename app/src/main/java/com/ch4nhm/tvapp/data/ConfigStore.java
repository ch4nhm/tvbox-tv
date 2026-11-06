package com.ch4nhm.tvapp.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebSettings;

import com.ch4nhm.tvapp.App;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ConfigStore {

    public static final String DEFAULT_URL = "https://qist.wyfc.qzz.io/fty.json";
    private static final String LEGACY_DEFAULT_URL = "https://9280.kstore.vip/aiwex.json";
    private static final String PREFS = "tvbox_config";
    private static final String KEY_URL = "config_url";
    private static final String KEY_JSON = "config_json";
    private static final String KEY_JSON_UPDATED_AT = "config_json_updated_at";
    private static final String KEY_USER_AGENT = "user_agent";
    private static final String KEY_PROFILES = "source_profiles";
    private static final String KEY_ACTIVE_PROFILE = "active_source_profile";
    private static final String KEY_PREFERRED_SITE = "preferred_site_key";
    private static final String KEY_DEFAULT_SOURCE_MIGRATED = "default_source_fty_v1";
    private static final Type PROFILE_LIST_TYPE =
            new TypeToken<ArrayList<SourceProfile>>() { }.getType();

    private final SharedPreferences preferences;
    private final Context context;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public ConfigStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateLegacyDefaultSource();
        ensureProfiles();
    }

    public Context getContext() {
        return context;
    }

    public java.io.File getCacheDir() {
        java.io.File directory = new java.io.File(context.getCacheDir(), "tvbox");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建配置缓存目录");
        return directory;
    }

    public String getUrl() {
        String value = preferences.getString(KEY_URL, DEFAULT_URL);
        return value == null || value.trim().isEmpty() ? DEFAULT_URL : value.trim();
    }

    public String getPreferredSiteKey() {
        return preferences.getString(KEY_PREFERRED_SITE, "");
    }

    public void setPreferredSiteKey(String siteKey) {
        String value = siteKey == null ? "" : siteKey.trim();
        SharedPreferences.Editor editor = preferences.edit();
        if (value.isEmpty()) editor.remove(KEY_PREFERRED_SITE);
        else editor.putString(KEY_PREFERRED_SITE, value);
        editor.apply();
    }

    public void setUrl(String url) {
        String value = url == null ? DEFAULT_URL : url.trim();
        String previous = getUrl();
        SharedPreferences.Editor editor = preferences.edit().putString(KEY_URL, value);
        if (!value.equals(previous)) editor.remove(KEY_PREFERRED_SITE);
        List<SourceProfile> profiles = readProfiles();
        SourceProfile active = findProfile(profiles, getActiveProfileId());
        if (active != null) {
            active.url = value;
            active.name = normalizeName(active.name, value);
            editor.putString(KEY_PROFILES, gson.toJson(profiles, PROFILE_LIST_TYPE));
        }
        editor.commit();
    }

    public void invalidate() {
        preferences.edit()
                .remove(KEY_JSON)
                .remove(KEY_JSON_UPDATED_AT)
                .apply();
    }

    public String getUserAgent() {
        String value = preferences.getString(KEY_USER_AGENT, "");
        return value == null ? "" : value.trim();
    }

    public void setUserAgent(String userAgent) {
        String value = userAgent == null ? "" : userAgent.trim();
        SharedPreferences.Editor editor = preferences.edit().putString(KEY_USER_AGENT, value);
        List<SourceProfile> profiles = readProfiles();
        SourceProfile active = findProfile(profiles, getActiveProfileId());
        if (active != null) {
            active.userAgent = value;
            editor.putString(KEY_PROFILES, gson.toJson(profiles, PROFILE_LIST_TYPE));
        }
        editor.commit();
    }

    public String getEffectiveUserAgent() {
        String configured = getUserAgent();
        return configured.isEmpty() ? WebSettings.getDefaultUserAgent(context) : configured;
    }

    public String getJson() {
        return preferences.getString(KEY_JSON, "");
    }

    public TvBoxConfig getConfig() {
        String json = getJson();
        if (json.isEmpty()) return null;
        try {
            TvBoxConfig config = gson.fromJson(json, TvBoxConfig.class);
            ConfigCompatibility.apply(config);
            return config;
        } catch (Exception ignored) {
            return null;
        }
    }

    public void save(TvBoxConfig config) {
        preferences.edit()
                .putString(KEY_JSON, gson.toJson(config))
                .putLong(KEY_JSON_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public boolean hasFreshConfig(long maxAgeMs) {
        if (getJson().isEmpty()) return false;
        long updatedAt = preferences.getLong(KEY_JSON_UPDATED_AT, 0L);
        if (updatedAt <= 0L) return false;
        return System.currentTimeMillis() - updatedAt <= Math.max(0L, maxAgeMs);
    }

    public synchronized List<SourceProfile> getSources() {
        List<SourceProfile> result = new ArrayList<>();
        for (SourceProfile profile : readProfiles()) result.add(profile.copy());
        return result;
    }

    public synchronized SourceProfile getActiveSource() {
        SourceProfile profile = findProfile(readProfiles(), getActiveProfileId());
        if (profile == null) return null;
        return profile.copy();
    }

    public synchronized SourceProfile addSource(String name, String url, String userAgent) {
        String address = normalizeUrl(url);
        SourceProfile profile = new SourceProfile();
        profile.id = "source-" + UUID.randomUUID();
        profile.name = normalizeName(name, address);
        profile.url = address;
        profile.userAgent = userAgent == null ? "" : userAgent.trim();
        List<SourceProfile> profiles = readProfiles();
        profiles.add(profile);
        preferences.edit()
                .putString(KEY_PROFILES, gson.toJson(profiles, PROFILE_LIST_TYPE))
                .commit();
        return profile.copy();
    }

    public synchronized boolean updateSource(String id, String name, String url,
                                             String userAgent) {
        List<SourceProfile> profiles = readProfiles();
        SourceProfile profile = findProfile(profiles, id);
        if (profile == null) return false;
        String address = normalizeUrl(url);
        boolean activeUrlChanged = id.equals(getActiveProfileId())
                && !profile.url.equals(address);
        profile.name = normalizeName(name, address);
        profile.url = address;
        profile.userAgent = userAgent == null ? "" : userAgent.trim();
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_PROFILES, gson.toJson(profiles, PROFILE_LIST_TYPE));
        if (id.equals(getActiveProfileId())) {
            editor.putString(KEY_URL, profile.url)
                    .putString(KEY_USER_AGENT, profile.userAgent);
            if (activeUrlChanged) {
                editor.remove(KEY_JSON).remove(KEY_PREFERRED_SITE);
            }
        }
        return editor.commit();
    }

    public synchronized boolean removeSource(String id) {
        String activeId = getActiveProfileId();
        if (activeId.equals(id)) return false;
        List<SourceProfile> profiles = readProfiles();
        boolean removed = profiles.removeIf(profile -> profile.id.equals(id));
        if (!removed || profiles.isEmpty()) return false;
        return preferences.edit()
                .putString(KEY_PROFILES, gson.toJson(profiles, PROFILE_LIST_TYPE))
                .commit();
    }

    public synchronized boolean activateSource(String id) {
        List<SourceProfile> profiles = readProfiles();
        SourceProfile profile = findProfile(profiles, id);
        if (profile == null) return false;
        return preferences.edit()
                .putString(KEY_ACTIVE_PROFILE, profile.id)
                .putString(KEY_URL, profile.url)
                .putString(KEY_USER_AGENT, profile.userAgent)
                .remove(KEY_PREFERRED_SITE)
                .remove(KEY_JSON)
                .commit();
    }

    public String getActiveProfileId() {
        String id = preferences.getString(KEY_ACTIVE_PROFILE, "");
        if (id != null && !id.isBlank()) return id;
        List<SourceProfile> profiles = readProfiles();
        return profiles.isEmpty() ? "" : profiles.get(0).id;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                preferences.contains(KEY_URL),
                preferences.getString(KEY_URL, ""),
                preferences.contains(KEY_JSON),
                preferences.getString(KEY_JSON, ""),
                preferences.contains(KEY_USER_AGENT),
                preferences.getString(KEY_USER_AGENT, ""),
                preferences.contains(KEY_PROFILES),
                preferences.getString(KEY_PROFILES, ""),
                preferences.contains(KEY_ACTIVE_PROFILE),
                preferences.getString(KEY_ACTIVE_PROFILE, ""));
    }

    public void restore(Snapshot snapshot) {
        if (snapshot == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(KEY_JSON_UPDATED_AT);
        putOrRemove(editor, KEY_URL, snapshot.hasUrl, snapshot.url);
        putOrRemove(editor, KEY_JSON, snapshot.hasJson, snapshot.json);
        putOrRemove(editor, KEY_USER_AGENT, snapshot.hasUserAgent, snapshot.userAgent);
        putOrRemove(editor, KEY_PROFILES, snapshot.hasProfiles, snapshot.profiles);
        putOrRemove(editor, KEY_ACTIVE_PROFILE, snapshot.hasActiveProfile,
                snapshot.activeProfileId);
        editor.commit();
    }

    private static void putOrRemove(SharedPreferences.Editor editor,
                                    String key, boolean present, String value) {
        if (present) editor.putString(key, value);
        else editor.remove(key);
    }

    private void ensureProfiles() {
        List<SourceProfile> profiles = readProfiles();
        String activeId = preferences.getString(KEY_ACTIVE_PROFILE, "");
        SourceProfile active = findProfile(profiles, activeId);
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        if (profiles.isEmpty()) {
            String url = getUrl();
            SourceProfile profile = new SourceProfile();
            profile.id = "source-" + UUID.randomUUID();
            profile.name = suggestName(url);
            profile.url = url;
            profile.userAgent = getUserAgent();
            profiles.add(profile);
            active = profile;
            activeId = profile.id;
            editor.putString(KEY_PROFILES, gson.toJson(profiles, PROFILE_LIST_TYPE))
                    .putString(KEY_ACTIVE_PROFILE, activeId)
                    .putString(KEY_URL, profile.url)
                    .putString(KEY_USER_AGENT, profile.userAgent);
            changed = true;
        } else if (active == null) {
            active = profiles.get(0);
            activeId = active.id;
            editor.putString(KEY_ACTIVE_PROFILE, activeId);
            changed = true;
        }
        if (!activeId.equals(preferences.getString(KEY_ACTIVE_PROFILE, ""))) {
            editor.putString(KEY_ACTIVE_PROFILE, activeId);
            changed = true;
        }
        String legacyUrl = preferences.getString(KEY_URL, "");
        String legacyUserAgent = preferences.getString(KEY_USER_AGENT, "");
        if (!active.url.equals(legacyUrl)) {
            editor.putString(KEY_URL, active.url);
            changed = true;
        }
        if (!active.userAgent.equals(legacyUserAgent == null ? "" : legacyUserAgent.trim())) {
            editor.putString(KEY_USER_AGENT, active.userAgent);
            changed = true;
        }
        if (changed) editor.commit();
    }

    private void migrateLegacyDefaultSource() {
        if (preferences.getBoolean(KEY_DEFAULT_SOURCE_MIGRATED, false)) return;

        SharedPreferences.Editor editor = preferences.edit();
        String currentUrl = preferences.getString(KEY_URL, "");
        List<SourceProfile> profiles = readProfiles();
        SourceProfile active = findProfile(profiles, getActiveProfileId());
        boolean changed = false;

        if (active != null && LEGACY_DEFAULT_URL.equals(active.url)) {
            active.url = DEFAULT_URL;
            active.name = suggestName(DEFAULT_URL);
            editor.putString(KEY_PROFILES, gson.toJson(profiles, PROFILE_LIST_TYPE))
                    .putString(KEY_URL, active.url)
                    .remove(KEY_JSON);
            changed = true;
        } else if (active == null && (currentUrl == null || currentUrl.isBlank()
                || LEGACY_DEFAULT_URL.equals(currentUrl.trim()))) {
            editor.putString(KEY_URL, DEFAULT_URL);
            changed = true;
        }

        editor.putBoolean(KEY_DEFAULT_SOURCE_MIGRATED, true);
        if (changed) editor.commit();
        else editor.apply();
    }

    private List<SourceProfile> readProfiles() {
        try {
            ArrayList<SourceProfile> profiles =
                    gson.fromJson(preferences.getString(KEY_PROFILES, ""), PROFILE_LIST_TYPE);
            if (profiles == null) return new ArrayList<>();
            profiles.removeIf(profile -> profile == null
                    || profile.id == null || profile.id.isBlank()
                    || profile.url == null || profile.url.isBlank());
            for (SourceProfile profile : profiles) {
                profile.name = normalizeName(profile.name, profile.url);
                profile.url = normalizeUrl(profile.url);
                if (profile.userAgent == null) profile.userAgent = "";
            }
            return profiles;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static SourceProfile findProfile(List<SourceProfile> profiles, String id) {
        if (id == null || id.isBlank()) return null;
        for (SourceProfile profile : profiles) {
            if (id.equals(profile.id)) return profile;
        }
        return null;
    }

    private static String normalizeUrl(String url) {
        String value = url == null ? "" : url.trim();
        return value.isEmpty() ? DEFAULT_URL : value;
    }

    private static String normalizeName(String name, String url) {
        String value = name == null ? "" : name.trim();
        return value.isEmpty() ? suggestName(url) : value;
    }

    private static String suggestName(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host != null && !host.isBlank()) return host.replaceFirst("^www\\.", "");
        } catch (Throwable ignored) {
        }
        return "TVBox 片源";
    }

    public static Gson gson() {
        return Holder.GSON;
    }

    public static final class SourceProfile {
        public String id = "";
        public String name = "";
        public String url = "";
        public String userAgent = "";

        public SourceProfile copy() {
            SourceProfile value = new SourceProfile();
            value.id = id;
            value.name = name;
            value.url = url;
            value.userAgent = userAgent;
            return value;
        }
    }

    public static final class Snapshot {
        final boolean hasUrl;
        final String url;
        final boolean hasJson;
        final String json;
        final boolean hasUserAgent;
        final String userAgent;
        final boolean hasProfiles;
        final String profiles;
        final boolean hasActiveProfile;
        final String activeProfileId;

        Snapshot(boolean hasUrl, String url, boolean hasJson, String json,
                 boolean hasUserAgent, String userAgent, boolean hasProfiles,
                 String profiles, boolean hasActiveProfile, String activeProfileId) {
            this.hasUrl = hasUrl;
            this.url = url;
            this.hasJson = hasJson;
            this.json = json;
            this.hasUserAgent = hasUserAgent;
            this.userAgent = userAgent;
            this.hasProfiles = hasProfiles;
            this.profiles = profiles;
            this.hasActiveProfile = hasActiveProfile;
            this.activeProfileId = activeProfileId;
        }
    }

    private static class Holder {
        static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    }
}
