package com.ch4nhm.tvapp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Implements Baidu Passport QR login directly, so the result does not depend
 * on whichever TVBox source happens to be selected.
 */
final class BaiduScanLoginClient {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";
    private static final String GENERATE =
            "https://passport.baidu.com/v2/api/getqrcode";
    private static final String POLL =
            "https://passport.baidu.com/channel/unicast";
    private static final String PASSPORT = "https://passport.baidu.com/";
    private static final long SESSION_TTL_MS = 180_000L;

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "baidu-scan-login");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    void start(Consumer<PanRepository.ScanLogin> success, Consumer<Throwable> failure) {
        executor.execute(() -> {
            try {
                HttpUrl url = HttpUrl.get(GENERATE).newBuilder()
                        .addQueryParameter("lp", "pc")
                        .addQueryParameter("qrloginfrom", "pc")
                        .addQueryParameter("gid", UUID.randomUUID()
                                .toString().replace("-", ""))
                        .addQueryParameter("apiver", "v3")
                        .addQueryParameter("tt",
                                String.valueOf(System.currentTimeMillis()))
                        .addQueryParameter("tpl", "netdisk")
                        .build();
                HttpResult response = get(url.toString(), "", PASSPORT);
                QrCode qr = parseQr(response.body);
                sessions.put(qr.sign, new Session(qr.sign, System.currentTimeMillis()));
                success.accept(new PanRepository.ScanLogin(
                        qr.sign, qr.imageUrl, qr.message, true));
            } catch (Throwable error) {
                failure.accept(error);
            }
        });
    }

    void poll(String taskId, Consumer<PollResult> success, Consumer<Throwable> failure) {
        executor.execute(() -> {
            Session session = sessions.get(taskId);
            if (session == null) {
                failure.accept(new IllegalStateException("二维码已失效，请刷新后重试"));
                return;
            }
            if (System.currentTimeMillis() - session.createdAtMs > SESSION_TTL_MS) {
                sessions.remove(taskId, session);
                success.accept(new PollResult(
                        "expired", "二维码已过期，请刷新后重试", true, null));
                return;
            }
            try {
                HttpUrl url = HttpUrl.get(POLL).newBuilder()
                        .addQueryParameter("channel_id", session.sign)
                        .addQueryParameter("tpl", "netdisk")
                        .addQueryParameter("apiver", "v3")
                        .addQueryParameter("tt",
                                String.valueOf(System.currentTimeMillis()))
                        .addQueryParameter("callback", "")
                        .build();
                HttpResult response = get(url.toString(), "", PASSPORT);
                PollState state = parsePoll(response.body);
                if (state.confirmed && !state.loginUrl.isBlank()) {
                    String cookie = completeLogin(state.loginUrl);
                    if (!sessions.remove(taskId, session)) {
                        success.accept(new PollResult(
                                "cancelled", "登录已取消", true, null));
                        return;
                    }
                    success.accept(new PollResult(
                            "success", "登录成功，正在继续播放", true, cookie));
                    return;
                }
                if (state.terminal) sessions.remove(taskId, session);
                success.accept(new PollResult(
                        state.terminal ? "expired" : state.status,
                        state.message, state.terminal, null));
            } catch (Throwable error) {
                failure.accept(error);
            }
        });
    }

    void cancel(String taskId) {
        if (taskId != null) sessions.remove(taskId);
    }

    private String completeLogin(String loginUrl) throws IOException {
        CookieJar cookies = new CookieJar();
        HttpResult result = follow(loginUrl, cookies, 10);
        cookies.add(result.setCookies);
        if (!cookies.contains("BDUSS")) {
            String followUp = "https://pan.baidu.com/";
            result = follow(followUp, cookies, 8);
            cookies.add(result.setCookies);
        }
        String cookie = cookies.header();
        if (!cookies.contains("BDUSS")) {
            throw new IOException("百度登录成功，但没有返回有效 Cookie");
        }
        return cookie;
    }

    private HttpResult follow(String startUrl, CookieJar cookies, int maxRedirects)
            throws IOException {
        String current = startUrl;
        HttpResult result = null;
        for (int index = 0; index <= maxRedirects; index++) {
            result = get(current, cookies.header(), PASSPORT);
            cookies.add(result.setCookies);
            if (!isRedirect(result.status)) return result;
            String location = result.location;
            if (location == null || location.isBlank()) return result;
            HttpUrl base = HttpUrl.parse(current);
            HttpUrl next = base == null ? HttpUrl.parse(location) : base.resolve(location);
            if (next == null) throw new IOException("百度登录跳转地址无效");
            current = next.toString();
        }
        throw new IOException("百度登录跳转次数过多");
    }

    private HttpResult get(String url, String cookie, String referer) throws IOException {
        Request.Builder request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        if (cookie != null && !cookie.isBlank()) request.header("Cookie", cookie);
        try (Response response = client.newCall(request.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new HttpResult(response.code(), body,
                    response.headers("Set-Cookie"), response.header("Location"));
        }
    }

    static QrCode parseQr(String body) throws IOException {
        JsonObject root = parseObject(body);
        int code = integer(root, "errno");
        String sign = string(root, "sign");
        String imageUrl = string(root, "imgurl");
        if (code != 0 || sign.isBlank() || imageUrl.isBlank()) {
            throw new IOException(nonBlank(string(root, "errmsg"),
                    "百度网盘二维码生成失败"));
        }
        String normalizedImage = imageUrl.startsWith("http://")
                || imageUrl.startsWith("https://")
                ? imageUrl : "https://" + imageUrl;
        String message = nonBlank(string(root, "prompt"),
                "请使用百度网盘 App 扫描二维码");
        return new QrCode(sign, normalizedImage, message);
    }

    static PollState parsePoll(String body) throws IOException {
        JsonObject root = parseObject(body);
        JsonObject channel = channelObject(root.get("channel_v"));
        if (channel == null || channel.isEmpty()) {
            int errno = integer(root, "errno");
            String message = errno == 1
                    ? "等待手机扫码并确认登录"
                    : nonBlank(string(root, "errmsg"),
                    "等待手机扫码并确认登录");
            return new PollState("waiting", message, false, false, "");
        }
        int status = integer(channel, "status");
        String loginUrl = string(channel, "v").replace("&amp;", "&");
        if (status == 0 && !loginUrl.isBlank()) {
            return new PollState("confirmed", "已确认登录，正在获取账号信息",
                    true, true, loginUrl);
        }
        if (status == 1) {
            return new PollState("scaned", "已扫码，请在手机上确认登录",
                    false, false, "");
        }
        if (status == 2) {
            return new PollState("expired", "二维码已过期，请刷新后重试",
                    true, false, "");
        }
        String message = string(channel, "msg");
        return new PollState("waiting",
                nonBlank(message, "等待手机扫码并确认登录"),
                false, false, "");
    }

    private static JsonObject channelObject(JsonElement value) throws IOException {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) return value.getAsJsonObject();
        if (!value.isJsonPrimitive()) return null;
        String text = value.getAsString();
        if (text.isBlank()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Throwable error) {
            throw new IOException("百度登录状态格式无效", error);
        }
    }

    private static JsonObject parseObject(String body) throws IOException {
        String value = body == null ? "" : body.trim();
        if (value.contains("(") && value.endsWith(")")) {
            value = value.substring(value.indexOf('(') + 1, value.length() - 1);
        }
        try {
            JsonElement element = JsonParser.parseString(value);
            if (!element.isJsonObject()) throw new IOException("登录服务返回格式无效");
            return element.getAsJsonObject();
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("登录服务返回格式无效", error);
        }
    }

    private static int integer(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? 0 : value.getAsInt();
        } catch (Exception ignored) {
            return 0;
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

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    record QrCode(String sign, String imageUrl, String message) {
    }

    record PollState(String status, String message, boolean terminal,
                     boolean confirmed, String loginUrl) {
    }

    static final class PollResult {
        final String status;
        final String message;
        final boolean terminal;
        final String cookie;

        PollResult(String status, String message, boolean terminal, String cookie) {
            this.status = status == null ? "" : status;
            this.message = message == null ? "" : message;
            this.terminal = terminal;
            this.cookie = cookie;
        }
    }

    private record Session(String sign, long createdAtMs) {
    }

    private record HttpResult(int status, String body, List<String> setCookies,
                              String location) {
    }

    private static final class CookieJar {
        private final LinkedHashMap<String, String> values = new LinkedHashMap<>();

        void add(List<String> setCookies) {
            if (setCookies == null) return;
            for (String raw : setCookies) {
                if (raw == null || raw.isBlank()) continue;
                String pair = raw.split(";", 2)[0].trim();
                int separator = pair.indexOf('=');
                if (separator <= 0) continue;
                String key = pair.substring(0, separator).trim();
                String value = pair.substring(separator + 1).trim();
                if (key.isEmpty()) continue;
                if (value.isEmpty()) values.remove(key);
                else values.put(key, value);
            }
        }

        boolean contains(String key) {
            return values.containsKey(key);
        }

        String header() {
            List<String> parts = new ArrayList<>(values.size());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                parts.add(entry.getKey() + "=" + entry.getValue());
            }
            return String.join("; ", parts);
        }
    }
}
