package com.ch4nhm.tvapp.spider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SpiderRuntime {
    private static final String TAG = "TVBoxSpider";
    private static final long DEFAULT_CALL_TIMEOUT_SECONDS = 60;
    private static final SpiderRuntime INSTANCE = new SpiderRuntime();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "spider-client");
        thread.setDaemon(true);
        return thread;
    });
    private static final int MAX_PARALLEL_CALLS = 6;
    private static final int MAX_BACKGROUND_CALLS = 5;
    private final ConcurrentLinkedQueue<Request> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Request> priorityQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<CompletableFuture<String>, Request> requests =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> siteGates = new ConcurrentHashMap<>();
    private final Semaphore parallel = new Semaphore(MAX_PARALLEL_CALLS);
    private final Semaphore background = new Semaphore(MAX_BACKGROUND_CALLS);
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "spider-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private Context context;
    private ISpiderService service;
    private Host dialogHost;
    private boolean binding;

    private SpiderRuntime() {
    }

    public static SpiderRuntime get() {
        return INSTANCE;
    }

    public static void set(Context context) {
        get().context = context.getApplicationContext();
    }

    public interface Host {
        void show(int callbackId, String options, IDialogSession session);

        void dismiss(int callbackId);

        boolean openWeb(String options);

        String getCookie(String url);

        boolean setCookie(String url, String cookie);

        String getUserAgent();
    }

    public static void setDialogHost(Host host) {
        get().dialogHost = host;
    }

    public CompletableFuture<String> call(String method, SiteArgs site, String argument) {
        return call(method, site, argument, timeoutSeconds(method));
    }

    public CompletableFuture<String> call(String method, SiteArgs site, String argument,
                                          long timeoutSeconds) {
        CompletableFuture<String> result = new CompletableFuture<>();
        result.whenComplete((ignored, error) -> requests.remove(result));
        executor.execute(() -> {
            if (result.isDone()) return;
            try {
                Request request = new Request(method, site.key, site.toJson(), argument,
                        Math.max(1, timeoutSeconds), result);
                requests.put(result, request);
                if (result.isDone() || request.cancelled.get()) {
                    requests.remove(result, request);
                    return;
                }
                Log.d(TAG, "Queue " + method + " for " + site.key);
                enqueue(request);
                if (service != null) drain();
                else bind();
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    /**
     * Removes a queued request and prevents a late spider result from reaching
     * the caller. In-flight spider calls cannot be interrupted safely, but they
     * no longer occupy the queue or a site gate once they return.
     */
    public void cancel(CompletableFuture<String> future) {
        if (future == null) return;
        Request request = requests.remove(future);
        if (request == null) {
            future.cancel(false);
            return;
        }
        request.cancelled.set(true);
        priorityQueue.remove(request);
        queue.remove(request);
        future.cancel(false);
    }

    public CompletableFuture<String> proxy(String query) {
        CompletableFuture<String> result = new CompletableFuture<>();
        executor.execute(() -> {
            ISpiderService current = awaitService();
            if (current == null) {
                result.completeExceptionally(new IllegalStateException("Spider 服务尚未连接"));
                return;
            }
            try {
                result.complete(current.proxy(query));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    public CompletableFuture<Void> prepare(SiteArgs site) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        executor.execute(() -> {
            ISpiderService current = awaitService();
            if (current == null) {
                result.completeExceptionally(new IllegalStateException("Spider 服务尚未连接"));
                return;
            }
            try {
                current.prepare(site.toJson());
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private ISpiderService awaitService() {
        ISpiderService current = service;
        if (current != null) return current;
        bind();
        try {
            long deadline = System.currentTimeMillis() + 5000L;
            while (service == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return service;
    }

    public CompletableFuture<Void> reset() {
        failAll(new IllegalStateException("Spider 正在重置"));
        CompletableFuture<Void> result = new CompletableFuture<>();
        executor.execute(() -> {
            ISpiderService current = service;
            try {
                if (current != null) {
                    current.reset();
                }
                result.complete(null);
            } catch (Throwable error) {
                Log.w(TAG, "Spider reset failed", error);
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    private void bind() {
        synchronized (this) {
            if (binding || service != null) return;
            if (context == null) {
                failAll(new IllegalStateException("Spider runtime is not initialized"));
                return;
            }
            binding = true;
        }
        Intent intent = new Intent();
        intent.setClassName(context, "com.ch4nhm.tvapp.spider.SpiderService");
        try {
            Log.i(TAG, "Binding spider service");
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
                throw new IllegalStateException("无法启动 Spider 服务");
        } catch (RuntimeException error) {
            Log.e(TAG, "Spider service bind failed", error);
            synchronized (this) {
                binding = false;
            }
            failAll(error);
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Spider service connected");
            synchronized (SpiderRuntime.this) {
                service = ISpiderService.Stub.asInterface(binder);
                binding = false;
            }
            try {
                Host host = dialogHost;
                if (host == null) throw new IllegalStateException("Dialog host not initialized");
                service.setDialogHost(new IDialogHost.Stub() {
                    @Override
                    public void showDialog(int callbackId, String options, IDialogSession session) {
                        host.show(callbackId, options, session);
                    }

                    @Override
                    public void dismissDialog(int callbackId) {
                        host.dismiss(callbackId);
                    }

                    @Override
                    public boolean openWeb(String options) {
                        return host.openWeb(options);
                    }

                    @Override
                    public String getCookie(String url) {
                        return host.getCookie(url);
                    }

                    @Override
                    public boolean setCookie(String url, String cookie) {
                        return host.setCookie(url, cookie);
                    }

                    @Override
                    public String getUserAgent() {
                        return host.getUserAgent();
                    }
                });
            } catch (Exception error) {
                Log.e(TAG, "Spider dialog host registration failed", error);
            }
            try {
                com.github.catvod.Proxy.set(service.getProxyPort());
            } catch (Exception error) {
                Log.e(TAG, "Unable to read proxy port", error);
            }
            drain();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "Spider service disconnected");
            synchronized (SpiderRuntime.this) {
                service = null;
                binding = false;
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.e(TAG, "Spider service returned null binder");
            onServiceDisconnected(name);
            failAll(new IllegalStateException("Spider 服务返回空 Binder"));
        }
    };

    private void drain() {
        executor.execute(() -> {
            while (true) {
                Request request = nextRequest();
                ISpiderService current = service;
                if (request == null) return;
                if (current == null) {
                    enqueue(request);
                    return;
                }
                if (request.cancelled.get() || request.result.isDone()) continue;
                boolean backgroundSlot = false;
                if (!request.isPriority()) {
                    if (!background.tryAcquire()) {
                        enqueue(request);
                        return;
                    }
                    backgroundSlot = true;
                }
                if (!parallel.tryAcquire()) {
                    if (backgroundSlot) background.release();
                    enqueue(request);
                    return;
                }
                boolean acquiredBackground = backgroundSlot;
                executor.execute(() -> execute(request, current, acquiredBackground));
            }
        });
    }

    private void execute(Request request, ISpiderService current,
                         boolean backgroundSlot) {
        if (request.cancelled.get() || request.result.isDone()) {
            parallel.release();
            if (backgroundSlot) background.release();
            drain();
            return;
        }
        Semaphore siteGate = siteGates.computeIfAbsent(
                request.siteKey, ignored -> new Semaphore(1, true));
        boolean siteAcquired = false;
        ScheduledFuture<?> timeout = null;
        try {
            siteAcquired = siteGate.tryAcquire(request.timeoutSeconds, TimeUnit.SECONDS);
            if (!siteAcquired) {
                if (!request.result.isDone()) {
                    request.result.completeExceptionally(
                            new IllegalStateException("站点请求排队超时: " + request.method));
                }
                return;
            }
            if (request.cancelled.get() || request.result.isDone()) {
                return;
            }
            timeout = timeoutExecutor.schedule(() -> {
                if (!request.cancelled.get() && request.result.completeExceptionally(
                        new IllegalStateException("站点请求超时: " + request.method))) {
                    Log.w(TAG, "Timeout " + request.method + " for " + request.siteKey);
                }
            }, request.timeoutSeconds, TimeUnit.SECONDS);
            String value = current.call(request.method, request.site, request.argument);
            if (!request.cancelled.get() && !request.result.isDone()) {
                request.result.complete(SpiderResultTransport.restore(context, value));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            if (!request.result.isDone()) request.result.completeExceptionally(error);
        } catch (Throwable error) {
            Log.e(TAG, request.method + " failed", error);
            if (!request.result.isDone()) request.result.completeExceptionally(error);
        } finally {
            if (timeout != null) timeout.cancel(false);
            if (siteAcquired) siteGate.release();
            parallel.release();
            if (backgroundSlot) background.release();
            drain();
        }
    }

    private void failAll(Throwable error) {
        Request request;
        while ((request = priorityQueue.poll()) != null) {
            request.cancelled.set(true);
            if (!request.result.isDone()) request.result.completeExceptionally(error);
        }
        while ((request = queue.poll()) != null) request.result.completeExceptionally(error);
    }

    private void enqueue(Request request) {
        if (request.isPriority()) priorityQueue.add(request);
        else queue.add(request);
    }

    private Request nextRequest() {
        Request request = priorityQueue.poll();
        return request == null ? queue.poll() : request;
    }

    private static long timeoutSeconds(String method) {
        if ("detail".equals(method)) return 25;
        if ("player".equals(method)) return 30;
        if ("search".equals(method)) return 18;
        return DEFAULT_CALL_TIMEOUT_SECONDS;
    }

    private static final class Request {
        final String method;
        final String siteKey;
        final String site;
        final String argument;
        final long timeoutSeconds;
        final CompletableFuture<String> result;
        final AtomicBoolean cancelled = new AtomicBoolean();

        Request(String method, String siteKey, String site, String argument,
                long timeoutSeconds, CompletableFuture<String> result) {
            this.method = method;
            this.siteKey = siteKey;
            this.site = site;
            this.argument = argument;
            this.timeoutSeconds = timeoutSeconds;
            this.result = result;
        }

        boolean isPriority() {
            return "detail".equals(method) || "player".equals(method);
        }
    }
}
