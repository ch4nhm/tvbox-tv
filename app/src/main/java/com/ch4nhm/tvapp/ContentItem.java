package com.ch4nhm.tvapp;

public final class ContentItem {

    public final String id;
    public final String title;
    public final String meta;
    public final String year;
    public final int imageRes;
    public final int progress;
    public final String pic;
    public final String remark;
    public final com.ch4nhm.tvapp.data.SiteConfig site;
    public final long hits;
    public final double rating;

    public ContentItem(String title, String meta, int imageRes) {
        this("", title, meta, "", imageRes, -1, "", "", null);
    }

    public ContentItem(String title, String meta, int imageRes, int progress) {
        this("", title, meta, "", imageRes, progress, "", "", null);
    }

    public ContentItem(String id, String title, String meta, String pic, String remark,
                       com.ch4nhm.tvapp.data.SiteConfig site) {
        this(id, title, meta, "", pic, remark, site);
    }

    public ContentItem(String id, String title, String meta, String year, String pic,
                       String remark, com.ch4nhm.tvapp.data.SiteConfig site) {
        this(id, title, meta, year, R.drawable.hero_till_end_of_moon, -1, pic, remark, site);
    }

    public ContentItem(String id, String title, String meta, int imageRes, int progress,
                       String pic, String remark, com.ch4nhm.tvapp.data.SiteConfig site) {
        this(id, title, meta, "", imageRes, progress, pic, remark, site, 0L, 0d);
    }

    public ContentItem(String id, String title, String meta, String year,
                       int imageRes, int progress, String pic, String remark,
                       com.ch4nhm.tvapp.data.SiteConfig site) {
        this(id, title, meta, year, imageRes, progress, pic, remark, site, 0L, 0d);
    }

    public ContentItem(String id, String title, String meta, String year,
                       int imageRes, int progress, String pic, String remark,
                       com.ch4nhm.tvapp.data.SiteConfig site, long hits, double rating) {
        this.id = id;
        this.title = title;
        this.meta = meta;
        this.year = year == null ? "" : year;
        this.imageRes = imageRes;
        this.progress = progress;
        this.pic = pic;
        this.remark = remark;
        this.site = site;
        this.hits = Math.max(0L, hits);
        this.rating = Math.max(0d, rating);
    }

    public ContentItem(String id, String title, String meta, String year,
                       String pic, String remark,
                       com.ch4nhm.tvapp.data.SiteConfig site, long hits, double rating) {
        this(id, title, meta, year, R.drawable.hero_till_end_of_moon, -1,
                pic, remark, site, hits, rating);
    }
}
