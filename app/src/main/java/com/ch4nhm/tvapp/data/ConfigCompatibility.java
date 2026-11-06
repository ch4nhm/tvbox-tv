package com.ch4nhm.tvapp.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Applies small compatibility defaults for incomplete third-party configs.
 *
 * <p>Some public TVBox configs omit fields that a spider needs for relative
 * request URLs. Keep these defaults here so cached and freshly downloaded
 * configs behave the same way.</p>
 */
public final class ConfigCompatibility {

    private static final String WOGG_SITE_URL = "https://www.wogg.net/";

    private ConfigCompatibility() {
    }

    public static void apply(TvBoxConfig config) {
        if (config == null || config.sites == null) return;
        for (SiteConfig site : config.sites) {
            if (site == null || site.api == null) continue;
            if (site.api.equals("csp_WoGGGuard") || site.api.equals("csp_WoGG")) {
                site.ext = withSiteUrl(site.ext, WOGG_SITE_URL);
            }
        }
    }

    static String withSiteUrl(String ext, String siteUrl) {
        String value = ext == null ? "" : ext.trim();
        JsonObject object = new JsonObject();
        if (!value.isEmpty()) {
            try {
                JsonElement parsed = JsonParser.parseString(value);
                if (!parsed.isJsonObject()) return value;
                object = parsed.getAsJsonObject().deepCopy();
            } catch (Throwable ignored) {
                return value;
            }
        }
        JsonElement configured = object.get("siteUrl");
        if (configured != null && !configured.isJsonNull()
                && !configured.getAsString().trim().isEmpty()) {
            return object.toString();
        }
        object.addProperty("siteUrl", siteUrl);
        return object.toString();
    }
}
