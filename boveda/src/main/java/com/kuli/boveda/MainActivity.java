package com.kuli.boveda;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    private static final int REQ_VOZ = 101, REQ_ARCHIVO = 102, REQ_AUTH = 103;
    private String tabActual = "CLAVES";
    private boolean desbloqueado = false;
    private LinearLayout listaContenedor;
    private EditText inputBusqueda;
    private Button btnTabClaves, btnTabRecordatorios, btnTabNotas, btnTabArchivos;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("kuli_boveda_db", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO}, 1);
        }
        construirUI();
        pedirDesbloqueoSamsung();
    }

    private void pedirDesbloqueoSamsung() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardSecure()) {
            Intent i = km.createConfirmDeviceCredentialIntent("🔐 Kuli Bóveda", "Confirma con tu huella o PIN para acceder");
            if (i != null) { startActivityForResult(i, REQ_AUTH); return; }
        }
        desbloqueado = true; refrescarLista();
    }

    private void construirUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B1220"));
        root.setPadding(dp(14), dp(14), dp(14), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(2), dp(4), dp(10));
        TextView titulo = new TextView(this);
        titulo.setText("🛡️ KULI BÓVEDA");
        titulo.setTextColor(Color.parseColor("#F5D061"));
        titulo.setTextSize(21);
        titulo.setTypeface(null, Typeface.BOLD);
        header.addView(titulo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnImportar = botonPill("📂 Guardar TXT/PDF", "#1E293B", "#F8FAFC", 13);
        btnImportar.setOnClickListener(v -> abrirSelectorArchivo());
        header.addView(btnImportar);
        root.addView(header);

        inputBusqueda = new EditText(this);
        inputBusqueda.setHint("🔍 Buscar contraseña, nota o archivo...");
        inputBusqueda.setHintTextColor(Color.parseColor("#64748B"));
        inputBusqueda.setTextColor(Color.WHITE);
        inputBusqueda.setTextSize(15);
        inputBusqueda.setPadding(dp(14), dp(10), dp(14), dp(10));
        inputBusqueda.setBackground(caja(Color.parseColor("#131E36"), Color.parseColor("#263554"), 14));
        inputBusqueda.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { refrescarLista(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        LinearLayout.LayoutParams lpBus = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpBus.bottomMargin = dp(10);
        root.addView(inputBusqueda, lpBus);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        btnTabClaves = botonTab("🔐 Claves", "CLAVES");
        btnTabRecordatorios = botonTab("⏰ Avisos", "AVISOS");
        btnTabNotas = botonTab("📝 Notas", "NOTAS");
        btnTabArchivos = botonTab("📄 Archivos", "ARCHIVOS");
        tabs.addView(btnTabClaves); tabs.addView(btnTabRecordatorios); tabs.addView(btnTabNotas); tabs.addView(btnTabArchivos);
        LinearLayout.LayoutParams lpTabs = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpTabs.bottomMargin = dp(10);
        root.addView(tabs, lpTabs);

        ScrollView scroll = new ScrollView(this);
        listaContenedor = new LinearLayout(this);
        listaContenedor.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listaContenedor);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout barraInf = new LinearLayout(this);
        barraInf.setOrientation(LinearLayout.HORIZONTAL);
        barraInf.setPadding(0, dp(10), 0, 0);
        Button btnVoz = botonPill("🎙️ HABLAR PARA GUARDAR", "#D4AF37", "#0B1220", 16);
        btnVoz.setTypeface(null, Typeface.BOLD);
        btnVoz.setPadding(dp(16), dp(14), dp(16), dp(14));
        btnVoz.setOnClickListener(v -> iniciarDictadoVoz());
        LinearLayout.LayoutParams lpVoz = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpVoz.rightMargin = dp(8);
        barraInf.addView(btnVoz, lpVoz);

        Button btnManual = botonPill("✏️ Escribir", "#1E293B", "#F5D061", 15);
        btnManual.setPadding(dp(16), dp(14), dp(16), dp(14));
        btnManual.setOnClickListener(v -> dialogoNuevoManual(""));
        barraInf.addView(btnManual);
        root.addView(barraInf);
        setContentView(root);
        actualizarEstiloTabs();
    }
                java.nio.file.Files.copy(in, dest.toPath());
        }
        String filtro = inputBusqueda.getText().toString().trim().toLowerCase(Locale.ROOT); int count = 0;
        try {
            JSONArray arr = new JSONArray(prefs.getString("items", "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i); String cat = o.getString("cat"), texto = o.getString("texto"), fecha = o.optString("fecha", ""), ruta = o.optString("ruta", ""); long id = o.getLong("id");
                if (filtro.isEmpty() && !cat.equals(tabActual)) continue;
                if (!filtro.isEmpty() && !texto.toLowerCase(Locale.ROOT).contains(filtro)) continue;
                count++;
                LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14), dp(12), dp(14), dp(12));
                card.setBackground(caja(Color.parseColor("#131E36"), Color.parseColor("#263554"), 14));
                LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lpC.bottomMargin = dp(8); card.setLayoutParams(lpC);
                TextView tvF = new TextView(this); tvF.setText(nombreCategoria(cat) + " · " + fecha); tvF.setTextColor(Color.parseColor("#94A3B8")); tvF.setTextSize(12); card.addView(tvF);
                TextView tvT = new TextView(this); tvT.setText(texto); tvT.setTextColor(Color.WHITE); tvT.setTextSize(16); tvT.setPadding(0, dp(4), 0, dp(8)); card.addView(tvT);
                LinearLayout acc = new LinearLayout(this); acc.setOrientation(LinearLayout.HORIZONTAL);
                if ("ARCHIVOS".equals(cat) && !ruta.isEmpty()) {
                    Button bV = botonPill("Abrir", "#D4AF37", "#0B1220", 13); bV.setOnClickListener(v -> verArchivoGuardado(texto, ruta)); acc.addView(bV);
                } else {
                    Button bC = botonPill("Copiar", "#1E293B", "#F5D061", 13);
                    bC.setOnClickListener(v -> { ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Boveda", texto)); Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show(); });
                    acc.addView(bC);
                }
                acc.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
                Button bD = botonPill("Borrar", "#2A1520", "#FCA5A5", 13);
                bD.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("¿Borrar?").setMessage(texto).setPositiveButton("Borrar", (d, w) -> borrarItem(id)).setNegativeButton("Cancelar", null).show());
                acc.addView(bD); card.addView(acc); listaContenedor.addView(card);
            }
        } catch (Exception ignored) {}
        if (count == 0) {
            TextView v = new TextView(this); v.setText("Pulsa HABLAR PARA GUARDAR abajo y dicta tu contraseña, recordatorio o nota.");
            v.setTextColor(Color.parseColor("#94A3B8")); v.setTextSize(15); v.setGravity(Gravity.CENTER); v.setPadding(dp(20), dp(40), dp(20), dp(40)); listaContenedor.addView(v);
        }
    }
    private void verArchivoGuardado(String nombre, String ruta) {
        File f = new File(ruta); if (!f.exists()) return;
        if (nombre.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                StringBuilder sb = new StringBuilder(); String l; while ((l = br.readLine()) != null) sb.append(l).append("\n");
                ScrollView sv = new ScrollView(this); TextView tv = new TextView(this); tv.setText(sb.toString()); tv.setTextSize(15); tv.setPadding(dp(16), dp(12), dp(16), dp(12)); sv.addView(tv);
                new AlertDialog.Builder(this).setTitle(nombre).setView(sv).setPositiveButton("Cerrar", null).show();
            } catch (Exception ignored) {}
        } else Toast.makeText(this, "Guardado: " + nombre + " (" + (f.length() / 1024) + " KB)", Toast.LENGTH_LONG).show();
    }
    private String nombreCategoria(String c) { return "CLAVES".equals(c) ? "Contraseña" : ("AVISOS".equals(c) ? "Recordatorio" : ("ARCHIVOS".equals(c) ? "Documento" : "Nota")); }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_AUTH) { if (res == RESULT_OK) desbloqueado = true; refrescarLista(); }
        else if (req == REQ_VOZ && res == RESULT_OK && data != null) { ArrayList<String> m = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS); if (m != null && !m.isEmpty()) procesarTextoVoz(m.get(0)); }
        else if (req == REQ_ARCHIVO && res == RESULT_OK && data != null && data.getData() != null) importarArchivoInterno(data.getData());
    }
    private Button botonPill(String txt, String bg, String fg, int sp) {
        Button b = new Button(this); b.setText(txt); b.setAllCaps(false); b.setTextSize(sp); b.setTextColor(Color.parseColor(fg));
        b.setPadding(dp(12), dp(8), dp(12), dp(8)); b.setBackground(caja(Color.parseColor(bg), Color.parseColor("#334155"), 12)); return b;
    }
    private GradientDrawable caja(int fill, int stroke, int radiusDp) {
        GradientDrawable gd = new GradientDrawable(); gd.setColor(fill); gd.setStroke(dp(1), stroke); gd.setCornerRadius(dp(radiusDp)); return gd;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
