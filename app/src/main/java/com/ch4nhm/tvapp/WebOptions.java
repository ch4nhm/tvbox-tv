package com.ch4nhm.tvapp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WebOptions {

    final String url;
    final boolean view;
    final List<CookieRule> cookies;

    private WebOptions(String url, boolean view, List<CookieRule> cookies) {
        this.url = url;
        this.view = view;
        this.cookies = cookies;
    }

    static WebOptions parse(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("登录页面参数为空");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String url = optional(root, "url");
        if (!validUrl(url)) throw new IllegalArgumentException("登录页面地址无效");
        String mode = optional(root, "mode");
        if (!mode.isEmpty() && !"browser".equals(mode) && !"view".equals(mode)) {
            throw new IllegalArgumentException("登录页面模式无效");
        }
        List<CookieRule> rules = new ArrayList<>();
        JsonElement values = root.get("cookie");
        if (values != null && !values.isJsonNull()) {
            if (!values.isJsonArray()) throw new IllegalArgumentException("Cookie 规则格式无效");
            JsonArray array = values.getAsJsonArray();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) throw new IllegalArgumentException("Cookie 规则格式无效");
                JsonObject value = item.getAsJsonObject();
                String cookieUrl = optional(value, "url");
                if (cookieUrl.isEmpty()) cookieUrl = url;
                if (!validUrl(cookieUrl)) throw new IllegalArgumentException("Cookie 地址无效");
                List<String> keys = new ArrayList<>();
                JsonElement names = value.get("keys");
                if (names == null || !names.isJsonArray() || names.getAsJsonArray().isEmpty()) {
                    throw new IllegalArgumentException("Cookie 字段为空");
                }
                for (JsonElement name : names.getAsJsonArray()) {
                    String key = name.isJsonPrimitive() ? name.getAsString().trim() : "";
                    if (!validKey(key) || keys.contains(key)) throw new IllegalArgumentException("Cookie 字段无效");
                    keys.add(key);
                }
                rules.add(new CookieRule(cookieUrl, List.copyOf(keys)));
            }
        }
        return new WebOptions(url, "view".equals(mode), List.copyOf(rules));
    }

    boolean hasCookies() {
        return !cookies.isEmpty();
    }

    void snapshot() {
        for (CookieRule rule : cookies) rule.snapshot(WebViewCookies.get(rule.url));
    }

    boolean completed(String page) {
        for (CookieRule rule : cookies) if (rule.completed(page)) return true;
        return false;
    }

    private static String optional(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean validUrl(String value) {
        if (!WebViewCookies.isHttp(value)) return false;
        try {
            return URI.create(value).getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean validKey(String key) {
        if (key == null || key.isEmpty()) return false;
        for (int index = 0; index < key.length(); index++) {
            char value = key.charAt(index);
            if (value <= 0x20 || value >= 0x7f
                    || "()<>@,;:\\\"/[]?={}".indexOf(value) >= 0) return false;
        }
        return true;
    }

    static final class CookieRule {
        final String url;
        final List<String> keys;
        Map<String, String> initial = Collections.emptyMap();

        CookieRule(String url, List<String> keys) {
            this.url = url;
            this.keys = keys;
        }

        void snapshot(String header) {
            Map<String, String> values = values(header);
            Map<String, String> current = new LinkedHashMap<>();
            for (String key : keys) current.put(key, values.getOrDefault(key, ""));
            initial = current;
        }

        boolean completed(String page) {
            if (!sameOrigin(url, page)) return false;
            Map<String, String> current = values(WebViewCookies.get(url));
            boolean changed = false;
            for (String key : keys) {
                String value = current.getOrDefault(key, "");
                if (value.isEmpty()) return false;
                changed |= !value.equals(initial.getOrDefault(key, ""));
            }
            return changed;
        }

        private static Map<String, String> values(String header) {
            if (header == null || header.isEmpty()) return Collections.emptyMap();
            Map<String, String> result = new LinkedHashMap<>();
            for (String item : header.split(";")) {
                int separator = item.indexOf('=');
                if (separator > 0) result.put(item.substring(0, separator).trim(),
                        item.substring(separator + 1).trim());
            }
            return result;
        }

        private static boolean sameOrigin(String left, String right) {
            if (left == null || right == null) return false;
            try {
                URI first = URI.create(left);
                URI second = URI.create(right);
                return equals(first.getScheme(), second.getScheme())
                        && equals(first.getHost(), second.getHost())
                        && port(first) == port(second);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        private static boolean equals(String left, String right) {
            return left != null && right != null
                    && left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
        }

        private static int port(URI uri) {
            if (uri.getPort() >= 0) return uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
    }
}
