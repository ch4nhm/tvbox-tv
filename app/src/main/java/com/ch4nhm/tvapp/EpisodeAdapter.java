package com.ch4nhm.tvapp;

import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {

    private static final Pattern LEADING_TAG =
            Pattern.compile("^\\s*(?:\\[[^\\]]{1,36}]|【[^】]{1,36}】)\\s*");
    private static final Pattern TRAILING_TAG = Pattern.compile("[【\\[].*$");
    private static final Pattern VIDEO_EXTENSION = Pattern.compile(
            "(?i)\\.(?:mkv|mp4|avi|ts|m3u8|flv|webm)\\s*$");
    private static final Pattern QUALITY_TAG = Pattern.compile(
            "(?i)(?:[._ -]*(?:4K|8K|2160P|1080P|720P|HDR|DV|"
                    + "HEVC|AVC|WEB[- ]?DL|BluRay|HD))+\\s*$");
    private static final Pattern TRAILING_ELLIPSIS = Pattern.compile("[…]+$");
    private static final Pattern SEASON_EPISODE =
            Pattern.compile("(?i)(?:S(\\d{1,2}))?[ ._-]*E(\\d{1,4})");
    private static final Pattern CHINESE_EPISODE =
            Pattern.compile("第\\s*\\d+\\s*[集话期]");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)\\d{2}");
    private static final Pattern LEADING_NUMBER =
            Pattern.compile("^(\\d{1,4})\\s*[-_. ]+.*$");
    private static final Pattern NUMERIC_EPISODE = Pattern.compile(
            "(?i)(?:^|[^A-Za-z0-9])(\\d{1,4})[ ._-]*(?:[集话期]|EP?)?"
                    + "(?:\\.(?:mkv|mp4|avi|ts|m3u8)|[\\s\\]】]|$)");

    public interface OnItemClickListener {
        void onClick(TvBoxRepository.Episode episode);
    }

    public interface OnItemFocusListener {
        void onFocus(TvBoxRepository.Episode episode);
    }

    private final List<TvBoxRepository.Episode> items;
    private final List<String> labels;
    private final OnItemClickListener listener;
    private final OnItemFocusListener focusListener;
    private int focusRequestGeneration;

    public EpisodeAdapter(List<TvBoxRepository.Episode> items, OnItemClickListener listener) {
        this(items, listener, null);
    }

    public EpisodeAdapter(List<TvBoxRepository.Episode> items, OnItemClickListener listener,
                          OnItemFocusListener focusListener) {
        this.items = items;
        this.labels = distinctLabels(items);
        this.listener = listener;
        this.focusListener = focusListener;
    }

    private static List<String> distinctLabels(List<TvBoxRepository.Episode> items) {
        List<String> base = items.stream()
                .map(episode -> displayName(episode.name))
                .toList();
        java.util.Map<String, Integer> totals = new java.util.HashMap<>();
        for (String label : base) totals.merge(label, 1, Integer::sum);
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        java.util.List<String> result = new java.util.ArrayList<>(base.size());
        for (String label : base) {
            int index = seen.merge(label, 1, Integer::sum);
            if (totals.getOrDefault(label, 0) <= 1) {
                result.add(label);
            } else {
                result.add(label + " " + duplicateMark(index));
            }
        }
        return result;
    }

    private static String duplicateMark(int index) {
        return index <= 26 ? String.valueOf((char) ('A' + index - 1)) : String.valueOf(index);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_episode, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        TvBoxRepository.Episode episode = items.get(position);
        holder.label.setText(labels.get(position));
        holder.itemView.setOnClickListener(view -> listener.onClick(episode));
        holder.itemView.setOnKeyListener((view, keyCode, event) ->
                handleDirectionalKey(holder, view, keyCode, event));
        FocusAnimator.scale(holder.itemView, 1.035f,
                focusListener == null ? null : () -> focusListener.onFocus(episode));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView label;

        Holder(@NonNull View itemView) {
            super(itemView);
            label = (TextView) itemView;
            TvLayoutMetrics metrics = TvLayout.metrics(itemView.getContext());
            ViewGroup.LayoutParams params = itemView.getLayoutParams();
            if (params != null) {
                params.height = TvLayout.dp(itemView.getContext(),
                        metrics.episodeItemHeightDp);
                itemView.setLayoutParams(params);
            }
            label.setTextSize(metrics.episodeTextSizeSp);
        }
    }

    private boolean handleDirectionalKey(Holder holder, View view, int keyCode,
                                         KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        // Ignore held-key repeats; otherwise several asynchronous focus
        // requests can race while the grid is scrolling and scaled items overlap.
        if (event.getRepeatCount() > 0) return true;
        int position = holder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION
                || !(view.getParent() instanceof RecyclerView list)
                || !(list.getLayoutManager() instanceof GridLayoutManager grid)) {
            return false;
        }
        int spanCount = Math.max(1, grid.getSpanCount());
        int target = position;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (position % spanCount == 0) return true;
                target = position - 1;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (position % spanCount == spanCount - 1
                        || position + 1 >= getItemCount()) {
                    return true;
                }
                target = position + 1;
            }
            case KeyEvent.KEYCODE_DPAD_UP -> {
                target = position - spanCount;
                if (target < 0) {
                    View play = view.getRootView().findViewById(R.id.detail_play);
                    if (play != null) play.requestFocus();
                    return true;
                }
            }
            case KeyEvent.KEYCODE_DPAD_DOWN -> {
                target = position + spanCount;
                if (target >= getItemCount()) return true;
            }
            default -> {
                return false;
            }
        }
        focusPosition(list, target, 4);
        return true;
    }

    private void focusPosition(RecyclerView list, int position, int attempts) {
        int generation = ++focusRequestGeneration;
        focusPosition(list, position, attempts, generation);
    }

    private void focusPosition(RecyclerView list, int position, int attempts, int generation) {
        if (generation != focusRequestGeneration) return;
        list.stopScroll();
        list.scrollToPosition(position);
        list.post(() -> {
            if (generation != focusRequestGeneration) return;
            RecyclerView.ViewHolder target =
                    list.findViewHolderForAdapterPosition(position);
            if (target != null) {
                target.itemView.requestFocus();
            } else if (attempts > 0) {
                list.postDelayed(
                        () -> focusPosition(list, position, attempts - 1, generation), 40L);
            }
        });
    }

    static String displayName(String raw) {
        if (raw == null || raw.isBlank()) return "正片";
        String value = LEADING_TAG.matcher(raw.trim()).replaceFirst("");
        value = TRAILING_TAG.matcher(value).replaceFirst("");
        value = VIDEO_EXTENSION.matcher(value).replaceFirst("");
        value = QUALITY_TAG.matcher(value).replaceFirst("");
        value = TRAILING_ELLIPSIS.matcher(value).replaceFirst("").trim();
        if (value.isEmpty()) return "正片";
        if (value.length() > 10 && (value.contains("定档")
                || value.contains("预告") || value.contains("花絮")
                || value.contains("片花"))) {
            return value.contains("花絮") ? "花絮"
                    : value.contains("片花") ? "片花" : "定档预告";
        }
        Matcher seasonEpisode = SEASON_EPISODE.matcher(value);
        if (seasonEpisode.find()) {
            String season = seasonEpisode.group(1);
            String episode = seasonEpisode.group(2);
            try {
                int number = Integer.parseInt(episode);
                return season == null
                        ? String.format(java.util.Locale.getDefault(), "第%02d集", number)
                        : "1".equals(season) || "01".equals(season)
                        ? String.format(java.util.Locale.getDefault(), "第%02d集", number)
                        : String.format(java.util.Locale.getDefault(), "第%s季 第%02d集",
                                season, number);
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher chinese = CHINESE_EPISODE.matcher(value);
        if (chinese.find()) return chinese.group().replaceAll("\\s+", "");
        if (YEAR.matcher(value).find()) {
            return "正片";
        }
        Matcher leadingNumber = LEADING_NUMBER.matcher(value);
        if (leadingNumber.matches()) {
            return String.format(java.util.Locale.getDefault(), "第%02d集",
                    Integer.parseInt(leadingNumber.group(1)));
        }
        Matcher numeric = NUMERIC_EPISODE.matcher(value);
        if (numeric.find()) {
            try {
                return String.format(java.util.Locale.getDefault(), "第%02d集",
                        Integer.parseInt(numeric.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        if (value.length() > 18) {
            int end = Math.max(value.lastIndexOf('.'), Math.max(value.lastIndexOf(']'), value.lastIndexOf('】')));
            if (end > 4) value = value.substring(0, end);
            if (value.length() > 18) value = value.substring(0, 18) + "…";
        }
        return value;
    }
}
