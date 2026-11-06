package com.ch4nhm.tvapp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Implements the Aliyun Drive web QR flow directly. Credentials are stored by
 * the app so future TVBox sources can reuse the same account session.
 */
final class AliyunScanLoginClient {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";
    private static final String GENERATE =
            "https://passport.aliyundrive.com/newlogin/qrcode/generate.do";
    private static final String POLL =
            "https://passport.aliyundrive.com/newlogin/qrcode/query.do";
    private static final String TOKEN =
            "https://auth.aliyundrive.com/v2/account/token";
    private static final String PASSPORT_REFERER = "https://www.aliyundrive.com/";
    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    private static final long SESSION_TTL_MS = 180_000L;

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "aliyun-scan-login");
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
                        .addQueryParameter("appName", "aliyun_drive")
                        .addQueryParameter("fromSite", "52")
                        .addQueryParameter("appEntrance", "web")
                        .addQueryParameter("isMobile", "false")
                        .addQueryParameter("lang", "zh_CN")
                        .addQueryParameter("returnUrl", "")
                        .addQueryParameter("bizParams", "")
                        .addQueryParameter("_bx-v", "2.0.31")
                        .build();
                HttpResult response = get(url.toString());
                QrCode qr = parseQr(response.body);
                sessions.put(qr.taskId, new Session(
                        qr.taskId, qr.ck, System.currentTimeMillis()));
                success.accept(new PanRepository.ScanLogin(
                        qr.taskId, qr.content, qr.message, false));
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
                HttpResult response = postPoll(session);
                PollState state = parsePoll(response.body);
                if ("CONFIRMED".equals(state.status)) {
                    AuthResult auth = completeLogin(state, response.setCookies);
                    if (!sessions.remove(taskId, session)) {
                        success.accept(new PollResult(
                                "cancelled", "登录已取消", true, null));
                        return;
                    }
                    success.accept(new PollResult(
                            "success", "登录成功，正在继续播放", true, auth));
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

    private AuthResult completeLogin(PollState state, List<String> setCookies)
            throws IOException {
        BizExt biz = parseBizExt(state.bizExt);
        String refreshToken = biz.refreshToken;
        String accessToken = "";
        String nickname = biz.nickname;
        String responseCookie = cookieHeader(setCookies);
        if (!refreshToken.isBlank()) {
            try {
                TokenResult token = exchangeToken(refreshToken);
                if (!token.refreshToken.isBlank()) refreshToken = token.refreshToken;
                accessToken = token.accessToken;
                if (nickname.isBlank()) nickname = token.nickname;
                responseCookie = mergeCookies(responseCookie,
                        cookieHeader(token.setCookies));
            } catch (IOException ignored) {
                // A refresh token is still enough for the source to finish login.
            }
        }
        String cookie = mergeCookies(responseCookie,
                pair("refresh_token", refreshToken),
                pair("access_token", accessToken),
                pair("token", accessToken));
        if (refreshToken.isBlank() && accessToken.isBlank() && cookie.isBlank()) {
            throw new IOException("阿里云盘登录成功，但没有返回有效凭据");
        }
        return new AuthResult(cookie, refreshToken, accessToken, nickname, biz.avatar);
    }

    private TokenResult exchangeToken(String refreshToken) throws IOException {
        String payload = "{\"grant_type\":\"refresh_token\",\"refresh_token\":"
                + JsonParser.parseString(quote(refreshToken)) + "}";
        Request request = new Request.Builder()
                .url(TOKEN)
                .header("User-Agent", USER_AGENT)
                .header("Referer", PASSPORT_REFERER)
                .header("Origin", "https://www.aliyundrive.com")
                .post(RequestBody.create(payload, JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("阿里云盘令牌刷新失败（" + response.code() + "）");
            }
            JsonObject root = parseObject(body);
            return new TokenResult(
                    string(root, "access_token"),
                    string(root, "refresh_token"),
                    firstNonBlank(string(root, "nick_name"),
                            string(root, "user_name")),
                    response.headers("Set-Cookie"));
        }
    }

    private HttpResult postPoll(Session session) throws IOException {
        HttpUrl url = HttpUrl.get(POLL).newBuilder()
                .addQueryParameter("appName", "aliyun_drive")
                .addQueryParameter("fromSite", "52")
                .addQueryParameter("_bx-v", "2.0.31")
                .build();
        RequestBody body = new okhttp3.FormBody.Builder()
                .add("t", session.token)
                .add("ck", session.ck)
                .add("appName", "aliyun_drive")
                .add("appEntrance", "web")
                .add("isMobile", "false")
                .add("lang", "zh_CN")
                .add("returnUrl", "")
                .add("fromSite", "52")
                .add("bizParams", "")
                .add("navlanguage", "zh-CN")
                .add("navPlatform", "web")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", PASSPORT_REFERER)
                .header("Origin", "https://www.aliyundrive.com")
                .post(body)
                .build();
        return execute(request);
    }

    private HttpResult get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", PASSPORT_REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build();
        return execute(request);
    }

    private HttpResult execute(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new HttpResult(response.code(), body, response.headers("Set-Cookie"));
        }
    }

    static QrCode parseQr(String body) throws IOException {
        JsonObject root = parseObject(body);
        JsonObject data = nestedObject(root, "content", "data");
        String token = string(data, "t");
        String ck = string(data, "ck");
        String content = string(data, "codeContent");
        if (token.isBlank() || ck.isBlank() || content.isBlank()) {
            throw new IOException("阿里云盘二维码生成失败");
        }
        return new QrCode(token, ck, content,
                "请使用阿里云盘 App 扫描二维码");
    }

    static PollState parsePoll(String body) throws IOException {
        JsonObject root = parseObject(body);
        JsonObject data = nestedObject(root, "content", "data");
        String status = firstNonBlank(
                string(data, "qrCodeStatus"),
                string(data, "status")).toUpperCase(Locale.ROOT);
        String bizExt = string(data, "bizExt");
        return switch (status) {
            case "CONFIRMED" -> new PollState(
                    status, "已确认登录，正在获取账号信息",
                    true, bizExt);
            case "SCANED" -> new PollState(
                    status, "已扫码，请在手机上确认登录",
                    false, bizExt);
            case "EXPIRED" -> new PollState(
                    status, "二维码已过期，请刷新后重试",
                    true, bizExt);
            case "CANCELED" -> new PollState(
                    status, "已在手机上取消登录",
                    true, bizExt);
            default -> new PollState(
                    "NEW", "等待手机扫码并确认登录",
                    false, bizExt);
        };
    }

    static BizExt parseBizExt(String raw) throws IOException {
        if (raw == null || raw.isBlank()) return new BizExt("", "", "");
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(raw),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            try {
                decoded = new String(Base64.getUrlDecoder().decode(raw),
                        StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                throw new IOException("阿里云盘登录信息格式无效", error);
            }
        }
        JsonObject root = parseObject(decoded);
        JsonObject result = root.has("pds_login_result")
                ? object(root, "pds_login_result") : root;
        return new BizExt(
                firstNonBlank(string(result, "refreshToken"),
                        string(root, "refreshToken")),
                firstNonBlank(string(result, "nickName"),
                        string(result, "nickname")),
                string(result, "avatar"));
    }

    private static JsonObject parseObject(String body) throws IOException {
        try {
            JsonElement element = JsonParser.parseString(body == null ? "" : body);
            if (!element.isJsonObject()) throw new IOException("登录服务返回格式无效");
            return element.getAsJsonObject();
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("登录服务返回格式无效", error);
        }
    }

    private static JsonObject nestedObject(JsonObject object, String... path) {
        JsonElement current = object;
        for (String key : path) {
            if (current == null || !current.isJsonObject()) return new JsonObject();
            current = current.getAsJsonObject().get(key);
        }
        return current != null && current.isJsonObject()
                ? current.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject()
                ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String key) {
        try {
            JsonElement value = object == null ? null : object.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String pair(String name, String value) {
        return value == null || value.isBlank() ? "" : name + "=" + value.trim();
    }

    private static String mergeCookies(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (result.length() > 0) result.append("; ");
            result.append(value.trim());
        }
        return result.toString();
    }

    private static String cookieHeader(List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return "";
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
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
        List<String> parts = new ArrayList<>(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join("; ", parts);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    record QrCode(String taskId, String ck, String content, String message) {
    }

    record PollState(String status, String message, boolean terminal,
                     String bizExt) {
    }

    record BizExt(String refreshToken, String nickname, String avatar) {
    }

    static final class AuthResult {
        final String cookie;
        final String refreshToken;
        final String accessToken;
        final String nickname;
        final String avatar;

        AuthResult(String cookie, String refreshToken, String accessToken,
                   String nickname, String avatar) {
            this.cookie = cookie == null ? "" : cookie;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
            this.accessToken = accessToken == null ? "" : accessToken;
            this.nickname = nickname == null ? "" : nickname;
            this.avatar = avatar == null ? "" : avatar;
        }
    }

    static final class PollResult {
        final String status;
        final String message;
        final boolean terminal;
        final AuthResult auth;

        PollResult(String status, String message, boolean terminal, AuthResult auth) {
            this.status = status == null ? "" : status;
            this.message = message == null ? "" : message;
            this.terminal = terminal;
            this.auth = auth;
        }
    }

    private record Session(String token, String ck, long createdAtMs) {
    }

    private record HttpResult(int status, String body, List<String> setCookies) {
    }

    private record TokenResult(String accessToken, String refreshToken,
                               String nickname, List<String> setCookies) {
    }
}
