package com.locatube.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ProfileActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_CREATE = "create";
    public static final String MODE_CHOOSE = "choose";
    public static final String MODE_MANAGE = "manage";

    private final List<String[]> profils = new ArrayList<>();
    private LinearLayout container;
    private EditText input;
    private Button createBtn;
    private String mode;
    private String baseApi;

    private static final int[] COULEURS_OFFLINE = {
            0xFFE50914, 0xFF1CE783, 0xFF564DFF,
            0xFFFFD54F, 0xFF4FC3F7, 0xFFAB47BC,
            0xFFFF7043, 0xFFB81D24, 0xFFFF6B6B,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) {
            mode = MODE_CHOOSE;
        }

        TextView titre = findViewById(R.id.profil_titre);
        TextView sousTitre = findViewById(R.id.profile_sous_titre);
        container = findViewById(R.id.profile_container);
        input = findViewById(R.id.profile_input_always);
        createBtn = findViewById(R.id.profile_create_btn_always);

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String srv = prefs.getString("server", "").trim();
        while (srv.endsWith("/")) {
            srv = srv.substring(0, srv.length() - 1);
        }
        baseApi = srv;

        createBtn.setOnClickListener(v -> creerProfil());
        input.setOnEditorActionListener((v, id, event) -> {
            creerProfil();
            return true;
        });

        findViewById(R.id.profile_ajouter_label).setOnClickListener(v -> {
            input.setVisibility(View.VISIBLE);
            createBtn.setVisibility(View.VISIBLE);
            input.requestFocus();
        });

        if (mode.equals(MODE_CREATE)) {
            titre.setText(R.string.profile_title_first);
            sousTitre.setText(R.string.profile_subtitle_first);
            sousTitre.setVisibility(View.VISIBLE);
            container.setVisibility(View.GONE);
            input.setVisibility(View.VISIBLE);
            createBtn.setVisibility(View.VISIBLE);
            findViewById(R.id.profile_ajouter_label).setVisibility(View.GONE);
        } else if (mode.equals(MODE_MANAGE)) {
            titre.setText(R.string.profile_manage);
            sousTitre.setVisibility(View.GONE);
            input.setVisibility(View.GONE);
            createBtn.setVisibility(View.GONE);
            findViewById(R.id.profile_ajouter_label).setVisibility(View.VISIBLE);
        } else {
            titre.setText(R.string.profile_title);
            sousTitre.setVisibility(View.VISIBLE);
            input.setVisibility(View.GONE);
            createBtn.setVisibility(View.GONE);
            findViewById(R.id.profile_ajouter_label).setVisibility(View.GONE);
        }

        chargerProfils();
    }

    private void chargerProfils() {
        profils.clear();
        if (baseApi == null || baseApi.isEmpty()) {
            afficherRonds();
            return;
        }
        new Thread(() -> {
            String body = null;
            try {
                body = Net.get(baseApi + "/api/profils");
            } catch (Exception ignored) {
            }
            final String reponse = body;
            runOnUiThread(() -> {
                if (reponse == null) {
                    afficherRonds();
                    return;
                }
                try {
                    JSONArray arr = new JSONObject(reponse).getJSONArray("profils");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String nom = o.getString("nom");
                        String couleur = o.optString("couleur", "#E50914");
                        profils.add(new String[]{nom, couleur});
                    }
                } catch (Exception ignored) {
                }
                afficherRonds();
                if (profils.isEmpty() && mode.equals(MODE_CHOOSE)) {
                    mode = MODE_CREATE;
                    TextView titre = findViewById(R.id.profil_titre);
                    titre.setText(R.string.profile_title_first);
                    findViewById(R.id.profile_sous_titre).setVisibility(View.VISIBLE);
                    container.setVisibility(View.GONE);
                    input.setVisibility(View.VISIBLE);
                    createBtn.setVisibility(View.VISIBLE);
                    findViewById(R.id.profile_ajouter_label).setVisibility(View.GONE);
                }
            });
        }).start();
    }

    private void afficherRonds() {
        container.removeAllViews();
        int dp = (int) getResources().getDisplayMetrics().density;
        int tailleRond = 80 * dp;
        int marge = 16 * dp;
        int parLigne = 3;

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String profilActif = prefs.getString(MainActivity.CURRENT_PROFILE, "");
        int profilActifIdx = 0;
        for (int j = 0; j < profils.size(); j++) {
            if (profils.get(j)[0].equals(profilActif)) {
                profilActifIdx = j;
                break;
            }
        }

        LinearLayout ligne = null;
        for (int i = 0; i < profils.size(); i++) {
            if (i % parLigne == 0) {
                ligne = new LinearLayout(this);
                ligne.setOrientation(LinearLayout.HORIZONTAL);
                ligne.setGravity(Gravity.CENTER_HORIZONTAL);
                container.addView(ligne);
            }

            final String nom = profils.get(i)[0];
            final String couleurHex = profils.get(i)[1];

            LinearLayout bloc = new LinearLayout(this);
            bloc.setOrientation(LinearLayout.VERTICAL);
            bloc.setGravity(Gravity.CENTER_HORIZONTAL);
            bloc.setPadding(marge, marge / 2, marge, marge / 2);
            bloc.setClickable(true);

            TextView rond = new TextView(this);
            rond.setText(lettreInitiale(nom));
            rond.setTextSize(28);
            rond.setTextColor(Color.WHITE);
            rond.setGravity(Gravity.CENTER);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor(couleurHex));
            if (i == profilActifIdx) {
                bg.setStroke(3 * dp, Color.WHITE);
            }
            rond.setBackground(bg);

            LinearLayout.LayoutParams rondParams = new LinearLayout.LayoutParams(tailleRond, tailleRond);
            rondParams.gravity = Gravity.CENTER_HORIZONTAL;
            rond.setLayoutParams(rondParams);

            bloc.addView(rond);

            TextView label = new TextView(this);
            label.setText(nom);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(0xFFCCCCCC);
            label.setTextSize(12);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);

            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            labelParams.gravity = Gravity.CENTER_HORIZONTAL;
            labelParams.topMargin = 6 * dp;
            label.setLayoutParams(labelParams);

            bloc.addView(label);

            bloc.setOnClickListener(v -> selectionner(nom, couleurHex));

            ligne.addView(bloc);
        }

        if (profils.isEmpty()) {
            container.setVisibility(View.GONE);
        } else {
            container.setVisibility(View.VISIBLE);
        }
    }

    private String lettreInitiale(String nom) {
        if (nom == null || nom.isEmpty()) return "?";
        return nom.substring(0, 1).toUpperCase();
    }

    private void creerProfil() {
        String nom = input.getText().toString().trim();
        if (nom.isEmpty()) {
            Toast.makeText(this, R.string.profile_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (baseApi == null || baseApi.isEmpty()) {
            int idx = profils.size();
            String hex = String.format("#%06X", 0xFFFFFF & COULEURS_OFFLINE[idx % COULEURS_OFFLINE.length]);
            enregistrerLocalement(nom, hex);
            return;
        }
        final String params = "nom=" + android.net.Uri.encode(nom);
        new Thread(() -> {
            String reponse = null;
            String erreur = null;
            try {
                reponse = Net.post(baseApi + "/api/profils", params);
            } catch (Exception e) {
                erreur = e.getMessage();
            }
            final String rep = reponse;
            final String err = erreur;
            runOnUiThread(() -> {
                if (err == null) {
                    String hex = "#E50914";
                    try {
                        JSONObject obj = new JSONObject(rep);
                        hex = obj.optString("couleur", hex);
                    } catch (Exception ignored) {
                    }
                    enregistrerLocalement(nom, hex);
                } else {
                    Toast.makeText(this,
                            getString(R.string.error_prefix) + " " + err,
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void selectionner(String nom, String couleur) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(MainActivity.CURRENT_PROFILE, nom)
                .putString(MainActivity.CURRENT_PROFILE_COLOR, couleur)
                .apply();
        setResult(RESULT_OK);
        finish();
    }

    private void enregistrerLocalement(String nom, String couleur) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(MainActivity.CURRENT_PROFILE, nom)
                .putString(MainActivity.CURRENT_PROFILE_COLOR, couleur)
                .apply();
        Toast.makeText(this,
                getString(R.string.profile_welcome, nom),
                Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
