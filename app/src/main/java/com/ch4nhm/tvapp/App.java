package com.ch4nhm.tvapp;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.catvod.Runtime;
import com.ch4nhm.tvapp.spider.SpiderRuntime;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private Activity activity;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        Runtime.set(this);
        PanCredentialStore.restoreCookies();
        SpiderRuntime.set(this);
        SpiderRuntime.setDialogHost(new SpiderRuntime.Host() {
            @Override
            public void show(int callbackId, String options, com.ch4nhm.tvapp.spider.IDialogSession session) {
                com.ch4nhm.tvapp.SpiderDialogHost.show(callbackId, options, session);
            }

            @Override
            public void dismiss(int callbackId) {
                com.ch4nhm.tvapp.SpiderDialogHost.dismiss(callbackId);
            }

            @Override
            public boolean openWeb(String options) {
                return WebActivity.openWeb(options);
            }

            @Override
            public String getCookie(String url) {
                return WebViewCookies.get(url);
            }

            @Override
            public boolean setCookie(String url, String cookie) {
                return WebViewCookies.set(url, cookie);
            }

            @Override
            public String getUserAgent() {
                return WebActivity.getUserAgent();
            }
        });
        Runtime.setActivity(() -> activity);
        Runtime.setToast(text -> {
            Activity current = activity;
            if (current != null) current.runOnUiThread(() -> android.widget.Toast.makeText(current, text, android.widget.Toast.LENGTH_SHORT).show());
        });
        Runtime.setDialog((options, onAction) -> SpiderDialog.show(options, onAction));
    }

    void setCurrentActivity(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (this.activity == activity) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}
