package com.ch4nhm.tvapp.spider;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import com.fongmi.quickjs.host.Host;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.Net;
import com.github.catvod.utils.Local;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;

final class SpiderEngine {
    private static final String TAG = "TVBoxSpider";
    private static final SpiderEngine INSTANCE = new SpiderEngine();
    private final Map<String, Loaded> sites = new ConcurrentHashMap<>();
    private final Map<String, JarState> jars = new ConcurrentHashMap<>();

    static SpiderEngine get() {
        return INSTANCE;
    }

    String call(String method, SiteArgs site, String argument) throws RemoteException {
        try {
            Spider spider = getSpider(site);
            Log.i(TAG, "Call " + method + " for " + site.key + ": " + abbreviate(argument));
            String value = switch (method) {
                case "home" -> spider.homeContent(true);
                case "homeVideo" -> spider.homeVideoContent();
                case "category" -> category(spider, argument);
                case "detail" -> spider.detailContent(List.of(argument == null ? "" : argument));
                case "search" -> spider.searchContent(argument == null ? "" : argument, false);
                case "player" -> player(spider, argument);
                case "action" -> spider.action(argument == null ? "" : argument);
                case "proxy" -> proxy(site, argument);
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            };
            Log.i(TAG, "Result " + method + " for " + site.key + " (" + (value == null ? 0 : value.length()) + " chars): " + abbreviate(value));
            return value == null ? "" : value;
        } catch (Throwable error) {
            Log.e(TAG, method + " failed for " + site.key, error);
            throw new RemoteException(rootMessage(error));
        }
    }

    void prepare(SiteArgs site) throws Exception {
        loadJar(site.jar);
        Log.i(TAG, "Prepared spider JAR for " + site.key);
    }

    Object[] proxy(Map<String, String> params) throws Exception {
        if (params == null) throw new IllegalArgumentException("Proxy parameters are required");
        String jarPath = params.getOrDefault("jar", "").trim();
        Map<String, String> external = new HashMap<>(params);
        external.remove("jar");
        if (!jarPath.isEmpty()) {
            JarState state = loadJar(jarPath);
            Object[] result = invokeProxy(state, external);
            if (result != null) return result;
            throw new IllegalStateException("Spider proxy returned an empty response");
        }
        Exception failure = null;
        for (JarState state : jars.values()) {
            try {
                Object[] result = invokeProxy(state, external);
                if (result != null && result.length > 0) return result;
            } catch (Exception error) {
                failure = error;
                Log.w(TAG, "Proxy attempt failed", error);
            }
        }
        if (failure != null) throw failure;
        throw new IllegalStateException("Proxy request has no loaded spider JAR");
    }

    private Object[] invokeProxy(JarState state, Map<String, String> params) throws Exception {
        try {
            Object result = state.proxy.invoke(null, params);
            if (result instanceof Object[] values) return values;
            return result == null ? null : new Object[]{result};
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw error;
        }
    }

    private String category(Spider spider, String argument) throws Exception {
        JsonObject params = JsonParser.parseString(argument).getAsJsonObject();
        HashMap<String, String> extend = new HashMap<>();
        if (params.has("extend") && params.get("extend").isJsonObject()) {
            for (var entry : params.getAsJsonObject("extend").entrySet()) extend.put(entry.getKey(), entry.getValue().getAsString());
        }
        return spider.categoryContent(Optional.string(params, "tid", ""), Optional.string(params, "page", "1"),
                Optional.bool(params, "filter", false), extend);
    }

    private String player(Spider spider, String argument) throws Exception {
        JsonObject params = JsonParser.parseString(argument).getAsJsonObject();
        return spider.playerContent(Optional.string(params, "flag", ""), Optional.string(params, "id", ""), Collections.emptyList());
    }

    private String proxy(SiteArgs site, String argument) throws Exception {
        Map<String, String> params = new HashMap<>();
        if (argument != null && !argument.isBlank()) {
            for (var entry : JsonParser.parseString(argument).getAsJsonObject().entrySet()) {
                params.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        Object result = loadJar(site.jar).proxy.invoke(null, params);
        if (result instanceof Object[] values) {
            return values.length > 2 && values[2] instanceof java.io.InputStream stream
                    ? new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    : "";
        }
        return result == null ? "" : new Gson().toJson(result);
    }

    private static String abbreviate(String value) {
        if (value == null) return "null";
        int limit = Math.min(value.length(), 620);
        String prefix = value.substring(0, limit)
                .replace('\n', ' ')
                .replace('\r', ' ');
        return value.length() <= 600 ? prefix : prefix.substring(0, 600) + "...";
    }

    private Spider getSpider(SiteArgs site) throws Exception {
        Loaded loaded = sites.get(site.key);
        if (loaded != null && loaded.site.matches(site)) return loaded.spider;
        synchronized (this) {
            loaded = sites.get(site.key);
            if (loaded != null && loaded.site.matches(site)) return loaded.spider;
            JarState state = loadJar(site.jar);
            String className = "com.github.catvod.spider." + site.api.substring(4);
            Spider spider = (Spider) state.loader.loadClass(className).getDeclaredConstructor().newInstance();
            spider.siteKey = site.key;
            spider.net = new Net(new SpiderHost(), site.key);
            spider.local = new Local(site.key);
            spider.init(context(), site.ext);
            if (loaded != null) loaded.close();
            sites.put(site.key, new Loaded(site.copy(), spider));
            return spider;
        }
    }

    private JarState loadJar(String path) throws Exception {
        File jar = new File(path).getAbsoluteFile();
        if (!jar.isFile() || jar.length() == 0) throw new IllegalStateException("Spider JAR 不存在: " + jar);
        String key = jar.getCanonicalPath();
        JarState state = jars.get(key);
        if (state != null) return state;
        synchronized (this) {
            state = jars.get(key);
            if (state != null) return state;
            File directory = new File(context().getCacheDir(), "spider-dex");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建 DEX 缓存目录");
            DexClassLoader loader = new DexClassLoader(jar.getAbsolutePath(), directory.getAbsolutePath(),
                    directory.getAbsolutePath(), context().getClassLoader());
            loader.loadClass("com.github.catvod.spider.Init").getMethod("init", Context.class).invoke(null, context());
            Method proxy = loader.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map.class);
            state = new JarState(loader, proxy);
            jars.put(key, state);
            return state;
        }
    }

    void reset() {
        for (Loaded loaded : sites.values()) loaded.close();
        sites.clear();
        jars.clear();
    }

    private Context context() {
        return com.github.catvod.Runtime.context();
    }

    private static final class SpiderHost implements Host {
        @Override
        public String getCookie(String url) {
            return com.github.catvod.Runtime.getCookie(url);
        }

        @Override
        public boolean setCookie(String url, String cookie) {
            return com.github.catvod.Runtime.setCookie(url, cookie);
        }

        @Override
        public boolean openWeb(String options) {
            return com.github.catvod.Runtime.openWeb(options);
        }

        @Override
        public String getUserAgent() {
            return com.github.catvod.Runtime.getUserAgent();
        }

        @Override
        public long getCallTimeout() {
            return 60_000L;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return Objects.toString(current.getMessage(), current.toString());
    }

    private record JarState(DexClassLoader loader, Method proxy) {
    }

    private record Loaded(SiteArgs site, Spider spider) {
        void close() {
            try {
                spider.dialog.close();
                spider.js.close();
                if (spider.net != null) spider.net.close();
                spider.destroy();
            } catch (RuntimeException | Error ignored) {
            }
        }
    }

    static final class Optional {
        static String string(JsonObject object, String key, String fallback) {
            try {
                return object.get(key).getAsString();
            } catch (Exception ignored) {
                return fallback;
            }
        }

        static boolean bool(JsonObject object, String key, boolean fallback) {
            try {
                return object.get(key).getAsBoolean();
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }
}
