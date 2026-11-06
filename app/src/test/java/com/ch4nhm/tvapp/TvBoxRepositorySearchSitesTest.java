package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ch4nhm.tvapp.data.SiteConfig;
import com.ch4nhm.tvapp.data.TvBoxConfig;

import org.junit.Test;

import java.util.List;

public final class TvBoxRepositorySearchSitesTest {

    @Test
    public void includesEverySearchableSpiderWithoutQuickSearchTruncation() {
        TvBoxConfig config = new TvBoxConfig();
        SiteConfig home = site("home", "当前源", 0, true);
        config.sites.add(home);
        for (int index = 0; index < 12; index++) {
            config.sites.add(site("site-" + index, "站点 " + index,
                    index % 2, true));
        }

        List<SiteConfig> result = TvBoxRepository.searchSites(config, home);

        assertEquals(13, result.size());
        assertEquals(home, result.get(0));
        assertTrue(result.stream().anyMatch(site -> !site.isQuickSearch()));
    }

    @Test
    public void excludesNonSearchableAndPanConfigurationSites() {
        TvBoxConfig config = new TvBoxConfig();
        SiteConfig home = site("home", "当前源", 1, true);
        SiteConfig disabled = site("disabled", "不可搜索", 1, false);
        SiteConfig panConfig = site("MDrive", "网盘配置", 1, true);
        panConfig.api = "csp_MyDriveGuard";
        config.sites.add(home);
        config.sites.add(disabled);
        config.sites.add(panConfig);

        List<SiteConfig> result = TvBoxRepository.searchSites(config, home);

        assertEquals(List.of(home), result);
        assertFalse(result.contains(disabled));
        assertFalse(result.contains(panConfig));
    }

    private static SiteConfig site(String key, String name, int quickSearch,
                                   boolean searchable) {
        SiteConfig site = new SiteConfig();
        site.key = key;
        site.name = name;
        site.api = "csp_TestGuard";
        site.type = 3;
        site.quickSearch = quickSearch;
        site.searchable = searchable ? 1 : 0;
        return site;
    }
}
