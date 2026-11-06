package com.ch4nhm.tvapp;

import android.util.Log;

import com.ch4nhm.tvapp.data.SiteConfig;
import com.ch4nhm.tvapp.spider.SiteArgs;
import com.ch4nhm.tvapp.spider.SpiderRuntime;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class PanRepository {

    private static final String TAG = "TVBoxPan";
    private static final String[] NAMES = {"quark", "aliyun", "uc", "baidu", "pan139",
            "thunder", "guangya", "pan123", "pan115", "pan189", "bili", "diy"};
    private static final String[] LABELS = {"夸克网盘", "阿里云盘", "UC 网盘", "百度网盘",
            "139 云盘", "迅雷云盘", "光鸭云盘", "123 云盘", "115 网盘", "天翼云盘",
            "哔哩哔哩", "自定义"};
    private static final String[] MARKS = {"Quark", "Aliyun", "UC", "Baidu", "China Mobile",
            "Thunder", "Guangya", "123Pan", "115", "189Cloud", "Bilibili", "WebDAV"};
    private static final String PAN_SITE = "MyPan";

    private final TvBoxRepository tvbox = TvBoxRepository.get();
    private final QuarkScanLoginClient quarkScanLogin = new QuarkScanLoginClient();
    private final BaiduScanLoginClient baiduScanLogin = new BaiduScanLoginClient();
    private final AliyunScanLoginClient aliyunScanLogin = new AliyunScanLoginClient();
    private static final Object PREPARE_LOCK = new Object();
    private static volatile String preparedSiteKey = "";

    public void load(Consumer<List<Brand>> onLoaded, Consumer<Throwable> onError) {
        List<Brand> brands = builtinBrands();
        applyLocalStatus(brands);
        SiteConfig config = configurationSite();
        if (config == null) {
            loadProxyStatus(brands, onLoaded);
            return;
        }
        category(config, "settings", onConfig -> {
            applyConfig(brands, onConfig);
            loadProxyStatus(brands, onLoaded);
        }, error -> {
            loadProxyStatus(brands, onLoaded);
        });
    }

    /**
     * Loads only the information needed to decide whether drive-backed play
     * groups can be shown. The settings category is intentionally skipped so
     * opening a detail page does not wait for configuration card metadata.
     */
    public void loadLoginState(Consumer<List<Brand>> onLoaded,
                               Consumer<Throwable> onError) {
        List<Brand> brands = builtinBrands();
        applyLocalStatus(brands);
        if (bridgeSite() == null) {
            markStatusUnknown(brands, null);
            onLoaded.accept(brands);
            return;
        }
        loadProxyStatus(brands, onLoaded);
    }

    public void login(Brand brand, Consumer<String> onOpened, Consumer<Throwable> onError) {
        SiteConfig config = configurationSite();
        if (config == null) {
            onError.accept(new IllegalStateException("配置中心不可用"));
            return;
        }
        category(config, brand.id, json -> {
            JsonArray list = parseArray(json);
            if (list == null || list.isEmpty()) {
                onError.accept(new IllegalStateException(brand.name + "没有登录入口"));
                return;
            }
            List<LoginAction> actions = new ArrayList<>();
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                JsonObject vod = element.getAsJsonObject();
                String id = string(vod, "vod_id");
                String title = string(vod, "vod_name");
                if (id.isEmpty() || title.isEmpty()) continue;
                actions.add(new LoginAction(title, id, string(vod, "vod_remarks")));
            }
            brand.actions = actions;
            SpiderRuntime.get().call("detail", args(config), brand.id).whenComplete((detail, error) -> {
                if (error != null) onError.accept(error);
                else onOpened.accept(brand.id);
            });
        }, onError);
    }

    public void perform(Brand brand, LoginAction action, Consumer<String> onResult, Consumer<Throwable> onError) {
        SiteConfig config = configurationSite();
        if (config == null) {
            onError.accept(new IllegalStateException("配置中心不可用"));
            return;
        }
        SpiderRuntime.get().call("detail", args(config), action.id).whenComplete((detail, detailError) -> {
            if (detailError != null) {
                onError.accept(detailError);
                return;
            }
            SpiderRuntime.get().call("action", args(config), action.id).whenComplete((value, actionError) -> {
                if (actionError != null) onError.accept(actionError);
                else onResult.accept(value == null ? "" : value);
            });
        });
    }

    public String scanProvider(Brand brand) {
        if (brand == null) return null;
        return switch (brand.id == null ? "" : brand.id) {
            case "quark", "baidu", "aliyun" -> brand.id;
            default -> null;
        };
    }

    public void startScanLogin(String provider, Consumer<ScanLogin> onStarted,
                               Consumer<Throwable> onError) {
        if ("quark".equals(provider)) {
            quarkScanLogin.start(onStarted, onError);
            return;
        }
        if ("baidu".equals(provider)) {
            baiduScanLogin.start(onStarted, onError);
            return;
        }
        if ("aliyun".equals(provider)) {
            aliyunScanLogin.start(onStarted, onError);
            return;
        }
        ensureProxyReady(() -> executeProxy(Map.of(
                "action", "loginStart",
                "provider", provider
        ), object -> {
            String taskId = string(object, "taskId");
            String qrUrl = firstNonEmpty(string(object, "qrUrl"),
                    firstNonEmpty(string(object, "qrImage"),
                            string(object, "qrCode")));
            if (taskId.isEmpty() || qrUrl.isEmpty()) {
                onError.accept(new IllegalStateException(
                        stringOrDefault(object, "msg", "登录二维码生成失败")));
                return;
            }
            onStarted.accept(new ScanLogin(taskId, qrUrl,
                    stringOrDefault(object, "msg", "请使用手机 App 扫码确认"),
                    isQrImage(object, qrUrl)));
        }, onError), onError);
    }

    public void pollScanLogin(String provider, String taskId,
                              Consumer<ScanStatus> onStatus,
                              Consumer<Throwable> onError) {
        if ("quark".equals(provider)) {
            quarkScanLogin.poll(taskId, result -> {
                if ("success".equals(result.status) && result.cookie != null) {
                    try {
                        PanCredentialStore.saveQuarkCookie(result.cookie);
                    } catch (Throwable error) {
                        onError.accept(error);
                        return;
                    }
                    SpiderRuntime.get().reset().whenComplete((ignored, resetError) -> {
                        if (resetError != null) {
                            Log.w(TAG, "Spider reset after Quark login failed: "
                                    + rootMessage(resetError));
                        }
                        onStatus.accept(new ScanStatus(
                                result.status, result.message, result.terminal));
                    });
                    return;
                }
                onStatus.accept(new ScanStatus(
                        result.status, result.message, result.terminal));
            }, onError);
            return;
        }
        if ("baidu".equals(provider)) {
            baiduScanLogin.poll(taskId, result -> {
                if ("success".equals(result.status) && result.cookie != null) {
                    try {
                        PanCredentialStore.saveBaiduCookie(result.cookie);
                    } catch (Throwable error) {
                        onError.accept(error);
                        return;
                    }
                    finishNativeLogin(new ScanStatus(
                                    result.status, result.message, result.terminal),
                            onStatus, onError);
                    return;
                }
                onStatus.accept(new ScanStatus(
                        result.status, result.message, result.terminal));
            }, onError);
            return;
        }
        if ("aliyun".equals(provider)) {
            aliyunScanLogin.poll(taskId, result -> {
                if ("success".equals(result.status) && result.auth != null) {
                    try {
                        PanCredentialStore.saveAliyunCredential(
                                result.auth.cookie,
                                result.auth.refreshToken,
                                result.auth.accessToken,
                                result.auth.nickname);
                    } catch (Throwable error) {
                        onError.accept(error);
                        return;
                    }
                    finishNativeLogin(new ScanStatus(
                                    result.status, result.message, result.terminal),
                            onStatus, onError);
                    return;
                }
                onStatus.accept(new ScanStatus(
                        result.status, result.message, result.terminal));
            }, onError);
            return;
        }
        executeProxy(Map.of(
                "action", "loginPoll",
                "provider", provider,
                "taskId", taskId
        ), object -> onStatus.accept(new ScanStatus(
                string(object, "status"),
                stringOrDefault(object, "msg", "等待扫码确认"),
                bool(object, "terminal")
        )), onError);
    }

    public void cancelScanLogin(String provider, String taskId) {
        if (provider == null || provider.isBlank() || taskId == null || taskId.isBlank()) return;
        if ("quark".equals(provider)) {
            quarkScanLogin.cancel(taskId);
            return;
        }
        if ("baidu".equals(provider)) {
            baiduScanLogin.cancel(taskId);
            return;
        }
        if ("aliyun".equals(provider)) {
            aliyunScanLogin.cancel(taskId);
            return;
        }
        executeProxy(Map.of(
                "action", "loginCancel",
                "provider", provider,
                "taskId", taskId
        ), ignored -> {
        }, ignored -> {
        });
    }

    public void isScanProviderConfigured(String provider, Consumer<Boolean> onResult,
                                         Consumer<Throwable> onError) {
        if ("quark".equals(provider) || "baidu".equals(provider)
                || "aliyun".equals(provider)) {
            onResult.accept(PanCredentialStore.hasCredential(provider));
            return;
        }
        ensureProxyReady(() -> proxyStatus(
                status -> onResult.accept(scanProviderConfigured(provider, status)),
                onError), onError);
    }

    public void mine(String group, Consumer<List<ContentItem>> onLoaded, Consumer<Throwable> onError) {
        SiteConfig pan = driveSite();
        if (pan == null) {
            onError.accept(new IllegalStateException("配置中没有网盘站点"));
            return;
        }
        category(pan, group, json -> {
            List<ContentItem> items = new ArrayList<>();
            JsonArray list = parseArray(json);
            if (list == null) {
                onLoaded.accept(items);
                return;
            }
            for (JsonElement element : list) {
                if (!element.isJsonObject()) continue;
                JsonObject vod = element.getAsJsonObject();
                String id = string(vod, "vod_id");
                String title = string(vod, "vod_name");
                if (id.isEmpty() || title.isEmpty()) continue;
                items.add(new ContentItem(id, title, string(vod, "type_name"),
                        string(vod, "vod_pic"), string(vod, "vod_remarks"), pan));
            }
            onLoaded.accept(items);
        }, onError);
    }

    private void executeProxy(Map<String, String> parameters,
                              Consumer<JsonObject> success,
                              Consumer<Throwable> failure) {
        executeProxyNow(parameters, success, failure);
    }

    private void executeProxyNow(Map<String, String> parameters,
                                 Consumer<JsonObject> success,
                                 Consumer<Throwable> failure) {
        String action = parameters.getOrDefault("action", "");
        String provider = parameters.getOrDefault("provider", "");
        Log.i(TAG, "Proxy login request: action=" + action + ", provider=" + provider);
        SiteConfig bridge = bridgeSite();
        Map<String, String> request = new HashMap<>(parameters);
        if (bridge != null && bridge.jar != null && !bridge.jar.isBlank()) {
            request.put("jar", bridge.jar);
        }
        SpiderRuntime.get().proxy(proxyQuery(request)).whenComplete((value, error) -> {
            if (error != null) {
                Log.w(TAG, "Login proxy request failed: " + rootMessage(error));
                failure.accept(error);
                return;
            }
            if (value == null || value.isBlank()) {
                failure.accept(new IOException("登录服务没有返回内容"));
                return;
            }
            try {
                JsonElement parsed = JsonParser.parseString(value);
                if (!parsed.isJsonObject()) {
                    failure.accept(new IOException("登录服务返回格式无效"));
                    return;
                }
                JsonObject object = parsed.getAsJsonObject();
                int code = integer(object, "code");
                if (code != 0) {
                    String message = stringOrDefault(object, "msg", "登录服务返回错误 " + code);
                    Log.w(TAG, "Login proxy failed: " + message);
                    failure.accept(new IllegalStateException(message));
                    return;
                }
                Log.i(TAG, "Login proxy response: action=" + action
                        + ", status=" + string(object, "status")
                        + ", terminal=" + bool(object, "terminal"));
                success.accept(object);
            } catch (Throwable parseError) {
                Log.w(TAG, "Login proxy response invalid: " + rootMessage(parseError));
                failure.accept(parseError);
            }
        });
    }

    private static String proxyQuery(Map<String, String> parameters) {
        StringBuilder query = new StringBuilder("do=pan");
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            query.append('&')
                    .append(URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    private void ensureProxyReady(Runnable onReady, Consumer<Throwable> onError) {
        SiteConfig config = bridgeSite();
        if (config == null) {
            onError.accept(new IllegalStateException("当前片源没有可用的网盘组件"));
            return;
        }
        String siteKey = String.valueOf(config.key) + "\u0000" + String.valueOf(config.jar);
        synchronized (PREPARE_LOCK) {
            if (siteKey.equals(preparedSiteKey)) {
                onReady.run();
                return;
            }
        }
        SpiderRuntime.get().prepare(args(config)).whenComplete((ignored, error) -> {
            if (error != null) {
                onError.accept(error);
                return;
            }
            synchronized (PREPARE_LOCK) {
                preparedSiteKey = siteKey;
            }
            onReady.run();
        });
    }

    public SiteConfig site(String key) {
        if (tvbox.getConfig() == null) return null;
        for (SiteConfig site : tvbox.getConfig().sites) {
            if (site.key != null && site.key.equalsIgnoreCase(key)) return site;
        }
        return null;
    }

    private void category(SiteConfig site, String tid, Consumer<String> success, Consumer<Throwable> failure) {
        SpiderRuntime.get().call("category", args(site), categoryArgs(tid))
                .whenComplete((json, error) -> {
                    if (error == null) success.accept(json);
                    else failure.accept(error);
                });
    }

    private String categoryArgs(String tid) {
        JsonObject args = new JsonObject();
        args.addProperty("tid", tid);
        args.addProperty("page", "1");
        args.addProperty("filter", false);
        args.add("extend", new JsonObject());
        return args.toString();
    }

    private void applyConfig(List<Brand> brands, String json) {
        JsonArray list = parseArray(json);
        if (list == null) return;
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            String key = canonicalBrandId(string(vod, "vod_id"));
            String title = string(vod, "vod_name");
            if (key.isEmpty()) continue;
            Brand brand = find(brands, key);
            if (brand == null) {
                String label = cleanBrandName(title, key);
                if (label.isEmpty()) continue;
                brand = new Brand(key, label, label);
                brands.add(brand);
            }
            String status = string(vod, "vod_remarks");
            brand.status = status;
            brand.icon = string(vod, "vod_pic");
            Boolean loggedIn = loginStateFromStatus(status);
            if (PanCredentialStore.hasCredential(key)) {
                brand.loggedIn = true;
                brand.status = "已登录";
            } else if (loggedIn != null) {
                brand.loggedIn = loggedIn;
            }
        }
    }

    private void proxyStatus(Consumer<JsonObject> success, Consumer<Throwable> failure) {
        if (bridgeSite() == null) {
            failure.accept(new IllegalStateException("当前片源没有可用的网盘组件"));
            return;
        }
        executeProxy(Map.of("action", "status"), success, failure);
    }

    private void applyStatus(List<Brand> brands, JsonObject status) {
        Boolean remoteQuark = optionalBool(status, "quarkCookie");
        mark(brands, "quark", effectiveLoginState(
                        PanCredentialStore.hasQuarkCookie(), remoteQuark),
                text(status, "quarkLoginText"));
        mark(brands, "uc", bool(status, "ucCookie") || bool(status, "ucTvToken"),
                text(status, "ucCookieLoginText"));
        mark(brands, "baidu", effectiveLoginState(
                        PanCredentialStore.hasBaiduCookie(),
                        optionalBool(status, "baiduCookie")),
                text(status, "baiduLoginText"));
        mark(brands, "pan139", bool(status, "pan139Cookie"), text(status, "pan139CookieText"));
        mark(brands, "thunder", bool(status, "thunderConfigured"), text(status, "thunderLoginText"));
        mark(brands, "guangya", bool(status, "guangYaToken"), text(status, "guangYaLoginText"));
        mark(brands, "pan123", bool(status, "pan123Configured"), text(status, "pan123LoginText"));
        mark(brands, "pan115", bool(status, "pan115Cookie"), text(status, "pan115LoginText"));
        mark(brands, "pan189", bool(status, "pan189Cookie"), text(status, "pan189CookieText"));
        mark(brands, "bili", bool(status, "biliCookie"), text(status, "biliLoginText"));
        mark(brands, "diy", bool(status, "alistUrl") || bool(status, "webDavUrl")
                || bool(status, "diyVodUrl"), "AList / WebDav / VodDIY");
        Boolean aliyun = optionalBoolAny(status, "aliyunCookie", "aliyunToken",
                "aliyunDriveToken", "alipanCookie", "aliyundriveCookie");
        mark(brands, "aliyun", effectiveLoginState(
                        PanCredentialStore.hasAliyunCredential(), aliyun),
                firstNonEmpty(text(status, "aliyunLoginText"),
                        text(status, "alipanLoginText")));
    }

    private static boolean scanProviderConfigured(String provider, JsonObject status) {
        if (provider == null) return false;
        return switch (provider) {
            case "quark" -> bool(status, "quarkCookie");
            case "ucCookie" -> bool(status, "ucCookie") || bool(status, "ucTvToken");
            case "baidu" -> bool(status, "baiduCookie");
            case "pan115" -> bool(status, "pan115Cookie");
            case "pan189" -> bool(status, "pan189Cookie")
                    || bool(status, "pan189RefreshCookie")
                    || bool(status, "pan189Account");
            case "bili" -> bool(status, "biliCookie");
            case "guangya" -> bool(status, "guangYaToken");
            default -> false;
        };
    }

    private void mark(List<Brand> brands, String id, boolean loggedIn, String remark) {
        Brand brand = find(brands, id);
        if (brand == null) return;
        brand.loggedIn = loggedIn;
        brand.status = loggedIn ? "已配置" : "未登录";
        if (remark != null && !remark.isEmpty()) brand.remark = remark;
    }

    private void markStatusUnknown(List<Brand> brands, Throwable error) {
        if (error == null) return;
        for (Brand brand : brands) {
            if (brand.status == null || brand.status.isBlank()
                    || "未登录".equals(brand.status)) {
                brand.status = "状态未知";
            }
            brand.remark = "状态接口暂不可用，仍可通过登录入口配置";
        }
        applyLocalStatus(brands);
    }

    private void applyLocalStatus(List<Brand> brands) {
        for (String provider : new String[]{"quark", "baidu", "aliyun"}) {
            Brand brand = find(brands, provider);
            if (brand == null || !PanCredentialStore.hasCredential(provider)) continue;
            brand.loggedIn = true;
            brand.status = "已登录";
            brand.remark = switch (provider) {
                case "quark" -> "夸克账号已保存在本机";
                case "baidu" -> "百度网盘账号已保存在本机";
                default -> "阿里云盘账号已保存在本机";
            };
        }
    }

    private static boolean effectiveLoginState(boolean local, Boolean remote) {
        return local || Boolean.TRUE.equals(remote);
    }

    private void loadProxyStatus(List<Brand> brands, Consumer<List<Brand>> onLoaded) {
        ensureProxyReady(
                () -> proxyStatus(status -> {
                    applyStatus(brands, status);
                    onLoaded.accept(brands);
                }, error -> {
                    markStatusUnknown(brands, error);
                    onLoaded.accept(brands);
                }),
                error -> {
                    markStatusUnknown(brands, error);
                    onLoaded.accept(brands);
                });
    }

    private void finishNativeLogin(ScanStatus status,
                                   Consumer<ScanStatus> onStatus,
                                   Consumer<Throwable> onError) {
        SpiderRuntime.get().reset().whenComplete((ignored, resetError) -> {
            if (resetError != null) {
                Log.w(TAG, "Spider reset after native pan login failed: "
                        + rootMessage(resetError));
            }
            onStatus.accept(status);
        });
    }

    private SiteConfig configurationSite() {
        if (tvbox.getConfig() == null) return null;
        String[] preferredKeys = {"Wexconfig", "MDrive", "PanConfigGuard"};
        for (String key : preferredKeys) {
            SiteConfig exact = site(key);
            if (exact != null) return exact;
        }
        for (SiteConfig candidate : tvbox.getConfig().sites) {
            if (candidate != null && isConfigurationSite(candidate)) return candidate;
        }
        return null;
    }

    private SiteConfig driveSite() {
        SiteConfig exact = site(PAN_SITE);
        if (exact != null) return exact;
        if (tvbox.getConfig() == null) return null;
        for (SiteConfig candidate : tvbox.getConfig().sites) {
            if (candidate != null && includesDriveConfig(candidate)) return candidate;
        }
        return null;
    }

    private SiteConfig bridgeSite() {
        SiteConfig config = configurationSite();
        if (config != null) return config;
        SiteConfig drive = driveSite();
        if (drive != null) return drive;
        if (tvbox.getConfig() == null) return null;
        for (SiteConfig candidate : tvbox.getConfig().sites) {
            if (candidate != null && candidate.isSpider()
                    && candidate.jar != null && !candidate.jar.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean includesDriveConfig(SiteConfig site) {
        String api = site.api == null ? "" : site.api.toLowerCase(java.util.Locale.ROOT);
        String ext = site.ext == null ? "" : site.ext.toLowerCase(java.util.Locale.ROOT);
        return api.contains("mydriveguard")
                || api.contains("panconfigguard")
                || api.contains("wexconfig")
                || ext.contains("cloud-drive");
    }

    private static boolean isConfigurationSite(SiteConfig site) {
        String api = site.api == null ? "" : site.api.toLowerCase(Locale.ROOT);
        return api.contains("wexconfig")
                || api.contains("mydriveguard")
                || api.contains("panconfigguard")
                || api.contains("mypanguard");
    }

    private static boolean bool(JsonObject object, String key) {
        Boolean value = optionalBool(object, key);
        return value != null && value;
    }

    private static Boolean optionalBool(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? null : value.getAsBoolean();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean optionalBoolAny(JsonObject object, String... keys) {
        for (String key : keys) {
            Boolean value = optionalBool(object, key);
            if (value != null) return value;
        }
        return null;
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static boolean isQrImage(JsonObject object, String qrUrl) {
        String type = string(object, "qrType");
        if ("image".equalsIgnoreCase(type)) return true;
        if (!string(object, "qrImage").isBlank()
                && string(object, "qrImage").equals(qrUrl)) {
            return true;
        }
        String normalized = qrUrl == null ? "" : qrUrl.toLowerCase(Locale.ROOT);
        return normalized.startsWith("data:image/")
                || normalized.matches(".*\\.(?:png|jpe?g|webp|gif)(?:[?#].*)?$");
    }

    private static String text(JsonObject object, String key) {
        return string(object, key);
    }

    private static int integer(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? 0 : value.getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String stringOrDefault(JsonObject object, String key, String fallback) {
        String value = string(object, key);
        return value.isEmpty() ? fallback : value;
    }

    private Brand find(List<Brand> brands, String key) {
        for (Brand brand : brands) if (brand.id.equals(key)) return brand;
        return null;
    }

    private List<Brand> builtinBrands() {
        List<Brand> result = new ArrayList<>();
        for (int index = 0; index < NAMES.length; index++) {
            result.add(new Brand(NAMES[index], LABELS[index], MARKS[index]));
        }
        return result;
    }

    public static Brand standaloneBrand(String id) {
        String key = id == null || id.isBlank() ? "quark" : id;
        key = canonicalBrandId(key);
        for (int index = 0; index < NAMES.length; index++) {
            if (!NAMES[index].equals(key)) continue;
            return new Brand(key, LABELS[index], MARKS[index]);
        }
        String label = cleanBrandName("", key);
        return new Brand(key, label, label);
    }

    public static String brandForSource(TvBoxRepository.PlayGroup group) {
        if (group == null) return "";
        StringBuilder source = new StringBuilder(group.flag == null ? "" : group.flag);
        int episodeCount = 0;
        for (TvBoxRepository.Episode episode : group.episodes) {
            if (episode == null) continue;
            source.append(' ').append(episode.name).append(' ').append(episode.id);
            if (++episodeCount >= 2) break;
        }
        return brandForSourceText(source.toString());
    }

    public static String brandForSourceText(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (value.contains("夸克") || value.contains("夸父") || value.contains("quark")) {
            return "quark";
        }
        if (value.contains("阿里云盘") || value.contains("阿里云")
                || value.contains("alipan") || value.contains("aliyundrive")
                || value.contains("aliyun") || value.contains("openapi.alipan")) {
            return "aliyun";
        }
        if (value.contains("uc浏览器") || value.contains("uc网盘") || value.contains("uc盘")
                || java.util.regex.Pattern.compile("(^|[^a-z])uc([^a-z]|$)")
                .matcher(value).find()) return "uc";
        if (value.contains("百度网盘") || value.contains("百度云")) return "baidu";
        if (value.contains("139云盘") || value.contains("139云") || value.contains("移动云盘")) {
            return "pan139";
        }
        if (value.contains("迅雷")) return "thunder";
        if (value.contains("光鸭")) return "guangya";
        if (value.contains("123云盘") || value.contains("123盘")
                || value.contains("123pan")) return "pan123";
        if (value.contains("115网盘") || value.contains("115生活")) return "pan115";
        if (value.contains("天翼") || value.contains("189云盘")) return "pan189";
        if (value.contains("哔哩") || value.contains("bili")) return "bili";
        if (value.contains("alist") || value.contains("webdav")
                || value.contains("自定义网盘")) return "diy";
        return "";
    }

    private static String canonicalBrandId(String value) {
        String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "alipan", "aliyundrive", "aliyunpan", "aliyun_drive" -> "aliyun";
            case "quarkpan", "quark_drive" -> "quark";
            case "baidupan", "baidu_drive" -> "baidu";
            case "ucpan", "uc_drive" -> "uc";
            default -> key;
        };
    }

    private static String cleanBrandName(String title, String key) {
        String value = title == null ? "" : title
                .replaceAll("[\\p{So}\\p{Cn}\\p{Cf}]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (!value.isEmpty()) return value;
        if (key == null || key.isBlank()) return "";
        String normalized = key.replace('_', ' ').replace('-', ' ').trim();
        return normalized.isEmpty() ? "" : normalized.substring(0, 1).toUpperCase(Locale.ROOT)
                + normalized.substring(1);
    }

    private static Boolean loginStateFromStatus(String status) {
        String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (value.contains("未登录") || value.contains("未配置") || value.contains("失效")
                || value.contains("过期") || value.contains("点击登录")
                || value.contains("扫码登录")) {
            return false;
        }
        if (value.contains("已登录") || value.contains("已配置") || value.contains("正常")
                || value.contains("有效")) {
            return true;
        }
        return null;
    }

    private JsonArray parseArray(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement list = root.get("list");
            return list != null && list.isJsonArray() ? list.getAsJsonArray() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? "未知错误"
                : String.valueOf(current.getMessage() == null ? current : current.getMessage());
    }

    private static SiteArgs args(SiteConfig site) {
        SiteArgs args = new SiteArgs();
        args.key = site.key;
        args.name = site.name;
        args.api = site.api;
        args.ext = site.ext;
        args.jar = site.jar;
        args.type = site.type;
        return args;
    }

    public static final class Brand {
        public final String id;
        public final String name;
        public final String label;
        public String status = "未登录";
        public String remark = "";
        public String icon = "";
        public boolean loggedIn;
        public List<LoginAction> actions = new ArrayList<>();

        public Brand(String id, String name, String label) {
            this.id = id;
            this.name = name;
            this.label = label;
        }
    }

    public static final class LoginAction {
        public final String title;
        public final String id;
        public final String remark;

        public LoginAction(String title, String id, String remark) {
            this.title = title;
            this.id = id;
            this.remark = remark;
        }
    }

    public static final class ScanLogin {
        public final String taskId;
        public final String qrUrl;
        public final String message;
        public final boolean image;

        public ScanLogin(String taskId, String qrUrl, String message) {
            this(taskId, qrUrl, message, false);
        }

        public ScanLogin(String taskId, String qrUrl, String message, boolean image) {
            this.taskId = taskId;
            this.qrUrl = qrUrl;
            this.message = message;
            this.image = image;
        }
    }

    public static final class ScanStatus {
        public final String status;
        public final String message;
        public final boolean terminal;

        public ScanStatus(String status, String message, boolean terminal) {
            this.status = status == null ? "" : status;
            this.message = message == null ? "" : message;
            this.terminal = terminal;
        }

        public boolean succeeded() {
            String value = status.replace("-", "").replace("_", "")
                    .toLowerCase(java.util.Locale.ROOT);
            return "success".equals(value)
                    || "ok".equals(value)
                    || "loggedin".equals(value)
                    || "confirmed".equals(value)
                    || "authorized".equals(value)
                    || "done".equals(value);
        }
    }
}
