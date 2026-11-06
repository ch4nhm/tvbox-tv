package com.ch4nhm.tvapp.spider;

interface IDialogSession {
    String action(int callbackId, String event) = 1;
    void complete(int callbackId, String value) = 2;
}
