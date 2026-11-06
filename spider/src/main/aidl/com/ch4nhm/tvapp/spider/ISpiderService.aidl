package com.ch4nhm.tvapp.spider;

import com.ch4nhm.tvapp.spider.IDialogHost;

interface ISpiderService {
    String call(String method, String siteJson, String argument) = 1;
    void reset() = 2;
    void setDialogHost(IDialogHost host) = 3;
    int getProxyPort() = 4;
    String proxy(String query) = 5;
    void prepare(String siteJson) = 6;
}
