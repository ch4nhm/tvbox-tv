package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WebPlaybackResolverTest {

    @Test
    public void parsesBootstrapFromPlayerPage() throws Exception {
        String html = """
                <script>
                window.__HHJX_BOOTSTRAP__={"url":"resource-token","t":1793799707,
                "key":"parse-key","failure_html":"播放失败"};
                </script>
                """;

        WebPlaybackResolver.Bootstrap bootstrap =
                WebPlaybackResolver.parseBootstrap(html);

        assertEquals("resource-token", bootstrap.resourceUrl);
        assertEquals(1793799707L, bootstrap.timestamp);
        assertEquals("parse-key", bootstrap.key);
    }

    @Test
    public void parsesDirectApiResult() throws Exception {
        WebPlaybackResolver.ApiResult result = WebPlaybackResolver.parseApiResult("""
                {"code":200,"msg":"成功",
                 "url":"https://media.example.com/show/index.m3u8"}
                """);

        assertEquals("https://media.example.com/show/index.m3u8", result.url);
        assertFalse(result.needsFallback());
    }

    @Test
    public void requestsFallbackForBrowserOnlyResult() throws Exception {
        WebPlaybackResolver.ApiResult result = WebPlaybackResolver.parseApiResult("""
                {"code":200,"msg":"成功","url":"video-id","ext":"youku"}
                """);

        assertTrue(result.needsFallback());
    }

    @Test
    public void resolvesLiveHhJxPageWhenConfigured() throws Exception {
        String url = System.getenv("TVBOX_LIVE_PARSE_URL");
        Assume.assumeTrue(url != null && !url.isBlank());

        TvBoxRepository.Playback resolved = WebPlaybackResolver.resolve(
                        new TvBoxRepository.Playback(url, Map.of(), "", true))
                .get(35, TimeUnit.SECONDS);

        assertTrue(resolved.url.startsWith("http://")
                || resolved.url.startsWith("https://"));
        assertFalse(resolved.requiresParsing);
    }
}
