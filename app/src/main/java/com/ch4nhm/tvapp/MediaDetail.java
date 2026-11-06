package com.ch4nhm.tvapp;

public final class MediaDetail {

    public final String title;
    public final String meta;
    public final String description;
    public final String year;
    public final String rating;
    public final int imageRes;
    public final int progress;
    public final int episodeCount;

    public MediaDetail(String title, String meta, String description, String year,
                       String rating, int imageRes, int progress, int episodeCount) {
        this.title = title;
        this.meta = meta;
        this.description = description;
        this.year = year;
        this.rating = rating;
        this.imageRes = imageRes;
        this.progress = progress;
        this.episodeCount = episodeCount;
    }
}
