package com.kuli.fotos;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int PERMISO = 300;
    private static final int TRABAJO = 7;
    private final int NEGRO = Color.rgb(8, 28, 56);
    private final int DORADO = Color.rgb(120, 175, 235);
    private final int GRIS = Color.rgb(150, 153, 168);

    private TextView estado, detalle;
    private ProgressBar barra;
    private Button copiar, auto;
    private boolean copiando = false;
    private final AtomicBoolean cancelar = new AtomicBoolean(false);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{android.graphics.Color.rgb(6, 22, 46), android.graphics.Color.rgb(13, 46, 88), android.graphics.Color.rgb(6, 20, 42)}));
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(22), dp(40), dp(22), dp(30));
        scroll.addView(p);

        p.addView(texto("📷", 56, DORADO, true), centro());
        p.addView(texto("KULI FOTOS", 26, Color.WHITE, true), centro());
        p.addView(texto("TUS RECUERDOS · SOLO PERSONAS · v2.0", 13, GRIS, false), centro());

        estado = texto("Mirando tus fotos…", 18, Color.WHITE, true);
        p.addView(estado, margen(centro(), 36));
        detalle = texto("", 13, GRIS, false);
        p.addView(detalle, centro());
        barra = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barra.setMax(1);
        LinearLayout.LayoutParams lpUnica = new LinearLayout.LayoutParams(-1, dp(10));
        lpUnica.topMargin = dp(16);
        p.addView(barra, lpUnica);

        copiar = boton("⬆️ Copiar personas", true);
        copiar.setOnClickListener(v -> alPulsarCopiar());
        p.addView(copiar, margen(new LinearLayout.LayoutParams(-1, dp(56)), 28));

        Button repasar = boton("🔄  Volver a revisar todo", false);
        repasar.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Volver a revisar todo")
            .setMessage("KULI FOTOS olvidará lo que ya copió y revisará todas las fotos y vídeos. Las que ya estén en el NAS no se repiten.")
            .setPositiveButton("REVISAR", (d, w) -> { CopiaFotos.olvidar(this); alPulsarCopiar(); })
            .setNegativeButton("CANCELAR", null).show());
        p.addView(repasar, margen(new LinearLayout.LayoutParams(-1, dp(52)), 12));

        Button galeria = boton("🖼  Ver fotos del NAS", true);
        galeria.setOnClickListener(v -> {
            if (!hayWifi()) { aviso("Necesitas la WiFi de casa (o la VPN)"); return; }
            startActivity(new android.content.Intent(this, GaleriaActivity.class));
        });
        LinearLayout.LayoutParams lpCol = new LinearLayout.LayoutParams(0, dp(48), 1f); lpCol.setMargins(6, 8, 6, 8);
        LinearLayout fila1 = new LinearLayout(this); fila1.setOrientation(LinearLayout.HORIZONTAL);
        fila1.addView(galeria, lpCol);
        Button ver = boton("📁 Carpetas NAS", false);
        ver.setOnClickListener(v -> verNas());
        fila1.addView(ver, lpCol); p.addView(fila1);
        auto = boton("", false);
        auto.setOnClickListener(v -> alternarAuto());
        LinearLayout fila2 = new LinearLayout(this); fila2.setOrientation(LinearLayout.HORIZONTAL);
        fila2.addView(auto, lpCol);
        Button nas = boton("⚙️ Ajustes NAS", false);
        nas.setOnClickListener(v -> ajustesNas());
fila2.addView(nas, lpCol); p.addView(fila2);
        LinearLayout filaExtraFotos = new LinearLayout(this); filaExtraFotos.setOrientation(LinearLayout.HORIZONTAL);
        Button btnElegida = boton("➕ Subir elegida", false);
        btnElegida.setOnClickListener(v -> elegirFotoManual());
        filaExtraFotos.addView(btnElegida, lpCol);
        Button btnMarco = boton("🎞️ Marco Digital", false);
        btnMarco.setOnClickListener(v -> iniciarMarcoDigital());
        filaExtraFotos.addView(btnMarco, lpCol);
        p.addView(filaExtraFotos);
        Button btnCapturas = boton("🧹 Limpiar capturas", false);
        btnCapturas.setOnClickListener(v -> abrirGestorCapturas());
        p.addView(btnCapturas);

        p.addView(texto("Se guardan en  FOTOS KULI / año / mes / origen\n(Camara, WhatsApp, Capturas…).\nLas que ya están copiadas no se repiten.", 12, GRIS, false),
                margen(centro(), 26));
        setContentView(scroll);
        pintarAuto();
        pedirPermiso();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!copiando) contarPendientes();
    }

    // ---------- Permiso y recuento ----------
    private boolean tienePermiso() {
        
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean puedeBorrar() {
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }
    private void pedirPermiso() {
        
        if (!tienePermiso() || !puedeBorrar()) requestPermissions(new String[]{
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISO);
    }
    @Override public void onRequestPermissionsResult(int codigo, String[] permisos, int[] r) {
        super.onRequestPermissionsResult(codigo, permisos, r);
        if (codigo == PERMISO) contarPendientes();
    }
    private void contarPendientes() {
        if (!tienePermiso()) {
            estado.setText("Falta el permiso para ver tus fotos");
            detalle.setText("Pulsa «Copiar ahora» para darlo");
            return;
        }
        new Thread(() -> {
            final ArrayList<CopiaFotos.Item> l = CopiaFotos.pendientes(this);
            final int enMovil = CopiaFotos.todas(this).size();
            int v = 0;
            for (CopiaFotos.Item it : l) if (it.video) v++;
            final int videos = v, fotos = l.size() - v;
            runOnUiThread(() -> {
                if (copiando || isDestroyed()) return;
                if (l.isEmpty()) {
                    estado.setText(enMovil == 0 ? "No veo fotos en el móvil" : "✓ Todo copiado");
                    detalle.setText(enMovil == 0 ? "Comprueba el permiso de fotos de la app"
                        : "Hay " + enMovil + " fotos y vídeos en el móvil, todos ya copiados");
                } else {
                    estado.setText(l.size() + " por copiar");
                    detalle.setText(fotos + " fotos y " + videos + " vídeos (en el móvil hay " + enMovil + ")");
                }
                barra.setProgress(0);
            });
        }).start();
    }

    // ---------- Copiar ----------
    private boolean hayWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
    }

    private void alPulsarCopiar() {
        if (copiando) { cancelar.set(true); copiar.setText("Parando…"); return; }
        if (!tienePermiso()) { pedirPermiso(); return; }
        if (!hayWifi()) { aviso("Necesitas la WiFi de casa (o la VPN)"); return; }
        copiando = true;
        cancelar.set(false);
        copiar.setText("Parar");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        estado.setText("Conectando con el NAS…");
        detalle.setText("Deja la pantalla encendida");
        new Thread(() -> {
            CopiaFotos.Resultado r = null;
            Throwable error = null;
            try {
                r = CopiaFotos.copiar(this, (hechas, total, nombre) -> runOnUiThread(() -> {
                    barra.setMax(Math.max(total, 1));
                    barra.setProgress(hechas);
                    if (hechas < total) {
                        estado.setText("Copiando " + (hechas + 1) + " de " + total);
                        detalle.setText(nombre);
                    }
                }), cancelar);
            } catch (Throwable e) { error = e; }
            final CopiaFotos.Resultado res = r;
            final Throwable err = error;
            runOnUiThread(() -> terminar(res, err));
        }).start();
    }

    private void terminar(CopiaFotos.Resultado r, Throwable error) {
        copiando = false;
        copiar.setText("⬆️ Copiar personas");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (isDestroyed()) return;
        if (error != null) {
            estado.setText("No se pudo conectar con el NAS");
            detalle.setText("");
            new AlertDialog.Builder(this).setTitle("No se pudo copiar")
                .setMessage(error.getClass().getSimpleName() + (error.getMessage() != null ? ": " + error.getMessage() : "")
                    + "\n\nComprueba que el NAS está encendido y que estás en la WiFi de casa.")
                .setPositiveButton("CERRAR", null).show();
        } else {
            String t = r.copiadas + " copiadas (" + r.personas + " personas, " + r.animales + " animales)";
            if (r.omitidas > 0) t += " · " + r.omitidas + " ya estaban";
            if (r.fallos > 0) t += " · " + r.fallos + " con fallo";
            estado.setText(cancelar.get() ? "Parado" : (r.abortado ? "Se cortó la conexión" : "✓ Terminado"));
            detalle.setText(t);
            if (r.fallos > 0 && !r.ultimoError.isEmpty())
                new AlertDialog.Builder(this).setTitle("Algunas no se copiaron")
                    .setMessage("Último error:\n" + r.ultimoError + "\n\nVuelve a pulsar «Copiar ahora» para reintentar.")
                    .setPositiveButton("CERRAR", null).show();
        }
        contarPendientesTras();
    }
    private void contarPendientesTras() {
        new Thread(() -> {
            final int n = CopiaFotos.pendientes(this).size();
            runOnUiThread(() -> { if (!copiando && !isDestroyed() && n > 0) detalle.append("\nQuedan " + n + " por copiar"); });
        }).start();
    }

    // ---------- Ver el NAS ----------
    private void verNas() {
        if (!hayWifi()) { aviso("Necesitas la WiFi de casa (o la VPN)"); return; }
        aviso("Mirando el NAS…");
        new Thread(() -> {
            String t; 
            try { t = CopiaFotos.resumenNas(this); }
            catch (Throwable e) { t = "No se pudo conectar con el NAS:\n" + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""); }
            final String texto = t;
            runOnUiThread(() -> { if (!isDestroyed()) new AlertDialog.Builder(this)
                .setTitle("📂 Lo que hay en el NAS").setMessage(texto).setPositiveButton("CERRAR", null).show(); });
        }).start();
    }

    // ---------- Liberar espacio ----------
    private void alPulsarLiberar() {
        if (copiando) { aviso("Espera a que termine la copia"); return; }
        if (!tienePermiso() || !puedeBorrar()) { pedirPermiso(); aviso("Concede el permiso y vuelve a pulsar"); return; }
        if (!hayWifi()) { aviso("Necesitas la WiFi de casa (o la VPN)"); return; }
        copiando = true;
        estado.setText("Comprobando en el NAS…");
        detalle.setText("Solo fotos con más de 30 días");
        new Thread(() -> {
            CopiaFotos.Borrables b = null;
            Throwable error = null;
            try { b = CopiaFotos.verificadas(this, 30); } catch (Throwable e) { error = e; }
            final CopiaFotos.Borrables res = b;
            final Throwable err = error;
            runOnUiThread(() -> {
                copiando = false;
                if (isDestroyed()) return;
                if (err != null) {
                    estado.setText("No se pudo conectar con el NAS");
                    detalle.setText("No se ha borrado nada");
                    return;
                }
                if (res.items.isEmpty()) {
                    estado.setText("Nada que borrar");
                    detalle.setText("No hay fotos con más de 30 días ya comprobadas en el NAS");
                    return;
                }
                contarPendientes();
                String gb = res.bytes >= 1073741824L
                    ? String.format(java.util.Locale.US, "%.1f GB", res.bytes / 1073741824.0)
                    : (res.bytes / 1048576L) + " MB";
                new AlertDialog.Builder(this).setTitle("🧹 Liberar espacio")
                    .setMessage("Puedo borrar del móvil " + res.fotos + " fotos y " + res.videos + " vídeos (" + gb
                        + ") con más de 30 días.\n\nYa he comprobado que cada una está en el NAS con el mismo tamaño."
                        + "\n\nBorrarlas del móvil no se puede deshacer. ¿Las borro?")
                    .setPositiveButton("BORRAR", (d, w) -> borrarAhora(res))
                    .setNegativeButton("CANCELAR", null).show();
            });
        }).start();
    }

    private void borrarAhora(final CopiaFotos.Borrables b) {
        copiando = true;
        estado.setText("Borrando del móvil…");
        detalle.setText("");
        new Thread(() -> {
            final int[] r = CopiaFotos.borrar(this, b.items);
            runOnUiThread(() -> {
                copiando = false;
                if (isDestroyed()) return;
                estado.setText("✓ " + r[0] + " borradas del móvil");
                detalle.setText(r[1] > 0 ? r[1] + " no se pudieron borrar (siguen en el móvil y en el NAS)" : "Siguen guardadas en el NAS");
                contarPendientes();
            });
        }).start();
    }

    // ---------- Copia automática ----------
    private SharedPreferences prefs() { return getSharedPreferences("fotos", MODE_PRIVATE); }
    private void pintarAuto() {
        auto.setText(prefs().getBoolean("auto", false) ? "🔁  Auto: ACTIVADA" : "🔁  Auto: apagada");
    }
    private void alternarAuto() {
        JobScheduler js = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        boolean activar = !prefs().getBoolean("auto", false);
        try {
            if (activar) {
                JobInfo info = new JobInfo.Builder(TRABAJO, new ComponentName(this, JobCopia.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(6L * 60 * 60 * 1000)
                    .setPersisted(true)
                    .build();
                js.schedule(info);
                aviso("Se copiará sola cuando haya WiFi");
            } else {
                js.cancel(TRABAJO);
                aviso("Copia automática apagada");
            }
            prefs().edit().putBoolean("auto", activar).apply();
        } catch (Exception e) { aviso("No se pudo cambiar: " + e.getMessage()); }
        pintarAuto();
    }

    // ---------- NAS ----------
    private void abrirGestorCapturas() {
        if (!tienePermiso()) { pedirPermiso(); return; }
        aviso("Buscando capturas...");
        new Thread(() -> {
            final java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<>();
            final java.util.ArrayList<String> nombres = new java.util.ArrayList<>();
            final java.util.ArrayList<android.graphics.Bitmap> minis = new java.util.ArrayList<>();
            String[] proj = new String[]{
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.provider.MediaStore.Images.Media.DATE_ADDED,
                android.provider.MediaStore.Images.Media.SIZE
            };
            try (android.database.Cursor c = getContentResolver().query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    proj, null, null, android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    int iId = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID);
                    int iNom = c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME);
                    int iRel = c.getColumnIndex(android.provider.MediaStore.Images.Media.RELATIVE_PATH);
                    int iFec = c.getColumnIndex(android.provider.MediaStore.Images.Media.DATE_ADDED);
                    int iTam = c.getColumnIndex(android.provider.MediaStore.Images.Media.SIZE);
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault());
                    while (c.moveToNext()) {
                        String nom = c.getString(iNom);
                        String rel = iRel >= 0 ? c.getString(iRel) : "";
                        String low = ((nom != null ? nom : "") + " " + (rel != null ? rel : "")).toLowerCase(java.util.Locale.US);
                        if (low.contains("screenshot") || low.contains("captura") || low.contains("screen_")) {
                            long id = c.getLong(iId);
                            android.net.Uri u = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                            long fecMs = (iFec >= 0 ? c.getLong(iFec) : 0L) * 1000L;
                            long kb = (iTam >= 0 ? c.getLong(iTam) : 0L) / 1024L;
                            uris.add(u);
                            nombres.add(sdf.format(new java.util.Date(fecMs)) + " · " + kb + " KB\n" + nom);
                            android.graphics.Bitmap bmp = null;
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    bmp = getContentResolver().loadThumbnail(u, new android.util.Size(120, 120), null);
                                }
                            } catch (Throwable ignored) {}
                            minis.add(bmp);
                        }
                    }
                }
            } catch (Throwable e) {
                runOnUiThread(() -> aviso("Error: " + e.getMessage()));
                return;
            }
            runOnUiThread(() -> {
                if (uris.isEmpty()) { aviso("No hay capturas de pantalla"); return; }
                android.widget.LinearLayout root = new android.widget.LinearLayout(this);
                root.setOrientation(android.widget.LinearLayout.VERTICAL);
                root.setPadding(dp(10), dp(6), dp(10), dp(6));
                android.widget.LinearLayout barra = new android.widget.LinearLayout(this);
                android.widget.Button btnTodas = new android.widget.Button(this);
                btnTodas.setText("Marcar todas");
                android.widget.Button btnNinguna = new android.widget.Button(this);
                btnNinguna.setText("Ninguna");
                barra.addView(btnTodas);
                barra.addView(btnNinguna);
                android.widget.Button btnGf = new android.widget.Button(this);
        btnGf.setText("🗑 Capturas");
        btnGf.setOnClickListener(v -> borrarCapturasMovil());
        barra.addView(btnGf);
                root.addView(barra);
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                android.widget.LinearLayout lista = new android.widget.LinearLayout(this);
                lista.setOrientation(android.widget.LinearLayout.VERTICAL);
                scroll.addView(lista);
                root.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, dp(350)));
                final java.util.ArrayList<android.widget.CheckBox> checks = new java.util.ArrayList<>();
                for (int i = 0; i < uris.size(); i++) {
                    android.widget.LinearLayout fila = new android.widget.LinearLayout(this);
                    fila.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    fila.setPadding(0, dp(5), 0, dp(5));
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    if (minis.get(i) != null) iv.setImageBitmap(minis.get(i));
                    fila.addView(iv, new android.widget.LinearLayout.LayoutParams(dp(52), dp(52)));
                    android.widget.CheckBox cb = new android.widget.CheckBox(this);
                    cb.setText(nombres.get(i));
                    cb.setTextSize(12f);
                    cb.setPadding(dp(8), 0, 0, 0);
                    checks.add(cb);
                    fila.addView(cb, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
                    lista.addView(fila);
                }
                btnTodas.setOnClickListener(v -> { for (android.widget.CheckBox c : checks) c.setChecked(true); });
                btnNinguna.setOnClickListener(v -> { for (android.widget.CheckBox c : checks) c.setChecked(false); });
                new AlertDialog.Builder(this)
                    .setTitle("Capturas (" + uris.size() + ") · Elige cuáles borrar")
                    .setView(root)
                    .setPositiveButton("BORRAR DEFINITIVAMENTE", (d, w) -> {
                        java.util.ArrayList<android.net.Uri> aBorrar = new java.util.ArrayList<>();
                        for (int i = 0; i < checks.size(); i++) if (checks.get(i).isChecked()) aBorrar.add(uris.get(i));
                        if (aBorrar.isEmpty()) { aviso("No marcaste ninguna"); return; }
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 30) {
                                android.app.PendingIntent pi = android.provider.MediaStore.createDeleteRequest(getContentResolver(), aBorrar);
                                startIntentSenderForResult(pi.getIntentSender(), 991, null, 0, 0, 0);
                            } else {
                                int b = 0;
                                for (android.net.Uri u : aBorrar) b += getContentResolver().delete(u, null, null);
                                aviso("Borradas: " + b);
                                contarPendientes();
                            }
                        } catch (Throwable e) { aviso("Error: " + e.getMessage()); }
                    })
                    .setNegativeButton("CERRAR", null).show();
            });
        }).start();
    }

    private void ajustesNas() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        box.setPadding(p, dp(8), p, dp(4));

        final EditText campo = new EditText(this);
        campo.setText(CopiaFotos.ip(this));
        campo.setSingleLine(true);
        campo.setSelectAllOnFocus(true);
        box.addView(campo);

        final android.widget.CheckBox chkMascotas = new android.widget.CheckBox(this);
        chkMascotas.setText("Separar animales en subcarpeta Mascotas");
        chkMascotas.setChecked(prefs().getBoolean("sep_mascotas", true));
        box.addView(chkMascotas);

        final android.widget.CheckBox chkWa = new android.widget.CheckBox(this);
        chkWa.setText("Incluir fotos/vídeos de WhatsApp y Telegram");
        chkWa.setChecked(prefs().getBoolean("inc_wa", false));
        box.addView(chkWa);

        new AlertDialog.Builder(this).setTitle("Ajustes NAS y Filtros")
                .setMessage("IP en casa: " + CopiaFotos.IP_DEFECTO)
                .setView(box)
                .setPositiveButton("GUARDAR", (d, w) -> {
                    String ip = campo.getText().toString().trim();
                    if (ip.isEmpty()) ip = CopiaFotos.IP_DEFECTO;
                    prefs().edit()
                        .putString("nas_ip", ip)
                        .putBoolean("sep_mascotas", chkMascotas.isChecked())
                        .putBoolean("inc_wa", chkWa.isChecked())
                        .apply();
                    contarPendientes();
                    aviso("Guardado · NAS: " + ip);
                })
                .setNegativeButton("CANCELAR", null).show();
    }

    // ---------- Ayudas de diseño ----------
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private void aviso(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private TextView texto(String s, int size, int color, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (negrita) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }
    private LinearLayout.LayoutParams centro() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams margen(LinearLayout.LayoutParams lp, int arriba) {
        lp.topMargin = dp(arriba); return lp;
    }
    private Button boton(String t, boolean lleno) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextSize(13); b.setSingleLine(true); b.setPadding(12, 0, 12, 0);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(lleno ? NEGRO : DORADO);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(30));
        if (lleno) g.setColor(DORADO); else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), DORADO); }
        b.setBackground(g);
        return b;
    }

    private static final int REQ_ELEGIR_FOTO = 7741;

    private void elegirFotoManual() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(android.content.Intent.createChooser(i, "Elige una foto para subir al NAS"), REQ_ELEGIR_FOTO);
        } catch (Exception e) {
            aviso("No se pudo abrir la galería");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ELEGIR_FOTO && res == RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri uri = data.getData();
            aviso("⬆️ Subiendo foto elegida al NAS...");
            new Thread(() -> {
                try {
                    String dia = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
                    String nom = "MANUAL_" + System.currentTimeMillis() + ".jpg";
                    String dir = CopiaFotos.CARPETA + "/" + dia;
                    java.io.File tmp = new java.io.File(getCacheDir(), nom);
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    java.io.File pubDir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "KULI FOTOS");
                    pubDir.mkdirs();
                    java.io.File dest = new java.io.File(pubDir, nom);
                    tmp.renameTo(dest);
                    android.media.MediaScannerConnection.scanFile(this, new String[]{dest.getAbsolutePath()}, null, null);
                    runOnUiThread(() -> aviso("✅ Guardada en " + dir + "/" + nom));
                } catch (Throwable t) {
                    runOnUiThread(() -> aviso("Error al subir: " + t.getMessage()));
                }
            }).start();
        }
    }

    private void iniciarMarcoDigital() {
        final java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<>();
        try (android.database.Cursor c = getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{android.provider.MediaStore.Images.Media._ID},
                null, null, android.provider.MediaStore.Images.Media.DATE_TAKEN + " DESC")) {
            if (c != null) {
                while (c.moveToNext() && uris.size() < 80) {
                    long id = c.getLong(0);
                    uris.add(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id));
                }
            }
        } catch (Throwable ignored) {}
        if (uris.isEmpty()) { aviso("No hay fotos para mostrar"); return; }
        final android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setBackgroundColor(android.graphics.Color.BLACK);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setMinimumHeight(dp(420));
        final int[] idx = new int[1];
        iv.setImageURI(uris.get(0));
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable r = new Runnable() {
            @Override public void run() {
                idx[0] = (idx[0] + 1) % uris.size();
                iv.setImageURI(uris.get(idx[0]));
                h.postDelayed(this, 4000);
            }
        };
        h.postDelayed(r, 4000);
        new AlertDialog.Builder(this)
            .setTitle("🎞️ Marco Digital · Recuerdos")
            .setView(iv)
            .setPositiveButton("Siguiente ⏭", (d, w) -> {})
            .setNegativeButton("Cerrar", (d, w) -> h.removeCallbacks(r))
            .setOnDismissListener(d -> h.removeCallbacks(r))
            .show();
    }


    private void borrarCapturasMovil() {
        java.util.ArrayList<android.net.Uri> caps = new java.util.ArrayList<>();
        try (android.database.Cursor c = getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[]{android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DATA},
                android.provider.MediaStore.Images.Media.DATA + " LIKE ?",
                new String[]{"%Screenshot%"}, null)) {
            while (c != null && c.moveToNext()) {
                caps.add(android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
            }
        } catch (Throwable ignored) {}
        if (caps.isEmpty()) { aviso("No hay capturas de pantalla en el móvil"); return; }
        new AlertDialog.Builder(this)
            .setTitle("🗑 Borrar capturas")
            .setMessage("Se han encontrado " + caps.size() + " capturas de pantalla en el móvil. ¿Quieres borrarlas todas?")
            .setPositiveButton("BORRAR TODAS", (d, w) -> {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try {
                        android.app.PendingIntent pi = android.provider.MediaStore.createDeleteRequest(getContentResolver(), caps);
                        startIntentSenderForResult(pi.getIntentSender(), 9555, null, 0, 0, 0);
                        return;
                    } catch (Throwable ignored) {}
                }
                int borradas = 0;
                for (android.net.Uri u : caps) {
                    try { if (getContentResolver().delete(u, null, null) > 0) borradas++; } catch (Throwable ignored) {}
                }
                aviso("Borradas " + borradas + " capturas");
            })
            .setNegativeButton("Cancelar", null).show();
    }

}
