package com.ch4nhm.tvapp;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class DetailActivity extends BaseActivity {

    private static final String TAG = "TVBoxDetail";
    private static final String EXTRA_ID = "id";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_META = "meta";
    private static final String EXTRA_YEAR = "year";
    private static final String EXTRA_PIC = "pic";
    private static final String EXTRA_REMARK = "remark";
    private static final String EXTRA_SITE = "site";

    private ContentItem currentItem;
    private RecyclerView episodeList;
    private RecyclerView playGroupList;
    private View playGroupRow;
    private PlayGroupAdapter playGroupAdapter;
    private TvBoxRepository.Detail loadedDetail;
    private final List<TvBoxRepository.PlayGroup> visibleGroups = new ArrayList<>();
    private final java.util.Set<String> loggedInPanBrands = new java.util.HashSet<>();
    private PanRepository panRepository;
    private int panStatusRequestId;
    private int selectedGroupIndex;
    private TextView playButton;
    private TextView keep;
    private int detailRequestId;
    private boolean playRequestedWhileLoading;
    private boolean playableGroupsRendered;
    private boolean panStatusScheduled;
    private TvLayoutMetrics layoutMetrics;
    private final Handler detailHandler = new Handler(Looper.getMainLooper());

    public static void start(Context context, ContentItem item) {
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(EXTRA_ID, item.id);
        intent.putExtra(EXTRA_TITLE, item.title);
        intent.putExtra(EXTRA_META, item.meta);
        intent.putExtra(EXTRA_YEAR, item.year);
        intent.putExtra(EXTRA_PIC, item.pic);
        intent.putExtra(EXTRA_REMARK, item.remark);
        if (item.site != null) intent.putExtra(EXTRA_SITE, item.site.key);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_detail);
        enterImmersive();

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String id = getIntent().getStringExtra(EXTRA_ID);
        String meta = getIntent().getStringExtra(EXTRA_META);
        String year = getIntent().getStringExtra(EXTRA_YEAR);
        String pic = getIntent().getStringExtra(EXTRA_PIC);
        String remark = getIntent().getStringExtra(EXTRA_REMARK);
        if (title == null || meta == null) {
            finish();
            return;
        }

        com.ch4nhm.tvapp.data.SiteConfig site = findSite(getIntent().getStringExtra(EXTRA_SITE));
        if (site == null) site = TvBoxRepository.get().getHomeSite();
        ContentItem item = new ContentItem(id == null ? "" : id, title, meta,
                year == null ? "" : year, pic == null ? "" : pic,
                remark == null ? "" : remark, site);
        bindStatic(item);

        episodeList = findViewById(R.id.episode_list);
        episodeList.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(
                this, TvDisplayAdapter.episodeGridSpanCount(this)));
        episodeList.setHasFixedSize(true);
        episodeList.setItemAnimator(null);
        episodeList.setFocusable(false);
        episodeList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        episodeList.setClipToPadding(false);
        GridSpacingDecoration.addPx(episodeList, TvDisplayAdapter.episodeGapPx(this));
        episodeList.setAdapter(new EpisodeAdapter(new ArrayList<>(), ignored -> {}));

        playGroupRow = findViewById(R.id.play_group_row);
        playGroupList = findViewById(R.id.play_group_list);
        playGroupList.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        playGroupList.setHasFixedSize(true);
        playGroupList.setItemAnimator(null);
        playGroupList.setFocusable(false);
        playGroupList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        playGroupAdapter = new PlayGroupAdapter(this::selectGroup, this::focusFirstEpisode);
        playGroupList.setAdapter(playGroupAdapter);
        applyResponsiveChrome();
        setupDetailNavigation();
        panRepository = new PanRepository();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        // Cached repository responses are synchronous, so all detail views and
        // adapters must exist before the first detail callback can arrive.
        markLocalPanCredentials();
        loadDetail(item);
    }

    private void setupDetailNavigation() {
        View.OnKeyListener downToEpisodes = (view, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN
                    || keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                return false;
            }
            return focusPlayGroupsOrEpisodes();
        };
        playButton.setOnKeyListener(downToEpisodes);
        keep.setOnKeyListener(downToEpisodes);
    }

    private void applyResponsiveChrome() {
        layoutMetrics = TvLayout.metrics(this);
        int margin = dp(layoutMetrics.contentMarginDp);

        View posterFrame = findViewById(R.id.detail_poster_frame);
        View contentRow = (View) posterFrame.getParent();
        View hero = (View) contentRow.getParent();
        setHeight(hero, layoutMetrics.detailHeroHeightDp);
        ViewGroup.LayoutParams posterParams = posterFrame.getLayoutParams();
        if (posterParams != null) {
            posterParams.width = dp(layoutMetrics.detailPosterWidthDp);
            posterParams.height = dp(layoutMetrics.detailPosterHeightDp);
            if (posterParams instanceof ViewGroup.MarginLayoutParams margins) {
                margins.setMarginEnd(dp(20));
            }
            posterFrame.setLayoutParams(posterParams);
        }

        TextView title = findViewById(R.id.detail_title);
        title.setTextSize(layoutMetrics.pageTitleTextSizeSp);
        TextView description = findViewById(R.id.detail_desc);
        description.setTextSize(Math.max(14f, layoutMetrics.posterTitleTextSizeSp - 1f));

        View sectionBar = (View) playGroupRow.getParent();
        setHeight(sectionBar, layoutMetrics.detailEpisodeHeaderHeightDp);
        setHeight(playGroupRow, Math.max(44f,
                layoutMetrics.detailEpisodeHeaderHeightDp - 10f));
        if (sectionBar instanceof ViewGroup sectionGroup) {
            sectionGroup.setClipChildren(false);
            sectionGroup.setClipToPadding(false);
        }
        if (playGroupRow instanceof ViewGroup playGroup) {
            playGroup.setClipChildren(false);
            playGroup.setClipToPadding(false);
        }
        playGroupList.setClipChildren(false);
        playGroupList.setClipToPadding(false);
        // Episode chips scale on focus. Reserve drawable space above and below
        // so the first/last row can expand without clipping its lower edge.
        episodeList.setPadding(margin, dp(16), margin, dp(28));
        TvLayout.applyButtonHeight(playButton, true);
        TvLayout.applyButtonHeight(keep, true);
    }

    private static void setHeight(View view, float heightDp) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.height = TvLayout.dp(view.getContext(), heightDp);
        view.setLayoutParams(params);
    }

    private boolean focusPlayGroupsOrEpisodes() {
        if (playGroupRow != null && playGroupRow.getVisibility() == View.VISIBLE
                && playGroupAdapter != null && playGroupAdapter.getItemCount() > 0) {
            focusPlayGroup(selectedGroupIndex, 4);
            return true;
        }
        return focusFirstEpisode();
    }

    private void focusPlayGroup(int position, int attempts) {
        playGroupList.post(() -> {
            RecyclerView.ViewHolder target =
                    playGroupList.findViewHolderForAdapterPosition(position);
            if (target != null) {
                target.itemView.requestFocus();
            } else if (attempts > 0) {
                playGroupList.postDelayed(
                        () -> focusPlayGroup(position, attempts - 1), 40L);
            }
        });
    }

    private boolean focusFirstEpisode() {
        if (episodeList == null || episodeList.getAdapter() == null
                || episodeList.getAdapter().getItemCount() == 0) {
            return false;
        }
        focusEpisode(0, 4);
        return true;
    }

    private void focusEpisode(int position, int attempts) {
        episodeList.scrollToPosition(position);
        episodeList.post(() -> {
            RecyclerView.ViewHolder target =
                    episodeList.findViewHolderForAdapterPosition(position);
            if (target != null) {
                target.itemView.requestFocus();
            } else if (attempts > 0) {
                episodeList.postDelayed(
                        () -> focusEpisode(position, attempts - 1), 40L);
            }
        });
    }

    private void bindStatic(ContentItem item) {
        currentItem = item;
        ImageLoader.backdrop(findViewById(R.id.detail_backdrop), item.title, item.year,
                item.pic, R.drawable.bg_hero_fallback);
        ImageLoader.largePoster(findViewById(R.id.detail_poster), item.title, item.year,
                item.pic, R.drawable.poster_placeholder);
        ((TextView) findViewById(R.id.detail_title)).setText(item.title);
        ((TextView) findViewById(R.id.detail_kicker)).setText(
                item.site == null ? "TVBox" : item.site.displayName());
        String summary = String.join(" · ",
                nonEmpty(item.meta, item.year, item.remark));
        ((TextView) findViewById(R.id.detail_meta)).setText(summary);
        TextView description = findViewById(R.id.detail_desc);
        description.setText(item.remark == null || item.remark.isBlank()
                ? "正在加载完整介绍和选集…" : item.remark);
        description.setVisibility(View.VISIBLE);
        TextView source = findViewById(R.id.detail_source);
        source.setText(item.site == null ? "TVBOX" : item.site.displayName());
        playButton = findViewById(R.id.detail_play);
        FocusAnimator.scale(playButton, 1.04f);
        keep = findViewById(R.id.detail_keep);
        FocusAnimator.scale(keep, 1.04f);
        keep.setOnClickListener(view -> toggleFavorite());
        updateFavorite();
        showDetailLoading();
        requestInitialFocus(playButton);
    }

    private void loadDetail(ContentItem item) {
        final int requestId = ++detailRequestId;
        long startedAt = SystemClock.elapsedRealtime();
        showDetailLoading();
        TvBoxRepository.get().detail(item, new TvBoxRepository.DetailCallback() {
            @Override
            public void onDetail(TvBoxRepository.Detail detail) {
                runOnUiThread(() -> {
                    if (requestId != detailRequestId || isFinishing() || isDestroyed()) return;
                    Log.i(TAG, "Detail ready in "
                            + (SystemClock.elapsedRealtime() - startedAt) + " ms");
                    bindDetail(item, detail);
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    if (requestId != detailRequestId || isFinishing() || isDestroyed()) return;
                    Toast.makeText(DetailActivity.this,
                            "暂时无法加载详情",
                            Toast.LENGTH_LONG).show();
                    bindFallback(item);
                });
            }
        });
    }

    private void showDetailLoading() {
        loadedDetail = null;
        playableGroupsRendered = false;
        playButton.setText(playRequestedWhileLoading ? "重试并播放" : "正在获取详情…");
        playButton.setEnabled(true);
        playButton.setAlpha(0.78f);
        playButton.setOnClickListener(view -> {
            playRequestedWhileLoading = true;
            playButton.setText("正在获取播放线路…");
            Toast.makeText(this, "正在获取详情，完成后会自动播放", Toast.LENGTH_SHORT).show();
        });
        requestInitialFocus(playButton);
    }

    private void loadPanStatus() {
        final int requestId = ++panStatusRequestId;
        long startedAt = SystemClock.elapsedRealtime();
        try {
            panRepository.loadLoginState(
                    brands -> runOnUiThread(() -> {
                        Log.i(TAG, "Pan status ready in "
                                + (SystemClock.elapsedRealtime() - startedAt) + " ms");
                        applyPanStatus(requestId, brands);
                    }),
                    ignored -> runOnUiThread(() ->
                            applyPanStatus(requestId, java.util.Collections.emptyList())));
        } catch (Throwable ignored) {
            applyPanStatus(requestId, java.util.Collections.emptyList());
        }
    }

    private void schedulePanStatus() {
        if (panStatusScheduled || isFinishing() || isDestroyed()) return;
        panStatusScheduled = true;
        detailHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            loadPanStatus();
        }, 180L);
    }

    private void applyPanStatus(int requestId, List<PanRepository.Brand> brands) {
        if (requestId != panStatusRequestId || isFinishing() || isDestroyed()) return;
        loggedInPanBrands.clear();
        if (brands != null) {
            for (PanRepository.Brand brand : brands) {
                if (brand != null && brand.loggedIn) loggedInPanBrands.add(brand.id);
            }
        }
        addLocalPanCredentials();
        if (loadedDetail != null) refreshGroupAvailability();
    }

    private void markLocalPanCredentials() {
        loggedInPanBrands.clear();
        addLocalPanCredentials();
    }

    private void addLocalPanCredentials() {
        for (String provider : new String[]{"quark", "baidu", "aliyun"}) {
            if (PanCredentialStore.hasCredential(provider)) {
                loggedInPanBrands.add(provider);
            }
        }
    }

    private void bindDetail(ContentItem item, TvBoxRepository.Detail detail) {
        currentItem = new ContentItem(item.id, detail.title, detail.typeName, detail.year, detail.pic,
                detail.remarks, item.site);
        ImageLoader.backdrop(findViewById(R.id.detail_backdrop), detail.title, detail.year,
                detail.pic, R.drawable.bg_hero_fallback);
        ImageLoader.largePoster(findViewById(R.id.detail_poster), detail.title, detail.year,
                detail.pic, R.drawable.poster_placeholder);
        ((TextView) findViewById(R.id.detail_title)).setText(detail.title);
        ((TextView) findViewById(R.id.detail_kicker)).setText(
                item.site == null ? "TVBox" : item.site.displayName());
        String meta = String.join(" · ", nonEmpty(detail.year, detail.area, detail.typeName));
        ((TextView) findViewById(R.id.detail_meta)).setText(meta);
        String sourceName = item.site == null ? "TVBOX" : item.site.displayName();
        ((TextView) findViewById(R.id.detail_source)).setText(sourceName);
        updateFavorite();

        loadedDetail = detail;
        renderPlayableGroups(detail);
        // Pan status is auxiliary UI state. Loading it after the detail and
        // episode adapters are bound keeps it from competing with the first
        // paint and with the selected site's detail request.
        schedulePanStatus();
    }

    private void bindFallback(ContentItem item) {
        currentItem = item;
        loadedDetail = null;
        playableGroupsRendered = false;
        visibleGroups.clear();
        playGroupAdapter.update(visibleGroups, loggedInPanBrands);
        playGroupRow.setVisibility(View.GONE);
        episodeList.setAdapter(new EpisodeAdapter(new ArrayList<>(), ignored -> {}));
        TextView description = findViewById(R.id.detail_desc);
        description.setText("暂时无法加载详情");
        description.setVisibility(View.VISIBLE);
        playButton.setText(playRequestedWhileLoading ? "重试并播放" : "重新加载详情");
        playButton.setAlpha(1f);
        playButton.setOnClickListener(view -> {
            if (playRequestedWhileLoading) {
                Toast.makeText(this, "正在重新加载", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "正在重新加载", Toast.LENGTH_SHORT).show();
            }
            loadDetail(item);
        });
        requestInitialFocus(playButton);
    }

    private void renderPlayableGroups(TvBoxRepository.Detail detail) {
        String selectedKey = selectedGroupKey();
        boolean firstPlayableRender = !playableGroupsRendered;
        visibleGroups.clear();
        visibleGroups.addAll(detail.episodes);

        String sourceName = currentItem.site == null ? "TVBOX" : currentItem.site.displayName();
        updateSourceSummary(sourceName, visibleGroups.size());
        bindDetailDescription(detail, sourceName, !visibleGroups.isEmpty());
        playGroupAdapter.update(visibleGroups, loggedInPanBrands);
        playGroupRow.setVisibility(visibleGroups.size() > 1 ? View.VISIBLE : View.GONE);

        if (visibleGroups.isEmpty()) {
            showNoPlayableGroups();
            return;
        }
        playableGroupsRendered = true;
        selectGroup(indexOfGroup(selectedKey));
        if (firstPlayableRender) requestInitialFocus(playButton);
        if (playRequestedWhileLoading) {
            playRequestedWhileLoading = false;
            playFirstEpisode();
        }
    }

    private String selectedGroupKey() {
        if (visibleGroups.isEmpty() || selectedGroupIndex < 0
                || selectedGroupIndex >= visibleGroups.size()) {
            return "";
        }
        return playGroupKey(visibleGroups.get(selectedGroupIndex));
    }

    private int indexOfGroup(String key) {
        if (key == null || key.isEmpty()) return 0;
        for (int index = 0; index < visibleGroups.size(); index++) {
            if (key.equals(playGroupKey(visibleGroups.get(index)))) return index;
        }
        return 0;
    }

    private static String playGroupKey(TvBoxRepository.PlayGroup group) {
        if (group == null) return "";
        String firstId = group.episodes == null || group.episodes.isEmpty()
                ? "" : group.episodes.get(0).id;
        return String.valueOf(group.flag) + "\u0000" + String.valueOf(firstId);
    }

    private void showNoPlayableGroups() {
        playRequestedWhileLoading = false;
        playButton.setText("暂无可播线路");
        playButton.setEnabled(true);
        playButton.setAlpha(0.72f);
        playButton.setOnClickListener(view -> Toast.makeText(this,
                "暂无可播线路",
                Toast.LENGTH_LONG).show());
        episodeList.setAdapter(new EpisodeAdapter(new ArrayList<>(), ignored -> {}));
        requestInitialFocus(playButton);
    }

    private void updateSourceSummary(String sourceName, int groupCount) {
        ((TextView) findViewById(R.id.detail_source)).setText(
                groupCount <= 0 ? sourceName : sourceName + " · " + groupCount + " 条线路");
    }

    private void bindDetailDescription(TvBoxRepository.Detail detail, String sourceName,
                                       boolean hasPlayableGroups) {
        TextView descriptionView = findViewById(R.id.detail_desc);
        if (!hasPlayableGroups) {
            descriptionView.setText("暂无播放线路");
            descriptionView.setVisibility(View.VISIBLE);
            return;
        }
        String description = sourceNeutralSynopsis(detail.content, sourceName);
        descriptionView.setText(description);
        descriptionView.setVisibility(description.isBlank() ? View.GONE : View.VISIBLE);
    }

    private void playFirstEpisode() {
        if (loadedDetail == null || visibleGroups.isEmpty()) return;
        TvBoxRepository.PlayGroup group = visibleGroups.get(0);
        if (group.episodes.isEmpty()) return;
        PlayerActivity.start(this, currentItem, group.episodes.get(0), loadedDetail.title);
    }

    private void selectGroup(int index) {
        if (loadedDetail == null || visibleGroups.isEmpty()
                || index < 0 || index >= visibleGroups.size()) {
            return;
        }
        selectedGroupIndex = index;
        playGroupAdapter.select(index);
        TvBoxRepository.PlayGroup group = visibleGroups.get(index);
        TvBoxRepository.Episode first =
                group.episodes.isEmpty() ? null : group.episodes.get(0);

        String panBrand = PanRepository.brandForSource(group);
        boolean needsLogin = !panBrand.isBlank()
                && !loggedInPanBrands.contains(panBrand);
        playButton.setText(first == null ? "暂无片源"
                : needsLogin ? "登录并播放" : "播放");
        playButton.setEnabled(true);
        playButton.setAlpha(1f);
        playButton.setOnClickListener(view -> {
            if (first != null) {
                PlayerActivity.start(this, currentItem, first, loadedDetail.title);
            } else {
                Toast.makeText(this, "当前线路没有播放地址", Toast.LENGTH_SHORT).show();
            }
        });

        episodeList.setAdapter(new EpisodeAdapter(group.episodes,
                episode -> PlayerActivity.start(this, currentItem, episode, loadedDetail.title),
                episode -> TvBoxRepository.get().prefetchPlayer(currentItem, episode)));
        episodeList.scrollToPosition(0);
        if (first != null) TvBoxRepository.get().prefetchPlayer(currentItem, first);
    }

    private void refreshGroupAvailability() {
        if (loadedDetail == null || visibleGroups.isEmpty()) return;
        playGroupAdapter.update(visibleGroups, loggedInPanBrands);
        if (selectedGroupIndex < 0 || selectedGroupIndex >= visibleGroups.size()) return;
        TvBoxRepository.PlayGroup group = visibleGroups.get(selectedGroupIndex);
        String panBrand = PanRepository.brandForSource(group);
        boolean needsLogin = !panBrand.isBlank()
                && !loggedInPanBrands.contains(panBrand);
        if (!group.episodes.isEmpty()) {
            playButton.setText(needsLogin ? "登录并播放" : "播放");
        }
    }

    private void toggleFavorite() {
        if (currentItem == null || currentItem.id == null || currentItem.id.isBlank()) {
            Toast.makeText(this, "当前内容暂时无法收藏", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean favorite = UserDataStore.get(this).toggleFavorite(currentItem);
        updateFavorite();
        Toast.makeText(this, favorite ? "已加入收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    private void updateFavorite() {
        if (keep == null) return;
        boolean favorite = currentItem != null
                && UserDataStore.get(this).isFavorite(currentItem);
        keep.setText(favorite ? "已收藏" : "收藏");
        keep.setTextColor(favorite
                ? ThemeManager.accent(this) : getColor(R.color.white));
    }

    @Override
    protected void onDestroy() {
        detailRequestId++;
        panStatusRequestId++;
        detailHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static java.util.List<String> nonEmpty(String... values) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) seen.add(value);
        }
        java.util.List<String> result = new ArrayList<>(seen);
        return result;
    }

    static String concise(String content) {
        if (content == null) return "";
        String text = content.trim();
        if (text.length() < 180) return text;
        String doubled = text.substring(0, text.length() / 2).trim();
        if (text.startsWith(doubled, text.length() / 2)
                || text.substring(text.length() / 2).trim().equals(doubled)) {
            text = doubled;
        }
        int sentenceEnd = -1;
        for (int index = 0; index < Math.min(text.length(), 180); index++) {
            char value = text.charAt(index);
            if (value == '。' || value == '！' || value == '？') sentenceEnd = index;
        }
        if (sentenceEnd > 48) text = text.substring(0, sentenceEnd + 1);
        return text.length() > 180 ? text.substring(0, 179) + "…" : text;
    }

    private static String sourceNeutralSynopsis(String content, String sourceName) {
        String text = concise(content);
        if (text.isBlank()) return "";
        if (sourceName != null && !sourceName.isBlank()) {
            text = text.replace("【" + sourceName + "】", "")
                    .replace("[" + sourceName + "]", "")
                    .replace(sourceName, "");
        }
        text = text.replaceAll("【[^】]{1,24}】", "")
                .replaceAll("\\[[^\\]]{1,24}\\]", "");
        text = text.replaceFirst("^(?:免费分享|免费资源|资源分享)\\s*[:：]?\\s*", "")
                .replaceAll("[\\s·|｜]+$", "")
                .trim();
        String compact = text.replaceAll("[\\s，,。.!！?？·:：]", "");
        if (compact.isEmpty()
                || (compact.contains("暂无简介") && compact.length() < 30)
                || compact.equals("暂无简介")
                || compact.equals("敬请期待")
                || compact.equals("暂无简介敬请期待")) {
            return "";
        }
        return text;
    }

    private com.ch4nhm.tvapp.data.SiteConfig findSite(String key) {
        if (key == null || TvBoxRepository.get().getConfig() == null) return null;
        for (com.ch4nhm.tvapp.data.SiteConfig site : TvBoxRepository.get().getConfig().sites) {
            if (site.key.equals(key)) return site;
        }
        return null;
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFavorite();
    }
}
