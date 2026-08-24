package com.locatube.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_REL = "rel";
    public static final String EXTRA_TITLE = "title";

    public static final String PREFS = "locatube";
    private static final String PREF_SERVER = "server";

    private final List<Video> allVideos = new ArrayList<>();
    private final List<Video> shownVideos = new ArrayList<>();
    private static final ExecutorService THUMB_POOL = Executors.newFixedThreadPool(3);
    private LruCache<String, Bitmap> thumbMem;
    private File thumbDiskDir;
    private VideoAdapter adapter;
    private TextView emptyView;
    private String query = "";

    private View barreLecture;
    private TextView barreTitre;
    private TextView barreTemps;
    private ProgressBar barreProgress;
    private String barreRel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        thumbMem = new LruCache<String, Bitmap>(8 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };
        File externe = getExternalFilesDir(null);
        thumbDiskDir = new File(externe != null ? externe : getCacheDir(), "miniatures");

        EditText searchInput = findViewById(R.id.search_input);
        ListView listView = findViewById(R.id.list);
        emptyView = findViewById(R.id.empty);

        findViewById(R.id.btn_refresh).setOnClickListener(v -> fetchVideos());

        barreLecture = findViewById(R.id.barre_lecture);
        barreTitre = findViewById(R.id.barre_titre);
        barreTemps = findViewById(R.id.barre_temps);
        barreProgress = findViewById(R.id.barre_progress);
        barreLecture.setOnClickListener(v -> {
            if (!barreRel.isEmpty()) {
                ouvrirVideo(barreRel, barreTitre.getText().toString());
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            Video v = shownVideos.get(position);
            ouvrirVideo(v.url, v.title);
        });

        adapter = new VideoAdapter();
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });

        if (serverBase().isEmpty()) {
            promptForServer();
        } else {
            fetchVideos();
        }
        UpdateChecker.check(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        majBarreLecture();
    }

    private void ouvrirVideo(String relatif, String titre) {
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra(EXTRA_URL, serverBase() + relatif);
        it.putExtra(EXTRA_REL, relatif);
        it.putExtra(EXTRA_TITLE, titre);
        startActivity(it);
    }

    private void majBarreLecture() {
        SharedPreferences p = prefs();
        String rel = p.getString("last_rel", "");
        int pos = p.getInt("last_pos", 0);
        int dur = p.getInt("last_dur", 0);
        if (rel.isEmpty() || pos <= 0 || serverBase().isEmpty()) {
            barreRel = "";
            barreLecture.setVisibility(View.GONE);
            return;
        }
        barreRel = rel;
        barreTitre.setText(p.getString("last_title", ""));
        barreTemps.setText(formatTime(pos) + " / " + formatTime(dur));
        if (dur > 0) {
            barreProgress.setProgress((int) (pos * 100L / dur));
        } else {
            barreProgress.setProgress(0);
        }
        barreLecture.setVisibility(View.VISIBLE);
    }

    public static String formatTime(int ms) {
        int totalSec = ms / 1000;
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        int s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String serverBase() {
        String s = prefs().getString(PREF_SERVER, "").trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void promptForServer() {
        final EditText input = new EditText(this);
        input.setHint("http://192.168.1.50:8000");
        input.setSingleLine(true);
        input.setText(serverBase());

        FrameLayout container = new FrameLayout(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 3, pad, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.server_dialog_title)
                .setMessage(R.string.server_dialog_message)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    prefs().edit().putString(PREF_SERVER, input.getText().toString().trim()).apply();
                    fetchVideos();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void fetchVideos() {
        final String base = serverBase();
        if (base.isEmpty()) {
            promptForServer();
            return;
        }
        setTitle(R.string.loading);
        emptyView.setText(R.string.loading);
        new Thread(() -> {
            String body = null;
            Exception error = null;
            try {
                body = Net.get(base + "/api/videos");
            } catch (Exception e) {
                error = e;
            }
            if (body != null) {
                try {
                    JSONArray arr = new JSONObject(body).getJSONArray("videos");
                    List<Video> result = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        result.add(new Video(o.getString("title"), o.getString("url"),
                                o.optString("thumb", ""), o.optLong("size", 0)));
                    }
                    allVideos.clear();
                    allVideos.addAll(result);
                } catch (Exception e) {
                    error = e;
                }
            }
            final Exception err = error;
            runOnUiThread(() -> {
                if (err == null) {
                    emptyView.setText(R.string.empty_none);
                    setTitle(getString(R.string.app_name) + " \u2013 " + allVideos.size());
                } else {
                    setTitle(R.string.app_name);
                    emptyView.setText(R.string.empty_error);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.error_prefix) + " " + err.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        shownVideos.clear();
        for (Video v : allVideos) {
            if (query.isEmpty() || v.title.toLowerCase(Locale.ROOT).contains(query)) {
                shownVideos.add(v);
            }
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.menu_server);
        menu.add(0, 2, 0, R.string.menu_refresh);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            promptForServer();
            return true;
        }
        if (item.getItemId() == 2) {
            fetchVideos();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static class Video {
        final String title;
        final String url;
        final String thumb;
        final long size;

        Video(String title, String url, String thumb, long size) {
            this.title = title;
            this.url = url;
            this.thumb = thumb;
            this.size = size;
        }
    }

    private class VideoAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shownVideos.size();
        }

        @Override
        public Object getItem(int position) {
            return shownVideos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            Holder h;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_video, parent, false);
                h = new Holder();
                h.title = row.findViewById(R.id.item_title);
                h.sub = row.findViewById(R.id.item_sub);
                h.img = row.findViewById(R.id.item_thumb);
                row.setTag(h);
            } else {
                h = (Holder) row.getTag();
            }
            Video v = shownVideos.get(position);
            h.title.setText(v.title);
            h.sub.setText(formatSize(v.size));
            chargerMiniature(h, v.thumb);
            return row;
        }
    }

    private static class Holder {
        TextView title;
        TextView sub;
        ImageView img;
        String boundUrl;
    }

    private void chargerMiniature(final Holder h, final String url) {
        h.boundUrl = url;

        Bitmap mem = url.isEmpty() ? null : thumbMem.get(url);
        if (mem != null) {
            h.img.setImageBitmap(mem);
            return;
        }

        h.img.setImageResource(R.drawable.thumb_placeholder);

        final File fichierDisque = new File(thumbDiskDir, md5(url) + ".jpg");

        THUMB_POOL.execute(() -> {
            Bitmap b = null;
            if (fichierDisque.isFile()) {
                b = BitmapFactory.decodeFile(fichierDisque.getAbsolutePath());
                if (b != null) {
                    thumbMem.put(url, b);
                }
            }
            if (b == null) {
                try {
                    byte[] brut = Net.getBytes(serverBase() + url);
                    b = decoder(brut);
                    if (b != null) {
                        thumbDiskDir.mkdirs();
                        FileOutputStream out = new FileOutputStream(fichierDisque);
                        out.write(brut);
                        out.close();
                        thumbMem.put(url, b);
                    }
                } catch (Exception e) {
                    b = null;
                }
            }
            final Bitmap resultat = b;
            runOnUiThread(() -> {
                if (url.equals(h.boundUrl) && resultat != null) {
                    h.img.setImageBitmap(resultat);
                }
            });
        });
    }

    private static Bitmap decoder(byte[] brut) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(brut, 0, brut.length, o);
        int echantillon = 1;
        while (Math.max(o.outWidth, o.outHeight) / (echantillon * 2) >= 480) {
            echantillon *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = echantillon;
        return BitmapFactory.decodeByteArray(brut, 0, brut.length, o);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte octet : d) {
                sb.append(String.format("%02x", octet));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        float kb = bytes / 1024f;
        if (kb < 1024f) {
            return String.format(Locale.US, "%.0f Ko", kb);
        }
        float mb = kb / 1024f;
        if (mb < 1024f) {
            return String.format(Locale.US, "%.1f Mo", mb);
        }
        return String.format(Locale.US, "%.2f Go", mb / 1024f);
    }
}
