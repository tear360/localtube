package com.locatube.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
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
    private ArrayAdapter adapter;
    private EditText input;
    private String mode;
    private String baseApi;

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
        ListView liste = findViewById(R.id.profile_list);
        input = findViewById(R.id.profile_input);
        findViewById(R.id.profile_create).setOnClickListener(v -> creerProfil());

        input.setOnEditorActionListener((v, id, event) -> {
            creerProfil();
            return true;
        });

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String srv = prefs.getString("server", "").trim();
        while (srv.endsWith("/")) {
            srv = srv.substring(0, srv.length() - 1);
        }
        baseApi = srv;

        adapter = new ArrayAdapter();
        liste.setAdapter(adapter);
        liste.setOnItemClickListener((parent, view, position, id) -> {
            String nom = profils.get(position);
            selectionner(nom);
        });

        if (mode.equals(MODE_CREATE)) {
            titre.setText(R.string.profile_title_first);
            sousTitre.setText(R.string.profile_subtitle_first);
            input.setVisibility(View.VISIBLE);
            liste.setVisibility(View.GONE);
            sousTitre.setVisibility(View.GONE);
        } else if (mode.equals(MODE_MANAGE)) {
            titre.setText(R.string.profile_manage);
            sousTitre.setVisibility(View.GONE);
            input.setVisibility(View.VISIBLE);
            liste.setVisibility(View.VISIBLE);
        } else {
            titre.setText(R.string.profile_title);
            sousTitre.setVisibility(View.VISIBLE);
            input.setVisibility(View.GONE);
        }

        chargerProfils();
    }

    private void chargerProfils() {
        profils.clear();
        if (baseApi == null || baseApi.isEmpty()) {
            adapter.notifyDataSetChanged();
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
                    adapter.notifyDataSetChanged();
                    return;
                }
                try {
                    JSONArray arr = new JSONObject(reponse).getJSONArray("profils");
                    for (int i = 0; i < arr.length(); i++) {
                        profils.add(arr.getString(i));
                    }
                } catch (Exception ignored) {
                }
                adapter.notifyDataSetChanged();
                if (profils.isEmpty() && mode.equals(MODE_CHOOSE)) {
                    mode = MODE_CREATE;
                    TextView titre = findViewById(R.id.profil_titre);
                    titre.setText(R.string.profile_title_first);
                    findViewById(R.id.profile_sous_titre).setVisibility(View.GONE);
                    input.setVisibility(View.VISIBLE);
                    findViewById(R.id.profile_list).setVisibility(View.GONE);
                }
            });
        }).start();
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

    private void enregistrerLocalement(String nom) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(MainActivity.CURRENT_PROFILE, nom).apply();
        Toast.makeText(this,
                getString(R.string.profile_welcome, nom),
                Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void selectionner(String nom) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(MainActivity.CURRENT_PROFILE, nom).apply();
        setResult(RESULT_OK);
        finish();
    }

    private class ArrayAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return profils.size();
        }

        @Override
        public Object getItem(int position) {
            return profils.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(ProfileActivity.this);
                int pad = (int) (16 * getResources().getDisplayMetrics().density);
                tv.setPadding(pad, pad, pad, pad);
                tv.setTextSize(18);
                tv.setTextColor(getResources().getColor(android.R.color.white));
            }
            tv.setText(profils.get(position));
            return tv;
        }
    }
}
