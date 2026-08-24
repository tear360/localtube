package com.locatube.app;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

public class PlayerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        String url = getIntent().getStringExtra(MainActivity.EXTRA_URL);
        String title = getIntent().getStringExtra(MainActivity.EXTRA_TITLE);

        if (getActionBar() != null) {
            getActionBar().setTitle(title);
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        VideoView videoView = findViewById(R.id.video_view);
        videoView.setKeepScreenOn(true);

        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);

        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(PlayerActivity.this, R.string.player_error, Toast.LENGTH_LONG).show();
            return true;
        });

        if (url != null) {
            videoView.setVideoURI(Uri.parse(url));
            videoView.start();
        }
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
