package com.ch4nhm.tvapp.spider;

import android.os.RemoteException;
import android.util.Log;

import com.github.catvod.Runtime;

import java.util.function.BiFunction;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Relays Spider dialogs from the isolated :spider process to the UI process.
 */
final class SpiderDialogBridge {

    private static final String TAG = "TVBoxSpider";
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Map<Integer, Request> REQUESTS = new ConcurrentHashMap<>();
    private static volatile IDialogHost host;
    private static volatile boolean installed;

    private SpiderDialogBridge() {
    }

    static void install(IDialogHost dialogHost) {
        host = dialogHost;
        if (installed) return;
        installed = true;
        Runtime.setDialog((options, onAction) -> show(options, onAction));
        Runtime.setWeb(SpiderDialogBridge::openWeb);
        Runtime.setCookieReader(SpiderDialogBridge::getCookie);
        Runtime.setCookieWriter(SpiderDialogBridge::setCookie);
        Runtime.setUserAgent(SpiderDialogBridge::getUserAgent);
    }

    static void disconnect() {
        host = null;
        for (Request request : REQUESTS.values()) request.future.cancel(true);
        REQUESTS.clear();
    }

    static void uninstall() {
        disconnect();
        if (!installed) return;
        installed = false;
        Runtime.setDialog(null);
        Runtime.setWeb(null);
        Runtime.setCookieReader(null);
        Runtime.setCookieWriter(null);
        Runtime.setUserAgent(null);
    }

    private static boolean openWeb(String options) {
        IDialogHost current = host;
        if (current == null) return false;
        try {
            return current.openWeb(options);
        } catch (RemoteException error) {
            Log.e(TAG, "Unable to open WebView", error);
            return false;
        }
    }

    private static String getCookie(String url) {
        IDialogHost current = host;
        if (current == null) return "";
        try {
            String value = current.getCookie(url);
            return value == null ? "" : value;
        } catch (RemoteException error) {
            Log.e(TAG, "Unable to read WebView cookie", error);
            return "";
        }
    }

    private static boolean setCookie(String url, String cookie) {
        IDialogHost current = host;
        if (current == null) return false;
        try {
            return current.setCookie(url, cookie);
        } catch (RemoteException error) {
            Log.e(TAG, "Unable to write WebView cookie", error);
            return false;
        }
    }

    private static String getUserAgent() {
        IDialogHost current = host;
        if (current == null) return "";
        try {
            String value = current.getUserAgent();
            return value == null ? "" : value;
        } catch (RemoteException error) {
            Log.e(TAG, "Unable to read WebView user agent", error);
            return "";
        }
    }

    private static CompletableFuture<String> show(
            String options, Function<String, CompletableFuture<String>> onAction) {
        IDialogHost current = host;
        if (current == null) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("登录窗口尚未连接主进程"));
            return failed;
        }
        int id = IDS.incrementAndGet();
        CompletableFuture<String> future = new CompletableFuture<>();
        Request request = new Request(id, onAction, future);
        REQUESTS.put(id, request);
        future.whenComplete((value, error) -> {
            REQUESTS.remove(id, request);
            try {
                if (error == null) current.dismissDialog(id);
            } catch (RemoteException ignored) {
            }
        });
        try {
            current.showDialog(id, options, request.session());
        } catch (RemoteException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private static final class Request {
        private final int id;
        private final Function<String, CompletableFuture<String>> action;
        private final CompletableFuture<String> future;
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile IDialogSession.Stub session;

        private synchronized IDialogSession.Stub session() {
            if (session == null) session = createSession();
            return session;
        }

        private IDialogSession.Stub createSession() {
            return new IDialogSession.Stub() {
            @Override
            public String action(int callbackId, String event) throws RemoteException {
                if (callbackId != id) throw new RemoteException("Dialog callback mismatch");
                Function<String, CompletableFuture<String>> callback = Request.this.action;
                if (callback == null) return "{}";
                try {
                    String value = callback.apply(event).get(60, TimeUnit.SECONDS);
                    return value == null ? "{}" : value;
                } catch (Exception error) {
                    Throwable cause = root(error);
                    Log.e(TAG, "Dialog action failed", cause);
                    throw new RemoteException(Objects.toString(cause.getMessage(), cause.toString()));
                }
            }

            @Override
            public void complete(int callbackId, String value) {
                if (callbackId != id || !completed.compareAndSet(false, true)) return;
                future.complete(value);
            }
            };
        }

        private Request(int id, Function<String, CompletableFuture<String>> action, CompletableFuture<String> future) {
            this.id = id;
            this.action = action;
            this.future = future;
        }

        private Function<String, CompletableFuture<String>> action() {
            return action;
        }
    }

    private static Throwable root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }
}
