package com.ch4nhm.tvapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;

import com.ch4nhm.tvapp.data.ConfigStore;
import com.google.gson.JsonObject;

public final class WebActivity extends BaseActivity {

    private static final String EXTRA_OPTIONS = "options";
    private static final String EXTRA_APPLE_THEME = "apple_theme";
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable loginCheck = this::checkLogin;
    private WebOptions options;
    private WebView webView;
    private ProgressBar progress;
    private View errorState;
    private TextView retry;
    private boolean finishedByLogin;
    private boolean appleTheme;

    public static boolean openWeb(String json) {
        Activity activity = com.github.catvod.Runtime.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            WebOptions.parse(json);
        } catch (RuntimeException error) {
            Toast.makeText(activity, "无法打开登录页：" + error.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        activity.startActivity(new Intent(activity, WebActivity.class).putExtra(EXTRA_OPTIONS, json));
        return true;
    }

    public static void openView(Context context, String url) {
        JsonObject options = new JsonObject();
        options.addProperty("url", url);
        options.addProperty("mode", "view");
        context.startActivity(new Intent(context, WebActivity.class)
                .putExtra(EXTRA_OPTIONS, options.toString())
                .putExtra(EXTRA_APPLE_THEME, true));
    }

    public static String getUserAgent() {
        return new ConfigStore(com.github.catvod.Runtime.context()).getEffectiveUserAgent();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            options = WebOptions.parse(getIntent().getStringExtra(EXTRA_OPTIONS));
        } catch (RuntimeException error) {
            Toast.makeText(this, "登录参数无效", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        appleTheme = getIntent().getBooleanExtra(EXTRA_APPLE_THEME, false);
        buildView();
        enterImmersive();
        options.snapshot();
        webView.loadUrl(options.url);
        if (options.hasCookies()) handler.postDelayed(loginCheck, 1000L);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) webView.goBack();
                else finish();
            }
        });
    }

    private void buildView() {
        setContentView(R.layout.activity_web);
        webView = findViewById(R.id.web_view);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        configure(webView);
        progress = findViewById(R.id.web_progress);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                ThemeManager.accent(this)));
        errorState = findViewById(R.id.web_error_state);
        retry = findViewById(R.id.web_retry);
        FocusAnimator.scale(retry, 1.04f);
        retry.setOnClickListener(view -> {
            showLoading();
            webView.reload();
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView view) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(new ConfigStore(this).getEffectiveUserAgent());
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        manager.setAcceptThirdPartyCookies(view, true);
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView webView, int value) {
                progress.setProgress(value);
                if (value >= 100 && errorState.getVisibility() != View.VISIBLE) {
                    progress.setVisibility(View.GONE);
                }
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView webView, String url, android.graphics.Bitmap favicon) {
                showLoading();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                return openExternal(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                return openExternal(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                webView.setVisibility(View.VISIBLE);
                CookieManager.getInstance().flush();
                if (appleTheme) {
                    WebTvTheme.install(webView, ThemeManager.accent(WebActivity.this),
                            ThemeManager.onAccent(WebActivity.this));
                }
                applyTvFocusStyle(webView);
                if (options.hasCookies()) checkLogin();
            }

            @Override
            public void onReceivedError(WebView webView, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) showWebError();
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView webView, int errorCode, String description,
                                        String failingUrl) {
                showWebError();
            }

            @Override
            public void onReceivedHttpError(WebView webView, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame() && errorResponse != null
                        && errorResponse.getStatusCode() >= 400) {
                    showWebError();
                }
            }
        });
    }

    private void showLoading() {
        if (errorState == null) return;
        errorState.setVisibility(View.GONE);
        if (webView != null) webView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
    }

    private void showWebError() {
        if (isFinishing() || isDestroyed()) return;
        progress.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        errorState.setVisibility(View.VISIBLE);
        errorState.post(retry::requestFocus);
    }

    private void applyTvFocusStyle(WebView view) {
        if (appleTheme) return;
        int accent = ThemeManager.accent(this);
        String color = String.format(java.util.Locale.US, "#%06X", accent & 0xFFFFFF);
        String glow = String.format(java.util.Locale.US, "rgba(%d,%d,%d,.18)",
                android.graphics.Color.red(accent),
                android.graphics.Color.green(accent),
                android.graphics.Color.blue(accent));
        String script = "javascript:(function(){"
                + "if(document.getElementById('tvbox-tv-focus'))return;"
                + "var s=document.createElement('style');s.id='tvbox-tv-focus';"
                + "s.textContent=':focus{outline:3px solid " + color + "!important;"
                + "outline-offset:3px!important;box-shadow:0 0 0 5px " + glow + "!important}"
                + "a,button,[tabindex],input,select,textarea{cursor:default!important}';"
                + "document.head.appendChild(s);})()";
        view.evaluateJavascript(script, null);
    }

    private boolean openExternal(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
        if ("about".equalsIgnoreCase(scheme) || "javascript".equalsIgnoreCase(scheme)) return false;
        if (!options.view) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (RuntimeException ignored) {
            }
        }
        return true;
    }

    private void checkLogin() {
        if (finishedByLogin || isFinishing() || isDestroyed() || webView == null) return;
        if (options.completed(webView.getUrl())) {
            finishedByLogin = true;
            CookieManager.getInstance().flush();
            Toast.makeText(this, "登录信息已保存", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::finish, 500L);
            return;
        }
        handler.postDelayed(loginCheck, 1000L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }
}
