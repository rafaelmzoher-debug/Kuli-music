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
import java.nio.file.*;
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
            Intent i = km.createConfirmDeviceCredentialIntent("Kuli Bóveda", "Confirma con tu huella o PIN para acceder");
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
    private Button botonTab(String label, String tabId) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(13); b.setPadding(dp(4), dp(8), dp(4), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); lp.setMargins(dp(2), 0, dp(2), 0); b.setLayoutParams(lp);
        b.setOnClickListener(v -> { tabActual = tabId; actualizarEstiloTabs(); refrescarLista(); }); return b;
    }
    private void actualizarEstiloTabs() {
        List<Button> all = Arrays.asList(btnTabClaves, btnTabRecordatorios, btnTabNotas, btnTabArchivos);
        List<String> ids = Arrays.asList("CLAVES", "AVISOS", "NOTAS", "ARCHIVOS");
        for (int i = 0; i < all.size(); i++) {
            boolean act = ids.get(i).equals(tabActual);
            all.get(i).setBackground(caja(act ? Color.parseColor("#D4AF37") : Color.parseColor("#131E36"), Color.parseColor("#263554"), 12));
            all.get(i).setTextColor(act ? Color.parseColor("#0B1220") : Color.parseColor("#CBD5E1"));
            all.get(i).setTypeface(null, act ? Typeface.BOLD : Typeface.NORMAL);
        }
    }
    private void iniciarDictadoVoz() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Di tu contraseña, recordatorio o nota...");
        try { startActivityForResult(i, REQ_VOZ); } catch (Exception e) { Toast.makeText(this, "Voz no disponible", Toast.LENGTH_SHORT).show(); }
    }
    private void procesarTextoVoz(String hablado) {
        String low = hablado.toLowerCase(Locale.ROOT); String cat = "NOTAS";
        if (low.contains("contraseña") || low.contains("clave") || low.contains("password") || low.contains("pin") || low.contains("usuario")) cat = "CLAVES";
        else if (low.contains("recuérdame") || low.contains("recordar") || low.contains("recordatorio") || low.contains("avísame") || low.contains("avisar") || low.contains("cita") || low.contains("mañana")) cat = "AVISOS";
        tabActual = cat; actualizarEstiloTabs();
        if ("AVISOS".equals(cat)) preguntarHoraAlarma(hablado);
        else { guardarItem(cat, hablado, ""); Toast.makeText(this, "Guardado en " + nombreCategoria(cat), Toast.LENGTH_SHORT).show(); refrescarLista(); }
    }
    private void preguntarHoraAlarma(String texto) {
        Calendar ahora = Calendar.getInstance();
        new TimePickerDialog(this, (v, h, m) -> {
            Calendar cal = Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m); cal.set(Calendar.SECOND, 0);
            if (cal.before(ahora)) cal.add(Calendar.DAY_OF_YEAR, 1);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, AlarmaReceiver.class); i.putExtra("texto", texto);
            PendingIntent pi = PendingIntent.getBroadcast(this, (int) (cal.getTimeInMillis() % 100000), i, PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            String hs = String.format(Locale.getDefault(), "%02d:%02d", h, m);
            guardarItem("AVISOS", "[" + hs + "] " + texto, "");
            Toast.makeText(this, "Aviso programado a las " + hs, Toast.LENGTH_LONG).show(); refrescarLista();
        }, ahora.get(Calendar.HOUR_OF_DAY), ahora.get(Calendar.MINUTE), true).show();
    }
    private void dialogoNuevoManual(String prefill) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(18), dp(12), dp(18), dp(8));
        final Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Arrays.asList("Contraseña / Clave", "Recordatorio con aviso", "Nota rápida")));
        if ("AVISOS".equals(tabActual)) sp.setSelection(1); else if ("NOTAS".equals(tabActual)) sp.setSelection(2);
        box.addView(sp);
        final EditText et = new EditText(this); et.setHint("Escribe aquí..."); et.setText(prefill); et.setMinLines(3); box.addView(et);
        new AlertDialog.Builder(this).setTitle("Nuevo en Kuli Bóveda").setView(box).setPositiveButton("Guardar", (d, w) -> {
            String txt = et.getText().toString().trim(); if (txt.isEmpty()) return;
            int sel = sp.getSelectedItemPosition(); String cat = sel == 0 ? "CLAVES" : (sel == 1 ? "AVISOS" : "NOTAS");
            tabActual = cat; actualizarEstiloTabs();
            if ("AVISOS".equals(cat)) preguntarHoraAlarma(txt); else { guardarItem(cat, txt, ""); refrescarLista(); }
        }).setNegativeButton("Cancelar", null).show();
    }
    private void abrirSelectorArchivo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        startActivityForResult(i, REQ_ARCHIVO);
    }
    private void importarArchivoInterno(Uri uri) {
        try {
            String nombre = "doc_" + System.currentTimeMillis();
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) { int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0) nombre = c.getString(idx); }
            }
            File dir = new File(getFilesDir(), "boveda_docs"); if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, System.currentTimeMillis() + "_" + nombre);
            try (InputStream in = getContentResolver().openInputStream(uri)) { Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            tabActual = "ARCHIVOS"; actualizarEstiloTabs(); guardarItem("ARCHIVOS", nombre, dest.getAbsolutePath());
            Toast.makeText(this, "Archivo guardado", Toast.LENGTH_SHORT).show(); refrescarLista();
        } catch (Exception e) { Toast.makeText(this, "Error al guardar", Toast.LENGTH_SHORT).show(); }
    }
    private void guardarItem(String cat, String texto, String ruta) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("items", "[]")); JSONObject obj = new JSONObject();
            obj.put("id", System.currentTimeMillis()); obj.put("cat", cat); obj.put("texto", texto); obj.put("ruta", ruta);
            obj.put("fecha", new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date()));
            arr.put(obj); prefs.edit().putString("items", arr.toString()).apply();
        } catch (Exception ignored) {}
    }
    private void borrarItem(long id) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("items", "[]")); JSONArray nuevo = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.getLong("id") != id) nuevo.put(o);
                else { String ruta = o.optString("ruta", ""); if (!ruta.isEmpty()) new File(ruta).delete(); }
            }
            prefs.edit().putString("items", nuevo.toString()).apply(); refrescarLista();
        } catch (Exception ignored) {}
    }
    private void refrescarLista() {
        listaContenedor.removeAllViews();
        if (!desbloqueado) {
            TextView lm = new TextView(this); lm.setText("Bóveda bloqueada. Toca aquí para desbloquear.");
            lm.setTextColor(Color.parseColor("#F5D061")); lm.setTextSize(16); lm.setPadding(dp(20), dp(40), dp(20), dp(40)); lm.setGravity(Gravity.CENTER);
            lm.setOnClickListener(v -> pedirDesbloqueoSamsung()); listaContenedor.addView(lm); return;
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
                bD.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Borrar?").setMessage(texto).setPositiveButton("Borrar", (d, w) -> borrarItem(id)).setNegativeButton("Cancelar", null).show());
                acc.addView(bD); card.addView(acc); listaContenedor.addView(card);
            }
        } catch (Exception ignored) {}
        if (count == 0) {
            TextView v = new TextView(this); v.setText("Pulsa HABLAR PARA GUARDAR abajo y dicta tu clave, aviso o nota.");
            v.setTextColor(Color.parseColor("#94A3B8")); v.setTextSize(15); v.setGravity(Gravity.CENTER); v.setPadding(dp(20), dp(40), dp(20), dp(40)); listaContenedor.addView(v);
        }
    }
    private void verArchivoGuardado(String nombre, String ruta) {
        File f = new File(ruta); if (!f.exists()) return;
        if (nombre.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            try {
                String txt = new String(Files.readAllBytes(f.toPath()), "UTF-8");
                ScrollView sv = new ScrollView(this); TextView tv = new TextView(this); tv.setText(txt); tv.setTextSize(15); tv.setPadding(dp(16), dp(12), dp(16), dp(12)); sv.addView(tv);
                new AlertDialog.Builder(this).setTitle(nombre).setView(sv).setPositiveButton("Cerrar", null).show();
            } catch (Exception ignored) {}
        } else Toast.makeText(this, "Guardado: " + nombre + " (" + (f.length() / 1024) + " KB)", Toast.LENGTH_LONG).show();
    }
    private String nombreCategoria(String c) { return "CLAVES".equals(c) ? "Clave" : ("AVISOS".equals(c) ? "Aviso" : ("ARCHIVOS".equals(c) ? "Archivo" : "Nota")); }
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
