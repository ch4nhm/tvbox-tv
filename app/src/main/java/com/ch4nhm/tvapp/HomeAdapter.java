package com.ch4nhm.tvapp;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_STATE = 0;
    private static final int TYPE_CONTINUE_HEADER = 1;
    private static final int TYPE_CONTINUE_ROW = 2;
    private static final int TYPE_FEATURED_HEADER = 3;
    private static final int TYPE_POSTER = 4;

    private final Context context;
    private final EmptyAction emptyAction;
    private final List<Integer> rowTypes = new ArrayList<>();
    private List<ContentItem> featuredItems = new ArrayList<>();
    private List<UserDataStore.HistoryEntry> continueItems = new ArrayList<>();
    private ContinueAdapter continueAdapter;
    private int featuredStart = -1;
    private String emptyTitle = "正在连接片源";
    private String emptyDescription = "首次加载可能需要几秒，正在准备海报、分类与播放线路。";
    private String emptyPrimary = "正在连接";
    private String emptySecondary = "TVBox 设置";
    private boolean emptyRetryEnabled;

    public interface EmptyAction {
        void retry();

        void settings();
    }

    public HomeAdapter(Context context, EmptyAction emptyAction) {
        this.context = context;
        this.emptyAction = emptyAction;
        rebuildRows();
    }

    public void update(List<ContentItem> featured,
                       List<UserDataStore.HistoryEntry> continueItems) {
        featuredItems = featured == null
                ? new ArrayList<>() : new ArrayList<>(featured);
        this.continueItems = continueItems == null
                ? new ArrayList<>() : new ArrayList<>(continueItems);
        rebuildRows();
        notifyDataSetChanged();
        updateContinueAdapter();
    }

    public void updateContinue(List<UserDataStore.HistoryEntry> items) {
        List<UserDataStore.HistoryEntry> next = items == null
                ? new ArrayList<>() : new ArrayList<>(items);
        if (sameContinueItems(continueItems, next)) return;
        continueItems = next;
        rebuildRows();
        notifyDataSetChanged();
        updateContinueAdapter();
    }

    private void updateContinueAdapter() {
        if (continueAdapter != null) continueAdapter.update(continueItems);
    }

    public void showEmptyHome(boolean hasCategories) {
        emptyTitle = "首页暂无推荐";
        emptyDescription = hasCategories
                ? "当前片源没有返回首页推荐，你仍可通过顶部分类继续浏览，或重新加载。"
                : "当前片源没有返回可浏览内容，请重新加载或更换 TVBox 配置。";
        emptyPrimary = "重新加载";
        emptySecondary = "打开设置";
        emptyRetryEnabled = true;
        update(new ArrayList<>(), continueItems);
    }

    public void showLoading() {
        emptyTitle = "正在连接片源";
        emptyDescription = "首次加载可能需要几秒，正在准备海报、分类与播放线路。";
        emptyPrimary = "正在连接";
        emptySecondary = "TVBox 设置";
        emptyRetryEnabled = false;
        notifyStateChanged();
    }

    public void showError(String detail) {
        emptyTitle = "片源暂不可用";
        emptyDescription = detail == null || detail.isBlank()
                ? "请检查网络连接，或进入设置更换 TVBox 配置。"
                : detail;
        emptyPrimary = "重新加载";
        emptySecondary = "打开设置";
        emptyRetryEnabled = true;
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        int index = rowTypes.indexOf(TYPE_STATE);
        if (index >= 0) notifyItemChanged(index);
    }

    boolean isFullSpan(int viewType) {
        return viewType != TYPE_POSTER;
    }

    @Override
    public int getItemViewType(int position) {
        return rowTypes.get(position);
    }

    @Override
    public int getItemCount() {
        return rowTypes.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return switch (viewType) {
            case TYPE_STATE -> new StateHolder(
                    inflater.inflate(R.layout.item_home_state, parent, false));
            case TYPE_CONTINUE_HEADER, TYPE_FEATURED_HEADER -> new HeaderHolder(
                    inflater.inflate(R.layout.item_section_header, parent, false));
            case TYPE_CONTINUE_ROW -> {
                if (continueAdapter == null) {
                    continueAdapter = new ContinueAdapter(continueItems, this::openContinue);
                }
                yield new RowHolder(
                        inflater.inflate(R.layout.item_continue_row, parent, false),
                        continueAdapter);
            }
            default -> new PosterHolder(
                    inflater.inflate(R.layout.item_poster_card, parent, false));
        };
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = getItemViewType(position);
        if (holder instanceof StateHolder state) {
            state.bind();
            return;
        }
        if (holder instanceof HeaderHolder header) {
            if (type == TYPE_CONTINUE_HEADER) {
                header.setVisible(!continueItems.isEmpty());
                header.bind(context.getString(R.string.continue_watching), continueItems.size());
            } else {
                header.setVisible(true);
                header.bind(context.getString(R.string.featured), featuredItems.size());
            }
            return;
        }
        if (holder instanceof RowHolder row) {
            row.setVisible(!continueItems.isEmpty());
            return;
        }
        if (holder instanceof PosterHolder poster && featuredStart >= 0) {
            int featuredIndex = position - featuredStart;
            if (featuredIndex >= 0 && featuredIndex < featuredItems.size()) {
                poster.bind(featuredItems.get(featuredIndex));
            }
        }
    }

    private void rebuildRows() {
        rowTypes.clear();
        featuredStart = -1;
        if (!continueItems.isEmpty()) {
            rowTypes.add(TYPE_CONTINUE_HEADER);
            rowTypes.add(TYPE_CONTINUE_ROW);
        }
        if (!featuredItems.isEmpty()) {
            rowTypes.add(TYPE_FEATURED_HEADER);
            featuredStart = rowTypes.size();
            for (int index = 0; index < featuredItems.size(); index++) {
                rowTypes.add(TYPE_POSTER);
            }
        } else {
            rowTypes.add(TYPE_STATE);
        }
    }

    private void openContinue(UserDataStore.HistoryEntry entry) {
        if (entry == null) return;
        ContentItem item = UserDataStore.get(context).resolve(entry);
        if (item == null) {
            Toast.makeText(context, "片源已失效，请重新搜索该内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (entry.episodeId == null || entry.episodeId.isBlank()) {
            DetailActivity.start(context, item);
            return;
        }
        long start = entry.durationMs > 0
                && entry.positionMs >= entry.durationMs - 10_000L ? 0 : entry.positionMs;
        PlayerActivity.start(context, item,
                new TvBoxRepository.Episode(entry.episodeName, entry.episodeId, entry.flag),
                entry.title, start);
    }

    private static boolean sameContinueItems(List<UserDataStore.HistoryEntry> left,
                                             List<UserDataStore.HistoryEntry> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            UserDataStore.HistoryEntry first = left.get(index);
            UserDataStore.HistoryEntry second = right.get(index);
            if (!first.itemId.equals(second.itemId)
                    || !first.siteKey.equals(second.siteKey)
                    || !first.episodeId.equals(second.episodeId)
                    || first.positionMs != second.positionMs
                    || first.durationMs != second.durationMs
                    || first.updatedAt != second.updatedAt) {
                return false;
            }
        }
        return true;
    }

    private final class StateHolder extends RecyclerView.ViewHolder {
        private final ProgressBar progress;
        private final TextView title;
        private final TextView description;
        private final TextView primary;
        private final TextView secondary;

        StateHolder(@NonNull View itemView) {
            super(itemView);
            progress = itemView.findViewById(R.id.home_state_progress);
            title = itemView.findViewById(R.id.home_state_title);
            description = itemView.findViewById(R.id.home_state_message);
            primary = itemView.findViewById(R.id.home_state_primary);
            secondary = itemView.findViewById(R.id.home_state_secondary);
            FocusAnimator.scale(primary, 1.04f);
            FocusAnimator.scale(secondary, 1.04f);
            primary.setOnClickListener(view -> {
                if (emptyRetryEnabled && emptyAction != null) emptyAction.retry();
            });
            secondary.setOnClickListener(view -> {
                if (emptyAction != null) emptyAction.settings();
            });
        }

        void bind() {
            progress.setVisibility(emptyRetryEnabled ? View.GONE : View.VISIBLE);
            title.setText(emptyTitle);
            description.setText(emptyDescription);
            primary.setText(emptyPrimary);
            primary.setEnabled(emptyRetryEnabled);
            primary.setAlpha(emptyRetryEnabled ? 1f : 0.58f);
            secondary.setText(emptySecondary);
        }
    }

    private final class PosterHolder extends RecyclerView.ViewHolder {
        private final View card;
        private final ImageView image;
        private final TextView title;

        PosterHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            image = itemView.findViewById(R.id.image);
            title = itemView.findViewById(R.id.title);
            TextView source = itemView.findViewById(R.id.source);
            source.setVisibility(View.GONE);
        }

        void bind(ContentItem item) {
            title.setText(item.title);
            image.setContentDescription(item.title);
            TvLayout.applyPosterCard(itemView, card, title, null);
            FocusAnimator.card(itemView, card, 1.025f,
                    () -> TvBoxRepository.get().prefetchDetail(item));
            ImageLoader.poster(image, item.title, item.year,
                    item.pic, item.imageRes, 0);
            itemView.setOnClickListener(view -> DetailActivity.start(context, item));
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView count;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.section_title);
            count = itemView.findViewById(R.id.section_count);
            TvLayoutMetrics metrics = TvLayout.metrics(itemView.getContext());
            title.setTextSize(metrics.sectionTitleTextSizeSp);
            ViewGroup.LayoutParams params = itemView.getLayoutParams();
            if (params != null) {
                params.height = TvLayout.dp(itemView.getContext(),
                        metrics.sectionHeaderHeightDp);
                itemView.setLayoutParams(params);
            }
        }

        void bind(String text, int itemCount) {
            title.setText(text);
            count.setText(itemCount == 0 ? "" : itemCount + " 部");
        }

        void setVisible(boolean visible) {
            itemView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final RecyclerView list;

        RowHolder(@NonNull View itemView, RecyclerView.Adapter<?> adapter) {
            super(itemView);
            list = itemView.findViewById(R.id.row_list);
            list.setLayoutManager(new LinearLayoutManager(itemView.getContext(),
                    LinearLayoutManager.HORIZONTAL, false));
            list.setHasFixedSize(true);
            list.setItemAnimator(null);
            list.setFocusable(false);
            list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            list.setClipToPadding(false);
            list.setAdapter(adapter);
            ViewGroup.LayoutParams params = itemView.getLayoutParams();
            if (params != null) {
                params.height = TvLayout.dp(itemView.getContext(),
                        TvLayout.metrics(itemView.getContext()).continueRowHeightDp);
                itemView.setLayoutParams(params);
            }
        }

        void setVisible(boolean visible) {
            itemView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
