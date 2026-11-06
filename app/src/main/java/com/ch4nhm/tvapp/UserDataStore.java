package com.ch4nhm.tvapp;

import android.content.Context;
import android.content.SharedPreferences;

import com.ch4nhm.tvapp.data.ConfigStore;
import com.ch4nhm.tvapp.data.SiteConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class UserDataStore {

    private static final String PREFS = "tvbox_user_data";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 80;
    private static final Type FAVORITE_TYPE =
            new TypeToken<ArrayList<SavedItem>>() { }.getType();
    private static final Type HISTORY_TYPE =
            new TypeToken<ArrayList<HistoryEntry>>() { }.getType();

    private static UserDataStore instance;

    private final SharedPreferences preferences;
    private final Gson gson = ConfigStore.gson();
    private final List<SavedItem> favorites;
    private final List<HistoryEntry> history;

    private UserDataStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        favorites = read(KEY_FAVORITES, FAVORITE_TYPE);
        history = read(KEY_HISTORY, HISTORY_TYPE);
        sortHistory();
    }

    public static synchronized UserDataStore get(Context context) {
        if (instance == null) instance = new UserDataStore(context);
        return instance;
    }

    public synchronized List<SavedItem> favorites() {
        List<SavedItem> result = new ArrayList<>(favorites);
        result.sort((left, right) -> Long.compare(right.savedAt, left.savedAt));
        return result;
    }

    public synchronized List<HistoryEntry> history() {
        return new ArrayList<>(history);
    }

    public synchronized boolean isFavorite(ContentItem item) {
        String key = key(item);
        for (SavedItem saved : favorites) {
            if (key(saved.siteKey, saved.id).equals(key)) return true;
        }
        return false;
    }

    public synchronized boolean toggleFavorite(ContentItem item) {
        if (item == null || item.id == null || item.id.isBlank()) return false;
        String key = key(item);
        for (int index = 0; index < favorites.size(); index++) {
            SavedItem saved = favorites.get(index);
            if (key(saved.siteKey, saved.id).equals(key)) {
                favorites.remove(index);
                persistFavorites();
                return false;
            }
        }
        SavedItem saved = SavedItem.from(item);
        saved.savedAt = System.currentTimeMillis();
        favorites.add(saved);
        persistFavorites();
        return true;
    }

    public synchronized void removeFavorite(SavedItem item) {
        if (item == null) return;
        favorites.removeIf(candidate ->
                candidate.id.equals(item.id) && safe(candidate.siteKey).equals(safe(item.siteKey)));
        persistFavorites();
    }

    public synchronized void clearFavorites() {
        favorites.clear();
        persistFavorites();
    }

    public synchronized void recordHistory(ContentItem item,
                                           String episodeName,
                                           String episodeId,
                                           String flag,
                                           long positionMs,
                                           long durationMs) {
        if (item == null || item.id == null || item.id.isBlank()) return;
        String itemKey = key(item);
        history.removeIf(candidate ->
                key(candidate.siteKey, candidate.itemId).equals(itemKey));
        HistoryEntry entry = HistoryEntry.from(item, episodeName, episodeId, flag,
                Math.max(0, positionMs), Math.max(0, durationMs));
        entry.updatedAt = System.currentTimeMillis();
        history.add(0, entry);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        sortHistory();
        persistHistory();
    }

    public synchronized void removeHistory(HistoryEntry item) {
        if (item == null) return;
        history.removeIf(candidate ->
                candidate.itemId.equals(item.itemId)
                        && safe(candidate.siteKey).equals(safe(item.siteKey)));
        persistHistory();
    }

    public synchronized void clearHistory() {
        history.clear();
        persistHistory();
    }

    public synchronized ContentItem resolve(SavedItem item) {
        if (item == null) return null;
        return new ContentItem(item.id, item.title, item.meta, item.pic, item.remark,
                findSite(item.siteKey));
    }

    public synchronized ContentItem resolve(HistoryEntry item) {
        if (item == null) return null;
        return new ContentItem(item.itemId, item.title, item.meta, item.pic, item.remark,
                findSite(item.siteKey));
    }

    public synchronized long resumePosition(ContentItem item, String episodeId) {
        if (item == null || episodeId == null || episodeId.isBlank()) return 0;
        String itemKey = key(item);
        for (HistoryEntry entry : history) {
            if (!key(entry.siteKey, entry.itemId).equals(itemKey)) continue;
            if (!episodeId.equals(entry.episodeId)) return 0;
            if (entry.positionMs < 5_000L) return 0;
            if (entry.durationMs > 0 && entry.positionMs >= entry.durationMs - 10_000L) return 0;
            return entry.positionMs;
        }
        return 0;
    }

    private void sortHistory() {
        history.sort(Comparator.comparingLong((HistoryEntry item) -> item.updatedAt).reversed());
    }

    private void persistFavorites() {
        preferences.edit().putString(KEY_FAVORITES, gson.toJson(favorites, FAVORITE_TYPE)).apply();
    }

    private void persistHistory() {
        preferences.edit().putString(KEY_HISTORY, gson.toJson(history, HISTORY_TYPE)).apply();
    }

    private <T> List<T> read(String key, Type type) {
        try {
            List<T> value = gson.fromJson(preferences.getString(key, "[]"), type);
            return value == null ? new ArrayList<>() : value;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static SiteConfig findSite(String key) {
        if (key == null || key.isBlank() || TvBoxRepository.get().getConfig() == null) return null;
        for (SiteConfig site : TvBoxRepository.get().getConfig().sites) {
            if (key.equals(site.key)) return site;
        }
        return null;
    }

    private static String key(ContentItem item) {
        return key(item == null ? null : item.site == null ? null : item.site.key,
                item == null ? null : item.id);
    }

    private static String key(String siteKey, String itemId) {
        return safe(siteKey) + "\u0000" + safe(itemId);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class SavedItem {
        public String id = "";
        public String title = "";
        public String meta = "";
        public String pic = "";
        public String remark = "";
        public String siteKey = "";
        public long savedAt;

        static SavedItem from(ContentItem item) {
            SavedItem saved = new SavedItem();
            saved.id = safe(item.id);
            saved.title = safe(item.title);
            saved.meta = safe(item.meta);
            saved.pic = safe(item.pic);
            saved.remark = safe(item.remark);
            saved.siteKey = item.site == null ? "" : safe(item.site.key);
            return saved;
        }
    }

    public static final class HistoryEntry {
        public String itemId = "";
        public String title = "";
        public String meta = "";
        public String pic = "";
        public String remark = "";
        public String siteKey = "";
        public String episodeName = "";
        public String episodeId = "";
        public String flag = "";
        public long positionMs;
        public long durationMs;
        public long updatedAt;

        static HistoryEntry from(ContentItem item, String episodeName, String episodeId,
                                 String flag, long positionMs, long durationMs) {
            HistoryEntry entry = new HistoryEntry();
            entry.itemId = safe(item.id);
            entry.title = safe(item.title);
            entry.meta = safe(item.meta);
            entry.pic = safe(item.pic);
            entry.remark = safe(item.remark);
            entry.siteKey = item.site == null ? "" : safe(item.site.key);
            entry.episodeName = safe(episodeName);
            entry.episodeId = safe(episodeId);
            entry.flag = safe(flag);
            entry.positionMs = positionMs;
            entry.durationMs = durationMs;
            return entry;
        }

        public int progressPercent() {
            if (durationMs <= 0) return 0;
            return (int) Math.max(0, Math.min(100, positionMs * 100 / durationMs));
        }
    }
}
