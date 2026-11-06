package com.ch4nhm.tvapp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ch4nhm.tvapp.data.ConfigStore;
import com.ch4nhm.tvapp.data.TvBoxConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small token-protected HTTP server used only while the settings screen is
 * visible. It intentionally has no external dependencies so the phone page can
 * work on the same LAN without a cloud relay.
 */
public final class LanSettingsServer {

    public static final String LOCAL_NETWORK_PERMISSION =
            "android.permission.ACCESS_LOCAL_NETWORK";

    private static final String TAG = "LanSettingsServer";
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final LanSettingsServer INSTANCE = new LanSettingsServer();

    private final Object lifecycleLock = new Object();
    private final Object mutationLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService clients = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tvbox-lan-client");
        thread.setDaemon(true);
        return thread;
    });

    private Context appContext;
    private ConfigStore configStore;
    private AppSettings appSettings;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private String token = "";
    private String accessUrl = "";
    private volatile Listener listener;
    private volatile boolean mutationBlocked;

    private LanSettingsServer() {
    }

    public static LanSettingsServer get() {
        return INSTANCE;
    }

    public State start(Context context) throws IOException {
        synchronized (lifecycleLock) {
            if (isRunning()) {
                return new State(accessUrl, token);
            }
            Context application = context.getApplicationContext();
            String address = findLanAddress(application);
            if (address.isBlank()) {
                throw new IOException("没有检测到可用的局域网地址，请确认电视已连接 Wi-Fi 或网线");
            }

            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", 0), 24);
            appContext = application;
            configStore = new ConfigStore(application);
            appSettings = new AppSettings(application);
            serverSocket = socket;
            token = newToken();
            accessUrl = "http://" + address + ":" + socket.getLocalPort()
                    + "/?t=" + token;

            acceptThread = new Thread(this::acceptLoop, "tvbox-lan-settings");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "LAN settings started on port " + socket.getLocalPort());
            return new State(accessUrl, token);
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            listener = null;
            mutationBlocked = false;
            ServerSocket socket = serverSocket;
            serverSocket = null;
            acceptThread = null;
            accessUrl = "";
            token = "";
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public boolean isRunning() {
        synchronized (lifecycleLock) {
            return serverSocket != null && !serverSocket.isClosed();
        }
    }

    public String getAccessUrl() {
        synchronized (lifecycleLock) {
            return accessUrl;
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setMutationBlocked(boolean blocked) {
        mutationBlocked = blocked;
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket socket;
            synchronized (lifecycleLock) {
                socket = serverSocket;
            }
            if (socket == null || socket.isClosed()) return;
            try {
                Socket client = socket.accept();
                clients.execute(() -> handleClient(client));
            } catch (SocketException error) {
                if (isRunning()) Log.w(TAG, "Accept failed", error);
                return;
            } catch (IOException error) {
                if (isRunning()) Log.w(TAG, "Accept failed", error);
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(12_000);
            socket.setTcpNoDelay(true);
            Request request = readRequest(socket.getInputStream());
            if (request == null) {
                sendText(socket, 400, "text/plain; charset=utf-8", "Bad Request");
                return;
            }
            Uri uri = Uri.parse("http://localhost" + request.target);
            String path = uri.getPath() == null ? "/" : uri.getPath();
            if ("/favicon.ico".equals(path)) {
                send(socket, 204, "image/x-icon", new byte[0], "");
                return;
            }
            String suppliedToken = uri.getQueryParameter("t");
            if (!validToken(suppliedToken)) {
                sendJson(socket, 403, error("二维码已失效，请在电视上重新打开手机设置"));
                return;
            }

            if ("GET".equals(request.method) && ("/".equals(path) || "/index.html".equals(path))) {
                sendText(socket, 200, "text/html; charset=utf-8", html());
                return;
            }
            if ("GET".equals(request.method) && "/api/state".equals(path)) {
                sendJson(socket, 200, stateJson());
                return;
            }
            if ("POST".equals(request.method)) {
                handlePost(socket, path, request.body);
                return;
            }
            sendJson(socket, 404, error("接口不存在"));
        } catch (Throwable error) {
            Log.w(TAG, "Request failed", error);
        }
    }

    private void handlePost(Socket socket, String path, byte[] body) throws IOException {
        if (mutationBlocked) {
            sendJson(socket, 409, error("电视正在验证片源，请稍后再试"));
            return;
        }
        JsonObject input;
        try {
            String text = new String(body, StandardCharsets.UTF_8).trim();
            input = text.isEmpty() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
        } catch (Throwable error) {
            sendJson(socket, 400, error("请求内容不是有效的 JSON"));
            return;
        }

        try {
            switch (path) {
                case "/api/source" -> saveSource(socket, input);
                case "/api/source/activate" -> activateSource(socket, input);
                case "/api/source/delete" -> deleteSource(socket, input);
                case "/api/preferences" -> updatePreferences(socket, input);
                case "/api/config/refresh" -> refreshConfig(socket);
                default -> sendJson(socket, 404, error("接口不存在"));
            }
        } catch (IllegalArgumentException error) {
            sendJson(socket, 400, error(error.getMessage()));
        } catch (Throwable error) {
            Log.w(TAG, "Mutation failed: " + path, error);
            sendJson(socket, 500, error("保存失败：" + friendly(error)));
        }
    }

    private void saveSource(Socket socket, JsonObject input) throws IOException {
        String id = string(input, "id");
        String name = string(input, "name");
        String url = string(input, "url");
        String userAgent = string(input, "userAgent");
        boolean activate = bool(input, "activate", false);
        validateSource(name, url, userAgent);

        synchronized (mutationLock) {
            ConfigStore.SourceProfile existing = findSource(id);
            ConfigStore.Snapshot previous = null;
            TvBoxRepository.RuntimeSnapshot previousRuntime = null;
            boolean requiresReload = false;

            if (id.isBlank()) {
                if (activate) {
                    previous = configStore.snapshot();
                    previousRuntime = TvBoxRepository.get().snapshotRuntime();
                }
                ConfigStore.SourceProfile added = configStore.addSource(name, url, userAgent);
                id = added.id;
                if (activate && !configStore.activateSource(id)) {
                    if (previous != null) configStore.restore(previous);
                    throw new IllegalStateException("新片源无法启用");
                }
                requiresReload = activate;
            } else {
                if (existing == null) throw new IllegalArgumentException("片源不存在，请刷新后重试");
                boolean active = id.equals(configStore.getActiveProfileId());
                boolean urlChanged = !existing.url.equals(url.trim());
                if ((active && urlChanged) || (activate && !active)) {
                    previous = configStore.snapshot();
                    previousRuntime = TvBoxRepository.get().snapshotRuntime();
                }
                if (!configStore.updateSource(id, name, url, userAgent)) {
                    throw new IllegalStateException("片源保存失败");
                }
                if (activate && !id.equals(configStore.getActiveProfileId())) {
                    if (!configStore.activateSource(id)) {
                        if (previous != null) configStore.restore(previous);
                        throw new IllegalStateException("片源无法启用");
                    }
                }
                requiresReload = (active && urlChanged) || (activate && !active);
            }

            Change change = new Change(
                    previous, previousRuntime, true, requiresReload, false,
                    activate ? "片源已保存并启用" : "片源已保存");
            dispatch(change);
            JsonObject response = success(change.message);
            response.addProperty("sourceId", id);
            response.addProperty("requiresReload", requiresReload);
            response.add("state", stateJson());
            sendJson(socket, 200, response);
        }
    }

    private void activateSource(Socket socket, JsonObject input) throws IOException {
        String id = string(input, "id");
        if (id.isBlank()) throw new IllegalArgumentException("缺少片源 ID");
        synchronized (mutationLock) {
            ConfigStore.SourceProfile source = findSource(id);
            if (source == null) throw new IllegalArgumentException("片源不存在，请刷新后重试");
            if (id.equals(configStore.getActiveProfileId())) {
                JsonObject response = success("该片源已经启用");
                response.add("state", stateJson());
                sendJson(socket, 200, response);
                return;
            }
            ConfigStore.Snapshot previous = configStore.snapshot();
            TvBoxRepository.RuntimeSnapshot previousRuntime =
                    TvBoxRepository.get().snapshotRuntime();
            if (!configStore.activateSource(id)) {
                throw new IllegalStateException("片源无法启用");
            }
            Change change = new Change(previous, previousRuntime,
                    true, true, false, "片源已启用，电视正在验证");
            dispatch(change);
            JsonObject response = success(change.message);
            response.addProperty("requiresReload", true);
            response.add("state", stateJson());
            sendJson(socket, 200, response);
        }
    }

    private void deleteSource(Socket socket, JsonObject input) throws IOException {
        String id = string(input, "id");
        if (id.isBlank()) throw new IllegalArgumentException("缺少片源 ID");
        synchronized (mutationLock) {
            if (id.equals(configStore.getActiveProfileId())) {
                throw new IllegalArgumentException("请先启用其他片源，再删除当前片源");
            }
            if (!configStore.removeSource(id)) {
                throw new IllegalArgumentException("无法删除该片源");
            }
            Change change = new Change(null, null, true, false, false, "片源已删除");
            dispatch(change);
            JsonObject response = success(change.message);
            response.add("state", stateJson());
            sendJson(socket, 200, response);
        }
    }

    private void updatePreferences(Socket socket, JsonObject input) throws IOException {
        synchronized (mutationLock) {
            String previousTheme = appSettings.getThemeKey();
            boolean changed = false;
            if (input.has("themeKey")) {
                String theme = ThemeManager.normalize(string(input, "themeKey"));
                if (!theme.equals(previousTheme)) {
                    appSettings.setThemeKey(theme);
                    changed = true;
                }
            }
            if (input.has("autoNext")) {
                appSettings.setAutoNextEpisode(bool(input, "autoNext",
                        appSettings.isAutoNextEpisode()));
                changed = true;
            }
            if (input.has("resume")) {
                appSettings.setResumePlayback(bool(input, "resume",
                        appSettings.isResumePlayback()));
                changed = true;
            }
            if (input.has("seekStep")) {
                int value = integer(input, "seekStep", appSettings.getSeekStepSeconds());
                if (!validSeekStep(value)) throw new IllegalArgumentException("快进步长无效");
                appSettings.setSeekStepSeconds(value);
                changed = true;
            }
            if (input.has("videoScale")) {
                int value = integer(input, "videoScale", appSettings.getVideoScale());
                if (value < AppSettings.VIDEO_SCALE_FIT
                        || value > AppSettings.VIDEO_SCALE_STRETCH) {
                    throw new IllegalArgumentException("画面比例无效");
                }
                appSettings.setVideoScale(value);
                changed = true;
            }
            if (input.has("decoderFallback")) {
                appSettings.setDecoderFallback(bool(input, "decoderFallback",
                        appSettings.isDecoderFallback()));
                changed = true;
            }
            boolean themeChanged = !previousTheme.equals(appSettings.getThemeKey());
            Change change = new Change(null, null, false, false, themeChanged,
                    changed ? "设置已保存" : "没有需要保存的更改");
            if (changed) dispatch(change);
            JsonObject response = success(change.message);
            response.addProperty("themeChanged", themeChanged);
            response.add("state", stateJson());
            sendJson(socket, 200, response);
        }
    }

    private void refreshConfig(Socket socket) throws IOException {
        synchronized (mutationLock) {
            ConfigStore.Snapshot previous = configStore.snapshot();
            TvBoxRepository.RuntimeSnapshot previousRuntime =
                    TvBoxRepository.get().snapshotRuntime();
            configStore.invalidate();
            Change change = new Change(previous, previousRuntime,
                    false, true, false, "正在重新加载当前片源");
            dispatch(change);
            JsonObject response = success(change.message);
            response.addProperty("requiresReload", true);
            response.add("state", stateJson());
            sendJson(socket, 200, response);
        }
    }

    private void dispatch(Change change) {
        Listener current = listener;
        mainHandler.post(() -> {
            Listener active = listener;
            if (active != null && active == current) active.onChanged(change);
        });
    }

    private JsonObject stateJson() {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("device", deviceName());
        root.addProperty("mutationBlocked", mutationBlocked);
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            root.addProperty("version", info.versionName);
        } catch (Throwable ignored) {
            root.addProperty("version", "");
        }

        JsonArray sources = new JsonArray();
        String activeId = configStore.getActiveProfileId();
        for (ConfigStore.SourceProfile source : configStore.getSources()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", source.id);
            item.addProperty("name", source.name);
            item.addProperty("url", source.url);
            item.addProperty("userAgent", source.userAgent);
            item.addProperty("active", source.id.equals(activeId));
            sources.add(item);
        }
        root.add("sources", sources);

        JsonObject preferences = new JsonObject();
        preferences.addProperty("themeKey", appSettings.getThemeKey());
        preferences.addProperty("autoNext", appSettings.isAutoNextEpisode());
        preferences.addProperty("resume", appSettings.isResumePlayback());
        preferences.addProperty("seekStep", appSettings.getSeekStepSeconds());
        preferences.addProperty("videoScale", appSettings.getVideoScale());
        preferences.addProperty("decoderFallback", appSettings.isDecoderFallback());
        root.add("preferences", preferences);

        JsonArray themes = new JsonArray();
        for (ThemeManager.Preset preset : ThemeManager.PRESETS) {
            JsonObject item = new JsonObject();
            item.addProperty("key", preset.key);
            item.addProperty("label", preset.label);
            item.addProperty("color", colorHex(ThemeManager.appContextColor(appContext,
                    preset.swatchColor)));
            themes.add(item);
        }
        root.add("themes", themes);

        JsonObject live = new JsonObject();
        TvBoxConfig config = TvBoxRepository.get().getConfig();
        if (config == null) config = configStore.getConfig();
        int liveCount = config == null || config.lives == null ? 0 : config.lives.size();
        live.addProperty("count", liveCount);
        JsonArray names = new JsonArray();
        if (config != null && config.lives != null) {
            for (TvBoxConfig.Live source : config.lives) {
                if (source == null) continue;
                names.add(source.name == null || source.name.isBlank()
                        ? "未命名直播源" : source.name);
            }
        }
        live.add("names", names);
        root.add("live", live);
        return root;
    }

    private ConfigStore.SourceProfile findSource(String id) {
        if (id == null || id.isBlank()) return null;
        for (ConfigStore.SourceProfile source : configStore.getSources()) {
            if (id.equals(source.id)) return source;
        }
        return null;
    }

    private static void validateSource(String name, String url, String userAgent) {
        if (name.length() > 100) throw new IllegalArgumentException("片源名称不能超过 100 个字符");
        if (url.isBlank()) throw new IllegalArgumentException("请填写 TVBox 配置地址");
        if (url.length() > 2048) throw new IllegalArgumentException("配置地址过长");
        Uri parsed = Uri.parse(url.trim());
        String scheme = parsed.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("配置地址必须以 http:// 或 https:// 开头");
        }
        if (userAgent.length() > 1024) {
            throw new IllegalArgumentException("User-Agent 不能超过 1024 个字符");
        }
    }

    private static boolean validSeekStep(int value) {
        for (int option : AppSettings.SEEK_OPTIONS) {
            if (option == value) return true;
        }
        return false;
    }

    private String html() throws IOException {
        try (InputStream input = appContext.getResources().openRawResource(R.raw.lan_settings)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static Request readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) return null;
            headerBytes.write(value);
            if ((matched == 0 || matched == 2) && value == '\r') matched++;
            else if ((matched == 1 || matched == 3) && value == '\n') matched++;
            else matched = value == '\r' ? 1 : 0;
            if (matched == 4) break;
        }
        if (matched != 4) return null;

        String[] lines = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
                .split("\\r\\n");
        if (lines.length == 0) return null;
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        String method = first[0].toUpperCase(Locale.ROOT);
        String target = first[1];
        if (!("GET".equals(method) || "POST".equals(method))) return null;

        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int separator = lines[index].indexOf(':');
            if (separator <= 0) continue;
            headers.put(lines[index].substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    lines[index].substring(separator + 1).trim());
        }
        int contentLength = 0;
        String rawLength = headers.get("content-length");
        if (rawLength != null && !rawLength.isBlank()) {
            try {
                contentLength = Integer.parseInt(rawLength);
            } catch (NumberFormatException error) {
                return null;
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) return null;
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int count = input.read(body, offset, contentLength - offset);
            if (count < 0) return null;
            offset += count;
        }
        return new Request(method, target, body);
    }

    private boolean validToken(String supplied) {
        if (supplied == null || token.isBlank()) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(Socket socket, int status, JsonObject value) throws IOException {
        send(socket, status, "application/json; charset=utf-8",
                value.toString().getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private static void sendText(Socket socket, int status, String contentType, String value)
            throws IOException {
        send(socket, status, contentType, value.getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private static void send(Socket socket, int status, String contentType, byte[] body,
                             String cacheControl) throws IOException {
        String reason = switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            default -> "Internal Server Error";
        };
        OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "X-Frame-Options: DENY\r\n"
                + "Content-Security-Policy: default-src 'self'; "
                + "style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
                + "img-src 'self' data:; connect-src 'self'; "
                + "base-uri 'none'; frame-ancestors 'none'\r\n"
                + (cacheControl.isBlank() ? "" : "Cache-Control: " + cacheControl + "\r\n")
                + "\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static JsonObject success(String message) {
        JsonObject value = new JsonObject();
        value.addProperty("ok", true);
        value.addProperty("message", message == null ? "" : message);
        return value;
    }

    private static JsonObject error(String message) {
        JsonObject value = new JsonObject();
        value.addProperty("ok", false);
        value.addProperty("message", message == null || message.isBlank()
                ? "操作失败" : message);
        return value;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String newToken() {
        byte[] random = new byte[18];
        new SecureRandom().nextBytes(random);
        char[] alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                .toCharArray();
        StringBuilder token = new StringBuilder(random.length * 2);
        for (byte value : random) {
            int unsigned = value & 0xff;
            token.append(alphabet[unsigned >>> 4]);
            token.append(alphabet[unsigned & 0x0f]);
        }
        return token.toString();
    }

    private static String findLanAddress(Context context) {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            LinkProperties properties = network == null ? null : manager.getLinkProperties(network);
            if (properties != null) {
                for (LinkAddress link : properties.getLinkAddresses()) {
                    InetAddress address = link.getAddress();
                    if (isLanAddress(address)) return address.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            List<InetAddress> candidates = new ArrayList<>();
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String name = network.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("rmnet") || name.startsWith("ccmni")
                        || name.startsWith("dummy") || name.startsWith("p2p")
                        || name.startsWith("tun") || name.startsWith("clat")) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (isLanAddress(address)) candidates.add(address);
                }
            }
            candidates.sort(Comparator.comparingInt(LanSettingsServer::addressScore).reversed());
            if (!candidates.isEmpty()) return candidates.get(0).getHostAddress();
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean isLanAddress(InetAddress address) {
        return address instanceof Inet4Address
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && address.isSiteLocalAddress();
    }

    private static int addressScore(InetAddress address) {
        try {
            NetworkInterface network = NetworkInterface.getByInetAddress(address);
            String name = network == null ? "" : network.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("wlan")) return 40;
            if (name.startsWith("eth")) return 30;
            return 10;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String colorHex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private static String deviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.contains("sdk_gphone") || lower.contains("emulator")
                || lower.contains("goldfish")) {
            return "Android TV 模拟器";
        }
        String manufacturer = Build.MANUFACTURER == null
                ? "" : Build.MANUFACTURER.trim();
        if (manufacturer.isBlank() || lower.startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            return model.isBlank() ? "Android TV" : model;
        }
        return (manufacturer + " " + model).trim();
    }

    private static String friendly(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    public interface Listener {
        void onChanged(Change change);
    }

    public static final class Change {
        public final ConfigStore.Snapshot previousConfig;
        public final TvBoxRepository.RuntimeSnapshot previousRuntime;
        public final boolean sourcesChanged;
        public final boolean requiresReload;
        public final boolean themeChanged;
        public final String message;

        Change(ConfigStore.Snapshot previousConfig,
               TvBoxRepository.RuntimeSnapshot previousRuntime,
               boolean sourcesChanged, boolean requiresReload,
               boolean themeChanged, String message) {
            this.previousConfig = previousConfig;
            this.previousRuntime = previousRuntime;
            this.sourcesChanged = sourcesChanged;
            this.requiresReload = requiresReload;
            this.themeChanged = themeChanged;
            this.message = message;
        }
    }

    public static final class State {
        public final String accessUrl;
        public final String token;

        State(String accessUrl, String token) {
            this.accessUrl = accessUrl;
            this.token = token;
        }
    }

    private static final class Request {
        final String method;
        final String target;
        final byte[] body;

        Request(String method, String target, byte[] body) {
            this.method = method;
            this.target = target;
            this.body = body;
        }
    }
}
