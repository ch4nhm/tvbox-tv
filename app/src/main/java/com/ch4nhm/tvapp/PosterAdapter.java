package com.ch4nhm.tvapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PosterAdapter extends RecyclerView.Adapter<PosterAdapter.Holder> {

    public interface OnItemClickListener {
        void onClick(ContentItem item);
    }

    private final List<ContentItem> items = new ArrayList<>();
    private final OnItemClickListener listener;

    public PosterAdapter(List<ContentItem> items, OnItemClickListener listener) {
        this.items.addAll(items);
        this.listener = listener;
    }

    public void update(List<ContentItem> items) {
        List<ContentItem> next = items == null ? List.of() : new ArrayList<>(items);
        List<ContentItem> previous = new ArrayList<>(this.items);
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
                return identity(previous.get(oldPosition)).equals(identity(next.get(newPosition)));
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return sameContent(previous.get(oldPosition), next.get(newPosition));
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
                .inflate(R.layout.item_poster_card, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ContentItem item = items.get(position);
        holder.title.setText(item.title);
        holder.meta.setText("");
        holder.meta.setVisibility(View.GONE);
        holder.source.setVisibility(View.GONE);
        holder.image.setContentDescription(item.title);
        TvLayout.applyPosterCard(holder.itemView, holder.card, holder.title, holder.meta);
        holder.itemView.setOnClickListener(view -> listener.onClick(item));
        FocusAnimator.card(holder.itemView, holder.card, 1.025f,
                () -> TvBoxRepository.get().prefetchDetail(item));
        ImageLoader.poster(holder.image, item.title, item.year,
                item.pic, item.imageRes, 0);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String identity(ContentItem item) {
        if (item == null) return "";
        String siteKey = item.site == null ? "" : item.site.key;
        String id = item.id == null ? "" : item.id;
        if (!id.isBlank()) return siteKey + "\u0000" + id;
        return siteKey + "\u0000" + Objects.toString(item.title, "")
                + "\u0000" + Objects.toString(item.pic, "");
    }

    private static boolean sameContent(ContentItem left, ContentItem right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return Objects.equals(left.title, right.title)
                && Objects.equals(left.meta, right.meta)
                && Objects.equals(left.year, right.year)
                && left.imageRes == right.imageRes
                && left.progress == right.progress
                && Objects.equals(left.pic, right.pic)
                && Objects.equals(left.remark, right.remark)
                && left.hits == right.hits
                && Double.compare(left.rating, right.rating) == 0
                && Objects.equals(identity(left), identity(right));
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView image;
        final TextView title;
        final TextView source;
        final TextView meta;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            image = itemView.findViewById(R.id.image);
            title = itemView.findViewById(R.id.title);
            source = itemView.findViewById(R.id.source);
            meta = itemView.findViewById(R.id.meta);
        }
    }
}
