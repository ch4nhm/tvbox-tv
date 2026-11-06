package com.ch4nhm.tvapp.data;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Stores the last successful home payload so the TV can paint immediately
 * while the Spider refreshes in the background.
 */
public final class HomeCache {

    private static final String FILE_NAME = "home-cache.txt";
    private static final long MAX_SIZE_BYTES = 2 * 1024 * 1024L;

    private HomeCache() {
    }

    public static Cached load(Context context, String expectedSiteKey) {
        File file = file(context);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_SIZE_BYTES) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String siteKey = reader.readLine();
            if (siteKey == null || siteKey.isBlank()
                    || !siteKey.equals(expectedSiteKey)) return null;
            StringBuilder json = new StringBuilder((int) Math.min(Integer.MAX_VALUE, file.length()));
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) json.append(buffer, 0, count);
            if (json.length() == 0) return null;
            return new Cached(siteKey, json.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void save(Context context, String siteKey, String json) {
        if (context == null || siteKey == null || siteKey.isBlank()
                || json == null || json.isBlank()
                || json.length() > MAX_SIZE_BYTES) return;
        File target = file(context);
        File directory = target.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) return;
        File temporary = null;
        try {
            temporary = File.createTempFile("home-", ".tmp", directory);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(temporary), StandardCharsets.UTF_8))) {
                writer.write(siteKey);
                writer.newLine();
                writer.write(json);
            }
            if (target.exists() && !target.delete()) return;
            if (!temporary.renameTo(target)) {
                java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private static File file(Context context) {
        return new File(new File(context.getCacheDir(), "tvbox"), FILE_NAME);
    }

    public static final class Cached {
        public final String siteKey;
        public final String json;

        Cached(String siteKey, String json) {
            this.siteKey = siteKey;
            this.json = json;
        }
    }
}
