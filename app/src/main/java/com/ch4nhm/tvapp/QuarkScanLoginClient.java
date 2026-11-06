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
 * Implements Quark's QR login protocol directly, independent of a TVBox source.
 */
final class QuarkScanLoginClient {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 "
                    + "Electron/18.3.5.4-b478491100 Safari/537.36 "
                    + "Channel/pckk_other_ch";
    private static final String UOP_TOKEN =
            "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin";
    private static final String UOP_POLL =
            "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken";
    private static final String QR_BASE = "https://su.quark.cn/4_eMHBJ";

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "quark-scan-login");
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
                String requestId = UUID.randomUUID().toString();
                HttpUrl url = HttpUrl.get(UOP_TOKEN).newBuilder()
                        .addQueryParameter("client_id", "532")
                        .addQueryParameter("v", "1.2")
                        .addQueryParameter("request_id", requestId)
                        .build();
                HttpResult result = get(url.toString(), "");
                JsonObject root = parseObject(result.body);
                int status = integer(root, "status");
                String token = nestedString(root, "data", "members", "token");
                if (status != 2000000 || token.isBlank()) {
                    throw new IOException(nonBlank(rootString(root, "message"),
                            "夸克二维码生成失败"));
                }
                sessions.put(token, new Session(token, System.currentTimeMillis()));
                HttpUrl qrUrl = HttpUrl.get(QR_BASE).newBuilder()
                        .addQueryParameter("token", token)
                        .addQueryParameter("client_id", "532")
                        .addQueryParameter("ssb", "weblogin")
                        .addQueryParameter("uc_param_str", "")
                        .addQueryParameter("uc_biz_str",
                                "S:customOptions|enableNocaptcha|true")
                        .build();
                success.accept(new PanRepository.ScanLogin(
                        token, qrUrl.toString(), "请使用夸克 App 扫描二维码"));
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
            if (System.currentTimeMillis() - session.createdAtMs > 120_000L) {
                sessions.remove(taskId, session);
                success.accept(new PollResult(
                        "expired", "二维码已过期，请刷新后重试", true, null));
                return;
            }
            try {
                HttpUrl url = HttpUrl.get(UOP_POLL).newBuilder()
                        .addQueryParameter("client_id", "532")
                        .addQueryParameter("v", "1.2")
                        .addQueryParameter("token", session.token)
                        .addQueryParameter("request_id", UUID.randomUUID().toString())
                        .build();
                HttpResult response = get(url.toString(), "");
                JsonObject root = parseObject(response.body);
                int status = integer(root, "status");
                String serviceTicket = nestedString(
                        root, "data", "members", "service_ticket");
                if (status == 2000000 && !serviceTicket.isBlank()) {
                    CookieResult cookie = exchangeTicket(serviceTicket);
                    if (!sessions.remove(taskId, session)) {
                        success.accept(new PollResult(
                                "cancelled", "登录已取消", true, null));
                        return;
                    }
                    success.accept(new PollResult(
                            "success", "登录成功，正在继续播放", true, cookie.cookie));
                    return;
                }
                String message = friendlyMessage(
                        rootString(root, "message"), status);
                boolean terminal = message.contains("过期")
                        || message.contains("失效")
                        || status == 50004004;
                if (terminal) sessions.remove(taskId, session);
                success.accept(new PollResult(
                        terminal ? "expired" : "waiting", message, terminal, null));
            } catch (Throwable error) {
                failure.accept(error);
            }
        });
    }

    void cancel(String taskId) {
        if (taskId != null) sessions.remove(taskId);
    }

    private CookieResult exchangeTicket(String serviceTicket) throws IOException {
        HttpUrl url = HttpUrl.get("https://pan.quark.cn/account/info").newBuilder()
                .addQueryParameter("st", serviceTicket)
                .addQueryParameter("lw", "scan")
                .build();
        CookieJar cookies = new CookieJar();
        HttpResult account = follow(url.toString(), cookies, 8);
        cookies.add(account.setCookies);
        JsonObject user = parseObjectLenient(account.body);

        String[] completionUrls = {
                "https://pan.quark.cn/list",
                "https://drive-pc.quark.cn/1/clouddrive/file/sort"
                        + "?pr=ucpro&fr=pc&uc_param_str=&pdir_fid=0&_page=1"
                        + "&_size=50&_fetch_total=1&_sort=file_type:asc,updated_at:desc",
                "https://drive.quark.cn/1/clouddrive/member"
                        + "?pr=ucpro&fr=pc&uc_param_str=&fetch_subscribe=true"
        };
        for (String completionUrl : completionUrls) {
            if (cookies.contains("__puus")) break;
            HttpResult completion = get(completionUrl, cookies.header());
            cookies.add(completion.setCookies);
        }

        String cookie = cookies.header();
        if (!cookies.contains("__pus") && !cookies.contains("__puus")) {
            throw new IOException("夸克登录成功，但没有返回有效 Cookie");
        }
        String accountName = nestedString(user, "data", "nickname");
        return new CookieResult(cookie, accountName);
    }

    private HttpResult follow(String startUrl, CookieJar cookies, int maxRedirects)
            throws IOException {
        String current = startUrl;
        HttpResult result = null;
        for (int index = 0; index <= maxRedirects; index++) {
            result = get(current, cookies.header());
            cookies.add(result.setCookies);
            if (!isRedirect(result.status)) return result;
            String location = result.location;
            if (location == null || location.isBlank()) return result;
            HttpUrl base = HttpUrl.parse(current);
            HttpUrl next = base == null ? HttpUrl.parse(location) : base.resolve(location);
            if (next == null) throw new IOException("夸克登录跳转地址无效");
            current = next.toString();
        }
        throw new IOException("夸克登录跳转次数过多");
    }

    private HttpResult get(String url, String cookie) throws IOException {
        Request.Builder request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://pan.quark.cn/")
                .header("Origin", "https://pan.quark.cn")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        if (cookie != null && !cookie.isBlank()) {
            request.header("Cookie", cookie);
        }
        try (Response response = client.newCall(request.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return new HttpResult(response.code(), body,
                    response.headers("Set-Cookie"), response.header("Location"));
        }
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
                || status == 307 || status == 308;
    }

    private static JsonObject parseObject(String body) throws IOException {
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) throw new IOException("登录服务返回格式无效");
            return element.getAsJsonObject();
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("登录服务返回格式无效", error);
        }
    }

    private static JsonObject parseObjectLenient(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Throwable ignored) {
            return new JsonObject();
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

    private static String rootString(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String nestedString(JsonObject object, String... path) {
        try {
            JsonElement current = object;
            for (String key : path) {
                if (!current.isJsonObject()) return "";
                current = current.getAsJsonObject().get(key);
                if (current == null || current.isJsonNull()) return "";
            }
            return current.getAsString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String waitingMessage(int status) {
        return switch (status) {
            case 50004001 -> "已扫码，请在手机上确认登录";
            case 50004002 -> "二维码已扫描，等待手机确认";
            default -> "等待手机扫码并确认登录";
        };
    }

    private static String friendlyMessage(String message, int status) {
        String value = message == null ? "" : message.trim();
        if (value.isEmpty()
                || value.toLowerCase(java.util.Locale.ROOT).contains("query result is empty")
                || value.toLowerCase(java.util.Locale.ROOT).contains("query result")) {
            return waitingMessage(status);
        }
        return value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

    private record Session(String token, long createdAtMs) {
    }

    private record CookieResult(String cookie, String accountName) {
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
