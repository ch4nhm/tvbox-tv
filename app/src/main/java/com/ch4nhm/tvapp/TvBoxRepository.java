package com.ch4nhm.tvapp;

import android.content.Context;
import android.text.Html;
import android.util.Log;

import com.ch4nhm.tvapp.data.ConfigLoader;
import com.ch4nhm.tvapp.data.ConfigStore;
import com.ch4nhm.tvapp.data.HomeCache;
import com.ch4nhm.tvapp.data.SiteConfig;
import com.ch4nhm.tvapp.data.TvBoxConfig;
import com.ch4nhm.tvapp.spider.SiteArgs;
import com.ch4nhm.tvapp.spider.SpiderRuntime;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class TvBoxRepository {

    private static final String TAG = "TVBoxRepository";
    private static final TvBoxRepository INSTANCE = new TvBoxRepository();
    private static final int MAX_HOME_CANDIDATES = 6;
    private static final int MAX_HOME_PREVIEW_CATEGORIES = 3;
    private static final int MAX_SEARCH_ITEMS_PER_SITE = 200;
    private static final long SEARCH_TIMEOUT_SECONDS = 30;
    private static final long SEARCH_SITE_TIMEOUT_SECONDS = 18;
    private static final long DETAIL_RETRY_DELAY_MS = 350L;
    private static final long DETAIL_PREFETCH_DELAY_MS = 260L;
    private static final long PLAYBACK_PREFETCH_DELAY_MS = 160L;
    private static final long PLAYBACK_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(2);
    private static final ScheduledExecutorService REQUEST_TIMER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tvbox-request-timer");
                thread.setDaemon(true);
                return thread;
            });

    private final Map<String, Detail> details = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<Detail>> pendingDetails =
            new ConcurrentHashMap<>();
    private final Map<String, CachedPlayback> playbackCache = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<Playback>> pendingPlaybacks =
            new ConcurrentHashMap<>();
    private final AtomicInteger detailPrefetchGeneration = new AtomicInteger();
    private final AtomicInteger playbackPrefetchGeneration = new AtomicInteger();
    private final Map<String, List<FilterGroup>> categoryFilters = new ConcurrentHashMap<>();
    private TvBoxConfig config;
    private SiteConfig homeSite;
    private Home lastHome;
    private Context context;
    private final AtomicBoolean loading = new AtomicBoolean();

    private TvBoxRepository() {
    }

    public static TvBoxRepository get() {
        return INSTANCE;
    }

    public TvBoxConfig getConfig() {
        return config;
    }

    public SiteConfig getHomeSite() {
        return homeSite;
    }

    public List<ContentItem> homeItems() {
        return lastHome == null ? List.of() : new ArrayList<>(lastHome.items);
    }

    public List<FilterGroup> filtersFor(String tid) {
        if (tid == null || tid.isBlank()) return List.of();
        List<FilterGroup> dynamic = categoryFilters.get(tid);
        if (dynamic != null && !dynamic.isEmpty()) return dynamic;
        if (lastHome != null) {
            List<FilterGroup> configured = lastHome.filters.get(tid);
            if (configured != null && !configured.isEmpty()) return configured;
        }
        return List.of();
    }

    public synchronized RuntimeSnapshot snapshotRuntime() {
        return new RuntimeSnapshot(config, homeSite, lastHome, new HashMap<>(details));
    }

    public void restoreRuntime(RuntimeSnapshot snapshot) {
        if (snapshot == null) return;
        synchronized (this) {
            config = snapshot.config;
            homeSite = snapshot.homeSite;
            lastHome = snapshot.home;
            details.clear();
            details.putAll(snapshot.details);
            pendingDetails.clear();
            detailPrefetchGeneration.incrementAndGet();
            playbackCache.clear();
            pendingPlaybacks.clear();
            playbackPrefetchGeneration.incrementAndGet();
            categoryFilters.clear();
            loading.set(false);
        }
        SpiderRuntime.get().reset();
    }

    public void reload(Context context, LoadCallback callback) {
        synchronized (this) {
            config = null;
            homeSite = null;
            lastHome = null;
            details.clear();
            pendingDetails.clear();
            detailPrefetchGeneration.incrementAndGet();
            playbackCache.clear();
            pendingPlaybacks.clear();
            playbackPrefetchGeneration.incrementAndGet();
            categoryFilters.clear();
            loading.set(false);
        }
        SpiderRuntime.get().reset();
        loadInternal(context, true, callback);
    }

    public void load(Context context, LoadCallback callback) {
        loadInternal(context, false, callback);
    }

    private void loadInternal(Context context, boolean forceRefresh, LoadCallback callback) {
        this.context = context.getApplicationContext();
        if (!forceRefresh && config != null && homeSite != null && lastHome != null) {
            callback.onConfig(config);
            callback.onHome(lastHome);
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            callback.onError(new IllegalStateException("配置正在加载"));
            return;
        }
        ConfigStore store = new ConfigStore(context);
        ConfigLoader.Callback configCallback = new ConfigLoader.Callback() {
            @Override
            public void onLoaded(TvBoxConfig loaded) {
                loading.set(false);
                config = loaded;
                homeSite = chooseHome(loaded, store.getPreferredSiteKey());
                detailPrefetchGeneration.incrementAndGet();
                callback.onConfig(loaded);
                if (homeSite == null) {
                    callback.onError(new IllegalStateException("配置中没有 Java Spider 站点"));
                    return;
                }
                loadHomeWithFallback(callback);
            }

            @Override
            public void onError(Throwable error) {
                loading.set(false);
                callback.onError(error);
            }
        };
        if (forceRefresh) ConfigLoader.refresh(store, configCallback);
        else ConfigLoader.load(store, configCallback);
    }

    private SiteConfig chooseHome(TvBoxConfig loaded, String preferredKey) {
        if (preferredKey != null && !preferredKey.isBlank()) {
            for (SiteConfig site : loaded.sites) {
                if (preferredKey.equals(site.key) && site.isSpider()
                        && !isUtilitySite(site)) {
                    return site;
                }
            }
        }
        for (SiteConfig site : loaded.sites) {
            if (site.isSpider() && site.isSearchable() && !isUtilitySite(site)) return site;
        }
        for (SiteConfig site : loaded.sites) {
            if (site.isSpider() && !isUtilitySite(site)) return site;
        }
        for (SiteConfig site : loaded.sites) if (site.isSpider()) return site;
        return null;
    }

    public synchronized List<SiteConfig> availableSites() {
        if (config == null || config.sites == null) return List.of();
        List<SiteConfig> result = new ArrayList<>();
        for (SiteConfig site : config.sites) {
            if (site != null && site.isSpider() && !isUtilitySite(site)) {
                result.add(site);
            }
        }
        return result;
    }

    public void switchSite(Context context, String siteKey, LoadCallback callback) {
        this.context = context.getApplicationContext();
        SiteConfig target = null;
        for (SiteConfig site : availableSites()) {
            if (site.key.equals(siteKey)) {
                target = site;
                break;
            }
        }
        if (target == null) {
            callback.onError(new IllegalArgumentException("片源站点不存在"));
            return;
        }
        synchronized (this) {
            if (target == homeSite) {
                callback.onConfig(config);
                if (lastHome != null) callback.onHome(lastHome);
                else loadHome(callback);
                return;
            }
        }
        SiteConfig targetSite = target;
        SiteConfig previousSite = homeSite;
        Home previousHome = lastHome;
        homeSite = targetSite;
        loadHome(new LoadCallback() {
            @Override
            public void onHome(Home home) {
                new ConfigStore(TvBoxRepository.this.context)
                        .setPreferredSiteKey(targetSite.key);
                callback.onConfig(config);
                callback.onHome(home);
            }

            @Override
            public void onError(Throwable error) {
                synchronized (TvBoxRepository.this) {
                    homeSite = previousSite;
                    lastHome = previousHome;
                }
                callback.onError(error);
            }
        });
    }

    static boolean isUtilitySite(SiteConfig site) {
        String api = site == null || site.api == null
                ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
        return api.contains("mydriveguard")
                || api.contains("panconfigguard")
                || api.contains("mypanguard")
                || api.contains("configguard")
                || api.contains("wexconfig");
    }

    private void loadHome(LoadCallback callback) {
        Log.i(TAG, "Loading home: " + homeSite.key + " (" + homeSite.api + ")");
        SpiderRuntime.get().call("home", args(homeSite), "{}").whenComplete((json, error) -> {
            if (error != null) {
                Log.w(TAG, "Home failed: " + homeSite.key + ": " + rootMessage(error));
                callback.onError(error);
            }
            else {
                try {
                    Log.i(TAG, "Home payload: " + homeSite.key + " (" + json.length() + " chars)");
                    lastHome = parseHome(json);
                    callback.onHome(lastHome);
                } catch (Throwable parseError) {
                    callback.onError(parseError);
                }
            }
        });
    }

    private void loadHomeWithFallback(LoadCallback callback) {
        List<SiteConfig> candidates = new ArrayList<>();
        candidates.add(homeSite);
        for (SiteConfig site : config.sites) {
            if (site == homeSite || !site.isSpider()) continue;
            candidates.add(site);
            if (candidates.size() >= MAX_HOME_CANDIDATES) break;
        }
        Home cached = cachedHome();
        if (cached == null) {
            tryHome(candidates, 0, callback);
            return;
        }
        lastHome = cached;
        Log.i(TAG, "Showing cached home: " + homeSite.key + " (" + cached.items.size() + " items)");
        callback.onHome(cached);
        tryHome(candidates, 0, new LoadCallback() {
            @Override
            public void onHome(Home home) {
                callback.onHome(home);
            }

            @Override
            public void onError(Throwable error) {
                Log.w(TAG, "Home refresh failed behind cache: " + rootMessage(error));
            }
        });
    }

    private void tryHome(List<SiteConfig> candidates, int index, LoadCallback callback) {
        if (index >= candidates.size()) {
            callback.onError(new IllegalStateException("所有 Java Spider 站点都未能返回首页"));
            return;
        }
        SiteConfig candidate = candidates.get(index);
        Log.i(TAG, "Trying home: " + candidate.key + " (" + candidate.api + ")");
        SpiderRuntime.get().call("home", args(candidate), "{}").whenComplete((json, error) -> {
            if (error != null) {
                Log.w(TAG, "Home candidate failed: " + candidate.key + ": " + rootMessage(error));
                tryHome(candidates, index + 1, callback);
                return;
            }
            try {
                Home home = parseHome(json, candidate);
                if (home.items.isEmpty() && home.types.isEmpty()) {
                    Log.w(TAG, "Home candidate empty: " + candidate.key);
                    tryHome(candidates, index + 1, callback);
                    return;
                }
                if (home.items.isEmpty()) {
                    fillHomePreview(candidate, home, 0, () ->
                            finishHomeCandidate(candidate, home, null, callback));
                } else {
                    finishHomeCandidate(candidate, home, json, callback);
                }
            } catch (Throwable parseError) {
                Log.w(TAG, "Home candidate invalid: " + candidate.key + ": " + rootMessage(parseError));
                tryHome(candidates, index + 1, callback);
            }
        });
    }

    private void fillHomePreview(SiteConfig site, Home home, int categoryIndex, Runnable finished) {
        if (categoryIndex >= Math.min(MAX_HOME_PREVIEW_CATEGORIES, home.types.size())) {
            finished.run();
            return;
        }
        Category category = home.types.get(categoryIndex);
        Log.i(TAG, "Home preview fallback: " + site.key + " category " + category.id);
        JsonObject args = categoryArgs(category.id, "1", false, Map.of());
        SpiderRuntime.get().call("category", args(site), args.toString())
                .whenComplete((json, error) -> {
                    if (error == null) {
                        try {
                            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                            List<ContentItem> items = parseItems(root, site);
                            if (!items.isEmpty()) {
                                home.items.addAll(items);
                                home.filters.putAll(parseFilters(root));
                                Log.i(TAG, "Home preview ready: " + site.key
                                        + " (" + items.size() + " items)");
                                finished.run();
                                return;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    fillHomePreview(site, home, categoryIndex + 1, finished);
                });
    }

    private void finishHomeCandidate(SiteConfig candidate, Home home, String rawJson,
                                     LoadCallback callback) {
        homeSite = candidate;
        lastHome = home;
        if (rawJson != null && !home.items.isEmpty() && context != null) {
            HomeCache.save(context, candidate.key, rawJson);
        }
        Log.i(TAG, "Home ready: " + candidate.key + " (" + home.items.size() + " items)");
        callback.onHome(home);
    }

    private Home cachedHome() {
        if (context == null || homeSite == null) return null;
        HomeCache.Cached cached = HomeCache.load(context, homeSite.key);
        if (cached == null) return null;
        try {
            Home home = parseHome(cached.json, homeSite);
            return home.items.isEmpty() && home.types.isEmpty() ? null : home;
        } catch (Throwable error) {
            Log.w(TAG, "Cached home invalid: " + rootMessage(error));
            return null;
        }
    }

    public void home(LoadCallback callback) {
        if (homeSite != null) loadHome(callback);
        else callback.onError(new IllegalStateException("尚未加载站点"));
    }

    public void category(String tid, String page, boolean filter, Map<String, String> extend, CategoryCallback callback) {
        SiteConfig site = homeSite;
        JsonObject args = categoryArgs(tid, page, filter, extend);
        SpiderRuntime.get().call("category", args(site), args.toString()).whenComplete((json, error) -> {
            if (error != null) callback.onError(error);
            else {
                try {
                    JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                    Map<String, List<FilterGroup>> allFilters = parseFilters(root);
                    List<FilterGroup> filters = allFilters.getOrDefault(tid, List.of());
                    if (!filters.isEmpty()) categoryFilters.put(tid, filters);
                    callback.onCategory(new CategoryPage(parseItems(root, site), filters));
                } catch (Throwable parseError) {
                    callback.onError(parseError);
                }
            }
        });
    }

    private JsonObject categoryArgs(String tid, String page, boolean filter,
                                    Map<String, String> extend) {
        JsonObject args = new JsonObject();
        args.addProperty("tid", tid);
        args.addProperty("page", page);
        args.addProperty("filter", filter);
        JsonObject filterJson = new JsonObject();
        if (extend != null) {
            for (var entry : extend.entrySet()) {
                filterJson.addProperty(entry.getKey(), entry.getValue());
            }
        }
        args.add("extend", filterJson);
        return args;
    }

    public void detail(ContentItem item, DetailCallback callback) {
        SiteConfig site = item.site;
        if (site == null) site = homeSite;
        if (site == null) {
            callback.onError(new IllegalStateException("站点已失效"));
            return;
        }
        if (item.id == null || item.id.isBlank()) {
            callback.onError(new IllegalStateException("内容 ID 为空"));
            return;
        }
        String cacheKey = detailKey(site, item.id);
        Detail cached = details.get(cacheKey);
        if (cached != null) {
            callback.onDetail(cached);
            return;
        }
        SiteConfig resolved = site;
        java.util.concurrent.CompletableFuture<Detail> pending =
                pendingDetails.computeIfAbsent(cacheKey,
                        ignored -> requestDetail(item, resolved, cacheKey));
        pending.whenComplete((detail, error) -> {
            pendingDetails.remove(cacheKey, pending);
            if (error != null) callback.onError(rootCause(error));
            else callback.onDetail(detail);
        });
    }

    public void prefetchDetail(ContentItem item) {
        SiteConfig site = item == null ? null : item.site;
        if (site == null) site = homeSite;
        if (item == null || site == null || !site.isSpider()
                || item.id == null || item.id.isBlank()) {
            return;
        }
        String cacheKey = detailKey(site, item.id);
        if (details.containsKey(cacheKey)) return;
        SiteConfig resolvedSite = site;
        int generation = detailPrefetchGeneration.incrementAndGet();
        REQUEST_TIMER.schedule(() -> {
            if (generation != detailPrefetchGeneration.get()
                    || details.containsKey(cacheKey)) {
                return;
            }
            java.util.concurrent.CompletableFuture<Detail> pending =
                    pendingDetails.computeIfAbsent(cacheKey,
                            ignored -> requestDetail(item, resolvedSite, cacheKey));
            pending.whenComplete((detail, error) -> {
                if (error != null) {
                    Log.d(TAG, "Detail prefetch failed: " + rootMessage(error));
                }
                pendingDetails.remove(cacheKey, pending);
            });
        }, DETAIL_PREFETCH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private java.util.concurrent.CompletableFuture<Detail> requestDetail(
            ContentItem item, SiteConfig site, String cacheKey) {
        java.util.concurrent.CompletableFuture<Detail> result =
                new java.util.concurrent.CompletableFuture<>();
        requestDetailAttempt(item, site, cacheKey, 1, result);
        return result;
    }

    private void requestDetailAttempt(ContentItem item, SiteConfig site, String cacheKey,
                                      int attempt,
                                      java.util.concurrent.CompletableFuture<Detail> result) {
        SpiderRuntime.get().call("detail", args(site), item.id)
                .whenComplete((json, error) -> {
                    if (error == null) {
                        try {
                            ContentItem resolvedItem = item.site == null
                                    ? new ContentItem(item.id, item.title, item.meta, item.year,
                                    item.pic, item.remark, site)
                                    : item;
                            Detail detail = parseDetail(json, resolvedItem);
                            Detail cached = details.putIfAbsent(cacheKey, detail);
                            result.complete(cached == null ? detail : cached);
                        } catch (Throwable parseError) {
                            retryDetail(item, site, cacheKey, attempt, parseError, result);
                        }
                        return;
                    }
                    retryDetail(item, site, cacheKey, attempt, error, result);
                });
    }

    private void retryDetail(ContentItem item, SiteConfig site, String cacheKey,
                             int attempt, Throwable error,
                             java.util.concurrent.CompletableFuture<Detail> result) {
        Log.w(TAG, "Detail attempt " + attempt + " failed: " + rootMessage(error));
        if (attempt >= 2 || result.isDone() || isTimeout(error)) {
            result.completeExceptionally(error);
            return;
        }
        REQUEST_TIMER.schedule(
                () -> requestDetailAttempt(item, site, cacheKey, attempt + 1, result),
                DETAIL_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static boolean isTimeout(Throwable error) {
        String message = rootMessage(error).toLowerCase(Locale.ROOT);
        return message.contains("timeout") || message.contains("超时");
    }

    public SearchHandle search(String keyword, SearchCallback callback) {
        SearchHandle handle = new SearchHandle();
        if (config == null) {
            callback.onError(new IllegalStateException("配置尚未加载"));
            return handle;
        }
        List<SiteConfig> sites = searchSites(config, homeSite);
        if (sites.isEmpty()) {
            callback.onError(new IllegalStateException("没有可搜索站点"));
            return handle;
        }
        List<ContentItem> results = Collections.synchronizedList(new ArrayList<>());
        int[] remaining = {sites.size()};
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (!handle.isCancelled() && finished.compareAndSet(false, true)) {
                callback.onSearch(snapshot(results));
            }
        };
        handle.setTimeout(REQUEST_TIMER.schedule(
                finish, SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        for (SiteConfig site : sites) {
            java.util.concurrent.CompletableFuture<String> request =
                    SpiderRuntime.get().call("search", args(site), keyword,
                            SEARCH_SITE_TIMEOUT_SECONDS);
            handle.track(request);
            request.whenComplete((json, error) -> {
                if (handle.isCancelled()) return;
                List<ContentItem> added = List.of();
                if (error == null) {
                    try {
                        added = parseSearchList(json, site);
                        results.addAll(added);
                    } catch (Throwable ignored) {
                    }
                }
                if (!added.isEmpty() && !finished.get() && !handle.isCancelled()) {
                    callback.onUpdate(snapshot(results));
                }
                synchronized (remaining) {
                    if (--remaining[0] == 0) finish.run();
                }
            });
        }
        return handle;
    }

    static List<SiteConfig> searchSites(TvBoxConfig config, SiteConfig homeSite) {
        if (config == null || config.sites == null) return List.of();
        List<SiteConfig> sites = new ArrayList<>();
        for (SiteConfig site : config.sites) {
            if (site != null && site.isSpider() && site.isSearchable()
                    && !isUtilitySite(site)) {
                sites.add(site);
            }
        }
        sites.sort((left, right) -> {
            int order = Boolean.compare(left != homeSite, right != homeSite);
            if (order != 0) return order;
            order = Boolean.compare(right.isQuickSearch(), left.isQuickSearch());
            if (order != 0) return order;
            return left.displayName().compareTo(right.displayName());
        });
        return sites;
    }

    private static List<ContentItem> snapshot(List<ContentItem> items) {
        synchronized (items) {
            return new ArrayList<>(items);
        }
    }

    public void player(ContentItem item, Episode episode, PlayerCallback callback) {
        if (episode == null || episode.id == null || episode.id.isBlank()) {
            callback.onError(new IllegalArgumentException("播放地址为空"));
            return;
        }
        SiteConfig site = item == null ? null : item.site;
        if (site == null) site = homeSite;
        if (site == null) {
            callback.onError(new IllegalStateException("站点已失效"));
            return;
        }
        String cacheKey = playbackKey(site, episode);
        Playback cached = cachedPlayback(cacheKey);
        if (cached != null) {
            callback.onPlayer(cached);
            return;
        }
        SiteConfig resolvedSite = site;
        java.util.concurrent.CompletableFuture<Playback> pending =
                pendingPlaybacks.computeIfAbsent(cacheKey,
                        ignored -> requestPlayer(resolvedSite, episode, cacheKey));
        pending.whenComplete((playback, error) -> {
            if (error != null) callback.onError(rootCause(error));
            else callback.onPlayer(playback);
        });
    }

    /**
     * Warms the player response while the user browses episodes. A short cache
     * keeps the prefetched URL reusable when the activity opens, while pending
     * requests are shared so touching a focused episode does not create a
     * duplicate spider call.
     */
    public void prefetchPlayer(ContentItem item, Episode episode) {
        SiteConfig site = item == null ? null : item.site;
        if (site == null) site = homeSite;
        if (site == null || episode == null || episode.id == null
                || episode.id.isBlank() || isDirectUrl(episode.id)
                || isCloudPayload(episode.id)) {
            return;
        }
        String cacheKey = playbackKey(site, episode);
        if (cachedPlayback(cacheKey) != null || pendingPlaybacks.containsKey(cacheKey)) {
            return;
        }
        SiteConfig resolvedSite = site;
        int generation = playbackPrefetchGeneration.incrementAndGet();
        REQUEST_TIMER.schedule(() -> {
            if (generation != playbackPrefetchGeneration.get()
                    || cachedPlayback(cacheKey) != null) {
                return;
            }
            java.util.concurrent.CompletableFuture<Playback> pending =
                    pendingPlaybacks.computeIfAbsent(cacheKey,
                            ignored -> requestPlayer(resolvedSite, episode, cacheKey));
            pending.whenComplete((playback, error) -> {
                if (error != null) {
                    Log.d(TAG, "Playback prefetch failed: " + rootMessage(error));
                }
            });
        }, PLAYBACK_PREFETCH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private java.util.concurrent.CompletableFuture<Playback> requestPlayer(
            SiteConfig site, Episode episode, String cacheKey) {
        long startedAt = System.currentTimeMillis();
        JsonObject args = new JsonObject();
        args.addProperty("flag", episode.flag);
        args.addProperty("id", episode.id);
        java.util.concurrent.CompletableFuture<Playback> result =
                new java.util.concurrent.CompletableFuture<>();
        result.whenComplete((playback, error) ->
                pendingPlaybacks.remove(cacheKey, result));
        SpiderRuntime.get().call("player", args(site), args.toString()).whenComplete((json, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                try {
                    Playback playback = parsePlayer(json);
                    if (playback.url != null && !playback.url.isBlank()) {
                        if (playbackCache.size() > 64) {
                            long now = System.currentTimeMillis();
                            playbackCache.entrySet().removeIf(entry ->
                                    entry.getValue().expiresAt <= now);
                        }
                        playbackCache.put(cacheKey, new CachedPlayback(
                                playback, System.currentTimeMillis() + PLAYBACK_CACHE_TTL_MS));
                    }
                    Log.i(TAG, "Player URL ready in "
                            + (System.currentTimeMillis() - startedAt)
                            + " ms for " + site.key);
                    result.complete(playback);
                } catch (Throwable parseError) {
                    result.completeExceptionally(parseError);
                }
            }
        });
        return result;
    }

    private Playback cachedPlayback(String key) {
        CachedPlayback cached = playbackCache.get(key);
        if (cached == null) return null;
        if (cached.expiresAt <= System.currentTimeMillis()) {
            playbackCache.remove(key, cached);
            return null;
        }
        return cached.playback;
    }

    private static String playbackKey(SiteConfig site, Episode episode) {
        String siteKey = site == null ? "" : site.key;
        String flag = episode == null || episode.flag == null ? "" : episode.flag;
        String id = episode == null || episode.id == null ? "" : episode.id;
        return siteKey + "\u0000" + flag + "\u0000" + id;
    }

    private static boolean isDirectUrl(String value) {
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return raw.startsWith("http://")
                || raw.startsWith("https://")
                || raw.startsWith("//")
                || raw.startsWith("rtsp://")
                || raw.startsWith("rtsps://")
                || raw.startsWith("file://")
                || raw.startsWith("content://");
    }

    private static boolean isCloudPayload(String value) {
        String raw = value == null ? "" : value.trim();
        return raw.startsWith("{")
                || raw.contains("\"sToken\"")
                || raw.contains("\"share_fid_token\"")
                || raw.contains("\"pdir_fid\"");
    }

    private Home parseHome(String json) {
        return parseHome(json, homeSite);
    }

    private Home parseHome(String json, SiteConfig site) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Home home = new Home();
        home.types = parseTypes(root);
        home.items = parseItems(root, site);
        home.filters.putAll(parseFilters(root));
        JsonObject video = parseObject(root, "video");
        if (video != null) home.items.addAll(parseItems(video, site));
        return home;
    }

    private List<ContentItem> parseList(String json) {
        return parseList(json, homeSite);
    }

    private List<ContentItem> parseList(String json, SiteConfig site) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new ArrayList<>(parseItems(root, site, true, 0));
    }

    private List<ContentItem> parseSearchList(String json, SiteConfig site) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new ArrayList<>(parseItems(
                root, site, false, MAX_SEARCH_ITEMS_PER_SITE));
    }

    private Detail parseDetail(String json, ContentItem source) {
        JsonArray list = parseArray(JsonParser.parseString(json).getAsJsonObject(), "list");
        if (list == null || list.isEmpty()) throw new IllegalStateException("详情为空");
        JsonObject vod = list.get(0).getAsJsonObject();
        Detail detail = new Detail();
        detail.item = source;
        detail.title = string(vod, "vod_name", string(vod, "name", source.title));
        detail.pic = string(vod, "vod_pic", string(vod, "pic", source.pic));
        detail.remarks = string(vod, "vod_remarks",
                string(vod, "remarks", source.remark));
        detail.year = string(vod, "vod_year", string(vod, "year", source.year));
        detail.area = string(vod, "vod_area", string(vod, "area", ""));
        detail.content = clean(string(vod, "vod_content",
                string(vod, "content", "")));
        detail.typeName = string(vod, "type_name",
                string(vod, "type", source.meta));
        String from = string(vod, "vod_play_from", "");
        String url = string(vod, "vod_play_url", "");
        String[] flags = from.split("\\$\\$\\$");
        String[] groups = url.split("\\$\\$\\$");
        for (int index = 0; index < Math.min(flags.length, groups.length); index++) {
            String flag = flags[index];
            List<Episode> episodes = new ArrayList<>();
            for (String line : groups[index].split("#")) {
                String[] parts = line.split("\\$", 2);
                if (parts.length < 2 || parts[1].isBlank()) continue;
                episodes.add(new Episode(parts[0].isBlank() ? "正片" : parts[0], parts[1], flag));
            }
            if (!episodes.isEmpty()) detail.episodes.add(new PlayGroup(flag, episodes));
        }
        if (detail.episodes.isEmpty() && isResourceContainerTitle(detail.title)
                && !source.title.isBlank()) {
            detail.title = source.title;
            detail.pic = source.pic;
            detail.typeName = source.meta.isBlank() ? detail.typeName : source.meta;
        }
        if (context != null) {
            ArtworkResolver.get(context).remember(detail.title, detail.year, detail.pic);
        }
        return detail;
    }

    static Playback parsePlayer(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String url = extractPlaybackUrl(root.get("url"));
        if (url.isBlank() && root.has("urls")) {
            url = extractPlaybackUrl(root.get("urls"));
        }
        if (url.startsWith("//")) url = "https:" + url;
        String message = string(root, "msg", string(root, "message", ""));
        Map<String, String> headers = new HashMap<>();
        appendHeaders(root.get("header"), headers);
        appendHeaders(root.get("headers"), headers);
        return new Playback(url, headers, message, isParseRequired(root.get("parse")));
    }

    private static boolean isParseRequired(JsonElement element) {
        if (element == null || element.isJsonNull()) return false;
        try {
            if (element.isJsonPrimitive()) {
                if (element.getAsJsonPrimitive().isBoolean()) {
                    return element.getAsBoolean();
                }
                String value = element.getAsString().trim();
                return "1".equals(value) || "true".equalsIgnoreCase(value);
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static String extractPlaybackUrl(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) {
            return element.getAsString().trim();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String[] preferred = {"url", "playUrl", "play_url", "src", "link", "address"};
            String fallback = "";
            for (String key : preferred) {
                if (!object.has(key)) continue;
                String candidate = extractPlaybackUrl(object.get(key));
                if (candidate.isBlank()) continue;
                if (isPlayableUrl(candidate)) return candidate;
                if (fallback.isBlank()) fallback = candidate;
            }
            for (var entry : object.entrySet()) {
                String candidate = extractPlaybackUrl(entry.getValue());
                if (candidate.isBlank()) continue;
                if (isPlayableUrl(candidate)) return candidate;
                if (fallback.isBlank()) fallback = candidate;
            }
            return fallback;
        }
        if (element.isJsonArray()) {
            String fallback = "";
            for (JsonElement value : element.getAsJsonArray()) {
                String candidate = extractPlaybackUrl(value);
                if (candidate.isBlank()) continue;
                if (isPlayableUrl(candidate)) return candidate;
                if (fallback.isBlank()) fallback = candidate;
            }
            return fallback;
        }
        return "";
    }

    private static boolean isPlayableUrl(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("//")
                || lower.startsWith("rtsp://")
                || lower.startsWith("rtsps://")
                || lower.startsWith("file://")
                || lower.startsWith("content://")
                || lower.startsWith("magnet:")
                || lower.contains("://");
    }

    private static void appendHeaders(JsonElement element, Map<String, String> headers) {
        if (element == null || element.isJsonNull() || headers == null) return;
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive()) {
                    headers.put(entry.getKey(), value.getAsString());
                }
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) appendHeaders(value, headers);
            return;
        }
        if (!element.isJsonPrimitive()) return;
        String raw = element.getAsString().trim();
        if (!raw.startsWith("{")) return;
        try {
            appendHeaders(JsonParser.parseString(raw), headers);
        } catch (Throwable ignored) {
        }
    }

    private List<ContentItem> parseItems(JsonObject root, SiteConfig site) {
        return parseItems(root, site, true, 0);
    }

    private List<ContentItem> parseItems(JsonObject root, SiteConfig site,
                                         boolean prefetchArtwork, int limit) {
        List<ContentItem> result = new ArrayList<>();
        JsonArray list = parseArray(root, "list");
        if (list == null) return result;
        for (JsonElement element : list) {
            if (limit > 0 && result.size() >= limit) break;
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            String id = string(vod, "vod_id", "");
            String title = string(vod, "vod_name", "");
            if (id.isEmpty() || title.isEmpty()) continue;
            String pic = string(vod, "vod_pic", "");
            String remarks = string(vod, "vod_remarks", "");
            String type = string(vod, "type_name", "");
            String year = string(vod, "vod_year", "");
            long hits = SearchMetrics.parseHits(string(vod, "vod_hits", ""));
            double rating = SearchMetrics.parseRating(string(vod, "vod_score", ""));
            result.add(new ContentItem(id, title, type, year, pic, remarks, site,
                    hits, rating));
        }
        if (context != null) {
            ArtworkResolver resolver = ArtworkResolver.get(context);
            resolver.remember(result);
            if (prefetchArtwork) resolver.prefetch(result, 12);
        }
        return result;
    }

    private static String detailKey(SiteConfig site, String id) {
        return (site == null ? "" : site.key) + "\u0000" + (id == null ? "" : id);
    }

    private List<Category> parseTypes(JsonObject root) {
        List<Category> result = new ArrayList<>();
        JsonArray types = parseArray(root, "class");
        if (types == null) return result;
        for (JsonElement element : types) {
            if (!element.isJsonObject()) continue;
            JsonObject type = element.getAsJsonObject();
            result.add(new Category(string(type, "type_id", ""), string(type, "type_name", "")));
        }
        return result;
    }

    private Map<String, List<FilterGroup>> parseFilters(JsonObject root) {
        Map<String, List<FilterGroup>> result = new LinkedHashMap<>();
        JsonObject filters = parseObject(root, "filters");
        if (filters == null) return result;
        for (Map.Entry<String, JsonElement> entry : filters.entrySet()) {
            List<FilterGroup> groups = parseFilterGroups(entry.getValue());
            if (!groups.isEmpty()) result.put(entry.getKey(), groups);
        }
        return result;
    }

    private List<FilterGroup> parseFilterGroups(JsonElement element) {
        List<FilterGroup> result = new ArrayList<>();
        if (element == null || element.isJsonNull()) return result;
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                FilterGroup group = parseFilterGroup(item);
                if (group != null) result.add(group);
            }
        } else {
            FilterGroup group = parseFilterGroup(element);
            if (group != null) result.add(group);
        }
        return result;
    }

    private FilterGroup parseFilterGroup(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        String key = string(object, "key", "");
        if (key.isBlank()) return null;
        String name = string(object, "name", key);
        String init = string(object, "init", "");
        List<FilterOption> options = new ArrayList<>();
        JsonElement values = object.get("value");
        if (values != null && values.isJsonArray()) {
            for (JsonElement valueElement : values.getAsJsonArray()) {
                if (!valueElement.isJsonObject()) continue;
                JsonObject value = valueElement.getAsJsonObject();
                String optionValue = string(value, "v", "");
                String label = string(value, "n", optionValue);
                if (label.isBlank()) label = "全部";
                options.add(new FilterOption(label, optionValue));
            }
        }
        return options.isEmpty() ? null : new FilterGroup(key, name, init, options);
    }

    private JsonObject parseObject(JsonObject root, String key) {
        try {
            JsonElement element = root.get(key);
            if (element == null || !element.isJsonObject()) return null;
            return element.getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonArray parseArray(JsonObject root, String key) {
        try {
            JsonElement element = root.get(key);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsString().trim();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String clean(String value) {
        String text = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('\u00a0', ' ')
                .replace('\u3000', ' ')
                .replaceAll("[\\p{Cf}]", "")
                .replaceAll("\\s+", " ")
                .trim();
        int boundary = sentenceBoundary(text);
        if (boundary > 0 && boundary <= 180) {
            String prefix = text.substring(0, boundary).toLowerCase(Locale.ROOT);
            boolean platform = prefix.contains("夸克") || prefix.contains("百度")
                    || prefix.contains("uc") || prefix.contains("迅雷")
                    || prefix.contains("网盘");
            boolean promotion = prefix.contains("和谐") || prefix.contains("会员")
                    || prefix.contains("频道") || prefix.contains("同步")
                    || prefix.contains("优先") || prefix.contains("登录")
                    || prefix.contains("飞机");
            if (platform && promotion) text = text.substring(boundary).trim();
        }
        return text.replaceFirst("^[\\p{So}\\p{Sk}\\s]+", "").trim();
    }

    private static int sentenceBoundary(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '。' || character == '！' || character == '？'
                    || character == '!' || character == '?') {
                return index + 1;
            }
        }
        return -1;
    }

    private static boolean isResourceContainerTitle(String title) {
        if (title == null) return false;
        String value = title.replaceAll("\\s+", "").trim();
        return value.equals("磁力链接") || value.equals("网盘链接")
                || value.equals("网盘资源") || value.equals("资源列表")
                || value.equals("资源");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return String.valueOf(current.getMessage() == null ? current : current.getMessage());
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? new IllegalStateException("详情加载失败") : current;
    }

    private static SiteArgs args(SiteConfig site) {
        if (site == null) throw new IllegalArgumentException("站点为空");
        SiteArgs args = new SiteArgs();
        args.key = site.key;
        args.name = site.name;
        args.api = site.api;
        args.ext = site.ext;
        args.jar = site.jar;
        args.type = site.type;
        return args;
    }

    public interface LoadCallback {
        default void onConfig(TvBoxConfig config) {
        }

        void onHome(Home home);

        void onError(Throwable error);
    }

    public interface CategoryCallback {
        void onCategory(CategoryPage page);

        void onError(Throwable error);
    }

    public interface DetailCallback {
        void onDetail(Detail detail);

        void onError(Throwable error);
    }

    public interface SearchCallback {
        default void onUpdate(List<ContentItem> items) {
        }

        void onSearch(List<ContentItem> items);

        void onError(Throwable error);
    }

    public static final class SearchHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<java.util.concurrent.CompletableFuture<String>> requests =
                new CopyOnWriteArrayList<>();
        private volatile ScheduledFuture<?> timeout;

        private void setTimeout(ScheduledFuture<?> timeout) {
            this.timeout = timeout;
            if (cancelled.get() && timeout != null) timeout.cancel(false);
        }

        private void track(java.util.concurrent.CompletableFuture<String> request) {
            if (request == null) return;
            requests.add(request);
            if (cancelled.get()) SpiderRuntime.get().cancel(request);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            ScheduledFuture<?> current = timeout;
            if (current != null) current.cancel(false);
            for (java.util.concurrent.CompletableFuture<String> request : requests) {
                SpiderRuntime.get().cancel(request);
            }
            requests.clear();
        }
    }

    public interface PlayerCallback {
        void onPlayer(Playback playback);

        void onError(Throwable error);
    }

    public static final class Home {
        public List<Category> types = new ArrayList<>();
        public List<ContentItem> items = new ArrayList<>();
        public Map<String, List<FilterGroup>> filters = new LinkedHashMap<>();
    }

    public static final class Category {
        public final String id;
        public final String name;

        public Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class CategoryPage {
        public final List<ContentItem> items;
        public final List<FilterGroup> filters;

        public CategoryPage(List<ContentItem> items, List<FilterGroup> filters) {
            this.items = items;
            this.filters = filters;
        }
    }

    public static final class FilterGroup {
        public final String key;
        public final String name;
        public final String init;
        public final List<FilterOption> options;

        public FilterGroup(String key, String name, String init, List<FilterOption> options) {
            this.key = key;
            this.name = name;
            this.init = init;
            this.options = options;
        }
    }

    public static final class FilterOption {
        public final String label;
        public final String value;

        public FilterOption(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    public static final class Detail {
        public ContentItem item;
        public String title;
        public String pic;
        public String remarks;
        public String year;
        public String area;
        public String content;
        public String typeName;
        public List<PlayGroup> episodes = new ArrayList<>();
    }

    public static final class RuntimeSnapshot {
        private final TvBoxConfig config;
        private final SiteConfig homeSite;
        private final Home home;
        private final Map<String, Detail> details;

        RuntimeSnapshot(TvBoxConfig config, SiteConfig homeSite, Home home,
                        Map<String, Detail> details) {
            this.config = config;
            this.homeSite = homeSite;
            this.home = home;
            this.details = details;
        }
    }

    public static final class PlayGroup {
        public final String flag;
        public final List<Episode> episodes;

        public PlayGroup(String flag, List<Episode> episodes) {
            this.flag = flag;
            this.episodes = episodes;
        }
    }

    public static final class Episode {
        public final String name;
        public final String id;
        public final String flag;

        public Episode(String name, String id, String flag) {
            this.name = name;
            this.id = id;
            this.flag = flag;
        }
    }

    public static final class Playback {
        public final String url;
        public final Map<String, String> headers;
        public final String message;
        public final boolean requiresParsing;

        public Playback(String url, Map<String, String> headers, String message) {
            this(url, headers, message, false);
        }

        public Playback(String url, Map<String, String> headers, String message,
                        boolean requiresParsing) {
            this.url = url;
            this.headers = headers;
            this.message = message;
            this.requiresParsing = requiresParsing;
        }
    }

    private record CachedPlayback(Playback playback, long expiresAt) {
    }
}
