package com.ch4nhm.tvapp;

import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ch4nhm.tvapp.data.SiteConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends BaseActivity {

    private static final long HOME_LOAD_TIMEOUT_MS = 35_000L;
    private static final int MAX_CATEGORY_PAGES = 30;
    private static final String[][] CATEGORY_ALIASES = {
            {},
            {"电影", "影片"},
            {"电视剧", "剧集", "连续剧", "电视"},
            {"综艺", "真人秀", "节目"},
            {"动漫", "动画", "番剧", "国漫"}
    };
    private static final String[][] MOVIE_TV_FILTER_ALIASES = {
            {"地区", "地址", "国家", "区域"},
            {"年代", "年份", "时间", "年"},
            {"类型", "分类", "类别", "片型", "题材", "剧情", "子分类"}
    };
    private static final String[][] VARIETY_ANIME_FILTER_ALIASES = {
            {"类别", "分类", "类型", "子分类", "片型", "剧情", "题材"},
            {"年代", "年份", "时间", "年"}
    };
    private static final PathInterpolator FILTER_TRANSITION_INTERPOLATOR =
            new PathInterpolator(0.22f, 0.8f, 0.24f, 1f);
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, CategoryState> categoryStates = new HashMap<>();
    private final List<UserDataStore.HistoryEntry> libraryHistory = new ArrayList<>();
    private TextView sourceSelector;
    private View[] navItems;
    private View navBackdrop;
    private int scrollY;
    private int activeTopTab;
    private HomeAdapter homeAdapter;
    private TvBoxRepository.Home home;
    private RecyclerView homeList;
    private View categoryView;
    private RecyclerView categoryList;
    private GridLayoutManager categoryLayout;
    private PosterAdapter categoryAdapter;
    private View categoryStateView;
    private ProgressBar categoryProgress;
    private TextView categoryMessage;
    private TextView categoryRetry;
    private View categoryFilterOverlay;
    private TextView categoryFilterReset;
    private LinearLayout categoryFilterPanel;
    private TextView firstCategoryFilterOption;
    private CategoryState activeCategory;
    private boolean categoryMode;
    private boolean libraryMode;
    private boolean liveMode;
    private boolean libraryHistoryMode;
    private boolean keepNavFocusForCategoryLoad;
    private Runnable pendingNavActivation;
    private View libraryView;
    private RecyclerView libraryList;
    private PosterAdapter libraryAdapter;
    private View libraryEmpty;
    private TextView libraryClear;
    private TextView libraryEmptyTitle;
    private TextView libraryEmptyHint;
    private LibraryTabSwitcher libraryTabs;
    private View liveView;
    private LivePanelController liveController;
    private int loadRequestId;
    private boolean homeRequestSettled;
    private boolean libraryViewReady;
    private boolean liveViewReady;
    private boolean secondaryViewsScheduled;
    private TvLayoutMetrics layoutMetrics;

    private final ActivityResultLauncher<android.content.Intent> settingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            if (categoryMode && activeCategory != null) {
                                reloadCategory();
                            } else if (libraryMode) {
                                refreshLibrary();
                            } else if (liveMode) {
                                liveController.onResume();
                            } else {
                                refreshHome();
                            }
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long startedAt = SystemClock.elapsedRealtime();
        super.onCreate(savedInstanceState);
        Log.d("TVBoxPerf", "main super " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        Log.d("TVBoxPerf", "main content " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
        enterImmersive();

        homeList = findViewById(R.id.home_list);
        homeAdapter = new HomeAdapter(this, new HomeAdapter.EmptyAction() {
            @Override
            public void retry() {
                loadConfig();
            }

            @Override
            public void settings() {
                settingsLauncher.launch(SettingsActivity.intent(MainActivity.this));
            }
        });
        GridLayoutManager homeLayout = new GridLayoutManager(
                this, TvDisplayAdapter.posterGridSpanCount(this));
        homeLayout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return homeAdapter.isFullSpan(homeAdapter.getItemViewType(position))
                        ? homeLayout.getSpanCount() : 1;
            }
        });
        homeList.setLayoutManager(homeLayout);
        GridSpacingDecoration.addPx(homeList, TvDisplayAdapter.posterGapPx(this));
        homeList.setAdapter(homeAdapter);
        homeList.setItemAnimator(null);
        homeList.setClipChildren(false);
        homeList.setClipToPadding(false);
        setupCategoryView();
        libraryView = findViewById(R.id.main_library_view);
        liveView = findViewById(R.id.main_live_view);
        navBackdrop = findViewById(R.id.nav_backdrop);
        homeList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                scrollY = Math.max(0, scrollY + dy);
                float threshold = Math.max(1f, dp(72));
                navBackdrop.setAlpha(Math.min(1f, scrollY / threshold));
            }
        });

        setupNavigation();
        setupActions();
        applyResponsiveChrome();
        Log.d("TVBoxPerf", "main setup " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (categoryMode) {
                    if (activeCategory != null && activeCategory.filtersExpanded) {
                        setCategoryFiltersExpanded(activeCategory, false, true, false);
                        return;
                    }
                    showHome();
                    return;
                }
                if (libraryMode) {
                    showHome();
                    return;
                }
                if (liveMode) {
                    showHome();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
        homeList.post(this::loadConfig);

        findViewById(R.id.nav_home).requestFocus();
        Log.d("TVBoxPerf", "main focus " + (SystemClock.elapsedRealtime() - startedAt) + "ms");
    }

    private void setupCategoryView() {
        categoryView = findViewById(R.id.main_category_view);
        categoryList = findViewById(R.id.main_category_list);
        categoryStateView = findViewById(R.id.main_category_state);
        categoryProgress = findViewById(R.id.main_category_progress);
        categoryMessage = findViewById(R.id.main_category_message);
        categoryRetry = findViewById(R.id.main_category_retry);
        categoryFilterOverlay = findViewById(R.id.main_category_filter_overlay);
        categoryFilterReset = findViewById(R.id.main_category_filter_reset);
        categoryFilterPanel = findViewById(R.id.main_category_filter_panel);

        int spanCount = TvDisplayAdapter.posterGridSpanCount(this);
        categoryLayout = new GridLayoutManager(this, spanCount);
        categoryList.setLayoutManager(categoryLayout);
        GridSpacingDecoration.addPx(categoryList, TvDisplayAdapter.posterGapPx(this));
        categoryList.setItemAnimator(null);
        categoryList.setHasFixedSize(true);
        categoryList.setClipChildren(true);
        // Keep drawing inside the list, but allow focused cards to expand into
        // the top/bottom padding instead of being cut by the padding bounds.
        categoryList.setClipToPadding(false);
        categoryList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                CategoryState state = activeCategory;
                if (!categoryMode || dy <= 0 || state == null
                        || state.loading || !state.hasMore) return;
                int last = categoryLayout.findLastVisibleItemPosition();
                if (last >= categoryAdapter.getItemCount() - 8) {
                    loadCategoryPage(state, false);
                }
            }
        });
        categoryAdapter = new PosterAdapter(new ArrayList<>(),
                item -> DetailActivity.start(this, item));
        categoryList.setAdapter(categoryAdapter);
        FocusAnimator.scale(categoryRetry, 1.04f);
        FocusAnimator.scale(categoryFilterReset, 1.035f);
        categoryRetry.setOnClickListener(view -> reloadCategory());
        categoryFilterReset.setOnClickListener(view -> {
            CategoryState state = activeCategory;
            if (state == null) return;
            state.selectedFilters.clear();
            state.filterRequested = false;
            state.pendingFilterFocus = null;
            renderCategoryFilters(state, false);
            categoryFilterPanel.post(this::focusFirstFilterOption);
            reloadCategory(state);
        });
        categoryFilterReset.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return focusActiveNavItem();
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            CategoryState state = activeCategory;
            if (state != null && state.filtersExpanded) return focusFirstFilterOption();
            return focusCategoryContent(true);
        });
    }

    private void setupLibraryView() {
        if (libraryViewReady) return;
        libraryViewReady = true;
        ViewStub stub = findViewById(R.id.library_view_stub);
        if (stub != null) stub.inflate();
        libraryList = findViewById(R.id.library_list);
        libraryEmpty = findViewById(R.id.library_empty);
        libraryClear = findViewById(R.id.library_clear);
        libraryEmptyTitle = findViewById(R.id.library_empty_title);
        libraryEmptyHint = findViewById(R.id.library_empty_hint);
        View title = findViewById(R.id.library_title_group);
        TextView pan = findViewById(R.id.library_pan);
        TextView settings = findViewById(R.id.library_settings);

        title.setVisibility(View.GONE);
        libraryList.setLayoutManager(new GridLayoutManager(
                this, TvDisplayAdapter.posterGridSpanCount(this)));
        GridSpacingDecoration.addPx(libraryList, TvDisplayAdapter.posterGapPx(this));
        libraryList.setItemAnimator(null);
        libraryList.setHasFixedSize(true);
        libraryList.setClipChildren(false);
        libraryList.setClipToPadding(false);
        libraryAdapter = new PosterAdapter(new ArrayList<>(), this::openLibraryItem);
        libraryList.setAdapter(libraryAdapter);

        FocusAnimator.scale(libraryClear, 1.04f);
        FocusAnimator.scale(pan, 1.04f);
        FocusAnimator.scale(settings, 1.04f);
        libraryTabs = new LibraryTabSwitcher(libraryView, libraryHistoryMode,
                this::selectLibraryMode);
        libraryClear.setOnClickListener(view -> confirmClearLibrary());
        pan.setOnClickListener(view -> PanCenterActivity.start(this));
        settings.setOnClickListener(view ->
                settingsLauncher.launch(SettingsActivity.intent(MainActivity.this)));
        applyResponsiveChrome();
    }

    private void setupLiveView() {
        if (liveViewReady) return;
        liveViewReady = true;
        ViewStub stub = findViewById(R.id.live_view_stub);
        if (stub != null) stub.inflate();
        liveController = new LivePanelController(this, liveView);
        applyResponsiveChrome();
    }

    private void setupNavigation() {
        navItems = new View[]{
                findViewById(R.id.nav_home),
                findViewById(R.id.nav_movie),
                findViewById(R.id.nav_tv),
                findViewById(R.id.nav_variety),
                findViewById(R.id.nav_anime),
                findViewById(R.id.nav_live),
                findViewById(R.id.nav_mine)
        };
        for (int index = 0; index < navItems.length; index++) {
            final int selected = index;
            View item = navItems[index];
            item.setOnClickListener(view -> {
                cancelPendingNavActivation();
                if (selected >= 1 && selected <= 4 && categoryMode
                        && activeTopTab == selected) {
                    toggleCategoryFiltersFromNav();
                    return;
                }
                selectTab(selected, false);
            });
            item.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN
                        || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                    return false;
                }
                if (categoryMode) return focusCategoryTop(true);
                if (libraryMode) {
                    if (libraryTabs != null) {
                        libraryTabs.requestFocus();
                        return true;
                    }
                    return focusLibraryContent(true);
                }
                if (liveMode) {
                    liveController.requestContentFocus();
                    return true;
                }
                return focusHomeContent(true);
            });
            FocusAnimator.scale(item, 1.06f, () -> scheduleNavActivation(selected));
        }
        navItems[0].setSelected(true);
    }

    private void applyResponsiveChrome() {
        layoutMetrics = TvLayout.metrics(this);
        TvLayoutMetrics metrics = layoutMetrics;
        int margin = dp(metrics.contentMarginDp);
        int navHeight = dp(metrics.navHeightDp);

        homeList.setPadding(margin, navHeight, margin, dp(36));
        categoryView.setPadding(categoryView.getPaddingLeft(), navHeight,
                categoryView.getPaddingRight(), categoryView.getPaddingBottom());
        libraryView.setPadding(libraryView.getPaddingLeft(), navHeight,
                libraryView.getPaddingRight(), libraryView.getPaddingBottom());
        liveView.setPadding(liveView.getPaddingLeft(), navHeight,
                liveView.getPaddingRight(), liveView.getPaddingBottom());
        navBackdrop.getLayoutParams().height = navHeight;
        navBackdrop.requestLayout();

        if (categoryList != null) {
            categoryList.setPadding(margin, dp(20), margin, dp(36));
        }
        if (categoryFilterOverlay != null) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) categoryFilterOverlay.getLayoutParams();
            params.setMarginStart(margin);
            params.setMarginEnd(margin);
            categoryFilterOverlay.setLayoutParams(params);
        }
        resizeHeight(categoryFilterReset, dp(34));
        if (libraryList != null) {
            libraryList.setPadding(margin, dp(14), margin, dp(28));
            applyLibraryChrome(metrics, margin);
        }

        View navTabs = findViewById(R.id.nav_tabs);
        View brand = findViewById(R.id.nav_brand);
        if (brand != null) {
            brand.setVisibility(TvDisplayAdapter.viewport(this).usableWidthDp < 900f
                    ? View.GONE : View.VISIBLE);
        }
        if (navTabs != null && navTabs.getParent() instanceof View navRoot) {
            navRoot.setPadding(margin, dp(8), margin, 0);
            ViewGroup.LayoutParams rootParams = navRoot.getLayoutParams();
            if (rootParams != null) {
                rootParams.height = navHeight;
                navRoot.setLayoutParams(rootParams);
            }
        }
        if (navItems != null) {
            for (View item : navItems) {
                ViewGroup.LayoutParams params = item.getLayoutParams();
                if (params != null) {
                    params.height = dp(metrics.topNavItemHeightDp);
                    item.setLayoutParams(params);
                }
                if (item instanceof TextView text) {
                    text.setTextSize(metrics.topNavTextSizeSp);
                }
            }
        }
        TextView sourceAction = findViewById(R.id.action_source);
        resizeTopAction(sourceAction, metrics.topActionSizeDp, true);
        if (sourceAction != null) {
            float usableWidthDp = TvDisplayAdapter.viewport(this).usableWidthDp;
            sourceAction.setVisibility(usableWidthDp < 760f ? View.GONE : View.VISIBLE);
            sourceAction.setMaxWidth(dp(usableWidthDp < 900f
                    ? 112f : Math.max(160f, metrics.topActionSizeDp * 3.4f)));
        }
        resizeTopAction(findViewById(R.id.action_search), metrics.topActionSizeDp, false);
        resizeTopAction(findViewById(R.id.nav_mine), metrics.topActionSizeDp, false);
        resizeTopAction(findViewById(R.id.action_settings),
                metrics.topActionSizeDp, false);
    }

    private void applyLibraryChrome(TvLayoutMetrics metrics, int marginPx) {
        View header = findViewById(R.id.library_header);
        if (header != null) {
            header.setPadding(marginPx, header.getPaddingTop(),
                    marginPx, header.getPaddingBottom());
            ViewGroup.LayoutParams params = header.getLayoutParams();
            if (params != null) {
                params.height = dp(Math.min(metrics.navHeightDp,
                        metrics.pageHeaderHeightDp));
                header.setLayoutParams(params);
            }
        }

        View tabs = findViewById(R.id.library_tabs);
        if (tabs != null) {
            int tabHeight = dp(metrics.buttonHeightDp);
            ViewGroup.LayoutParams params = tabs.getLayoutParams();
            if (params != null) {
                params.height = tabHeight;
                tabs.setLayoutParams(params);
            }
            tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
            resizeHeight(findViewById(R.id.library_tab_indicator),
                    Math.max(dp(40), tabHeight - dp(8)));
        }
        int favoriteWidth = dp(Math.max(92f, metrics.posterCardWidthDp * 0.62f));
        int historyWidth = dp(Math.max(120f, metrics.posterCardWidthDp * 0.78f));
        resizeWidth(findViewById(R.id.library_favorites), favoriteWidth);
        resizeWidth(findViewById(R.id.library_history), historyWidth);
        TextView favorites = findViewById(R.id.library_favorites);
        TextView history = findViewById(R.id.library_history);
        if (favorites != null) favorites.setTextSize(metrics.posterTitleTextSizeSp);
        if (history != null) history.setTextSize(metrics.posterTitleTextSizeSp);

        TvLayout.applyButtonHeight(findViewById(R.id.library_clear), true);
        TvLayout.applyButtonHeight(findViewById(R.id.library_pan), true);
        TvLayout.applyButtonHeight(findViewById(R.id.library_settings), true);
    }

    private void resizeTopAction(View view, int sizeDp, boolean capWidth) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.height = dp(sizeDp);
            if (!capWidth) params.width = dp(sizeDp);
            view.setLayoutParams(params);
        }
        if (capWidth && view instanceof TextView text) {
            text.setMaxWidth(dp(Math.max(150f, sizeDp * 3.3f)));
        }
    }

    private static void resizeHeight(View view, int heightPx) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.height = heightPx;
        view.setLayoutParams(params);
    }

    private static void resizeWidth(View view, int widthPx) {
        if (view == null) return;
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) return;
        params.width = widthPx;
        view.setLayoutParams(params);
    }

    private void scheduleNavActivation(int selected) {
        cancelPendingNavActivation();
        pendingNavActivation = () -> {
            pendingNavActivation = null;
            if (navItems != null && selected >= 0 && selected < navItems.length
                    && navItems[selected].hasFocus()) {
                selectTab(selected, true);
            }
        };
        clockHandler.postDelayed(pendingNavActivation, 180L);
    }

    private void cancelPendingNavActivation() {
        if (pendingNavActivation == null) return;
        clockHandler.removeCallbacks(pendingNavActivation);
        pendingNavActivation = null;
    }

    private void selectTab(int selected, boolean fromFocus) {
        if (fromFocus && selected >= 1 && selected <= 4
                && categoryMode && activeTopTab == selected) {
            return;
        }
        if (selected == 0) {
            showHome();
            return;
        }
        if (selected == 5) {
            showLive(fromFocus);
            return;
        }
        if (selected == 6) {
            showLibrary(fromFocus);
            return;
        }
        openCategoryInPlace(selected, fromFocus);
    }

    private void setupActions() {
        sourceSelector = findViewById(R.id.action_source);
        ImageView search = findViewById(R.id.action_search);
        ImageView settings = findViewById(R.id.action_settings);
        FocusAnimator.scale(sourceSelector, 1.04f);
        FocusAnimator.scale(search, 1.06f);
        FocusAnimator.scale(settings, 1.06f);
        sourceSelector.setOnClickListener(view -> openSourcePicker());
        search.setOnClickListener(view -> openSearch());
        settings.setOnClickListener(view -> settingsLauncher.launch(SettingsActivity.intent(this)));
        updateSourceSelector();
    }

    private void openSourcePicker() {
        TvBoxRepository repository = TvBoxRepository.get();
        List<SiteConfig> sites = repository.availableSites();
        if (sites.isEmpty()) {
            Toast.makeText(this, "片源配置尚未加载完成", Toast.LENGTH_SHORT).show();
            loadConfig();
            return;
        }
        String activeKey = repository.getHomeSite() == null
                ? "" : repository.getHomeSite().key;
        String[] labels = new String[sites.size()];
        int selected = 0;
        for (int index = 0; index < sites.size(); index++) {
            SiteConfig site = sites.get(index);
            labels[index] = site.displayName();
            if (site.key.equals(activeKey)) selected = index;
        }
        DialogStyler.showOptions(this, "切换内容源", labels, selected,
                index -> switchSource(sites.get(index)));
    }

    private void switchSource(SiteConfig site) {
        if (site == null || TvBoxRepository.get().getHomeSite() == site) return;
        sourceSelector.setEnabled(false);
        sourceSelector.setText("切换中…");
        TvBoxRepository.get().switchSite(this, site.key,
                new TvBoxRepository.LoadCallback() {
                    @Override
                    public void onHome(TvBoxRepository.Home value) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            home = value;
                            categoryStates.clear();
                            if (value.items.isEmpty()) {
                                homeAdapter.showEmptyHome(!value.types.isEmpty());
                            } else {
                                homeAdapter.update(value.items,
                                        UserDataStore.get(MainActivity.this).history());
                            }
                            sourceSelector.setEnabled(true);
                            updateSourceSelector();
                            showHome();
                            Toast.makeText(MainActivity.this,
                                    "已切换至 " + site.displayName(),
                                    Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        runOnUiThread(() -> {
                            sourceSelector.setEnabled(true);
                            updateSourceSelector();
                            sourceSelector.requestFocus();
                            Toast.makeText(MainActivity.this,
                                    "切换失败：" + rootMessage(error),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    private void updateSourceSelector() {
        if (sourceSelector == null) return;
        SiteConfig site = TvBoxRepository.get().getHomeSite();
        sourceSelector.setText(site == null ? "片源" : conciseSourceName(site));
        sourceSelector.setEnabled(true);
    }

    private static String conciseSourceName(SiteConfig site) {
        String value = site.displayName()
                .replace("┃", " · ")
                .replaceAll("[\\p{So}\\p{Cn}\\p{Cf}]", "")
                .replaceAll("\\s+", " ")
                .trim();
        int separator = value.indexOf(" · ");
        if (separator > 1) value = value.substring(0, separator).trim();
        if (value.length() > 10) value = value.substring(0, 9) + "…";
        return value.isBlank() ? "片源" : value;
    }

    private void loadConfig() {
        final int requestId = ++loadRequestId;
        homeRequestSettled = false;
        homeAdapter.showLoading();
        TvBoxRepository.get().load(this, new TvBoxRepository.LoadCallback() {
            @Override
            public void onHome(TvBoxRepository.Home home) {
                runOnUiThread(() -> {
                    if (requestId != loadRequestId || isFinishing() || isDestroyed()) return;
                    homeRequestSettled = true;
                    MainActivity.this.home = home;
                    updateSourceSelector();
                    if (home.items.isEmpty()) {
                        homeAdapter.showEmptyHome(!home.types.isEmpty());
                    } else {
                        homeAdapter.update(home.items,
                                UserDataStore.get(MainActivity.this).history());
                    }
                    prewarmSecondaryViews();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    if (requestId != loadRequestId || isFinishing() || isDestroyed()) return;
                    homeRequestSettled = true;
                    homeAdapter.showError("片源加载失败：" + error.getMessage()
                            + "。请检查网络后重新加载，或进入设置更换 TVBox 配置。");
                    Toast.makeText(MainActivity.this,
                            "片源加载失败", Toast.LENGTH_LONG).show();
                });
            }
        });
        clockHandler.postDelayed(() -> {
            if (requestId != loadRequestId || homeRequestSettled
                    || isFinishing() || isDestroyed()) return;
            homeAdapter.showError("片源响应超时。你可以重新加载，或进入设置更换 TVBox 配置。");
            Toast.makeText(this, "片源响应超时，可重新加载", Toast.LENGTH_LONG).show();
        }, HOME_LOAD_TIMEOUT_MS);
    }

    private void prewarmSecondaryViews() {
        if (secondaryViewsScheduled) return;
        secondaryViewsScheduled = true;
        clockHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) setupLibraryView();
        }, 3_200L);
        clockHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) setupLiveView();
        }, 6_000L);
    }

    private void openCategoryInPlace(int index, boolean keepNavigationFocus) {
        collapseCategoryFiltersImmediately();
        TvBoxRepository repository = TvBoxRepository.get();
        if (repository.getHomeSite() == null) {
            Toast.makeText(this, "源尚未就绪", Toast.LENGTH_SHORT).show();
            selectNav(activeTopTab);
            return;
        }
        String tid = categoryId(index);
        if (tid == null) {
            Toast.makeText(this, "当前源未提供此分类", Toast.LENGTH_SHORT).show();
            selectNav(activeTopTab);
            return;
        }
        String[] names = {"", "电影", "电视剧", "综艺", "动漫"};
        String name = index < names.length ? names[index] : "分类";
        activeTopTab = index;
        categoryMode = true;
        libraryMode = false;
        liveMode = false;
        keepNavFocusForCategoryLoad = keepNavigationFocus;
        homeList.setVisibility(View.GONE);
        libraryView.setVisibility(View.GONE);
        liveView.setVisibility(View.GONE);
        categoryView.setVisibility(View.VISIBLE);
        navBackdrop.setAlpha(1f);
        for (View item : navItems) item.setNextFocusDownId(View.NO_ID);
        selectNav(index);

        CategoryState state = categoryStates.get(index);
        if (state == null || !tid.equals(state.tid)) {
            state = new CategoryState(tid, name);
            categoryStates.put(index, state);
        }
        activeCategory = state;
        if (state.filterGroups.isEmpty()) {
            state.filterGroups.addAll(TvBoxRepository.get().filtersFor(tid));
        }
        renderCategoryFilters(state, false);
        categoryAdapter.update(state.items);
        categoryList.scrollToPosition(0);
        if (state.loaded) {
            showCategoryState(state.items.isEmpty(), state.items.isEmpty()
                    ? "当前分类没有返回内容" : "");
            if (!state.items.isEmpty() && !keepNavigationFocus) {
                focusCategoryTop(false);
            }
        } else {
            showCategoryState(true, "正在加载" + name + "…");
            loadCategoryPage(state, true);
        }
    }

    private void showHome() {
        collapseCategoryFiltersImmediately();
        categoryMode = false;
        libraryMode = false;
        liveMode = false;
        keepNavFocusForCategoryLoad = false;
        activeCategory = null;
        activeTopTab = 0;
        categoryView.setVisibility(View.GONE);
        libraryView.setVisibility(View.GONE);
        liveView.setVisibility(View.GONE);
        homeList.setVisibility(View.VISIBLE);
        selectNav(0);
        float threshold = Math.max(1f, dp(72));
        navBackdrop.setAlpha(Math.min(1f, scrollY / threshold));
        for (View item : navItems) item.setNextFocusDownId(View.NO_ID);
        homeList.scrollToPosition(0);
        navItems[0].requestFocus();
    }

    private void showLibrary(boolean keepNavigationFocus) {
        collapseCategoryFiltersImmediately();
        setupLibraryView();
        categoryMode = false;
        libraryMode = true;
        liveMode = false;
        keepNavFocusForCategoryLoad = false;
        activeCategory = null;
        activeTopTab = 6;
        homeList.setVisibility(View.GONE);
        categoryView.setVisibility(View.GONE);
        liveView.setVisibility(View.GONE);
        libraryView.setVisibility(View.VISIBLE);
        navBackdrop.setAlpha(1f);
        for (View item : navItems) item.setNextFocusDownId(View.NO_ID);
        selectNav(6);
        refreshLibrary();
        if (!keepNavigationFocus) focusLibraryContent(false);
    }

    private void showLive(boolean keepNavigationFocus) {
        collapseCategoryFiltersImmediately();
        setupLiveView();
        categoryMode = false;
        libraryMode = false;
        liveMode = true;
        keepNavFocusForCategoryLoad = false;
        activeCategory = null;
        activeTopTab = 5;
        homeList.setVisibility(View.GONE);
        categoryView.setVisibility(View.GONE);
        libraryView.setVisibility(View.GONE);
        liveView.setVisibility(View.VISIBLE);
        navBackdrop.setAlpha(1f);
        for (View item : navItems) item.setNextFocusDownId(View.NO_ID);
        selectNav(5);
        liveController.show(!keepNavigationFocus);
    }

    private void selectLibraryMode(boolean history) {
        libraryHistoryMode = history;
        if (libraryTabs != null) libraryTabs.setMode(history, true);
        refreshLibrary();
    }

    private void refreshLibrary() {
        if (!libraryMode || libraryAdapter == null) return;
        List<ContentItem> items = new ArrayList<>();
        libraryHistory.clear();
        UserDataStore store = UserDataStore.get(this);
        if (libraryHistoryMode) {
            libraryHistory.addAll(store.history());
            for (UserDataStore.HistoryEntry entry : libraryHistory) {
                ContentItem item = store.resolve(entry);
                if (item == null) continue;
                String episode = entry.episodeName.isBlank() ? "正片" : entry.episodeName;
                String meta = episode + " · " + formatDuration(entry.positionMs)
                        + (entry.durationMs > 0
                        ? " / " + formatDuration(entry.durationMs) : "");
                items.add(new ContentItem(item.id, item.title, meta, item.pic,
                        entry.itemId + "@" + entry.updatedAt, item.site));
            }
        } else {
            for (UserDataStore.SavedItem saved : store.favorites()) {
                ContentItem item = store.resolve(saved);
                if (item != null) items.add(item);
            }
        }

        libraryAdapter.update(items);
        libraryList.scrollToPosition(0);
        boolean empty = items.isEmpty();
        libraryEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        libraryList.setVisibility(empty ? View.GONE : View.VISIBLE);
        libraryClear.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        if (empty) {
            libraryEmptyTitle.setText(libraryHistoryMode
                    ? "还没有播放记录" : "还没有收藏内容");
            libraryEmptyHint.setText("");
        }
    }

    private boolean focusLibraryContent(boolean force) {
        if (!libraryMode) return false;
        libraryList.post(() -> {
            if (!libraryMode) return;
            RecyclerView.ViewHolder first =
                    libraryList.findViewHolderForAdapterPosition(0);
            if (first != null) {
                if (force) first.itemView.requestFocus();
                else TvFocus.requestInitial(first.itemView, navItems[6]);
            } else {
                if (libraryTabs != null) libraryTabs.requestFocus();
            }
        });
        return true;
    }

    private boolean focusHomeContent(boolean force) {
        return focusHomeContent(force, 8);
    }

    private boolean focusHomeContent(boolean force, int attempts) {
        if (categoryMode || libraryMode || liveMode) return false;
        homeList.post(() -> {
            if (categoryMode || libraryMode || liveMode) return;
            RecyclerView.LayoutManager manager = homeList.getLayoutManager();
            int first = 0;
            int last = homeAdapter.getItemCount() - 1;
            if (manager instanceof GridLayoutManager grid) {
                first = Math.max(0, grid.findFirstVisibleItemPosition());
                last = Math.min(last, grid.findLastVisibleItemPosition());
            }
            for (int position = first; position <= last; position++) {
                RecyclerView.ViewHolder holder =
                        homeList.findViewHolderForAdapterPosition(position);
                if (holder == null) continue;
                View target = firstFocusable(holder.itemView);
                if (target == null) continue;
                if (force) target.requestFocus();
                else TvFocus.requestInitial(target, navItems[0]);
                return;
            }
            if (attempts > 0) {
                homeList.postDelayed(() -> focusHomeContent(force, attempts - 1), 80L);
            }
        });
        return true;
    }

    private static View firstFocusable(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isShown()) return null;
        if (view.isFocusable() && view.isEnabled()) return view;
        if (!(view instanceof ViewGroup group)) return null;
        for (int index = 0; index < group.getChildCount(); index++) {
            View target = firstFocusable(group.getChildAt(index));
            if (target != null) return target;
        }
        return null;
    }

    private void openLibraryItem(ContentItem item) {
        if (!libraryHistoryMode) {
            DetailActivity.start(this, item);
            return;
        }
        UserDataStore.HistoryEntry entry = findLibraryHistory(item);
        if (entry == null || entry.episodeId.isBlank() || item.site == null) {
            DetailActivity.start(this, item);
            return;
        }
        PlayerActivity.start(this, item,
                new TvBoxRepository.Episode(entry.episodeName, entry.episodeId, entry.flag),
                item.title, entry.positionMs);
    }

    private UserDataStore.HistoryEntry findLibraryHistory(ContentItem item) {
        String siteKey = item.site == null ? "" : item.site.key;
        for (UserDataStore.HistoryEntry entry : libraryHistory) {
            if (!item.id.equals(entry.itemId + "@" + entry.updatedAt)) continue;
            if (siteKey.equals(entry.siteKey)) return entry;
        }
        return null;
    }

    private void confirmClearLibrary() {
        String title = libraryHistoryMode ? "清空全部播放记录？" : "清空全部收藏？";
        DialogStyler.showConfirmation(
                this, title, "", "取消", "清空", () -> {
                    UserDataStore store = UserDataStore.get(this);
                    if (libraryHistoryMode) store.clearHistory();
                    else store.clearFavorites();
                    refreshLibrary();
                });
    }

    private static String formatDuration(long millis) {
        int seconds = (int) Math.max(0, millis / 1000);
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void renderCategoryFilters(CategoryState state, boolean focusFirst) {
        if (categoryFilterPanel == null || state == null || state != activeCategory) return;
        firstCategoryFilterOption = null;
        categoryFilterPanel.removeAllViews();
        if (state.filterGroups.isEmpty()) {
            state.filtersExpanded = false;
            categoryFilterOverlay.setVisibility(View.GONE);
            categoryFilterReset.setVisibility(View.GONE);
            updateCategoryFilterIndicator();
            return;
        }
        Set<String> validKeys = new HashSet<>();
        for (TvBoxRepository.FilterGroup group : state.filterGroups) {
            validKeys.add(group.key);
        }
        state.selectedFilters.keySet().removeIf(key -> !validKeys.contains(key));
        List<FilterGroupBinding> groups = visibleFilterGroups(state);
        if (groups.isEmpty()) {
            state.filtersExpanded = false;
            categoryFilterOverlay.setVisibility(View.GONE);
            categoryFilterReset.setVisibility(View.GONE);
            updateCategoryFilterIndicator();
            return;
        }

        int selectedCount = 0;
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            FilterGroupBinding binding = groups.get(groupIndex);
            if (state.selectedFilters.containsKey(binding.group.key)) selectedCount++;
            categoryFilterPanel.addView(createCategoryFilterGroupRow(
                    state, binding, groupIndex, groups.size()));
        }
        categoryFilterReset.setVisibility(selectedCount > 0 ? View.VISIBLE : View.GONE);
        categoryFilterOverlay.setVisibility(state.filtersExpanded ? View.VISIBLE : View.GONE);
        bindFilterHeaderFocus();
        updateCategoryFilterIndicator();
        if (state.pendingFilterFocus != null) {
            String targetKey = state.pendingFilterFocus;
            state.pendingFilterFocus = null;
            categoryFilterPanel.post(() -> {
                View target = categoryFilterPanel.findViewWithTag(targetKey);
                if (target != null) target.requestFocus();
            });
            return;
        }
        if (focusFirst && state.filtersExpanded) {
            categoryFilterPanel.post(this::focusFirstFilterOption);
        }
    }

    private View createCategoryFilterGroupRow(CategoryState state,
                                              FilterGroupBinding binding,
                                              int groupIndex,
                                              int groupCount) {
        if (layoutMetrics == null) layoutMetrics = TvLayout.metrics(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)));

        TextView label = new TextView(this);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        dp(Math.max(54f, layoutMetrics.contentMarginDp * 1.25f)),
                        ViewGroup.LayoutParams.MATCH_PARENT);
        label.setLayoutParams(labelParams);
        label.setText(binding.label);
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(Math.max(12f, layoutMetrics.posterTitleTextSizeSp - 4f));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(label);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        scroll.setLayoutParams(scrollParams);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.setGravity(android.view.Gravity.CENTER_VERTICAL);
        options.setPadding(dp(4), 0, dp(10), 0);
        options.setLayoutParams(new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String current = currentCategoryFilterValue(state, binding.group);
        for (TvBoxRepository.FilterOption option : binding.group.options) {
            TextView chip = createInlineFilterOption(
                    state, binding.group, option, option.value.equals(current),
                    groupIndex, groupCount);
            options.addView(chip);
            if (firstCategoryFilterOption == null) firstCategoryFilterOption = chip;
        }
        scroll.addView(options);
        row.addView(scroll);
        return row;
    }

    private TextView createInlineFilterOption(CategoryState state,
                                              TvBoxRepository.FilterGroup group,
                                              TvBoxRepository.FilterOption option,
                                              boolean selected,
                                              int groupIndex,
                                              int groupCount) {
        if (layoutMetrics == null) layoutMetrics = TvLayout.metrics(this);
        TextView chip = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34));
        params.setMarginEnd(dp(7));
        chip.setLayoutParams(params);
        chip.setText(option.label);
        chip.setTextColor(getColorStateList(R.color.filter_chip_text));
        chip.setTextSize(Math.max(12f, layoutMetrics.posterTitleTextSizeSp - 5f));
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setBackgroundResource(R.drawable.bg_filter_chip);
        chip.setTag(filterOptionTag(group.key, option.value));
        chip.setId(View.generateViewId());
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setSelected(selected);
        if (groupIndex == 0 && navItems != null && activeTopTab >= 0
                && activeTopTab < navItems.length) {
            chip.setNextFocusUpId(navItems[activeTopTab].getId());
        }
        if (groupIndex == groupCount - 1) chip.setNextFocusDownId(categoryList.getId());
        chip.setOnClickListener(view -> {
            state.selectedFilters.put(group.key, option.value);
            state.filterRequested = true;
            state.pendingFilterFocus = filterOptionTag(group.key, option.value);
            renderCategoryFilters(state, false);
            reloadCategory(state);
        });
        chip.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    || event.getRepeatCount() != 0) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && groupIndex == 0) {
                return focusActiveNavItem();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    && groupIndex == groupCount - 1) {
                state.filtersExpanded = false;
                hideCategoryFilterOverlay(false, false);
                return focusCategoryContent(true);
            }
            return false;
        });
        FocusAnimator.scale(chip, 1.035f, () -> chip.post(() ->
                chip.requestRectangleOnScreen(
                        new Rect(0, 0, chip.getWidth(), chip.getHeight()), true)));
        return chip;
    }

    private void setCategoryFiltersExpanded(CategoryState state,
                                            boolean expanded,
                                            boolean animate,
                                            boolean focusFirstOption) {
        if (state == null || state != activeCategory || categoryFilterPanel == null) return;
        if (expanded && visibleFilterGroups(state).isEmpty()) {
            updateCategoryFilterIndicator();
            Toast.makeText(this, "当前内容源没有提供筛选项", Toast.LENGTH_SHORT).show();
            return;
        }
        state.filtersExpanded = expanded;
        if (expanded) {
            showCategoryFilterOverlay(animate);
            if (focusFirstOption) categoryFilterPanel.post(this::focusFirstFilterOption);
        } else {
            hideCategoryFilterOverlay(animate);
        }
    }

    private void toggleCategoryFiltersFromNav() {
        CategoryState state = activeCategory;
        if (state == null) return;
        setCategoryFiltersExpanded(
                state, !state.filtersExpanded, true, !state.filtersExpanded);
    }

    private void showCategoryFilterOverlay(boolean animate) {
        if (categoryFilterOverlay == null) return;
        categoryFilterOverlay.animate().cancel();
        categoryFilterOverlay.setVisibility(View.VISIBLE);
        if (!animate) {
            categoryFilterOverlay.setAlpha(1f);
            categoryFilterOverlay.setTranslationY(0f);
            categoryFilterOverlay.setScaleX(1f);
            categoryFilterOverlay.setScaleY(1f);
            return;
        }
        categoryFilterOverlay.setAlpha(0f);
        categoryFilterOverlay.setTranslationY(-dp(8));
        categoryFilterOverlay.setScaleX(0.985f);
        categoryFilterOverlay.setScaleY(0.985f);
        categoryFilterOverlay.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(190L)
                .setInterpolator(FILTER_TRANSITION_INTERPOLATOR)
                .start();
    }

    private void hideCategoryFilterOverlay(boolean animate) {
        hideCategoryFilterOverlay(animate, true);
    }

    private void hideCategoryFilterOverlay(boolean animate, boolean returnToNav) {
        if (categoryFilterOverlay == null) return;
        boolean shouldReturnToNav = returnToNav && categoryFilterOverlay.hasFocus();
        categoryFilterOverlay.animate().cancel();
        if (!animate) {
            categoryFilterOverlay.setVisibility(View.GONE);
            categoryFilterOverlay.setAlpha(1f);
            categoryFilterOverlay.setTranslationY(0f);
            categoryFilterOverlay.setScaleX(1f);
            categoryFilterOverlay.setScaleY(1f);
        } else {
            categoryFilterOverlay.animate()
                    .alpha(0f)
                    .translationY(-dp(6))
                    .scaleX(0.99f)
                    .scaleY(0.99f)
                    .setDuration(150L)
                    .setInterpolator(FILTER_TRANSITION_INTERPOLATOR)
                    .withEndAction(() -> {
                        CategoryState state = activeCategory;
                        if (state != null && state.filtersExpanded) return;
                        categoryFilterOverlay.setVisibility(View.GONE);
                        categoryFilterOverlay.setAlpha(1f);
                        categoryFilterOverlay.setTranslationY(0f);
                        categoryFilterOverlay.setScaleX(1f);
                        categoryFilterOverlay.setScaleY(1f);
                    })
                    .start();
        }
        if (shouldReturnToNav) focusActiveNavItem();
    }

    private void collapseCategoryFiltersImmediately() {
        CategoryState state = activeCategory;
        if (state != null) {
            state.filtersExpanded = false;
            state.pendingFilterFocus = null;
        }
        if (categoryFilterOverlay != null) {
            categoryFilterOverlay.animate().cancel();
            categoryFilterOverlay.setVisibility(View.GONE);
            categoryFilterOverlay.setAlpha(1f);
            categoryFilterOverlay.setTranslationY(0f);
            categoryFilterOverlay.setScaleX(1f);
            categoryFilterOverlay.setScaleY(1f);
        }
    }

    private boolean focusFirstFilterOption() {
        if (firstCategoryFilterOption == null || !firstCategoryFilterOption.isShown()) {
            return false;
        }
        firstCategoryFilterOption.requestFocus();
        return true;
    }

    private void bindFilterHeaderFocus() {
        if (navItems == null || activeTopTab < 0 || activeTopTab >= navItems.length) return;
        int navId = navItems[activeTopTab].getId();
        categoryFilterReset.setNextFocusUpId(navId);
        categoryFilterReset.setNextFocusDownId(firstCategoryFilterOption == null
                ? categoryList.getId() : firstCategoryFilterOption.getId());
        if (firstCategoryFilterOption != null) {
            firstCategoryFilterOption.setNextFocusUpId(navId);
        }
    }

    private boolean focusActiveNavItem() {
        if (navItems == null || activeTopTab < 0 || activeTopTab >= navItems.length) {
            return false;
        }
        navItems[activeTopTab].requestFocus();
        return true;
    }

    private void updateCategoryFilterIndicator() {
        if (navItems == null) return;
        boolean show = categoryMode && activeCategory != null
                && activeTopTab >= 1 && activeTopTab <= 4
                && !visibleFilterGroups(activeCategory).isEmpty();
        for (int index = 1; index <= 4 && index < navItems.length; index++) {
            if (!(navItems[index] instanceof TextView item)) continue;
            boolean itemShowsFilter = show && index == activeTopTab;
            item.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0, 0, itemShowsFilter ? R.drawable.ic_filter : 0, 0);
            item.setCompoundDrawablePadding(itemShowsFilter ? dp(7) : 0);
            TextViewCompat.setCompoundDrawableTintList(
                    item, itemShowsFilter
                            ? android.content.res.ColorStateList.valueOf(
                                    ThemeManager.accent(this))
                            : getColorStateList(R.color.nav_text));
        }
    }

    private List<FilterGroupBinding> visibleFilterGroups(CategoryState state) {
        String[][] aliases = activeTopTab == 1 || activeTopTab == 2
                ? MOVIE_TV_FILTER_ALIASES
                : activeTopTab == 3 || activeTopTab == 4
                ? VARIETY_ANIME_FILTER_ALIASES : new String[0][];
        List<FilterGroupBinding> bindings = new ArrayList<>();
        Set<String> usedKeys = new HashSet<>();
        for (String[] groupAliases : aliases) {
            TvBoxRepository.FilterGroup matched = null;
            for (String alias : groupAliases) {
                for (TvBoxRepository.FilterGroup group : state.filterGroups) {
                    if (usedKeys.contains(group.key) || group.options.isEmpty()) continue;
                    if (filterNameMatches(group, alias)) {
                        matched = group;
                        break;
                    }
                }
                if (matched != null) break;
            }
            if (matched != null) {
                bindings.add(new FilterGroupBinding(groupAliases[0], matched));
                usedKeys.add(matched.key);
            }
        }
        return bindings;
    }

    private static boolean filterNameMatches(TvBoxRepository.FilterGroup group, String alias) {
        String normalizedAlias = normalizeFilterName(alias);
        String name = normalizeFilterName(group.name);
        String key = normalizeFilterName(group.key);
        return name.equals(normalizedAlias)
                || name.contains(normalizedAlias)
                || key.equals(normalizedAlias);
    }

    private static String normalizeFilterName(String value) {
        return value == null ? ""
                : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String filterOptionTag(String groupKey, String optionValue) {
        return "filter-option:" + groupKey + ":" + optionValue;
    }

    private String currentCategoryFilterValue(CategoryState state,
                                              TvBoxRepository.FilterGroup group) {
        String selected = state.selectedFilters.get(group.key);
        if (selected != null) return selected;
        if (group.init != null && !group.init.isBlank()) return group.init;
        return group.options.isEmpty() ? "" : group.options.get(0).value;
    }

    private Map<String, String> currentCategoryFilterValues(CategoryState state) {
        Map<String, String> values = new LinkedHashMap<>();
        for (FilterGroupBinding binding : visibleFilterGroups(state)) {
            values.put(binding.group.key,
                    currentCategoryFilterValue(state, binding.group));
        }
        return values;
    }

    private void loadCategoryPage(CategoryState state, boolean reset) {
        if (state == null || state.loading) return;
        if (reset) {
            state.generation++;
            state.items.clear();
            state.identities.clear();
            state.nextPage = 1;
            state.hasMore = true;
            state.loaded = false;
            if (state == activeCategory) categoryAdapter.update(state.items);
        }
        if (!state.hasMore) return;

        state.loading = true;
        final int generation = state.generation;
        final int page = state.nextPage;
        if (state == activeCategory) {
            showCategoryState(page == 1, page == 1
                    ? "正在加载" + state.name + "…" : "正在加载第 " + page + " 页…");
        }
        Map<String, String> extend = state.filterRequested
                ? currentCategoryFilterValues(state) : null;
        TvBoxRepository.get().category(state.tid, String.valueOf(page),
                state.filterRequested, extend,
                new TvBoxRepository.CategoryCallback() {
                    @Override
                    public void onCategory(TvBoxRepository.CategoryPage result) {
                        runOnUiThread(() -> {
                            if (generation != state.generation || isFinishing()
                                    || isDestroyed()) return;
                            state.loading = false;
                            if (!result.filters.isEmpty() && state.filterGroups.isEmpty()) {
                                state.filterGroups.clear();
                                state.filterGroups.addAll(result.filters);
                                if (state == activeCategory) {
                                    renderCategoryFilters(state, false);
                                }
                            }
                            int before = state.items.size();
                            for (ContentItem item : result.items) {
                                String identity = (item.site == null ? "" : item.site.key)
                                        + "\u0000" + item.id;
                                if (state.identities.add(identity)) state.items.add(item);
                            }
                            state.nextPage = page + 1;
                            state.hasMore = page < MAX_CATEGORY_PAGES
                                    && !result.items.isEmpty()
                                    && state.items.size() > before;
                            state.loaded = true;
                            if (state == activeCategory) {
                                categoryAdapter.update(state.items);
                                showCategoryState(state.items.isEmpty(),
                                        state.items.isEmpty()
                                                ? "当前分类没有返回内容"
                                                : "正在加载第 " + state.nextPage + " 页…");
                                if (page == 1 && !state.items.isEmpty()
                                        && !keepNavFocusForCategoryLoad
                                        && !categoryFilterOverlay.hasFocus()) {
                                    focusCategoryContent(false);
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        runOnUiThread(() -> {
                            if (generation != state.generation) return;
                            state.loading = false;
                            if (page == 1) {
                                state.loaded = false;
                                if (state == activeCategory) {
                                    showCategoryState(true, "暂时无法加载");
                                    categoryRetry.setVisibility(View.VISIBLE);
                                }
                            } else {
                                state.hasMore = true;
                                if (state == activeCategory) {
                                    categoryMessage.setText("加载中断，继续滚动可重试");
                                }
                            }
                        });
                    }
                });
    }

    private void reloadCategory() {
        CategoryState state = activeCategory;
        if (state == null) return;
        reloadCategory(state);
    }

    private void reloadCategory(CategoryState state) {
        if (state == null) return;
        state.loading = false;
        loadCategoryPage(state, true);
    }

    private boolean focusCategoryTop(boolean force) {
        if (!categoryMode) return false;
        if (categoryFilterOverlay != null
                && categoryFilterOverlay.getVisibility() == View.VISIBLE) {
            return focusFirstFilterOption() || focusCategoryContent(force);
        }
        return focusCategoryContent(force);
    }

    private boolean focusCategoryContent(boolean force) {
        if (!categoryMode) return false;
        if (categoryRetry != null && categoryRetry.isShown()) {
            if (force) categoryRetry.requestFocus();
            return true;
        }
        if (categoryAdapter == null || categoryAdapter.getItemCount() == 0) return false;
        focusCategoryItem(force, 4);
        return true;
    }

    private void focusCategoryItem(boolean force, int remainingAttempts) {
        if (!categoryMode || categoryList == null) return;
        categoryList.post(() -> {
            if (!categoryMode || categoryAdapter == null
                    || categoryAdapter.getItemCount() == 0) return;
            RecyclerView.ViewHolder first =
                    categoryList.findViewHolderForAdapterPosition(0);
            if (first != null) {
                if (force) {
                    first.itemView.requestFocus();
                } else {
                    View replaceable = activeTopTab >= 0 && navItems != null
                            && activeTopTab < navItems.length ? navItems[activeTopTab] : null;
                    TvFocus.requestInitial(first.itemView, replaceable);
                }
                return;
            }
            if (remainingAttempts > 0) {
                categoryList.postDelayed(
                        () -> focusCategoryItem(force, remainingAttempts - 1), 80L);
            }
        });
    }

    private void showCategoryState(boolean visible, String message) {
        categoryStateView.setVisibility(visible ? View.VISIBLE : View.GONE);
        categoryList.setVisibility(visible ? View.GONE : View.VISIBLE);
        categoryProgress.setVisibility(activeCategory != null
                && activeCategory.loading ? View.VISIBLE : View.GONE);
        categoryMessage.setText(message);
        categoryRetry.setVisibility(View.GONE);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? "未知错误"
                : String.valueOf(current.getMessage() == null ? current : current.getMessage());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String categoryId(int index) {
        if (home == null || home.types.isEmpty()
                || index < 1 || index >= CATEGORY_ALIASES.length) return null;

        Map<Integer, String> resolved = new HashMap<>();
        Set<String> assignedIds = new HashSet<>();
        for (int tab = 1; tab < CATEGORY_ALIASES.length; tab++) {
            String id = findCategoryId(tab, assignedIds);
            if (id == null) continue;
            resolved.put(tab, id);
            assignedIds.add(id);
        }
        String matched = resolved.get(index);
        if (matched != null) return matched;

        for (TvBoxRepository.Category type : home.types) {
            if (!assignedIds.contains(type.id)) return type.id;
        }
        return null;
    }

    private String findCategoryId(int index, Set<String> excludedIds) {
        for (String alias : CATEGORY_ALIASES[index]) {
            for (TvBoxRepository.Category type : home.types) {
                String name = type.name == null ? "" : type.name.replaceAll("\\s+", "");
                if (excludedIds.contains(type.id)) continue;
                if (name.equals(alias) || name.contains(alias)) return type.id;
            }
        }
        return null;
    }

    private void openSearch() {
        SearchActivity.start(this);
    }

    private void refreshHome() {
        TvBoxRepository repository = TvBoxRepository.get();
        if (repository.getHomeSite() == null) {
            loadConfig();
            return;
        }
        repository.home(new TvBoxRepository.LoadCallback() {
            @Override
            public void onHome(TvBoxRepository.Home value) {
                runOnUiThread(() -> {
                    home = value;
                    updateSourceSelector();
                    if (value.items.isEmpty()) {
                        homeAdapter.showEmptyHome(!value.types.isEmpty());
                    } else {
                        homeAdapter.update(value.items,
                                UserDataStore.get(MainActivity.this).history());
                    }
                    homeList.scrollToPosition(0);
                    navBackdrop.setAlpha(0f);
                    scrollY = 0;
                    navItems[0].requestFocus();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "首页刷新失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void selectNav(int selected) {
        if (navItems == null) return;
        for (int index = 0; index < navItems.length; index++) {
            navItems[index].setSelected(index == selected);
        }
        updateCategoryFilterIndicator();
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
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            if (liveMode && liveController != null
                    && TvFocus.returnToTopOnUp(event,
                    liveController.channelView(), liveController.topFilterTarget())) {
                return true;
            }
            if (libraryMode && libraryTabs != null
                    && TvFocus.returnToTopOnUp(event, libraryList, libraryTabs.view())) {
                return true;
            }
            View contentRoot = categoryMode ? categoryList
                    : libraryMode ? libraryView
                    : liveMode ? liveView : homeList;
            View topTarget = navItems != null && activeTopTab >= 0
                    && activeTopTab < navItems.length ? navItems[activeTopTab] : null;
            if (TvFocus.returnToTopOnUp(event, contentRoot, topTarget)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSourceSelector();
        selectNav(activeTopTab);
        if (homeAdapter != null) {
            homeAdapter.updateContinue(UserDataStore.get(this).history());
        }
        if (libraryMode) refreshLibrary();
        if (liveMode && liveController != null) liveController.onResume();
        View selectedTab = navItems == null || activeTopTab < 0
                || activeTopTab >= navItems.length ? null : navItems[activeTopTab];
        View current = getCurrentFocus();
        if (selectedTab == null) return;
        if (categoryMode && (current == null || isTopNavItem(current))
                && focusCategoryContent(false)) {
            return;
        }
        if (current == null || isTopNavItem(current)) {
            selectedTab.requestFocus();
            return;
        }
        current.post(() -> {
            if (!current.hasFocus()) current.requestFocus();
        });
    }

    private boolean isTopNavItem(View view) {
        if (view == null || navItems == null) return false;
        for (View navItem : navItems) {
            if (navItem == view) return true;
        }
        return view.getId() == R.id.action_search
                || view.getId() == R.id.action_source
                || view.getId() == R.id.action_settings;
    }

    @Override
    protected void onDestroy() {
        loadRequestId++;
        for (CategoryState state : categoryStates.values()) state.generation++;
        pendingNavActivation = null;
        if (liveController != null) liveController.destroy();
        clockHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static final class CategoryState {
        final String tid;
        final String name;
        final List<ContentItem> items = new ArrayList<>();
        final Set<String> identities = new HashSet<>();
        final List<TvBoxRepository.FilterGroup> filterGroups = new ArrayList<>();
        final Map<String, String> selectedFilters = new LinkedHashMap<>();
        int nextPage = 1;
        int generation;
        boolean loading;
        boolean loaded;
        boolean hasMore = true;
        boolean filterRequested;
        boolean filtersExpanded;
        String pendingFilterFocus;

        CategoryState(String tid, String name) {
            this.tid = tid;
            this.name = name;
        }
    }

    private static final class FilterGroupBinding {
        final String label;
        final TvBoxRepository.FilterGroup group;

        FilterGroupBinding(String label, TvBoxRepository.FilterGroup group) {
            this.label = label;
            this.group = group;
        }
    }
}
