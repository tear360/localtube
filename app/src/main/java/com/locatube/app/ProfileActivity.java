package com.locatube.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
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

    private final List<String> profils = new ArrayList<>();
    private GridLayout grid;
    private EditText input;
    private Button createBtn;
    private String mode;
    private String baseApi;

    private static final int[] COULEURS = {
            0xFFE50914, 0xFFB81D24, 0xFF1CE783,
            0xFF564DFF, 0xFFFF6B6B, 0xFFFFD54F,
            0xFF4FC3F7, 0xFFAB47BC, 0xFFFF7043,
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
        grid = findViewById(R.id.profile_grid);
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
            grid.setVisibility(View.GONE);
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
                        profils.add(arr.getString(i));
                    }
                } catch (Exception ignored) {
                }
                afficherRonds();
                if (profils.isEmpty() && mode.equals(MODE_CHOOSE)) {
                    mode = MODE_CREATE;
                    TextView titre = findViewById(R.id.profil_titre);
                    titre.setText(R.string.profile_title_first);
                    findViewById(R.id.profile_sous_titre).setVisibility(View.VISIBLE);
                    grid.setVisibility(View.GONE);
                    input.setVisibility(View.VISIBLE);
                    createBtn.setVisibility(View.VISIBLE);
                    findViewById(R.id.profile_ajouter_label).setVisibility(View.GONE);
                }
            });
        }).start();
    }

    private void afficherRonds() {
        grid.removeAllViews();
        int tailleRond = (int) (80 * getResources().getDisplayMetrics().density);
        int marge = (int) (16 * getResources().getDisplayMetrics().density);
        int tailleLettre = (int) (30 * getResources().getDisplayMetrics().density);

        for (int i = 0; i < profils.size(); i++) {
            final String nom = profils.get(i);

            TextView rond = new TextView(this);
            rond.setText(lettreInitiale(nom));
            rond.setTextSize(28);
            rond.setTextColor(Color.WHITE);
            rond.setGravity(Gravity.CENTER);
            rond.setWidth(tailleRond);
            rond.setHeight(tailleRond);
            rond.setClickable(true);
            rond.setFocusable(true);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(COULEURS[i % COULEURS.length]);
            rond.setBackground(bg);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = tailleRond;
            params.height = tailleRond;
            params.setMargins(marge, marge / 2, marge, marge / 2);
            rond.setLayoutParams(params);

            rond.setOnClickListener(v -> selectionner(nom));
            rond.setOnLongClickListener(v -> {
                supprimerProfil(nom);
                return true;
            });

            grid.addView(rond);

            TextView label = new TextView(this);
            label.setText(nom);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(getResources().getColor(android.R.color.white));
            label.setTextSize(12);
            label.setWidth(tailleRond);
            label.setSingleLine(true);

            GridLayout.LayoutParams lparams = new GridLayout.LayoutParams();
            lparams.width = tailleRond;
            lparams.setMargins(marge, 0, marge, 0);
            label.setLayoutParams(lparams);

            grid.addView(label);
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
            enregistrerLocalement(nom);
            return;
        }
        final String params = "nom=" + android.net.Uri.encode(nom);
        new Thread(() -> {
            String erreur = null;
            try {
                Net.post(baseApi + "/api/profils", params);
            } catch (Exception e) {
                erreur = e.getMessage();
            }
            final String err = erreur;
            runOnUiThread(() -> {
                if (err == null) {
                    enregistrerLocalement(nom);
                } else {
                    Toast.makeText(this,
                            getString(R.string.error_prefix) + " " + err,
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void selectionner(String nom) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(MainActivity.CURRENT_PROFILE, nom).apply();
        setResult(RESULT_OK);
        finish();
    }

    private void supprimerProfil(String nom) {
        profils.remove(nom);
        afficherRonds();
    }

    private void enregistrerLocalement(String nom) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(MainActivity.CURRENT_PROFILE, nom).apply();
        Toast.makeText(this,
                getString(R.string.profile_welcome, nom),
                Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}
