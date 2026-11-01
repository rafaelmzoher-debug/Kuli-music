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
import java.util.Map;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 100;
    private static final String NAS_IP = "192.168.1.46";

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
                try { if (reproductor != null) reproductor.seekTo(s.getProgress()); }
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
        botonPlay.setOnClickListener(v -> {
            if (reproductor == null) { aviso("Selecciona una canción"); return; }
            try {
                if (reproductor.isPlaying()) {
                    reproductor.pause(); estado.setText("Ⅱ EN PAUSA");
                    detenerServicio();
                } else {
                    reproductor.start(); estado.setText("▶ REPRODUCIENDO");
                    iniciarServicio();
                }
            } catch (IllegalStateException e) { aviso("No se pudo reproducir"); }
            actualizarProgreso();
        });
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

        TextView pie = texto("✦ KULI EDITION ✦", 10, DORADO);
        pie.setAlpha(0.7f);
        pie.setLetterSpacing(0.2f);
        pantalla.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout.LayoutParams lpPie = new LinearLayout.LayoutParams(-1, -2);
        lpPie.topMargin = dp(16);
        pantalla.addView(pie, lpPie);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }
    @Override protected void onPause() {
        ui.removeCallbacks(ticker);
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
        try {
            liberarSiguiente();
            if (reproductor != null) { reproductor.release(); reproductor = null; }
            reproductor = abrirReproductor(uri);
            uriActual = uri;
            mostrarDatos(uri);
            reproductor.setOnCompletionListener(mp -> alTerminar());
            prepararSiguiente();
            reproductor.start(); estado.setText("▶ REPRODUCIENDO");
            iniciarServicio();
            arrastrando = false;
            actualizarProgreso();
        } catch (Exception e) {
            android.util.Log.e("KULI_PLAY", "No se pudo reproducir " + uri, e);
            String motivo = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            String ayuda = (e instanceof SecurityException)
                ? "\n\nAndroid ya no deja a la app leer esta canción. Vuelve a elegirla con «Mis canciones»."
                : "";
            new AlertDialog.Builder(this).setTitle("No se pudo reproducir")
                .setMessage(motivo + ayuda).setPositiveButton("CERRAR", null).show();
        }
    }

    // Notificación fija mientras suena, para que Android no pare la música en segundo plano.
    private void iniciarServicio() {
        try { startForegroundService(new Intent(this, MusicaService.class)); }
        catch (Throwable e) { android.util.Log.e("KULI_PLAY", "Servicio", e); }
    }
    private void detenerServicio() {
        try { stopService(new Intent(this, MusicaService.class)); }
        catch (Throwable ignored) { }
    }

    private MediaPlayer abrirReproductor(Uri uri) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, uri);
            mp.prepare();
            return mp;
        } catch (Exception e) {
            mp.release();
            throw e;
        }
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
            favoritos.edit().putString(clave, tituloActual + " — " + artistaActual).apply();
            aviso("Guardada en favoritas. Copiando al NAS…");
            try { copiarFavoritaAlNas(uriActual); }
            catch (Throwable e) { aviso("Favorita guardada; error al iniciar NAS"); android.util.Log.e("KULI_NAS", "Inicio NAS", e); }
        }
        actualizarBotonFavorito();
    }
    // Copia una canción seleccionada al NAS solo con WiFi. No borra el original.
    private void copiarFavoritaAlNas(Uri uri) {
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
                final String ruta = "KULI MUSIC\\" + archivo;
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
                runOnUiThread(() -> aviso("NAS: " + archivo + " (" + bytes + " bytes)"));
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
             Connection conexion = cliente.connect(NAS_IP)) {
            Session sesion = conexion.authenticate(ctx);
            try (DiskShare disco = (DiskShare) sesion.connectShare("Public");
                 InputStream origen = getContentResolver().openInputStream(uri)) {
                if (origen == null) throw new java.io.IOException("No se puede leer el MP3");
                if (!disco.folderExists("KULI MUSIC"))
                    throw new java.io.IOException("No existe la carpeta Public/KULI MUSIC");
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
        final String[] epocas = {"Años 80", "Años 90", "Años 2000", "Música libre (Jamendo)"};
        final String[] consultas = {
            "éxitos música años 80", "éxitos música años 90", "éxitos música años 2000"};
        new AlertDialog.Builder(this).setTitle("⌕ BUSCAR MÚSICA")
            .setItems(epocas, (d, i) -> {
                if (i == 3) { abrirEnlace("https://www.jamendo.com/search"); return; }
                elegirServicio(epocas[i], consultas[i]);
            })
            .setNegativeButton("CERRAR", null).show();
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
            .setPositiveButton("▶ REPRODUCIR TODAS", (dialog, which) -> {
                modoLista = true;
                posicionFavorita = 0;
                reproducir(Uri.parse(listaFavoritas.get(0)));
            })
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
        detenerServicio();
        liberarSiguiente();
        if (reproductor != null) { reproductor.release(); reproductor = null; }
        super.onDestroy();
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
