package com.ch4nhm.tvapp.data;

import android.util.Log;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Decoder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ConfigLoader {

    private static final String TAG = "TVBoxConfig";
    private static final long CONFIG_TIMEOUT_SECONDS = 20;
    private static final long SPIDER_TIMEOUT_SECONDS = 90;
    private static final long CACHE_MAX_AGE_MS = 24 * 60 * 60_000L;
    private static final ExecutorService NETWORK = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tvbox-network");
        thread.setDaemon(true);
        return thread;
    });

    public interface Callback {
        void onLoaded(TvBoxConfig config);
        void onError(Throwable error);
    }

    private ConfigLoader() {
    }

    public static void load(@NonNull ConfigStore store, @NonNull Callback callback) {
        load(store, false, callback);
    }

    public static void refresh(@NonNull ConfigStore store, @NonNull Callback callback) {
        load(store, true, callback);
    }

    private static void load(@NonNull ConfigStore store, boolean forceRefresh,
                             @NonNull Callback callback) {
        Thread worker = new Thread(() -> {
            try {
                if (!forceRefresh && store.hasFreshConfig(CACHE_MAX_AGE_MS)) {
                    TvBoxConfig cached = store.getConfig();
                    if (isUsable(cached)) {
                        Log.i(TAG, "Using cached config: " + store.getUrl()
                                + ", sites=" + cached.sites.size());
                        callback.onLoaded(cached);
                        return;
                    }
                }
                Log.i(TAG, "Loading config: " + store.getUrl());
                TvBoxConfig config = fetch(store.getUrl(), store);
                if (config.sites.isEmpty()) throw new IllegalStateException("配置中没有可用站点");
                store.save(config);
                Log.i(TAG, "Config ready: " + config.sites.size() + " sites, spider=" + config.spider);
                callback.onLoaded(config);
            } catch (Throwable error) {
                TvBoxConfig cached = store.getConfig();
                if (cached != null && !cached.sites.isEmpty()) {
                    Log.w(TAG, "Config refresh failed, using cache", error);
                    callback.onLoaded(cached);
                } else {
                    Log.e(TAG, "Config loading failed", error);
                    callback.onError(error);
                }
            }
        }, "tvbox-config");
        worker.setDaemon(true);
        worker.start();
    }

    private static boolean isUsable(TvBoxConfig config) {
        if (config == null || config.sites == null || config.sites.isEmpty()) return false;
        for (SiteConfig site : config.sites) {
            if (site == null || !site.isSpider()) continue;
            if (site.jar == null || site.jar.isBlank()) continue;
            File jar = new File(site.jar);
            if (jar.isFile() && jar.length() > 0) return true;
        }
        return false;
    }

    private static TvBoxConfig fetch(String url, ConfigStore store) throws Exception {
        String text = withTimeout(NETWORK.submit(() -> Decoder.getJson(url, "config")), CONFIG_TIMEOUT_SECONDS, "配置下载超时");
        Log.i(TAG, "Downloaded config: " + text.length() + " chars");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        TvBoxConfig config = ConfigStore.gson().fromJson(root, TvBoxConfig.class);
        if (config == null) throw new IllegalStateException("配置格式无效");
        if (config.spider == null) config.spider = "";
        if (config.sites == null) config.sites = new java.util.ArrayList<>();
        if (config.lives == null) config.lives = new java.util.ArrayList<>();
        if (config.headers == null) config.headers = new java.util.ArrayList<>();
        for (SiteConfig site : config.sites) {
            if (site.jar == null || site.jar.isEmpty()) site.jar = config.spider;
            if (site.api == null) site.api = "";
            if (site.ext == null) site.ext = "";
            if (site.name == null) site.name = "";
            if (site.categories == null) site.categories = new java.util.ArrayList<>();
        }
        for (TvBoxConfig.Live live : config.lives) {
            if (live == null) continue;
            if (live.name == null) live.name = "";
            if (live.url == null) live.url = "";
            if (live.api == null) live.api = "";
            if (live.ext == null) live.ext = "";
            if (live.jar == null) live.jar = "";
            if (live.ua == null) live.ua = "";
            if (live.epg == null) live.epg = "";
            if (live.logo == null) live.logo = "";
        }
        ConfigCompatibility.apply(config);
        if (!config.spider.isEmpty()) {
            Log.i(TAG, "Preparing spider: " + config.spider);
            File jar = prepareSpider(config.spider, store);
            Log.i(TAG, "Spider ready: " + jar.getAbsolutePath() + " (" + jar.length() + " bytes)");
            for (SiteConfig site : config.sites) site.jar = jar.getAbsolutePath();
        }
        return config;
    }

    public static File prepareSpider(String spec, ConfigStore store) throws Exception {
        String[] parts = spec.split(";md5;");
        String source = parts[0].trim();
        String expected = parts.length > 1 ? parts[1].trim() : "";
        File directory = store.getCacheDir();
        File cache = new File(directory, "spider.jar");
        if (expected.isEmpty() && cache.isFile() && cache.length() > 0) return secureSpider(cache);
        if (cache.isFile() && !expected.isEmpty() && expected.equalsIgnoreCase(md5(cache))) {
            return secureSpider(cache);
        }
        byte[] data = withTimeout(NETWORK.submit(() -> read(source)), SPIDER_TIMEOUT_SECONDS, "Spider 下载超时");
        if (!expected.isEmpty() && !expected.equalsIgnoreCase(md5(data))) throw new IllegalStateException("Spider JAR MD5 校验失败");
        File temporary = new File(cache.getParentFile(), "spider.jar.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(data);
            output.getFD().sync();
        }
        if (cache.exists() && !cache.delete()) throw new IllegalStateException("无法替换旧 Spider JAR");
        if (!temporary.renameTo(cache)) throw new IllegalStateException("无法保存 Spider JAR");
        return secureSpider(cache);
    }

    private static File secureSpider(File file) throws ErrnoException {
        // Android rejects writable files passed to DexClassLoader, and File#setReadOnly
        // is not reliable for app-private storage on recent platform versions.
        Os.chmod(file.getAbsolutePath(), 0400);
        if (file.canWrite()) throw new IllegalStateException("无法将 Spider JAR 设为只读");
        return file;
    }

    private static <T> T withTimeout(Future<T> future, long seconds, String message) throws Exception {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            OkHttp.cancelAll();
            throw new IllegalStateException(message, error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }

    private static byte[] read(String source) throws Exception {
        if (source.startsWith("http")) {
            try (var response = OkHttp.newCall(source).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("HTTP " + response.code());
                return response.body().bytes();
            }
        }
        if (source.startsWith("file")) {
            return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(source.replace("file://", "")));
        }
        if (source.startsWith("assets")) {
            return new byte[0];
        }
        return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(source));
    }

    private static String md5(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(data);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }

    private static String md5(File file) throws Exception {
        return md5(java.nio.file.Files.readAllBytes(file.toPath()));
    }
}
