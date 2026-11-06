package com.ch4nhm.tvapp.spider;

import android.util.Log;

import com.github.catvod.Proxy;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Hosts the TVBox proxy ABI in the isolated spider process.
 */
final class SpiderProxyServer extends NanoHTTPD {

    private static final String TAG = "TVBoxSpider";
    private final SpiderEngine engine;

    private SpiderProxyServer(int port, SpiderEngine engine) {
        super(port);
        this.engine = engine;
    }

    static SpiderProxyServer start(SpiderEngine engine) {
        for (int port = 9978; port < 9999; port++) {
            SpiderProxyServer server = new SpiderProxyServer(port, engine);
            try {
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                Proxy.set(port);
                Log.i(TAG, "Spider proxy listening on " + port);
                return server;
            } catch (Throwable error) {
                Log.w(TAG, "Proxy port unavailable: " + port, error);
                try {
                    server.stop();
                } catch (Throwable ignored) {
                }
            }
        }
        throw new IllegalStateException("Unable to start the TVBox proxy server");
    }

    int getPort() {
        return getListeningPort();
    }

    String read(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null && !query.isBlank()) {
            for (String value : query.split("&")) {
                int separator = value.indexOf('=');
                String key = separator < 0 ? value : value.substring(0, separator);
                String item = separator < 0 ? "" : value.substring(separator + 1);
                try {
                    params.put(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(item, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException ignored) {
                    params.put(key, item);
                }
            }
        }
        try {
            Log.i(TAG, "Internal proxy query: " + params.keySet());
            Object[] result = engine.proxy(params);
            if (result == null || result.length < 3 || !(result[2] instanceof InputStream stream)) {
                return "";
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
            stream.close();
            return output.toString(StandardCharsets.UTF_8);
        } catch (Throwable error) {
            Log.e(TAG, "Internal proxy read failed", error);
            throw new IllegalStateException(error.getMessage() == null
                    ? error.toString() : error.getMessage(), error);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri() == null ? "" : session.getUri();
        if ("/health".equals(uri)) return newFixedLengthResponse(
                Response.Status.OK, MIME_PLAINTEXT, "{\"state\":\"ok\"}");
        if (!uri.startsWith("/proxy")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found");
        }
        try {
            Map<String, String> params = new HashMap<>(session.getParms());
            Map<String, String> headers = session.getHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if (key != null && !params.containsKey(key)) params.put(key, entry.getValue());
                }
            }
            Object[] result = engine.proxy(params);
            return response(result);
        } catch (Throwable error) {
            Log.e(TAG, "Proxy request failed: " + uri, error);
            Response response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, rootMessage(error));
            response.addHeader("X-TV-Proxy-Error", "1");
            return response;
        }
    }

    private Response response(Object[] values) {
        if (values == null || values.length == 0) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Invalid proxy response");
        }
        if (values[0] instanceof Response response) return response;
        if (values.length < 3) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Invalid proxy response");
        }
        int status = values[0] instanceof Number number ? number.intValue() : 500;
        String mime = values[1] instanceof String value ? value : MIME_PLAINTEXT;
        InputStream stream = values[2] instanceof InputStream input ? input : null;
        if (stream == null) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT, "Proxy response has no stream");
        }
        Response response = newChunkedResponse(status(status), mime, stream);
        if (values.length > 3 && values[3] instanceof Map<?, ?> headers) {
            for (Map.Entry<?, ?> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                String name = String.valueOf(entry.getKey());
                if (!name.equalsIgnoreCase("Content-Length")
                        && !name.equalsIgnoreCase("Transfer-Encoding")) {
                    response.addHeader(name, String.valueOf(entry.getValue()));
                }
            }
        }
        return response;
    }

    private static Response.Status status(int code) {
        Response.Status value = Response.Status.lookup(code);
        return value == null ? Response.Status.INTERNAL_ERROR : value;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
