package com.ch4nhm.tvapp;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class SearchSuggestionAdapter
        extends RecyclerView.Adapter<SearchSuggestionAdapter.Holder> {

    interface Listener {
        void onSelected(SearchSuggestion suggestion);

        void onUp();
    }

    private final List<SearchSuggestion> items = new ArrayList<>();
    private final Listener listener;

    SearchSuggestionAdapter(Listener listener) {
        this.listener = listener;
    }

    void update(List<SearchSuggestion> suggestions) {
        List<SearchSuggestion> next = suggestions == null
                ? List.of() : new ArrayList<>(suggestions);
        List<SearchSuggestion> previous = new ArrayList<>(items);
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
                return previous.get(oldPosition).identity()
                        .equals(next.get(newPosition).identity());
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).sameContent(next.get(newPosition));
            }
        });
        items.clear();
        items.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_suggestion, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SearchSuggestion suggestion = items.get(position);
        holder.title.setText(suggestion.title);
        holder.detail.setText(suggestion.detail);
        holder.detail.setVisibility(suggestion.detail.isBlank()
                ? View.GONE : View.VISIBLE);
        holder.itemView.setContentDescription(suggestion.detail.isBlank()
                ? suggestion.title : suggestion.title + "，" + suggestion.detail);
        holder.itemView.setOnClickListener(view -> listener.onSelected(suggestion));
        holder.itemView.setOnKeyListener((view, keyCode, event) -> {
            if (position == 0
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                listener.onUp();
                return true;
            }
            return false;
        });
        FocusAnimator.scale(holder.itemView, 1.012f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView detail;

        Holder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.search_suggestion_title);
            detail = itemView.findViewById(R.id.search_suggestion_detail);
        }
    }
}
