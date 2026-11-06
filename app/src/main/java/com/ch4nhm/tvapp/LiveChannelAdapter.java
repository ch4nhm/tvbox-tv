package com.ch4nhm.tvapp;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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
import java.util.Locale;
import java.util.Objects;

public final class LiveChannelAdapter
        extends RecyclerView.Adapter<LiveChannelAdapter.Holder> {

    public interface Listener {
        void onClick(LiveRepository.LiveChannel channel);
    }

    private final List<LiveRepository.LiveChannel> channels = new ArrayList<>();
    private final Listener listener;
    private final ColorDrawable transparent = new ColorDrawable(Color.TRANSPARENT);

    public LiveChannelAdapter(Listener listener) {
        this.listener = listener;
    }

    public void update(List<LiveRepository.LiveChannel> values) {
        List<LiveRepository.LiveChannel> next =
                values == null ? List.of() : new ArrayList<>(values);
        List<LiveRepository.LiveChannel> previous = new ArrayList<>(channels);
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
                return Objects.equals(previous.get(oldPosition).id, next.get(newPosition).id);
            }

            @Override
            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                LiveRepository.LiveChannel left = previous.get(oldPosition);
                LiveRepository.LiveChannel right = next.get(newPosition);
                return Objects.equals(left.name, right.name)
                        && Objects.equals(left.group, right.group)
                        && Objects.equals(left.logo, right.logo)
                        && Objects.equals(left.epgId, right.epgId)
                        && left.streams.size() == right.streams.size();
            }
        });
        channels.clear();
        channels.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_live_channel, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LiveRepository.LiveChannel channel = channels.get(position);
        holder.name.setText(channel.name);
        String mark = initials(channel.name);
        holder.initial.setText(mark);
        holder.initial.setVisibility(View.VISIBLE);
        TvLayoutMetrics metrics = TvLayout.metrics(holder.itemView.getContext());
        holder.card.setBackground(cover(holder.card, channel,
                Math.round(Math.max(14f, Math.min(30f,
                        metrics.channelCardHeightDp * 0.18f)))));
        holder.image.setTag(R.id.live_channel_logo, channel.id);
        holder.image.setImageDrawable(transparent);
        ImageLoader.channelLogo(holder.image, channel.logo, () -> {
            if (channel.id.equals(holder.image.getTag(R.id.live_channel_logo))) {
                holder.initial.setVisibility(View.GONE);
            }
        });
        holder.itemView.setContentDescription(channel.name + "，" + channel.group
                + "，" + channel.streams.size() + " 条线路");
        holder.itemView.setOnClickListener(view -> listener.onClick(channel));
        FocusAnimator.card(holder.itemView, holder.card, 1.04f);
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    private static String initials(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return "LIVE";
        java.util.regex.Matcher callSign = java.util.regex.Pattern
                .compile("(?i)^CCTV[\\s-]*([0-9]+(?:\\+|K|[A-Z])?)").matcher(text);
        if (callSign.find()) {
            return ("C" + callSign.group(1)).toUpperCase(Locale.ROOT);
        }
        if (text.regionMatches(true, 0, "CGTN", 0, 4)) {
            return "CG";
        }
        String normalized = text.replaceFirst(
                "(?i)^CHC[\\s-]*", "");
        String[] words = normalized.split("[\\s·|/\\\\-]+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            int codePoint = word.codePointAt(0);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                result.appendCodePoint(codePoint);
                int next = word.offsetByCodePoints(0, 1);
                if (next < word.length()) {
                    int second = word.codePointAt(next);
                    if (Character.UnicodeScript.of(second) == Character.UnicodeScript.HAN) {
                        result.appendCodePoint(second);
                    }
                }
                break;
            } else if (Character.isLetterOrDigit(codePoint)) {
                result.appendCodePoint(Character.toUpperCase(codePoint));
                if (result.length() >= 2) break;
            }
        }
        String valueOut = result.length() == 0 ? "LIVE" : result.toString();
        return valueOut.toUpperCase(Locale.ROOT);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable cover(View view, LiveRepository.LiveChannel channel,
                                          int cornerRadiusDp) {
        int[][] palettes = {
                {0xff27334a, 0xff101319},
                {0xff3b3029, 0xff15110f},
                {0xff203936, 0xff0d1514},
                {0xff39283d, 0xff140f16},
                {0xff422b24, 0xff160f0d}
        };
        int seed = Objects.hash(channel.name, channel.group);
        int[] palette = palettes[Math.floorMod(seed, palettes.length)];
        GradientDrawable cover = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, palette);
        cover.setStroke(Math.max(1, dp(view, 1)), 0x24ffffff);
        cover.setCornerRadius(dp(view, cornerRadiusDp));
        return cover;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView image;
        final TextView initial;
        final TextView name;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.live_channel_card);
            image = itemView.findViewById(R.id.live_channel_logo);
            initial = itemView.findViewById(R.id.live_channel_initial);
            name = itemView.findViewById(R.id.live_channel_name);
            TvLayoutMetrics metrics = TvLayout.metrics(itemView.getContext());
            ViewGroup.LayoutParams cardParams = card.getLayoutParams();
            if (cardParams != null) {
                cardParams.height = TvLayout.dp(itemView.getContext(),
                        metrics.channelCardHeightDp);
                card.setLayoutParams(cardParams);
            }
            name.setTextSize(metrics.channelNameTextSizeSp);
            initial.setTextSize(metrics.channelNameTextSizeSp * 1.8f);
            ViewGroup.LayoutParams rootParams = itemView.getLayoutParams();
            if (rootParams instanceof ViewGroup.MarginLayoutParams margins) {
                margins.setMarginEnd(0);
                margins.bottomMargin = 0;
                itemView.setLayoutParams(margins);
            }
        }
    }
}
