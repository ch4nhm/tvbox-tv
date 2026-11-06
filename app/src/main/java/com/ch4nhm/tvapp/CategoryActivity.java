package com.ch4nhm.tvapp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CategoryActivity extends BaseActivity {

    private static final String EXTRA_TID = "tid";
    private static final String EXTRA_NAME = "name";
    private static final int MAX_PAGES = 30;

    private final List<ContentItem> items = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private final Map<String, String> selectedFilters = new LinkedHashMap<>();
    private String tid;
    private String categoryName;
    private PosterAdapter adapter;
    private RecyclerView list;
    private GridLayoutManager layoutManager;
    private LinearLayout state;
    private ProgressBar progress;
    private TextView message;
    private TextView retry;
    private TextView count;
    private HorizontalScrollView filterScroll;
    private LinearLayout filterBar;
    private List<TvBoxRepository.FilterGroup> filterGroups = List.of();
    private boolean loading;
    private boolean hasMore = true;
    private boolean filterRequested;
    private String pendingFilterFocus;
    private int nextPage = 1;
    private int requestGeneration;
    private TvLayoutMetrics layoutMetrics;

    public static void start(Context context, String tid, String name) {
        Intent intent = new Intent(context, CategoryActivity.class);
        intent.putExtra(EXTRA_TID, tid);
        intent.putExtra(EXTRA_NAME, name);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_category);
        enterImmersive();

        tid = getIntent().getStringExtra(EXTRA_TID);
        categoryName = getIntent().getStringExtra(EXTRA_NAME);
        if (tid == null || tid.isBlank()) {
            finish();
            return;
        }
        if (categoryName == null || categoryName.isBlank()) categoryName = "分类";

        TextView title = findViewById(R.id.category_title);
        count = findViewById(R.id.category_count);
        list = findViewById(R.id.category_list);
        state = findViewById(R.id.category_state);
        progress = findViewById(R.id.category_progress);
        message = findViewById(R.id.category_message);
        retry = findViewById(R.id.category_retry);
        filterScroll = findViewById(R.id.category_filter_scroll);
        filterBar = findViewById(R.id.category_filter_bar);
        layoutMetrics = TvLayout.metrics(this);
        applyResponsiveChrome(title);

        title.setText(categoryName);
        count.setText("0 部");
        FocusAnimator.scale(retry, 1.04f);
        retry.setOnClickListener(view -> {
            reloadFirstPage();
        });

        int spanCount = TvDisplayAdapter.posterGridSpanCount(this);
        layoutManager = new GridLayoutManager(this, spanCount);
        list.setLayoutManager(layoutManager);
        GridSpacingDecoration.addPx(list, TvDisplayAdapter.posterGapPx(this));
        list.setItemAnimator(null);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0 || loading || !hasMore) return;
                int last = layoutManager.findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 8) loadPage();
            }
        });
        adapter = new PosterAdapter(items, item -> DetailActivity.start(this, item));
        list.setAdapter(adapter);
        renderFilters(TvBoxRepository.get().filtersFor(tid));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        if (filterBar.getChildCount() > 0) filterBar.getChildAt(0).requestFocus();
        loadPage();
    }

    private void loadPage() {
        if (loading || !hasMore) return;
        loading = true;
        final int generation = requestGeneration;
        final int page = nextPage;
        final boolean useFilters = filterRequested;
        final Map<String, String> extend = useFilters ? currentFilterValues() : null;
        if (page == 1) showState(true, "正在加载分类内容…");
        else message.setText("正在加载第 " + page + " 页…");
        TvBoxRepository.get().category(tid, String.valueOf(page), useFilters, extend,
                new TvBoxRepository.CategoryCallback() {
                    @Override
                    public void onCategory(TvBoxRepository.CategoryPage result) {
                        runOnUiThread(() -> {
                            if (generation != requestGeneration
                                    || isFinishing() || isDestroyed()) return;
                            if (!result.filters.isEmpty()) renderFilters(result.filters);
                            append(page, result.items);
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        runOnUiThread(() -> {
                            if (generation != requestGeneration) return;
                            fail(page, error);
                        });
                    }
                });
    }

    private void reloadFirstPage() {
        requestGeneration++;
        loading = false;
        hasMore = true;
        nextPage = 1;
        items.clear();
        identities.clear();
        adapter.update(items);
        count.setText("0 部");
        showState(true, "正在加载分类内容…");
        loadPage();
    }

    private void renderFilters(List<TvBoxRepository.FilterGroup> groups) {
        if (groups == null) groups = List.of();
        if (groups.isEmpty()) {
            filterGroups = List.of();
            selectedFilters.clear();
            filterBar.removeAllViews();
            filterScroll.setVisibility(View.GONE);
            return;
        }

        filterGroups = new ArrayList<>(groups);
        Set<String> validKeys = new HashSet<>();
        for (TvBoxRepository.FilterGroup group : filterGroups) validKeys.add(group.key);
        selectedFilters.keySet().removeIf(key -> !validKeys.contains(key));

        filterBar.removeAllViews();
        for (TvBoxRepository.FilterGroup group : filterGroups) {
            filterBar.addView(createFilterChip(group));
        }
        if (filterRequested || !selectedFilters.isEmpty()) {
            filterBar.addView(createResetChip());
        }
        filterScroll.setVisibility(View.VISIBLE);
    }

    private TextView createFilterChip(TvBoxRepository.FilterGroup group) {
        TextView chip = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, chipHeightPx());
        params.setMarginEnd(dp(12));
        chip.setLayoutParams(params);
        String value = currentFilterValue(group);
        String label = optionLabel(group, value);
        boolean selected = selectedFilters.containsKey(group.key);
        chip.setText(selected ? group.name + " · " + label : group.name);
        chip.setTextColor(getColorStateList(R.color.filter_chip_text));
        chip.setTextSize(17);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(dp(22), 0, dp(22), 0);
        chip.setBackgroundResource(R.drawable.bg_filter_chip);
        chip.setTag(group.key);
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setSelected(selected);
        chip.setOnClickListener(view -> showFilterDialog(group));
        FocusAnimator.scale(chip, 1.035f, () -> chip.post(() ->
                chip.requestRectangleOnScreen(
                        new Rect(0, 0, chip.getWidth(), chip.getHeight()), true)));
        return chip;
    }

    private TextView createResetChip() {
        TextView reset = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, chipHeightPx());
        params.setMarginEnd(dp(12));
        reset.setLayoutParams(params);
        reset.setText("清除筛选");
        reset.setTextColor(getColorStateList(R.color.filter_chip_text));
        reset.setTextSize(17);
        reset.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        reset.setGravity(android.view.Gravity.CENTER);
        reset.setPadding(dp(22), 0, dp(22), 0);
        reset.setBackgroundResource(R.drawable.bg_filter_chip);
        reset.setFocusable(true);
        reset.setFocusableInTouchMode(true);
        reset.setOnClickListener(view -> {
            selectedFilters.clear();
            filterRequested = false;
            renderFilters(filterGroups);
            reloadFirstPage();
        });
        FocusAnimator.scale(reset, 1.035f, () -> reset.post(() ->
                reset.requestRectangleOnScreen(
                        new Rect(0, 0, reset.getWidth(), reset.getHeight()), true)));
        return reset;
    }

    private void showFilterDialog(TvBoxRepository.FilterGroup group) {
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_category_filter, null, false);
        TextView title = content.findViewById(R.id.filter_dialog_title);
        LinearLayout options = content.findViewById(R.id.filter_dialog_options);
        ScrollView scroll = content.findViewById(R.id.filter_dialog_scroll);
        title.setText(group.name);
        String current = currentFilterValue(group);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setNegativeButton("取消", null)
                .create();
        TextView selectedView = null;
        for (TvBoxRepository.FilterOption option : group.options) {
            TextView item = createFilterOption(option, option.value.equals(current));
            options.addView(item);
            if (item.isSelected()) selectedView = item;
            item.setOnClickListener(view -> {
                selectedFilters.put(group.key, option.value);
                filterRequested = true;
                pendingFilterFocus = group.key;
                dialog.dismiss();
                renderFilters(filterGroups);
                reloadFirstPage();
            });
        }
        int maxOptionsHeight = dp(Math.min(300f, layoutMetrics.dialogMaxHeightDp * 0.55f));
        int rowHeight = Math.max(dp(44), dp(layoutMetrics.buttonHeightDp + 4f));
        int optionsHeight = dp(8) + group.options.size() * rowHeight;
        ViewGroup.LayoutParams scrollParams = scroll.getLayoutParams();
        scrollParams.height = Math.min(maxOptionsHeight, optionsHeight);
        scroll.setLayoutParams(scrollParams);
        TextView initialFocus = selectedView;
        dialog.setOnShowListener(ignored -> {
            DialogStyler.style(dialog);
            if (initialFocus != null) initialFocus.requestFocus();
            else options.getChildAt(0).requestFocus();
        });
        dialog.show();
    }

    private TextView createFilterOption(TvBoxRepository.FilterOption option, boolean selected) {
        TextView item = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(dp(44), dp(layoutMetrics.buttonHeightDp + 4f)));
        params.bottomMargin = dp(4);
        item.setLayoutParams(params);
        item.setText(option.label);
        item.setTextColor(getColorStateList(R.color.filter_option_text));
        item.setTextSize(17);
        item.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        item.setGravity(android.view.Gravity.CENTER_VERTICAL);
        item.setPadding(dp(22), 0, dp(22), 0);
        item.setBackgroundResource(R.drawable.bg_filter_option);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setSelected(selected);
        item.setOnFocusChangeListener((view, focused) -> {
            if (focused) view.post(() -> view.requestRectangleOnScreen(
                    new Rect(0, 0, view.getWidth(), view.getHeight()), true));
        });
        return item;
    }

    private Map<String, String> currentFilterValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (TvBoxRepository.FilterGroup group : filterGroups) {
            values.put(group.key, currentFilterValue(group));
        }
        return values;
    }

    private String currentFilterValue(TvBoxRepository.FilterGroup group) {
        String selected = selectedFilters.get(group.key);
        if (selected != null) return selected;
        if (group.init != null && !group.init.isBlank()) return group.init;
        return group.options.isEmpty() ? "" : group.options.get(0).value;
    }

    private static String optionLabel(TvBoxRepository.FilterGroup group, String value) {
        for (TvBoxRepository.FilterOption option : group.options) {
            if (option.value.equals(value)) return option.label;
        }
        return value == null || value.isBlank() ? "全部" : value;
    }

    private void append(int page, List<ContentItem> result) {
        if (isFinishing() || isDestroyed()) return;
        loading = false;
        int before = items.size();
        for (ContentItem item : result) {
            String identity = (item.site == null ? "" : item.site.key) + "\u0000" + item.id;
            if (identities.add(identity)) items.add(item);
        }
        adapter.update(items);
        count.setText(String.format(Locale.getDefault(), "%d 部", items.size()));
        nextPage = page + 1;
        hasMore = page < MAX_PAGES && !result.isEmpty() && items.size() > before;
        showState(items.isEmpty(), items.isEmpty()
                ? "当前分类没有返回内容" : "");
        if (page == 1) {
            list.post(() -> {
                if (pendingFilterFocus != null) {
                    View chip = filterBar.findViewWithTag(pendingFilterFocus);
                    pendingFilterFocus = null;
                    if (chip != null) {
                        chip.requestFocus();
                        return;
                    }
                }
                if (items.isEmpty()) {
                    focusFilterFallback();
                    return;
                }
                RecyclerView.ViewHolder holder =
                        list.findViewHolderForAdapterPosition(0);
                if (holder != null) holder.itemView.requestFocus();
                else focusFilterFallback();
            });
        }
    }

    private void focusFilterFallback() {
        if (filterBar.getChildCount() > 0) filterBar.getChildAt(0).requestFocus();
        else if (retry.isShown()) retry.requestFocus();
    }

    private void fail(int page, Throwable error) {
        if (isFinishing() || isDestroyed()) return;
        loading = false;
        if (page == 1) {
            showState(true, "暂时无法加载");
            retry.setVisibility(View.VISIBLE);
            retry.requestFocus();
        } else {
            hasMore = true;
            message.setText("加载中断，继续滚动可重试");
        }
    }

    private void showState(boolean visible, String text) {
        state.setVisibility(visible ? View.VISIBLE : View.GONE);
        list.setVisibility(visible ? View.GONE : View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        message.setText(text);
        retry.setVisibility(View.GONE);
    }

    private int chipHeightPx() {
        return dp(Math.max(36f, layoutMetrics.filterBarHeightDp - 16f));
    }

    private void applyResponsiveChrome(View title) {
        int margin = dp(layoutMetrics.contentMarginDp);
        if (title instanceof TextView titleView) {
            titleView.setTextSize(layoutMetrics.pageTitleTextSizeSp);
        }
        if (title.getParent() instanceof View header) {
            header.setPadding(margin, header.getPaddingTop(),
                    margin, header.getPaddingBottom());
            ViewGroup.LayoutParams params = header.getLayoutParams();
            if (params != null) {
                params.height = dp(layoutMetrics.pageHeaderHeightDp);
                header.setLayoutParams(params);
            }
        }
        filterScroll.getLayoutParams().height = dp(layoutMetrics.filterBarHeightDp);
        filterScroll.requestLayout();
        filterBar.setPadding(margin, filterBar.getPaddingTop(),
                margin, filterBar.getPaddingBottom());
        list.setPadding(margin, dp(14), margin, dp(36));
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
        View topTarget = filterBar.getChildCount() > 0
                ? filterBar.getChildAt(0) : retry.isShown() ? retry : null;
        if (TvFocus.returnToTopOnUp(event, list, topTarget)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }
}
