package com.ch4nhm.tvapp;

import android.webkit.CookieManager;

import java.util.Locale;

public final class WebViewCookies {

    private WebViewCookies() {
    }

    public static String get(String url) {
        if (!isHttp(url)) return "";
        try {
            String value = CookieManager.getInstance().getCookie(url);
            return value == null || hasLineBreak(value) ? "" : value;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static boolean set(String url, String cookie) {
        if (!isHttp(url) || cookie == null || cookie.isBlank() || hasLineBreak(cookie)) return false;
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.setAcceptCookie(true);
            for (String item : cookie.split(";")) {
                String value = item.trim();
                if (!value.isEmpty()) manager.setCookie(url, value);
            }
            manager.flush();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void clear(String url) {
        if (!isHttp(url)) return;
        try {
            CookieManager manager = CookieManager.getInstance();
            String current = manager.getCookie(url);
            if (current != null && !current.isBlank()) {
                for (String item : current.split(";")) {
                    String pair = item.trim();
                    int separator = pair.indexOf('=');
                    if (separator <= 0) continue;
                    String name = pair.substring(0, separator).trim();
                    if (!name.isEmpty()) {
                        manager.setCookie(url, name + "=; Max-Age=0; Path=/");
                    }
                }
            }
            manager.flush();
        } catch (RuntimeException ignored) {
        }
    }

    static boolean isHttp(String url) {
        if (url == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            return uri.getHost() != null && scheme != null
                    && ("http".equals(scheme.toLowerCase(Locale.ROOT))
                    || "https".equals(scheme.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean hasLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }
}
