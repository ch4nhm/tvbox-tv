package com.ch4nhm.tvapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.ch4nhm.tvapp.data.ConfigStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SettingsActivity extends BaseActivity implements LanSettingsServer.Listener {

    private static final int REQUEST_LOCAL_NETWORK = 1701;

    private ConfigStore configStore;
    private AppSettings appSettings;
    private LinearLayout sourceList;
    private LinearLayout themeList;
    private TextView status;
    private TextView sourceName;
    private TextView sourceUrl;
    private TextView sourceCount;
    private TextView message;
    private TextView cacheSize;
    private TextView themeName;
    private TextView addSource;
    private TextView editSource;
    private TextView activateSource;
    private TextView refreshSource;
    private TextView deleteSource;
    private TextView autoNextValue;
    private TextView resumeValue;
    private TextView seekStepValue;
    private TextView videoScaleValue;
    private TextView decoderFallbackValue;
    private TextView overscanValue;
    private TextView remoteStatus;
    private TextView remoteAction;
    private final List<ConfigStore.SourceProfile> loadedSources = new ArrayList<>();
    private final LanSettingsServer lanSettings = LanSettingsServer.get();
    private String selectedSourceId;
    private boolean refreshing;
    private AlertDialog remoteDialog;

    public static void start(Context context) {
        context.startActivity(intent(context));
    }

    public static Intent intent(Context context) {
        return new Intent(context, SettingsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_settings);
        enterImmersive();

        configStore = new ConfigStore(this);
        appSettings = new AppSettings(this);
        selectedSourceId = configStore.getActiveProfileId();
        bindViews();
        bindActions();
        bindPlayerSettings();
        rebuildSources(true);
        rebuildThemes();
        applyResponsiveChrome();
        updateSummary();
        updateVersion();
        updateCacheSize();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!refreshing) finish();
            }
        });
    }

    private void bindViews() {
        sourceList = findViewById(R.id.settings_source_list);
        themeList = findViewById(R.id.settings_theme_list);
        status = findViewById(R.id.settings_source_status);
        sourceName = findViewById(R.id.settings_source_name);
        sourceUrl = findViewById(R.id.settings_source_url);
        sourceCount = findViewById(R.id.settings_source_count);
        message = findViewById(R.id.settings_message);
        cacheSize = findViewById(R.id.settings_cache_size);
        themeName = findViewById(R.id.settings_theme_name);
        addSource = findViewById(R.id.settings_add_source);
        editSource = findViewById(R.id.settings_edit_source);
        activateSource = findViewById(R.id.settings_activate_source);
        refreshSource = findViewById(R.id.settings_refresh_source);
        deleteSource = findViewById(R.id.settings_delete_source);
        autoNextValue = findViewById(R.id.settings_auto_next_value);
        resumeValue = findViewById(R.id.settings_resume_value);
        seekStepValue = findViewById(R.id.settings_seek_step_value);
        videoScaleValue = findViewById(R.id.settings_video_scale_value);
        decoderFallbackValue = findViewById(R.id.settings_decoder_fallback_value);
        overscanValue = findViewById(R.id.settings_overscan_value);
        remoteStatus = findViewById(R.id.settings_remote_status);
        remoteAction = findViewById(R.id.settings_remote_action);
    }

    private void bindActions() {
        TextView clearCache = findViewById(R.id.settings_clear_cache);
        FocusAnimator.scale(clearCache, 1.04f);
        FocusAnimator.scale(addSource, 1.04f);
        FocusAnimator.scale(editSource, 1.04f);
        FocusAnimator.scale(activateSource, 1.04f);
        FocusAnimator.scale(refreshSource, 1.04f);
        FocusAnimator.scale(deleteSource, 1.04f);
        FocusAnimator.scale(remoteAction, 1.04f);

        clearCache.setOnClickListener(view -> clearImageCache());
        addSource.setOnClickListener(view -> showSourceDialog(null));
        editSource.setOnClickListener(view -> {
            ConfigStore.SourceProfile selected = selectedSource();
            if (selected != null) showSourceDialog(selected);
        });
        activateSource.setOnClickListener(view -> confirmActivate(selectedSourceId));
        refreshSource.setOnClickListener(view -> refreshCurrentSource());
        deleteSource.setOnClickListener(view -> confirmDelete());
        remoteAction.setOnClickListener(view -> requestLanSettings());
    }

    private void requestLanSettings() {
        if (refreshing) {
            message.setText("电视正在验证片源，请稍后再打开手机设置。");
            return;
        }
        if (!hasLocalNetworkPermission()) {
            if (Build.VERSION.SDK_INT >= 37) {
                requestPermissions(new String[]{LanSettingsServer.LOCAL_NETWORK_PERMISSION},
                        REQUEST_LOCAL_NETWORK);
            } else {
                showLanSettings();
            }
            return;
        }
        showLanSettings();
    }

    private boolean hasLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < 37) return true;
        try {
            return checkSelfPermission(LanSettingsServer.LOCAL_NETWORK_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCAL_NETWORK) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showLanSettings();
        } else {
            message.setText("未获得局域网权限，无法启动手机设置。");
            Toast.makeText(this, "请允许访问局域网后重试", Toast.LENGTH_LONG).show();
        }
    }

    private void showLanSettings() {
        try {
            LanSettingsServer.State state = lanSettings.start(this);
            lanSettings.setListener(this);
            updateRemoteStatus();
            showLanSettingsDialog(state.accessUrl);
        } catch (Throwable error) {
            message.setText("无法开启手机设置：" + rootMessage(error));
            DialogStyler.showConfirmation(this, "暂时无法扫码", rootMessage(error),
                    "关闭", "重试", this::showLanSettings);
        }
    }

    private void showLanSettingsDialog(String accessUrl) {
        if (remoteDialog != null && remoteDialog.isShowing()) remoteDialog.dismiss();
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_lan_settings, null, false);
        ImageView qr = content.findViewById(R.id.dialog_lan_qr);
        TextView status = content.findViewById(R.id.dialog_lan_status);
        Bitmap bitmap = QrCodes.encode(accessUrl, dp(240));
        if (bitmap == null) {
            message.setText("二维码生成失败，请重试。");
            return;
        }
        qr.setImageBitmap(bitmap);
        status.setText("已开启 · 等待手机扫码");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制链接", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("TVBox 手机设置", accessUrl));
                    status.setText("链接已复制 · 等待手机打开");
                    Toast.makeText(this, "手机设置链接已复制", Toast.LENGTH_SHORT).show();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        });
        dialog.setOnDismissListener(ignored -> {
            remoteDialog = null;
            updateRemoteStatus();
            remoteAction.requestFocus();
        });
        remoteDialog = dialog;
        dialog.show();
    }

    private void updateRemoteStatus() {
        if (remoteStatus == null) return;
        remoteStatus.setText(lanSettings.isRunning()
                ? "已开启 · 手机与电视需连接同一 Wi-Fi"
                : "同一 Wi-Fi 下，用手机编辑片源、播放与主题");
    }

    private void applyResponsiveChrome() {
        TvLayoutMetrics metrics = TvLayout.metrics(this);
        View decorContent = findViewById(android.R.id.content);
        if (!(decorContent instanceof ViewGroup group) || group.getChildCount() == 0) return;
        View root = group.getChildAt(0);
        View scroll = root instanceof ViewGroup rootGroup && rootGroup.getChildCount() > 0
                ? rootGroup.getChildAt(0) : null;
        View column = scroll instanceof ViewGroup scrollGroup && scrollGroup.getChildCount() > 0
                ? scrollGroup.getChildAt(0) : null;
        if (!(column instanceof ViewGroup columnGroup)) return;

        int margin = dp(metrics.contentMarginDp);
        columnGroup.setPadding(margin, columnGroup.getPaddingTop(),
                margin, dp(42));
        columnGroup.setMinimumHeight(Math.round(
                TvDisplayAdapter.viewport(this).usableHeightDp
                        * getResources().getDisplayMetrics().density));
        if (columnGroup.getChildCount() > 0
                && columnGroup.getChildAt(0) instanceof ViewGroup header) {
            ViewGroup.LayoutParams headerParams = header.getLayoutParams();
            if (headerParams != null) {
                headerParams.height = dp(metrics.settingsHeaderHeightDp);
                header.setLayoutParams(headerParams);
            }
            if (header.getChildCount() > 0
                    && header.getChildAt(0) instanceof TextView title) {
                title.setTextSize(metrics.pageTitleTextSizeSp);
            }
        }

        int[] rowIds = {
                R.id.settings_auto_next, R.id.settings_resume,
                R.id.settings_seek_step, R.id.settings_video_scale,
                R.id.settings_decoder_fallback, R.id.settings_overscan
        };
        for (int id : rowIds) {
            View row = findViewById(id);
            if (row == null) continue;
            ViewGroup.LayoutParams params = row.getLayoutParams();
            if (params != null) {
                params.height = dp(metrics.buttonHeightDp + 16f);
                row.setLayoutParams(params);
            }
        }
        int[] actionIds = {
                R.id.settings_add_source, R.id.settings_edit_source,
                R.id.settings_refresh_source, R.id.settings_delete_source,
                R.id.settings_remote_action, R.id.settings_clear_cache
        };
        for (int id : actionIds) {
            TvLayout.applyButtonHeight(findViewById(id), true);
        }
    }

    @Override
    public void onChanged(LanSettingsServer.Change change) {
        if (change == null || isFinishing() || isDestroyed()) return;
        if (change.sourcesChanged) {
            selectedSourceId = configStore.getActiveProfileId();
            rebuildSources(false);
            updateSummary();
            updateSourceActions();
        }
        if (change.themeChanged) {
            rebuildThemes();
            setResult(Activity.RESULT_OK);
        }
        if (change.message != null && !change.message.isBlank()) {
            message.setText(change.message + "。");
        }
        if (change.requiresReload) {
            reloadForRemoteChange(change);
        }
        if (remoteDialog != null && remoteDialog.isShowing()) {
            View qr = remoteDialog.findViewById(R.id.dialog_lan_qr);
            if (qr != null) {
                TextView status = remoteDialog.findViewById(R.id.dialog_lan_status);
                if (status != null) status.setText("手机已连接 · 设置正在同步");
            }
        }
    }

    private void reloadForRemoteChange(LanSettingsServer.Change change) {
        if (refreshing) {
            message.setText("已有配置任务正在执行，请稍后重试。");
            return;
        }
        File spiderBackup;
        try {
            spiderBackup = backupSpider();
        } catch (IOException error) {
            restoreRemoteChange(change);
            message.setText("无法备份当前 Spider，已撤销手机端更改：" + rootMessage(error));
            return;
        }
        refreshing = true;
        lanSettings.setMutationBlocked(true);
        updateSourceActions();
        message.setText("正在验证手机端片源更改…");
        TvBoxRepository.get().reload(this, new TvBoxRepository.LoadCallback() {
            @Override
            public void onHome(TvBoxRepository.Home home) {
                runOnUiThread(() -> {
                    refreshing = false;
                    lanSettings.setMutationBlocked(false);
                    deleteQuietly(spiderBackup);
                    setResult(Activity.RESULT_OK);
                    selectedSourceId = configStore.getActiveProfileId();
                    rebuildSources(true);
                    updateSummary();
                    message.setText("手机端片源已生效，站点与首页均已更新。");
                    Toast.makeText(SettingsActivity.this,
                            "手机端设置已同步", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    restoreRemoteChange(change);
                    restoreSpider(spiderBackup);
                    refreshing = false;
                    lanSettings.setMutationBlocked(false);
                    selectedSourceId = configStore.getActiveProfileId();
                    rebuildSources(true);
                    updateSummary();
                    message.setText("片源验证失败，已恢复原配置：" + rootMessage(error));
                    Toast.makeText(SettingsActivity.this,
                            "验证失败，已恢复原片源", Toast.LENGTH_LONG).show();
                });
            }
        });
        updateSourceActions();
    }

    private void restoreRemoteChange(LanSettingsServer.Change change) {
        if (change.previousConfig != null) configStore.restore(change.previousConfig);
        if (change.previousRuntime != null) {
            TvBoxRepository.get().restoreRuntime(change.previousRuntime);
        }
    }

    private void bindPlayerSettings() {
        LinearLayout autoNext = findViewById(R.id.settings_auto_next);
        LinearLayout resume = findViewById(R.id.settings_resume);
        LinearLayout seek = findViewById(R.id.settings_seek_step);
        LinearLayout scale = findViewById(R.id.settings_video_scale);
        LinearLayout fallback = findViewById(R.id.settings_decoder_fallback);
        LinearLayout overscan = findViewById(R.id.settings_overscan);
        FocusAnimator.scale(autoNext, 1.012f);
        FocusAnimator.scale(resume, 1.012f);
        FocusAnimator.scale(seek, 1.012f);
        FocusAnimator.scale(scale, 1.012f);
        FocusAnimator.scale(fallback, 1.012f);
        FocusAnimator.scale(overscan, 1.012f);

        autoNext.setOnClickListener(view -> {
            if (refreshing) return;
            appSettings.setAutoNextEpisode(!appSettings.isAutoNextEpisode());
            updatePlayerValues();
        });
        resume.setOnClickListener(view -> {
            if (refreshing) return;
            appSettings.setResumePlayback(!appSettings.isResumePlayback());
            updatePlayerValues();
        });
        seek.setOnClickListener(view -> {
            if (refreshing) return;
            int[] options = AppSettings.SEEK_OPTIONS;
            int current = appSettings.getSeekStepSeconds();
            int next = options[0];
            for (int index = 0; index < options.length; index++) {
                if (options[index] == current) {
                    next = options[(index + 1) % options.length];
                    break;
                }
            }
            appSettings.setSeekStepSeconds(next);
            updatePlayerValues();
        });
        scale.setOnClickListener(view -> {
            if (refreshing) return;
            appSettings.setVideoScale((appSettings.getVideoScale() + 1) % 3);
            updatePlayerValues();
        });
        fallback.setOnClickListener(view -> {
            if (refreshing) return;
            appSettings.setDecoderFallback(!appSettings.isDecoderFallback());
            updatePlayerValues();
        });
        overscan.setOnClickListener(view -> {
            if (refreshing) return;
            int current = appSettings.getOverscanPermille();
            int next = AppSettings.OVERSCAN_OPTIONS_PERMILLE[0];
            for (int index = 0; index < AppSettings.OVERSCAN_OPTIONS_PERMILLE.length; index++) {
                if (AppSettings.OVERSCAN_OPTIONS_PERMILLE[index] == current) {
                    next = AppSettings.OVERSCAN_OPTIONS_PERMILLE[
                            (index + 1) % AppSettings.OVERSCAN_OPTIONS_PERMILLE.length];
                    break;
                }
            }
            appSettings.setOverscanPermille(next);
            updatePlayerValues();
            view.postDelayed(this::recreate, 120L);
        });
        updatePlayerValues();
    }

    private void updatePlayerValues() {
        autoNextValue.setText(appSettings.isAutoNextEpisode() ? "开启" : "关闭");
        resumeValue.setText(appSettings.isResumePlayback() ? "开启" : "关闭");
        seekStepValue.setText(appSettings.getSeekStepSeconds() + " 秒");
        videoScaleValue.setText(AppSettings.VIDEO_SCALE_NAMES[appSettings.getVideoScale()]);
        decoderFallbackValue.setText(appSettings.isDecoderFallback() ? "开启" : "关闭");
        int overscan = appSettings.getOverscanPermille();
        String overscanName = AppSettings.OVERSCAN_OPTION_NAMES[0];
        for (int index = 0; index < AppSettings.OVERSCAN_OPTIONS_PERMILLE.length; index++) {
            if (AppSettings.OVERSCAN_OPTIONS_PERMILLE[index] == overscan) {
                overscanName = AppSettings.OVERSCAN_OPTION_NAMES[index];
                break;
            }
        }
        overscanValue.setText(overscanName);
    }

    private void rebuildSources(boolean focusSelected) {
        loadedSources.clear();
        loadedSources.addAll(configStore.getSources());
        String activeId = configStore.getActiveProfileId();
        if (findSource(selectedSourceId) == null) selectedSourceId = activeId;

        sourceList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        List<View> sourceViews = new ArrayList<>();
        int focusIndex = -1;
        for (int index = 0; index < loadedSources.size(); index++) {
            ConfigStore.SourceProfile profile = loadedSources.get(index);
            View item = inflater.inflate(R.layout.item_source_profile, sourceList, false);
            TextView number = item.findViewById(R.id.source_number);
            TextView name = item.findViewById(R.id.source_name);
            TextView url = item.findViewById(R.id.source_url);
            TextView badge = item.findViewById(R.id.source_badge);
            number.setText(String.format(Locale.getDefault(), "%02d", index + 1));
            name.setText(profile.name);
            url.setText(profile.url);
            boolean active = profile.id.equals(activeId);
            item.setSelected(active);
            badge.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            item.setOnClickListener(view -> {
                if (refreshing) return;
                selectedSourceId = profile.id;
                updateSourceActions();
                if (!profile.id.equals(configStore.getActiveProfileId())) {
                    confirmActivate(profile.id);
                }
            });
            item.setOnLongClickListener(view -> {
                if (!refreshing) showSourceDialog(profile);
                return true;
            });
            FocusAnimator.scale(item, 1.012f, () -> {
                selectedSourceId = profile.id;
                updateSourceActions();
            });
            item.setId(View.generateViewId());
            sourceList.addView(item);
            sourceViews.add(item);
            if (profile.id.equals(selectedSourceId)) focusIndex = index;
        }
        for (int index = 0; index < sourceViews.size() - 1; index++) {
            sourceViews.get(index).setNextFocusDownId(sourceViews.get(index + 1).getId());
        }
        if (!sourceViews.isEmpty()) {
            int lastSourceId = sourceViews.get(sourceViews.size() - 1).getId();
            sourceViews.get(sourceViews.size() - 1).setNextFocusDownId(R.id.settings_add_source);
            addSource.setNextFocusUpId(lastSourceId);
            editSource.setNextFocusUpId(lastSourceId);
            activateSource.setNextFocusUpId(lastSourceId);
            refreshSource.setNextFocusUpId(lastSourceId);
            deleteSource.setNextFocusUpId(lastSourceId);
        }
        updateSourceActions();
        updateSummary();
        if (focusSelected && focusIndex >= 0) {
            int target = focusIndex;
            sourceList.post(() -> {
                if (target < sourceList.getChildCount()) {
                    sourceList.getChildAt(target).requestFocus();
                }
            });
        }
    }

    private void rebuildThemes() {
        themeList.removeAllViews();
        String activeKey = appSettings.getThemeKey();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (ThemeManager.Preset preset : ThemeManager.PRESETS) {
            View item = inflater.inflate(R.layout.item_theme_swatch, themeList, false);
            View dot = item.findViewById(R.id.theme_dot);
            TextView label = item.findViewById(R.id.theme_label);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(getColor(preset.swatchColor));
            circle.setStroke(dp(2), 0x66FFFFFF);
            dot.setBackground(circle);
            label.setText(preset.label);
            item.setSelected(preset.key.equals(activeKey));
            item.setOnClickListener(view -> {
                if (refreshing || preset.key.equals(appSettings.getThemeKey())) return;
                appSettings.setThemeKey(preset.key);
                setResult(Activity.RESULT_OK);
                Toast.makeText(this, "已切换为" + preset.label + "主题", Toast.LENGTH_SHORT).show();
                view.postDelayed(this::recreate, 80L);
            });
            FocusAnimator.scale(item, 1.045f);
            themeList.addView(item);
        }
        themeName.setText("当前主题：" + ThemeManager.preset(activeKey).label);
    }

    private void updateSummary() {
        ConfigStore.SourceProfile active = configStore.getActiveSource();
        if (active != null) {
            sourceName.setText(active.name);
            sourceUrl.setText(active.url);
        } else {
            sourceName.setText("尚未配置片源");
            sourceUrl.setText(ConfigStore.DEFAULT_URL);
        }
        sourceCount.setText(loadedSources.size() + " 套片源");
        String siteName = TvBoxRepository.get().getHomeSite() == null
                ? "尚未连接站点" : TvBoxRepository.get().getHomeSite().displayName();
        status.setText("当前站点：" + siteName + " · 设置会自动保存");
        updateRemoteStatus();
    }

    private void updateSourceActions() {
        ConfigStore.SourceProfile selected = selectedSource();
        String activeId = configStore.getActiveProfileId();
        boolean hasSelected = selected != null;
        boolean activeSelected = hasSelected && selected.id.equals(activeId);
        boolean busy = refreshing;
        editSource.setEnabled(!busy && hasSelected);
        activateSource.setEnabled(!busy && hasSelected && !activeSelected);
        refreshSource.setEnabled(!busy && !loadedSources.isEmpty());
        deleteSource.setEnabled(!busy && hasSelected && !activeSelected
                && loadedSources.size() > 1);
        addSource.setEnabled(!busy);
        setActionAlpha(editSource, editSource.isEnabled());
        setActionAlpha(activateSource, activateSource.isEnabled());
        setActionAlpha(refreshSource, refreshSource.isEnabled());
        setActionAlpha(deleteSource, deleteSource.isEnabled());
        setActionAlpha(addSource, addSource.isEnabled());
    }

    private void confirmActivate(String id) {
        if (refreshing) return;
        ConfigStore.SourceProfile target = findSource(id);
        if (target == null) return;
        if (target.id.equals(configStore.getActiveProfileId())) {
            message.setText("“" + target.name + "”已经是在用片源。");
            return;
        }
        DialogStyler.showConfirmation(this, "启用片源“" + target.name + "”？",
                "验证失败会恢复当前片源。", "取消", "切换并验证",
                () -> performActivate(id));
    }

    private void performActivate(String id) {
        if (refreshing) return;
        ConfigStore.Snapshot previous = configStore.snapshot();
        TvBoxRepository.RuntimeSnapshot runtime = TvBoxRepository.get().snapshotRuntime();
        File spiderBackup;
        try {
            spiderBackup = backupSpider();
        } catch (IOException error) {
            message.setText("无法备份当前 Spider，已取消切换：" + rootMessage(error));
            return;
        }
        if (!configStore.activateSource(id)) {
            deleteQuietly(spiderBackup);
            message.setText("片源档案不存在，请刷新设置页后重试。");
            return;
        }
        selectedSourceId = id;
        reloadAfterMutation(previous, runtime, spiderBackup, "片源已切换");
    }

    private void refreshCurrentSource() {
        if (refreshing) return;
        ConfigStore.Snapshot previous = configStore.snapshot();
        TvBoxRepository.RuntimeSnapshot runtime = TvBoxRepository.get().snapshotRuntime();
        File spiderBackup;
        try {
            spiderBackup = backupSpider();
        } catch (IOException error) {
            message.setText("刷新前无法备份 Spider：" + rootMessage(error));
            return;
        }
        configStore.invalidate();
        reloadAfterMutation(previous, runtime, spiderBackup, "当前片源已刷新");
    }

    private void showSourceDialog(ConfigStore.SourceProfile source) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_source_profile, null, false);
        EditText name = content.findViewById(R.id.dialog_source_name);
        EditText url = content.findViewById(R.id.dialog_source_url);
        EditText userAgent = content.findViewById(R.id.dialog_source_ua);

        boolean editing = source != null;
        if (editing) {
            name.setText(source.name);
            url.setText(source.url);
            userAgent.setText(source.userAgent);
        } else {
            url.setText(ConfigStore.DEFAULT_URL);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "编辑 TVBox 片源" : "添加 TVBox 片源")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String sourceName = name.getText().toString().trim();
                String address = url.getText().toString().trim();
                String agent = userAgent.getText().toString().trim();
                if (!isHttpAddress(address)) {
                    url.setError("配置地址必须以 http:// 或 https:// 开头");
                    url.requestFocus();
                    return;
                }
                dialog.dismiss();
                if (editing) updateSource(source, sourceName, address, agent);
                else addAndActivateSource(sourceName, address, agent);
            });
            name.requestFocus();
        });
        dialog.show();
    }

    private void addAndActivateSource(String name, String address, String userAgent) {
        ConfigStore.Snapshot previous = configStore.snapshot();
        TvBoxRepository.RuntimeSnapshot runtime = TvBoxRepository.get().snapshotRuntime();
        File spiderBackup;
        try {
            spiderBackup = backupSpider();
        } catch (IOException error) {
            message.setText("无法备份当前 Spider，已取消添加：" + rootMessage(error));
            return;
        }
        ConfigStore.SourceProfile added = configStore.addSource(name, address, userAgent);
        if (!configStore.activateSource(added.id)) {
            restoreImmediately(previous, runtime, spiderBackup);
            message.setText("新片源保存失败，已恢复原配置。");
            return;
        }
        selectedSourceId = added.id;
        reloadAfterMutation(previous, runtime, spiderBackup, "新片源已添加");
    }

    private void updateSource(ConfigStore.SourceProfile previousSource,
                              String name, String address, String userAgent) {
        boolean active = previousSource.id.equals(configStore.getActiveProfileId());
        boolean addressChanged = !previousSource.url.equals(address);
        if (!active || !addressChanged) {
            if (!configStore.updateSource(previousSource.id, name, address, userAgent)) {
                message.setText("片源保存失败，请重试。");
                return;
            }
            rebuildSources(true);
            message.setText(addressChanged
                    ? "片源已保存。启用该片源后生效。"
                    : "片源信息已更新。");
            return;
        }

        ConfigStore.Snapshot previous = configStore.snapshot();
        TvBoxRepository.RuntimeSnapshot runtime = TvBoxRepository.get().snapshotRuntime();
        File spiderBackup;
        try {
            spiderBackup = backupSpider();
        } catch (IOException error) {
            message.setText("无法备份当前 Spider，已取消修改：" + rootMessage(error));
            return;
        }
        if (!configStore.updateSource(previousSource.id, name, address, userAgent)) {
            deleteQuietly(spiderBackup);
            message.setText("片源保存失败，请重试。");
            return;
        }
        reloadAfterMutation(previous, runtime, spiderBackup, "片源配置已更新");
    }

    private void reloadAfterMutation(ConfigStore.Snapshot previous,
                                     TvBoxRepository.RuntimeSnapshot runtime,
                                     File spiderBackup, String successText) {
        refreshing = true;
        updateSourceActions();
        message.setText("正在下载并验证片源配置…");
        configStore.invalidate();
        TvBoxRepository.get().reload(this, new TvBoxRepository.LoadCallback() {
            @Override
            public void onHome(TvBoxRepository.Home home) {
                runOnUiThread(() -> {
                    refreshing = false;
                    deleteQuietly(spiderBackup);
                    setResult(Activity.RESULT_OK);
                    selectedSourceId = configStore.getActiveProfileId();
                    rebuildSources(true);
                    message.setText(successText + "，站点与首页均已更新。");
                    Toast.makeText(SettingsActivity.this,
                            successText, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> restorePrevious(previous, runtime, spiderBackup,
                        rootMessage(error)));
            }
        });
    }

    private void restorePrevious(ConfigStore.Snapshot previous,
                                 TvBoxRepository.RuntimeSnapshot runtime,
                                 File spiderBackup, String failure) {
        restoreImmediately(previous, runtime, spiderBackup);
        refreshing = false;
        selectedSourceId = configStore.getActiveProfileId();
        rebuildSources(true);
        message.setText("新配置验证失败，已恢复原片源：" + failure);
        Toast.makeText(this, "已恢复原片源", Toast.LENGTH_LONG).show();
    }

    private void restoreImmediately(ConfigStore.Snapshot previous,
                                    TvBoxRepository.RuntimeSnapshot runtime,
                                    File spiderBackup) {
        configStore.restore(previous);
        restoreSpider(spiderBackup);
        TvBoxRepository.get().restoreRuntime(runtime);
    }

    private void confirmDelete() {
        if (refreshing) return;
        ConfigStore.SourceProfile selected = selectedSource();
        if (selected == null) return;
        if (selected.id.equals(configStore.getActiveProfileId())) {
            message.setText("请先启用其他片源，再删除当前在用片源。");
            return;
        }
        DialogStyler.showConfirmation(this, "删除片源“" + selected.name + "”？",
                "仅删除本地配置。", "取消", "删除", () -> {
                    if (configStore.removeSource(selected.id)) {
                        selectedSourceId = configStore.getActiveProfileId();
                        rebuildSources(true);
                        message.setText("片源档案已删除。");
                    } else {
                        message.setText("无法删除该片源。");
                    }
                });
    }

    private void styleDialog(AlertDialog dialog) {
        DialogStyler.style(dialog);
    }

    private ConfigStore.SourceProfile selectedSource() {
        return findSource(selectedSourceId);
    }

    private ConfigStore.SourceProfile findSource(String id) {
        if (id == null || id.isBlank()) return null;
        for (ConfigStore.SourceProfile profile : loadedSources) {
            if (id.equals(profile.id)) return profile;
        }
        return null;
    }

    private static boolean isHttpAddress(String address) {
        return address.startsWith("http://") || address.startsWith("https://");
    }

    private static void setActionAlpha(View view, boolean enabled) {
        view.setAlpha(enabled ? 1f : 0.45f);
    }

    private File backupSpider() throws IOException {
        File source = new File(configStore.getCacheDir(), "spider.jar");
        File backup = new File(configStore.getCacheDir(), "spider.jar.rollback");
        deleteQuietly(backup);
        if (!source.isFile()) return null;
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(backup)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException error) {
            deleteQuietly(backup);
            throw error;
        }
        return backup;
    }

    private void restoreSpider(File backup) {
        File current = new File(configStore.getCacheDir(), "spider.jar");
        deleteQuietly(current);
        if (backup != null && backup.isFile()) backup.renameTo(current);
        deleteQuietly(backup);
    }

    private void updateVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionText(info);
        } catch (Exception ignored) {
            ((TextView) findViewById(R.id.settings_version))
                    .setText("TVBox · Android TV");
        }
    }

    private void versionText(PackageInfo info) {
        TextView version = findViewById(R.id.settings_version);
        version.setText("TVBox " + info.versionName + " · Android TV");
    }

    private void updateCacheSize() {
        cacheSize.setText("正在计算缓存…");
        new Thread(() -> {
            long bytes = sizeOf(getCacheDir());
            String text = "缓存 " + formatBytes(bytes);
            runOnUiThread(() -> {
                if (!isFinishing()) cacheSize.setText(text);
            });
        }, "settings-cache-size").start();
    }

    private void clearImageCache() {
        Glide.get(this).clearMemory();
        message.setText("正在清理图片缓存…");
        new Thread(() -> {
            String result;
            try {
                Glide.get(getApplicationContext()).clearDiskCache();
                result = "图片缓存已清理。";
            } catch (Throwable error) {
                result = "清理失败：" + rootMessage(error);
            }
            String finalResult = result;
            runOnUiThread(() -> {
                message.setText(finalResult);
                updateCacheSize();
            });
        }, "image-cache-clear").start();
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children == null) return 0;
        for (File child : children) total += sizeOf(child);
        return total;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024d;
        if (value < 1024) return String.format(Locale.getDefault(), "%.1f KB", value);
        value /= 1024d;
        if (value < 1024) return String.format(Locale.getDefault(), "%.1f MB", value);
        return String.format(Locale.getDefault(), "%.2f GB", value / 1024d);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) file.delete();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? "未知错误"
                : String.valueOf(current.getMessage() == null ? current : current.getMessage());
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
    protected void onStop() {
        if (remoteDialog != null && remoteDialog.isShowing()) remoteDialog.dismiss();
        lanSettings.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        lanSettings.stop();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }
}
