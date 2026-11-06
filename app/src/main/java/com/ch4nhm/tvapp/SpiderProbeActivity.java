package com.ch4nhm.tvapp;

import android.os.Bundle;
import android.widget.TextView;


import com.ch4nhm.tvapp.data.SiteConfig;
import com.ch4nhm.tvapp.spider.SiteArgs;
import com.ch4nhm.tvapp.spider.SpiderRuntime;
import com.google.gson.JsonObject;

public class SpiderProbeActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_spider_probe);
        String key = getIntent().getStringExtra("site");
        String method = getIntent().getStringExtra("method");
        String argument = getIntent().getStringExtra("argument");
        String tid = getIntent().getStringExtra("tid");
        if ("category".equals(method) && tid != null) {
            JsonObject category = new JsonObject();
            category.addProperty("tid", tid);
            category.addProperty("page", "1");
            category.addProperty("filter", false);
            category.add("extend", new JsonObject());
            argument = category.toString();
        }
        TextView title = findViewById(R.id.probe_title);
        TextView body = findViewById(R.id.probe_body);
        title.setText(method + " · " + key);
        SiteConfig site = findSite(key);
        if (site == null) {
            body.setText("站点不存在");
            return;
        }
        SiteArgs args = new SiteArgs();
        args.key = site.key;
        args.name = site.name;
        args.api = site.api;
        args.ext = site.ext;
        args.jar = site.jar;
        args.type = site.type;
        SpiderRuntime.get().call(method, args, argument == null ? "" : argument).whenComplete((value, error) -> {
            runOnUiThread(() -> body.setText(error == null ? value : "ERROR: " + error));
        });
    }

    private SiteConfig findSite(String key) {
        if (TvBoxRepository.get().getConfig() == null) return null;
        for (SiteConfig site : TvBoxRepository.get().getConfig().sites) {
            if (site.key.equals(key)) return site;
        }
        return null;
    }
}
