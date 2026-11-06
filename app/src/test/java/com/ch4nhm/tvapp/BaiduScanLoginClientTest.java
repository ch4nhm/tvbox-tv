package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BaiduScanLoginClientTest {

    @Test
    public void parsesQrCodeAndNormalizesImageUrl() throws Exception {
        BaiduScanLoginClient.QrCode qr = BaiduScanLoginClient.parseQr(
                "{\"imgurl\":\"passport.baidu.com\\/v2\\/api\\/qrcode?sign=abc\","
                        + "\"errno\":0,\"sign\":\"abc\","
                        + "\"prompt\":\"请扫码\"}");

        assertEquals("abc", qr.sign());
        assertEquals("https://passport.baidu.com/v2/api/qrcode?sign=abc",
                qr.imageUrl());
        assertEquals("请扫码", qr.message());
    }

    @Test
    public void keepsUnscannedPollInWaitingState() throws Exception {
        BaiduScanLoginClient.PollState state =
                BaiduScanLoginClient.parsePoll("{\"errno\":1}");

        assertEquals("waiting", state.status());
        assertTrue(!state.terminal());
        assertTrue(!state.confirmed());
    }

    @Test
    public void exposesLoginUrlAfterPhoneConfirmation() throws Exception {
        BaiduScanLoginClient.PollState state = BaiduScanLoginClient.parsePoll(
                "{\"errno\":0,\"channel_v\":"
                        + "\"{\\\"status\\\":0,\\\"v\\\":"
                        + "\\\"https://passport.baidu.com/v3/login/main/"
                        + "qrbdusslogin?bduss=token\\\"}\"}");

        assertTrue(state.confirmed());
        assertTrue(state.terminal());
        assertEquals("https://passport.baidu.com/v3/login/main/"
                + "qrbdusslogin?bduss=token", state.loginUrl());
    }

    @Test
    public void reportsScannedButNotConfirmed() throws Exception {
        BaiduScanLoginClient.PollState state = BaiduScanLoginClient.parsePoll(
                "{\"errno\":0,\"channel_v\":\"{\\\"status\\\":1}\"}");

        assertEquals("scaned", state.status());
        assertTrue(state.message().contains("确认"));
        assertTrue(!state.terminal());
    }
}
