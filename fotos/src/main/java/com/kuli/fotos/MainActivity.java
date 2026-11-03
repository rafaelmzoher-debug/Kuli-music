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
    private final int NEGRO = Color.rgb(9, 10, 15);
    private final int DORADO = Color.rgb(232, 190, 92);
    private final int GRIS = Color.rgb(150, 153, 168);

    private TextView estado, detalle;
    private ProgressBar barra;
    private Button copiar, auto;
    private boolean copiando = false;
    private final AtomicBoolean cancelar = new AtomicBoolean(false);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(NEGRO);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(22), dp(40), dp(22), dp(30));
        scroll.addView(p);

        p.addView(texto("📷", 56, DORADO, true), centro());
        p.addView(texto("KULI FOTOS", 26, Color.WHITE, true), centro());
        p.addView(texto("Copia de seguridad de tus fotos y vídeos al NAS", 13, GRIS, false), centro());

        estado = texto("Mirando tus fotos…", 18, Color.WHITE, true);
        p.addView(estado, margen(centro(), 36));
        detalle = texto("", 13, GRIS, false);
        p.addView(detalle, centro());
        barra = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barra.setMax(1);
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(-1, dp(10));
        lpB.topMargin = dp(16);
        p.addView(barra, lpB);

        copiar = boton("Copiar ahora", true);
        copiar.setOnClickListener(v -> alPulsarCopiar());
        p.addView(copiar, margen(new LinearLayout.LayoutParams(-1, dp(56)), 28));

        auto = boton("", false);
        auto.setOnClickListener(v -> alternarAuto());
        p.addView(auto, margen(new LinearLayout.LayoutParams(-1, dp(52)), 12));
        Button nas = boton("⚙  Dirección del NAS", false);
        nas.setOnClickListener(v -> ajustesNas());
        p.addView(nas, margen(new LinearLayout.LayoutParams(-1, dp(52)), 12));

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
    private void pedirPermiso() {
        if (!tienePermiso()) requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISO);
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
            int v = 0;
            for (CopiaFotos.Item it : l) if (it.video) v++;
            final int videos = v, fotos = l.size() - v;
            runOnUiThread(() -> {
                if (copiando || isDestroyed()) return;
                if (l.isEmpty()) {
                    estado.setText("✓ Todo copiado");
                    detalle.setText("No hay fotos ni vídeos nuevos");
                } else {
                    estado.setText(l.size() + " por copiar");
                    detalle.setText(fotos + " fotos y " + videos + " vídeos");
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
        copiar.setText("Copiar ahora");
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
            String t = r.copiadas + " copiadas";
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

    // ---------- Copia automática ----------
    private SharedPreferences prefs() { return getSharedPreferences("fotos", MODE_PRIVATE); }
    private void pintarAuto() {
        auto.setText(prefs().getBoolean("auto", false) ? "🔁  Copia automática: ACTIVADA" : "🔁  Copia automática: apagada");
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
    private void ajustesNas() {
        final EditText campo = new EditText(this);
        campo.setText(CopiaFotos.ip(this));
        campo.setSingleLine(true);
        campo.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Dirección del NAS")
            .setMessage("En casa: " + CopiaFotos.IP_DEFECTO)
            .setView(campo)
            .setPositiveButton("GUARDAR", (d, w) -> {
                String ip = campo.getText().toString().trim();
                if (ip.isEmpty()) ip = CopiaFotos.IP_DEFECTO;
                prefs().edit().putString("nas_ip", ip).apply();
                aviso("NAS: " + ip);
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
        b.setText(t); b.setAllCaps(false); b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(lleno ? NEGRO : DORADO);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(30));
        if (lleno) g.setColor(DORADO); else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), DORADO); }
        b.setBackground(g);
        return b;
    }
}
