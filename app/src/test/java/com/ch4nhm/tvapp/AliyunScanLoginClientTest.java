package com.ch4nhm.tvapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class AliyunScanLoginClientTest {

    @Test
    public void parsesGeneratedQrCode() throws Exception {
        AliyunScanLoginClient.QrCode qr = AliyunScanLoginClient.parseQr(
                "{\"content\":{\"data\":{\"t\":1793530007321,"
                        + "\"codeContent\":\"https://passport.aliyundrive.com/qrcodeCheck.htm\","
                        + "\"ck\":\"check-code\",\"resultCode\":100}},"
                        + "\"hasError\":false}");

        assertEquals("1793530007321", qr.taskId());
        assertEquals("check-code", qr.ck());
        assertEquals("https://passport.aliyundrive.com/qrcodeCheck.htm",
                qr.content());
    }

    @Test
    public void mapsScannedStateWithoutCompletingLogin() throws Exception {
        AliyunScanLoginClient.PollState state =
                AliyunScanLoginClient.parsePoll(
                        "{\"content\":{\"data\":{\"status\":\"SCANED\"}}}");

        assertEquals("SCANED", state.status());
        assertTrue(!state.terminal());
        assertTrue(state.message().contains("确认"));
    }

    @Test
    public void mapsQrCodeStatusFromLivePollResponse() throws Exception {
        AliyunScanLoginClient.PollState scanning =
                AliyunScanLoginClient.parsePoll(
                        "{\"content\":{\"data\":{\"qrCodeStatus\":\"SCANED\","
                                + "\"resultCode\":100}},\"hasError\":false}");

        assertEquals("SCANED", scanning.status());
        assertTrue(!scanning.terminal());
        assertTrue(scanning.message().contains("确认"));
    }

    @Test
    public void extractsRefreshTokenFromConfirmedBizExt() throws Exception {
        String bizExt = Base64.getEncoder().encodeToString(
                ("{\"pds_login_result\":{\"refreshToken\":\"refresh-token\","
                        + "\"nickName\":\"测试账号\","
                        + "\"avatar\":\"https://example.com/avatar.png\"}}")
                        .getBytes(StandardCharsets.UTF_8));
        AliyunScanLoginClient.PollState state =
                AliyunScanLoginClient.parsePoll(
                        "{\"content\":{\"data\":{\"qrCodeStatus\":\"CONFIRMED\","
                                + "\"bizExt\":\"" + bizExt + "\"}}}");

        AliyunScanLoginClient.BizExt parsed =
                AliyunScanLoginClient.parseBizExt(state.bizExt());
        assertTrue(state.terminal());
        assertEquals("refresh-token", parsed.refreshToken());
        assertEquals("测试账号", parsed.nickname());
        assertEquals("https://example.com/avatar.png", parsed.avatar());
    }
}
