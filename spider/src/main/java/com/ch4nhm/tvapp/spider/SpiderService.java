package com.ch4nhm.tvapp.spider;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class SpiderService extends Service {

    private SpiderProxyServer proxyServer;

    private final ISpiderService.Stub binder = new ISpiderService.Stub() {
        @Override
        public String call(String method, String siteJson, String argument) throws RemoteException {
            try {
                String value = SpiderEngine.get().call(method, SiteArgs.fromJson(siteJson), argument);
                return SpiderResultTransport.store(SpiderService.this, value);
            } catch (java.io.IOException error) {
                throw new RemoteException("Spider 结果保存失败: " + error.getMessage());
            }
        }

        @Override
        public void reset() {
            SpiderEngine.get().reset();
        }

        @Override
        public void setDialogHost(com.ch4nhm.tvapp.spider.IDialogHost host) {
            SpiderDialogBridge.install(host);
        }

        @Override
        public int getProxyPort() {
            return proxyServer == null ? -1 : proxyServer.getPort();
        }

        @Override
        public String proxy(String query) throws RemoteException {
            return proxyServer == null ? "" : proxyServer.read(query);
        }

        @Override
        public void prepare(String siteJson) throws RemoteException {
            try {
                SpiderEngine.get().prepare(SiteArgs.fromJson(siteJson));
            } catch (Throwable error) {
                throw new RemoteException(error.getMessage() == null
                        ? error.toString() : error.getMessage());
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("TVBoxSpider", "Spider process started: " + android.os.Process.myPid());
        SpiderResultTransport.clear(this);
        try {
            proxyServer = SpiderProxyServer.start(SpiderEngine.get());
        } catch (Throwable error) {
            Log.e("TVBoxSpider", "Unable to start proxy server", error);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        SpiderResultTransport.clear(this);
        if (proxyServer != null) {
            proxyServer.stop();
            proxyServer = null;
        }
        SpiderEngine.get().reset();
        SpiderDialogBridge.uninstall();
        super.onDestroy();
    }
}
