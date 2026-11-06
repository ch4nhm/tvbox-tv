package com.ch4nhm.tvapp.data;

import java.util.ArrayList;
import java.util.List;

public final class TvBoxConfig {

    public String spider = "";
    public List<SiteConfig> sites = new ArrayList<>();
    public List<Live> lives = new ArrayList<>();
    public List<Parse> parses = new ArrayList<>();
    public List<Header> headers = new ArrayList<>();
    public String notice = "";

    public static final class Header {
        public String host = "";
        public java.util.Map<String, String> header = new java.util.LinkedHashMap<>();
    }

    public static final class Parse {
        public String name = "";
        public int type;
        public String url = "";
    }

    /**
     * TVBox configs use the same live-source model across FongMi and
     * CatVod-compatible distributions. Type 0 is the common M3U/TXT/JSON
     * source; spider-backed live APIs are retained for future ABI support.
     */
    public static final class Live {
        public String name = "";
        public String url = "";
        public String api = "";
        public String ext = "";
        public String jar = "";
        public String ua = "";
        public String epg = "";
        public String logo = "";
        public int type;
        public int playerType;
    }
}
