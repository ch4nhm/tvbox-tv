package com.ch4nhm.tvapp;

import android.util.Log;

import com.ch4nhm.tvapp.spider.IDialogSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI-process endpoint for dialogs requested by the isolated spider process.
 */
public final class SpiderDialogHost {

    private static final String TAG = "TVBoxSpider";
    private static final Map<Integer, CompletableFuture<String>> REQUESTS = new ConcurrentHashMap<>();

    public static void show(int callbackId, String options, IDialogSession session) {
        if (REQUESTS.containsKey(callbackId)) return;
        Log.i(TAG, "Show remote dialog #" + callbackId + ": " + options);
        CompletableFuture<String> future = SpiderDialog.show(
                callbackId,
                options,
                event -> callAction(session, callbackId, event),
                value -> {
                    try {
                        session.complete(callbackId, value);
                    } catch (Throwable error) {
                        Log.e(TAG, "Dialog completion failed", error);
                    }
                });
        REQUESTS.put(callbackId, future);
        future.whenComplete((value, error) -> REQUESTS.remove(callbackId));
    }

    public static void dismiss(int callbackId) {
        CompletableFuture<String> future = REQUESTS.remove(callbackId);
        if (future != null) future.cancel(true);
    }

    private static CompletableFuture<String> callAction(IDialogSession session, int callbackId, String event) {
        try {
            String value = session.action(callbackId, event);
            return CompletableFuture.completedFuture(value == null ? "{}" : value);
        } catch (Throwable error) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(error);
            return failed;
        }
    }
}
