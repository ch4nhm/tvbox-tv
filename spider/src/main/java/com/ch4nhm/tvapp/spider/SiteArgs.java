package com.ch4nhm.tvapp.spider;

import com.google.gson.Gson;

public final class SiteArgs {

    private static final Gson GSON = new Gson();

    public String key = "";
    public String name = "";
    public String api = "";
    public String ext = "";
    public String jar = "";
    public int type;

    public String toJson() {
        return GSON.toJson(this);
    }

    public static SiteArgs fromJson(String value) {
        SiteArgs site = GSON.fromJson(value, SiteArgs.class);
        if (site == null) throw new IllegalArgumentException("站点配置为空");
        if (site.key == null) site.key = "";
        if (site.name == null) site.name = "";
        if (site.api == null) site.api = "";
        if (site.ext == null) site.ext = "";
        if (site.jar == null) site.jar = "";
        return site;
    }

    SiteArgs copy() {
        SiteArgs site = new SiteArgs();
        site.key = key;
        site.name = name;
        site.api = api;
        site.ext = ext;
        site.jar = jar;
        site.type = type;
        return site;
    }

    boolean matches(SiteArgs other) {
        return key.equals(other.key) && api.equals(other.api) && ext.equals(other.ext) && jar.equals(other.jar);
    }
}
