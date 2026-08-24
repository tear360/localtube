package com.locatube.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class Net {

    private Net() {
    }

    static String get(String spec) throws IOException {
        return new String(getBytes(spec), "UTF-8");
    }

    static byte[] getBytes(String spec) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "LocalTube");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                in.close();
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
