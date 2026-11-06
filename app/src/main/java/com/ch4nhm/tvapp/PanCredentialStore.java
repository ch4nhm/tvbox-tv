package com.ch4nhm.tvapp;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.catvod.Runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persists credentials in the preference file used by the bundled TVBox JAR.
 */
final class PanCredentialStore {

    private static final String PREFS = "NewWexFnw_preferences";
    private static final String QUARK_COOKIE = "Wex_quark_cookie";
    private static final String QUARK_CREDENTIAL = "pan.credential.quark";
    private static final String QUARK_TIME = "qktime";
    private static final String BAIDU_COOKIE = "Wex_baidu_cookie";
    private static final String BAIDU_CREDENTIAL = "pan.credential.baidu";
    private static final String BAIDU_TIME = "pan.credential.baidu.savedAt";
    private static final String ALIYUN_COOKIE = "Wex_aliyun_cookie";
    private static final String ALIYUN_REFRESH_TOKEN = "Wex_aliyun_refresh_token";
    private static final String ALIYUN_ACCESS_TOKEN = "Wex_aliyun_token";
    private static final String ALIYUN_ACCESS_TOKEN_ALIAS = "Wex_aliyundrive_token";
    private static final String ALIYUN_NAME = "Wex_aliyun_name";
    private static final String ALIYUN_CREDENTIAL = "pan.credential.aliyun";
    private static final String ALIYUN_TIME = "pan.credential.aliyun.savedAt";

    private PanCredentialStore() {
    }

    static boolean hasCredential(String brandId) {
        return switch (brandId == null ? "" : brandId) {
            case "quark" -> hasQuarkCookie();
            case "baidu" -> hasBaiduCookie();
            case "aliyun" -> hasAliyunCredential();
            default -> false;
        };
    }

    static String credentialFingerprint(String brandId) {
        String material = switch (brandId == null ? "" : brandId) {
            case "quark" -> quarkCookie();
            case "baidu" -> baiduCookie();
            case "aliyun" -> aliyunCookie() + "\u0000"
                    + aliyunRefreshToken() + "\u0000" + aliyunAccessToken();
            default -> "";
        };
        if (material.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(material.hashCode()) + ":" + material.length();
        }
    }

    static boolean hasQuarkCookie() {
        String cookie = quarkCookie();
        return cookieHasValue(cookie, "__pus") || cookieHasValue(cookie, "__puus");
    }

    static String quarkCookie() {
        Context context = Runtime.context();
        if (context == null) return "";
        return preferences(context).getString(QUARK_COOKIE, "");
    }

    static void saveQuarkCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalArgumentException("夸克登录 Cookie 为空");
        }
        Context context = Runtime.context();
        if (context == null) throw new IllegalStateException("应用上下文尚未初始化");
        String normalizedCookie = cookie.trim();
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        boolean saved = preferences(context).edit()
                .putString(QUARK_COOKIE, normalizedCookie)
                .putString(QUARK_CREDENTIAL, "{\"b\":\"quark\"}")
                .putString(QUARK_TIME, date)
                .commit();
        if (!saved) throw new IllegalStateException("登录信息保存失败");
        mirrorQuarkCookie(normalizedCookie);
    }

    static boolean hasBaiduCookie() {
        return cookieHasValue(baiduCookie(), "BDUSS");
    }

    static String baiduCookie() {
        Context context = Runtime.context();
        if (context == null) return "";
        return preferences(context).getString(BAIDU_COOKIE, "");
    }

    static void saveBaiduCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalArgumentException("百度网盘登录 Cookie 为空");
        }
        Context context = Runtime.context();
        if (context == null) throw new IllegalStateException("应用上下文尚未初始化");
        String normalizedCookie = cookie.trim();
        String savedAt = String.valueOf(System.currentTimeMillis());
        boolean saved = preferences(context).edit()
                .putString(BAIDU_COOKIE, normalizedCookie)
                .putString(BAIDU_CREDENTIAL, "{\"b\":\"baidu\"}")
                .putString(BAIDU_TIME, savedAt)
                .commit();
        if (!saved) throw new IllegalStateException("百度网盘登录信息保存失败");
        mirrorBaiduCookie(normalizedCookie);
    }

    static boolean hasAliyunCredential() {
        return !aliyunRefreshToken().isBlank()
                || !aliyunAccessToken().isBlank()
                || !aliyunCookie().isBlank();
    }

    static String aliyunCookie() {
        Context context = Runtime.context();
        if (context == null) return "";
        return preferences(context).getString(ALIYUN_COOKIE, "");
    }

    static String aliyunRefreshToken() {
        Context context = Runtime.context();
        if (context == null) return "";
        return preferences(context).getString(ALIYUN_REFRESH_TOKEN, "");
    }

    static String aliyunAccessToken() {
        Context context = Runtime.context();
        if (context == null) return "";
        return preferences(context).getString(ALIYUN_ACCESS_TOKEN, "");
    }

    static void saveAliyunCredential(String cookie, String refreshToken,
                                     String accessToken, String nickname) {
        String normalizedCookie = safe(cookie);
        String normalizedRefresh = safe(refreshToken);
        String normalizedAccess = safe(accessToken);
        if (normalizedRefresh.isBlank() && normalizedAccess.isBlank()
                && normalizedCookie.isBlank()) {
            throw new IllegalArgumentException("阿里云盘登录凭据为空");
        }
        Context context = Runtime.context();
        if (context == null) throw new IllegalStateException("应用上下文尚未初始化");
        String savedAt = String.valueOf(System.currentTimeMillis());
        SharedPreferences.Editor editor = preferences(context).edit()
                .putString(ALIYUN_CREDENTIAL, "{\"b\":\"aliyun\"}")
                .putString(ALIYUN_TIME, savedAt);
        putIfPresent(editor, ALIYUN_COOKIE, normalizedCookie);
        putIfPresent(editor, ALIYUN_REFRESH_TOKEN, normalizedRefresh);
        putIfPresent(editor, ALIYUN_ACCESS_TOKEN, normalizedAccess);
        putIfPresent(editor, ALIYUN_ACCESS_TOKEN_ALIAS, normalizedAccess);
        putIfPresent(editor, ALIYUN_NAME, safe(nickname));
        boolean saved = editor.commit();
        if (!saved) throw new IllegalStateException("阿里云盘登录信息保存失败");
        mirrorAliyunCredential(normalizedCookie, normalizedRefresh, normalizedAccess);
    }

    static void clearQuarkCookie() {
        Context context = Runtime.context();
        if (context == null) return;
        preferences(context).edit()
                .remove(QUARK_COOKIE)
                .remove(QUARK_CREDENTIAL)
                .remove(QUARK_TIME)
                .commit();
        clearDomains(
                "https://pan.quark.cn/",
                "https://drive.quark.cn/",
                "https://drive-pc.quark.cn/",
                "https://fast.quark.cn/");
    }

    static void clearCredential(String brandId) {
        if ("quark".equals(brandId)) {
            clearQuarkCookie();
            return;
        }
        Context context = Runtime.context();
        if (context == null) return;
        SharedPreferences.Editor editor = preferences(context).edit();
        switch (brandId == null ? "" : brandId) {
            case "baidu" -> editor
                    .remove(BAIDU_COOKIE)
                    .remove(BAIDU_CREDENTIAL)
                    .remove(BAIDU_TIME);
            case "aliyun" -> editor
                    .remove(ALIYUN_COOKIE)
                    .remove(ALIYUN_REFRESH_TOKEN)
                    .remove(ALIYUN_ACCESS_TOKEN)
                    .remove(ALIYUN_ACCESS_TOKEN_ALIAS)
                    .remove(ALIYUN_NAME)
                    .remove(ALIYUN_CREDENTIAL)
                    .remove(ALIYUN_TIME);
            default -> {
                return;
            }
        }
        editor.commit();
        if ("baidu".equals(brandId)) {
            clearDomains(
                    "https://pan.baidu.com/",
                    "https://passport.baidu.com/",
                    "https://pcs.baidu.com/",
                    "https://d.pcs.baidu.com/",
                    "https://www.baidu.com/");
        } else if ("aliyun".equals(brandId)) {
            clearDomains(
                    "https://www.aliyundrive.com/",
                    "https://www.alipan.com/",
                    "https://passport.aliyundrive.com/",
                    "https://auth.aliyundrive.com/",
                    "https://api.aliyundrive.com/",
                    "https://openapi.alipan.com/",
                    "https://api.alipan.com/",
                    "https://pds.aliyundrive.com/");
        }
    }

    static void restoreCookies() {
        mirrorQuarkCookie(quarkCookie());
        mirrorBaiduCookie(baiduCookie());
        mirrorAliyunCredential(aliyunCookie(), aliyunRefreshToken(), aliyunAccessToken());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void mirrorQuarkCookie(String cookie) {
        // The bundled spider reads cookies through the app's WebView cookie host.
        for (String url : new String[]{
                "https://pan.quark.cn/",
                "https://drive.quark.cn/",
                "https://drive-pc.quark.cn/",
                "https://fast.quark.cn/"
        }) {
            WebViewCookies.set(url, cookie);
        }
    }

    private static void mirrorBaiduCookie(String cookie) {
        if (cookie == null || cookie.isBlank()) return;
        for (String url : new String[]{
                "https://pan.baidu.com/",
                "https://passport.baidu.com/",
                "https://pcs.baidu.com/",
                "https://d.pcs.baidu.com/",
                "https://www.baidu.com/"
        }) {
            WebViewCookies.set(url, cookie);
        }
    }

    private static void mirrorAliyunCredential(String cookie, String refreshToken,
                                               String accessToken) {
        String merged = mergeCookies(cookie,
                cookiePair("refresh_token", refreshToken),
                cookiePair("access_token", accessToken),
                cookiePair("token", accessToken));
        if (merged.isBlank()) return;
        for (String url : new String[]{
                "https://www.aliyundrive.com/",
                "https://www.alipan.com/",
                "https://passport.aliyundrive.com/",
                "https://auth.aliyundrive.com/",
                "https://api.aliyundrive.com/",
                "https://openapi.alipan.com/",
                "https://api.alipan.com/",
                "https://pds.aliyundrive.com/"
        }) {
            WebViewCookies.set(url, merged);
        }
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

    private static String cookiePair(String name, String value) {
        return value == null || value.isBlank() ? "" : name + "=" + value.trim();
    }

    private static void clearDomains(String... urls) {
        for (String url : urls) WebViewCookies.clear(url);
    }

    private static void putIfPresent(SharedPreferences.Editor editor, String key, String value) {
        if (value == null || value.isBlank()) editor.remove(key);
        else editor.putString(key, value);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean cookieHasValue(String cookie, String name) {
        if (cookie == null || cookie.isBlank()) return false;
        for (String part : cookie.split(";")) {
            String pair = part.trim();
            int separator = pair.indexOf('=');
            if (separator <= 0 || !name.equals(pair.substring(0, separator).trim())) continue;
            return !pair.substring(separator + 1).trim().isEmpty();
        }
        return false;
    }
}
