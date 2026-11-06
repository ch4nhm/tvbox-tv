package com.ch4nhm.tvapp;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.content.res.Configuration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Objects;

public abstract class BaseActivity extends AppCompatActivity {

    private String appliedThemeKey;
    private int appliedOverscanPermille;
    private String viewportSignature;
    private View initialFocusTarget;
    private boolean initialFocusDispatched;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        long startedAt = SystemClock.elapsedRealtime();
        ((App) getApplication()).setCurrentActivity(this);
        TvDisplayAdapter.apply(this);
        AppSettings settings = new AppSettings(this);
        appliedThemeKey = settings.getThemeKey();
        appliedOverscanPermille = settings.getOverscanPermille();
        setTheme(ThemeManager.styleFor(appliedThemeKey));
        Log.d("TVBoxPerf", getClass().getSimpleName() + " theme "
                + (SystemClock.elapsedRealtime() - startedAt) + "ms");
        super.onCreate(savedInstanceState);
        Log.d("TVBoxPerf", getClass().getSimpleName() + " super "
                + (SystemClock.elapsedRealtime() - startedAt) + "ms");
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppSettings settings = new AppSettings(this);
        if (!settings.getThemeKey().equals(appliedThemeKey)
                || settings.getOverscanPermille() != appliedOverscanPermille) {
            recreate();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean densityChanged = TvDisplayAdapter.apply(this);
        TvViewport viewport = TvDisplayAdapter.applySafeArea(this);
        if (densityChanged || !Objects.equals(viewportSignature, viewport.signature())) {
            recreate();
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        afterContentView();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        afterContentView();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        afterContentView();
    }

    protected final void requestInitialFocus(View target) {
        initialFocusTarget = target;
        initialFocusDispatched = false;
        if (hasWindowFocus()) dispatchInitialFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            TvViewport viewport = TvDisplayAdapter.applySafeArea(this);
            viewportSignature = viewport.signature();
            dispatchInitialFocus();
        }
    }

    private void afterContentView() {
        TvViewport viewport = TvDisplayAdapter.applySafeArea(this);
        viewportSignature = viewport.signature();
        getWindow().getDecorView().postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            TvViewport settled = TvDisplayAdapter.applySafeArea(this);
            viewportSignature = settled.signature();
        }, 180L);
    }

    private void dispatchInitialFocus() {
        View target = initialFocusTarget;
        if (target == null || initialFocusDispatched) return;
        initialFocusDispatched = true;
        target.postDelayed(() -> TvFocus.requestInitial(target), 60L);
    }
}
