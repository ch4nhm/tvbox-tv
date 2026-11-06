package com.ch4nhm.tvapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class SearchSourceAdapter
        extends RecyclerView.Adapter<SearchSourceAdapter.Holder> {

    interface Listener {
        void onSelected(SearchResultAggregator.SourceGroup source);
    }

    private final List<SearchResultAggregator.SourceGroup> items = new ArrayList<>();
    private final Listener listener;
    private String selectedKey = "";

    SearchSourceAdapter(Listener listener) {
        this.listener = listener;
    }

    void update(List<SearchResultAggregator.SourceGroup> sources, String selectedKey) {
        List<SearchResultAggregator.SourceGroup> next =
                sources == null ? List.of() : new ArrayList<>(sources);
        List<SearchResultAggregator.SourceGroup> previous = new ArrayList<>(items);
        String nextSelectedKey = selectedKey == null ? "" : selectedKey;
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
                return previous.get(oldPosition).key.equals(next.get(newPosition).key);
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                SearchResultAggregator.SourceGroup oldSource = previous.get(oldPosition);
                SearchResultAggregator.SourceGroup newSource = next.get(newPosition);
                return oldSource.title.equals(newSource.title)
                        && oldSource.size() == newSource.size();
            }
        });
        items.clear();
        items.addAll(next);
        selectedKey = nextSelectedKey;
        diff.dispatchUpdatesTo(this);
    }

    void select(String key) {
        String next = key == null ? "" : key;
        if (selectedKey.equals(next)) return;
        selectedKey = next;
        notifyItemRangeChanged(0, items.size(), PAYLOAD_SELECTION);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_source, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SearchResultAggregator.SourceGroup source = items.get(position);
        holder.name.setText(source.title);
        holder.count.setText(holder.itemView.getResources()
                .getQuantityString(R.plurals.search_source_count,
                        source.size(), source.size()));
        holder.itemView.setSelected(source.key.equals(selectedKey));
        holder.itemView.setContentDescription(source.title + "，"
                + source.size() + " 个结果");
        holder.itemView.setOnClickListener(view -> listener.onSelected(source));
        FocusAnimator.scale(holder.itemView, 1.012f);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            SearchResultAggregator.SourceGroup source = items.get(position);
            holder.itemView.setSelected(source.key.equals(selectedKey));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    int positionOf(String key) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).key.equals(key)) return index;
        }
        return -1;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView count;

        Holder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.search_source_name);
            count = itemView.findViewById(R.id.search_source_count);
        }
    }

    private static final Object PAYLOAD_SELECTION = new Object();
}
