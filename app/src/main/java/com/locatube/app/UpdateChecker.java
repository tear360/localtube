package com.locatube.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class UpdateChecker {

    public interface Callback {
        void onUpdateFound(int version, String apkUrl);

        void onUpToDate();

        void onError(String message);
    }

    private static final String LATEST =
            "https://api.github.com/repos/tear360/localtube/releases/latest";

    private UpdateChecker() {
    }

    public static void check(final Activity act) {
        check(act, new Callback() {
            @Override
            public void onUpdateFound(final int version, final String apkUrl) {
                new AlertDialog.Builder(act)
                        .setTitle(R.string.update_title)
                        .setMessage(act.getString(R.string.update_message, version))
                        .setPositiveButton(R.string.update_install,
                                (d, w) -> downloadAndInstall(act, apkUrl))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }

            @Override
            public void onUpToDate() {
            }

            @Override
            public void onError(String message) {
            }
        });
    }

    public static void check(final Activity act, final Callback cb) {
        new Thread(() -> {
            try {
                PackageInfo pi = act.getPackageManager()
                        .getPackageInfo(act.getPackageName(), 0);
                final int mine = pi.versionCode;

                String body;
                try {
                    body = Net.get(LATEST);
                } catch (IOException e) {
                    if (String.valueOf(e.getMessage()).contains("404")) {
                        post(act, () -> cb.onUpToDate());
                    } else {
                        throw e;
                    }
                    return;
                }

                JSONObject rel = new JSONObject(body);
                final int latest = parseTag(rel.optString("tag_name", ""));

                String url = "";
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String nom = a.optString("name", "");
                        if (nom.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            url = a.optString("browser_download_url", "");
                            break;
                        }
                    }
                }

                final String apkUrl = url;
                post(act, () -> {
                    if (latest > mine && !apkUrl.isEmpty()) {
                        cb.onUpdateFound(latest, apkUrl);
                    } else {
                        cb.onUpToDate();
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage();
                post(act, () -> cb.onError(msg));
            }
        }).start();
    }

    private static int parseTag(String tag) {
        String chiffres = tag.replaceAll("[^0-9]", "");
        if (chiffres.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(chiffres);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void post(Activity act, Runnable r) {
        act.runOnUiThread(r);
    }

    private static void downloadAndInstall(final Activity act, final String url) {
        File dir = act.getExternalFilesDir(null);
        if (dir == null) {
            Toast.makeText(act, R.string.install_fail, Toast.LENGTH_LONG).show();
            return;
        }
        final File dest = new File(dir, "maj.apk");

        final ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle(R.string.update_title);
        pd.setMessage(act.getString(R.string.update_downloading));
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "LocalTube");
                int len = c.getContentLength();

                InputStream in = new BufferedInputStream(c.getInputStream());
                FileOutputStream out = new FileOutputStream(dest);
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                if (len > 0) {
                    post(act, () -> {
                        pd.setIndeterminate(false);
                        pd.setMax(len);
                    });
                }
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (len > 0) {
                        final int p = (int) total;
                        post(act, () -> pd.setProgress(p));
                    }
                }
                out.close();
                in.close();
                c.disconnect();

                post(act, () -> {
                    pd.dismiss();
                    lancerInstallation(act, dest);
                });
            } catch (Exception e) {
                post(act, () -> {
                    pd.dismiss();
                    dest.delete();
                    Toast.makeText(act,
                            act.getString(R.string.error_prefix) + " " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static void lancerInstallation(Activity act, File f) {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        if (Build.VERSION.SDK_INT >= 24) {
            Uri uri = Uri.parse("content://com.locatube.app.apkfiles/" + f.getName());
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
        }
        try {
            act.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(act, R.string.install_fail, Toast.LENGTH_LONG).show();
        }
    }
}
