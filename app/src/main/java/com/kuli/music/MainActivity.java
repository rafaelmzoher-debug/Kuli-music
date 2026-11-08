package com.kuli.music;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.content.Context;
import android.provider.OpenableColumns;
import android.database.Cursor;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.Map;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 100;
    private static final int PICK_SUBIR = 101;
    static MainActivity instancia;
    private static final String NAS_IP_DEFECTO = "192.168.1.46";
    private String nasIp() {
        String ip = getSharedPreferences("kuli_ajustes", MODE_PRIVATE).getString("nas_ip", NAS_IP_DEFECTO);
        return (ip == null || ip.trim().isEmpty()) ? NAS_IP_DEFECTO : ip.trim();
    }
    private void ajustesNas() {
        final android.widget.EditText campo = new android.widget.EditText(this);
        campo.setText(nasIp());
        campo.setSingleLine(true);
        campo.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
            .setTitle("Dirección del NAS")
            .setMessage("En casa: " + NAS_IP_DEFECTO + "\nFuera de casa con Tailscale: la IP 100.x.x.x del NAS")
            .setView(campo)
            .setPositiveButton("GUARDAR", (d, w) -> {
                String ip = campo.getText().toString().trim();
                if (ip.isEmpty()) ip = NAS_IP_DEFECTO;
                getSharedPreferences("kuli_ajustes", MODE_PRIVATE).edit().putString("nas_ip", ip).apply();
                aviso("NAS: " + ip);
            })
            .setNeutralButton("RESTAURAR", (d, w) -> {
                getSharedPreferences("kuli_ajustes", MODE_PRIVATE).edit().remove("nas_ip").apply();
                aviso("NAS: " + NAS_IP_DEFECTO);
            })
            .setNegativeButton("CANCELAR", null).show();
    }

    // Paleta: negro profundo, dorado y grises suaves.
    private final int NEGRO = Color.rgb(9, 10, 15);
    private final int FONDO_ALTO = Color.rgb(24, 22, 33);
    private final int DORADO = Color.rgb(232, 190, 92);
    private final int TARJETA = Color.rgb(26, 28, 37);
    private final int GRIS = Color.rgb(150, 153, 168);
    private final int LINEA = Color.argb(70, 232, 190, 92);

    private static final int LLENO = 0, CONTORNO = 1, APAGADO = 2;

    private MediaPlayer reproductor;
    private MediaPlayer siguienteReproductor;
    private int indicePreparado = -1;
    private ImageView portada;
    private TextView cancion, artista, estado, tiempoActual, tiempoTotal;
    private Button favorito, botonPlay, mezcla, bucle;
    private SeekBar barra;
    private boolean arrastrando = false;
    private Uri uriActual;
    private String tituloActual = "", artistaActual = "";
    private SharedPreferences favoritos;
    private final ArrayList<String> listaFavoritas = new ArrayList<>();
    private int posicionFavorita = -1;
    private boolean modoLista = false, aleatorio = false, repetir = true;
    private boolean esRadio = false;
    private Uri ultimaUri = null;
    private int posicionPendienteMs = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            actualizarProgreso();
            ui.postDelayed(this, 400);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        favoritos = getSharedPreferences("kuli_favoritos", MODE_PRIVATE);
        getWindow().setStatusBarColor(FONDO_ALTO);
        getWindow().setNavigationBarColor(NEGRO);

        float density = getResources().getDisplayMetrics().density;
        int altoDp = (int) (getResources().getDisplayMetrics().heightPixels / density);
        int anchoDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        int portadaDp = Math.max(120, Math.min(Math.min(300, anchoDp - 72), altoDp - 520));

        LinearLayout pantalla = new LinearLayout(this);
        pantalla.setOrientation(LinearLayout.VERTICAL);
        pantalla.setGravity(Gravity.CENTER_HORIZONTAL);
        pantalla.setPadding(dp(24), dp(18), dp(24), dp(14));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{FONDO_ALTO, NEGRO}));
        scroll.addView(pantalla, new FrameLayout.LayoutParams(-1, -2));

        // Cabecera
        TextView titulo = texto("KULI MUSIC", 16, DORADO);
        titulo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titulo.setLetterSpacing(0.3f);
        pantalla.addView(titulo, new LinearLayout.LayoutParams(-1, -2));
        TextView lema = texto("TU MÚSICA · TU ESTILO", 10, GRIS);
        lema.setLetterSpacing(0.2f);
        pantalla.addView(lema, new LinearLayout.LayoutParams(-1, -2));

        // Portada con esquinas redondeadas
        portada = new ImageView(this);
        portada.setBackground(fondo(TARJETA, 24, LINEA));
        portada.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(24));
            }
        });
        portada.setClipToOutline(true);
        portada.setElevation(dp(10));
        portadaVacia();
        LinearLayout.LayoutParams lpPortada = new LinearLayout.LayoutParams(dp(portadaDp), dp(portadaDp));
        lpPortada.topMargin = dp(22);
        lpPortada.bottomMargin = dp(22);
        pantalla.addView(portada, lpPortada);

        // Datos de la canción
        cancion = texto("Selecciona una canción", 21, Color.WHITE);
        cancion.setSingleLine(true);
        cancion.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cancion.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pantalla.addView(cancion, new LinearLayout.LayoutParams(-1, -2));
        artista = texto("Artista desconocido", 14, GRIS);
        artista.setSingleLine(true);
        artista.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pantalla.addView(artista, new LinearLayout.LayoutParams(-1, -2));
        estado = texto("LISTO PARA REPRODUCIR", 10, DORADO);
        estado.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams lpEstado = new LinearLayout.LayoutParams(-1, -2);
        lpEstado.topMargin = dp(6);
        pantalla.addView(estado, lpEstado);

        // Barra de progreso
        barra = new SeekBar(this);
        barra.setProgressTintList(ColorStateList.valueOf(DORADO));
        barra.setThumbTintList(ColorStateList.valueOf(DORADO));
        barra.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)));
        LinearLayout.LayoutParams lpBarra = new LinearLayout.LayoutParams(-1, -2);
        lpBarra.topMargin = dp(14);
        pantalla.addView(barra, lpBarra);
        LinearLayout tiempos = new LinearLayout(this);
        tiempoActual = texto("0:00", 11, GRIS);
        tiempoActual.setGravity(Gravity.START);
        tiempoTotal = texto("0:00", 11, GRIS);
        tiempoTotal.setGravity(Gravity.END);
        tiempos.addView(tiempoActual, new LinearLayout.LayoutParams(0, -2, 1));
        tiempos.addView(tiempoTotal, new LinearLayout.LayoutParams(0, -2, 1));
        pantalla.addView(tiempos, new LinearLayout.LayoutParams(-1, -2));
        barra.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean delUsuario) {
                if (delUsuario) tiempoActual.setText(formato(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { arrastrando = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                arrastrando = false;
                try { if (reproductor != null && !esRadio) reproductor.seekTo(s.getProgress()); }
                catch (IllegalStateException ignored) { }
            }
        });

        // Controles principales
        mezcla = circulo("⇄", 46, 18, APAGADO);
        Button anterior = circulo("⏮", 54, 20, CONTORNO);
        botonPlay = circulo("▶", 78, 28, LLENO);
        Button siguiente = circulo("⏭", 54, 20, CONTORNO);
        bucle = circulo("↻", 46, 20, repetir ? CONTORNO : APAGADO);

        LinearLayout controles = new LinearLayout(this);
        controles.setGravity(Gravity.CENTER_VERTICAL);
        controles.addView(mezcla, new LinearLayout.LayoutParams(dp(46), dp(46)));
        controles.addView(separador(), new LinearLayout.LayoutParams(0, 1, 1));
        controles.addView(anterior, new LinearLayout.LayoutParams(dp(54), dp(54)));
        controles.addView(separador(), new LinearLayout.LayoutParams(0, 1, 1));
        controles.addView(botonPlay, new LinearLayout.LayoutParams(dp(78), dp(78)));
        controles.addView(separador(), new LinearLayout.LayoutParams(0, 1, 1));
        controles.addView(siguiente, new LinearLayout.LayoutParams(dp(54), dp(54)));
        controles.addView(separador(), new LinearLayout.LayoutParams(0, 1, 1));
        controles.addView(bucle, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout.LayoutParams lpControles = new LinearLayout.LayoutParams(-1, -2);
        lpControles.topMargin = dp(10);
        pantalla.addView(controles, lpControles);

        anterior.setOnClickListener(v -> saltar(-1));
        siguiente.setOnClickListener(v -> saltar(1));
        botonPlay.setOnClickListener(v -> alternarReproduccion());
        mezcla.setOnClickListener(v -> {
            aleatorio = !aleatorio;
            estiloBoton(mezcla, aleatorio ? CONTORNO : APAGADO, 1000);
            aviso(aleatorio ? "Aleatorio activado" : "Aleatorio desactivado");
            prepararSiguiente();
        });
        bucle.setOnClickListener(v -> {
            repetir = !repetir;
            estiloBoton(bucle, repetir ? CONTORNO : APAGADO, 1000);
            aviso(repetir ? "Repetir lista activado" : "Repetir lista desactivado");
            prepararSiguiente();
        });

        // Acciones: biblioteca
        Button movil = pastilla("♫  Mis canciones", LLENO);
        Button misCanciones = pastilla("♥  Favoritas", CONTORNO);
        LinearLayout fila1 = new LinearLayout(this);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp1.rightMargin = dp(5);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp2.leftMargin = dp(5);
        fila1.addView(movil, lp1);
        fila1.addView(misCanciones, lp2);
        LinearLayout.LayoutParams lpFila1 = new LinearLayout.LayoutParams(-1, -2);
        lpFila1.topMargin = dp(22);
        pantalla.addView(fila1, lpFila1);
        Button nas = pastilla("☁  Ver NAS", CONTORNO);
        Button subir = pastilla("⇪  Subir al NAS", CONTORNO);
        LinearLayout filaNas = new LinearLayout(this);
        LinearLayout.LayoutParams lpN1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lpN1.rightMargin = dp(5);
        LinearLayout.LayoutParams lpN2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        lpN2.leftMargin = dp(5);
        filaNas.addView(nas, lpN1);
        filaNas.addView(subir, lpN2);
        LinearLayout.LayoutParams lpFilaNas = new LinearLayout.LayoutParams(-1, -2);
        lpFilaNas.topMargin = dp(10);
        pantalla.addView(filaNas, lpFilaNas);
        nas.setOnClickListener(v -> abrirListaNas());
        subir.setOnClickListener(v -> elegirCancionesParaSubir());
        movil.setOnClickListener(v -> abrirCanciones());
        misCanciones.setOnClickListener(v -> mostrarFavoritas());

        // Acciones: secundarias
        favorito = pastilla("♡  Guardar", CONTORNO);
        Button ymusic = pastilla("🎧  YMusic", CONTORNO);
        Button buscar = pastilla("⌕  Buscar", CONTORNO);
        LinearLayout fila2 = new LinearLayout(this);
        for (Button b : new Button[]{favorito, ymusic, buscar}) {
            b.setTextSize(13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.leftMargin = dp(4); lp.rightMargin = dp(4);
            fila2.addView(b, lp);
        }
        LinearLayout.LayoutParams lpFila2 = new LinearLayout.LayoutParams(-1, -2);
        lpFila2.topMargin = dp(10);
        pantalla.addView(fila2, lpFila2);
        favorito.setOnClickListener(v -> alternarFavorito());
        ymusic.setOnClickListener(v -> {
            Intent i = buscarYMusic();
            if (i != null) startActivity(i); else aviso("YMusic no encontrado");
        });
        buscar.setOnClickListener(v -> abrirBuscador());

        TextView ajustes = texto("⚙  NAS", 12, GRIS);
        ajustes.setPadding(dp(10), dp(10), dp(10), dp(10));
        ajustes.setOnClickListener(v -> ajustesNas());
        TextView eq = texto("🎚  Ecualizador", 12, GRIS);
        eq.setPadding(dp(10), dp(10), dp(10), dp(10));
        eq.setOnClickListener(v -> abrirEcualizador());
        TextView dormir = texto("🌙  Dormir", 12, GRIS);
        dormir.setPadding(dp(10), dp(10), dp(10), dp(10));
        dormir.setOnClickListener(v -> abrirDormir());
        TextView radio = texto("📻  Radio en directo", 13, DORADO);
        radio.setPadding(dp(12), dp(10), dp(12), dp(6));
        radio.setOnClickListener(v -> abrirRadio());
        TextView tv = texto("📺  TV en directo", 13, DORADO);
        tv.setPadding(dp(12), dp(10), dp(12), dp(6));
        tv.setOnClickListener(v -> abrirTv());
        LinearLayout filaMedios = new LinearLayout(this);
        filaMedios.setGravity(Gravity.CENTER_HORIZONTAL);
        filaMedios.addView(radio);
        filaMedios.addView(tv);
        LinearLayout.LayoutParams lpRadio = new LinearLayout.LayoutParams(-1, -2);
        lpRadio.topMargin = dp(6);
        pantalla.addView(filaMedios, lpRadio);

        LinearLayout filaAjustes = new LinearLayout(this);
        filaAjustes.setGravity(Gravity.CENTER_HORIZONTAL);
        filaAjustes.addView(eq);
        filaAjustes.addView(dormir);
        filaAjustes.addView(ajustes);
        LinearLayout.LayoutParams lpAjustes = new LinearLayout.LayoutParams(-1, -2);
        lpAjustes.topMargin = dp(0);
        pantalla.addView(filaAjustes, lpAjustes);

        TextView pie = texto("✦ KULI EDITION ✦", 10, DORADO);
        pie.setAlpha(0.7f);
        pie.setLetterSpacing(0.2f);
        pantalla.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout.LayoutParams lpPie = new LinearLayout.LayoutParams(-1, -2);
        lpPie.topMargin = dp(16);
        pantalla.addView(pie, lpPie);

        setContentView(scroll);

        MainActivity viejo = instancia;
        instancia = this;
        if (viejo != null && viejo != this && viejo.reproductor != null) adoptar(viejo);
        else cargarUltima();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
        revisarYMusic();
        ymusicAFavoritas();
    }
    @Override protected void onPause() {
        ui.removeCallbacks(ticker);
        try {
            if (instancia == this && reproductor != null && uriActual != null && !esRadio) {
                getSharedPreferences("kuli_ajustes", MODE_PRIVATE).edit()
                    .putString("ultima_uri", uriActual.toString())
                    .putString("ultima_titulo", tituloActual + " — " + artistaActual)
                    .putInt("ultima_pos", reproductor.getCurrentPosition()).apply();
            }
        } catch (Exception ignored) { }
        super.onPause();
    }

    // ---------- Ayudas de diseño ----------
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private View separador() { return new View(this); }

    private TextView texto(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(4), dp(2), dp(4), dp(2));
        return t;
    }
    private GradientDrawable fondo(int color, int radioDp, int trazo) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radioDp >= 500 ? 10000 : dp(radioDp));
        if (trazo != 0) d.setStroke(dp(1), trazo);
        return d;
    }
    private Drawable ripple(Drawable base) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), base, null);
    }
    // Aplica el aspecto: LLENO (dorado), CONTORNO (tarjeta con borde dorado) o APAGADO (sin fondo).
    private void estiloBoton(Button b, int modo, int radioDp) {
        if (modo == LLENO) {
            b.setTextColor(NEGRO);
            b.setBackground(ripple(fondo(DORADO, radioDp, 0)));
        } else if (modo == CONTORNO) {
            b.setTextColor(DORADO);
            b.setBackground(ripple(fondo(TARJETA, radioDp, DORADO)));
        } else {
            b.setTextColor(GRIS);
            b.setBackground(ripple(fondo(Color.TRANSPARENT, radioDp, Color.argb(60, 255, 255, 255))));
        }
    }
    private Button base(String s, int sizeSp) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(sizeSp); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setStateListAnimator(null);
        b.setBackgroundTintList(null);
        return b;
    }
    private Button circulo(String s, int tamDp, int sizeSp, int modo) {
        Button b = base(s, sizeSp);
        estiloBoton(b, modo, 1000);
        return b;
    }
    private Button pastilla(String s, int modo) {
        Button b = base(s, 15);
        estiloBoton(b, modo, 24);
        return b;
    }
    private void portadaVacia() {
        portada.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        portada.setPadding(dp(56), dp(56), dp(56), dp(56));
        portada.setImageResource(android.R.drawable.ic_media_play);
        portada.setColorFilter(DORADO);
    }
    private void aviso(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private String formato(int ms) {
        int seg = Math.max(0, ms) / 1000;
        return (seg / 60) + ":" + (seg % 60 < 10 ? "0" : "") + (seg % 60);
    }
    private void actualizarProgreso() {
        if (reproductor == null) return;
        if (esRadio) {
            try {
                barra.setMax(1); barra.setProgress(0);
                tiempoActual.setText("●"); tiempoTotal.setText("DIRECTO");
                botonPlay.setText(reproductor.isPlaying() ? "❚❚" : "▶");
            } catch (IllegalStateException ignored) { }
            return;
        }
        try {
            int duracion = reproductor.getDuration();
            int pos = reproductor.getCurrentPosition();
            if (!arrastrando) {
                barra.setMax(Math.max(duracion, 1));
                barra.setProgress(pos);
                tiempoActual.setText(formato(pos));
            }
            tiempoTotal.setText(formato(duracion));
            botonPlay.setText(reproductor.isPlaying() ? "❚❚" : "▶");
        } catch (IllegalStateException ignored) { }
    }

    // ---------- Reproducción ----------
    private void abrirCanciones() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_AUDIO);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_SUBIR && resultCode == RESULT_OK && data != null) {
            final ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int k = 0; k < data.getClipData().getItemCount(); k++)
                    uris.add(data.getClipData().getItemAt(k).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            String que = uris.size() == 1 ? "1 canción" : uris.size() + " canciones";
            ofrecerSubida(uris, null, null);
            return;
        }
        if (requestCode == PICK_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            modoLista = false;
            reproducir(uri);
        }
    }
    private void reproducir(Uri uri) {
        if ("nas".equals(uri.getScheme())) { reproducirDelNas(uri); return; }
        reproducirArchivo(uri, uri);
    }

    // 'fuente' es lo que se reproduce; 'identidad' es cómo se reconoce la canción (favoritos, NAS...).
    private void reproducirArchivo(Uri fuente, Uri identidad) {
        try {
            esRadio = false;
            liberarSiguiente();
            if (reproductor != null) { reproductor.release(); reproductor = null; }
            reproductor = abrirReproductor(fuente);
            uriActual = identidad;
            mostrarDatos(fuente);
            uriActual = identidad;
            if ("nas".equals(identidad.getScheme()) && tituloActual.equals("Mi canción")) {
                tituloActual = nombreSinExtension(identidad.getLastPathSegment());
                cancion.setText(tituloActual);
            }
            actualizarBotonFavorito();
            reproductor.setOnCompletionListener(mp -> alTerminar());
            prepararSiguiente();
            if (posicionPendienteMs > 0) {
                try { reproductor.seekTo(posicionPendienteMs); } catch (Exception ignored) { }
                posicionPendienteMs = 0;
            }
            reproductor.start(); estado.setText("▶ REPRODUCIENDO");
            iniciarServicio();
            arrastrando = false;
            actualizarProgreso();
        } catch (Exception e) {
            android.util.Log.e("KULI_PLAY", "No se pudo reproducir " + fuente, e);
            if (isDestroyed()) return;
            String motivo = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            String ayuda = (e instanceof SecurityException)
                ? "\n\nAndroid ya no deja a la app leer esta canción. Vuelve a elegirla con «Mis canciones»."
                : "";
            new AlertDialog.Builder(this).setTitle("No se pudo reproducir")
                .setMessage(motivo + ayuda).setPositiveButton("CERRAR", null).show();
        }
    }

    // Cuando suena la radio del coche, la música del móvil se pausa.
    void pausarDesdeAuto() { pausarPorTemporizador(); }

    private void alternarReproduccion() {
        if (reproductor == null && ultimaUri != null) {
            Uri u = ultimaUri; ultimaUri = null;
            posicionPendienteMs = getSharedPreferences("kuli_ajustes", MODE_PRIVATE).getInt("ultima_pos", 0);
            reproducir(u);
            return;
        }
        if (reproductor == null) { aviso("Selecciona una canción"); return; }
        try {
            if (reproductor.isPlaying()) {
                reproductor.pause(); estado.setText("Ⅱ EN PAUSA");
            } else {
                reproductor.start(); estado.setText("▶ REPRODUCIENDO");
            }
            iniciarServicio();
        } catch (IllegalStateException e) { aviso("No se pudo reproducir"); }
        actualizarProgreso();
    }

    // Órdenes que llegan desde los botones de la notificación.
    void accionNotificacion(String accion) {
        if (MusicaService.PAUSA.equals(accion)) alternarReproduccion();
        else if (MusicaService.SIGUIENTE.equals(accion)) saltar(1);
        else if (MusicaService.ANTERIOR.equals(accion)) saltar(-1);
    }

    // Si la app se cerró (o giró) con música sonando, la nueva pantalla recoge el reproductor.
    private void adoptar(MainActivity v) {
        reproductor = v.reproductor; v.reproductor = null;
        siguienteReproductor = v.siguienteReproductor; v.siguienteReproductor = null;
        indicePreparado = v.indicePreparado;
        uriActual = v.uriActual;
        tituloActual = v.tituloActual; artistaActual = v.artistaActual;
        listaFavoritas.clear(); listaFavoritas.addAll(v.listaFavoritas);
        posicionFavorita = v.posicionFavorita;
        modoLista = v.modoLista; aleatorio = v.aleatorio; repetir = v.repetir; esRadio = v.esRadio;
        try { reproductor.setOnCompletionListener(mp -> alTerminar()); } catch (Exception ignored) { }
        cancion.setText(tituloActual);
        artista.setText(artistaActual);
        estiloBoton(mezcla, aleatorio ? CONTORNO : APAGADO, 1000);
        estiloBoton(bucle, repetir ? CONTORNO : APAGADO, 1000);
        if (v.portada != null && v.portada.getColorFilter() == null && v.portada.getDrawable() != null) {
            portada.clearColorFilter();
            portada.setPadding(0, 0, 0, 0);
            portada.setScaleType(ImageView.ScaleType.CENTER_CROP);
            portada.setImageDrawable(v.portada.getDrawable());
        }
        boolean suena = false;
        try { suena = reproductor.isPlaying(); } catch (Exception ignored) { }
        estado.setText(suena ? "▶ REPRODUCIENDO" : "Ⅱ EN PAUSA");
        actualizarBotonFavorito();
        actualizarProgreso();
    }

    // Notificación fija con botones mientras suena, para que Android no pare la música.
    private void iniciarServicio() {
        boolean suena = false;
        try { suena = reproductor != null && reproductor.isPlaying(); } catch (Exception ignored) { }
        if (suena && AutoService.instancia != null) AutoService.instancia.pararPorApp();
        try {
            MusicaService activo = MusicaService.instancia;
            if (activo != null) { activo.refrescar(tituloActual, artistaActual, suena); return; }
            Intent i = new Intent(this, MusicaService.class)
                .putExtra("t", tituloActual).putExtra("a", artistaActual).putExtra("p", suena);
            startForegroundService(i);
        } catch (Throwable e) { android.util.Log.e("KULI_PLAY", "Servicio", e); }
    }
    private void detenerServicio() {
        try { stopService(new Intent(this, MusicaService.class)); }
        catch (Throwable ignored) { }
    }

    private MediaPlayer abrirReproductor(Uri uri) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        try {
            try { mp.setAudioSessionId(sesionAudio()); } catch (Exception ignored) { }
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, uri);
            mp.prepare();
            aplicarEcualizador();
            return mp;
        } catch (Exception e) {
            mp.release();
            throw e;
        }
    }

    // ---------- Subida automática por estilo ----------
    private void ofrecerSubida(final ArrayList<Uri> uris, final java.util.function.Consumer<Uri> alSubir,
                               final Runnable alIgnorar) {
        final String que = uris.size() == 1 ? "1 canción" : uris.size() + " canciones";
        final ArrayList<String> op = new ArrayList<>();
        op.add("⚡  Subir automático (cada una a su estilo)");
        op.add("📁  Elegir yo un estilo para todas");
        if (alIgnorar != null) op.add("🚫  Ignorar estas canciones");
        op.add("Ahora no");
        new AlertDialog.Builder(this).setTitle("Música nueva · " + que)
            .setItems(op.toArray(new String[0]), (d, i) -> {
                String e = op.get(i);
                if (e.startsWith("⚡")) subirAutomatico(uris, alSubir);
                else if (e.startsWith("📁")) elegirEstilo("Estilo para " + que,
                        estilo -> subirVariasAlNas(uris, estilo, alSubir));
                else if (e.startsWith("🚫")) alIgnorar.run();
            }).show();
    }

    private void subirAutomatico(final ArrayList<Uri> uris, final java.util.function.Consumer<Uri> alSubir) {
        aviso("Reconociendo " + uris.size() + " canciones…");
        new Thread(() -> {
            final LinkedHashMap<String, ArrayList<Uri>> porEstilo = new LinkedHashMap<>();
            for (Uri u : uris) {
                String estilo;
                try { estilo = detectarEstilo(u); } catch (Throwable e) { estilo = "Sin clasificar"; }
                if (!porEstilo.containsKey(estilo)) porEstilo.put(estilo, new ArrayList<>());
                porEstilo.get(estilo).add(u);
            }
            runOnUiThread(() -> confirmarAutomatico(uris, porEstilo, alSubir));
        }).start();
    }

    private void confirmarAutomatico(final ArrayList<Uri> uris, final LinkedHashMap<String, ArrayList<Uri>> porEstilo,
                                     final java.util.function.Consumer<Uri> alSubir) {
        if (isDestroyed()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ArrayList<Uri>> e : porEstilo.entrySet())
            sb.append("📁 ").append(e.getKey()).append(":  ").append(e.getValue().size()).append("\n");
        sb.append("\nLas «Sin clasificar» no se han podido reconocer; puedes moverlas luego con una pulsación larga en la lista del NAS.");
        new AlertDialog.Builder(this).setTitle("Así las voy a guardar")
            .setMessage(sb.toString())
            .setPositiveButton("SUBIR ASÍ", (d, w) -> subirGrupos(new ArrayList<>(porEstilo.entrySet()), 0, alSubir))
            .setNeutralButton("ELEGIR YO", (d, w) -> elegirEstilo("Estilo para todas",
                    estilo -> subirVariasAlNas(uris, estilo, alSubir)))
            .setNegativeButton("CANCELAR", null).show();
    }

    private void subirGrupos(final ArrayList<Map.Entry<String, ArrayList<Uri>>> cola, final int i,
                             final java.util.function.Consumer<Uri> alSubir) {
        if (i >= cola.size()) return;
        subirVariasAlNas(cola.get(i).getValue(), cola.get(i).getKey(), alSubir,
                () -> subirGrupos(cola, i + 1, alSubir));
    }

    // Etiqueta de la canción; si no hay, la busca en internet (iTunes) por título y artista.
    private String detectarEstilo(Uri u) {
        String genero = null, titulo = null, autor = null;
        int anio = 0;
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(this, u);
            genero = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
            titulo = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            autor = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String y = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
            if (y != null && y.length() >= 4) anio = Integer.parseInt(y.substring(0, 4));
        } catch (Exception ignored) { } finally { try { m.release(); } catch (Exception ignored) { } }
        String estilo = simple(nombreDeUri(u)).contains("flamenc") ? "Flamenquito" : estiloDeGenero(genero);
        if (estilo == null) {
            String termino = (titulo != null && !titulo.trim().isEmpty())
                ? ((autor == null ? "" : autor + " ") + titulo)
                : limpiarNombre(nombreDeUri(u));
            String[] r = consultarItunes(termino);
            if (r != null) {
                estilo = estiloDeGenero(r[0]);
                if (anio == 0 && r[1] != null && r[1].length() >= 4) {
                    try { anio = Integer.parseInt(r[1].substring(0, 4)); } catch (Exception ignored) { }
                }
            }
        }
        if (estilo == null && anio >= 1980 && anio <= 2009)
            estilo = anio < 1990 ? "Años 80" : anio < 2000 ? "Años 90" : "Años 2000";
        return estilo == null ? "Sin clasificar" : estilo;
    }

    private String estiloDeGenero(String genero) {
        if (genero == null) return null;
        String g = simple(genero);
        if (g.isEmpty()) return null;
        if (g.contains("flamenc") || g.contains("rumba")) return "Flamenquito";
        if (g.contains("latin") || g.contains("reggaeton") || g.contains("urban") || g.contains("salsa")
            || g.contains("bachata") || g.contains("cumbia") || g.contains("tropical")) return "Latino";
        if (g.contains("rock") || g.contains("alternativ") || g.contains("metal") || g.contains("punk")) return "Rock";
        if (g.contains("danc") || g.contains("electr") || g.contains("house") || g.contains("techno")
            || g.contains("disco")) return "Dance";
        if (g.contains("balad") || g.contains("romant") || g.contains("singer") || g.contains("easy listening")) return "Baladas";
        if (g.contains("pop")) return "Pop";
        return null;
    }

    // Nombre "comparable": sin extensión, sin (Video Oficial), sin acentos ni símbolos.
    private String claveNombre(String nombre) {
        return simple(limpiarNombre(nombre)).replaceAll("[^a-z0-9]", "");
    }

    private String limpiarNombre(String nombre) {
        String n = nombreSinExtension(nombre).replace('_', ' ');
        n = n.replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]", " ");
        n = n.replaceAll("(?i)official|video oficial|videoclip|lyrics|letra|audio|\\bhd\\b|\\bhq\\b", " ");
        return n.replaceAll("\\s+", " ").trim();
    }

    private String[] consultarItunes(String termino) {
        if (termino == null || termino.trim().isEmpty()) return null;
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                "https://itunes.apple.com/search?media=music&entity=song&limit=1&country=ES&term="
                + java.net.URLEncoder.encode(termino, "UTF-8")).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(8000);
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String linea;
                while ((linea = r.readLine()) != null) sb.append(linea);
            }
            org.json.JSONArray res = new org.json.JSONObject(sb.toString()).optJSONArray("results");
            if (res == null || res.length() == 0) return null;
            org.json.JSONObject o = res.getJSONObject(0);
            return new String[]{o.optString("primaryGenreName"), o.optString("releaseDate")};
        } catch (Exception e) {
            android.util.Log.e("KULI_ESTILO", "iTunes", e);
            return null;
        }
    }

    // ---------- Última canción ----------
    private void cargarUltima() {
        try {
            SharedPreferences aj = getSharedPreferences("kuli_ajustes", MODE_PRIVATE);
            String u = aj.getString("ultima_uri", null);
            if (u == null) return;
            ultimaUri = Uri.parse(u);
            cancion.setText("↺ " + aj.getString("ultima_titulo", "Última canción"));
            artista.setText("Pulsa ▶ para continuar");
        } catch (Exception ignored) { }
    }

    // ---------- Radio en directo ----------
    private void abrirRadio() {
        aviso("Buscando emisoras…");
        buscarEmisoras(null);
    }

    private void buscarEmisoras(final String nombre) {
        new Thread(() -> {
            String filtro = "&order=clickcount&reverse=true&limit=60&hidebroken=true";
            String ruta;
            try {
                ruta = nombre == null
                    ? "/json/stations/search?countrycode=ES" + filtro
                    : "/json/stations/search?name=" + java.net.URLEncoder.encode(nombre, "UTF-8") + filtro;
            } catch (Exception e) { return; }
            String[] servidores = {"https://de1.api.radio-browser.info", "https://at1.api.radio-browser.info",
                                   "https://nl1.api.radio-browser.info"};
            String texto = null;
            for (String srv : servidores) {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(srv + ruta).openConnection();
                    c.setConnectTimeout(6000); c.setReadTimeout(9000);
                    c.setRequestProperty("User-Agent", "KULIMusic/3.3");
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String linea;
                        while ((linea = r.readLine()) != null) sb.append(linea);
                    }
                    texto = sb.toString();
                    break;
                } catch (Exception e) { android.util.Log.e("KULI_RADIO", srv, e); }
            }
            if (texto == null) { runOnUiThread(() -> aviso("No se pudo cargar la lista de emisoras")); return; }
            final ArrayList<String> nombres = new ArrayList<>(), urls = new ArrayList<>();
            try {
                org.json.JSONArray arr = new org.json.JSONArray(texto);
                java.util.HashSet<String> vistos = new java.util.HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    String n = o.optString("name").trim();
                    String url = o.optString("url_resolved");
                    if (url.isEmpty()) url = o.optString("url");
                    if (n.isEmpty() || url.isEmpty() || !vistos.add(n.toLowerCase())) continue;
                    nombres.add(n); urls.add(url);
                }
            } catch (Exception e) { runOnUiThread(() -> aviso("Respuesta de emisoras no válida")); return; }
            runOnUiThread(() -> mostrarEmisoras(nombre, nombres, urls));
        }).start();
    }

    private void mostrarEmisoras(String nombre, final ArrayList<String> nombres, final ArrayList<String> urls) {
        if (isDestroyed()) return;
        if (nombres.isEmpty()) { aviso("No hay emisoras con ese nombre"); return; }
        String titulo = nombre == null ? "📻 Radios de España" : "📻 «" + nombre + "»";
        new AlertDialog.Builder(this).setTitle(titulo)
            .setItems(nombres.toArray(new String[0]), (d, i) -> reproducirRadio(nombres.get(i), urls.get(i)))
            .setNeutralButton("🔍 BUSCAR", (d, w) ->
                pedirTexto("🔍 Buscar emisora (ej. Los 40)", q -> buscarEmisoras(q)))
            .setNegativeButton("CERRAR", null).show();
    }

    private void reproducirRadio(final String nombre, final String url) {
        aviso("Conectando con " + nombre + "…");
        new Thread(() -> {
            final MediaPlayer mp = new MediaPlayer();
            try {
                try { mp.setAudioSessionId(sesionAudio()); } catch (Exception ignored) { }
                mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                mp.setDataSource(url);
                mp.prepare();
            } catch (Exception e) {
                android.util.Log.e("KULI_RADIO", "No abre " + url, e);
                try { mp.release(); } catch (Exception ignored) { }
                runOnUiThread(() -> aviso("No se pudo abrir " + nombre + ". Prueba con otra."));
                return;
            }
            runOnUiThread(() -> {
                liberarSiguiente();
                if (reproductor != null) { try { reproductor.release(); } catch (Exception ignored) { } }
                reproductor = mp;
                modoLista = false; esRadio = true; uriActual = null;
                tituloActual = nombre; artistaActual = "Radio en directo";
                cancion.setText(nombre); artista.setText("Radio en directo");
                portadaVacia();
                mp.setOnErrorListener((m, w, x) -> { aviso("Se cortó la emisora"); return true; });
                mp.start();
                estado.setText("📻 EN DIRECTO");
                actualizarBotonFavorito();
                iniciarServicio();
                aplicarEcualizador();
                actualizarProgreso();
            });
        }).start();
    }

    // ---------- Temporizador para dormir ----------
    private static final Handler hTemporizador = new Handler(Looper.getMainLooper());
    private static Runnable temporizador;

    private void abrirDormir() {
        final int[] minutos = {15, 30, 60, 0};
        String[] items = {"15 minutos", "30 minutos", "1 hora", "Cancelar temporizador"};
        new AlertDialog.Builder(this).setTitle("🌙 Parar la música en…")
            .setItems(items, (d, i) -> programarDormir(minutos[i]))
            .setNegativeButton("CERRAR", null).show();
    }

    private void programarDormir(int minutos) {
        if (temporizador != null) hTemporizador.removeCallbacks(temporizador);
        temporizador = null;
        if (minutos <= 0) { aviso("Temporizador cancelado"); return; }
        temporizador = () -> {
            temporizador = null;
            MainActivity m = instancia;
            if (m != null) m.pausarPorTemporizador();
        };
        hTemporizador.postDelayed(temporizador, minutos * 60000L);
        aviso("La música se parará en " + minutos + " min");
    }

    private void pausarPorTemporizador() {
        try {
            if (reproductor != null && reproductor.isPlaying()) {
                reproductor.pause();
                estado.setText("Ⅱ EN PAUSA");
                iniciarServicio();
            }
        } catch (Exception ignored) { }
    }

    // ---------- Canciones nuevas de YMusic ----------
    private static boolean revisadoYMusic = false;

    private void marcarVistas(java.util.Collection<String> claves) {
        SharedPreferences aj = getSharedPreferences("kuli_ajustes", MODE_PRIVATE);
        java.util.HashSet<String> todas = new java.util.HashSet<>(aj.getStringSet("ymusic_vistas", new java.util.HashSet<>()));
        todas.addAll(claves);
        aj.edit().putStringSet("ymusic_vistas", todas).apply();
    }

    // Las canciones de la carpeta YMusic entran solas en Favoritas (una sola vez cada una).
    private static boolean favYMusicRevisado = false;
    private void ymusicAFavoritas() {
        if (favYMusicRevisado || isFinishing() || isDestroyed()) return;
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        favYMusicRevisado = true;
        SharedPreferences aj = getSharedPreferences("kuli_ajustes", MODE_PRIVATE);
        java.util.HashSet<String> hechas = new java.util.HashSet<>(
                aj.getStringSet("ymusic_favs_hechas", new java.util.HashSet<String>()));
        SharedPreferences.Editor ed = favoritos.edit();
        int nuevas = 0;
        try (Cursor c = getContentResolver().query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{android.provider.MediaStore.Audio.Media._ID,
                             android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
                             android.provider.MediaStore.Audio.Media.TITLE,
                             android.provider.MediaStore.Audio.Media.ARTIST},
                android.provider.MediaStore.Audio.Media.DATA + " LIKE ?", new String[]{"%/YMusic/%"}, null)) {
            while (c != null && c.moveToNext()) {
                String clave = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString();
                if (hechas.contains(clave)) continue;
                hechas.add(clave);
                if (favoritos.contains(clave)) continue;
                String nombre = c.getString(1) == null ? "Canción" : nombreSinExtension(c.getString(1));
                String titulo = c.getString(2), artista = c.getString(3);
                if (titulo == null || titulo.trim().isEmpty()) titulo = nombre;
                String texto = (artista == null || artista.trim().isEmpty() || artista.contains("unknown"))
                        ? titulo : titulo + " — " + artista;
                ed.putString(clave, texto);
                nuevas++;
            }
        } catch (Exception e) { android.util.Log.e("KULI_YM", "Favoritas YMusic", e); return; }
        ed.apply();
        aj.edit().putStringSet("ymusic_favs_hechas", hechas).apply();
        if (nuevas > 0) aviso("♥ " + nuevas + (nuevas == 1 ? " canción" : " canciones") + " de YMusic añadidas a Favoritas");
    }

    private void revisarYMusic() {
        if (revisadoYMusic || isFinishing() || isDestroyed()) return;
        SharedPreferences aj = getSharedPreferences("kuli_ajustes", MODE_PRIVATE);
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!aj.getBoolean("pidio_permiso", false)) {
                aj.edit().putBoolean("pidio_permiso", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 200);
            }
            return;
        }
        if (!hayWifi()) return;
        revisadoYMusic = true;
        final java.util.Set<String> vistas = aj.getStringSet("ymusic_vistas", new java.util.HashSet<>());
        final ArrayList<Uri> nuevas = new ArrayList<>();
        final java.util.HashMap<Uri, String> claves = new java.util.HashMap<>();
        try (Cursor c = getContentResolver().query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{android.provider.MediaStore.Audio.Media._ID, android.provider.MediaStore.Audio.Media.SIZE},
                android.provider.MediaStore.Audio.Media.DATA + " LIKE ?", new String[]{"%/YMusic/%"}, null)) {
            while (c != null && c.moveToNext()) {
                String clave = c.getLong(0) + ":" + c.getLong(1);
                if (vistas.contains(clave)) continue;
                Uri u = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                nuevas.add(u);
                claves.put(u, clave);
            }
        } catch (Exception e) { android.util.Log.e("KULI_YM", "Buscando YMusic", e); return; }
        if (nuevas.isEmpty()) return;
        final String que = nuevas.size() == 1 ? "1 canción nueva" : nuevas.size() + " canciones nuevas";
        final java.util.function.Consumer<Uri> marcar = u -> marcarVistas(java.util.Collections.singleton(claves.get(u)));
        final Runnable ignorar = () -> marcarVistas(claves.values());
        aviso("Hay " + que + " de YMusic");
        ofrecerSubida(nuevas, marcar, ignorar);
    }

    @Override public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        if (codigo == 200 && resultados.length > 0
                && resultados[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) revisarYMusic();
    }

    // ---------- Ecualizador ----------
    private static int sesionAudio = 0;
    private static android.media.audiofx.Equalizer ecualizador;
    private static android.media.audiofx.BassBoost refuerzo;
    private static final String[] PRESETS = {"Normal", "Bajos", "Rock", "Pop", "Voces", "Agudos"};

    private int sesionAudio() {
        if (sesionAudio == 0) {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            sesionAudio = am.generateAudioSessionId();
        }
        return sesionAudio;
    }

    // Forma de cada ajuste: t va de 0 (graves) a 1 (agudos); devuelve de -1 a 1.
    private double curva(int preset, double t) {
        switch (preset) {
            case 1: return Math.max(0, 1 - t * 2) * 1.0;                 // Bajos
            case 2: return Math.abs(t - 0.5) * 2 * 0.7 - 0.2;            // Rock (en V)
            case 3: return 0.6 * Math.exp(-Math.pow((t - 0.5) / 0.28, 2)) + (t < 0.2 ? 0.15 : 0) - 0.1; // Pop
            case 4: return 0.7 * Math.exp(-Math.pow((t - 0.6) / 0.2, 2)) - (t < 0.2 ? 0.3 : 0);        // Voces
            case 5: return Math.max(0, t * 2 - 1) * 1.0;                 // Agudos
            default: return 0;
        }
    }

    private void aplicarEcualizador() {
        try {
            int preset = getSharedPreferences("kuli_ajustes", MODE_PRIVATE).getInt("eq_preset", 0);
            if (ecualizador == null) ecualizador = new android.media.audiofx.Equalizer(0, sesionAudio());
            short[] rango = ecualizador.getBandLevelRange();
            short bandas = ecualizador.getNumberOfBands();
            for (short b = 0; b < bandas; b++) {
                double t = bandas > 1 ? (double) b / (bandas - 1) : 0.5;
                double f = Math.max(-1, Math.min(1, curva(preset, t)));
                int nivel = (int) (f >= 0 ? f * rango[1] : -f * rango[0]);
                ecualizador.setBandLevel(b, (short) nivel);
            }
            ecualizador.setEnabled(preset != 0);
        } catch (Throwable e) { android.util.Log.e("KULI_EQ", "Ecualizador", e); }
        try {
            int preset = getSharedPreferences("kuli_ajustes", MODE_PRIVATE).getInt("eq_preset", 0);
            if (refuerzo == null) refuerzo = new android.media.audiofx.BassBoost(0, sesionAudio());
            if (refuerzo.getStrengthSupported()) refuerzo.setStrength((short) (preset == 1 ? 700 : 0));
            refuerzo.setEnabled(preset == 1);
        } catch (Throwable e) { android.util.Log.e("KULI_EQ", "Refuerzo de graves", e); }
    }

    private void abrirEcualizador() {
        int actual = getSharedPreferences("kuli_ajustes", MODE_PRIVATE).getInt("eq_preset", 0);
        new AlertDialog.Builder(this).setTitle("🎚 Ecualizador")
            .setSingleChoiceItems(PRESETS, actual, (d, i) -> {
                getSharedPreferences("kuli_ajustes", MODE_PRIVATE).edit().putInt("eq_preset", i).apply();
                aplicarEcualizador();
                aviso("Sonido: " + PRESETS[i]);
                d.dismiss();
            })
            .setNegativeButton("CERRAR", null).show();
    }

    private void mostrarDatos(Uri uri) {
        uriActual = uri;
        try {
            tituloActual = "Mi canción"; artistaActual = "Artista desconocido";
            portadaVacia();
            MediaMetadataRetriever meta = new MediaMetadataRetriever();
            try {
                meta.setDataSource(this, uri);
                String titulo = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                String autor = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (titulo != null && !titulo.trim().isEmpty()) tituloActual = titulo;
                if (autor != null && !autor.trim().isEmpty()) artistaActual = autor;
                byte[] bytes = meta.getEmbeddedPicture();
                if (bytes != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) {
                        portada.clearColorFilter();
                        portada.setPadding(0, 0, 0, 0);
                        portada.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        portada.setImageBitmap(bitmap);
                    }
                }
            } catch (Exception ignored) { } finally { try { meta.release(); } catch (Exception ignored) { } }
            if (tituloActual.equals("Mi canción")) {
                try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        if (col >= 0) tituloActual = c.getString(col);
                    }
                } catch (Exception ignored) { }
            }
            cancion.setText(tituloActual); artista.setText(artistaActual);
            actualizarBotonFavorito();
        } catch (Exception e) { aviso("Error mostrando datos: " + e.getMessage()); }
    }

    private void liberarSiguiente() {
        if (siguienteReproductor != null) {
            try { siguienteReproductor.release(); } catch (Exception ignored) { }
            siguienteReproductor = null;
        }
        indicePreparado = -1;
    }

    private int indiceSiguiente() {
        if (!modoLista || listaFavoritas.isEmpty()) return -1;
        if (aleatorio && listaFavoritas.size() > 1) {
            int n = new java.util.Random().nextInt(listaFavoritas.size() - 1);
            return n >= posicionFavorita ? n + 1 : n;
        }
        int n = posicionFavorita + 1;
        return n < listaFavoritas.size() ? n : (repetir ? 0 : -1);
    }

    // Prepara la siguiente pista para encadenarla al terminar la actual.
    private void prepararSiguiente() {
        liberarSiguiente();
        int n = indiceSiguiente();
        if (n < 0 || reproductor == null) return;
        Uri siguienteUri = Uri.parse(listaFavoritas.get(n));
        if ("nas".equals(siguienteUri.getScheme())) { precargarNas(siguienteUri); return; }
        try {
            MediaPlayer proximo = abrirReproductor(Uri.parse(listaFavoritas.get(n)));
            siguienteReproductor = proximo;
            indicePreparado = n;
        } catch (Exception ignored) { liberarSiguiente(); }
    }

    private void alTerminar() {
        if (!modoLista || listaFavoritas.isEmpty()) {
            estado.setText("✓ REPRODUCCIÓN TERMINADA");
            detenerServicio();
            actualizarProgreso();
            return;
        }
        if (siguienteReproductor != null && indicePreparado >= 0) {
            MediaPlayer anterior = reproductor;
            reproductor = siguienteReproductor;
            try { reproductor.start(); } catch (Exception ignored) { }
            posicionFavorita = indicePreparado;
            siguienteReproductor = null;
            indicePreparado = -1;
            try { anterior.release(); } catch (Exception ignored) { }
            mostrarDatos(Uri.parse(listaFavoritas.get(posicionFavorita)));
            reproductor.setOnCompletionListener(mp -> alTerminar());
            estado.setText("▶ REPRODUCIENDO");
            iniciarServicio();
            prepararSiguiente();
        } else {
            saltar(1);
        }
    }

    // ---------- Favoritas y NAS ----------
    private void actualizarBotonFavorito() {
        boolean guardada = uriActual != null && favoritos.contains(uriActual.toString());
        favorito.setText(guardada ? "♥  Guardada" : "♡  Guardar");
        estiloBoton(favorito, guardada ? LLENO : CONTORNO, 24);
    }
    private void alternarFavorito() {
        if (uriActual == null) { aviso("Selecciona una canción primero"); return; }
        String clave = uriActual.toString();
        if (favoritos.contains(clave)) {
            favoritos.edit().remove(clave).apply(); aviso("Eliminada de favoritas");
        } else {
            String titulo = tituloActual + " — " + artistaActual;
            if ("nas".equals(uriActual.getScheme())) {
                favoritos.edit().putString(clave, titulo).apply();
                aviso("Guardada en favoritas (ya está en el NAS)");
            } else {
                pedirEstiloYGuardar(uriActual, clave, titulo);
                return;
            }
        }
        actualizarBotonFavorito();
    }

    // ---------- Estilos (carpetas del NAS) ----------
    private static final String[] ESTILOS = {"Pop", "Rock", "Latino", "Flamenquito", "Baladas", "Dance",
        "Años 80", "Años 90", "Años 2000", "Otro…"};

    private void elegirEstilo(final String titulo, final java.util.function.Consumer<String> listo) {
        new AlertDialog.Builder(this).setTitle(titulo)
            .setItems(ESTILOS, (d, i) -> {
                if (i == ESTILOS.length - 1) {
                    final android.widget.EditText campo = new android.widget.EditText(this);
                    campo.setHint("Ej.: Flamenco, Jazz, Infantil…");
                    campo.setSingleLine(true);
                    new AlertDialog.Builder(this).setTitle("Nuevo estilo").setView(campo)
                        .setPositiveButton("ACEPTAR", (d2, w) -> {
                            String texto = campo.getText().toString().trim();
                            listo.accept(texto.isEmpty() ? "Sin clasificar" : texto);
                        })
                        .setNegativeButton("CANCELAR", null).show();
                } else {
                    listo.accept(ESTILOS[i]);
                }
            })
            .setNegativeButton("CANCELAR", null).show();
    }
    private void pedirEstiloYGuardar(final Uri uri, final String clave, final String titulo) {
        elegirEstilo("¿De qué estilo es?", estilo -> guardarFavoritaConEstilo(uri, clave, titulo, estilo));
    }
    private void guardarFavoritaConEstilo(Uri uri, String clave, String titulo, String estilo) {
        favoritos.edit().putString(clave, titulo).apply();
        aviso("Guardada en favoritas. Copiando al NAS…");
        try { copiarFavoritaAlNas(uri, estilo); }
        catch (Throwable e) { aviso("Favorita guardada; error al iniciar NAS"); android.util.Log.e("KULI_NAS", "Inicio NAS", e); }
        actualizarBotonFavorito();
    }
    // Copia una canción seleccionada al NAS solo con WiFi. No borra el original.
    private void copiarFavoritaAlNas(Uri uri, String estilo) {
        // Ejecutar TODA la operación de red fuera del hilo principal.
        new Thread(() -> {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    runOnUiThread(() -> aviso("Favorita guardada; para copiar al NAS necesitas WiFi"));
                    return;
                }
                String nombre = "cancion.mp3";
                try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) nombre = c.getString(0);
                } catch (Exception ignored) { }
                if (nombre == null || nombre.trim().isEmpty()) nombre = "cancion.mp3";
                nombre = nombre.replaceAll("[\\/:*?\"<>|]", "_");
                final String archivo = nombre;
                String carpeta = estilo == null ? "" : estilo.replaceAll("[\\/:*?\"<>|]", "_").trim();
                if (carpeta.isEmpty()) carpeta = "Sin clasificar";
                final String estiloFinal = carpeta;
                final String ruta = "KULI MUSIC\\" + estiloFinal + "\\" + archivo;
                // Prueba varias formas de conectar: invitado/anónimo, con SMB por defecto y con SMB 2.1.
                SmbConfig[] configs = {
                    SmbConfig.createDefaultConfig(),
                    SmbConfig.builder().withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
                        .withSigningRequired(false).build()
                };
                AuthenticationContext[] cuentas = {
                    AuthenticationContext.guest(),
                    AuthenticationContext.anonymous()
                };
                long total = -1;
                Throwable ultimo = null;
                buscar:
                for (SmbConfig cfg : configs) {
                    for (AuthenticationContext ctx : cuentas) {
                        try {
                            total = subirAlNas(uri, ruta, cfg, ctx);
                            break buscar;
                        } catch (Throwable e) {
                            ultimo = e;
                            android.util.Log.e("KULI_NAS", "Intento fallido", e);
                        }
                    }
                }
                if (total < 0) throw ultimo != null ? ultimo : new java.io.IOException("Sin conexión con el NAS");
                final long bytes = total;
                runOnUiThread(() -> aviso("NAS · " + estiloFinal + ": " + archivo + " (" + bytes + " bytes)"));
            } catch (Throwable e) {
                android.util.Log.e("KULI_NAS", "Error al copiar favorita", e);
                String detalle = e.getClass().getSimpleName();
                if (e.getMessage() != null) detalle += ": " + e.getMessage();
                final String mensaje = detalle;
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("No se pudo copiar al NAS")
                        .setMessage(mensaje)
                        .setPositiveButton("CERRAR", null).show());
            }
        }).start();
    }

    private long subirAlNas(Uri uri, String ruta, SmbConfig cfg, AuthenticationContext ctx) throws Exception {
        long total = 0;
        try (SMBClient cliente = new SMBClient(cfg);
             Connection conexion = cliente.connect(nasIp())) {
            Session sesion = conexion.authenticate(ctx);
            try (DiskShare disco = (DiskShare) sesion.connectShare("Public");
                 InputStream origen = getContentResolver().openInputStream(uri)) {
                if (origen == null) throw new java.io.IOException("No se puede leer el MP3");
                if (!disco.folderExists("KULI MUSIC"))
                    throw new java.io.IOException("No existe la carpeta Public/KULI MUSIC");
                String carpetaEstilo = ruta.substring(0, ruta.lastIndexOf('\\'));
                if (!disco.folderExists(carpetaEstilo)) disco.mkdir(carpetaEstilo);
                try (File destino = disco.openFile(ruta,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                     OutputStream salida = destino.getOutputStream()) {
                    byte[] buffer = new byte[65536];
                    int leidos;
                    while ((leidos = origen.read(buffer)) != -1) {
                        salida.write(buffer, 0, leidos);
                        total += leidos;
                    }
                    salida.flush();
                }
                if (total == 0 || !disco.fileExists(ruta))
                    throw new java.io.IOException("No se pudo verificar el archivo en Public/KULI MUSIC");
            }
        }
        return total;
    }

    // ---------- Buscar música por épocas ----------
    private void abrirEnlace(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { aviso("No se pudo abrir el enlace"); }
    }
    private void abrirBuscador() {
        final String[] epocas = {"🔥 Novedades de ahora", "Años 80", "Años 90", "Años 2000", "Música libre (Jamendo)"};
        final String[] consultas = {
            "", "éxitos música años 80", "éxitos música años 90", "éxitos música años 2000"};
        new AlertDialog.Builder(this).setTitle("⌕ BUSCAR MÚSICA")
            .setItems(epocas, (d, i) -> {
                if (i == 0) { abrirNovedades(); return; }
                if (i == 4) { abrirEnlace("https://www.jamendo.com/search"); return; }
                elegirServicio(epocas[i], consultas[i]);
            })
            .setNegativeButton("CERRAR", null).show();
    }
    private void abrirNovedades() {
        final String[] temas = {"Nuevos lanzamientos", "Top 50 España", "Éxitos del momento",
                                "Novedades latino", "Novedades pop", "Novedades flamenco"};
        final String[] consultas = {"nuevos lanzamientos música", "top 50 España", "éxitos del momento",
                                    "novedades música latina", "novedades pop", "novedades flamenco pop"};
        new AlertDialog.Builder(this).setTitle("🔥 Novedades · ¿qué buscas?")
            .setItems(temas, (d, i) -> elegirServicio(temas[i], consultas[i]))
            .setNegativeButton("ATRÁS", (d, w) -> abrirBuscador()).show();
    }
    private void elegirServicio(String epoca, String consulta) {
        final String q = Uri.encode(consulta);
        final String[] servicios = {"YouTube Music", "YouTube", "Spotify", "Deezer"};
        final String[] enlaces = {
            "https://music.youtube.com/search?q=" + q,
            "https://www.youtube.com/results?search_query=" + Uri.encode(consulta + " mix"),
            "https://open.spotify.com/search/" + q,
            "https://www.deezer.com/search/" + q};
        new AlertDialog.Builder(this).setTitle(epoca + " · ¿dónde buscar?")
            .setItems(servicios, (d, i) -> abrirEnlace(enlaces[i]))
            .setNegativeButton("ATRÁS", (d, w) -> abrirBuscador()).show();
    }

    // ---------- Canciones del NAS ----------
    private interface NasTarea<T> { T ejecutar(DiskShare disco) throws Exception; }
    private int pedidoNas = 0;

    private boolean hayWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
    }

    // Conecta con el NAS probando invitado/anónimo y dos versiones de SMB, y ejecuta la tarea.
    private <T> T conNas(NasTarea<T> tarea) throws Throwable {
        SmbConfig[] configs = {
            SmbConfig.createDefaultConfig(),
            SmbConfig.builder().withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
                .withSigningRequired(false).build()
        };
        AuthenticationContext[] cuentas = { AuthenticationContext.guest(), AuthenticationContext.anonymous() };
        Throwable ultimo = null;
        for (SmbConfig cfg : configs) {
            for (AuthenticationContext ctx : cuentas) {
                try (SMBClient cliente = new SMBClient(cfg);
                     Connection conexion = cliente.connect(nasIp())) {
                    Session sesion = conexion.authenticate(ctx);
                    try (DiskShare disco = (DiskShare) sesion.connectShare("Public")) {
                        return tarea.ejecutar(disco);
                    }
                } catch (Throwable e) {
                    ultimo = e;
                    android.util.Log.e("KULI_NAS", "Intento fallido", e);
                }
            }
        }
        throw ultimo != null ? ultimo : new java.io.IOException("Sin conexión con el NAS");
    }

    private boolean esAudio(String n) {
        String m = n.toLowerCase();
        return m.endsWith(".mp3") || m.endsWith(".m4a") || m.endsWith(".aac") || m.endsWith(".ogg")
            || m.endsWith(".flac") || m.endsWith(".wav") || m.endsWith(".opus");
    }
    private String nombreSinExtension(String n) {
        if (n == null) return "Canción del NAS";
        int barra = n.lastIndexOf('/');
        if (barra >= 0) n = n.substring(barra + 1);
        int p = n.lastIndexOf('.');
        return p > 0 ? n.substring(0, p) : n;
    }
    private Uri uriNas(String nombre) { return Uri.parse("nas://kuli/" + Uri.encode(nombre)); }

    private void mostrarErrorNas(String titulo, Throwable e) {
        android.util.Log.e("KULI_NAS", titulo, e);
        if (isDestroyed()) return;
        String m = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        new AlertDialog.Builder(this).setTitle(titulo).setMessage(m)
            .setPositiveButton("CERRAR", null).show();
    }

    private void abrirListaNas() {
        if (!hayWifi()) { aviso("Necesitas la WiFi de casa (o la VPN) para ver el NAS"); return; }
        aviso("Conectando con el NAS…");
        new Thread(() -> {
            try {
                final LinkedHashMap<String, ArrayList<String>> grupos = conNas(disco -> {
                    TreeMap<String, ArrayList<String>> carpetas = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    ArrayList<String> sueltas = new ArrayList<>();
                    for (var f : disco.list("KULI MUSIC")) {
                        String n = f.getFileName();
                        if (n.equals(".") || n.equals("..")) continue;
                        if ((f.getFileAttributes() & 0x10L) != 0) {
                            ArrayList<String> dentro = new ArrayList<>();
                            for (var g : disco.list("KULI MUSIC\\" + n)) {
                                String m = g.getFileName();
                                if ((g.getFileAttributes() & 0x10L) == 0 && esAudio(m)) dentro.add(n + "/" + m);
                            }
                            Collections.sort(dentro, String.CASE_INSENSITIVE_ORDER);
                            if (!dentro.isEmpty()) carpetas.put(n, dentro);
                        } else if (esAudio(n)) {
                            sueltas.add(n);
                        }
                    }
                    Collections.sort(sueltas, String.CASE_INSENSITIVE_ORDER);
                    LinkedHashMap<String, ArrayList<String>> r = new LinkedHashMap<>(carpetas);
                    if (!sueltas.isEmpty()) r.put("Sin clasificar", sueltas);
                    return r;
                });
                runOnUiThread(() -> mostrarGruposNas(grupos));
            } catch (Throwable e) {
                runOnUiThread(() -> mostrarErrorNas("No se pudo abrir el NAS", e));
            }
        }).start();
    }

    private void mostrarGruposNas(final LinkedHashMap<String, ArrayList<String>> grupos) {
        if (grupos.isEmpty()) { aviso("No hay canciones en la carpeta KULI MUSIC del NAS"); return; }
        final ArrayList<String> claves = new ArrayList<>(grupos.keySet());
        final ArrayList<String> todas = new ArrayList<>();
        String[] items = new String[claves.size()];
        for (int i = 0; i < items.length; i++) {
            ArrayList<String> lista = grupos.get(claves.get(i));
            items[i] = "📁  " + claves.get(i) + "  (" + lista.size() + ")";
            todas.addAll(lista);
        }
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("☁ NAS · " + todas.size() + " canciones")
            .setItems(items, (d, i) -> mostrarCancionesNas(claves.get(i), grupos.get(claves.get(i)), grupos))
            .setPositiveButton("▶ TODO", (d, w) -> reproducirListaNas(todas, 0))
            .setNeutralButton("🔍 BUSCAR", (d, w) -> buscarEnNas(grupos))
            .setNegativeButton("CERRAR", null).show();
        // Mantener pulsada una carpeta: suena entera, en aleatorio.
        dlg.getListView().setOnItemLongClickListener((padre, vista, pos, id) -> {
            dlg.dismiss();
            reproducirAleatorio(grupos.get(claves.get(pos)));
            return true;
        });
        aviso("Toca una carpeta para verla · mantenla pulsada para oírla entera en aleatorio");
    }

    private void reproducirAleatorio(List<String> rutas) {
        if (rutas.isEmpty()) return;
        aleatorio = true;
        estiloBoton(mezcla, CONTORNO, 1000);
        reproducirListaNas(rutas, new java.util.Random().nextInt(rutas.size()));
    }

    // Quita acentos y mayúsculas para que "años" encuentre "Anos" y "AÑOS".
    private String simple(String t) {
        String n = java.text.Normalizer.normalize(t == null ? "" : t, java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase();
    }

    private void pedirTexto(String titulo, final java.util.function.Consumer<String> alAceptar) {
        final android.widget.EditText campo = new android.widget.EditText(this);
        campo.setSingleLine(true);
        campo.setHint("Nombre de la canción o artista");
        new AlertDialog.Builder(this).setTitle(titulo).setView(campo)
            .setPositiveButton("BUSCAR", (d, w) -> {
                String q = campo.getText().toString().trim();
                if (!q.isEmpty()) alAceptar.accept(q);
            })
            .setNegativeButton("CANCELAR", null).show();
    }

    private void buscarEnNas(final LinkedHashMap<String, ArrayList<String>> grupos) {
        pedirTexto("🔍 Buscar en el NAS", q -> {
            String clave = simple(q);
            ArrayList<String> hallados = new ArrayList<>();
            for (ArrayList<String> lista : grupos.values())
                for (String ruta : lista)
                    if (simple(ruta.replace('/', ' ')).contains(clave)) hallados.add(ruta);
            if (hallados.isEmpty()) { aviso("No hay nada con «" + q + "»"); return; }
            mostrarCancionesNas("«" + q + "»", hallados, grupos);
        });
    }

    private void buscarEnFavoritas() {
        final Map<String, ?> datos = favoritos.getAll();
        pedirTexto("🔍 Buscar en favoritas", q -> {
            String clave = simple(q);
            final ArrayList<String> claves = new ArrayList<>();
            for (String k : datos.keySet())
                if (simple(String.valueOf(datos.get(k))).contains(clave)) claves.add(k);
            if (claves.isEmpty()) { aviso("No hay nada con «" + q + "»"); return; }
            Collections.sort(claves, (a, b) ->
                String.valueOf(datos.get(a)).compareToIgnoreCase(String.valueOf(datos.get(b))));
            String[] nombres = new String[claves.size()];
            for (int n = 0; n < nombres.length; n++) nombres[n] = String.valueOf(datos.get(claves.get(n)));
            new AlertDialog.Builder(this).setTitle("🔍 «" + q + "» · " + claves.size())
                .setItems(nombres, (d, i) -> {
                    listaFavoritas.clear(); listaFavoritas.addAll(claves);
                    modoLista = true; posicionFavorita = i;
                    reproducir(Uri.parse(listaFavoritas.get(i)));
                })
                .setPositiveButton("▶ TODAS", (d, w) -> {
                    listaFavoritas.clear(); listaFavoritas.addAll(claves);
                    modoLista = true; posicionFavorita = 0;
                    reproducir(Uri.parse(listaFavoritas.get(0)));
                })
                .setNegativeButton("CERRAR", null).show();
        });
    }

    private void mostrarCancionesNas(String estilo, final ArrayList<String> rutas,
                                     final LinkedHashMap<String, ArrayList<String>> grupos) {
        String[] items = new String[rutas.size()];
        for (int i = 0; i < items.length; i++) items[i] = nombreSinExtension(rutas.get(i));
        final boolean hayMasGrupos = grupos.size() > 1;
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("📁 " + estilo + " · " + rutas.size())
            .setItems(items, (d, i) -> reproducirListaNas(rutas, i))
            .setPositiveButton("▶ TODAS", (d, w) -> reproducirListaNas(rutas, 0))
            .setNeutralButton("🔀 ALEATORIO", (d, w) -> reproducirAleatorio(rutas))
            .setNegativeButton(hayMasGrupos ? "ATRÁS" : "CERRAR", (d, w) -> {
                if (hayMasGrupos) mostrarGruposNas(grupos);
            }).show();
        dlg.getListView().setOnItemLongClickListener((padre, vista, pos, id) -> {
            dlg.dismiss();
            final String ruta = rutas.get(pos);
            elegirEstilo("Mover «" + nombreSinExtension(ruta) + "» a…", elegido -> moverCancionNas(ruta, elegido));
            return true;
        });
        aviso("Mantén pulsada una canción para cambiarle el estilo");
    }

    // Mueve una canción de una carpeta de estilo a otra, dentro del NAS.
    private void moverCancionNas(final String rutaRel, String estiloElegido) {
        String carpeta = estiloElegido.replaceAll("[\\/:*?\"<>|]", "_").trim();
        if (carpeta.isEmpty()) carpeta = "Sin clasificar";
        final String estiloNuevo = carpeta;
        final String archivo = rutaRel.substring(rutaRel.lastIndexOf('/') + 1);
        final String nuevaRel = estiloNuevo + "/" + archivo;
        if (nuevaRel.equals(rutaRel)) { aviso("Ya está en ese estilo"); return; }
        final String origen = "KULI MUSIC\\" + rutaRel.replace('/', '\\');
        final String destino = "KULI MUSIC\\" + estiloNuevo + "\\" + archivo;
        aviso("Moviendo…");
        new Thread(() -> {
            try {
                conNas(disco -> {
                    if (!disco.folderExists("KULI MUSIC\\" + estiloNuevo)) disco.mkdir("KULI MUSIC\\" + estiloNuevo);
                    try (File f = disco.openFile(origen,
                            EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                        f.rename(destino);
                    }
                    return null;
                });
                runOnUiThread(() -> {
                    actualizarFavoritoMovido(rutaRel, nuevaRel);
                    aviso("Movida a " + estiloNuevo);
                    abrirListaNas();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> mostrarErrorNas("No se pudo mover la canción", e));
            }
        }).start();
    }

    // Si la canción movida estaba en favoritas, actualiza su ruta para que siga sonando.
    private void actualizarFavoritoMovido(String vieja, String nueva) {
        String claveVieja = uriNas(vieja).toString();
        String titulo = favoritos.getString(claveVieja, null);
        if (titulo != null) {
            favoritos.edit().remove(claveVieja).putString(uriNas(nueva).toString(), titulo).apply();
        }
    }

    private void reproducirListaNas(List<String> rutas, int inicio) {
        cargarListaNas(rutas);
        modoLista = true;
        posicionFavorita = inicio;
        reproducir(Uri.parse(listaFavoritas.get(inicio)));
    }

    private void cargarListaNas(List<String> nombres) {
        listaFavoritas.clear();
        for (String n : nombres) listaFavoritas.add(uriNas(n).toString());
    }

    private java.io.File archivoCache(String nombre) {
        java.io.File dir = new java.io.File(getCacheDir(), "nas");
        dir.mkdirs();
        return new java.io.File(dir, nombre.replace('/', '_'));
    }
    // Borra de la caché todo menos la canción actual y la siguiente.
    private void limpiarCache(String a, String b) {
        java.io.File[] fs = new java.io.File(getCacheDir(), "nas").listFiles();
        if (fs == null) return;
        if (a != null) a = a.replace('/', '_');
        if (b != null) b = b.replace('/', '_');
        for (java.io.File f : fs) {
            String n = f.getName();
            if (a != null && (n.equals(a) || n.startsWith(a + "."))) continue;
            if (b != null && (n.equals(b) || n.startsWith(b + "."))) continue;
            f.delete();
        }
    }
    private String nombreSiguienteNas() {
        int n = indiceSiguiente();
        if (n < 0) return null;
        Uri u = Uri.parse(listaFavoritas.get(n));
        return "nas".equals(u.getScheme()) ? u.getLastPathSegment() : null;
    }

    private void descargarDelNas(final String nombre, final java.io.File destino) throws Throwable {
        conNas(disco -> {
            java.io.File tmp = new java.io.File(destino.getPath() + "." + System.nanoTime() + ".part");
            try (File origen = disco.openFile("KULI MUSIC\\" + nombre.replace('/', '\\'),
                        EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                 InputStream in = origen.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buffer = new byte[65536];
                int leidos;
                while ((leidos = in.read(buffer)) != -1) out.write(buffer, 0, leidos);
            } catch (Exception e) {
                tmp.delete();
                throw e;
            }
            if (!tmp.renameTo(destino)) {
                tmp.delete();
                throw new java.io.IOException("No se pudo guardar en la caché");
            }
            return null;
        });
    }

    private void reproducirDelNas(final Uri nasUri) {
        final String nombre = nasUri.getLastPathSegment();
        final java.io.File destino = archivoCache(nombre);
        final int pedido = ++pedidoNas;
        limpiarCache(nombre, nombreSiguienteNas());
        if (destino.exists() && destino.length() > 0) {
            reproducirArchivo(Uri.fromFile(destino), nasUri);
            return;
        }
        estado.setText("☁ DESCARGANDO DEL NAS…");
        new Thread(() -> {
            try {
                descargarDelNas(nombre, destino);
                runOnUiThread(() -> {
                    if (pedido == pedidoNas) reproducirArchivo(Uri.fromFile(destino), nasUri);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    if (pedido == pedidoNas) mostrarErrorNas("No se pudo traer la canción del NAS", e);
                });
            }
        }).start();
    }

    // Descarga en segundo plano la siguiente canción para que no haya pausa entre una y otra.
    private void precargarNas(Uri nasUri) {
        final String nombre = nasUri.getLastPathSegment();
        final java.io.File destino = archivoCache(nombre);
        if (destino.exists() && destino.length() > 0) return;
        new Thread(() -> {
            try { descargarDelNas(nombre, destino); }
            catch (Throwable ignored) { }
        }).start();
    }

    // ---------- Subir canciones del móvil (p. ej. las de YMusic) al NAS ----------
    private void elegirCancionesParaSubir() {
        if (!hayWifi()) { aviso("Necesitas la WiFi de casa (o la VPN) para subir al NAS"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_SUBIR);
    }

    private String nombreDeUri(Uri uri) {
        String nombre = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) nombre = c.getString(0);
        } catch (Exception ignored) { }
        if (nombre == null || nombre.trim().isEmpty()) nombre = "cancion_" + System.currentTimeMillis() + ".mp3";
        return nombre.replaceAll("[\\/:*?\"<>|]", "_");
    }

    private long copiarAlDisco(DiskShare disco, Uri uri, String ruta) throws Exception {
        long total = 0;
        try (InputStream origen = getContentResolver().openInputStream(uri)) {
            if (origen == null) throw new java.io.IOException("No se puede leer el archivo");
            try (File destino = disco.openFile(ruta,
                    EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                 OutputStream salida = destino.getOutputStream()) {
                byte[] buffer = new byte[65536];
                int leidos;
                while ((leidos = origen.read(buffer)) != -1) {
                    salida.write(buffer, 0, leidos);
                    total += leidos;
                }
                salida.flush();
            }
        }
        if (total == 0) throw new java.io.IOException("El archivo está vacío");
        return total;
    }

    private void subirVariasAlNas(final List<Uri> uris, String estiloElegido) {
        subirVariasAlNas(uris, estiloElegido, null, null);
    }

    private void subirVariasAlNas(final List<Uri> uris, String estiloElegido,
                                  final java.util.function.Consumer<Uri> alSubir) {
        subirVariasAlNas(uris, estiloElegido, alSubir, null);
    }

    private void subirVariasAlNas(final List<Uri> uris, String estiloElegido,
                                  final java.util.function.Consumer<Uri> alSubir, final Runnable alFin) {
        String carpeta = estiloElegido.replaceAll("[\\/:*?\"<>|]", "_").trim();
        if (carpeta.isEmpty()) carpeta = "Sin clasificar";
        final String estilo = carpeta;
        final int total = uris.size();
        aviso("Subiendo " + total + " al NAS (" + estilo + ")…");
        new Thread(() -> {
            final int[] hechas = {0};
            final int[] repetidas = {0};
            final ArrayList<String> fallos = new ArrayList<>();
            try {
                conNas(disco -> {
                    hechas[0] = 0;
                    repetidas[0] = 0;
                    fallos.clear();
                    String dir = "KULI MUSIC\\" + estilo;
                    if (!disco.folderExists("KULI MUSIC"))
                        throw new java.io.IOException("No existe la carpeta Public/KULI MUSIC");
                    if (!disco.folderExists(dir)) disco.mkdir(dir);
                    // Canciones que ya hay en cualquier carpeta del NAS (para no duplicar).
                    java.util.HashSet<String> existentes = new java.util.HashSet<>();
                    for (var f : disco.list("KULI MUSIC")) {
                        String n = f.getFileName();
                        if (n.equals(".") || n.equals("..")) continue;
                        if ((f.getFileAttributes() & 0x10L) != 0) {
                            for (var g : disco.list("KULI MUSIC\\" + n))
                                if ((g.getFileAttributes() & 0x10L) == 0) existentes.add(claveNombre(g.getFileName()));
                        } else {
                            existentes.add(claveNombre(n));
                        }
                    }
                    for (int k = 0; k < uris.size(); k++) {
                        String nombre = nombreDeUri(uris.get(k));
                        String clave = claveNombre(nombre);
                        if (existentes.contains(clave)) {
                            repetidas[0]++;
                            if (alSubir != null) alSubir.accept(uris.get(k));
                            continue;
                        }
                        try {
                            copiarAlDisco(disco, uris.get(k), dir + "\\" + nombre);
                            existentes.add(clave);
                            hechas[0]++;
                            if (alSubir != null) alSubir.accept(uris.get(k));
                        } catch (Exception e) {
                            fallos.add(nombre + " (" + e.getMessage() + ")");
                        }
                    }
                    return null;
                });
                runOnUiThread(() -> {
                    String rep = repetidas[0] == 0 ? "" : " · " + repetidas[0] + " ya estaban (no se repiten)";
                    if (fallos.isEmpty()) {
                        aviso(hechas[0] == 0 && repetidas[0] > 0
                            ? "Ya estaban todas en el NAS (" + repetidas[0] + ")"
                            : "✓ " + hechas[0] + " subidas a " + estilo + rep);
                    } else {
                        new AlertDialog.Builder(this)
                            .setTitle(hechas[0] + " subidas a " + estilo + rep)
                            .setMessage("No se pudieron subir:\n\n" + android.text.TextUtils.join("\n", fallos))
                            .setPositiveButton("CERRAR", null).show();
                    }
                    if (alFin != null) alFin.run();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> mostrarErrorNas("No se pudo subir al NAS", e));
            }
        }).start();
    }

    private void mostrarFavoritas() {
        Map<String, ?> datos = favoritos.getAll();
        if (datos.isEmpty()) { aviso("Aún no tienes canciones favoritas"); return; }
        listaFavoritas.clear();
        listaFavoritas.addAll(datos.keySet());
        Collections.sort(listaFavoritas, (a, b) ->
            String.valueOf(datos.get(a)).compareToIgnoreCase(String.valueOf(datos.get(b))));
        String[] nombres = new String[listaFavoritas.size()];
        for (int n = 0; n < listaFavoritas.size(); n++)
            nombres[n] = String.valueOf(datos.get(listaFavoritas.get(n)));
        new AlertDialog.Builder(this).setTitle("♥ MIS FAVORITAS · REPRODUCCIÓN CONTINUA")
            .setItems(nombres, (dialog, index) -> {
                modoLista = true;
                posicionFavorita = index;
                reproducir(Uri.parse(listaFavoritas.get(index)));
            })
            .setPositiveButton("▶ TODAS", (dialog, which) -> {
                modoLista = true;
                posicionFavorita = 0;
                reproducir(Uri.parse(listaFavoritas.get(0)));
            })
            .setNeutralButton("🔍 BUSCAR", (dialog, which) -> buscarEnFavoritas())
            .setNegativeButton("CERRAR", null).show();
    }
    private void saltar(int direccion) {
        if (!modoLista || listaFavoritas.isEmpty()) {
            aviso("Primero abre FAVORITAS"); return;
        }
        int siguienteIndice;
        if (aleatorio && direccion > 0 && listaFavoritas.size() > 1) {
            siguienteIndice = new java.util.Random().nextInt(listaFavoritas.size() - 1);
            if (siguienteIndice >= posicionFavorita) siguienteIndice++;
        } else {
            siguienteIndice = posicionFavorita + direccion;
        }
        if (siguienteIndice >= listaFavoritas.size()) {
            if (!repetir) {
                modoLista = false;
                estado.setText("✓ LISTA TERMINADA");
                detenerServicio();
                return;
            }
            siguienteIndice = 0;
        }
        if (siguienteIndice < 0) siguienteIndice = listaFavoritas.size() - 1;
        posicionFavorita = siguienteIndice;
        reproducir(Uri.parse(listaFavoritas.get(posicionFavorita)));
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(ticker);
        if (instancia != this) { super.onDestroy(); return; }
        boolean suena = false;
        try { suena = reproductor != null && reproductor.isPlaying(); } catch (Exception ignored) { }
        if (suena) { super.onDestroy(); return; }   // sigue sonando en segundo plano
        instancia = null;
        detenerServicio();
        liberarSiguiente();
        if (reproductor != null) { reproductor.release(); reproductor = null; }
        super.onDestroy();
    }
    // ---------- TV dentro de KULI (lista pública de canales de España) ----------
    private ArrayList<String> tvNombres, tvUrls;

    private void abrirTv() {
        if (tvNombres != null && !tvNombres.isEmpty()) { mostrarCanalesTv(null); return; }
        aviso("Cargando canales…");
        new Thread(() -> {
            String[] fuentes = {"https://www.tdtchannels.com/lists/tv.m3u",
                                "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
                                "https://iptv-org.github.io/iptv/index.m3u"};
            final ArrayList<String> nombres = new ArrayList<>(), urls = new ArrayList<>();
            java.util.HashSet<String> vistos = new java.util.HashSet<>();
            for (String f : fuentes) {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(f).openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "KULIMusic/3.12");
                    String nombre = null, grupo = null, tvgId = null;
                    boolean soloEs = f.contains("Free-TV");
                    boolean porId = f.contains("iptv-org");
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String l;
                        while ((l = r.readLine()) != null) {
                            l = l.trim();
                            if (l.startsWith("#EXTINF")) {
                                int k = l.lastIndexOf(',');
                                nombre = k >= 0 ? l.substring(k + 1).trim() : null;
                                grupo = null; tvgId = null;
                                int t = l.indexOf("tvg-id=\"");
                                if (t >= 0) { int u2 = l.indexOf('"', t + 8); if (u2 > t) tvgId = l.substring(t + 8, u2); }
                                int g = l.indexOf("group-title=\"");
                                if (g >= 0) { int h = l.indexOf('"', g + 13); if (h > g) grupo = l.substring(g + 13, h); }
                            } else if (!l.isEmpty() && !l.startsWith("#")) {
                                if (nombre != null && !nombre.isEmpty() && l.startsWith("http") && (!soloEs || esEspana(grupo)) && (!porId || (tvgId != null && tvgId.toLowerCase().contains(".es")))
                                        && vistos.add(nombre.toLowerCase())) {
                                    nombres.add(nombre); urls.add(l);
                                }
                                nombre = null;
                            }
                        }
                    }
                } catch (Exception e) { android.util.Log.e("KULI_TV", f, e); }
            }
            runOnUiThread(() -> {
                if (nombres.isEmpty()) aviso("No se pudo cargar la lista pública. Revisa tu conexión.");
                tvNombres = nombres; tvUrls = urls;
                mostrarCanalesTv(null);
            });
        }).start();
    }

    private boolean esEspana(String g) {
        if (g == null) return false;
        String t = g.toLowerCase();
        return t.contains("spain") || t.contains("españa") || t.contains("spanish");
    }

    private void mostrarCanalesTv(final String filtro) {
        if (isDestroyed()) return;
        final ArrayList<String> n = new ArrayList<>(), u = new ArrayList<>();
        for (int i = 0; i < tvNombres.size(); i++)
            if (filtro == null || (filtro.equals("\u0001DEPORTES") ? esDeportivo(tvNombres.get(i))
                    : tvNombres.get(i).toLowerCase().contains(filtro.toLowerCase()))) {
                n.add(tvNombres.get(i)); u.add(tvUrls.get(i));
            }
        if (filtro == null) {
            String[][] web = {
                {"antena3", "📺 Antena 3 (abre Atresplayer)", "https://www.atresplayer.com/directos/antena3/"},
                {"telecinco", "📺 Telecinco (abre Mitele)", "https://www.mitele.es/directo/telecinco/"},
                {"lasexta", "📺 laSexta (abre Atresplayer)", "https://www.atresplayer.com/directos/lasexta/"},
                {"cuatro", "📺 Cuatro (abre Mitele)", "https://www.mitele.es/directo/cuatro/"}};
            int pos = 0;
            for (String[] w : web) {
                boolean esta = false;
                for (String x : tvNombres) if (x.toLowerCase().replace(" ", "").contains(w[0])) esta = true;
                if (!esta) { n.add(pos, w[1]); u.add(pos, "web:" + w[2]); pos++; }
            }
        }
        if (n.isEmpty()) { aviso("No hay canales con ese nombre"); return; }
        new AlertDialog.Builder(this).setTitle(filtro != null && filtro.equals("\u0001DEPORTES") ? "⚽ Deportes" : "📺 TV en directo")
            .setPositiveButton(filtro == null ? "⚽ DEPORTES" : "📺 TODOS", (d, w) ->
                mostrarCanalesTv(filtro == null ? "\u0001DEPORTES" : null))
            .setItems(n.toArray(new String[0]), (d, i) -> {
                if (u.get(i).startsWith("web:")) abrirEnlace(u.get(i).substring(4));
                else reproducirTv(n.get(i), u.get(i));
            })
            .setNeutralButton("🔍 BUSCAR", (d, w) ->
                pedirTexto("🔍 Buscar canal (ej. La 1)", q -> mostrarCanalesTv(q)))
            .setNegativeButton("CERRAR", null).show();
    }

    private boolean esDeportivo(String nombre) {
        String t = nombre.toLowerCase();
        String[] claves = {"deport", "sport", "gol", "marca", "real madrid", "barça", "barca", "futbol", "fútbol",
                           "motor", "toros", "caza", "pesca", "padel", "pádel", "tenis", "basket", "baloncesto", "esport"};
        for (String c : claves) if (t.contains(c)) return true;
        return false;
    }

    private void reproducirTv(final String nombre, final String url) {
        try { if (reproductor != null && reproductor.isPlaying()) reproductor.pause(); } catch (Exception ignored) { }
        final android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout caja = new FrameLayout(this);
        caja.setBackgroundColor(Color.BLACK);
        final android.widget.VideoView video = new android.widget.VideoView(this);
        caja.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        final TextView info = new TextView(this);
        info.setText("📺 " + nombre + "\nConectando…");
        info.setTextColor(Color.WHITE); info.setTextSize(16); info.setGravity(Gravity.CENTER);
        caja.addView(info, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        dlg.setContentView(caja);
        video.setOnPreparedListener(mp -> info.setVisibility(View.GONE));
        video.setOnErrorListener((mp, w, x) -> {
            aviso("No se pudo abrir " + nombre + ". Prueba con otro canal.");
            dlg.dismiss();
            mostrarCanalesTv(null);
            return true;
        });
        dlg.setOnDismissListener(d -> { try { video.stopPlayback(); } catch (Exception ignored) { } });
        dlg.show();
        video.setVideoURI(Uri.parse(url));
        video.start();
    }

    private void elegirAppTv() {
        final android.content.pm.PackageManager pm = getPackageManager();
        Intent principal = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final ArrayList<android.content.pm.ResolveInfo> apps = new ArrayList<>();
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(principal, 0))
            if (!r.activityInfo.packageName.equals(getPackageName())) apps.add(r);
        Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        String[] nombres = new String[apps.size()];
        for (int i = 0; i < nombres.length; i++) nombres[i] = apps.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(this).setTitle("📺 Elige tu app de TV")
            .setItems(nombres, (d, i) -> {
                String pkg = apps.get(i).activityInfo.packageName;
                getSharedPreferences("kuli_ajustes", MODE_PRIVATE).edit().putString("tv_pkg", pkg).apply();
                Intent l = pm.getLaunchIntentForPackage(pkg);
                if (l != null) startActivity(l);
            })
            .setNeutralButton("🔍 BUSCAR EN INTERNET", (d, w) ->
                abrirEnlace("https://www.google.com/search?q=TDT+Channels+app+android"))
            .setNegativeButton("CERRAR", null).show();
    }

    private Intent buscarYMusic() {
        android.content.pm.PackageManager pm = getPackageManager();
        for (android.content.pm.ApplicationInfo app :
                pm.getInstalledApplications(0)) {
            String nombre = pm.getApplicationLabel(app).toString();
            if (nombre.toLowerCase().replace(" ", "").contains("ymusic") || app.packageName.toLowerCase().contains("ymusic")) {
                return pm.getLaunchIntentForPackage(app.packageName);
            }
        }
        return null;
    }

}
