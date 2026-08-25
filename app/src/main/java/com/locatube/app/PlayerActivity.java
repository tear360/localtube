package com.locatube.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONObject;

public class PlayerActivity extends Activity {

    private static final String PREF_POS = "pos_";
    private static final String LAST_REL = "last_rel";
    private static final String LAST_TITLE = "last_title";
    private static final String LAST_POS = "last_pos";
    private static final String LAST_DUR = "last_dur";
    private static final int INTERVALLE_TICK_MS = 10_000;

    private VideoView videoView;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String urlAbsolue;
    private String relatif;
    private String title;
    private String baseApi;
    private boolean fini;
    private boolean pret;
    private boolean bloquerAffiche;

    private LinearLayout overlay;
    private TextView blocTitre;
    private TextView blocMessage;
    private TextView blocStatut;
    private Button btnDemander;

    private final Runnable cycle = new Runnable() {
        @Override
        public void run() {
            if (fini || baseApi == null) {
                return;
            }
            if (!bloquerAffiche && videoView.isPlaying()) {
                envoyerTick(INTERVALLE_TICK_MS / 1000);
            } else if (bloquerAffiche) {
                verifierEtat();
            }
            handler.postDelayed(this, INTERVALLE_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        urlAbsolue = getIntent().getStringExtra(MainActivity.EXTRA_URL);
        relatif = getIntent().getStringExtra(MainActivity.EXTRA_REL);
        title = getIntent().getStringExtra(MainActivity.EXTRA_TITLE);
        prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);

        if (urlAbsolue != null && relatif != null && urlAbsolue.endsWith(relatif)) {
            baseApi = urlAbsolue.substring(0, urlAbsolue.length() - relatif.length());
        }

        if (getActionBar() != null) {
            getActionBar().setTitle(title);
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        videoView = findViewById(R.id.video_view);
        videoView.setKeepScreenOn(true);

        overlay = findViewById(R.id.blocage_overlay);
        blocTitre = findViewById(R.id.blocage_titre);
        blocMessage = findViewById(R.id.blocage_message);
        blocStatut = findViewById(R.id.blocage_statut);
        btnDemander = findViewById(R.id.btn_demander);

        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);

        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(PlayerActivity.this, R.string.player_error, Toast.LENGTH_LONG).show();
            return true;
        });

        btnDemander.setOnClickListener(v -> envoyerDemande());

        videoView.setOnPreparedListener(mp -> {
            pret = true;
            verifierAvantLecture();
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

    private void verifierAvantLecture() {
        if (baseApi == null) {
            demarrerLecture();
            return;
        }
        new Thread(() -> {
            JSONObject etat = null;
            try {
                etat = new JSONObject(Net.get(baseApi + "/api/parental"));
            } catch (Exception ignored) {
            }
            final JSONObject resultat = etat;
            runOnUiThread(() -> {
                if (fini) {
                    return;
                }
                if (resultat != null && resultat.optBoolean("bloque")) {
                    montrerBlocage(resultat.optString("raison"));
                } else {
                    demarrerLecture();
                }
            });
        }).start();
    }

    private void demarrerLecture() {
        int sauve = prefs.getInt(PREF_POS + relatif, 0);
        int duree = videoView.getDuration();
        if (sauve > 3000 && duree > 0 && sauve < duree - 8000) {
            videoView.seekTo(sauve);
            Toast.makeText(this,
                    getString(R.string.resume_toast, MainActivity.formatTime(sauve)),
                    Toast.LENGTH_SHORT).show();
        }
        videoView.start();
        handler.removeCallbacks(cycle);
        handler.postDelayed(cycle, INTERVALLE_TICK_MS);
    }

    private void envoyerTick(int secondes) {
        final String params = "sec=" + secondes
                + "&video=" + Uri.encode(title == null ? "" : title);
        new Thread(() -> {
            JSONObject etat = null;
            try {
                etat = new JSONObject(Net.post(baseApi + "/api/parental/tick", params));
            } catch (Exception ignored) {
            }
            final JSONObject resultat = etat;
            runOnUiThread(() -> {
                if (resultat != null) {
                    appliquerEtat(resultat);
                }
            });
        }).start();
    }

    private void verifierEtat() {
        new Thread(() -> {
            JSONObject etat = null;
            try {
                etat = new JSONObject(Net.get(baseApi + "/api/parental"));
            } catch (Exception ignored) {
            }
            final JSONObject resultat = etat;
            runOnUiThread(() -> {
                if (resultat != null) {
                    appliquerEtat(resultat);
                }
            });
        }).start();
    }

    private void appliquerEtat(JSONObject etat) {
        boolean bloque = etat.optBoolean("bloque");
        if (bloque && !bloquerAffiche) {
            montrerBlocage(etat.optString("raison"));
        } else if (!bloque && bloquerAffiche) {
            cacherBlocageEtRelancer();
        }
    }

    private void montrerBlocage(String raison) {
        bloquerAffiche = true;
        videoView.pause();
        boolean manuel = "manuel".equals(raison);
        blocTitre.setText(manuel ? R.string.blocage_manuel_titre : R.string.blocage_temps_titre);
        blocMessage.setText(manuel ? R.string.blocage_manuel_msg : R.string.blocage_temps_msg);
        blocStatut.setText("");
        btnDemander.setEnabled(true);
        btnDemander.setText(R.string.btn_demander);
        overlay.setVisibility(View.VISIBLE);
    }

    private void cacherBlocageEtRelancer() {
        bloquerAffiche = false;
        overlay.setVisibility(View.GONE);
        if (pret && !fini) {
            videoView.start();
        }
    }

    private void envoyerDemande() {
        btnDemander.setEnabled(false);
        btnDemander.setText(R.string.demande_envoi);
        blocStatut.setText("");
        final String params = "texte=" + Uri.encode(
                getString(R.string.demande_texte, title == null ? "" : title));
        new Thread(() -> {
            String erreur = null;
            try {
                Net.post(baseApi + "/api/parental/demande", params);
            } catch (Exception e) {
                erreur = e.getMessage();
            }
            final String err = erreur;
            runOnUiThread(() -> {
                if (fini) {
                    return;
                }
                if (err == null) {
                    blocStatut.setText(R.string.demande_envoyee);
                } else {
                    btnDemander.setEnabled(true);
                    btnDemander.setText(R.string.btn_demander);
                    blocStatut.setText(getString(R.string.error_prefix) + " " + err);
                }
            });
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!fini && relatif != null && urlAbsolue != null) {
            int pos = videoView.getCurrentPosition();
            if (pos > 0) {
                prefs.edit()
                        .putInt(PREF_POS + relatif, pos)
                        .putString(LAST_REL, relatif)
                        .putString(LAST_TITLE, title)
                        .putInt(LAST_POS, pos)
                        .putInt(LAST_DUR, Math.max(videoView.getDuration(), 0))
                        .apply();
            }
        }
        if (!fini && pret) {
            videoView.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pret && !fini && !bloquerAffiche) {
            videoView.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fini = true;
        handler.removeCallbacksAndMessages(null);
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
