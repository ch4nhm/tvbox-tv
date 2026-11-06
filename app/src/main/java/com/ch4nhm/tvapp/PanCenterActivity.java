package com.ch4nhm.tvapp;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public final class PanCenterActivity extends BaseActivity {

    private static final String EXTRA_BRAND_ID = "brand_id";

    private final List<PanRepository.Brand> brands = new ArrayList<>();
    private PanRepository repository;
    private BrandAdapter adapter;
    private RecyclerView list;
    private ViewGroup empty;
    private ProgressBar progress;
    private TextView emptyText;
    private TextView refresh;
    private boolean loading;
    private boolean resumedOnce;
    private String requestedBrandId;
    private TvLayoutMetrics layoutMetrics;

    public static void start(Context context) {
        start(context, "");
    }

    public static void start(Context context, String brandId) {
        context.startActivity(new Intent(context, PanCenterActivity.class)
                .putExtra(EXTRA_BRAND_ID, brandId == null ? "" : brandId));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pan_center);
        repository = new PanRepository();
        requestedBrandId = getIntent().getStringExtra(EXTRA_BRAND_ID);
        refresh = findViewById(R.id.pan_refresh);
        FocusAnimator.scale(refresh, 1.04f);
        refresh.setOnClickListener(view -> load());
        empty = findViewById(R.id.pan_empty);
        progress = findViewById(R.id.pan_progress);
        emptyText = findViewById(R.id.pan_empty_text);
        list = findViewById(R.id.pan_list);
        layoutMetrics = TvLayout.metrics(this);
        int spanCount = calculateSpanCount();
        GridLayoutManager layoutManager = new GridLayoutManager(this, spanCount);
        list.setLayoutManager(layoutManager);
        list.setItemAnimator(null);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull android.graphics.Rect outRect,
                                       @NonNull View view,
                                       @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int column = ((GridLayoutManager.LayoutParams) view.getLayoutParams())
                        .getSpanIndex();
                int spanSize = ((GridLayoutManager.LayoutParams) view.getLayoutParams())
                        .getSpanSize();
                outRect.right = column + spanSize <
                        ((GridLayoutManager) parent.getLayoutManager()).getSpanCount()
                        ? dp(layoutMetrics.cardGapDp) : 0;
                outRect.bottom = dp(layoutMetrics.cardGapDp * 0.75f);
            }
        });
        adapter = new BrandAdapter();
        list.setAdapter(adapter);
        applyResponsiveChrome();
        load();
    }

    private int calculateSpanCount() {
        float availableWidthDp = Math.max(360f,
                TvDisplayAdapter.viewport(this).usableWidthDp
                        - layoutMetrics.contentMarginDp * 2f);
        float minimumCardWidthDp = 270f;
        int columns = (int) Math.floor(
                (availableWidthDp + layoutMetrics.cardGapDp)
                        / (minimumCardWidthDp + layoutMetrics.cardGapDp));
        return Math.max(2, Math.min(5, columns));
    }

    private void load() {
        if (loading) return;
        loading = true;
        refresh.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.VISIBLE);
        emptyText.setText("正在读取网盘状态…");
        repository.load(values -> runOnUiThread(() -> {
            brands.clear();
            brands.addAll(values);
            loading = false;
            refresh.setEnabled(true);
            adapter.notifyDataSetChanged();
            empty.setVisibility(values.isEmpty() ? View.VISIBLE : View.GONE);
            if (values.isEmpty()) emptyText.setText("没有可用的网盘");
            else focusRequestedBrand();
        }), ignoredError -> runOnUiThread(() -> {
            loading = false;
            refresh.setEnabled(true);
            progress.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
            emptyText.setText("网盘状态暂时不可用");
            Toast.makeText(this, emptyText.getText(), Toast.LENGTH_LONG).show();
        }));
    }

    private void focusRequestedBrand() {
        int requestedPosition = 0;
        if (requestedBrandId != null && !requestedBrandId.isBlank()) {
            for (int index = 0; index < brands.size(); index++) {
                if (requestedBrandId.equals(brands.get(index).id)) {
                    requestedPosition = index;
                    break;
                }
            }
        }
        final int targetPosition = requestedPosition;
        list.scrollToPosition(targetPosition);
        list.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            RecyclerView.ViewHolder holder =
                    list.findViewHolderForAdapterPosition(targetPosition);
            if (holder == null) holder = list.findViewHolderForAdapterPosition(0);
            if (holder == null) {
                requestInitialFocus(refresh);
                return;
            }
            requestBrandFocus(holder.itemView, 0);
        }, 160L);
    }

    private void requestBrandFocus(View target, int attempt) {
        if (isFinishing() || isDestroyed() || attempt > 5) return;
        if (!target.isAttachedToWindow() || !target.isShown()) {
            list.postDelayed(() -> requestBrandFocus(target, attempt + 1), 100L);
            return;
        }
        View current = getCurrentFocus();
        if (current != target) {
            if (current != null) current.clearFocus();
            target.requestFocus();
        }
        if (!target.hasFocus()) {
            list.postDelayed(() -> requestBrandFocus(target, attempt + 1), 100L);
        }
    }

    private void open(PanRepository.Brand brand) {
        String provider = repository.scanProvider(brand);
        if (provider != null) {
            if (brand.loggedIn) showNativeAccountActions(brand);
            else showScanLogin(brand, provider);
            return;
        }
        repository.login(brand, ignored -> runOnUiThread(() -> choose(brand)),
                error -> runOnUiThread(() -> showLoginError(brand, error)));
    }

    private void showNativeAccountActions(PanRepository.Brand brand) {
        DialogStyler.showOptions(this, brand.name + " · 本机账号",
                new String[]{"重新扫码登录", "退出登录"}, -1, which -> {
                    if (which == 0) {
                        showScanLogin(brand, repository.scanProvider(brand));
                    } else if (which == 1) {
                        confirmLogout(brand);
                    }
                });
    }

    private void confirmLogout(PanRepository.Brand brand) {
        DialogStyler.showConfirmation(this, "退出" + brand.name,
                "只清除本机保存的网盘账号，不影响其他片源和设置。",
                "取消", "退出登录", () -> {
                    PanCredentialStore.clearCredential(brand.id);
                    brand.loggedIn = false;
                    brand.status = "未登录";
                    brand.remark = "";
                    if (adapter != null) adapter.notifyDataSetChanged();
                    Toast.makeText(this, brand.name + "已退出登录",
                            Toast.LENGTH_SHORT).show();
                    load();
                });
    }

    private void choose(PanRepository.Brand brand) {
        if (brand.actions.isEmpty()) {
            perform(brand, new PanRepository.LoginAction(brand.name, brand.id, ""));
            return;
        }
        String[] labels = brand.actions.stream()
                .map(action -> action.title)
                .toArray(String[]::new);
        DialogStyler.showOptions(this, brand.name, labels, -1,
                which -> perform(brand, brand.actions.get(which)));
    }

    private void perform(PanRepository.Brand brand, PanRepository.LoginAction action) {
        String provider = repository.scanProvider(brand);
        if (provider != null && action.title.contains("扫码")) {
            showScanLogin(brand, provider);
            return;
        }
        repository.perform(brand, action, value -> runOnUiThread(() -> {
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                Toast.makeText(this, value, Toast.LENGTH_LONG).show();
            }
            load();
        }), error -> runOnUiThread(() -> showLoginError(brand, error)));
    }

    private void showScanLogin(PanRepository.Brand brand, String provider) {
        PanLoginDialog.show(this, repository, brand, provider, false,
                new PanLoginDialog.Listener() {
            @Override
            public void onLoggedIn() {
                brand.loggedIn = true;
                brand.status = "已登录";
                if (adapter != null) adapter.notifyDataSetChanged();
                Toast.makeText(PanCenterActivity.this,
                        brand.name + "登录成功", Toast.LENGTH_SHORT).show();
                if (list != null) list.postDelayed(PanCenterActivity.this::load, 450L);
            }

            @Override
            public void onOtherMethods() {
                repository.login(brand, ignored -> runOnUiThread(() ->
                                choose(brand)),
                        error -> runOnUiThread(() ->
                                showLoginError(brand, error)));
            }
        });
    }

    private void showLoginError(PanRepository.Brand brand, Throwable error) {
        String detail = rootMessage(error);
        String message = detail.contains("没有登录入口")
                ? brand.name + "：当前片源未提供登录入口"
                : "暂时无法打开" + brand.name + "登录：" + detail;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void applyResponsiveChrome() {
        View root = findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup content) || content.getChildCount() == 0) return;
        View activityRoot = content.getChildAt(0);
        View first = activityRoot instanceof ViewGroup rootGroup && rootGroup.getChildCount() > 0
                ? rootGroup.getChildAt(0) : null;
        if (!(first instanceof ViewGroup column)) return;
        int margin = dp(layoutMetrics.contentMarginDp);
        column.setPadding(margin, column.getPaddingTop(), margin, dp(24));
        if (column.getChildCount() > 0
                && column.getChildAt(0) instanceof ViewGroup header) {
            ViewGroup.LayoutParams params = header.getLayoutParams();
            if (params != null) {
                params.height = dp(layoutMetrics.pageHeaderHeightDp);
                header.setLayoutParams(params);
            }
        }
        TvLayout.applyButtonHeight(refresh, true);
        list.setPadding(0, 0, 0, dp(20));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        if (adapter != null) load();
    }

    private final class BrandAdapter extends RecyclerView.Adapter<BrandHolder> {

        @NonNull
        @Override
        public BrandHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new BrandHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pan_brand, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull BrandHolder holder, int position) {
            PanRepository.Brand brand = brands.get(position);
            holder.name.setText(brand.name);
            String label = brand.name == null || brand.name.isBlank()
                    ? brand.label : brand.name.trim();
            holder.icon.setText(label.substring(0, Math.min(1, label.length())));
            holder.action.setText(brand.loggedIn ? "管理" : "登录");
            holder.itemView.setOnClickListener(view -> open(brand));
            FocusAnimator.card(holder.itemView, holder.card, 1.015f);
        }

        @Override
        public int getItemCount() {
            return brands.size();
        }
    }

    private final class BrandHolder extends RecyclerView.ViewHolder {
        final com.google.android.material.card.MaterialCardView card;
        final TextView icon;
        final TextView name;
        final TextView action;

        BrandHolder(View view) {
            super(view);
            card = view.findViewById(R.id.pan_card);
            icon = view.findViewById(R.id.pan_icon);
            name = view.findViewById(R.id.pan_name);
            action = view.findViewById(R.id.pan_action);
            ViewGroup.LayoutParams cardParams = card.getLayoutParams();
            if (cardParams != null) {
                cardParams.height = dp(Math.max(84f,
                        Math.min(104f, layoutMetrics.buttonHeightDp * 1.55f)));
                card.setLayoutParams(cardParams);
            }
            View iconFrame = view.findViewById(R.id.pan_icon_frame);
            if (iconFrame != null) {
                int iconSize = dp(Math.max(52f, Math.min(68f,
                        layoutMetrics.buttonHeightDp * 1.05f)));
                ViewGroup.LayoutParams iconParams = iconFrame.getLayoutParams();
                if (iconParams != null) {
                    iconParams.width = iconSize;
                    iconParams.height = iconSize;
                    iconFrame.setLayoutParams(iconParams);
                }
            }
            icon.setTextSize(Math.max(19f,
                    Math.min(23f, layoutMetrics.posterTitleTextSizeSp + 4f)));
            name.setTextSize(Math.max(17f,
                    Math.min(20f, layoutMetrics.posterTitleTextSizeSp + 1.5f)));
            action.setTextSize(Math.max(15f,
                    Math.min(17f, layoutMetrics.posterTitleTextSizeSp - 0.5f)));
            ViewGroup.LayoutParams actionParams = action.getLayoutParams();
            if (actionParams != null) {
                actionParams.height = dp(Math.max(40f,
                        Math.min(46f, layoutMetrics.buttonHeightDp * 0.82f)));
                action.setLayoutParams(actionParams);
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? "" : current.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

}
