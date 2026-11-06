package com.ch4nhm.tvapp.data;

import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;

import java.util.ArrayList;
import java.util.List;

public final class SiteConfig {

    public String key = "";
    public String name = "";
    public String api = "";
    @JsonAdapter(ExtAdapter.class)
    public String ext = "";
    public String jar = "";
    public int type;
    @SerializedName("searchable")
    public int searchable = 1;
    @SerializedName("quickSearch")
    public int quickSearch = 1;
    @SerializedName("filterable")
    public int filterable;
    public List<Category> categories = new ArrayList<>();

    public boolean isSearchable() {
        return searchable == 1;
    }

    public boolean isQuickSearch() {
        return quickSearch == 1;
    }

    public boolean isSpider() {
        return type == 3 && api.startsWith("csp_");
    }

    public String displayName() {
        String cleaned = name == null ? "" : name
                .replace("┃", " · ")
                .replaceAll("[【\\[](?=[^】\\]]*(?:免费|分享|更新|发布|广告|群|中心))"
                        + "[^】\\]]*[】\\]]", "")
                .replaceAll("[\\p{So}\\p{Cn}\\p{Cf}]", "")
                .replaceAll("\\s*·\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? "TVBox" : cleaned;
    }

    public static final class ExtAdapter implements JsonDeserializer<String> {
        @Override
        public String deserialize(JsonElement json, java.lang.reflect.Type type, JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) return "";
            if (json.isJsonPrimitive()) return json.getAsString();
            return json.toString();
        }
    }

    public static final class Category {
        public String type_id = "";
        public String type_name = "";
    }
}
