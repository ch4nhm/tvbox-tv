package com.ch4nhm.tvapp;

import android.content.Context;
import android.widget.Toast;

/**
 * Resolves the first playable episode without forcing a detour through the detail screen.
 */
public final class QuickPlay {

    private QuickPlay() {
    }

    public static void start(Context context, ContentItem item) {
        Toast.makeText(context, "正在获取可播放剧集…", Toast.LENGTH_SHORT).show();
        TvBoxRepository.get().detail(item, new TvBoxRepository.DetailCallback() {
            @Override
            public void onDetail(TvBoxRepository.Detail detail) {
                onUi(context, () -> {
                    TvBoxRepository.Episode episode = firstPlayable(detail);
                    if (episode == null) {
                        Toast.makeText(context, "当前站点没有播放地址，已打开详情", Toast.LENGTH_SHORT).show();
                        DetailActivity.start(context, item);
                        return;
                    }
                    PlayerActivity.start(context, item, episode, detail.title);
                });
            }

            @Override
            public void onError(Throwable error) {
                onUi(context, () -> {
                    Toast.makeText(context, "快速播放失败，已打开详情", Toast.LENGTH_SHORT).show();
                    DetailActivity.start(context, item);
                });
            }
        });
    }

    private static TvBoxRepository.Episode firstPlayable(TvBoxRepository.Detail detail) {
        for (TvBoxRepository.PlayGroup group : detail.episodes) {
            for (TvBoxRepository.Episode episode : group.episodes) {
                if (episode.id != null && !episode.id.isBlank()) return episode;
            }
        }
        return null;
    }

    private static void onUi(Context context, Runnable action) {
        if (context instanceof android.app.Activity activity) {
            activity.runOnUiThread(action);
        }
    }
}
