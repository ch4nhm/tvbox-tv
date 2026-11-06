package com.ch4nhm.tvapp;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


import com.ch4nhm.tvapp.data.SiteConfig;
import com.ch4nhm.tvapp.spider.SiteArgs;
import com.ch4nhm.tvapp.spider.SpiderRuntime;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class RemoteListActivity extends BaseActivity {

    public static final String EXTRA_SITE = "site";
    public static final String EXTRA_TID = "tid";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_app);
        root.setPadding(dp(40), dp(36), dp(40), dp(28));
        setContentView(root);
        String siteKey = getIntent().getStringExtra(EXTRA_SITE);
        String tid = getIntent().getStringExtra(EXTRA_TID);
        SiteConfig site = TvBoxRepository.get().getConfig() == null ? null : TvBoxRepository.get()
                .getConfig().sites.stream().filter(value -> value.key.equals(siteKey)).findFirst().orElse(null);
        if (site == null) {
            root.addView(text("内容不存在", 18, 0xffff7b72));
            return;
        }
        TextView title = text(site.displayName(), 32, 0xffffffff);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-black", android.graphics.Typeface.BOLD));
        root.addView(title, wrap(0, 0, 0, dp(24)));
        JsonObject args = new JsonObject();
        args.addProperty("tid", tid);
        args.addProperty("page", "1");
        args.addProperty("filter", false);
        args.add("extend", new JsonObject());
        SiteArgs siteArgs = new SiteArgs();
        siteArgs.key = site.key;
        siteArgs.name = site.name;
        siteArgs.api = site.api;
        siteArgs.ext = site.ext;
        siteArgs.jar = site.jar;
        siteArgs.type = site.type;
        SpiderRuntime.get().call("category", siteArgs, args.toString()).whenComplete((json, error) ->
                runOnUiThread(() -> {
                    if (error != null) {
                        root.addView(text("加载失败", 18, 0xffff7b72));
                        return;
                    }
                    render(root, site, json);
                }));
    }

    private void render(LinearLayout root, SiteConfig site, String json) {
        JsonArray list;
        try {
            list = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("list");
        } catch (Exception error) {
            root.addView(text("数据解析失败", 18, 0xffff7b72));
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (list == null) return;
        for (JsonElement element : list) {
            if (!element.isJsonObject()) continue;
            JsonObject vod = element.getAsJsonObject();
            String id = opt(vod, "vod_id");
            String name = opt(vod, "vod_name");
            if (id.isEmpty() || name.isEmpty()) continue;
            TextView item = text(clean(name), 19, 0xffffffff);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            item.setIncludeFontPadding(false);
            item.setMinHeight(dp(72));
            item.setPadding(dp(24), dp(18), dp(24), dp(18));
            item.setBackgroundResource(R.drawable.bg_source_item);
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setOnClickListener(view -> open(site, id));
            FocusAnimator.scale(item, 1.015f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(10);
            content.addView(item, params);
        }
    }

    private void open(SiteConfig site, String id) {
        SiteArgs args = new SiteArgs();
        args.key = site.key;
        args.name = site.name;
        args.api = site.api;
        args.ext = site.ext;
        args.jar = site.jar;
        args.type = site.type;
        SpiderRuntime.get().call("detail", args, id).whenComplete((detail, error) -> {
            if (error != null) return;
            SpiderRuntime.get().call("action", args, id).whenComplete((value, actionError) -> {
                if (actionError != null) return;
                runOnUiThread(() -> android.widget.Toast.makeText(this,
                        "动作返回: " + (value == null || value.isEmpty() ? "空" : value),
                        android.widget.Toast.LENGTH_LONG).show());
            });
        });
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout.LayoutParams wrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private String opt(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String clean(String value) {
        return android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString().replace('\u00a0', ' ').trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
