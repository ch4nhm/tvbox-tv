package com.ch4nhm.tvapp.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.google.gson.JsonParser;

import org.junit.Test;

public final class ConfigCompatibilityTest {

    @Test
    public void addsWoGGBaseUrlWithoutDroppingExistingExtValues() {
        TvBoxConfig config = config(site("csp_WoGGGuard",
                "{\"Cloud-drive\":\"tvfan/Cloud-drive.txt\"}"));

        ConfigCompatibility.apply(config);

        assertEquals("https://www.wogg.net/",
                JsonParser.parseString(config.sites.get(0).ext)
                        .getAsJsonObject().get("siteUrl").getAsString());
        assertEquals("tvfan/Cloud-drive.txt",
                JsonParser.parseString(config.sites.get(0).ext)
                        .getAsJsonObject().get("Cloud-drive").getAsString());
    }

    @Test
    public void keepsAnExplicitSiteUrl() {
        TvBoxConfig config = config(site("csp_WoGG",
                "{\"siteUrl\":\"https://custom.example/\"}"));

        ConfigCompatibility.apply(config);

        assertEquals("https://custom.example/",
                JsonParser.parseString(config.sites.get(0).ext)
                        .getAsJsonObject().get("siteUrl").getAsString());
    }

    @Test
    public void leavesMalformedJsonAndOtherSpidersUntouched() {
        SiteConfig malformed = site("csp_WoGGGuard", "not-json");
        SiteConfig other = site("csp_Other", "{}");
        TvBoxConfig config = config(malformed, other);

        ConfigCompatibility.apply(config);

        assertEquals("not-json", malformed.ext);
        assertSame(other, config.sites.get(1));
        assertEquals("{}", other.ext);
    }

    private static TvBoxConfig config(SiteConfig... sites) {
        TvBoxConfig config = new TvBoxConfig();
        for (SiteConfig site : sites) config.sites.add(site);
        return config;
    }

    private static SiteConfig site(String api, String ext) {
        SiteConfig site = new SiteConfig();
        site.api = api;
        site.ext = ext;
        return site;
    }
}
