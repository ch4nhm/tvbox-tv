package com.ch4nhm.tvapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SampleContent {

    private SampleContent() {
    }

    public static List<ContentItem> continueWatching() {
        return Arrays.asList(
                new ContentItem("长月烬明", "电视剧", R.drawable.continue_changyue, 32),
                new ContentItem("三大队", "电影", R.drawable.continue_sandadui, 18),
                new ContentItem("去有风的地方", "电视剧", R.drawable.continue_wind, 56),
                new ContentItem("航海王", "动漫", R.drawable.continue_one_piece, 12),
                new ContentItem("玫瑰的故事", "电视剧", R.drawable.continue_rose, 8)
        );
    }

    public static List<ContentItem> featured() {
        return Arrays.asList(
                new ContentItem("流浪地球2", "科幻 · 电影", R.drawable.poster_wandering_earth),
                new ContentItem("庆余年 第二季", "电视剧", R.drawable.poster_joy_of_life),
                new ContentItem("去有风的地方", "电视剧", R.drawable.poster_wind),
                new ContentItem("狂飙", "电视剧", R.drawable.poster_kuangbiao),
                new ContentItem("天空之城", "动漫", R.drawable.poster_castle_in_sky),
                new ContentItem("斗罗大陆", "动漫", R.drawable.poster_douluo)
        );
    }

    public static MediaDetail detail(ContentItem item) {
        String description;
        String year;
        String rating;
        int episodes;
        switch (item.title) {
            case "长月烬明" -> {
                description = "为争夺神魔之力，澹台烬与黎苏苏跨越千年纠葛不断，宿命轮回，爱恨交织。";
                year = "2023";
                rating = "8.5";
                episodes = 24;
            }
            case "三大队" -> {
                description = "刑侦大队跨越十二年追凶，旧案未结，人心未冷。";
                year = "2023";
                rating = "8.2";
                episodes = 1;
            }
            case "去有风的地方" -> {
                description = "许红豆辞职来到云南，在有风的地方重新找回生活，也遇见了一群温暖的人。";
                year = "2023";
                rating = "8.7";
                episodes = 20;
            }
            case "航海王" -> {
                description = "草帽一伙驶向伟大航路，寻找传说中的 One Piece。";
                year = "1999";
                rating = "9.5";
                episodes = 12;
            }
            case "玫瑰的故事" -> {
                description = "黄亦玫在几段感情与人生选择中，寻找真正的自我。";
                year = "2024";
                rating = "8.0";
                episodes = 24;
            }
            case "流浪地球2" -> {
                description = "太阳危机逼近，人类用行星发动机推动地球，寻找新的家园。";
                year = "2023";
                rating = "8.9";
                episodes = 1;
            }
            case "庆余年 第二季" -> {
                description = "范闲重返京都，在庙堂与江湖之间继续破局。";
                year = "2024";
                rating = "8.4";
                episodes = 12;
            }
            case "狂飙" -> {
                description = "一名刑警与黑恶势力跨越二十年的较量。";
                year = "2023";
                rating = "8.8";
                episodes = 12;
            }
            case "天空之城" -> {
                description = "少女希达与少年巴鲁寻找传说中的天空之城。";
                year = "1986";
                rating = "9.2";
                episodes = 1;
            }
            case "斗罗大陆" -> {
                description = "唐三在斗罗大陆觉醒武魂，踏上强者之路。";
                year = "2021";
                rating = "8.1";
                episodes = 12;
            }
            default -> {
                description = "一个关于选择、相遇与重新出发的故事。";
                year = "2024";
                rating = "8.0";
                episodes = 12;
            }
        }
        return new MediaDetail(item.title, item.meta, description, year, rating,
                detailImage(item.title), item.progress, episodes);
    }

    private static int detailImage(String title) {
        return switch (title) {
            case "长月烬明" -> R.drawable.detail_changyue;
            case "三大队" -> R.drawable.detail_sandadui;
            case "去有风的地方" -> R.drawable.detail_wind;
            case "航海王" -> R.drawable.detail_one_piece;
            case "玫瑰的故事" -> R.drawable.detail_rose;
            case "流浪地球2" -> R.drawable.detail_wandering_earth;
            case "庆余年 第二季" -> R.drawable.detail_joy_of_life;
            case "狂飙" -> R.drawable.detail_kuangbiao;
            case "天空之城" -> R.drawable.detail_castle_in_sky;
            case "斗罗大陆" -> R.drawable.detail_douluo;
            default -> R.drawable.detail_changyue;
        };
    }

    public static List<String> episodes(int count) {
        if (count <= 1) return Collections.singletonList("正片");
        List<String> result = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            result.add("第" + index + "集");
        }
        return result;
    }

    public static List<ContentItem> related(ContentItem item) {
        List<ContentItem> result = new ArrayList<>(featured());
        result.removeIf(candidate -> candidate.title.equals(item.title));
        return result;
    }
}
