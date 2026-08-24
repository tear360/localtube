package com.locatube.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

public class PlayerActivity extends Activity {

    private static final String PREF_POS = "pos_";
    private static final String LAST_REL = "last_rel";
    private static final String LAST_TITLE = "last_title";
    private static final String LAST_POS = "last_pos";
    private static final String LAST_DUR = "last_dur";

    private VideoView videoView;
    private SharedPreferences prefs;
    private String urlAbsolue;
    private String relatif;
    private String title;
    private boolean fini;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        urlAbsolue = getIntent().getStringExtra(MainActivity.EXTRA_URL);
        relatif = getIntent().getStringExtra(MainActivity.EXTRA_REL);
        title = getIntent().getStringExtra(MainActivity.EXTRA_TITLE);
        prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);

        if (getActionBar() != null) {
            getActionBar().setTitle(title);
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        videoView = findViewById(R.id.video_view);
        videoView.setKeepScreenOn(true);

        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);

        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(PlayerActivity.this, R.string.player_error, Toast.LENGTH_LONG).show();
            return true;
        });

        videoView.setOnPreparedListener(mp -> {
            int sauve = prefs.getInt(PREF_POS + relatif, 0);
            int duree = videoView.getDuration();
            if (sauve > 3000 && duree > 0 && sauve < duree - 8000) {
                videoView.seekTo(sauve);
                Toast.makeText(PlayerActivity.this,
                        getString(R.string.resume_toast, MainActivity.formatTime(sauve)),
                        Toast.LENGTH_SHORT).show();
            }
            videoView.start();
        });

        videoView.setOnCompletionListener(mp -> {
            fini = true;
            prefs.edit()
                    .remove(PREF_POS + relatif)
                    .remove(LAST_REL)
                    .apply();
            finish();
        });

        if (urlAbsolue != null) {
            videoView.setVideoURI(Uri.parse(urlAbsolue));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (fini || relatif == null || urlAbsolue == null) {
            return;
        }
        int pos = videoView.getCurrentPosition();
        if (pos <= 0) {
            return;
        }
        prefs.edit()
                .putInt(PREF_POS + relatif, pos)
                .putString(LAST_REL, relatif)
                .putString(LAST_TITLE, title)
                .putInt(LAST_POS, pos)
                .putInt(LAST_DUR, Math.max(videoView.getDuration(), 0))
                .apply();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
