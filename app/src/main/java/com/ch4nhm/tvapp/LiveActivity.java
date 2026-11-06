package com.ch4nhm.tvapp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ch4nhm.tvapp.data.ConfigStore;
import com.ch4nhm.tvapp.data.TvBoxConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LiveActivity extends BaseActivity {

    private final List<TvBoxConfig.Live> liveSources = new ArrayList<>();
    private final List<LiveRepository.LiveChannel> visibleChannels = new ArrayList<>();
    private AppSettings appSettings;
    private TvBoxConfig config;
    private LiveRepository.LiveSource currentSource;
    private LiveChannelAdapter adapter;
    private RecyclerView channelList;
    private GridLayoutManager layoutManager;
    private LinearLayout groupBar;
    private TextView sourceSelector;
    private View filterDivider;
    private LinearLayout state;
    private ProgressBar progress;
    private TextView message;
    private TextView retry;
    private String selectedGroup = "";
    private int selectedSourceIndex;
    private int loadGeneration;
    private boolean loading;
    private String liveSignature = "";
    private TvLayoutMetrics layoutMetrics;
    private final Set<Integer> autoFallbackSources = new HashSet<>();

    public static void start(Context context) {
        context.startActivity(new Intent(context, LiveActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_live);
        enterImmersive();
        appSettings = new AppSettings(this);

        bindViews();
        bindGrid();
        applyResponsiveChrome();

        TvBoxConfig cached = TvBoxRepository.get().getConfig();
        if (cached == null) cached = new ConfigStore(this).getConfig();
        if (cached != null) {
            openConfig(cached);
        } else {
            loadConfigThenOpen();
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });
    }

    private void bindViews() {
        sourceSelector = findViewById(R.id.live_source_selector);
        filterDivider = findViewById(R.id.live_filter_divider);
        groupBar = findViewById(R.id.live_group_bar);
        channelList = findViewById(R.id.live_channel_list);
        state = findViewById(R.id.live_state);
        progress = findViewById(R.id.live_progress);
        message = findViewById(R.id.live_message);
        retry = findViewById(R.id.live_retry);
        FocusAnimator.scale(sourceSelector, 1.04f);
        FocusAnimator.scale(retry, 1.04f);
        sourceSelector.setOnClickListener(view -> showSourcePicker());
        retry.setOnClickListener(view -> loadSelectedSource());
    }

    private void bindGrid() {
        int spanCount = TvDisplayAdapter.channelGridSpanCount(this);
        layoutManager = new GridLayoutManager(this, spanCount);
        channelList.setLayoutManager(layoutManager);
        channelList.setItemAnimator(null);
        channelList.setClipChildren(false);
        channelList.setClipToPadding(false);
        GridSpacingDecoration.addPx(channelList, TvDisplayAdapter.channelGapPx(this));
        adapter = new LiveChannelAdapter(this::openChannel);
        channelList.setAdapter(adapter);
    }

    private void loadConfigThenOpen() {
        showLoading("正在加载 TVBox 配置…");
        TvBoxRepository.get().load(this, new TvBoxRepository.LoadCallback() {
            @Override
            public void onHome(TvBoxRepository.Home home) {
                runOnUiThread(() -> {
                    TvBoxConfig loaded = TvBoxRepository.get().getConfig();
                    if (loaded == null) {
                        showError("配置已下载，但没有读取到直播源。");
                    } else {
                        openConfig(loaded);
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> showError("配置加载失败：" + rootMessage(error)));
            }
        });
    }

    private void openConfig(TvBoxConfig loaded) {
        config = loaded;
        liveSignature = signature(loaded);
        liveSources.clear();
        if (loaded.lives != null) {
            for (TvBoxConfig.Live live : loaded.lives) {
                if (live != null && (!safe(live.url).isBlank() || !safe(live.api).isBlank())) {
                    liveSources.add(live);
                }
            }
        }
        if (liveSources.isEmpty()) {
            sourceSelector.setVisibility(View.GONE);
            filterDivider.setVisibility(View.GONE);
            groupBar.removeAllViews();
            showError("当前配置没有 lives 直播源。可在设置中切换包含直播的 TVBox 配置。");
            return;
        }

        selectedSourceIndex = findSavedSource();
        if (selectedSourceIndex < 0) selectedSourceIndex = 0;
        renderSourceSelector();
        sourceSelector.requestFocus();
        loadSelectedSource();
    }

    private int findSavedSource() {
        String saved = appSettings.getLastLiveSourceUrl();
        if (saved == null || saved.isBlank()) return -1;
        for (int index = 0; index < liveSources.size(); index++) {
            if (saved.equals(liveSources.get(index).url)) return index;
        }
        return -1;
    }

    private void renderSourceSelector() {
        if (selectedSourceIndex < 0 || selectedSourceIndex >= liveSources.size()) return;
        TvBoxConfig.Live live = liveSources.get(selectedSourceIndex);
        String name = safe(live.name).isBlank()
                ? "直播源 " + (selectedSourceIndex + 1) : live.name.trim();
        boolean hasChoices = liveSources.size() > 1;
        sourceSelector.setText(name);
        sourceSelector.setContentDescription(
                hasChoices ? "当前直播源：" + name + "，按确定切换" : "当前直播源：" + name);
        sourceSelector.setClickable(hasChoices);
        sourceSelector.setFocusable(hasChoices);
        sourceSelector.setAlpha(hasChoices ? 1f : 0.82f);
        sourceSelector.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, hasChoices ? R.drawable.ic_chevron_down : 0, 0);
        filterDivider.setVisibility(View.VISIBLE);
    }

    private void showSourcePicker() {
        if (loading || liveSources.size() <= 1) return;
        String[] labels = new String[liveSources.size()];
        for (int index = 0; index < liveSources.size(); index++) {
            String name = safe(liveSources.get(index).name).trim();
            labels[index] = name.isEmpty() ? "直播源 " + (index + 1) : name;
        }
        AlertDialog dialog = DialogStyler.showOptions(
                this, "直播源", labels, selectedSourceIndex, which -> {
                    if (which == selectedSourceIndex) return;
                    autoFallbackSources.clear();
                    selectedSourceIndex = which;
                    selectedGroup = "";
                    renderSourceSelector();
                    loadSelectedSource();
                });
        dialog.setOnDismissListener(ignored -> sourceSelector.requestFocus());
    }

    private TextView createChip(String label, boolean selected, String tag) {
        TextView chip = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, chipHeightPx());
        params.setMarginEnd(dp(8));
        chip.setLayoutParams(params);
        chip.setText(label);
        chip.setTextColor(getColorStateList(R.color.filter_chip_text));
        chip.setTextSize(15);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(16), 0, dp(16), 0);
        chip.setBackgroundResource(R.drawable.bg_filter_chip);
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setSelected(selected);
        chip.setTag(tag);
        FocusAnimator.scale(chip, 1.04f, () -> chip.post(() ->
                chip.requestRectangleOnScreen(
                        new Rect(0, 0, chip.getWidth(), chip.getHeight()), true)));
        return chip;
    }

    private View liveTopTarget() {
        View selected = groupBar.findViewWithTag(selectedGroup.isEmpty()
                ? "live-group-all" : "live-group-" + selectedGroup);
        if (selected != null && selected.isShown() && selected.isFocusable()) return selected;
        if (groupBar.getChildCount() > 0) {
            View first = groupBar.getChildAt(0);
            if (first.isShown() && first.isFocusable()) return first;
        }
        if (sourceSelector.isShown() && sourceSelector.isFocusable()) return sourceSelector;
        return groupBar;
    }

    private void loadSelectedSource() {
        autoFallbackSources.clear();
        loadSourceAt(selectedSourceIndex);
    }

    private void loadSourceAt(int sourceIndex) {
        if (config == null || sourceIndex < 0
                || sourceIndex >= liveSources.size()) {
            showError("没有可用的直播源。");
            return;
        }
        selectedSourceIndex = sourceIndex;
        TvBoxConfig.Live live = liveSources.get(sourceIndex);
        String address = safe(live.url).trim();
        if (address.isEmpty()) address = safe(live.api).trim();
        if (address.isEmpty()) {
            if (tryNextLiveSource(sourceIndex)) return;
            showError("直播源“" + safe(live.name) + "”没有地址。");
            return;
        }
        int generation = ++loadGeneration;
        loading = true;
        currentSource = null;
        visibleChannels.clear();
        adapter.update(visibleChannels);
        channelList.scrollToPosition(0);
        groupBar.removeAllViews();
        renderSourceSelector();
        appSettings.setLastLiveSourceUrl(address);
        showLoading("正在连接直播");

        LiveRepository.get().load(this, config, live,
                new LiveRepository.Callback() {
                    @Override
                    public void onLoaded(LiveRepository.LiveSource source) {
                        runOnUiThread(() -> {
                            if (generation != loadGeneration || isFinishing()
                                    || isDestroyed()) return;
                            loading = false;
                            currentSource = source;
                            renderGroupChips(false);
                            applyFilter(true);
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        runOnUiThread(() -> {
                            if (generation != loadGeneration) return;
                            loading = false;
                            if (tryNextLiveSource(sourceIndex)) return;
                            showError("直播源加载失败：" + rootMessage(error));
                            renderSourceSelector();
                        });
                    }
                });
    }

    private boolean tryNextLiveSource(int failedIndex) {
        autoFallbackSources.add(failedIndex);
        if (liveSources.size() <= 1) return false;
        for (int offset = 1; offset < liveSources.size(); offset++) {
            int candidate = (failedIndex + offset) % liveSources.size();
            if (autoFallbackSources.contains(candidate)) continue;
            TvBoxConfig.Live next = liveSources.get(candidate);
            if (safe(next.url).isBlank() && safe(next.api).isBlank()) continue;
            selectedSourceIndex = candidate;
            selectedGroup = "";
            renderSourceSelector();
            String name = safe(next.name).trim();
            if (name.isEmpty()) name = "直播源 " + (candidate + 1);
            Toast.makeText(this, "当前直播源不可用，已切换到「" + name + "」",
                    Toast.LENGTH_SHORT).show();
            loadSourceAt(candidate);
            return true;
        }
        return false;
    }

    private void renderGroupChips(boolean focusSelection) {
        groupBar.removeAllViews();
        if (currentSource == null) return;
        TextView all = createChip("全部 " + currentSource.channels.size(),
                selectedGroup.isEmpty(), "live-group-all");
        all.setOnClickListener(view -> selectGroup(""));
        groupBar.addView(all);
        for (String group : currentSource.groups) {
            int total = currentSource.channelsInGroup(group).size();
            TextView chip = createChip(group + " " + total,
                    group.equals(selectedGroup), "live-group-" + group);
            chip.setOnClickListener(view -> selectGroup(group));
            groupBar.addView(chip);
        }
        if (focusSelection) {
            TextView selectedChip = groupBar.findViewWithTag(
                    selectedGroup.isEmpty() ? "live-group-all" : "live-group-" + selectedGroup);
            if (selectedChip != null) selectedChip.requestFocus();
        }
    }

    private void selectGroup(String group) {
        if (loading || currentSource == null) return;
        selectedGroup = safe(group);
        renderGroupChips(true);
        applyFilter(false);
    }

    private void applyFilter(boolean focusFirstChannel) {
        if (currentSource == null) return;
        visibleChannels.clear();
        for (LiveRepository.LiveChannel channel : currentSource.channels) {
            if (!selectedGroup.isBlank() && !selectedGroup.equals(channel.group)) continue;
            visibleChannels.add(channel);
        }
        adapter.update(visibleChannels);
        if (visibleChannels.isEmpty()) {
            channelList.setVisibility(View.GONE);
            state.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            retry.setVisibility(View.GONE);
            message.setText("这个分组暂时没有频道。");
            if (sourceSelector.isShown() && sourceSelector.isFocusable()) {
                sourceSelector.requestFocus();
            }
            return;
        }
        state.setVisibility(View.GONE);
        channelList.setVisibility(View.VISIBLE);
        channelList.scrollToPosition(0);
        if (focusFirstChannel) {
            channelList.post(() -> {
                RecyclerView.ViewHolder holder =
                        channelList.findViewHolderForAdapterPosition(0);
                if (holder != null) holder.itemView.requestFocus();
                else if (sourceSelector.isShown() && sourceSelector.isFocusable()) {
                    sourceSelector.requestFocus();
                }
            });
        }
    }

    private void openChannel(LiveRepository.LiveChannel channel) {
        if (currentSource == null || channel == null || channel.streams.isEmpty()) {
            Toast.makeText(this, "这个频道暂时没有播放线路", Toast.LENGTH_SHORT).show();
            return;
        }
        LivePlayerActivity.start(this, currentSource.sourceUrl, channel.id,
                selectedGroup, "");
    }

    private void showLoading(String detail) {
        loading = true;
        channelList.setVisibility(View.GONE);
        state.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        message.setText(detail);
    }

    private void showError(String detail) {
        loading = false;
        channelList.setVisibility(View.GONE);
        state.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        retry.setVisibility(View.VISIBLE);
        message.setText(detail);
        retry.requestFocus();
    }

    private static String signature(TvBoxConfig config) {
        if (config == null || config.lives == null) return "";
        StringBuilder result = new StringBuilder();
        for (TvBoxConfig.Live live : config.lives) {
            if (live != null) result.append(live.name).append('|')
                    .append(live.url).append('|').append(live.ua).append('\n');
        }
        return result.toString();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? "未知错误"
                : String.valueOf(current.getMessage() == null ? current : current.getMessage());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private void applyResponsiveChrome() {
        layoutMetrics = TvLayout.metrics(this);
        int margin = dp(layoutMetrics.contentMarginDp);
        View content = findViewById(R.id.live_content);
        if (content != null) {
            content.setPadding(margin, content.getPaddingTop(),
                    margin, content.getPaddingBottom());
        }
        View header = findViewById(R.id.live_header);
        if (header != null) {
            ViewGroup.LayoutParams params = header.getLayoutParams();
            if (params != null) {
                params.height = dp(layoutMetrics.liveHeaderHeightDp);
                header.setLayoutParams(params);
            }
        }
        channelList.setPadding(dp(8), dp(8), dp(8), dp(28));
    }

    private int chipHeightPx() {
        if (layoutMetrics == null) layoutMetrics = TvLayout.metrics(this);
        return dp(Math.max(36f, layoutMetrics.liveHeaderHeightDp - 20f));
    }

    private int dp(float value) {
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (TvFocus.returnToTopOnUp(event, channelList, liveTopTarget())) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TvBoxConfig latest = TvBoxRepository.get().getConfig();
        if (latest != null && !liveSignature.equals(signature(latest)) && !loading) {
            openConfig(latest);
        }
    }
}
