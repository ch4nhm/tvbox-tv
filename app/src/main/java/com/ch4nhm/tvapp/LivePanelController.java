package com.ch4nhm.tvapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ch4nhm.tvapp.data.ConfigStore;
import com.ch4nhm.tvapp.data.TvBoxConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LivePanelController {

    private final Activity activity;
    private final View root;
    private final List<TvBoxConfig.Live> liveSources = new ArrayList<>();
    private final List<LiveRepository.LiveChannel> visibleChannels = new ArrayList<>();
    private final Set<Integer> failedSourceIndexes = new HashSet<>();
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
    private boolean initialized;
    private boolean loading;
    private boolean focusContentWhenReady;
    private String liveSignature = "";
    private String fallbackFromSource = "";
    private TvLayoutMetrics layoutMetrics;

    LivePanelController(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
        appSettings = new AppSettings(activity);

        sourceSelector = root.findViewById(R.id.live_source_selector);
        filterDivider = root.findViewById(R.id.live_filter_divider);
        groupBar = root.findViewById(R.id.live_group_bar);
        channelList = root.findViewById(R.id.live_channel_list);
        state = root.findViewById(R.id.live_state);
        progress = root.findViewById(R.id.live_progress);
        message = root.findViewById(R.id.live_message);
        retry = root.findViewById(R.id.live_retry);
        FocusAnimator.scale(sourceSelector, 1.04f);
        FocusAnimator.scale(retry, 1.04f);
        sourceSelector.setOnClickListener(view -> showSourcePicker());
        retry.setOnClickListener(view -> loadSelectedSource());
        bindGrid();
        applyResponsiveChrome();
    }

    void show(boolean focusContent) {
        if (!initialized) {
            initialized = true;
            TvBoxConfig cached = TvBoxRepository.get().getConfig();
            if (cached == null) cached = new ConfigStore(activity).getConfig();
            if (cached != null) {
                openConfig(cached);
            } else {
                loadConfigThenOpen();
            }
        }
        if (focusContent) requestContentFocus();
    }

    void onResume() {
        if (!initialized || loading) return;
        TvBoxConfig latest = TvBoxRepository.get().getConfig();
        if (latest != null && !liveSignature.equals(signature(latest))) openConfig(latest);
    }

    void destroy() {
        loadGeneration++;
    }

    void requestContentFocus() {
        focusContentWhenReady = true;
        if (loading) return;
        if (retry.isShown()) {
            retry.requestFocus();
            focusContentWhenReady = false;
            return;
        }
        if (adapter != null && adapter.getItemCount() > 0
                && channelList.getVisibility() == View.VISIBLE) {
            channelList.post(() -> {
                RecyclerView.ViewHolder holder =
                        channelList.findViewHolderForAdapterPosition(0);
                if (holder != null) holder.itemView.requestFocus();
                else if (sourceSelector.isShown() && sourceSelector.isFocusable()) {
                    sourceSelector.requestFocus();
                }
            });
            focusContentWhenReady = false;
            return;
        }
        if (sourceSelector.isShown() && sourceSelector.isFocusable()) {
            sourceSelector.requestFocus();
            focusContentWhenReady = false;
        }
    }

    RecyclerView channelView() {
        return channelList;
    }

    View topFilterTarget() {
        if (groupBar != null) {
            View selected = groupBar.findViewWithTag(selectedGroup.isEmpty()
                    ? "live-group-all" : "live-group-" + selectedGroup);
            if (selected != null && selected.isShown() && selected.isFocusable()) return selected;
            if (groupBar.getChildCount() > 0) {
                View first = groupBar.getChildAt(0);
                if (first.isShown() && first.isFocusable()) return first;
            }
        }
        if (sourceSelector != null && sourceSelector.isShown()
                && sourceSelector.isFocusable()) {
            return sourceSelector;
        }
        return sourceSelector;
    }

    private void bindGrid() {
        layoutManager = new GridLayoutManager(
                activity, TvDisplayAdapter.channelGridSpanCount(activity));
        channelList.setLayoutManager(layoutManager);
        channelList.setItemAnimator(null);
        channelList.setClipChildren(false);
        channelList.setClipToPadding(false);
        GridSpacingDecoration.addPx(channelList,
                TvDisplayAdapter.channelGapPx(activity));
        adapter = new LiveChannelAdapter(this::openChannel);
        channelList.setAdapter(adapter);
    }

    private void loadConfigThenOpen() {
        showLoading("正在加载 TVBox 配置…");
        TvBoxRepository.get().load(activity, new TvBoxRepository.LoadCallback() {
            @Override
            public void onHome(TvBoxRepository.Home home) {
                activity.runOnUiThread(() -> {
                    TvBoxConfig loaded = TvBoxRepository.get().getConfig();
                    if (loaded == null) showError("配置已下载，但没有读取到直播源。");
                    else openConfig(loaded);
                });
            }

            @Override
            public void onError(Throwable error) {
                activity.runOnUiThread(() ->
                        showError("配置加载失败：" + rootMessage(error)));
            }
        });
    }

    private void openConfig(TvBoxConfig loaded) {
        config = loaded;
        liveSignature = signature(loaded);
        liveSources.clear();
        if (loaded.lives != null) {
            for (TvBoxConfig.Live live : loaded.lives) {
                if (live != null && (!safe(live.url).isBlank()
                        || !safe(live.api).isBlank())) {
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
                activity, "直播源", labels, selectedSourceIndex, which -> {
                    if (which == selectedSourceIndex) return;
                    selectedSourceIndex = which;
                    selectedGroup = "";
                    renderSourceSelector();
                    loadSelectedSource();
                });
        dialog.setOnDismissListener(ignored -> sourceSelector.requestFocus());
    }

    private TextView createChip(String label, boolean selected, String tag) {
        TextView chip = new TextView(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, chipHeightPx());
        params.setMarginEnd(dp(8));
        chip.setLayoutParams(params);
        chip.setText(label);
        chip.setTextColor(activity.getColorStateList(R.color.filter_chip_text));
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

    private void loadSelectedSource() {
        failedSourceIndexes.clear();
        fallbackFromSource = "";
        loadSourceAt(selectedSourceIndex, false);
    }

    private void loadSourceAt(int sourceIndex, boolean automaticFallback) {
        if (config == null || sourceIndex < 0 || sourceIndex >= liveSources.size()) {
            showError("没有可用的直播源。");
            return;
        }
        selectedSourceIndex = sourceIndex;
        TvBoxConfig.Live live = liveSources.get(sourceIndex);
        String address = safe(live.url).trim();
        if (address.isEmpty()) address = safe(live.api).trim();
        String resolvedAddress = address;
        if (address.isEmpty()) {
            failedSourceIndexes.add(sourceIndex);
            if (tryNextSource(sourceIndex)) return;
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
        String sourceName = safe(live.name).isBlank()
                ? "直播源 " + (sourceIndex + 1) : live.name.trim();
        showLoading("正在连接直播");
        renderSourceSelector();

        LiveRepository.get().load(activity, config, live, new LiveRepository.Callback() {
            @Override
            public void onLoaded(LiveRepository.LiveSource source) {
                activity.runOnUiThread(() -> {
                    if (generation != loadGeneration || activity.isFinishing()
                            || activity.isDestroyed()) return;
                    loading = false;
                    currentSource = source;
                    failedSourceIndexes.clear();
                    appSettings.setLastLiveSourceUrl(resolvedAddress);
                    renderGroupChips(false);
                    applyFilter(focusContentWhenReady);
                    focusContentWhenReady = false;
                    fallbackFromSource = "";
                });
            }

            @Override
            public void onError(Throwable error) {
                activity.runOnUiThread(() -> {
                    if (generation != loadGeneration) return;
                    loading = false;
                    focusContentWhenReady = false;
                    failedSourceIndexes.add(sourceIndex);
                    if (tryNextSource(sourceIndex)) return;
                    showError("直播源加载失败：" + rootMessage(error));
                    renderSourceSelector();
                });
            }
        });
    }

    private boolean tryNextSource(int failedIndex) {
        if (liveSources.size() <= 1) return false;
        for (int offset = 1; offset < liveSources.size(); offset++) {
            int candidate = (failedIndex + offset) % liveSources.size();
            if (failedSourceIndexes.contains(candidate)) continue;
            TvBoxConfig.Live live = liveSources.get(candidate);
            if (safe(live.url).isBlank() && safe(live.api).isBlank()) {
                failedSourceIndexes.add(candidate);
                continue;
            }
            if (fallbackFromSource.isBlank()) {
                fallbackFromSource = safe(liveSources.get(failedIndex).name);
            }
            selectedGroup = "";
            loadSourceAt(candidate, true);
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
            Toast.makeText(activity, "这个频道暂时没有播放线路", Toast.LENGTH_SHORT).show();
            return;
        }
        LivePlayerActivity.start(activity, currentSource.sourceUrl, channel.id,
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
        layoutMetrics = TvLayout.metrics(activity);
        int margin = dp(layoutMetrics.contentMarginDp);
        View content = root.findViewById(R.id.live_content);
        if (content != null) {
            content.setPadding(margin, content.getPaddingTop(),
                    margin, content.getPaddingBottom());
        }
        View header = root.findViewById(R.id.live_header);
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
        if (layoutMetrics == null) layoutMetrics = TvLayout.metrics(activity);
        return dp(Math.max(36f, layoutMetrics.liveHeaderHeightDp - 20f));
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
