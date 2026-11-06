package com.ch4nhm.tvapp;

import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class PlayGroupAdapter extends RecyclerView.Adapter<PlayGroupAdapter.Holder> {

    private static final Pattern INVALID_LABEL =
            Pattern.compile("(?i)m3u8|mp4|hls|dash|.*\\.m3u8.*");

    interface OnGroupClickListener {
        void onClick(int index);
    }

    private final List<TvBoxRepository.PlayGroup> groups = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private final OnGroupClickListener listener;
    private final Runnable onNavigateDown;
    private int selected;

    PlayGroupAdapter(OnGroupClickListener listener, Runnable onNavigateDown) {
        this.listener = listener;
        this.onNavigateDown = onNavigateDown;
    }

    void update(List<TvBoxRepository.PlayGroup> values, Set<String> loggedInBrands) {
        groups.clear();
        groups.addAll(values);
        labels.clear();
        for (int index = 0; index < groups.size(); index++) {
            TvBoxRepository.PlayGroup group = groups.get(index);
            String brand = PanRepository.brandForSource(group);
            boolean needsLogin = !brand.isBlank()
                    && (loggedInBrands == null || !loggedInBrands.contains(brand));
            labels.add(displayName(group.flag, index) + (needsLogin ? " · 需登录" : ""));
        }
        if (selected >= groups.size()) selected = 0;
        notifyDataSetChanged();
    }

    void select(int index) {
        if (index < 0 || index >= groups.size() || selected == index) return;
        int previous = selected;
        selected = index;
        notifyItemChanged(previous);
        notifyItemChanged(selected);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_play_group, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.label.setText(labels.get(position));
        holder.itemView.setSelected(position == selected);
        holder.itemView.setOnClickListener(view -> listener.onClick(position));
        holder.itemView.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                onNavigateDown.run();
                return true;
            }
            return false;
        });
        FocusAnimator.scale(holder.itemView, 1.04f);
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    private static String displayName(String flag, int index) {
        String value = flag == null ? "" : flag
                .replace("┃", " · ")
                .replaceAll("\\s+", " ")
                .trim();
        if (value.isEmpty()
                || value.startsWith("http")
                || value.contains("$$$")
                || INVALID_LABEL.matcher(value).matches()) {
            return String.format(Locale.getDefault(), "线路 %d", index + 1);
        }
        if (value.length() > 10) value = value.substring(0, 9) + "…";
        return value;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView label;

        Holder(@NonNull View itemView) {
            super(itemView);
            label = (TextView) itemView;
            TvLayoutMetrics metrics = TvLayout.metrics(itemView.getContext());
            ViewGroup.LayoutParams params = itemView.getLayoutParams();
            if (params != null) {
                params.height = TvLayout.dp(itemView.getContext(),
                        Math.max(42f, Math.min(48f,
                                metrics.detailEpisodeHeaderHeightDp - 18f)));
                itemView.setLayoutParams(params);
            }
            label.setTextSize(Math.max(14f, metrics.posterTitleTextSizeSp - 2f));
        }
    }
}
