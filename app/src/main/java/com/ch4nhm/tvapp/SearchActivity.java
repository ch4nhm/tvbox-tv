package com.ch4nhm.tvapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class SearchActivity extends BaseActivity {

    private static final String TAG = "TVBoxSearch";
    private static final long LOCAL_SUGGESTION_DELAY_MS = 200L;
    private static final int MAX_LOCAL_SUGGESTIONS = 4;
    private static final int MAX_REMOTE_SUGGESTIONS = 4;
    private static final int RESULTS_PAGE_SIZE = 24;
    private static final long RESULT_UPDATE_DELAY_MS = 120L;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService computationExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tvbox-search");
                thread.setDaemon(true);
                return thread;
            });
    private final List<ContentItem> visibleItems = new ArrayList<>();
    private final Map<String, SearchResultAggregator.SourceGroup> sourceGroupsByKey =
            new HashMap<>();
    private final Map<String, Integer> sourceVisibleCounts = new LinkedHashMap<>();
    private final Map<String, Integer> sourceScrollPositions = new LinkedHashMap<>();
    private final Runnable localSuggestionTask = this::buildLocalSuggestions;

    private EditText input;
    private TextView action;
    private TextView count;
    private RecyclerView suggestionList;
    private RecyclerView sourceList;
    private RecyclerView list;
    private View sourcePanel;
    private View resultsPanel;
    private View state;
    private ProgressBar progress;
    private TextView message;
    private SearchSuggestionAdapter suggestionAdapter;
    private SearchSourceAdapter sourceAdapter;
    private PosterAdapter adapter;
    private Set<String> favoriteKeys = Set.of();
    private Set<String> historyKeys = Set.of();

    private volatile int generation;
    private volatile int renderGeneration;
    private int localSuggestionGeneration;
    private int remoteSuggestionGeneration;
    private boolean loading;
    private boolean configLoading;
    private String pendingConfigKeyword = "";
    private boolean pendingConfigAllowRemote;
    private String remoteSuggestionQuery = "";
    private boolean remoteSuggestionPending;
    private String selectedSourceKey = "";
    private int resultSpanCount = 3;
    private boolean resultFocusPending;
    private boolean pageLoadScheduled;
    private boolean searchCompleted;
    private TvBoxRepository.SearchHandle activeSearch;
    private Runnable pendingResultTask;
    private List<ContentItem> pendingResultSnapshot = List.of();
    private String pendingResultKeyword = "";
    private int pendingResultRequest;
    private ViewTreeObserver.OnPreDrawListener viewportFillListener;

    public static void start(Context context) {
        context.startActivity(new Intent(context, SearchActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_search);
        enterImmersive();

        input = findViewById(R.id.search_input);
        action = findViewById(R.id.search_action);
        count = findViewById(R.id.search_count);
        suggestionList = findViewById(R.id.search_suggestions);
        sourceList = findViewById(R.id.search_sources);
        list = findViewById(R.id.search_list);
        sourcePanel = findViewById(R.id.search_source_panel);
        resultsPanel = findViewById(R.id.search_results_panel);
        state = findViewById(R.id.search_state);
        progress = findViewById(R.id.search_progress);
        message = findViewById(R.id.search_message);

        setupSuggestions();
        setupSources();
        applyResponsiveChrome();
        setupResults();
        setupInput();
        FocusAnimator.scale(action, 1.04f);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dismissSuggestions(true)) return;
                finish();
            }
        });

        input.requestFocus();
    }

    private void setupSuggestions() {
        suggestionList.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        suggestionList.setItemAnimator(null);
        suggestionAdapter = new SearchSuggestionAdapter(new SearchSuggestionAdapter.Listener() {
            @Override
            public void onSelected(SearchSuggestion suggestion) {
                if (suggestion == null || suggestion.title.isBlank()) return;
                selectSuggestedTitle(suggestion.title);
            }

            @Override
            public void onUp() {
                input.requestFocus();
            }
        });
        suggestionList.setAdapter(suggestionAdapter);
    }

    private void setupSources() {
        sourceList.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        sourceList.setItemAnimator(null);
        sourceList.setClipChildren(false);
        sourceList.setClipToPadding(false);
        sourceAdapter = new SearchSourceAdapter(source -> selectSource(source.key, true));
        sourceList.setAdapter(sourceAdapter);
    }

    private void setupResults() {
        list.setLayoutManager(new GridLayoutManager(this, resultSpanCount));
        GridSpacingDecoration.addPx(list, TvDisplayAdapter.posterGapPx(this));
        list.setItemAnimator(null);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        adapter = new PosterAdapter(visibleItems, this::openResult);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) scheduleNextPageIfNeeded();
            }
        });
    }

    private void setupInput() {
        input.setShowSoftInputOnFocus(false);
        input.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) resultFocusPending = false;
        });
        input.setOnClickListener(view -> showKeyboard());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable value) {
                invalidateRemoteSuggestions();
                scheduleLocalSuggestions(value == null ? "" : value.toString());
            }
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                searchFromInput();
                return true;
            }
            return false;
        });
        input.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    || event.getRepeatCount() != 0
                    || keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                return false;
            }
            return focusSuggestion(0);
        });
        action.setOnClickListener(view -> searchFromInput());
    }

    private void applyResponsiveChrome() {
        TvLayoutMetrics metrics = TvLayout.metrics(this);
        View inputRow = (View) input.getParent();
        if (!(inputRow.getParent() instanceof View content)) return;
        int margin = dp(metrics.contentMarginDp);
        content.setPadding(margin, dp(14), margin, dp(22));

        if (count.getParent() instanceof View header) {
            ViewGroup.LayoutParams headerParams = header.getLayoutParams();
            if (headerParams != null) {
                headerParams.height = dp(metrics.pageHeaderHeightDp);
                header.setLayoutParams(headerParams);
            }
            if (header instanceof ViewGroup group && group.getChildCount() > 0
                    && group.getChildAt(0) instanceof TextView title) {
                title.setTextSize(metrics.pageTitleTextSizeSp);
            }
        }

        ViewGroup.LayoutParams rowParams = inputRow.getLayoutParams();
        if (rowParams != null) {
            rowParams.height = dp(metrics.buttonHeightDp + 8f);
            inputRow.setLayoutParams(rowParams);
        }
        for (View view : new View[]{input, action}) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                params.height = dp(metrics.buttonHeightDp);
                view.setLayoutParams(params);
            }
        }

        float contentWidthDp = Math.max(360f,
                TvDisplayAdapter.viewport(this).usableWidthDp
                        - metrics.contentMarginDp * 2f);
        float panelWidthDp = Math.max(210f,
                Math.min(320f, contentWidthDp * 0.24f));
        float panelGapDp = Math.max(16f, Math.min(28f, contentWidthDp * 0.025f));
        float resultWidthDp = Math.max(220f,
                contentWidthDp - panelWidthDp - panelGapDp);
        float desiredCardWidthDp = Math.max(118f,
                Math.min(metrics.posterCardWidthDp, metrics.posterCardWidthDp * 0.82f));
        int columns = (int) Math.floor(
                (resultWidthDp + metrics.cardGapDp)
                        / (desiredCardWidthDp + metrics.cardGapDp));
        resultSpanCount = Math.max(2,
                Math.min(metrics.posterColumns, columns));

        ViewGroup.LayoutParams sourceParams = sourcePanel.getLayoutParams();
        if (sourceParams != null) {
            sourceParams.width = dp(panelWidthDp);
            sourcePanel.setLayoutParams(sourceParams);
        }
        ViewGroup.LayoutParams resultParams = resultsPanel.getLayoutParams();
        if (resultParams instanceof ViewGroup.MarginLayoutParams margins) {
            margins.setMarginStart(dp(panelGapDp));
            resultsPanel.setLayoutParams(margins);
        }
        list.setPadding(list.getPaddingLeft(), dp(16),
                list.getPaddingRight(), dp(28));
    }

    private void searchFromInput() {
        if (loading || configLoading) return;
        String keyword = input.getText().toString().trim();
        if (keyword.isEmpty()) {
            count.setText("");
            hideSuggestions();
            input.requestFocus();
            return;
        }
        submitSearch(keyword, SearchTextMatcher.isPinyinLike(keyword));
    }

    private void selectSuggestedTitle(String title) {
        String keyword = title == null ? "" : title.trim();
        if (keyword.isEmpty()) return;
        input.setText(keyword);
        input.setSelection(keyword.length());
        submitSearch(keyword, false);
    }

    private void submitSearch(String keyword, boolean allowRemoteSuggestions) {
        hideKeyboard();
        hideSuggestions();
        debounceHandler.removeCallbacks(localSuggestionTask);
        localSuggestionGeneration++;
        remoteSuggestionGeneration++;
        remoteSuggestionQuery = "";
        remoteSuggestionPending = false;
        captureUserSignals();

        if (TvBoxRepository.get().getConfig() == null) {
            loadConfigThenSearch(keyword, allowRemoteSuggestions);
            return;
        }
        beginSearch(keyword, allowRemoteSuggestions);
    }

    private void loadConfigThenSearch(String keyword, boolean allowRemoteSuggestions) {
        pendingConfigKeyword = keyword;
        pendingConfigAllowRemote = allowRemoteSuggestions;
        configLoading = true;
        action.setEnabled(false);
        action.setAlpha(0.55f);
        count.setText("准备中");
        list.setVisibility(View.GONE);
        state.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        message.setText("正在启动搜索");
        TvBoxRepository.get().load(this, new TvBoxRepository.LoadCallback() {
            @Override
            public void onHome(TvBoxRepository.Home home) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    configLoading = false;
                    beginSearch(pendingConfigKeyword, pendingConfigAllowRemote);
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    configLoading = false;
                    fail(++generation, error);
                });
            }
        });
    }

    private void beginSearch(String keyword, boolean allowRemoteSuggestions) {
        int request = ++generation;
        renderGeneration++;
        clearPendingResultTask();
        searchCompleted = false;
        cancelActiveSearch();
        loading = true;
        selectedSourceKey = "";
        resultFocusPending = true;
        sourceGroupsByKey.clear();
        sourceVisibleCounts.clear();
        sourceScrollPositions.clear();
        visibleItems.clear();
        sourceAdapter.update(List.of(), "");
        action.setEnabled(false);
        action.setAlpha(0.55f);
        count.setText("搜索中");
        adapter.update(visibleItems);
        list.setVisibility(View.GONE);
        sourcePanel.setVisibility(View.GONE);
        state.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        message.setText("正在搜索“" + keyword + "”");
        action.requestFocus();

        if (allowRemoteSuggestions) requestRemoteSuggestions(keyword);
        activeSearch = TvBoxRepository.get().search(keyword, new TvBoxRepository.SearchCallback() {
            @Override
            public void onUpdate(List<ContentItem> result) {
                acceptSearchResult(request, keyword, result, false);
            }

            @Override
            public void onSearch(List<ContentItem> result) {
                acceptSearchResult(request, keyword, result, true);
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> fail(request, error));
            }
        });
    }

    private void cancelActiveSearch() {
        TvBoxRepository.SearchHandle current = activeSearch;
        activeSearch = null;
        if (current != null) current.cancel();
    }

    private void acceptSearchResult(int request, String keyword,
                                    List<ContentItem> result, boolean complete) {
        runOnUiThread(() -> {
            if (request != generation || isFinishing() || isDestroyed()) return;
            List<ContentItem> snapshot = result == null ? List.of() : result;
            if (complete) {
                searchCompleted = true;
                clearPendingResultTask();
                render(request, keyword, snapshot, true);
                return;
            }
            if (searchCompleted) return;
            pendingResultRequest = request;
            pendingResultKeyword = keyword;
            pendingResultSnapshot = snapshot;
            if (pendingResultTask != null) return;
            pendingResultTask = () -> {
                pendingResultTask = null;
                if (pendingResultRequest != generation || searchCompleted
                        || isFinishing() || isDestroyed()) {
                    return;
                }
                render(pendingResultRequest, pendingResultKeyword,
                        pendingResultSnapshot, false);
            };
            debounceHandler.postDelayed(pendingResultTask, RESULT_UPDATE_DELAY_MS);
        });
    }

    private void clearPendingResultTask() {
        if (pendingResultTask != null) {
            debounceHandler.removeCallbacks(pendingResultTask);
            pendingResultTask = null;
        }
        pendingResultSnapshot = List.of();
        pendingResultKeyword = "";
        pendingResultRequest = 0;
    }

    private void render(int request, String keyword, List<ContentItem> result,
                        boolean complete) {
        List<ContentItem> snapshot = result == null ? List.of() : new ArrayList<>(result);
        runOnUiThread(() -> {
            if (request != generation || isFinishing() || isDestroyed()) return;
            int renderRequest = ++renderGeneration;
            submitComputation(() -> {
                if (request != generation || renderRequest != renderGeneration) return;
                SearchResultAggregator.Result aggregated;
                try {
                    aggregated = SearchResultAggregator.aggregate(snapshot);
                } catch (Throwable error) {
                    runOnUiThread(() -> fail(request, error));
                    return;
                }
                runOnUiThread(() -> {
                    if (request != generation || renderRequest != renderGeneration
                            || isFinishing() || isDestroyed()) {
                        return;
                    }
                    show(request, keyword, aggregated, complete);
                });
            });
        });
    }

    private void show(int request, String keyword,
                      SearchResultAggregator.Result aggregated, boolean complete) {
        if (request != generation || isFinishing() || isDestroyed()) return;
        try {
            if (complete) {
                loading = false;
                action.setEnabled(true);
                action.setAlpha(1f);
            }

            sourceGroupsByKey.clear();
            for (SearchResultAggregator.SourceGroup source : aggregated.sources) {
                sourceGroupsByKey.put(source.key, source);
            }

            if (aggregated.sources.isEmpty()) {
                if (!complete) {
                    count.setText("搜索中");
                    return;
                }
                selectedSourceKey = "";
                visibleItems.clear();
                adapter.update(visibleItems);
                sourceAdapter.update(List.of(), "");
                count.setText("0 个结果");
                list.setVisibility(View.GONE);
                sourcePanel.setVisibility(View.GONE);
                state.setVisibility(View.VISIBLE);
                progress.setVisibility(View.GONE);
                message.setText("没有找到相关影片");
                if (suggestionList.getVisibility() == View.VISIBLE) input.requestFocus();
                else action.requestFocus();
                return;
            }

            if (selectedSourceKey.isBlank()
                    || !sourceGroupsByKey.containsKey(selectedSourceKey)) {
                selectedSourceKey = aggregated.sources.get(0).key;
            }
            sourcePanel.setVisibility(View.VISIBLE);
            sourceAdapter.update(aggregated.sources, selectedSourceKey);
            displaySelectedSource(false, false);

            String summary = String.format(Locale.getDefault(),
                    "%d 个资源 · %d 个结果",
                    aggregated.sources.size(), aggregated.itemCount);
            count.setText(complete ? summary : summary + " · 搜索中");
            state.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            scheduleVisibleViewportFill();
            if (complete && !remoteSuggestionPending
                    && suggestionList.getVisibility() != View.VISIBLE
                    && resultFocusPending) {
                resultFocusPending = false;
                if (shouldAutoFocusResults()) focusFirstResult(true);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to render search results", error);
            fail(request, error);
        }
    }

    private void fail(int request, Throwable error) {
        if (request != generation || isFinishing() || isDestroyed()) return;
        cancelActiveSearch();
        searchCompleted = true;
        clearPendingResultTask();
        loading = false;
        action.setEnabled(true);
        action.setAlpha(1f);
        count.setText("搜索失败");
        sourceGroupsByKey.clear();
        sourceVisibleCounts.clear();
        sourceScrollPositions.clear();
        selectedSourceKey = "";
        visibleItems.clear();
        adapter.update(visibleItems);
        sourceAdapter.update(List.of(), "");
        list.setVisibility(View.GONE);
        sourcePanel.setVisibility(View.GONE);
        state.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        message.setText("搜索暂不可用");
        action.requestFocus();
    }

    private void openResult(ContentItem item) {
        if (item == null) return;
        searchCompleted = true;
        clearPendingResultTask();
        cancelActiveSearch();
        loading = false;
        // Start the request before the activity transition. If the user tapped
        // immediately, DetailActivity can join the same in-flight future.
        TvBoxRepository.get().prefetchDetail(item);
        DetailActivity.start(this, item);
    }

    private void selectSource(String key, boolean focusResults) {
        if (key == null || key.isBlank() || key.equals(selectedSourceKey)) {
            if (focusResults) {
                focusResultAt(sourceScrollPositions.getOrDefault(key, 0));
            }
            return;
        }
        saveSelectedScrollPosition();
        selectedSourceKey = key;
        resultFocusPending = false;
        sourceAdapter.select(key);
        displaySelectedSource(focusResults, true);
    }

    private void displaySelectedSource(boolean focusResults, boolean restoreScroll) {
        SearchResultAggregator.SourceGroup source = sourceGroupsByKey.get(selectedSourceKey);
        if (source == null) {
            visibleItems.clear();
            adapter.update(visibleItems);
            return;
        }

        int visibleCount = SearchPaging.visibleCount(source.size(),
                sourceVisibleCounts.getOrDefault(selectedSourceKey, RESULTS_PAGE_SIZE),
                RESULTS_PAGE_SIZE);
        sourceVisibleCounts.put(selectedSourceKey, visibleCount);

        visibleItems.clear();
        visibleItems.addAll(source.items.subList(0, visibleCount));
        adapter.update(visibleItems);
        scheduleVisibleViewportFill();

        int scrollPosition = restoreScroll
                ? sourceScrollPositions.getOrDefault(selectedSourceKey, 0) : 0;
        if (restoreScroll) list.scrollToPosition(scrollPosition);
        if (focusResults) focusResultAt(scrollPosition);
    }

    private void scheduleNextPageIfNeeded() {
        if (pageLoadScheduled || isFinishing() || isDestroyed()
                || list.getVisibility() != View.VISIBLE) {
            return;
        }
        pageLoadScheduled = true;
        list.post(() -> {
            pageLoadScheduled = false;
            if (isFinishing() || isDestroyed()
                    || list.getVisibility() != View.VISIBLE) {
                return;
            }
            if (list.isComputingLayout()) {
                scheduleNextPageIfNeeded();
                return;
            }
            loadNextPageIfNeeded();
        });
    }

    private void loadNextPageIfNeeded() {
        SearchResultAggregator.SourceGroup source = sourceGroupsByKey.get(selectedSourceKey);
        if (source == null || adapter.getItemCount() >= source.size()) return;
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager linear)) return;
        int lastVisible = linear.findLastVisibleItemPosition();
        if (lastVisible < adapter.getItemCount() - resultSpanCount * 2) return;
        int current = sourceVisibleCounts.getOrDefault(
                selectedSourceKey, RESULTS_PAGE_SIZE);
        sourceVisibleCounts.put(selectedSourceKey,
                SearchPaging.visibleCount(source.size(),
                        current + RESULTS_PAGE_SIZE, RESULTS_PAGE_SIZE));
        displaySelectedSource(false, false);
    }

    private void scheduleVisibleViewportFill() {
        if (isFinishing() || isDestroyed()) return;
        removeViewportFillListener();
        list.post(() -> {
            if (isFinishing() || isDestroyed() || list.getVisibility() != View.VISIBLE) {
                return;
            }
            ViewTreeObserver observer = list.getViewTreeObserver();
            if (!observer.isAlive()) {
                fillVisibleViewport();
                return;
            }
            viewportFillListener = new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    removeViewportFillListener();
                    fillVisibleViewport();
                    return true;
                }
            };
            observer.addOnPreDrawListener(viewportFillListener);
            list.requestLayout();
        });
    }

    private void removeViewportFillListener() {
        if (viewportFillListener == null) return;
        ViewTreeObserver observer = list.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(viewportFillListener);
        viewportFillListener = null;
    }

    private void fillVisibleViewport() {
        if (isFinishing() || isDestroyed() || list.getVisibility() != View.VISIBLE) {
            return;
        }
        if (list.getChildCount() > 0 && !list.canScrollVertically(1)) {
            scheduleNextPageIfNeeded();
        }
    }

    private void saveSelectedScrollPosition() {
        if (selectedSourceKey == null || selectedSourceKey.isBlank()) return;
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager linear)) return;
        int position = linear.findFirstVisibleItemPosition();
        if (position >= 0) sourceScrollPositions.put(selectedSourceKey, position);
    }

    private void focusResultAt(int position) {
        if (adapter == null || adapter.getItemCount() == 0) {
            action.requestFocus();
            return;
        }
        int target = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        list.scrollToPosition(target);
        list.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            RecyclerView.ViewHolder holder =
                    list.findViewHolderForAdapterPosition(target);
            if (holder != null) {
                holder.itemView.requestFocus();
                return;
            }
            list.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                RecyclerView.ViewHolder delayed =
                        list.findViewHolderForAdapterPosition(target);
                if (delayed != null) delayed.itemView.requestFocus();
                else focusFirstResult();
            }, 80L);
        });
    }

    private void submitComputation(Runnable task) {
        if (task == null || isFinishing() || isDestroyed()
                || computationExecutor.isShutdown()) {
            return;
        }
        try {
            computationExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void scheduleLocalSuggestions(String value) {
        debounceHandler.removeCallbacks(localSuggestionTask);
        localSuggestionGeneration++;
        if (SearchTextMatcher.normalize(value).isEmpty()) {
            hideSuggestions();
            return;
        }
        debounceHandler.postDelayed(localSuggestionTask, LOCAL_SUGGESTION_DELAY_MS);
    }

    private void buildLocalSuggestions() {
        if (isFinishing() || isDestroyed()) return;
        String query = input.getText().toString().trim();
        if (query.isEmpty()) return;
        int request = localSuggestionGeneration;
        List<LocalItem> pool = localSuggestionPool();
        Set<String> favorites = favoriteKeys;
        Set<String> history = historyKeys;
        submitComputation(() -> {
            List<SearchSuggestion> suggestions =
                    rankLocalSuggestions(pool, query, favorites, history);
            runOnUiThread(() -> {
                if (request != localSuggestionGeneration
                        || (suggestionList.getVisibility() == View.VISIBLE
                        && remoteSuggestionQuery.length() > 0)
                        || isFinishing() || isDestroyed()) {
                    return;
                }
                showSuggestions(suggestions);
            });
        });
    }

    private List<LocalItem> localSuggestionPool() {
        Map<String, LocalItem> unique = new LinkedHashMap<>();
        for (ContentItem item : TvBoxRepository.get().homeItems()) {
            addLocalItem(unique, new LocalItem(item, false, false));
        }
        UserDataStore store = UserDataStore.get(this);
        for (UserDataStore.SavedItem saved : store.favorites()) {
            ContentItem item = store.resolve(saved);
            if (item != null) addLocalItem(unique, new LocalItem(item, true, false));
        }
        for (UserDataStore.HistoryEntry entry : store.history()) {
            ContentItem item = store.resolve(entry);
            if (item != null) addLocalItem(unique, new LocalItem(item, false, true));
        }
        return new ArrayList<>(unique.values());
    }

    private static void addLocalItem(Map<String, LocalItem> unique, LocalItem local) {
        if (local == null || local.item == null
                || local.item.title == null || local.item.title.isBlank()) {
            return;
        }
        String key = SearchRanker.key(local.item);
        LocalItem current = unique.get(key);
        if (current == null) {
            unique.put(key, local);
        } else {
            unique.put(key, new LocalItem(current.item,
                    current.favorite || local.favorite,
                    current.history || local.history));
        }
    }

    private static List<SearchSuggestion> rankLocalSuggestions(
            List<LocalItem> pool, String query, Set<String> favoriteKeys,
            Set<String> historyKeys) {
        List<RankedLocal> matches = new ArrayList<>();
        for (LocalItem local : pool) {
            int match = SearchTextMatcher.matchScore(local.item.title, query);
            if (match <= 0) continue;
            boolean favorite = local.favorite
                    || favoriteKeys.contains(SearchRanker.key(local.item));
            boolean history = local.history
                    || historyKeys.contains(SearchRanker.key(local.item));
            long score = SearchRanker.score(local.item, query, 1, favorite, history);
            matches.add(new RankedLocal(local.item, score, favorite, history));
        }
        matches.sort((left, right) -> {
            int scoreOrder = Long.compare(right.score, left.score);
            if (scoreOrder != 0) return scoreOrder;
            return safe(left.item.title).compareTo(safe(right.item.title));
        });

        Set<String> titles = new HashSet<>();
        List<SearchSuggestion> result = new ArrayList<>();
        for (RankedLocal match : matches) {
            String normalTitle = SearchTextMatcher.normalize(match.item.title);
            if (!titles.add(normalTitle)) continue;
            String source = match.item.site == null
                    ? "TVBox" : match.item.site.displayName();
            List<String> tags = new ArrayList<>();
            if (match.history) tags.add("最近播放");
            else if (match.favorite) tags.add("收藏");
            if (match.item.meta != null && !match.item.meta.isBlank()) {
                tags.add(match.item.meta);
            }
            String detail = source + (tags.isEmpty() ? "" : " · " + String.join(" · ", tags));
            result.add(new SearchSuggestion(match.item.title, detail, match.item));
            if (result.size() >= MAX_LOCAL_SUGGESTIONS) break;
        }
        return result;
    }

    private void requestRemoteSuggestions(String keyword) {
        if (keyword == null || !SearchTextMatcher.isPinyinLike(keyword)
                || SearchTextMatcher.normalize(keyword).length() < 2) {
            return;
        }
        remoteSuggestionQuery = keyword;
        remoteSuggestionPending = true;
        int request = ++remoteSuggestionGeneration;
        SearchSuggestionClient.suggest(keyword, (suggestions, error) -> runOnUiThread(() -> {
            if (request != remoteSuggestionGeneration
                    || !keyword.equals(remoteSuggestionQuery)
                    || isFinishing() || isDestroyed()) {
                return;
            }
            remoteSuggestionPending = false;
            List<SearchSuggestion> remote = new ArrayList<>();
            if (error == null && suggestions != null) {
                Set<String> seen = new HashSet<>();
                for (String title : suggestions) {
                    if (title == null || title.isBlank()) continue;
                    String normalized = SearchTextMatcher.normalize(title);
                    if (normalized.isEmpty() || !seen.add(normalized)) continue;
                    remote.add(new SearchSuggestion(title, "联想片名", null));
                    if (remote.size() >= MAX_REMOTE_SUGGESTIONS) break;
                }
            }
            showSuggestions(remote);
            if (remote.isEmpty()) focusFirstResultIfReady();
        }));
    }

    private void showSuggestions(List<SearchSuggestion> suggestions) {
        List<SearchSuggestion> safe = suggestions == null ? List.of() : suggestions;
        suggestionAdapter.update(safe);
        suggestionList.setVisibility(safe.isEmpty() ? View.GONE : View.VISIBLE);
        if (safe.isEmpty()) return;
        count.post(() -> {
            if (suggestionList.getVisibility() == View.VISIBLE
                    && !input.hasFocus()
                    && suggestionList.findFocus() == null
                    && sourceList.findFocus() == null
                    && list.findFocus() == null) {
                input.requestFocus();
            }
        });
    }

    private boolean focusSuggestion(int position) {
        if (suggestionList.getVisibility() != View.VISIBLE
                || suggestionAdapter.getItemCount() == 0) {
            return false;
        }
        int target = Math.max(0, Math.min(position,
                suggestionAdapter.getItemCount() - 1));
        suggestionList.scrollToPosition(target);
        suggestionList.post(() -> {
            RecyclerView.ViewHolder holder =
                    suggestionList.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
        return true;
    }

    private boolean dismissSuggestions(boolean returnFocus) {
        if (suggestionList.getVisibility() != View.VISIBLE) return false;
        hideSuggestions();
        if (returnFocus) input.requestFocus();
        return true;
    }

    private void hideSuggestions() {
        suggestionAdapter.update(List.of());
        suggestionList.setVisibility(View.GONE);
    }

    private void invalidateRemoteSuggestions() {
        remoteSuggestionGeneration++;
        remoteSuggestionQuery = "";
        remoteSuggestionPending = false;
    }

    private void captureUserSignals() {
        UserDataStore store = UserDataStore.get(this);
        Set<String> favorites = new HashSet<>();
        for (UserDataStore.SavedItem item : store.favorites()) {
            favorites.add(SearchRanker.key(item.siteKey, item.id));
        }
        Set<String> history = new HashSet<>();
        for (UserDataStore.HistoryEntry item : store.history()) {
            history.add(SearchRanker.key(item.siteKey, item.itemId));
        }
        favoriteKeys = favorites;
        historyKeys = history;
    }

    private void focusFirstResult() {
        focusFirstResult(false);
    }

    private void focusFirstResult(boolean requireIdleFocus) {
        list.post(() -> {
            if (isFinishing() || isDestroyed()
                    || (requireIdleFocus && !shouldAutoFocusResults())) {
                return;
            }
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else action.requestFocus();
        });
    }

    private void focusFirstResultIfReady() {
        if (loading || visibleItems.isEmpty()
                || suggestionList.getVisibility() == View.VISIBLE
                || !shouldAutoFocusResults()) {
            return;
        }
        focusFirstResult(true);
    }

    private boolean shouldAutoFocusResults() {
        View focused = getCurrentFocus();
        return hasWindowFocus() && (focused == null || focused == action);
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return;
        InputMethodManager manager =
                (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
    }

    private void showKeyboard() {
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager manager =
                    (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 80L);
    }

    private boolean handleResultUpNavigation(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0
                || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP
                || suggestionList.getVisibility() != View.VISIBLE) {
            return false;
        }
        View focused = getCurrentFocus();
        if (!isDescendantOf(focused, list) || !isOnFirstResultRow(focused)) return false;
        return focusSuggestion(0);
    }

    private boolean isOnFirstResultRow(View focused) {
        if (focused == null) return false;
        View child = focused;
        while (child.getParent() instanceof View parent && parent != list) {
            child = parent;
        }
        if (child.getParent() != list) return false;
        int position = list.getChildAdapterPosition(child);
        if (position < 0) return false;
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        int spanCount = manager instanceof GridLayoutManager grid
                ? grid.getSpanCount() : 1;
        return position < spanCount;
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        if (child == null || ancestor == null) return false;
        if (child == ancestor) return true;
        View current = child;
        while (current.getParent() instanceof View parent) {
            if (parent == ancestor) return true;
            current = parent;
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean handleSourceRightNavigation(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0
                || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_RIGHT
                || !isDescendantOf(getCurrentFocus(), sourceList)) {
            return false;
        }
        focusResultAt(sourceScrollPositions.getOrDefault(selectedSourceKey, 0));
        return true;
    }

    private boolean handleResultLeftNavigation(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0
                || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_LEFT) {
            return false;
        }
        View focused = getCurrentFocus();
        if (!isDescendantOf(focused, list)) return false;
        RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (!(manager instanceof GridLayoutManager)) return false;

        View child = focused;
        while (child.getParent() instanceof View parent && parent != list) {
            child = parent;
        }
        if (child.getParent() != list) return false;
        ViewGroup.LayoutParams params = child.getLayoutParams();
        if (!(params instanceof GridLayoutManager.LayoutParams gridParams)
                || gridParams.getSpanIndex() != 0) {
            return false;
        }
        focusSelectedSource();
        return true;
    }

    private void focusSelectedSource() {
        int position = sourceAdapter.positionOf(selectedSourceKey);
        if (position < 0) position = 0;
        if (sourceAdapter.getItemCount() == 0) return;
        int target = position;
        sourceList.scrollToPosition(target);
        sourceList.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            RecyclerView.ViewHolder holder =
                    sourceList.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleResultUpNavigation(event)) return true;
        if (handleSourceRightNavigation(event)) return true;
        if (handleResultLeftNavigation(event)) return true;
        if (TvFocus.returnToTopOnUp(event, list, input)) return true;
        if (TvFocus.returnToTopOnUp(event, sourceList, input)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        generation++;
        renderGeneration++;
        searchCompleted = true;
        clearPendingResultTask();
        localSuggestionGeneration++;
        remoteSuggestionGeneration++;
        cancelActiveSearch();
        loading = false;
        pageLoadScheduled = false;
        removeViewportFillListener();
        debounceHandler.removeCallbacksAndMessages(null);
        computationExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
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

    private static final class LocalItem {
        final ContentItem item;
        final boolean favorite;
        final boolean history;

        LocalItem(ContentItem item, boolean favorite, boolean history) {
            this.item = item;
            this.favorite = favorite;
            this.history = history;
        }
    }

    private static final class RankedLocal {
        final ContentItem item;
        final long score;
        final boolean favorite;
        final boolean history;

        RankedLocal(ContentItem item, long score, boolean favorite, boolean history) {
            this.item = item;
            this.score = score;
            this.favorite = favorite;
            this.history = history;
        }
    }
}
