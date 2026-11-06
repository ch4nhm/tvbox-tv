package com.ch4nhm.tvapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class ContinueAdapter extends RecyclerView.Adapter<ContinueAdapter.Holder> {

    public interface OnItemClickListener {
        void onClick(UserDataStore.HistoryEntry item);
    }

    private final List<UserDataStore.HistoryEntry> items = new ArrayList<>();
    private final OnItemClickListener listener;

    public ContinueAdapter(List<UserDataStore.HistoryEntry> items,
                           OnItemClickListener listener) {
        this.items.addAll(items);
        this.listener = listener;
    }

    public void update(List<UserDataStore.HistoryEntry> items) {
        List<UserDataStore.HistoryEntry> next =
                items == null ? List.of() : new ArrayList<>(items);
        List<UserDataStore.HistoryEntry> previous = new ArrayList<>(this.items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return previous.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return identity(previous.get(oldPosition))
                        .equals(identity(next.get(newPosition)));
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                UserDataStore.HistoryEntry left = previous.get(oldPosition);
                UserDataStore.HistoryEntry right = next.get(newPosition);
                return Objects.equals(left.title, right.title)
                        && Objects.equals(left.episodeName, right.episodeName)
                        && Objects.equals(left.episodeId, right.episodeId)
                        && left.positionMs == right.positionMs
                        && left.durationMs == right.durationMs
                        && Objects.equals(left.pic, right.pic);
            }
        });
        this.items.clear();
        this.items.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_continue_card, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        UserDataStore.HistoryEntry entry = items.get(position);
        holder.title.setText(entry.title);
        String episode = entry.episodeName == null || entry.episodeName.isBlank()
                ? "正片" : EpisodeAdapter.displayName(entry.episodeName);
        holder.meta.setText(episode + " · " + position(entry.positionMs)
                + (entry.durationMs > 0 ? " / " + position(entry.durationMs) : ""));
        int progress = entry.progressPercent();
        holder.progressText.setText(progress >= 99
                ? "已看完 · 可重播"
                : String.format(Locale.getDefault(), "继续观看 · %d%%", progress));
        holder.progress.setProgress(progress);
        holder.image.setContentDescription(entry.title);
        holder.itemView.setOnClickListener(view -> listener.onClick(entry));
        ImageLoader.widePoster(holder.image, entry.title, "",
                entry.pic, R.drawable.poster_placeholder, 12);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String identity(UserDataStore.HistoryEntry entry) {
        if (entry == null) return "";
        return entry.siteKey + "\u0000" + entry.itemId + "\u0000" + entry.episodeId;
    }

    static class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView image;
        final TextView title;
        final TextView meta;
        final TextView progressText;
        final ProgressBar progress;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            image = itemView.findViewById(R.id.image);
            title = itemView.findViewById(R.id.title);
            meta = itemView.findViewById(R.id.meta);
            progressText = itemView.findViewById(R.id.progress_text);
            progress = itemView.findViewById(R.id.progress);
            TvLayoutMetrics metrics = TvLayout.metrics(itemView.getContext());
            ViewGroup.LayoutParams rootParams = itemView.getLayoutParams();
            if (rootParams != null) {
                rootParams.width = TvLayout.dp(itemView.getContext(),
                        metrics.continueCardWidthDp);
                if (rootParams instanceof ViewGroup.MarginLayoutParams margins) {
                    margins.setMarginEnd(TvLayout.dp(itemView.getContext(),
                            metrics.cardGapDp));
                }
                itemView.setLayoutParams(rootParams);
            }
            ViewGroup.LayoutParams cardParams = card.getLayoutParams();
            if (cardParams != null) {
                cardParams.height = TvLayout.dp(itemView.getContext(),
                        metrics.continueImageHeightDp);
                card.setLayoutParams(cardParams);
            }
            title.setTextSize(metrics.posterTitleTextSizeSp);
            FocusAnimator.card(itemView, card, 1.05f);
        }
    }

    private static String position(long millis) {
        int seconds = (int) Math.max(0, millis / 1000);
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
    }
}
