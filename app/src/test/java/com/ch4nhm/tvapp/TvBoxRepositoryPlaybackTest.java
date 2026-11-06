package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TvBoxRepositoryPlaybackTest {

    @Test
    public void parsesAlternatingQualityAndUrlArray() {
        TvBoxRepository.Playback playback = TvBoxRepository.parsePlayer("""
                {
                  "url": [
                    "清晰 480P",
                    "http://127.0.0.1:9978/proxy?do=bili&type=mpd",
                    "流畅 360P",
                    "http://127.0.0.1:9978/proxy?do=bili&type=mpd&qn=16"
                  ],
                  "header": "{\\"Origin\\":\\"https://www.bilibili.com\\",\\"cookie\\":\\"token=1\\"}",
                  "msg": "ok"
                }
                """);

        assertEquals("http://127.0.0.1:9978/proxy?do=bili&type=mpd", playback.url);
        assertEquals("https://www.bilibili.com", playback.headers.get("Origin"));
        assertEquals("token=1", playback.headers.get("cookie"));
        assertEquals("ok", playback.message);
    }

    @Test
    public void findsUrlInsideNestedObject() {
        TvBoxRepository.Playback playback = TvBoxRepository.parsePlayer("""
                {
                  "url": {
                    "name": "超清",
                    "playUrl": {
                      "url": "https://example.com/video.m3u8"
                    }
                  }
                }
                """);

        assertEquals("https://example.com/video.m3u8", playback.url);
    }

    @Test
    public void marksWebParserResponsesAsRequiringParsing() {
        TvBoxRepository.Playback playback = TvBoxRepository.parsePlayer("""
                {
                  "parse": 1,
                  "url": "https://hhjx.hhplayer.com/?url=abc"
                }
                """);

        assertEquals(true, playback.requiresParsing);
    }
}
