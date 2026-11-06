package com.ch4nhm.tvapp.spider;

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
 * Keeps large Spider responses out of Binder transactions. The service writes
 * oversized JSON to its private cache and returns a short file token instead.
 */
final class SpiderResultTransport {

    private static final String PREFIX = "@@TVBOX_SPIDER_FILE@@";
    private static final String DIRECTORY = "spider-results";
    private static final int MAX_INLINE_CHARS = 120_000;
    private static final long MAX_FILE_AGE_MS = 10 * 60_000L;

    private SpiderResultTransport() {
    }

    static String store(Context context, String value) throws IOException {
        if (value == null || value.length() <= MAX_INLINE_CHARS) return value;
        File directory = directory(context);
        cleanup(directory);
        File file = File.createTempFile("result-", ".json", directory);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(value);
        }
        return PREFIX + file.getName();
    }

    static String restore(Context context, String value) throws IOException {
        if (value == null || !value.startsWith(PREFIX)) return value;
        String name = value.substring(PREFIX.length());
        if (name.isBlank() || name.contains("/") || name.contains("\\")
                || name.contains("..")) {
            throw new IOException("Spider 返回文件标识无效");
        }
        File file = new File(directory(context), name);
        if (!file.isFile()) throw new IOException("Spider 返回结果已失效");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder((int) Math.min(Integer.MAX_VALUE,
                    Math.max(1024L, file.length())));
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, count);
            }
            return result.toString();
        } finally {
            if (!file.delete()) file.deleteOnExit();
        }
    }

    static void clear(Context context) {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.delete()) file.deleteOnExit();
        }
    }

    private static File directory(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建 Spider 结果缓存目录");
        }
        return directory;
    }

    private static void cleanup(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - MAX_FILE_AGE_MS;
        for (File file : files) {
            if (file.lastModified() < cutoff && !file.delete()) file.deleteOnExit();
        }
    }
}
