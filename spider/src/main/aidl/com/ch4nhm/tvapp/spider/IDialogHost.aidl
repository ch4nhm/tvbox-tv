package com.ch4nhm.tvapp.spider;

import com.ch4nhm.tvapp.spider.IDialogSession;

interface IDialogHost {
    void showDialog(int callbackId, String options, IDialogSession session) = 1;
    void dismissDialog(int callbackId) = 2;
    boolean openWeb(String options) = 3;
    String getCookie(String url) = 4;
    boolean setCookie(String url, String cookie) = 5;
    String getUserAgent() = 6;
}
