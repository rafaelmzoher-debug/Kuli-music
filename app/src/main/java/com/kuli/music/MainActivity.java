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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.ViewGroup;
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
import android.widget.ListView;
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
    private android.widget.TextView bannerNas;
    public static MainActivity instanciaActiva;
    private static final int PICK_AUDIO = 100;
    private static final int PICK_SUBIR = 101;
    private static final int PICK_CARPETA = 102, PICK_DESCARGAS = 104;
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
    private final int NEGRO = Color.rgb(5, 28, 62);
    private final int FONDO_ALTO = Color.rgb(12, 88, 168);
    private final int DORADO = Color.rgb(255, 255, 255);          // acento principal: blanco
    private final int ORO = Color.rgb(255, 205, 80);              // detalles dorados
    private final int TARJETA = Color.rgb(10, 62, 124);
    private final int GRIS = Color.rgb(170, 200, 235);
    private final int LINEA = Color.argb(110, 255, 255, 255);

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
    // Escudo propio de KULI (dibujado a mano, original).
    private static final class Escudo extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Escudo(android.content.Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            Path path = new Path();
            path.moveTo(w * 0.08f, h * 0.06f);
            path.lineTo(w * 0.92f, h * 0.06f);
            path.lineTo(w * 0.92f, h * 0.50f);
            path.quadTo(w * 0.92f, h * 0.84f, w * 0.50f, h * 0.97f);
            path.quadTo(w * 0.08f, h * 0.84f, w * 0.08f, h * 0.50f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(5, 28, 62));
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.07f);
            p.setColor(Color.WHITE);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(255, 205, 80));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(h * 0.52f);
            c.drawText("K", w * 0.5f, h * 0.64f, p);
            p.setColor(Color.WHITE);
            c.drawRect(w * 0.22f, h * 0.72f, w * 0.78f, h * 0.76f, p);
        }
    }

    private static final int MEZCLA_MS = 3000, MEZCLA_DJ_MS = 6000;
    private int mezclaDur = 3000;
    private MediaPlayer mezclaAnterior = null;
    private boolean mezclando = false;
    private long mezclaInicio = 0;

    private final Runnable rampaMezcla = new Runnable() {
        @Override public void run() {
            if (!mezclando) return;
            float t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - mezclaInicio) / (float) mezclaDur);
            float vn = (float) Math.sin(t * Math.PI / 2), va = (float) Math.cos(t * Math.PI / 2);
            try { if (reproductor != null) reproductor.setVolume(vn, vn); } catch (Exception ignorar) { }
            try { if (mezclaAnterior != null) mezclaAnterior.setVolume(va, va); } catch (Exception ignorar) { }
            if (t >= 1f) { cancelarMezcla(); return; }
            ui.postDelayed(this, 80);
        }
    };

    private void cancelarMezcla() {
        mezclando = false;
        ui.removeCallbacks(rampaMezcla);
        if (mezclaAnterior != null) {
            try { mezclaAnterior.stop(); } catch (Exception ignorar) { }
            try { mezclaAnterior.release(); } catch (Exception ignorar) { }
            mezclaAnterior = null;
        }
        try { if (reproductor != null) reproductor.setVolume(1f, 1f); } catch (Exception ignorar) { }
    }

    // Empieza ya la siguiente canción mientras la actual se apaga poco a poco.
    private void iniciarMezcla() {
        if (mezclando || siguienteReproductor == null || indicePreparado < 0 || reproductor == null) return;
        try {
            MediaPlayer anterior = reproductor;
            MediaPlayer nuevo = siguienteReproductor;
            int idx = indicePreparado;
            nuevo.setVolume(0f, 0f);
            nuevo.start();
            anterior.setOnCompletionListener(null);
            reproductor = nuevo;
            mezclaDur = djLista() ? MEZCLA_DJ_MS : MEZCLA_MS;
            posicionFavorita = idx;
            if (djLista()) { indiceDj = idx; refrescarEtiquetaDj(); }
            siguienteReproductor = null;
            indicePreparado = -1;
            mezclaAnterior = anterior;
            mezclando = true;
            mezclaInicio = android.os.SystemClock.uptimeMillis();
            mostrarDatos(Uri.parse(listaFavoritas.get(posicionFavorita)));
            reproductor.setOnCompletionListener(mp -> alTerminar());
            estado.setText("🎛 MEZCLANDO");
            iniciarServicio();
            prepararSiguiente();
            ui.post(rampaMezcla);
        } catch (Exception e) {
            android.util.Log.e("KULI_DJ", "Mezcla", e);
            cancelarMezcla();
        }
    }

    // Modo DJ: vigila el final de cada canción y la mezcla con la siguiente.
    private boolean djActivo = false, fundidoArrancado = false, volumenTocado = false;
    private final Runnable fundido = new Runnable() {
        @Override public void run() {
            try {
                if (reproductor != null && !esRadio && reproductor.isPlaying()) {
                    if ((djActivo || djLista()) && modoLista) {
                        if (!mezclando) {
                            int dur = reproductor.getDuration(), resto = dur - reproductor.getCurrentPosition();
                            if (dur > 12000 && resto <= (djLista() ? MEZCLA_DJ_MS : MEZCLA_MS) + 400) {
                                if (siguienteReproductor != null && indicePreparado >= 0) iniciarMezcla();
                                else if (resto < 2000) {
                                    float v = Math.max(0f, resto / 2000f);
                                    reproductor.setVolume(v, v);
                                    volumenTocado = true;
                                }
                            } else if (volumenTocado) {
                                reproductor.setVolume(1f, 1f);
                                volumenTocado = false;
                            }
                        }
                    } else if (volumenTocado) {
                        reproductor.setVolume(1f, 1f);
                        volumenTocado = false;
                    }
                }
            } catch (Exception ignorar) { }
            ui.postDelayed(this, 100);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            actualizarProgreso();
            ui.postDelayed(this, 400);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instanciaActiva = this;
        favoritos = getSharedPreferences("kuli_favoritos", MODE_PRIVATE);
        escuchaFavs = (sp, clave) -> guardarCopiaFavoritos();
        favoritos.registerOnSharedPreferenceChangeListener(escuchaFavs);
        getWindow().setStatusBarColor(FONDO_ALTO);
        getWindow().setNavigationBarColor(NEGRO);

        float density = getResources().getDisplayMetrics().density;
        int altoDp = (int) (getResources().getDisplayMetrics().heightPixels / density);
        int anchoDp = (int) (getResources().getDisplayMetrics().widthPixels / density);
        int portadaDp = Math.max(90, Math.min(Math.min(240, anchoDp - 72), altoDp - 640));

        LinearLayout pantalla = new LinearLayout(this);
        pantalla.setOrientation(LinearLayout.VERTICAL);
        pantalla.setGravity(Gravity.CENTER_HORIZONTAL);
        pantalla.setPadding(dp(16), dp(8), dp(16), dp(6));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{FONDO_ALTO, NEGRO}));
        scroll.addView(pantalla, new FrameLayout.LayoutParams(-1, -2));

        // Cabecera
        TextView titulo = texto("KULI MUSIC", 16, DORADO);
        titulo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titulo.setLetterSpacing(0.3f);
        LinearLayout cabecera = new LinearLayout(this);
        cabecera.setGravity(Gravity.CENTER);
        cabecera.addView(new Escudo(this), new LinearLayout.LayoutParams(dp(28), dp(32)));
        cabecera.addView(titulo, new LinearLayout.LayoutParams(-2, -2));
        pantalla.addView(cabecera, new LinearLayout.LayoutParams(-1, -2));
        TextView lema = texto("TU MÚSICA · TU ESTILO · v" + versionApp(), 10, ORO);
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
        lpPortada.topMargin = dp(8);
        lpPortada.bottomMargin = dp(8);
        pantalla.addView(portada, lpPortada);

        // Datos de la canción
        cancion = texto("Selecciona una canción", 18, Color.WHITE);
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
        lpEstado.topMargin = dp(2);
        pantalla.addView(estado, lpEstado);

        // Barra de progreso
        barra = new SeekBar(this);
        barra.setProgressTintList(ColorStateList.valueOf(DORADO));
        barra.setThumbTintList(ColorStateList.valueOf(DORADO));
        barra.setProgressBackgroundTintList(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)));
        LinearLayout.LayoutParams lpBarra = new LinearLayout.LayoutParams(-1, -2);
        lpBarra.topMargin = dp(4);
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
        mezcla = circulo("⇄", 40, 16, APAGADO);
        Button anterior = circulo("⏮", 46, 18, CONTORNO);
        botonPlay = circulo("▶", 62, 24, LLENO);
        Button siguiente = circulo("⏭", 46, 18, CONTORNO);
        bucle = circulo("↻", 40, 18, repetir ? CONTORNO : APAGADO);

        LinearLayout controles = new LinearLayout(this);
        controles.setGravity(Gravity.CENTER_VERTICAL);
        controles.addView(mezcla, new LinearLayout.LayoutParams(dp(40), dp(40)));
        controles.addView(separador(), new LinearLayout.LayoutParams(0, 1, 1));
        controles.addView(anterior, new LinearLayout.LayoutParams(dp(46), dp(46)));
        controles.addView(separador(), new LinearLayout.LayoutParams(0, 1, 1));
        controles.addView(botonPlay, new LinearLayout.LayoutParams(dp(62), dp(62)));
        controles.addView(separador(), new LinearLayout.LayoutParams(0, 1, 1));
        controles.addView(siguiente, new LinearLayout.LayoutParams(dp(46), dp(46)));
        controles.addView(separador(), new LinearLayout.LayoutParams(0, 1, 1));
        controles.addView(bucle, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams lpControles = new LinearLayout.LayoutParams(-1, -2);
        lpControles.topMargin = dp(4);
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
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(40), 1);
        lp1.rightMargin = dp(5);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(40), 1);
        lp2.leftMargin = dp(5);
        fila1.addView(movil, lp1);
        fila1.addView(misCanciones, lp2);
        LinearLayout.LayoutParams lpFila1 = new LinearLayout.LayoutParams(-1, -2);
        lpFila1.topMargin = dp(5);
        pantalla.addView(fila1, lpFila1);
        Button nas = pastilla("☁  Ver NAS", CONTORNO);
        Button subir = pastilla("⇪  Subir al NAS", CONTORNO);
        LinearLayout filaNas = new LinearLayout(this);
        LinearLayout.LayoutParams lpN1 = new LinearLayout.LayoutParams(0, dp(40), 1);
        lpN1.rightMargin = dp(5);
        LinearLayout.LayoutParams lpN2 = new LinearLayout.LayoutParams(0, dp(40), 1);
        lpN2.leftMargin = dp(5);
        filaNas.addView(nas, lpN1);
        filaNas.addView(subir, lpN2);
        LinearLayout.LayoutParams lpFilaNas = new LinearLayout.LayoutParams(-1, -2);
        lpFilaNas.topMargin = dp(5);
        pantalla.addView(filaNas, lpFilaNas);
        Button carpeta = pastilla("📂  Carpeta de música del móvil", CONTORNO);
        LinearLayout.LayoutParams lpCarp = new LinearLayout.LayoutParams(-1, dp(40));
        lpCarp.topMargin = dp(5);
        pantalla.addView(carpeta, lpCarp);
        carpeta.setOnClickListener(v -> abrirCarpetaMusica());
        carpeta.setOnLongClickListener(v -> { elegirCarpetaMusica(); return true; });
        Button ordenar = pastilla("🧹  Ordenar y poner nombres (álbumes · sueltas · duplicadas)", CONTORNO);
        LinearLayout.LayoutParams lpOrd = new LinearLayout.LayoutParams(-1, dp(40));
        lpOrd.topMargin = dp(5);
        pantalla.addView(ordenar, lpOrd);
        ordenar.setOnClickListener(v -> ordenarMusica());
        nas.setOnClickListener(v -> abrirListaNas());
        subir.setOnClickListener(v -> elegirCancionesParaSubir());
        movil.setOnClickListener(v -> abrirCanciones());
        misCanciones.setOnClickListener(v -> abrirBibliotecaKuliDirecta());
        final Button btnDj = pastilla("🎧 Modo DJ · 3x3", 0xFF0F172A);
        btnDj.setTextColor(0xFFF5D061);
        btnDjRef = btnDj;
        btnDj.setOnClickListener(v -> abrirModoCocheDj());
        btnDj.setOnLongClickListener(v -> {
            if (modoDjActivo && colaDj.size() > 3) {
                indiceDj = ((indiceDj / 3) + 1) * 3 % colaDj.size();
                reproducirConFundidoDj(colaDj.get(indiceDj));
                aviso("🎧 Saltando al siguiente estilo");
            }
            return true;
        });
        Button btnBus = pastilla("🔍 Buscar", 0xFF0D47A1); btnBus.setOnClickListener(v -> abrirBuscadorGlobal());
        try { ViewGroup vg = (ViewGroup) misCanciones.getParent(); int ix = vg.indexOfChild(misCanciones); LinearLayout filaExtra = new LinearLayout(this); filaExtra.setOrientation(LinearLayout.HORIZONTAL); filaExtra.setGravity(android.view.Gravity.CENTER); LinearLayout.LayoutParams lpHalf = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); lpHalf.setMargins(8, 8, 8, 8); filaExtra.addView(btnDj, lpHalf); filaExtra.addView(btnBus, lpHalf); pantalla.addView(filaExtra, pantalla.indexOfChild(nas.getParent() instanceof View ? (View) nas.getParent() : nas) + 1); } catch (Exception ignored) {}

        // Acciones: secundarias
        favorito = pastilla("♡  Guardar", CONTORNO);
        Button prism = pastilla("🎧 Prism", CONTORNO);
        Button notube = pastilla("▶ NoTube", CONTORNO);
        Button buscar = pastilla("⌕  Buscar", CONTORNO);
        LinearLayout fila2 = new LinearLayout(this);
        for (Button b : new Button[]{favorito, prism, notube, buscar}) {
            b.setTextSize(12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
            lp.leftMargin = dp(4); lp.rightMargin = dp(4);
            fila2.addView(b, lp);
        }
        LinearLayout.LayoutParams lpFila2 = new LinearLayout.LayoutParams(-1, -2);
        lpFila2.topMargin = dp(5);
        pantalla.addView(fila2, lpFila2);
        favorito.setOnClickListener(v -> alternarFavorito());
        prism.setOnClickListener(v -> mostrarMenuPrism());
        notube.setOnClickListener(v -> abrirApp("NoTube", "notube", "noutube"));
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
        lpRadio.topMargin = dp(2);
        pantalla.addView(filaMedios, lpRadio);

        LinearLayout filaAjustes = new LinearLayout(this);
        filaAjustes.setGravity(Gravity.CENTER_HORIZONTAL);
        filaAjustes.addView(eq);
        filaAjustes.addView(dormir);
        filaAjustes.addView(ajustes);
        LinearLayout.LayoutParams lpAjustes = new LinearLayout.LayoutParams(-1, -2);
        lpAjustes.topMargin = dp(0);
        pantalla.addView(filaAjustes, lpAjustes);

        TextView descargas = texto("🆕  Clasificar descargas (Prism · NoTube)", 13, DORADO);
        descargas.setPadding(dp(12), dp(6), dp(12), dp(6));
        descargas.setGravity(Gravity.CENTER_HORIZONTAL);
        descargas.setOnClickListener(v -> clasificarDescargas());
        descargas.setOnLongClickListener(v -> { elegirCarpetaDescargas(); return true; });
        pantalla.addView(descargas, new LinearLayout.LayoutParams(-1, -2));

        TextView pie = texto("✦ KULI EDITION ✦", 10, ORO);
        pie.setAlpha(0.7f);
        pie.setLetterSpacing(0.2f);
        pantalla.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout.LayoutParams lpPie = new LinearLayout.LayoutParams(-1, -2);
        lpPie.topMargin = dp(4);
        pantalla.addView(pie, lpPie);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(NEGRO);

        ScrollView scrollBib = new ScrollView(this);
        scrollBib.setFillViewport(true);
        scrollBib.setVisibility(View.GONE);
        LinearLayout pagBib = new LinearLayout(this);
        pagBib.setOrientation(LinearLayout.VERTICAL);
        pagBib.setGravity(Gravity.CENTER_HORIZONTAL);
        pagBib.setPadding(dp(16), dp(20), dp(16), dp(20));
        scrollBib.addView(pagBib);

        ScrollView scrollMed = new ScrollView(this);
        scrollMed.setFillViewport(true);
        scrollMed.setVisibility(View.GONE);
        LinearLayout pagMed = new LinearLayout(this);
        pagMed.setOrientation(LinearLayout.VERTICAL);
        pagMed.setGravity(Gravity.CENTER_HORIZONTAL);
        pagMed.setPadding(dp(16), dp(20), dp(16), dp(20));
        scrollMed.addView(pagMed);

        for (View v : new View[]{carpeta, ordenar, descargas, fila2}) {
            if (v != null && v.getParent() instanceof android.view.ViewGroup) {
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                ((android.view.ViewGroup) v.getParent()).removeView(v);
                pagBib.addView(v, lp);
            }
        }
        for (View v : new View[]{filaMedios, filaAjustes}) {
            if (v != null && v.getParent() instanceof android.view.ViewGroup) {
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                ((android.view.ViewGroup) v.getParent()).removeView(v);
                pagMed.addView(v, lp);
            }
        }

        android.widget.FrameLayout contenedorTabs = new android.widget.FrameLayout(this);
        contenedorTabs.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        contenedorTabs.addView(scrollBib, new android.widget.FrameLayout.LayoutParams(-1, -1));
        contenedorTabs.addView(scrollMed, new android.widget.FrameLayout.LayoutParams(-1, -1));
        raiz.addView(contenedorTabs, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout barraInf = new LinearLayout(this);
        barraInf.setOrientation(LinearLayout.HORIZONTAL);
        barraInf.setBackgroundColor(FONDO_ALTO);
        barraInf.setPadding(dp(4), dp(8), dp(4), dp(10));
        barraInf.setElevation(dp(8));

        final TextView tab1 = texto("🎵\nReproductor", 11, DORADO);
        final TextView tab2 = texto("📁\nBiblioteca/NAS", 11, GRIS);
        final TextView tab3 = texto("📻\nRadio/TV/Ajustes", 11, GRIS);
        for (TextView t : new TextView[]{tab1, tab2, tab3}) {
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(2), dp(4), dp(2), dp(4));
            barraInf.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        Runnable[] accionesTab = new Runnable[]{
            () -> { scroll.setVisibility(View.VISIBLE); scrollBib.setVisibility(View.GONE); scrollMed.setVisibility(View.GONE); tab1.setTextColor(DORADO); tab2.setTextColor(GRIS); tab3.setTextColor(GRIS); },
            () -> { scroll.setVisibility(View.GONE); scrollBib.setVisibility(View.VISIBLE); scrollMed.setVisibility(View.GONE); tab1.setTextColor(GRIS); tab2.setTextColor(DORADO); tab3.setTextColor(GRIS); },
            () -> { scroll.setVisibility(View.GONE); scrollBib.setVisibility(View.GONE); scrollMed.setVisibility(View.VISIBLE); tab1.setTextColor(GRIS); tab2.setTextColor(GRIS); tab3.setTextColor(DORADO); }
        };

        tab1.setOnClickListener(v -> accionesTab[0].run());
        tab2.setOnClickListener(v -> accionesTab[1].run());
        tab3.setOnClickListener(v -> accionesTab[2].run());

        raiz.addView(barraInf, new LinearLayout.LayoutParams(-1, -2));
        setContentView(raiz);

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
        copiaDeFavoritos();
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
        portada.clearColorFilter();
        portada.setPadding(0, 0, 0, 0);
        portada.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int sz = dp(96);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(DORADO);
        p.setStyle(android.graphics.Paint.Style.FILL);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(sz * 0.34f, sz * 0.24f);
        path.lineTo(sz * 0.76f, sz * 0.50f);
        path.lineTo(sz * 0.34f, sz * 0.76f);
        path.close();
        c.drawPath(path, p);
        portada.setImageBitmap(bmp);
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
            if (!djLista() && modoDjActivo && colaDj != null && !colaDj.isEmpty() && duracion > 12000 && (duracion - pos) <= 6000 && !djCambiando) {
                djCambiando = true;
                indiceDj = (indiceDj + 1) % colaDj.size();
                reproducirConFundidoDj(colaDj.get(indiceDj));
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> djCambiando = false, 7500);
            }
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
        if (requestCode == 9421 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getSharedPreferences("kuli_prefs", MODE_PRIVATE).edit().putString("prism_uri", tree.toString()).apply();
            aviso("Carpeta Prism autorizada. Pulsa ahora Pasar nuevas a MP3.");
            return;
        }
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
        if (requestCode == PICK_CARPETA && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri arbol = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(arbol, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            getSharedPreferences("kuli_ajustes", MODE_PRIVATE).edit().putString("carpeta_musica", arbol.toString()).apply();
            abrirCarpetaMusica();
            return;
        }
        if (requestCode == PICK_DESCARGAS && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri arbol = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(arbol, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            getSharedPreferences("kuli_ajustes", MODE_PRIVATE).edit().putString("carpeta_descargas", arbol.toString()).apply();
            clasificarDescargas();
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
    // Mete varias vistas dentro de una sección que se abre y se cierra tocando el título.
    private void agrupar(LinearLayout pantalla, final String titulo, View... vistas) {
        int pos = pantalla.indexOfChild(vistas[0]);
        if (pos < 0) pos = pantalla.getChildCount();
        final LinearLayout cuerpo = new LinearLayout(this);
        cuerpo.setOrientation(LinearLayout.VERTICAL);
        for (View v : vistas) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            pantalla.removeView(v);
            cuerpo.addView(v, lp != null ? lp : new LinearLayout.LayoutParams(-1, -2));
        }
        cuerpo.setVisibility(View.GONE);
        final TextView cab = texto("▸  " + titulo.trim(), 13, DORADO);
        cab.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cab.setPadding(dp(14), dp(8), dp(14), dp(8));
        cab.setBackground(fondo(TARJETA, 12, LINEA));
        cab.setOnClickListener(v -> {
            boolean abrir = cuerpo.getVisibility() != View.VISIBLE;
            cuerpo.setVisibility(abrir ? View.VISIBLE : View.GONE);
            cab.setText((abrir ? "▾  " : "▸  ") + titulo.trim());
        });
        LinearLayout.LayoutParams lpCab = new LinearLayout.LayoutParams(-1, -2);
        lpCab.topMargin = dp(6);
        pantalla.addView(cab, Math.min(pos, pantalla.getChildCount()), lpCab);
        pantalla.addView(cuerpo, Math.min(pos + 1, pantalla.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------- Clasificar las descargas de Prism / NoTube en las carpetas del NAS ----------
    private void elegirCarpetaDescargas() {
        java.io.File musicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC);
        java.io.File base = new java.io.File(musicDir, "KULI MUSIC");
        java.io.File dlPrism = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Prism_MP3");

        final java.util.ArrayList<String> nombres = new java.util.ArrayList<>();
        final java.util.ArrayList<java.util.ArrayList<Uri>> grupos = new java.util.ArrayList<>();

        java.io.File albRoot = new java.io.File(base, "Álbumes");
        if (albRoot.exists() && albRoot.listFiles() != null) {
            java.io.File[] dirs = albRoot.listFiles();
            java.util.Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (java.io.File d : dirs) {
                if (d.isDirectory()) {
                    java.util.ArrayList<Uri> u = recogerAudiosCarpeta(d);
                    if (!u.isEmpty()) {
                        nombres.add("💿 " + d.getName() + " (" + u.size() + " temas)");
                        grupos.add(u);
                    }
                }
            }
        }
        java.io.File sueltas = new java.io.File(base, "Sueltas");
        if (sueltas.exists()) {
            java.util.ArrayList<Uri> u = recogerAudiosCarpeta(sueltas);
            if (!u.isEmpty()) {
                nombres.add("🎵 Sueltas (" + u.size() + " temas)");
                grupos.add(u);
            }
        }
        if (dlPrism.exists()) {
            java.util.ArrayList<Uri> u = recogerAudiosCarpeta(dlPrism);
            if (!u.isEmpty()) {
                nombres.add("📥 Descargas Prism sin mover (" + u.size() + " temas)");
                grupos.add(u);
            }
        }

        if (nombres.isEmpty()) {
            aviso("No hay carpetas de música en KULI MUSIC para subir.");
            return;
        }

        final String[] items = nombres.toArray(new String[0]);
        final boolean[] marcados = new boolean[items.length];

        new AlertDialog.Builder(this)
            .setTitle("☁️ Elige qué carpetas subir al NAS")
            .setMultiChoiceItems(items, marcados, (d, which, isChecked) -> marcados[which] = isChecked)
            .setPositiveButton("⬆️ Subir marcadas", (d, w) -> {
                java.util.ArrayList<Uri> elegidas = new java.util.ArrayList<>();
                for (int i = 0; i < marcados.length; i++) {
                    if (marcados[i]) elegidas.addAll(grupos.get(i));
                }
                if (elegidas.isEmpty()) {
                    aviso("Marca al menos una carpeta para subir.");
                    return;
                }
                subirAutomatico(elegidas, null);
            })
            .setNeutralButton("Marcar todas", (d, w) -> {
                java.util.ArrayList<Uri> todas = new java.util.ArrayList<>();
                for (java.util.ArrayList<Uri> g : grupos) todas.addAll(g);
                subirAutomatico(todas, null);
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private java.util.ArrayList<Uri> recogerAudiosCarpeta(java.io.File dir) {
        java.util.ArrayList<Uri> out = new java.util.ArrayList<>();
        if (dir == null || !dir.exists() || dir.listFiles() == null) return out;
        java.io.File[] fs = dir.listFiles();
        java.util.Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (java.io.File f : fs) {
            if (f.isFile()) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".ogg")) {
                    out.add(Uri.fromFile(f));
                }
            }
        }
        return out;
    }

    private synchronized void marcarDescargaHecha(Uri uri) {
        SharedPreferences aj = getSharedPreferences("kuli_ajustes", MODE_PRIVATE);
        java.util.HashSet<String> hechas = new java.util.HashSet<>(aj.getStringSet("descargas_hechas", new java.util.HashSet<String>()));
        hechas.add(uri.toString());
        aj.edit().putStringSet("descargas_hechas", hechas).apply();
    }

    
    private void autoOrganizarAlbumesDescargados(boolean avisarSiNada) {
        try {
            java.io.File dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File prism = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "Prism_MP3");
            java.io.File baseKuli = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
            java.io.File dirAlbumes = new java.io.File(baseKuli, "Álbumes");
            java.io.File dirSueltas = new java.io.File(baseKuli, "Sueltas");
            dirAlbumes.mkdirs(); dirSueltas.mkdirs();

            java.util.ArrayList<java.io.File> cand = new java.util.ArrayList<>();
            for (java.io.File dir : new java.io.File[]{dl, prism}) {
                if (dir != null && dir.exists() && dir.listFiles() != null) {
                    for (java.io.File f : dir.listFiles()) {
                        String nm = f.getName().toLowerCase();
                        if (f.isFile() && f.length() > 30000 && (nm.endsWith(".mp3") || nm.endsWith(".m4a"))) {
                            cand.add(f);
                        }
                    }
                }
            }
            if (cand.isEmpty()) return;

            class InfoPista {
                java.io.File f; String tit, art, alb, pista; byte[] artBytes;
            }
            java.util.ArrayList<InfoPista> infos = new java.util.ArrayList<>();
            java.util.HashMap<String, Integer> conteoAlb = new java.util.HashMap<>();

            for (java.io.File f : cand) {
                InfoPista ip = new InfoPista();
                ip.f = f;
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                try {
                    mmr.setDataSource(f.getAbsolutePath());
                    ip.tit = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
                    ip.art = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    ip.alb = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    ip.pista = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
                    ip.artBytes = mmr.getEmbeddedPicture();
                } catch (Throwable ignored) {}
                try { mmr.release(); } catch (Throwable ignored) {}

                if (ip.tit == null || ip.tit.trim().isEmpty()) ip.tit = f.getName().replaceFirst("\\.[^.]+$", "");
                if (ip.art == null) ip.art = "";
                if (ip.alb == null) ip.alb = "";
                infos.add(ip);

                String kAlb = ip.alb.trim().toLowerCase();
                if (!kAlb.isEmpty() && !kAlb.contains("download") && !kAlb.contains("prism") && !kAlb.contains("unknown")) {
                    conteoAlb.put(kAlb, conteoAlb.getOrDefault(kAlb, 0) + 1);
                }
            }

            int movidos = 0;
            final java.util.ArrayList<Uri> urisParaNas = new java.util.ArrayList<>();
            java.util.HashSet<String> albumesCreados = new java.util.HashSet<>();
            for (InfoPista ip : infos) {
                String ext = ip.f.getName().contains(".") ? ip.f.getName().substring(ip.f.getName().lastIndexOf(".")) : ".m4a";
                String kAlb = ip.alb.trim().toLowerCase();
                boolean esAlbum = !kAlb.isEmpty() && conteoAlb.getOrDefault(kAlb, 0) >= 2;
                String titLimpio = ip.tit.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                String artLimpio = ip.art.replaceAll("[\\\\/:*?\"<>|]", "").trim();

                java.io.File destinoDir;
                String nombreFinal;
                if (esAlbum) {
                    String albLimpio = ip.alb.replaceAll("[\\\\/:*?\"<>|]", "").trim();
                    String carpetaAlb = (!artLimpio.isEmpty() && !albLimpio.toLowerCase().contains(artLimpio.toLowerCase()))
                        ? (artLimpio + " - " + albLimpio) : albLimpio;
                    destinoDir = new java.io.File(dirAlbumes, carpetaAlb);
                    destinoDir.mkdirs();
                    albumesCreados.add(carpetaAlb);

                    String num = "";
                    if (ip.pista != null && !ip.pista.trim().isEmpty()) {
                        String nOnly = ip.pista.split("/")[0].replaceAll("[^0-9]", "");
                        if (!nOnly.isEmpty()) num = String.format(java.util.Locale.US, "%02d - ", Integer.parseInt(nOnly));
                    }
                    nombreFinal = num + titLimpio + ext;

                    if (ip.artBytes != null && ip.artBytes.length > 0) {
                        java.io.File cov = new java.io.File(destinoDir, "cover.jpg");
                        if (!cov.exists()) {
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(cov)) { fos.write(ip.artBytes); }
                        }
                    }
                } else {
                    destinoDir = dirSueltas;
                    nombreFinal = (!artLimpio.isEmpty() ? (artLimpio + " - " + titLimpio) : titLimpio) + ext;
                }

                java.io.File destFile = new java.io.File(destinoDir, nombreFinal);
                if (!destFile.exists()) {
                    if (!ip.f.renameTo(destFile)) {
                        try (java.io.InputStream in = new java.io.FileInputStream(ip.f);
                             java.io.OutputStream out = new java.io.FileOutputStream(destFile)) {
                            byte[] buf = new byte[8192]; int r;
                            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        }
                        ip.f.delete();
                    }
                    movidos++;
                    urisParaNas.add(Uri.fromFile(destFile));
                }
            }
            if (movidos > 0 && !urisParaNas.isEmpty()) {
                final int tot = movidos;
                final int nAlb = albumesCreados.size();
                runOnUiThread(() -> {
                    aviso(nAlb > 0
                        ? ("💿 " + nAlb + " álbum(es) con portada (" + tot + " temas) · Subiendo al NAS...")
                        : ("🎵 " + tot + " canciones organizadas · Subiendo al NAS..."));
                    try { subirAutomatico(urisParaNas, null); } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable ignored) {}
    }

    private void clasificarDescargas() {
        new Thread(() -> autoOrganizarAlbumesDescargados(true)).start();
        if (!hayWifi()) { aviso("Necesitas la WiFi de casa (o la VPN) para subir al NAS"); return; }
        final SharedPreferences aj = getSharedPreferences("kuli_ajustes", MODE_PRIVATE);
        String guardada = aj.getString("carpeta_descargas", null);
        if (guardada == null) { aviso("Elige la carpeta donde Prism y NoTube guardan las canciones"); elegirCarpetaDescargas(); return; }
        final Uri arbol = Uri.parse(guardada);
        aviso("Buscando canciones nuevas…");
        new Thread(() -> {
            ArrayList<String[]> todas = CarpetaMusica.listar(getContentResolver(), arbol);
            java.util.Set<String> hechas = new java.util.HashSet<>(aj.getStringSet("descargas_hechas", new java.util.HashSet<String>()));
            final ArrayList<String[]> nuevas = new ArrayList<>();
            for (String[] c : todas) if (!hechas.contains(c[1])) nuevas.add(c);
            runOnUiThread(() -> mostrarDescargasNuevas(nuevas));
        }).start();
    }

    private void mostrarDescargasNuevas(final ArrayList<String[]> nuevas) {
        if (nuevas.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("🆕 Descargas")
                .setMessage("No hay canciones nuevas en esa carpeta. Si no es la carpeta correcta, elige otra.")
                .setPositiveButton("OK", null)
                .setNeutralButton("📂 OTRA CARPETA", (d, w) -> elegirCarpetaDescargas()).show();
            return;
        }
        final String[] nombres = new String[nuevas.size()];
        final boolean[] marcadas = new boolean[nuevas.size()];
        for (int i = 0; i < nombres.length; i++) { nombres[i] = nuevas.get(i)[0]; marcadas[i] = true; }
        new AlertDialog.Builder(this).setTitle("🆕 " + nuevas.size() + (nuevas.size() == 1 ? " canción nueva" : " canciones nuevas"))
            .setMultiChoiceItems(nombres, marcadas, (d, i, v) -> marcadas[i] = v)
            .setPositiveButton("ELEGIR ESTILO", (d, w) -> {
                final ArrayList<Uri> elegidas = new ArrayList<>();
                for (int i = 0; i < marcadas.length; i++) if (marcadas[i]) elegidas.add(Uri.parse(nuevas.get(i)[1]));
                if (elegidas.isEmpty()) { aviso("No has marcado ninguna"); return; }
                elegirEstilo("¿De qué estilo son estas " + elegidas.size() + "?",
                    estilo -> subirVariasAlNas(elegidas, estilo, uri -> marcarDescargaHecha(uri), null));
            })
            .setNeutralButton("📂 OTRA CARPETA", (d, w) -> elegirCarpetaDescargas())
            .setNegativeButton("CERRAR", null).show();
    }

    // ---------- Carpeta de música del móvil ----------
    private void elegirCarpetaMusica() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_CARPETA);
    }

    private void abrirCarpetaMusica() {
        String guardada = getSharedPreferences("kuli_ajustes", MODE_PRIVATE).getString("carpeta_musica", null);
        if (guardada == null) { aviso("Elige la carpeta donde tienes la música"); elegirCarpetaMusica(); return; }
        final Uri arbol = Uri.parse(guardada);
        aviso("Leyendo la carpeta…");
        new Thread(() -> {
            final ArrayList<String[]> canciones = CarpetaMusica.listar(getContentResolver(), arbol);
            runOnUiThread(() -> mostrarCarpetaMusica(canciones));
        }).start();
    }

    private void mostrarCarpetaMusica(final ArrayList<String[]> canciones) {
        if (isDestroyed()) return; abrirBibliotecaKuliDirecta(); if (true) return;
        if (canciones.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No hay canciones")
                .setMessage("No he encontrado música en esa carpeta (ni en sus subcarpetas).")
                .setPositiveButton("ELEGIR OTRA", (d, w) -> elegirCarpetaMusica())
                .setNegativeButton("CERRAR", null).show();
            return;
        }
        djActivo = false;
        listaFavoritas.clear();
        String[] nombres = new String[canciones.size()];
        for (int n = 0; n < canciones.size(); n++) {
            nombres[n] = canciones.get(n)[0];
            listaFavoritas.add(canciones.get(n)[1]);
        }
        new AlertDialog.Builder(this).setTitle("📂 MÚSICA DEL MÓVIL · " + canciones.size() + " canciones")
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
            .setNeutralButton("🔀 AL AZAR", (dialog, which) -> {
                modoLista = true;
                posicionFavorita = new java.util.Random().nextInt(listaFavoritas.size());
                reproducir(Uri.parse(listaFavoritas.get(posicionFavorita)));
            })
            .setNegativeButton("📁 OTRA CARPETA", (dialog, which) -> elegirCarpetaMusica()).show();
    }

    private void registrarCancionOida(String clave) {
        if (clave == null || clave.isEmpty()) return;
        try {
            android.content.SharedPreferences sp = getSharedPreferences("kuli_historial_dj", MODE_PRIVATE);
            String prev = sp.getString("recientes", "");
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (String item : prev.split("\n")) if (!item.isEmpty()) set.add(item);
            set.remove(clave);
            set.add(clave);
            while (set.size() > 80) {
                java.util.Iterator<String> it = set.iterator();
                it.next();
                it.remove();
            }
            sp.edit().putString("recientes", String.join("\n", set)).apply();
        } catch (Throwable ignored) {}
    }

    private int elegirInicioSinRepetir(java.util.List<String> lista, int inicioPorDefecto) {
        if (lista == null || lista.size() <= 1 || inicioPorDefecto > 0) return inicioPorDefecto;
        try {
            android.content.SharedPreferences sp = getSharedPreferences("kuli_historial_dj", MODE_PRIVATE);
            java.util.HashSet<String> oidas = new java.util.HashSet<>(java.util.Arrays.asList(sp.getString("recientes", "").split("\n")));
            java.util.ArrayList<Integer> frescas = new java.util.ArrayList<>();
            for (int i = 0; i < lista.size(); i++) {
                if (!oidas.contains(lista.get(i))) frescas.add(i);
            }
            if (!frescas.isEmpty()) {
                return frescas.get(new java.util.Random().nextInt(frescas.size()));
            }
            return new java.util.Random().nextInt(lista.size());
        } catch (Throwable ignored) {
            return inicioPorDefecto;
        }
    }

    private android.media.audiofx.DynamicsProcessing niveladorAudio = null;

    private void aplicarNiveladorVolumen(MediaPlayer mp) {
        if (mp == null) return;
        try {
            int sessionId = mp.getAudioSessionId();
            if (sessionId <= 0) return;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                if (niveladorAudio != null) {
                    try { niveladorAudio.release(); } catch (Throwable ignored) {}
                    niveladorAudio = null;
                }
                android.media.audiofx.DynamicsProcessing.Config.Builder cfgBuilder =
                    new android.media.audiofx.DynamicsProcessing.Config.Builder(
                        android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        2,     // canales (estéreo)  V344
                        false, // preEq
                        0,
                        true,  // mbc (compresor multibanda para igualar canciones bajas y altas)
                        2,
                        false, // postEq
                        0,
                        true   // limiter (evita picos fuertes)
                    );
                android.media.audiofx.DynamicsProcessing.Config cfg = cfgBuilder.build();
                for (int ch = 0; ch < 2; ch++) {
                    android.media.audiofx.DynamicsProcessing.Mbc mbc = cfg.getMbcByChannelIndex(ch);
                    mbc.setEnabled(true);
                    for (int b = 0; b < mbc.getBandCount(); b++) {
                        android.media.audiofx.DynamicsProcessing.MbcBand band = mbc.getBand(b);
                        band.setEnabled(true);
                        band.setThreshold(-24.0f);
                        band.setRatio(4.0f);
                        band.setKneeWidth(6.0f);
                        band.setAttackTime(15.0f);
                        band.setReleaseTime(120.0f);
                        band.setPostGain(6.5f);
                    }
                    android.media.audiofx.DynamicsProcessing.Limiter lim = cfg.getLimiterByChannelIndex(ch);
                    lim.setEnabled(true);
                    lim.setThreshold(-2.0f);
                    lim.setRatio(10.0f);
                    lim.setAttackTime(2.0f);
                    lim.setReleaseTime(60.0f);
                    lim.setPostGain(0.0f);
                }
                niveladorAudio = new android.media.audiofx.DynamicsProcessing(0, sessionId, cfg);
                niveladorAudio.setEnabled(true);
            }
        } catch (Throwable e) {
            android.util.Log.w("KULI_AUDIO", "Nivelador no disponible: " + e.getMessage());
        }
    }

    private void registrarEscuchada(String u) {
        if (u == null || u.isEmpty()) return;
        try {
            android.content.SharedPreferences sp = getSharedPreferences("kuli_historial_dj", MODE_PRIVATE);
            String prev = sp.getString("recientes", "");
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            for (String s : prev.split("\n")) if (!s.isEmpty() && !s.equals(u)) set.add(s);
            set.add(u);
            while (set.size() > 500) {
                java.util.Iterator<String> it = set.iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            StringBuilder sb = new StringBuilder();
            for (String s : set) sb.append(s).append("\n");
            sp.edit().putString("recientes", sb.toString()).apply();
            azarCache = -1;
        } catch (Throwable ignored) {}
    }

    private void reproducir(Uri uri) {
        if (uri != null) registrarEscuchada(uri.toString());
        if (modoDjActivo && !mantenerDj) modoDjActivo = false;   // si eliges otra cosa, el DJ se apaga
        mantenerDj = false;
        if (uri != null) registrarCancionOida(uri.toString());
        if ("nas".equals(uri.getScheme())) { reproducirDelNas(uri); return; }
        reproducirArchivo(uri, uri);
    }

    // 'fuente' es lo que se reproduce; 'identidad' es cómo se reconoce la canción (favoritos, NAS...).
    private void reproducirArchivo(Uri fuente, Uri identidad) {
        try {
            esRadio = false;
            cancelarMezcla();
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
            aplicarNiveladorVolumen(reproductor);
            reproductor.start(); actualizarNotificacionSegundoPlano(true); estado.setText("▶ REPRODUCIENDO"); actualizarNotificacionSegundoPlano(true);
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
                reproductor.pause(); estado.setText("Ⅱ EN PAUSA"); actualizarNotificacionSegundoPlano(false);
            } else {
                reproductor.start(); actualizarNotificacionSegundoPlano(true); estado.setText("▶ REPRODUCIENDO"); actualizarNotificacionSegundoPlano(true);
            }
            iniciarServicio();
        } catch (IllegalStateException e) { aviso("No se pudo reproducir"); }
        actualizarProgreso();
    }

    // Órdenes que llegan desde los botones de la notificación.
    void accionNotificacion(String accion) {
        if (MusicaService.PAUSA.equals(accion)) alternarReproduccion();
        else if (MusicaService.SIGUIENTE.equals(accion)) saltarGeneral(1);
        else if (MusicaService.ANTERIOR.equals(accion)) saltarGeneral(-1);
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
            try {
                android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build();
                mp.setAudioAttributes(aa);
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (am != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        am.requestAudioFocus(new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(aa).build());
                    } else {
                        am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN);
                    }
                }
            } catch (Exception ignored) { }
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
        if (uris == null || uris.isEmpty()) return;
        aviso("Leyendo carpetas de tu NAS...");
        new Thread(() -> {
            final LinkedHashMap<String, Integer> carpetasNas = new LinkedHashMap<>();
            try {
                conNas(disco -> {
                    if (disco.folderExists("KULI MUSIC")) {
                        for (com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation f : disco.list("KULI MUSIC")) {
                            String n = f.getFileName();
                            if (n.equals(".") || n.equals("..")) continue;
                            if ((f.getFileAttributes() & 0x10L) != 0) {
                                int cnt = 0;
                                for (com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation g : disco.list("KULI MUSIC\\" + n)) {
                                    if ((g.getFileAttributes() & 0x10L) == 0 && esAudio(g.getFileName())) cnt++;
                                }
                                carpetasNas.put(n, cnt);
                            }
                        }
                    }
                    return null;
                });
            } catch (Throwable ignored) { }
            runOnUiThread(() -> {
                if (carpetasNas.isEmpty()) { ofrecerSubidaAutoOriginal(uris, alSubir, alIgnorar); return; }
                final ArrayList<String> nombres = new ArrayList<>(carpetasNas.keySet());
                Collections.sort(nombres, String.CASE_INSENSITIVE_ORDER);
                String[] items = new String[nombres.size() + 2];
                items[0] = "🤖 Clasificar automáticamente por estilo (" + uris.size() + " temas)";
                for (int i = 0; i < nombres.size(); i++) {
                    String nom = nombres.get(i);
                    items[i + 1] = "📁 " + nom + "  (" + carpetasNas.get(nom) + " canciones en NAS)";
                }
                items[nombres.size() + 1] = "➕ Crear nueva carpeta en el NAS...";
                new AlertDialog.Builder(this).setTitle("Subir " + uris.size() + " canciones · Elige carpeta del NAS")
                        .setItems(items, (d, which) -> {
                            if (which == 0) ofrecerSubidaAutoOriginal(uris, alSubir, alIgnorar);
                            else if (which == nombres.size() + 1) {
                                final android.widget.EditText et = new android.widget.EditText(this); et.setHint("Nombre de nueva carpeta en NAS...");
                                new AlertDialog.Builder(this).setTitle("Nueva carpeta en KULI MUSIC").setView(et)
                                        .setPositiveButton("Subir ahí", (d2, w2) -> {
                                            String nueva = et.getText().toString().trim();
                                            if (!nueva.isEmpty()) { subirVariasAlNas(uris, nueva, alSubir); }
                                        }).setNegativeButton("Cancelar", null).show();
                            } else {
                                subirVariasAlNas(uris, nombres.get(which - 1), alSubir);
                            }
                        }).setNegativeButton("Cancelar", null).show();
            });
        }).start();
    }
    private void ofrecerSubidaAutoOriginal(final ArrayList<Uri> uris, final java.util.function.Consumer<Uri> alSubir,
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
        subirGrupos(new ArrayList<>(porEstilo.entrySet()), 0, alSubir);
    }

    private void subirGrupos(final ArrayList<Map.Entry<String, ArrayList<Uri>>> cola, final int i,
                             final java.util.function.Consumer<Uri> alSubir) {
        if (i >= cola.size()) {
            runOnUiThread(() -> {
                if (bannerNas != null) {
                    bannerNas.setBackgroundColor(0xFF14362B);
                    bannerNas.setTextColor(0xFF80ED99);
                    bannerNas.setText("✅ NAS al día · Subida completada");
                    bannerNas.postDelayed(() -> bannerNas.setVisibility(android.view.View.GONE), 6000);
                }
            });
            return;
        }
        final String estiloAct = cola.get(i).getKey();
        final int cantAct = cola.get(i).getValue().size();
        runOnUiThread(() -> {
            if (bannerNas != null) {
                bannerNas.setBackgroundColor(0xFF0D253F);
                bannerNas.setTextColor(0xFFFFD166);
                bannerNas.setVisibility(android.view.View.VISIBLE);
                bannerNas.setText("☁️ NAS: Subiendo (" + (i + 1) + "/" + cola.size() + ") · " + estiloAct + " (" + cantAct + " temas)...");
            }
        });
        subirVariasAlNas(cola.get(i).getValue(), estiloAct, alSubir,
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
        // V349: sin nombre real y sin título/artista dentro -> no se mete en ninguna carpeta de estilo.
        if (nombreDeUri(u).startsWith("cancion_") && (titulo == null || titulo.trim().isEmpty()) && (autor == null || autor.trim().isEmpty()))
            return "Sin clasificar";
        String estilo = detectarEstiloPersonalizado(simple(nombreDeUri(u)), autor, genero, anio);
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
        if (g.contains("flamenc") || g.contains("rumba")) return "Flamenkito";
        if (g.contains("latin") || g.contains("reggaeton") || g.contains("urban") || g.contains("salsa")
            || g.contains("bachata") || g.contains("cumbia") || g.contains("tropical")) return "Latino";
        if (g.contains("rock") || g.contains("alternativ") || g.contains("metal") || g.contains("punk")) return "Rock";
        if (g.contains("danc") || g.contains("electr") || g.contains("house") || g.contains("techno")
            || g.contains("disco") || g.contains("baile")) return "Dance"; // V345
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

    // ---------- Copia de los favoritos fuera de la app (sobrevive si desinstalas) ----------
    private SharedPreferences.OnSharedPreferenceChangeListener escuchaFavs;

    private boolean permisoArchivos() {
        return android.os.Build.VERSION.SDK_INT < 23
            || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private java.io.File archivoFavs() {
        return new java.io.File(new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS), "KULI"), "favoritos_kuli.txt");
    }

    private void guardarCopiaFavoritos() {
        if (!permisoArchivos()) return;
        try {
            Map<String, ?> m = favoritos.getAll();
            if (m.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, ?> e : m.entrySet())
                sb.append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t')
                  .append(String.valueOf(e.getValue()).replace('\t', ' ').replace('\n', ' ')).append('\n');
            java.io.File f = archivoFavs();
            f.getParentFile().mkdirs();
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(sb.toString().getBytes("UTF-8")); }
        } catch (Exception e) { android.util.Log.e("KULI_FAV", "Copia de favoritos", e); }
    }

    // Primera vez tras instalar: si no hay favoritos y existe la copia, se recuperan.
    private void restaurarFavoritosUnaVez() {
        SharedPreferences aj = getSharedPreferences("kuli_ajustes", MODE_PRIVATE);
        if (aj.getBoolean("favs_chequeo", false)) return;
        aj.edit().putBoolean("favs_chequeo", true).apply();
        try {
            java.io.File f = archivoFavs();
            if (!favoritos.getAll().isEmpty() || !f.exists()) return;
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            }
            SharedPreferences.Editor ed = favoritos.edit();
            int total = 0;
            for (String l : new String(o.toByteArray(), "UTF-8").split("\n")) {
                String[] p = l.split("\t", 2);
                if (p.length == 2 && !p[0].trim().isEmpty()) { ed.putString(p[0], p[1]); total++; }
            }
            ed.apply();
            if (total > 0) aviso("♥ He recuperado " + total + " favoritas de la copia");
        } catch (Exception e) { android.util.Log.e("KULI_FAV", "Recuperando favoritos", e); }
    }

    private void copiaDeFavoritos() {
        if (permisoArchivos()) { restaurarFavoritosUnaVez(); guardarCopiaFavoritos(); return; }
        SharedPreferences aj = getSharedPreferences("kuli_ajustes", MODE_PRIVATE);
        if (aj.getBoolean("favs_pidio", false)) return;
        aj.edit().putBoolean("favs_pidio", true).apply();
        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 203);
    }

    @Override public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        if (codigo == 203) {
            if (resultados.length > 0 && resultados[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) { restaurarFavoritosUnaVez(); guardarCopiaFavoritos(); }
            return;
        }
        if (codigo == 202) {
            if (resultados.length > 0 && resultados[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) ordenarMusica();
            else aviso("Sin permiso no puedo ordenar la música");
            return;
        }
        if (codigo == 201) {
            ArrayList<String> r = pendienteMovil;
            pendienteMovil = null;
            if (r != null && resultados.length > 0 && resultados[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) llevarAlMovil(r);
            else aviso("Sin permiso para guardar no puedo copiar la música");
            return;
        }
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
                        KuliPlaybackService.portadaActual = bitmap;
                        KuliPlaybackService.artistaActual = artistaActual;
                        actualizarNotificacionSegundoPlano(reproductor != null && reproductor.isPlaying());
                    }
                } else {
                    KuliPlaybackService.portadaActual = null;
                    KuliPlaybackService.artistaActual = artistaActual;
                    buscarCaratulaOnline(uri, tituloActual, artistaActual);
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

    // Aleatorio sin repetir: elige una que no hayas oido hace poco; si las has oido todas, la mas antigua.
    private int azarCachePos = -1, azarCacheN = -1, azarCache = -1;
    private String azarCacheDe = "";
    private int azarFresco() {
        int total = listaFavoritas.size();
        if (total <= 1) return 0;
        String actual = posicionFavorita >= 0 && posicionFavorita < total ? listaFavoritas.get(posicionFavorita) : "";
        if (azarCache >= 0 && azarCache < total && azarCachePos == posicionFavorita && azarCacheN == total && azarCacheDe.equals(actual)) return azarCache;
        int elegido = -1;
        try {
            String[] rec = getSharedPreferences("kuli_historial_dj", MODE_PRIVATE).getString("recientes", "").split("\n");
            java.util.HashMap<String, Integer> orden = new java.util.HashMap<>();
            for (int i = 0; i < rec.length; i++) if (!rec[i].isEmpty()) orden.put(rec[i], i);
            java.util.ArrayList<Integer> frescas = new java.util.ArrayList<>();
            int masAntigua = -1, ordenAntigua = Integer.MAX_VALUE;
            for (int i = 0; i < total; i++) {
                if (i == posicionFavorita) continue;
                Integer o = orden.get(listaFavoritas.get(i));
                if (o == null) frescas.add(i);
                else if (o < ordenAntigua) { ordenAntigua = o; masAntigua = i; }
            }
            elegido = !frescas.isEmpty() ? frescas.get(new java.util.Random().nextInt(frescas.size())) : masAntigua;
        } catch (Throwable ignorar) { }
        if (elegido < 0) {
            elegido = new java.util.Random().nextInt(total - 1);
            if (elegido >= posicionFavorita) elegido++;
        }
        azarCachePos = posicionFavorita; azarCacheN = total; azarCache = elegido; azarCacheDe = actual;
        return elegido;
    }

    private int indiceSiguiente() {
        if (aleatorio && listaFavoritas.size() > 1) return azarFresco();
        if (!modoLista || listaFavoritas.isEmpty()) return -1;
        if (djLista()) return (indiceDj + 1) % colaDj.size();
        if (aleatorio && listaFavoritas.size() > 1) return azarFresco();
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
            try { reproductor.start(); actualizarNotificacionSegundoPlano(true); } catch (Exception ignored) { }
            posicionFavorita = indicePreparado;
            if (djLista()) { indiceDj = posicionFavorita; refrescarEtiquetaDj(); }
            siguienteReproductor = null;
            indicePreparado = -1;
            try { anterior.release(); } catch (Exception ignored) { }
            mostrarDatos(Uri.parse(listaFavoritas.get(posicionFavorita)));
            reproductor.setOnCompletionListener(mp -> alTerminar());
            estado.setText("▶ REPRODUCIENDO"); actualizarNotificacionSegundoPlano(true);
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
    private static final String[] ESTILOS = {"Pop", "Rock", "Latino", "Flamenkito", "Baladas", "Dance",
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
        for (int intento = 1; intento <= 3; intento++) {
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
                        android.util.Log.e("KULI_NAS", "Intento " + intento + " fallido: " + e.getMessage());
                    }
                }
            }
            if (intento < 3) try { Thread.sleep(1000); } catch (Exception ignored) {}
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
        
        // Cargar caché local primero para carga instantánea
        final java.io.File archivoCache = new java.io.File(getCacheDir(), "nas_cache.json");
        if (archivoCache.exists()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(archivoCache)) {
                byte[] bytes = new byte[(int) archivoCache.length()];
                fis.read(bytes);
                org.json.JSONObject json = new org.json.JSONObject(new String(bytes, "UTF-8"));
                LinkedHashMap<String, ArrayList<String>> cacheMap = new LinkedHashMap<>();
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    org.json.JSONArray arr = json.getJSONArray(key);
                    ArrayList<String> canciones = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) canciones.add(arr.getString(i));
                    cacheMap.put(key, canciones);
                }
                if (!cacheMap.isEmpty()) mostrarGruposNas(cacheMap);
            } catch (Exception e) { android.util.Log.e("KULI_NAS", "Error leyendo caché", e); }
        }

        aviso("Refrescando lista del NAS…");
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
                            for (var g : disco.list("KULI MUSIC\\\\" + n)) {
                                String m = g.getFileName();
                                if ((g.getFileAttributes() & 0x10L) == 0 && esAudio(m)) dentro.add(n + "/" + m);
                            }
                            Collections.sort(dentro, String.CASE_INSENSITIVE_ORDER);
                            carpetas.put(n, dentro);
                        } else if (esAudio(n)) {
                            sueltas.add(n);
                        }
                    }
                    Collections.sort(sueltas, String.CASE_INSENSITIVE_ORDER);
                    LinkedHashMap<String, ArrayList<String>> r = new LinkedHashMap<>(carpetas);
                    if (!sueltas.isEmpty()) r.put("Sin clasificar", sueltas);
                    return r;
                });
                
                // Guardar en caché
                try {
                    org.json.JSONObject json = new org.json.JSONObject();
                    for (String k : grupos.keySet()) json.put(k, new org.json.JSONArray(grupos.get(k)));
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(archivoCache)) {
                        fos.write(json.toString().getBytes("UTF-8"));
                    }
                } catch (Exception e) { android.util.Log.e("KULI_NAS", "Error guardando caché", e); }

                runOnUiThread(() -> mostrarGruposNas(grupos));
            } catch (Throwable e) {
                if (!archivoCache.exists()) runOnUiThread(() -> mostrarErrorNas("No se pudo abrir el NAS", e));
            }
        }).start();
    }

    private void mostrarGruposNas(final LinkedHashMap<String, ArrayList<String>> grupos) {
        if (grupos.isEmpty()) { aviso("No hay canciones en la carpeta KULI MUSIC del NAS"); return; }
        final ArrayList<String> claves = new ArrayList<>(grupos.keySet());
        final ArrayList<String> todas = new ArrayList<>();
        String[] items = new String[claves.size() + 2]; // V346
        items[0] = "🎛  MODO DJ  (4 de cada estilo y cambia)";
        items[1] = "🎨  Ordenar «Pop», «Flamenkito» o «Latino»…";
        for (int i = 0; i < claves.size(); i++) {
            ArrayList<String> lista = grupos.get(claves.get(i));
            items[i + 2] = "📁  " + claves.get(i) + "  (" + lista.size() + ")";
            todas.addAll(lista);
        }
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("☁ NAS · " + todas.size() + " canciones")
            .setItems(items, (d, i) -> {
                if (i == 0) modoDj(grupos);
                else if (i == 1) reclasificarPopNas(grupos);
                else mostrarCancionesNas(claves.get(i - 2), grupos.get(claves.get(i - 2)), grupos);
            })
            .setPositiveButton("▶ TODO", (d, w) -> reproducirListaNas(todas, 0))
            .setNeutralButton("🔍 BUSCAR", (d, w) -> buscarEnNas(grupos))
            .setNegativeButton("📥 AL MÓVIL", (d, w) -> elegirParaMovil(grupos)).show();
        // Mantener pulsada una carpeta: suena entera, en aleatorio.
        dlg.getListView().setOnItemLongClickListener((padre, vista, pos, id) -> {
            if (pos < 2) return false;
            dlg.dismiss();
            reproducirAleatorio(grupos.get(claves.get(pos - 2)));
            return true;
        });
        aviso("Toca una carpeta para verla · mantenla pulsada para oírla entera en aleatorio");
    }

    // V346: artista de un archivo del NAS con nombre «Artista - Título».
    private String artistaDeNombreNas(String ruta) {
        String n = nombreSinExtension(ruta);
        int i = n.indexOf(" - ");
        if (i <= 0) return null;
        String a = simple(n.substring(0, i)).trim();
        return a.length() < 3 ? null : a;
    }

    // V346: mira los artistas de las demás carpetas y propone sacar de «Pop» lo que tiene su sitio.
    private String carpetaOrden = "Pop"; // V349

    // V351: mantiene la pantalla encendida mientras dura una tarea larga (iTunes, leer etiquetas).
    private void pantallaFija(final boolean si) {
        runOnUiThread(() -> {
            if (si) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }


    private void reclasificarPopNas(final LinkedHashMap<String, ArrayList<String>> grupos) { // V349: elegir carpeta
        final ArrayList<String> cands = new ArrayList<>();
        for (String k : new String[]{"Pop", "Flamenkito", "Latino"}) if (grupos.get(k) != null && !grupos.get(k).isEmpty()) cands.add(k);
        if (cands.isEmpty()) { aviso("No hay carpetas Pop ni Flamenkito en el NAS"); return; }
        String[] nom = new String[cands.size()];
        for (int i = 0; i < nom.length; i++) nom[i] = "📁  " + cands.get(i) + "  (" + grupos.get(cands.get(i)).size() + ")";
        new AlertDialog.Builder(this).setTitle("¿Qué carpeta ordenamos?")
            .setItems(nom, (d, i) -> { carpetaOrden = cands.get(i); ordenarCarpetaNas(grupos); })
            .setNegativeButton("CANCELAR", null).show();
    }

    private void ordenarCarpetaNas(final LinkedHashMap<String, ArrayList<String>> grupos) {
        final ArrayList<String> pop0 = grupos.get(carpetaOrden);
        if (pop0 == null || pop0.isEmpty()) { aviso("No hay carpeta " + carpetaOrden + " en el NAS"); return; }
        String[] op = {
            "🎄  Villancicos → carpeta «Navidad»",
            "🏷  Leer título y artista de los «cancion_…»",
            "🌐  Con internet (iTunes)  ~" + Math.max(1, pop0.size() * 3 / 60) + " min",
            "🎨  Por artista (rápido)",
            "🗑  Quitar los que no tienen nombre",
            "🔤  Por nombre (artistas conocidos)"};
        new AlertDialog.Builder(this).setTitle("🎨 Ordenar «" + carpetaOrden + "» (" + pop0.size() + ")")
            .setItems(op, (d, i) -> {
                if (i == 0) navidadPop(grupos);
                else if (i == 1) etiquetasPop(grupos);
                else if (i == 2) reclasificarPopInternet(grupos);
                else if (i == 3) reclasificarPopPorArtista(grupos);
                else if (i == 4) quitarSinNombre(grupos);
                else porNombreCarpeta(grupos);
            })
            .setNegativeButton("CANCELAR", null).show();
    }

    // V348
    private static final String[] NAVIDAD_PAL = {"villancic", "navidad", "nochebuena", "noche de paz", "a belen", "portal de belen", "belen pastores", "pastores", "reyes magos", "campanillas", "christmas", "jingle bell", "tamborilero", "burrito sabanero", "fum fum", "campana sobre campana", "ande ande ande", "arbol de navidad", "feliz navidad", "santa claus"};
    private boolean esNavidad(String t) {
        String x = simple(t == null ? "" : t);
        for (String k : NAVIDAD_PAL) if (x.contains(k)) return true;
        return false;
    }
    private boolean temporadaNavidad() {
        int m = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH);
        return m == java.util.Calendar.DECEMBER || m == java.util.Calendar.JANUARY;
    }

    private void navidadPop(final LinkedHashMap<String, ArrayList<String>> grupos) {
        ArrayList<String[]> plan = new ArrayList<>();
        for (String r : grupos.get(carpetaOrden)) {
            String n = nombreSinExtension(r).replace('_', ' ');
            String et = getSharedPreferences("kuli_etiquetas", MODE_PRIVATE).getString(r, "");
            if (esNavidad(n + " " + et)) plan.add(new String[]{r, "Navidad"});
        }
        if (plan.isEmpty()) { aviso("No encuentro villancicos en Pop"); return; }
        StringBuilder sb = new StringBuilder(plan.size() + " canciones parecen villancicos:\n\n");
        for (int i = 0; i < plan.size() && i < 12; i++) sb.append("• ").append(nombreSinExtension(plan.get(i)[0])).append("\n");
        if (plan.size() > 12) sb.append("… y ").append(plan.size() - 12).append(" más\n");
        sb.append("\nSe moverán a la carpeta «Navidad». Se puede deshacer.");
        final ArrayList<String[]> p2 = plan;
        new AlertDialog.Builder(this).setTitle("🎄 Navidad").setMessage(sb.toString())
            .setPositiveButton("MOVER", (d, w) -> moverVariasNas(p2, carpetaOrden))
            .setNegativeButton("CANCELAR", null).show();
    }

    // V348: lee la etiqueta ID3 (título y artista) de un trozo del principio del archivo.
    private String[] leerEtiquetaId3(byte[] b, int n) {
        try {
            if (n < 10 || b[0] != 'I' || b[1] != 'D' || b[2] != '3') return null;
            int ver = b[3] & 0xFF;
            int tam = ((b[6] & 0x7F) << 21) | ((b[7] & 0x7F) << 14) | ((b[8] & 0x7F) << 7) | (b[9] & 0x7F);
            int fin = Math.min(n, 10 + tam);
            int p = 10;
            String tit = null, art = null;
            int cab = ver == 2 ? 6 : 10;
            while (p + cab <= fin) {
                String id = new String(b, p, ver == 2 ? 3 : 4, "ISO-8859-1");
                if (id.charAt(0) == 0) break;
                int sz;
                if (ver == 2) sz = ((b[p + 3] & 0xFF) << 16) | ((b[p + 4] & 0xFF) << 8) | (b[p + 5] & 0xFF);
                else if (ver == 4) sz = ((b[p + 4] & 0x7F) << 21) | ((b[p + 5] & 0x7F) << 14) | ((b[p + 6] & 0x7F) << 7) | (b[p + 7] & 0x7F);
                else sz = ((b[p + 4] & 0xFF) << 24) | ((b[p + 5] & 0xFF) << 16) | ((b[p + 6] & 0xFF) << 8) | (b[p + 7] & 0xFF);
                if (sz <= 1 || p + cab + sz > fin) break;
                boolean esT = id.equals("TIT2") || id.equals("TT2"), esA = id.equals("TPE1") || id.equals("TP1");
                if (esT || esA) {
                    int enc = b[p + cab] & 0xFF;
                    String cs = enc == 0 ? "ISO-8859-1" : enc == 1 ? "UTF-16" : enc == 2 ? "UTF-16BE" : "UTF-8";
                    String v = new String(b, p + cab + 1, sz - 1, cs).replace("\u0000", "").trim();
                    if (esT) tit = v; else art = v;
                }
                p += cab + sz;
            }
            if ((tit == null || tit.isEmpty()) && (art == null || art.isEmpty())) return null;
            return new String[]{art == null ? "" : art, tit == null ? "" : tit};
        } catch (Throwable e) { return null; }
    }

    private void etiquetasPop(final LinkedHashMap<String, ArrayList<String>> grupos) {
        final ArrayList<String> pop = new ArrayList<>();
        for (String r : grupos.get(carpetaOrden)) if (nombreSinExtension(r).startsWith("cancion_")) pop.add(r);
        if (pop.isEmpty()) { aviso("No hay archivos «cancion_…» en Pop"); return; }
        final android.content.SharedPreferences memo = getSharedPreferences("kuli_etiquetas", MODE_PRIVATE);
        aviso("Leyendo " + pop.size() + " archivos del NAS… puede tardar unos minutos");
        new Thread(() -> {
        pantallaFija(true); // V351
        try {
            final int[] cuenta = {0, 0, 0}; // con etiqueta, sin nada, no mp3
            final ArrayList<String[]> plan = new ArrayList<>();
            try {
                conNas(disco -> {
                    int hechos = 0;
                    for (String r : pop) {
                        String v = memo.getString(r, null);
                        if (v == null) {
                            String bajo = r.toLowerCase();
                            if (!bajo.endsWith(".mp3")) v = "?";
                            else {
                                byte[] b = new byte[131072];
                                int n = 0;
                                try (File f = disco.openFile("KULI MUSIC\\" + r.replace('/', '\\'),
                                        EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                                        SMB2CreateDisposition.FILE_OPEN,
                                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                                     InputStream in = f.getInputStream()) {
                                    int x;
                                    while (n < b.length && (x = in.read(b, n, b.length - n)) > 0) n += x;
                                } catch (Exception e) { n = -1; }
                                if (n < 0) continue;
                                String[] t = leerEtiquetaId3(b, n);
                                v = t == null ? "" : t[0] + "\t" + t[1];
                            }
                            memo.edit().putString(r, v).apply();
                        }
                        if (v.equals("?")) cuenta[2]++;
                        else if (v.isEmpty()) cuenta[1]++;
                        else {
                            cuenta[0]++;
                            String[] t = v.split("\t", -1);
                            String est = esNavidad(t[0] + " " + t[1]) ? "Navidad"
                                : detectarEstiloPersonalizado(t[1], t[0], "", 0);
                            if (est != null && !est.equals(carpetaOrden) && (carpetaOrden.equals("Pop") || !est.equals("Pop")) && !(carpetaOrden.equals("Flamenkito") && est.equals("Latino"))) plan.add(new String[]{r, est});
                        }
                        if (++hechos % 50 == 0) { final String m = "Leídos " + hechos + " de " + pop.size() + "…"; runOnUiThread(() -> aviso(m)); }
                    }
                    return null;
                });
            } catch (Throwable e) {
                runOnUiThread(() -> mostrarErrorNas("No se pudieron leer las etiquetas", e));
                return;
            }
            runOnUiThread(() -> {
                StringBuilder sb = new StringBuilder("De " + pop.size() + " archivos «cancion_…»:\n\n• Con título/artista dentro: " + cuenta[0]
                    + "\n• Sin nada dentro: " + cuenta[1] + (cuenta[2] > 0 ? "\n• Otro formato (no mp3): " + cuenta[2] : ""));
                if (plan.isEmpty()) {
                    sb.append("\n\nNinguno cambia de carpeta por su etiqueta.");
                    if (cuenta[1] > 0) sb.append("\nLos «sin nada» los puedes quitar con 🗑 Quitar los que no tienen nombre.");
                    new AlertDialog.Builder(this).setTitle("🏷 Etiquetas").setMessage(sb.toString()).setPositiveButton("OK", null).show();
                } else {
                    sb.append("\n\n").append(plan.size()).append(" cambian de carpeta por su etiqueta. Se puede deshacer.");
                    new AlertDialog.Builder(this).setTitle("🏷 Etiquetas").setMessage(sb.toString())
                        .setPositiveButton("MOVER", (d, w) -> moverVariasNas(plan, carpetaOrden))
                        .setNegativeButton("AHORA NO", null).show();
                }
            });
        } finally { runOnUiThread(() -> pantallaFija(false)); }
        }).start();
    }

    // V352: mira el nombre y el artista de cada canción con las listas de la app y propone moverla si es de otro estilo.
    private void porNombreCarpeta(final LinkedHashMap<String, ArrayList<String>> grupos) {
        final android.content.SharedPreferences etq = getSharedPreferences("kuli_etiquetas", MODE_PRIVATE);
        final ArrayList<String[]> plan = new ArrayList<>();
        final TreeMap<String, Integer> resumen = new TreeMap<>();
        for (String r : grupos.get(carpetaOrden)) {
            String n = nombreSinExtension(r).replace('_', ' ');
            String t = etq.getString(r, "");
            if (t.contains("\t")) n = n + " " + t.replace('\t', ' ');
            String est = detectarEstiloPersonalizado(n, "", "", 0);
            if (est == null || est.equals(carpetaOrden)) continue;
            if (est.equals("Pop") || est.equals("Sin clasificar")) continue; // «Pop» es lo que sale cuando no sabe
            plan.add(new String[]{r, est});
            Integer v = resumen.get(est);
            resumen.put(est, v == null ? 1 : v + 1);
        }
        if (plan.isEmpty()) { aviso("Por el nombre no encuentro ninguna que sea de otro estilo"); return; }
        StringBuilder sb = new StringBuilder(plan.size() + " de las " + grupos.get(carpetaOrden).size() + " canciones de «" + carpetaOrden + "» son de otro estilo por su nombre:\n\n");
        for (java.util.Map.Entry<String, Integer> e : resumen.entrySet()) sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        sb.append("\nEjemplos:\n");
        for (int i = 0; i < plan.size() && i < 6; i++) sb.append("– ").append(nombreSinExtension(plan.get(i)[0])).append(" → ").append(plan.get(i)[1]).append("\n");
        sb.append("\nSe puede deshacer.");
        new AlertDialog.Builder(this).setTitle("🔤 Por nombre").setMessage(sb.toString())
            .setPositiveButton("MOVER", (d, w) -> moverVariasNas(plan, carpetaOrden))
            .setNegativeButton("CANCELAR", null).show();
    }

    // V348: los «cancion_…» que se han mirado y no llevan nada dentro, se apartan a «KULI PAPELERA» (fuera de la música). Se pueden recuperar.
    private void quitarSinNombre(final LinkedHashMap<String, ArrayList<String>> grupos) {
        final android.content.SharedPreferences memo = getSharedPreferences("kuli_etiquetas", MODE_PRIVATE);
        final ArrayList<String> malas = new ArrayList<>();
        int sinMirar = 0;
        for (String r : grupos.get(carpetaOrden)) {
            if (!nombreSinExtension(r).startsWith("cancion_")) continue;
            String v = memo.getString(r, null);
            if (v == null) sinMirar++;
            else if (v.isEmpty()) malas.add(r);
        }
        if (malas.isEmpty()) {
            aviso(sinMirar > 0 ? "Primero usa «Leer título y artista» para saber cuáles no tienen nada" : "No hay canciones sin nombre");
            return;
        }
        new AlertDialog.Builder(this).setTitle("🗑 Quitar sin nombre")
            .setMessage(malas.size() + " canciones no tienen título ni artista (ni en el nombre ni dentro).\n\n"
                + "No se borran: se mueven a la carpeta «KULI PAPELERA» del NAS, fuera de tu música. Si quieres alguna, la sacas de allí; si no, borras la carpeta cuando quieras."
                + (sinMirar > 0 ? "\n\n(" + sinMirar + " más sin mirar todavía.)" : ""))
            .setPositiveButton("APARTAR", (d, w) -> {
                aviso("Apartando " + malas.size() + " canciones…");
                new Thread(() -> {
                    final int[] ok = {0};
                    try {
                        conNas(disco -> {
                            if (!disco.folderExists("KULI PAPELERA")) disco.mkdir("KULI PAPELERA");
                            for (String r : malas) {
                                try {
                                    String archivo = r.substring(r.lastIndexOf('/') + 1);
                                    String destino = "KULI PAPELERA\\" + archivo;
                                    if (disco.fileExists(destino)) continue;
                                    try (File f = disco.openFile("KULI MUSIC\\" + r.replace('/', '\\'),
                                            EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                                            SMB2CreateDisposition.FILE_OPEN,
                                            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                                        f.rename(destino);
                                    }
                                    ok[0]++;
                                } catch (Exception e) { }
                            }
                            return null;
                        });
                    } catch (Throwable e) {
                        runOnUiThread(() -> mostrarErrorNas("No se pudieron apartar", e));
                        return;
                    }
                    runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("✓ Hecho")
                        .setMessage("Apartadas " + ok[0] + " canciones a «KULI PAPELERA».")
                        .setPositiveButton("OK", (dd, ww) -> abrirListaNas()).show());
                }).start();
            })
            .setNegativeButton("CANCELAR", null).show();
    }

    // V347: pregunta a iTunes el género de cada canción de Pop y propone moverla a su estilo.
    private void reclasificarPopInternet(final LinkedHashMap<String, ArrayList<String>> grupos) {
        if (!hayWifi()) { aviso("Necesitas WiFi o datos para esto"); return; }
        final ArrayList<String> pop = new ArrayList<>(grupos.get(carpetaOrden));
        final android.content.SharedPreferences memo = getSharedPreferences("kuli_itunes_genero", MODE_PRIVATE);
        aviso("Preguntando a iTunes… puedes dejar la app abierta");
        new Thread(() -> {
        pantallaFija(true); // V351
        try {
            final ArrayList<String[]> plan = new ArrayList<>();
            final TreeMap<String, Integer> resumen = new TreeMap<>();
            int hechas = 0, sinDato = 0;
            for (String r : pop) {
                String genero = memo.getString(r, null);
                if (genero == null) {
                    String termino = nombreSinExtension(r).replace('_', ' ').replace(" - ", " ")
                        .replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]", " ").trim();
                    String[] x = consultarItunes(termino);
                    genero = x == null ? "" : x[0];
                    memo.edit().putString(r, genero == null ? "" : genero).apply();
                    try { Thread.sleep(3200); } catch (InterruptedException ie) { break; }
                }
                String est = estiloDeGenero(genero);
                if (est == null) sinDato++;
                else if (!est.equals(carpetaOrden) && (carpetaOrden.equals("Pop") || !est.equals("Pop")) && !(carpetaOrden.equals("Flamenkito") && est.equals("Latino")) && !(!carpetaOrden.equals("Pop") && detectarEstiloPersonalizado(nombreSinExtension(r).replace('_', ' '), "", "", 0).equals(carpetaOrden))) {
                    plan.add(new String[]{r, est});
                    Integer v = resumen.get(est);
                    resumen.put(est, v == null ? 1 : v + 1);
                }
                hechas++;
                if (hechas % 40 == 0) {
                    final String msg = "iTunes: " + hechas + " de " + pop.size() + "…";
                    runOnUiThread(() -> aviso(msg));
                }
            }
            final int sd = sinDato;
            runOnUiThread(() -> {
                if (plan.isEmpty()) { aviso("iTunes no ha encontrado ningún estilo distinto de Pop"); return; }
                StringBuilder sb = new StringBuilder(plan.size() + " de las " + pop.size() + " canciones de Pop tienen otro estilo:\n\n");
                for (java.util.Map.Entry<String, Integer> e : resumen.entrySet()) sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                sb.append("\nSin dato en iTunes: ").append(sd).append(" (se quedan en Pop).\nSe puede deshacer.");
                new AlertDialog.Builder(this).setTitle("🌐 Reclasificar «Pop»").setMessage(sb.toString())
                    .setPositiveButton("MOVER", (d, w) -> moverVariasNas(plan, carpetaOrden))
                    .setNegativeButton("CANCELAR", null).show();
            });
        } finally { runOnUiThread(() -> pantallaFija(false)); }
        }).start();
    }

    private void reclasificarPopPorArtista(final LinkedHashMap<String, ArrayList<String>> grupos) {
        final ArrayList<String> pop = grupos.get(carpetaOrden);
        if (pop == null || pop.isEmpty()) { aviso("No hay carpeta Pop en el NAS"); return; }
        java.util.HashMap<String, java.util.HashMap<String, Integer>> cuenta = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, ArrayList<String>> e : grupos.entrySet()) {
            String est = e.getKey();
            if (est.equals(carpetaOrden) || est.equals("Sin clasificar")) continue;
            for (String r : e.getValue()) {
                String a = artistaDeNombreNas(r);
                if (a == null) continue;
                java.util.HashMap<String, Integer> c = cuenta.get(a);
                if (c == null) { c = new java.util.HashMap<>(); cuenta.put(a, c); }
                Integer v = c.get(est);
                c.put(est, v == null ? 1 : v + 1);
            }
        }
        java.util.HashMap<String, String> mejor = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.HashMap<String, Integer>> e : cuenta.entrySet()) {
            String m = null; int max = 0;
            for (java.util.Map.Entry<String, Integer> x : e.getValue().entrySet()) if (x.getValue() > max) { max = x.getValue(); m = x.getKey(); }
            if (m != null) mejor.put(e.getKey(), m);
        }
        final ArrayList<String[]> plan = new ArrayList<>();
        TreeMap<String, Integer> resumen = new TreeMap<>();
        for (String r : pop) {
            String a = artistaDeNombreNas(r);
            String est = a == null ? null : mejor.get(a);
            if (est == null) continue;
            plan.add(new String[]{r, est});
            Integer v = resumen.get(est);
            resumen.put(est, v == null ? 1 : v + 1);
        }
        if (plan.isEmpty()) { aviso("No encuentro ninguna canción de Pop con un artista que ya esté en otra carpeta"); return; }
        StringBuilder sb = new StringBuilder(plan.size() + " de las " + pop.size() + " canciones de Pop se pueden colocar:\n\n");
        for (java.util.Map.Entry<String, Integer> e : resumen.entrySet()) sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        sb.append("\nEl resto se queda en Pop. Se puede deshacer.");
        new AlertDialog.Builder(this).setTitle("🎨 Reclasificar «Pop»").setMessage(sb.toString())
            .setPositiveButton("MOVER", (d, w) -> moverVariasNas(plan, carpetaOrden))
            .setNegativeButton("CANCELAR", null).show();
    }

    // V346: mueve varias canciones del NAS de carpeta. plan = {rutaRelativa, estiloDestino}. estiloOrigen = el que había (para deshacer).
    private final String[] motivoFallo = {""}; // V352

    private void moverVariasNas(final ArrayList<String[]> plan, final String estiloOrigen) {
        aviso("Moviendo " + plan.size() + " canciones… no cierres la app");
        new Thread(() -> {
            final ArrayList<String[]> hechas = new ArrayList<>();
            int fallos = 0;
            try {
                final ArrayList<String[]> lote = plan;
                final ArrayList<String[]> ok = new ArrayList<>();
                final int[] mal = {0};
                conNas(disco -> {
                    motivoFallo[0] = "";
                    java.util.HashSet<String> creadas = new java.util.HashSet<>();
                    for (String[] p : lote) {
                        try {
                            String estilo = p[1].replaceAll("[\\/:*?\"<>|]", "_").trim();
                            if (estilo.isEmpty()) estilo = "Sin clasificar";
                            String archivo = p[0].substring(p[0].lastIndexOf('/') + 1);
                            if (creadas.add(estilo) && !disco.folderExists("KULI MUSIC\\" + estilo)) disco.mkdir("KULI MUSIC\\" + estilo);
                            String origen = "KULI MUSIC\\" + p[0].replace('/', '\\');
                            String destino = "KULI MUSIC\\" + estilo + "\\" + archivo;
                            if (disco.fileExists(destino)) { mal[0]++; if (motivoFallo[0].isEmpty()) motivoFallo[0] = "Ya existe en destino: " + destino; continue; }
                            try (File f = disco.openFile(origen,
                                    EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                                    SMB2CreateDisposition.FILE_OPEN,
                                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                                f.rename(destino);
                            }
                            ok.add(new String[]{p[0], estilo + "/" + archivo});
                        } catch (Exception e) { mal[0]++; if (motivoFallo[0].isEmpty()) motivoFallo[0] = e.getClass().getSimpleName() + ": " + e.getMessage() + " [" + p[0] + "]"; }
                    }
                    return null;
                });
                hechas.addAll(ok);
                fallos = mal[0];
            } catch (Throwable e) {
                runOnUiThread(() -> mostrarErrorNas("No se pudieron mover las canciones", e));
                return;
            }
            final int f2 = fallos;
            runOnUiThread(() -> {
                for (String[] h : hechas) actualizarFavoritoMovido(h[0], h[1]);
                final ArrayList<String[]> inverso = new ArrayList<>();
                for (String[] h : hechas) inverso.add(new String[]{h[1], estiloOrigen});
                AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("✓ Hecho")
                    .setMessage("Movidas " + hechas.size() + " canciones" + (f2 > 0 ? " (" + f2 + " no se han podido mover)" : "") + "."
                        + (f2 > 0 && !motivoFallo[0].isEmpty() ? "\n\nMotivo del primer fallo:\n" + motivoFallo[0] : ""))
                    .setPositiveButton("OK", (d, w) -> abrirListaNas());
                if (!inverso.isEmpty()) b.setNeutralButton("↩ DESHACER", (d, w) -> moverVariasNas(inverso, "Pop"));
                b.show();
            });
        }).start();
    }

    // ---------- Modo DJ: 4 canciones de un estilo, luego otro estilo ----------
    private void modoDj(LinkedHashMap<String, ArrayList<String>> grupos) {
        java.util.Random azar = new java.util.Random();
        final android.content.SharedPreferences hp = getSharedPreferences("kuli_dj_hist", MODE_PRIVATE); // V350
        final java.util.HashSet<String> histDj = new java.util.HashSet<>();
        final ArrayList<String> histLista = new ArrayList<>();
        for (String l : hp.getString("h", "").split("\n")) if (!l.isEmpty()) { histDj.add(l); histLista.add(l); }
        LinkedHashMap<String, ArrayList<String>> baraja = new LinkedHashMap<>();
        for (String k : grupos.keySet()) {
            if (k.equals("Sin clasificar") || grupos.get(k).isEmpty()) continue;
            if (k.equals("Navidad") && !temporadaNavidad()) continue; // V348
            ArrayList<String> c = new ArrayList<>(grupos.get(k));
            Collections.shuffle(c, azar);
            // V350: las que sonaron en las últimas mezclas se dejan para el final de su carpeta.
            ArrayList<String> frescas = new ArrayList<>(), viejas = new ArrayList<>();
            for (String r : c) (histDj.contains(r) ? viejas : frescas).add(r);
            frescas.addAll(viejas);
            baraja.put(k, frescas);
        }
        if (baraja.size() < 2) { aviso("Para el DJ hacen falta al menos 2 estilos con canciones"); return; }
        final java.util.HashMap<String, Integer> usados = new java.util.HashMap<>(); // V350: bloques ya puestos por estilo
        ArrayList<String> orden = new ArrayList<>();
        StringBuilder secuencia = new StringBuilder();
        String ultimo = null;
        int bloques = 0;
        while (orden.size() < 400) {
            ArrayList<String> candidatos = new ArrayList<>();
            for (String k : baraja.keySet()) if (!baraja.get(k).isEmpty() && !k.equals(ultimo)) candidatos.add(k);
            if (candidatos.isEmpty()) for (String k : baraja.keySet()) if (!baraja.get(k).isEmpty()) candidatos.add(k);
            if (candidatos.isEmpty()) break;
            // V350: se elige entre los estilos que menos bloques llevan, para que ninguno (Flamenkito, Pop…) lo tape todo.
            int minB = Integer.MAX_VALUE;
            for (String k : candidatos) { Integer u = usados.get(k); if ((u == null ? 0 : u) < minB) minB = u == null ? 0 : u; }
            ArrayList<String> menos = new ArrayList<>();
            for (String k : candidatos) { Integer u = usados.get(k); if ((u == null ? 0 : u) == minB) menos.add(k); }
            String estilo = menos.get(azar.nextInt(menos.size()));
            usados.put(estilo, minB + 1);
            ArrayList<String> lista = baraja.get(estilo);
            for (int i = 0; i < 4 && !lista.isEmpty(); i++) orden.add(lista.remove(0));
            if (bloques++ < 6) secuencia.append(secuencia.length() == 0 ? "" : " → ").append(estilo);
            ultimo = estilo;
        }
        // V350: se recuerdan las primeras 150 de esta mezcla (las últimas 400 en total).
        for (int i = 0; i < orden.size() && i < 150; i++) if (!histDj.contains(orden.get(i))) { histLista.add(orden.get(i)); histDj.add(orden.get(i)); }
        while (histLista.size() > 400) histLista.remove(0);
        hp.edit().putString("h", android.text.TextUtils.join("\n", histLista)).apply();
        aleatorio = false;
        estiloBoton(mezcla, APAGADO, 1000);
        aviso("🎛 DJ: " + secuencia + " …");
        reproducirListaNas(orden, 0);
        djActivo = true;
        if (!fundidoArrancado) { fundidoArrancado = true; ui.post(fundido); }
    }

    // ---------- Llevar canciones del NAS al móvil ----------
    private java.io.File carpetaMovil() {
        return new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
    }

    private void elegirParaMovil(final LinkedHashMap<String, ArrayList<String>> grupos) {
        final ArrayList<String> claves = new ArrayList<>(grupos.keySet());
        final boolean[] marcadas = new boolean[claves.size()];
        String[] items = new String[claves.size()];
        for (int i = 0; i < items.length; i++) items[i] = claves.get(i) + "  (" + grupos.get(claves.get(i)).size() + ")";
        new AlertDialog.Builder(this).setTitle("📥 ¿Qué carpetas llevo al móvil?")
            .setMultiChoiceItems(items, marcadas, (d, i, v) -> marcadas[i] = v)
            .setPositiveButton("LLEVAR", (d, w) -> {
                ArrayList<String> rutas = new ArrayList<>();
                for (int i = 0; i < marcadas.length; i++) if (marcadas[i]) rutas.addAll(grupos.get(claves.get(i)));
                if (rutas.isEmpty()) { aviso("No has marcado ninguna carpeta"); return; }
                llevarAlMovil(rutas);
            })
            .setNegativeButton("CANCELAR", null).show();
    }

    private ArrayList<String> pendienteMovil = null;

    // ---------- Ordenar música: Álbumes / Sueltas / Duplicadas ----------
    // ESTILOS-KULI: menú de ordenar + carpetas por estilo con deshacer.
    private void ordenarMusica() {
        new AlertDialog.Builder(this).setTitle("🧹 Ordenar música")
            .setItems(new String[]{"🧹 Ordenar y poner nombres", "🎨 Carpetas por estilo", "📥 Juntar música de otras carpetas", "↩ Deshacer el último movimiento"}, (d, i) -> {
                if (i == 0) ordenarClasico();
                else if (i == 1) ordenarPorEstilos();
                else if (i == 2) juntarMusica();
                else deshacerEstilos();
            }).setNegativeButton("Cancelar", null).show();
    }

    private java.io.File registroEstilos() { return new java.io.File(getFilesDir(), "deshacer_estilos.txt"); }

    private void ordenarPorEstilos() {
        if (android.os.Build.VERSION.SDK_INT < 33
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 202);
            return;
        }
        final java.io.File base = carpetaMovil();
        if (!base.exists()) { aviso("Aún no existe Música › KULI MUSIC"); return; }
        new AlertDialog.Builder(this).setTitle("🎨 Carpetas por estilo")
            .setMessage("Cada canción irá a su carpeta de estilo dentro de Música › KULI MUSIC (Flamenkito, Latino, Años 90…).\n\nNo se borra nada. Tus carpetas y «Duplicadas» no se tocan. Podrás deshacerlo.\n\n¿Qué canciones muevo?")
            .setPositiveButton("SOLO SUELTAS", (d, w) -> planEstilos(base, false))
            .setNeutralButton("TODAS (con álbumes)", (d, w) -> planEstilos(base, true))
            .setNegativeButton("CANCELAR", null).show();
    }

    private void planEstilos(final java.io.File base, final boolean conAlbumes) {
        aviso("Mirando los estilos… puede tardar un poco");
        new Thread(() -> {
            java.util.ArrayList<java.io.File> todas = new java.util.ArrayList<>();
            recolectarMp3Directo(base, todas);
            artistaEstilo = null; // V345
            cargarArtistasAprendidos();
            android.content.SharedPreferences cache = getSharedPreferences("kuli_estilos", MODE_PRIVATE);
            android.content.SharedPreferences.Editor ed = cache.edit();
            final java.util.ArrayList<java.io.File[]> plan = new java.util.ArrayList<>();
            final java.util.TreeMap<String, Integer> cuenta = new java.util.TreeMap<>();
            String basePath = base.getAbsolutePath() + "/";
            for (java.io.File f : todas) {
                String ruta = f.getAbsolutePath();
                if (!ruta.startsWith(basePath)) continue;
                String rel = ruta.substring(basePath.length());
                int barra = rel.indexOf('/');
                String top = barra < 0 ? "" : rel.substring(0, barra);
                boolean mover = top.isEmpty() || top.equals(OrdenarMusica.SUELTAS) || (conAlbumes && top.equals(OrdenarMusica.ALBUMES));
                if (!mover) continue;
                String st = estiloDeArchivo(f, cache, ed);
                if (st == null || st.trim().isEmpty()) st = "Variados";
                String carpeta = OrdenarMusica.seguro(st);
                plan.add(new java.io.File[]{f, new java.io.File(new java.io.File(base, carpeta), f.getName())});
                Integer c = cuenta.get(carpeta);
                cuenta.put(carpeta, c == null ? 1 : c + 1);
            }
            ed.apply();
            runOnUiThread(() -> { if (!isDestroyed()) resumenEstilos(base, plan, cuenta); });
        }).start();
    }

    private void resumenEstilos(final java.io.File base, final java.util.ArrayList<java.io.File[]> plan, java.util.TreeMap<String, Integer> cuenta) {
        if (plan.isEmpty()) { aviso("No hay canciones que mover"); return; }
        StringBuilder sb = new StringBuilder(plan.size() + " canciones irán a estas carpetas:\n\n");
        for (java.util.Map.Entry<String, Integer> e : cuenta.entrySet()) sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        new AlertDialog.Builder(this).setTitle("🎨 Carpetas por estilo").setMessage(sb.toString())
            .setPositiveButton("MOVER", (d, w) -> moverEstilos(base, plan))
            .setNegativeButton("CANCELAR", null).show();
    }

    private void moverEstilos(final java.io.File base, final java.util.ArrayList<java.io.File[]> plan) {
        aviso("Moviendo… no cierres la app");
        new Thread(() -> {
            int hechas = 0, fallos = 0;
            java.util.ArrayList<String> rutas = new java.util.ArrayList<>();
            StringBuilder reg = new StringBuilder();
            for (java.io.File[] par : plan) {
                java.io.File dest = OrdenarMusica.libre(par[1]);
                rutas.add(par[0].getAbsolutePath());
                if (OrdenarMusica.mover(par[0], dest)) {
                    hechas++;
                    rutas.add(dest.getAbsolutePath());
                    reg.append(dest.getAbsolutePath()).append('\t').append(par[0].getAbsolutePath()).append('\n');
                } else fallos++;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(registroEstilos())) {
                out.write(reg.toString().getBytes("UTF-8"));
            } catch (Exception ignorar) { }
            OrdenarMusica.quitarVacias(base, true);
            android.media.MediaScannerConnection.scanFile(this, rutas.toArray(new String[0]), null, null);
            final int h = hechas, f = fallos;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle("✓ Ordenado por estilos")
                    .setMessage("Movidas: " + h + (f > 0 ? "\nNo se pudieron mover: " + f : ""))
                    .setPositiveButton("OK", null)
                    .setNeutralButton("↩ DESHACER", (d, w) -> deshacerEstilos()).show();
            });
        }).start();
    }

    private void deshacerEstilos() {
        final java.io.File reg = registroEstilos();
        if (!reg.exists()) { aviso("No hay nada que deshacer"); return; }
        aviso("Deshaciendo…");
        new Thread(() -> {
            int hechas = 0, fallos = 0;
            java.util.ArrayList<String> rutas = new java.util.ArrayList<>();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(reg), "UTF-8"))) {
                String l;
                while ((l = br.readLine()) != null) {
                    int t = l.indexOf('\t');
                    if (t <= 0) continue;
                    java.io.File desde = new java.io.File(l.substring(0, t)), hacia = new java.io.File(l.substring(t + 1));
                    if (!desde.exists()) { fallos++; continue; }
                    java.io.File d2 = OrdenarMusica.libre(hacia);
                    rutas.add(desde.getAbsolutePath());
                    if (OrdenarMusica.mover(desde, d2)) { hechas++; rutas.add(d2.getAbsolutePath()); } else fallos++;
                }
            } catch (Exception ignorar) { }
            reg.delete();
            OrdenarMusica.quitarVacias(carpetaMovil(), true);
            android.media.MediaScannerConnection.scanFile(this, rutas.toArray(new String[0]), null, null);
            final int h = hechas, f = fallos;
            runOnUiThread(() -> aviso("↩ Devueltas " + h + (f > 0 ? " · " + f + " no estaban" : "")));
        }).start();
    }

    // JUNTAR-KULI: trae la música que está fuera de Música › KULI MUSIC a su carpeta de estilo.
    private void juntarMusica() {
        if (android.os.Build.VERSION.SDK_INT < 33
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 202);
            return;
        }
        final java.io.File base = carpetaMovil();
        aviso("Buscando música fuera de KULI MUSIC…");
        new Thread(() -> {
            final java.util.LinkedHashMap<java.io.File, java.util.ArrayList<java.io.File>> grupos = new java.util.LinkedHashMap<>();
            String basePath = base.getAbsolutePath() + "/";
            android.database.Cursor c = null;
            try {
                c = getContentResolver().query(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Audio.Media.DATA, android.provider.MediaStore.Audio.Media.DURATION},
                    null, null, null);
                while (c != null && c.moveToNext()) {
                    String p = c.getString(0);
                    long dur = c.getLong(1);
                    if (p == null || dur < 60000 || p.startsWith(basePath) || !OrdenarMusica.esAudio(p)) continue;
                    String pl = p.toLowerCase(java.util.Locale.ROOT);
                    if (pl.contains("/android/") || pl.contains("song_cache") || pl.contains("/ringtones/") || pl.contains("/alarms/")
                        || pl.contains("/notifications/") || pl.contains("/recordings/") || pl.contains("/whatsapp") || pl.contains("/call")) continue;
                    java.io.File f = new java.io.File(p);
                    if (!f.isFile() || f.getParentFile() == null) continue;
                    java.util.ArrayList<java.io.File> l = grupos.get(f.getParentFile());
                    if (l == null) { l = new java.util.ArrayList<>(); grupos.put(f.getParentFile(), l); }
                    l.add(f);
                }
            } catch (Throwable e) {
                android.util.Log.e("KULI_JUNTAR", "Buscar", e);
            } finally {
                if (c != null) { try { c.close(); } catch (Throwable ignorar) { } }
            }
            runOnUiThread(() -> mostrarCarpetasFuera(base, grupos));
        }).start();
    }

    private void mostrarCarpetasFuera(final java.io.File base, final java.util.LinkedHashMap<java.io.File, java.util.ArrayList<java.io.File>> grupos) {
        if (isDestroyed()) return;
        if (grupos.isEmpty()) { aviso("No hay música fuera de KULI MUSIC"); return; }
        final java.util.ArrayList<java.io.File> dirs = new java.util.ArrayList<>(grupos.keySet());
        final String[] items = new String[dirs.size()];
        final boolean[] marcadas = new boolean[dirs.size()];
        int total = 0;
        for (int i = 0; i < items.length; i++) {
            int n = grupos.get(dirs.get(i)).size();
            total += n;
            items[i] = dirs.get(i).getName() + "  (" + n + ")";
            marcadas[i] = true;
        }
        new AlertDialog.Builder(this).setTitle("📥 " + total + " canciones fuera de KULI MUSIC")
            .setMultiChoiceItems(items, marcadas, (d, i, v) -> marcadas[i] = v)
            .setPositiveButton("MOVER", (d, w) -> {
                java.util.ArrayList<java.io.File> elegidas = new java.util.ArrayList<>();
                for (int i = 0; i < marcadas.length; i++) if (marcadas[i]) elegidas.addAll(grupos.get(dirs.get(i)));
                if (elegidas.isEmpty()) { aviso("No has marcado ninguna carpeta"); return; }
                traerFuera(base, elegidas);
            })
            .setNegativeButton("CANCELAR", null).show();
    }

    private void traerFuera(final java.io.File base, final java.util.ArrayList<java.io.File> archivos) {
        aviso("Moviendo " + archivos.size() + " canciones… no cierres la app");
        new Thread(() -> {
            java.util.HashSet<String> ya = new java.util.HashSet<>();
            java.util.ArrayList<java.io.File> loc = new java.util.ArrayList<>();
            recolectarMp3Directo(base, loc);
            for (java.io.File x : loc) ya.add(claveNombre(x.getName()));
            android.content.SharedPreferences cache = getSharedPreferences("kuli_estilos", MODE_PRIVATE);
            android.content.SharedPreferences.Editor ed = cache.edit();
            int hechas = 0, repetidas = 0, copiadas = 0, fallos = 0;
            java.util.ArrayList<String> rutas = new java.util.ArrayList<>();
            StringBuilder reg = new StringBuilder();
            for (java.io.File f : archivos) {
                String clave = claveNombre(f.getName());
                if (ya.contains(clave)) { repetidas++; continue; }
                String st = estiloDeArchivo(f, cache, ed);
                if (st == null || st.trim().isEmpty()) st = "Variados";
                java.io.File dest = OrdenarMusica.libre(new java.io.File(new java.io.File(base, OrdenarMusica.seguro(st)), f.getName()));
                rutas.add(f.getAbsolutePath());
                if (OrdenarMusica.mover(f, dest)) {
                    hechas++; ya.add(clave);
                    rutas.add(dest.getAbsolutePath());
                    reg.append(dest.getAbsolutePath()).append('\t').append(f.getAbsolutePath()).append('\n');
                } else if (dest.exists() && dest.length() == f.length()) {
                    copiadas++; ya.add(clave);
                    rutas.add(dest.getAbsolutePath());
                } else fallos++;
            }
            ed.apply();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(registroEstilos())) {
                out.write(reg.toString().getBytes("UTF-8"));
            } catch (Exception ignorar) { }
            android.media.MediaScannerConnection.scanFile(this, rutas.toArray(new String[0]), null, null);
            final int h = hechas, r = repetidas, c = copiadas, f2 = fallos;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                StringBuilder sb = new StringBuilder("Movidas a su carpeta de estilo: " + h);
                if (c > 0) sb.append("\nCopiadas (Android no dejó quitar la original): ").append(c);
                if (r > 0) sb.append("\nYa estaban en KULI MUSIC, no se tocan: ").append(r);
                if (f2 > 0) sb.append("\nNo se pudieron mover: ").append(f2);
                new AlertDialog.Builder(this).setTitle("✓ Música juntada").setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .setNeutralButton("↩ DESHACER", (d, w) -> deshacerEstilos()).show();
            });
        }).start();
    }

    private void ordenarClasico() {
        if (android.os.Build.VERSION.SDK_INT < 33
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 202);
            return;
        }
        final java.io.File base = carpetaMovil();
        if (!base.exists()) { aviso("Aún no existe Música › KULI MUSIC"); return; }
        aviso("Mirando tu música…");
        new Thread(() -> {
            final OrdenarMusica.Plan plan = OrdenarMusica.planear(base);
            runOnUiThread(() -> resumenOrdenar(base, plan));
        }).start();
    }

    private void resumenOrdenar(final java.io.File base, final OrdenarMusica.Plan plan) {
        if (isDestroyed()) return;
        final int guardadas = OrdenarMusica.duplicadasGuardadas(base).size();
        String msg = plan.total + " canciones en Música › KULI MUSIC.\n\n"
            + "• Álbumes: " + plan.enAlbumes + " canciones en " + plan.nAlbumes + " álbumes\n"
            + "• Sueltas: " + plan.enSueltas + "\n"
            + "• Repetidas: " + plan.duplicadas.size() + " (se apartan en «Duplicadas», no se borran)\n"
            + "• Nombres que se arreglan: " + plan.renombradas + "\n"
            + (plan.ejemplos.isEmpty() ? "" : "\nEjemplos:\n" + android.text.TextUtils.join("\n", plan.ejemplos) + (plan.renombradas > plan.ejemplos.size() ? "\n…" : "") + "\n")
            + "\nSe moverán " + (plan.mover.size() + plan.duplicadas.size()) + " archivos."
            + (guardadas > 0 ? "\n\nYa hay " + guardadas + " en «Duplicadas» esperando a que las borres." : "");
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("🧹 Ordenar y poner nombres").setMessage(msg)
            .setNegativeButton("CANCELAR", null);
        if (plan.mover.size() + plan.duplicadas.size() > 0) {
            b.setPositiveButton("ORDENAR", (d, w) -> {
                aviso("Ordenando… no cierres la app");
                new Thread(() -> {
                    int[] fallos = {0};
                    java.util.ArrayList<String> rutas = OrdenarMusica.ejecutar(plan, base, fallos);
                    android.media.MediaScannerConnection.scanFile(this, rutas.toArray(new String[0]), null, null);
                    final int nf = fallos[0];
                    runOnUiThread(() -> {
                        aviso(nf == 0 ? "✓ Música ordenada" : "Ordenada, pero " + nf + " archivos no se pudieron mover");
                        if (!isDestroyed() && !OrdenarMusica.duplicadasGuardadas(base).isEmpty()) revisarDuplicadas(base);
                    });
                }).start();
            });
        }
        if (guardadas > 0) b.setNeutralButton("🗑 DUPLICADAS", (d, w) -> revisarDuplicadas(base));
        b.show();
    }

    private void revisarDuplicadas(final java.io.File base) {
        final java.util.ArrayList<java.io.File> l = OrdenarMusica.duplicadasGuardadas(base);
        if (l.isEmpty()) { aviso("No hay duplicadas"); return; }
        String[] nombres = new String[l.size()];
        for (int i = 0; i < nombres.length; i++) nombres[i] = l.get(i).getName();
        new AlertDialog.Builder(this).setTitle("🗑 " + l.size() + " canciones repetidas")
            .setItems(nombres, null)
            .setPositiveButton("BORRAR TODAS", (d, w) -> new AlertDialog.Builder(this)
                .setTitle("¿Seguro?").setMessage("Se borrarán " + l.size() + " canciones repetidas. No se pueden recuperar.")
                .setPositiveButton("BORRAR", (d2, w2) -> {
                    int n = 0;
                    java.util.ArrayList<String> rutas = new java.util.ArrayList<>();
                    java.util.ArrayList<String> fallidas = new java.util.ArrayList<>();
                    for (java.io.File f : l) {
                        rutas.add(f.getAbsolutePath());
                        boolean ok = f.delete();
                        if (!ok) {
                            try {
                                getContentResolver().delete(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                    android.provider.MediaStore.MediaColumns.DATA + "=?", new String[]{f.getAbsolutePath()});
                            } catch (Throwable ignorar) { }
                            ok = !f.exists();
                        }
                        if (ok) n++; else fallidas.add(f.getName());
                    }
                    android.media.MediaScannerConnection.scanFile(this, rutas.toArray(new String[0]), null, null);
                    if (fallidas.isEmpty()) aviso("Borradas " + n);
                    else new AlertDialog.Builder(this).setTitle("No pude borrar " + fallidas.size())
                        .setMessage("Borradas: " + n + "\n\nAndroid no me deja borrar estas porque las creó otra app. Bórralas tú desde Archivos › Música › KULI MUSIC › Duplicadas:\n\n" + android.text.TextUtils.join("\n", fallidas))
                        .setPositiveButton("CERRAR", null).show();
                })
                .setNegativeButton("NO", null).show())
            .setNegativeButton("DEJARLAS", null).show();
    }

    private void llevarAlMovil(final ArrayList<String> rutas) {
        if (android.os.Build.VERSION.SDK_INT < 33
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendienteMovil = rutas;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 201);
            return;
        }
        final java.io.File base = carpetaMovil();
        aviso("Copiando " + rutas.size() + " canciones… puedes seguir usando el móvil");
        new Thread(() -> {
            int hechas = 0, saltadas = 0, fallos = 0;
            for (int i = 0; i < rutas.size(); i++) {
                final String ruta = rutas.get(i);
                final int n = i + 1;
                runOnUiThread(() -> estado.setText("📥 AL MÓVIL " + n + "/" + rutas.size()));
                java.io.File destino = new java.io.File(base, ruta.replace('\\', '/'));
                if (destino.exists() && destino.length() > 0) { saltadas++; continue; }
                try {
                    destino.getParentFile().mkdirs();
                    descargarDelNas(ruta, destino);
                    hechas++;
                } catch (Throwable e) {
                    fallos++;
                    if (fallos >= 3 && hechas == 0) break; // el NAS no responde
                }
            }
            final String msg = "Copiadas: " + hechas + (saltadas > 0 ? " · ya estaban: " + saltadas : "")
                    + (fallos > 0 ? " · con error: " + fallos : "");
            runOnUiThread(() -> {
                estado.setText("");
                if (isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle("📥 Listo")
                    .setMessage(msg + "\n\nEstán en Música › KULI MUSIC.\nAhora pulsa «📂 Carpeta de música del móvil» y elige esa carpeta (solo la primera vez).")
                    .setPositiveButton("ELEGIR CARPETA", (d, w) -> elegirCarpetaMusica())
                    .setNegativeButton("CERRAR", null).show();
            });
        }).start();
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
            .setNeutralButton("⬇️ BAJAR CARPETA", (d, w) -> descargarCarpetaNasAlMovil(rutas))
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
        inicio = elegirInicioSinRepetir(listaFavoritas, inicio);
        posicionFavorita = inicio;
        reproducir(Uri.parse(listaFavoritas.get(inicio)));
    }

    private void cargarListaNas(List<String> nombres) {
        djActivo = false;
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
            try (File origen = disco.openFile("KULI MUSIC\\\\" + nombre.replace('/', '\\'),
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
        aviso("Escaneando carpetas de música...");
        new Thread(() -> {
            final java.util.TreeMap<String, ArrayList<Uri>> carpetas = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            String[] proj = { android.provider.MediaStore.Audio.Media._ID, android.provider.MediaStore.Audio.Media.DATA, android.provider.MediaStore.Audio.Media.DISPLAY_NAME };
            try (Cursor c = getContentResolver().query(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, android.provider.MediaStore.Audio.Media.IS_MUSIC + "!=0", null, null)) {
                if (c != null) {
                    int idxId = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID);
                    int idxData = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA);
                    while (c.moveToNext()) {
                        long id = c.getLong(idxId);
                        String ruta = idxData >= 0 ? c.getString(idxData) : null;
                        String nomCarpeta = "Música General";
                        if (ruta != null) {
                            java.io.File pf = new java.io.File(ruta).getParentFile();
                            if (pf != null && pf.getName() != null && !pf.getName().isEmpty()) nomCarpeta = pf.getName();
                        }
                        Uri u = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                        carpetas.computeIfAbsent(nomCarpeta, k -> new ArrayList<>()).add(u);
                    }
                }
            } catch (Exception ignored) { }
            runOnUiThread(() -> {
                if (carpetas.isEmpty()) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("audio/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); startActivityForResult(i, PICK_SUBIR);
                    return;
                }
                final ArrayList<String> nombres = new ArrayList<>(carpetas.keySet());
                final String[] etiquetas = new String[nombres.size() + 1];
                int totalGlobal = 0;
                for (int k = 0; k < nombres.size(); k++) {
                    int n = carpetas.get(nombres.get(k)).size();
                    totalGlobal += n;
                    etiquetas[k] = "📁 " + nombres.get(k) + "  (" + n + " canciones)";
                }
                etiquetas[nombres.size()] = "🚀 SUBIR TODAS LAS CARPETAS (" + totalGlobal + " canciones)";
                new AlertDialog.Builder(this).setTitle("Elige carpeta para subir al NAS").setItems(etiquetas, (d, which) -> {
                    if (which == nombres.size()) {
                        ArrayList<Uri> todas = new ArrayList<>();
                        for (String key : nombres) todas.addAll(carpetas.get(key));
                        ofrecerSubida(todas, null, null);
                    } else {
                        String carp = nombres.get(which);
                        ofrecerSubida(carpetas.get(carp), null, null);
                    }
                }).setNegativeButton("Cancelar", null).show();
            });
        }).start();
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
                            for (var g : disco.list("KULI MUSIC\\\\" + n))
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
    // ESPEJO-KULI
    private void saltar(int direccion) {
        if (modoDjActivo && colaDj != null && !colaDj.isEmpty()) {
            indiceDj = (indiceDj + direccion + colaDj.size()) % colaDj.size();
            reproducirConFundidoDj(colaDj.get(indiceDj));
            return;
        }
        if (!modoLista || listaFavoritas.isEmpty()) {
            saltarGeneral(direccion); return;
        }
        int siguienteIndice;
        if (aleatorio && direccion > 0 && listaFavoritas.size() > 1) {
            siguienteIndice = azarFresco();
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
                    c.setInstanceFollowRedirects(true); c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36");
                    String nombre = null, grupo = null, tvgId = null;
                    boolean soloEs = f.contains("Free-TV");
                    boolean porId = false;
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
            if (filtro == null || (filtro.equals("\u0001DEPORTES") ? esDeportivo(tvNombres.get(i)) : (filtro.equals("\u0001CINE") ? esCine(tvNombres.get(i))
                    : tvNombres.get(i).toLowerCase().contains(filtro.toLowerCase())))) {
                n.add(tvNombres.get(i)); u.add(tvUrls.get(i));
            }
        if (filtro == null) {
            ArrayList<String> ordN = new ArrayList<>(), ordU = new ArrayList<>();
            String[][] top = {
                {"1. La 1", "la 1", null},
                {"2. La 2", "la 2", null},
                {"3. Antena 3", "antena 3", "web:https://atresplayer.com"},
                {"4. Cuatro", "cuatro", "web:https://mitele.es"},
                {"5. Telecinco", "telecinco", "web:https://mitele.es"},
                {"6. laSexta", "lasexta", "web:https://atresplayer.com"},
                {"7. La Séptima", "la septima", "web:https://tdtchannels.com"}
            };
            boolean[] usados = new boolean[n.size()];
            for (String[] t : top) {
                if (t[2] != null && !t[1].equals("la septima")) {
                    ordN.add(t[0]); ordU.add(t[2]);
                    for (int i = 0; i < n.size(); i++) {
                        String low = n.get(i).toLowerCase().replace(" ", "");
                        if (low.contains(t[1].replace(" ", ""))) usados[i] = true;
                    }
                    continue;
                }
                int found = -1;
                for (int i = 0; i < n.size(); i++) {
                    String low = n.get(i).toLowerCase().replace("á","a").replace("é","e").replace("í","i").replace("ó","o").replace("ú","u");
                    if (low.equals("la7") || low.startsWith("la7 ")) continue;
                    String key = t[1];
                    if (!usados[i] && (low.equals(key) || low.startsWith(key + " ") || low.contains(key))) {
                        found = i; break;
                    }
                }
                if (found >= 0) {
                    ordN.add(t[0]); ordU.add(u.get(found)); usados[found] = true;
                } else if (t[2] != null) {
                    ordN.add(t[0]); ordU.add(t[2]);
                }
            }
            for (int i = 0; i < n.size(); i++) if (!usados[i]) { ordN.add(n.get(i)); ordU.add(u.get(i)); }
            n.clear(); n.addAll(ordN);
            u.clear(); u.addAll(ordU);
        }
        if (n.isEmpty()) { aviso("No hay canales con ese nombre"); return; }
        final LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(dp(12), dp(4), dp(12), 0);
        final AlertDialog[] dlg = new AlertDialog[1];
        String[] etiquetas = {filtro == null ? "⚽ DEPORTES" : "📺 TODOS", "🎬 CINE/SERIES", "🔍 BUSCAR"};
        for (int k = 0; k < 3; k++) {
            final int cual = k;
            Button bt = new Button(this);
            bt.setText(etiquetas[k]);
            bt.setAllCaps(false);
            bt.setOnClickListener(x -> {
                dlg[0].dismiss();
                if (cual == 0) mostrarCanalesTv(filtro == null ? "\u0001DEPORTES" : null);
                else if (cual == 1) mostrarCanalesTv("\u0001CINE");
                else pedirTexto("🔍 Buscar canal (ej. La 1, Pluto)", q -> mostrarCanalesTv(q));
            });
            caja.addView(bt, new LinearLayout.LayoutParams(-1, dp(46)));
        }
        ListView lista = new ListView(this);
        lista.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, n));
        lista.setOnItemClickListener((pa, vw, pos, id) -> {
            dlg[0].dismiss();
            if (u.get(pos).startsWith("web:")) reproducirTvWeb(n.get(pos), u.get(pos).substring(4));
            else reproducirTv(n.get(pos), u.get(pos));
        });
        caja.addView(lista, new LinearLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.45f)));
        dlg[0] = new AlertDialog.Builder(this)
            .setTitle(filtro != null && filtro.equals("\u0001DEPORTES") ? "⚽ Deportes" : "📺 TV en directo")
            .setView(caja).setNegativeButton("CERRAR", null).create();
        dlg[0].show();
    }

    private String versionApp() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable e) { return ""; }
    }

    private boolean esCine(String n) { String t = n.toLowerCase(); for (String c : new String[]{"cine","pelicula","película","movie","series","serie","accion","acción","crimen","drama","pluto","runtime","rakuten","paramount","hollywood","thriller"}) if (t.contains(c)) return true; return false; }
    private boolean esDeportivo(String nombre) {
        String t = nombre.toLowerCase();
        String[] claves = {"deport", "sport", "gol", "marca", "real madrid", "barça", "barca", "futbol", "fútbol",
                           "motor", "toros", "caza", "pesca", "padel", "pádel", "tenis", "basket", "baloncesto", "esport", "teledeporte", "tdp", "fifa", "red bull", "racing", "rally", "box", "ufc", "fight", "nba"};
        for (String c : claves) if (t.contains(c)) return true;
        return false;
    }

    private void reproducirTvWeb(final String nombre, final String url) {
        try { if (reproductor != null && reproductor.isPlaying()) reproductor.pause(); } catch (Exception ignored) { }
        final android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.setBackgroundColor(Color.BLACK);
        android.webkit.WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.setWebChromeClient(new android.webkit.WebChromeClient());
        wv.setWebViewClient(new android.webkit.WebViewClient());
        dlg.setContentView(wv);
        dlg.setOnDismissListener(d -> { try { wv.loadUrl("about:blank"); wv.destroy(); } catch (Exception ignored) { } });
        dlg.show();
        wv.loadUrl(url);
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
            dlg.dismiss();
            reproducirTvWeb(nombre, url);
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

    // Abre una app ya instalada buscándola por su nombre.
    private void abrirApp(String nombreBonito, String... claves) {
        android.content.pm.PackageManager pm = getPackageManager();
        for (android.content.pm.ApplicationInfo app : pm.getInstalledApplications(0)) {
            String nombre = pm.getApplicationLabel(app).toString().toLowerCase().replace(" ", "");
            String paquete = app.packageName.toLowerCase();
            for (String c : claves) {
                if (nombre.contains(c) || paquete.contains(c)) {
                    Intent i = pm.getLaunchIntentForPackage(app.packageName);
                    if (i != null) { startActivity(i); return; }
                }
            }
        }
        aviso(nombreBonito + " no encontrado. ¿Está instalado?");
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

    // TERMUX-KULI
    private int sinConvertir = 0;

    private boolean convertirMp3Termux(java.io.File src, java.io.File dst) {
        try {
            new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "kuli_mp3.sh").delete();
            if (dst.exists()) dst.delete();
            Intent ti = new Intent("com.termux.RUN_COMMAND");
            ti.setClassName("com.termux", "com.termux.app.RunCommandService");
            ti.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/ffmpeg");
            ti.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-y", "-i", src.getAbsolutePath(), "-vn", "-ar", "44100", "-ac", "2", "-b:a", "320k", dst.getAbsolutePath()});
            ti.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(ti); else startService(ti);
            long antes = -1;
            for (int w = 0; w < 240; w++) {
                Thread.sleep(500);
                long ahora = dst.exists() ? dst.length() : 0;
                if (ahora > 10000 && ahora == antes) return true;
                antes = ahora;
            }
        } catch (Exception e) { android.util.Log.e("KULI_MUSIC", "Convertir con Termux", e); }
        return dst.exists() && dst.length() > 10000;
    }

    private void clasificarSueltasPorEstilos() {
        new Thread(() -> {
            java.io.File base = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
            java.io.File sueltas = new java.io.File(base, "Sueltas");
            if (!sueltas.exists() || sueltas.listFiles() == null) { runOnUiThread(() -> aviso("No hay canciones en Sueltas.")); return; }
            int mov = 0;
            for (java.io.File f : sueltas.listFiles()) {
                if (!f.isFile() || !f.getName().endsWith(".mp3")) continue;
                String t = f.getName().toLowerCase();
                String gen = "Pop y Varios";
                if (t.matches(".*(reggaeton|perreo|daddy|bad bunny|karol|feid|rauw|ozuna|anuel|maluma|j balvin|quevedo|myke).*")) gen = "Reggaeton y Urbano";
                else if (t.matches(".*(flamenco|rumba|camaron|paco|rosalia|tangos|bulerias|sevillanas|el barrio|niña pastori|ketama|estopa).*")) gen = "Flamenco y Rumba";
                else if (t.matches(".*(remix|dj|techno|house|electronica|edm|dance|club|guetta|tiesto|hardwell).*")) gen = "Electronica y Dance";
                else if (t.matches(".*(rock|metal|queen|acdc|metallica|nirvana|roll|guitar|punk).*")) gen = "Rock";
                else if (t.matches(".*(salsa|bachata|merengue|cumbia|romeo|prince royce|marc anthony|juan luis).*")) gen = "Latino y Bachata";
                java.io.File gDir = new java.io.File(base, gen); gDir.mkdirs();
                if (f.renameTo(new java.io.File(gDir, f.getName()))) mov++;
            }
            final int total = mov;
            runOnUiThread(() -> { aviso("Clasificadas " + total + " canciones por estilos."); recreate(); });
        }).start();
    }

    private void copiarSafNativo(Uri treeUri, String docId, java.io.File dest) {
        Uri childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        try (android.database.Cursor c = getContentResolver().query(childrenUri, new String[]{
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
        }, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String cid = c.getString(0), nm = c.getString(1), mime = c.getString(2);
                if (nm == null || nm.equals("cache") || nm.equals("code_cache") || nm.equals("image_manager_disk_cache") || nm.equals("shared_prefs")) continue;
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    java.io.File sub = new java.io.File(dest, nm); sub.mkdirs(); copiarSafNativo(treeUri, cid, sub);
                } else {
                    Uri fUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, cid);
                    try (java.io.InputStream is = getContentResolver().openInputStream(fUri); java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(dest, nm))) {
                        byte[] b = new byte[8192]; int n; while ((n = is.read(b)) > 0) fos.write(b, 0, n);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private void recolectarAudiosKuli(java.io.File dir, java.util.Map<String, java.util.List<java.io.File>> byId) {
        java.io.File[] ls = dir.listFiles(); if (ls == null) return;
        for (java.io.File f : ls) {
            if (f.isDirectory()) recolectarAudiosKuli(f, byId);
            else if (f.length() > 50000 && !f.getName().contains(".db") && !f.getName().endsWith(".exi") && !f.getName().endsWith(".uid") && !f.getName().endsWith(".json") && !f.getName().startsWith("raw_")) {
                String nm = f.getName(); String pid = nm.contains(".") ? nm.substring(0, nm.indexOf(".")) : nm;
                if (!byId.containsKey(pid)) byId.put(pid, new java.util.ArrayList<>());
                byId.get(pid).add(f);
            }
        }
    }

    private java.io.File buscarArchivoKuli(java.io.File dir, String target) {
        java.io.File[] ls = dir.listFiles(); if (ls == null) return null;
        for (java.io.File f : ls) { if (f.getName().equals(target)) return f; if (f.isDirectory()) { java.io.File r = buscarArchivoKuli(f, target); if (r != null) return r; } }
        return null;
    }

    private void borrarDirKuli(java.io.File d) {
        if (d == null || !d.exists()) return;
        if (d.isDirectory() && d.listFiles() != null) for (java.io.File c : d.listFiles()) borrarDirKuli(c);
        d.delete();
    }

    private void pasarNuevasDePrismAMp3() {
        String uStr = getSharedPreferences("kuli_prefs", MODE_PRIVATE).getString("prism_uri", "content://com.prism.music.MTDataFilesProvider/tree/com.prism.music");
        aviso("Buscando nuevas en Prism y convirtiendo a MP3...");
        new Thread(() -> {
            try {
                java.io.File baseMusic = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
                baseMusic.mkdirs();
                java.io.File workTmp = new java.io.File(getCacheDir(), "prism_import_tmp");
                borrarDirKuli(workTmp); workTmp.mkdirs();
                Uri treeUri = Uri.parse(uStr);
                String rootDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                copiarSafNativo(treeUri, rootDocId, workTmp);
                java.io.File prismDb = buscarArchivoKuli(workTmp, "prism.db");
                java.io.File exoDb = buscarArchivoKuli(workTmp, "exoplayer_internal.db");
                java.util.Map<String, String> idToKey = new java.util.HashMap<>();
                if (exoDb != null && exoDb.exists()) {
                    try {
                        android.database.sqlite.SQLiteDatabase dbExo = android.database.sqlite.SQLiteDatabase.openDatabase(exoDb.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                        android.database.Cursor ct = dbExo.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'ExoPlayerCacheIndex%'", null);
                        if (ct.moveToFirst()) {
                            android.database.Cursor c1 = dbExo.rawQuery("SELECT id, key FROM " + ct.getString(0), null);
                            while (c1.moveToNext()) idToKey.put(c1.getString(0), c1.getString(1));
                            c1.close();
                        }
                        ct.close(); dbExo.close();
                    } catch (Exception ignored) {}
                }
                java.util.Map<String, String[]> metaById = new java.util.HashMap<>();
                java.util.Map<String, Integer> albumCounts = new java.util.HashMap<>();
                if (prismDb != null && prismDb.exists()) {
                    try {
                        android.database.sqlite.SQLiteDatabase dbP = android.database.sqlite.SQLiteDatabase.openDatabase(prismDb.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                        android.database.Cursor cs = dbP.rawQuery("SELECT id, title, albumName FROM song", null);
                        while (cs.moveToNext()) {
                            String sid = cs.getString(0), st = cs.getString(1), sa = cs.getString(2);
                            metaById.put(sid, new String[]{st, sa});
                            if (sa != null && !sa.trim().isEmpty() && !sa.trim().equalsIgnoreCase(st != null ? st.trim() : "")) {
                                String kAlb = sa.trim().toLowerCase();
                                albumCounts.put(kAlb, (albumCounts.containsKey(kAlb) ? albumCounts.get(kAlb) : 0) + 1);
                            }
                        }
                        cs.close(); dbP.close();
                    } catch (Exception ignored) {}
                }
                java.util.Map<String, java.util.List<java.io.File>> byId = new java.util.HashMap<>();
                recolectarAudiosKuli(workTmp, byId);
                final java.util.ArrayList<Uri> nuevasUris = new java.util.ArrayList<>();
                sinConvertir = 0;
                for (java.util.Map.Entry<String, java.util.List<java.io.File>> entry : byId.entrySet()) {
                    String pid = entry.getKey();
                    String key = idToKey.containsKey(pid) ? idToKey.get(pid) : pid;
                    String title = null, album = null;
                    for (java.util.Map.Entry<String, String[]> m : metaById.entrySet()) {
                        if (key.contains(m.getKey()) || m.getKey().equals(pid)) { title = m.getValue()[0]; album = m.getValue()[1]; break; }
                    }
                    if (title == null || title.trim().isEmpty()) title = "Prism_" + pid;
                    String cleanTitle = title.replace("/", "_").replace(":", "_").trim();
                    boolean esAlbumReal = false;
                    if (album != null && !album.trim().isEmpty()) {
                        Integer cnt = albumCounts.get(album.trim().toLowerCase());
                        if (cnt != null && cnt > 1) esAlbumReal = true;
                    }
                    java.io.File destDir = esAlbumReal ? new java.io.File(baseMusic, "Álbumes/" + album.replace("/", "_").replace(":", "_").trim()) : baseMusic;
                    destDir.mkdirs();
                    java.io.File outMp3 = new java.io.File(destDir, cleanTitle + ".mp3");
                    if (outMp3.exists() && outMp3.length() > 10000) continue;
                    java.util.List<java.io.File> parts = entry.getValue();
                    java.util.Collections.sort(parts, (a, b) -> a.getName().compareTo(b.getName()));
                    java.io.File rawTmp = new java.io.File(workTmp, "raw_" + System.currentTimeMillis() + ".m4a");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(rawTmp);
                    byte[] buf = new byte[8192];
                    for (java.io.File pf : parts) { java.io.FileInputStream fis = new java.io.FileInputStream(pf); int r; while ((r = fis.read(buf)) > 0) fos.write(buf, 0, r); fis.close(); }
                    fos.close();
                    convertirMp3Termux(rawTmp, outMp3);
                    if (!outMp3.exists() || outMp3.length() < 10000) { sinConvertir++; rawTmp.renameTo(outMp3); } else rawTmp.delete();
                    android.media.MediaScannerConnection.scanFile(this, new String[]{outMp3.getAbsolutePath()}, null, null);
                    nuevasUris.add(Uri.fromFile(outMp3));
                }
                borrarDirKuli(workTmp);
                runOnUiThread(() -> {
                    if (nuevasUris.isEmpty()) {
                        aviso("No hay canciones nuevas en Prism (o autoriza carpeta Prism).");
                        abrirCanciones();
                    } else {
                        new android.app.AlertDialog.Builder(this)
                            .setTitle("Prism: " + nuevasUris.size() + " canciones nuevas")
                            .setMessage((sinConvertir == 0 ? "Ya están en MP3 (320 kbps) dentro de KULI MUSIC." : "⚠️ " + sinConvertir + " no se pudieron pasar a MP3 (abre Termux una vez, o falta ffmpeg). Se han guardado sin convertir.") + " ¿Qué quieres hacer ahora?")
                            .setPositiveButton("Subir al NAS (clasificar y quitar repetidas)", (d, w) -> subirAutomatico(nuevasUris, null))
                            .setNegativeButton("Ver en Mi música del móvil", (d, w) -> abrirCanciones())
                            .show();
                    }
                });
            } catch (SecurityException se) {
                runOnUiThread(() -> { aviso("Autoriza primero Prism."); autorizarCarpetaPrism(); });
            } catch (Exception e) {
                android.util.Log.e("KULI_MUSIC", "Prism", e);
                runOnUiThread(() -> aviso("Error con Prism: " + e.getMessage()));
            }
        }).start();
    }

    private void autorizarCarpetaPrism() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.putExtra("android.provider.extra.INITIAL_URI", Uri.parse("content://com.prism.music.MTDataFilesProvider/tree/com.prism.music"));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, 9421);
        } catch (Exception e) { aviso("Error selector Prism: " + e.getMessage()); }
    }

    private void abrirBibliotecaKuliDirecta() {
        java.io.File base = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
        base.mkdirs();
        final java.util.ArrayList<java.io.File> lista = new java.util.ArrayList<>();
        recolectarMp3Directo(base, lista);
        java.util.Collections.sort(lista, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        if (lista.isEmpty()) {
            aviso("No hay canciones aún en Music/KULI MUSIC");
            return;
        }
        java.io.File dirAlbumes = new java.io.File(base, "Álbumes");
        final java.util.ArrayList<java.io.File> carpetasAlbum = new java.util.ArrayList<>();
        if (dirAlbumes.exists() && dirAlbumes.listFiles() != null) {
            java.io.File[] sub = dirAlbumes.listFiles(java.io.File::isDirectory);
            if (sub != null) {
                java.util.Arrays.sort(sub, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (java.io.File d : sub) {
                    java.util.ArrayList<java.io.File> tmp = new java.util.ArrayList<>();
                    recolectarMp3Directo(d, tmp);
                    if (!tmp.isEmpty()) carpetasAlbum.add(d);
                }
            }
        }
        java.util.ArrayList<String> nombres = new java.util.ArrayList<>();
        nombres.add("⬆️ SUBIR TODAS AL NAS (clasificar y quitar repetidas) [" + lista.size() + "]");
        for (java.io.File d : carpetasAlbum) {
            java.util.ArrayList<java.io.File> tmp = new java.util.ArrayList<>();
            recolectarMp3Directo(d, tmp);
            nombres.add("💿 ÁLBUM: " + d.getName() + " (" + tmp.size() + " temas)");
        }
        for (java.io.File f : lista) {
            String rel = f.getParentFile() != null && !f.getParentFile().equals(base) ? " [" + f.getParentFile().getName() + "]" : "";
            nombres.add("🎵 " + f.getName().replace(".mp3", "").replace(".m4a", "") + rel);
        }
        final int nAlb = carpetasAlbum.size();
        new android.app.AlertDialog.Builder(this)
                .setTitle("Mi música del móvil (" + nAlb + " álbumes · " + lista.size() + " temas)")
                .setItems(nombres.toArray(new String[0]), (d, i) -> {
                    if (i == 0) {
                        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
                        for (java.io.File f : lista) uris.add(Uri.fromFile(f));
                        subirAutomatico(uris, null);
                    } else if (i <= nAlb) {
                        abrirVistaAlbumConPortada(carpetasAlbum.get(i - 1));
                    } else {
                        java.io.File sel = lista.get(i - 1 - nAlb);
                        reproducir(Uri.fromFile(sel));
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void abrirVistaAlbumConPortada(java.io.File carpetaAlbum) {
        final java.util.ArrayList<java.io.File> pistas = new java.util.ArrayList<>();
        recolectarMp3Directo(carpetaAlbum, pistas);
        java.util.Collections.sort(pistas, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        if (pistas.isEmpty()) return;

        android.widget.LinearLayout lay = new android.widget.LinearLayout(this);
        lay.setOrientation(android.widget.LinearLayout.VERTICAL);
        lay.setPadding(dp(16), dp(12), dp(16), dp(8));

        java.io.File cov = new java.io.File(carpetaAlbum, "cover.jpg");
        if (cov.exists()) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(cov.getAbsolutePath());
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                iv.setMaxHeight(dp(180));
                lay.addView(iv);
            }
        }

        String[] titulos = new String[pistas.size()];
        for (int k = 0; k < pistas.size(); k++) {
            titulos[k] = (k + 1) + ". " + pistas.get(k).getName().replace(".mp3", "").replace(".m4a", "");
        }
        android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titulos));
        lay.addView(lv);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("💿 " + carpetaAlbum.getName())
                .setView(lay)
                .setPositiveButton("▶️ Reproducir álbum", (d, w) -> {
                    colaDj = new java.util.ArrayList<>(pistas);
                    indiceDj = 0;
                    modoDjActivo = true; mantenerDj = true;
                    reproducir(Uri.fromFile(pistas.get(0)));
                })
                .setNegativeButton("Volver", null)
                .create();
        lv.setOnItemClickListener((av, v, pos, id) -> {
            colaDj = new java.util.ArrayList<>(pistas);
            indiceDj = pos;
            modoDjActivo = true; mantenerDj = true;
            reproducir(Uri.fromFile(pistas.get(pos)));
            dlg.dismiss();
        });
        dlg.show();
    }

    private void recolectarMp3Directo(java.io.File dir, java.util.List<java.io.File> out) {
        if (dir == null || !dir.exists()) return;
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) {
            if (f.isDirectory()) recolectarMp3Directo(f, out);
            else if ((f.getName().toLowerCase().endsWith(".mp3") || f.getName().toLowerCase().endsWith(".m4a")) && f.length() > 10000) out.add(f);
        }
    }

    private void mostrarMenuPrism() {
        String[] ops = {"Abrir Prism (descargar canciones)", "Pasar nuevas de Prism a MP3 + Subir al NAS", "Clasificar descargas (Prism · NoTube)", "Autorizar carpeta Prism (1 vez)"};
        new android.app.AlertDialog.Builder(this).setTitle("Prism Music").setItems(ops, (d, i) -> {
            if (i == 0) abrirApp("Prism", "prism");
            else if (i == 1) pasarNuevasDePrismAMp3();
            else if (i == 2) clasificarDescargas();
            else autorizarCarpetaPrism();
        }).setNegativeButton("Cerrar", null).show();
    }

    private String tituloActualNotif = "Kuli Music";
    private boolean modoDjActivo = false; private boolean djCambiando = false; private boolean mantenerDj = false;
    private Button btnDjRef = null;
    private boolean modoDjBloques3 = false;
    private java.util.List<java.io.File> colaDj = new java.util.ArrayList<>();
    private int indiceDj = 0;
    private java.util.List<java.io.File> colaDjListada = null;
    // La cola DJ sólo usa el mezclador de listas cuando listaFavoritas es una copia de ella.
    private boolean djLista() {
        return modoDjActivo && colaDj != null && !colaDj.isEmpty() && colaDj == colaDjListada
            && modoLista && listaFavoritas.size() == colaDj.size();
    }
    private void refrescarEtiquetaDj() {
        try {
            if (btnDjRef == null || !modoDjActivo || colaDj == null || indiceDj < 0 || indiceDj >= colaDj.size()) return;
            java.io.File f = colaDj.get(indiceDj);
            String etq = (modoDjBloques3 && etiquetasDj != null && indiceDj < etiquetasDj.size()) ? etiquetasDj.get(indiceDj) : null;
            btnDjRef.setText(etq != null ? "🎧 " + etq : ("🎧 DJ · " + estiloDj(f)));
        } catch (Throwable ignorar) { }
    }

    private void actualizarNotificacionSegundoPlano(boolean sonando) {
        try {
            String t = cancion != null && cancion.getText() != null && cancion.getText().length() > 0 ? cancion.getText().toString() : tituloActualNotif;
            tituloActualNotif = t;
            String sub = (btnDjRef != null && modoDjActivo) ? btnDjRef.getText().toString() : artistaActual;
            KuliPlaybackService.artistaActual = (sub == null || sub.trim().isEmpty()) ? "Kuli Music" : sub;
            Intent i = new Intent(this, KuliPlaybackService.class);
            i.putExtra("titulo", t); i.putExtra("sonando", sonando);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ignored) {}
    }
    void alternarDesdeNotificacion() {
        alternarReproduccion();
        try { actualizarNotificacionSegundoPlano(reproductor != null && reproductor.isPlaying()); } catch (Exception ignorar) { }
    }
    void siguienteDesdeNotificacion() { saltarGeneral(1); }
    void anteriorDesdeNotificacion() { saltarGeneral(-1); }
    // Siguiente / anterior desde el coche, la notificacion o el movil: funciona en DJ, albumes y cualquier lista.
    void saltarGeneral(int d) {
        if ((modoDjActivo && colaDj != null && !colaDj.isEmpty()) || (modoLista && !listaFavoritas.isEmpty())) { saltar(d); return; }
        try {
            if (uriActual != null && "file".equals(uriActual.getScheme()) && uriActual.getPath() != null) {
                java.io.File act = new java.io.File(uriActual.getPath());
                java.io.File[] hs = act.getParentFile() != null ? act.getParentFile().listFiles() : null;
                if (hs != null) {
                    java.util.ArrayList<java.io.File> pistas = new java.util.ArrayList<>();
                    for (java.io.File x : hs) if (x.isFile() && OrdenarMusica.esAudio(x.getName())) pistas.add(x);
                    java.util.Collections.sort(pistas);
                    int pos = pistas.indexOf(act);
                    if (pistas.size() > 1 && pos >= 0) {
                        listaFavoritas.clear();
                        for (java.io.File x : pistas) listaFavoritas.add(Uri.fromFile(x).toString());
                        modoLista = true; posicionFavorita = pos;
                        saltar(d);
                        return;
                    }
                }
            }
        } catch (Throwable ignorar) { }
        if (d < 0 && reproductor != null) { try { reproductor.seekTo(0); } catch (Exception ignorar) { } return; }
        aviso("Abre una lista (Favoritas, carpeta o DJ) para pasar de canción");
    }
    private void abrirModoCocheDj() {
        java.io.File base = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
        java.util.ArrayList<java.io.File> todas = new java.util.ArrayList<>();
        recolectarMp3Directo(base, todas);
        java.io.File flowDir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "Flow");
        if (flowDir.exists()) recolectarMp3Directo(flowDir, todas);
        // Sin repetidas (mismo titulo) y sin la carpeta «Duplicadas».
        java.util.HashSet<String> vistosDj = new java.util.HashSet<>();
        for (java.util.Iterator<java.io.File> it = todas.iterator(); it.hasNext();) {
            java.io.File fd = it.next();
            String kd = claveNombre(fd.getName());
            if (kd.isEmpty()) kd = fd.getName();
            if (fd.getAbsolutePath().contains("/Duplicadas/") || !vistosDj.add(kd)) it.remove();
        }
        if (todas.isEmpty()) { aviso("No hay canciones en Music/KULI MUSIC"); return; }
        String[] modos = {
            "✨ DJ Elegante · 3 canciones por estilo (" + todas.size() + " temas)",
            "🔀 Mezcla Total Aleatoria",
            "💃 Solo Sesión Flamenkito",
            "🆕 Solo Sesión Novedades 2026",
            "🌴 Solo Sesión Latino / Dance"
        };
        aviso("🎧 Mirando el estilo de " + todas.size() + " canciones… (la primera vez tarda un poco, luego es al instante)");
        final java.util.ArrayList<java.io.File> todasF = todas;
        new Thread(() -> {
            artistaEstilo = null; // V345: se vuelve a aprender por si has movido canciones
            cargarArtistasAprendidos();
            android.content.SharedPreferences cache = getSharedPreferences("kuli_estilos", MODE_PRIVATE);
            android.content.SharedPreferences.Editor ed = cache.edit();
            java.util.HashMap<String, String> mapa = new java.util.HashMap<>();
            for (java.io.File f : todasF) mapa.put(f.getAbsolutePath(), estiloDeArchivo(f, cache, ed));
            ed.apply();
            runOnUiThread(() -> { if (isDestroyed()) return; estilosDj = mapa; mostrarMenuDj(modos, todasF); });
        }).start();
    }

    private void mostrarMenuDj(String[] modos, final java.util.ArrayList<java.io.File> todas) {
        new AlertDialog.Builder(this).setTitle("🎧 Modo DJ · Selección de Sesión").setItems(modos, (d, which) -> {
            java.util.ArrayList<java.io.File> sel = new java.util.ArrayList<>();
            modoDjBloques3 = (which == 0);
            if (which == 0) {
                java.util.LinkedHashMap<String, java.util.ArrayList<java.io.File>> grupos = new java.util.LinkedHashMap<>();
                for (java.io.File f : todas) {
                    String st = estiloDj(f);
                    if (st == null || st.trim().isEmpty()) st = "Variados";
                    grupos.computeIfAbsent(st, k -> new java.util.ArrayList<>()).add(f);
                }
                java.security.SecureRandom rng = new java.security.SecureRandom();
                String histRaw = getSharedPreferences("kuli_historial_dj", MODE_PRIVATE).getString("recientes", "");
                java.util.HashSet<String> yaOidas = new java.util.HashSet<>();
                for (String s : histRaw.split("\n")) if (!s.isEmpty()) yaOidas.add(s);
                java.util.ArrayList<String> estilos = new java.util.ArrayList<>(grupos.keySet());
                java.util.Collections.shuffle(estilos, rng);
                for (String est : estilos) {
                    java.util.ArrayList<java.io.File> g = grupos.get(est);
                    java.util.ArrayList<java.io.File> nuevas = new java.util.ArrayList<>();
                    java.util.ArrayList<java.io.File> vistas = new java.util.ArrayList<>();
                    for (java.io.File x : g) {
                        if (yaOidas.contains(android.net.Uri.fromFile(x).toString())) vistas.add(x);
                        else nuevas.add(x);
                    }
                    java.util.Collections.shuffle(nuevas, rng);
                    java.util.Collections.shuffle(vistas, rng);
                    g.clear();
                    g.addAll(nuevas);
                    g.addAll(vistas);
                }
                etiquetasDj = new java.util.ArrayList<>();
                boolean quedan = true;
                while (quedan) {
                    quedan = false;
                    for (String est : estilos) {
                        java.util.ArrayList<java.io.File> listaEst = grupos.get(est);
                        int toma = Math.min(3, listaEst.size());
                        for (int k = 0; k < toma; k++) {
                            sel.add(listaEst.remove(0));
                            etiquetasDj.add(est + " (" + (k + 1) + "/" + toma + ")");
                            quedan = true;
                        }
                    }
                }
            } else {
                for (java.io.File f : todas) {
                    String st = estiloDj(f);
                    if (which == 1) sel.add(f);
                    else if (which == 2 && "Flamenkito".equals(st)) sel.add(f);
                    else if (which == 3 && "Novedades 2026".equals(st)) sel.add(f);
                    else if (which == 4 && ("Latino".equals(st) || "Dance".equals(st))) sel.add(f);
                }
                if (sel.isEmpty()) sel.addAll(todas);
                java.security.SecureRandom rng2 = new java.security.SecureRandom();
                String histRaw2 = getSharedPreferences("kuli_historial_dj", MODE_PRIVATE).getString("recientes", "");
                java.util.HashSet<String> yaOidas2 = new java.util.HashSet<>();
                for (String s : histRaw2.split("\n")) if (!s.isEmpty()) yaOidas2.add(s);
                java.util.ArrayList<java.io.File> nuevas2 = new java.util.ArrayList<>();
                java.util.ArrayList<java.io.File> vistas2 = new java.util.ArrayList<>();
                for (java.io.File x : sel) {
                    if (yaOidas2.contains(android.net.Uri.fromFile(x).toString())) vistas2.add(x);
                    else nuevas2.add(x);
                }
                java.util.Collections.shuffle(nuevas2, rng2);
                java.util.Collections.shuffle(vistas2, rng2);
                sel.clear();
                sel.addAll(nuevas2);
                sel.addAll(vistas2);
            }
            aleatorio = false;
            repetir = true;
            colaDj = sel;
            indiceDj = 0;
            modoDjActivo = true;
            aviso(modoDjBloques3 ? "🎧 DJ Elegante: 3 temas por estilo iniciado" : "🎧 Sesión DJ iniciada (" + colaDj.size() + " temas)");
            reproducirConFundidoDj(colaDj.get(0));
        }).setNegativeButton("Cancelar", null).show();
    }

    private MediaPlayer djSiguientePlayer = null;
    private boolean djMezclando = false;
    private final Handler djMixHandler = new Handler(Looper.getMainLooper());
    private Runnable djMixWatcher = null;

    private void detenerMezclaDj() {
        djMezclando = false;
        if (djMixWatcher != null) { djMixHandler.removeCallbacks(djMixWatcher); djMixWatcher = null; }
        if (djSiguientePlayer != null) {
            try { djSiguientePlayer.stop(); } catch (Throwable ignored) {}
            try { djSiguientePlayer.release(); } catch (Throwable ignored) {}
            djSiguientePlayer = null;
        }
    }

    private void iniciarMezclaCruzadaDj() {
        if (modoLista) return; // V344: la mezcla la hace el motor de la lista; dos a la vez sonaban mal
        if (djMezclando || !modoDjActivo || colaDj == null || colaDj.size() < 2) return;
        djMezclando = true;
        final int sigIdx = (indiceDj + 1) % colaDj.size();
        final java.io.File sigFile = colaDj.get(sigIdx);
        registrarEscuchada(Uri.fromFile(sigFile).toString());
        try {
            djSiguientePlayer = new MediaPlayer();
            djSiguientePlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
            djSiguientePlayer.setDataSource(this, Uri.fromFile(sigFile));
            djSiguientePlayer.prepare();
            djSiguientePlayer.setVolume(0.0f, 0.0f);
            aplicarNiveladorVolumen(djSiguientePlayer);
            djSiguientePlayer.start();

            final int pasos = 28; // 28 pasos * 250ms = 7 segundos de mezcla real
            final int[] paso = new int[]{0};
            final MediaPlayer viejo = reproductor;
            final MediaPlayer nuevo = djSiguientePlayer;

            Runnable fadeStep = new Runnable() {
                @Override public void run() {
                    if (!modoDjActivo || !djMezclando) return;
                    paso[0]++;
                    float p = Math.min(1.0f, paso[0] / (float) pasos);
                    float volViejo = (float) Math.cos(p * Math.PI * 0.5);
                    float volNuevo = (float) Math.sin(p * Math.PI * 0.5);
                    try { if (viejo != null) viejo.setVolume(volViejo, volViejo); } catch (Throwable ignored) {}
                    try { if (nuevo != null) nuevo.setVolume(volNuevo, volNuevo); } catch (Throwable ignored) {}

                    if (paso[0] < pasos) {
                        djMixHandler.postDelayed(this, 250);
                    } else {
                        try { if (viejo != null) { viejo.stop(); viejo.release(); } } catch (Throwable ignored) {}
                        reproductor = nuevo;
                        djSiguientePlayer = null;
                        djMezclando = false;
                        indiceDj = sigIdx;
                        posicionFavorita = indiceDj;
                        uriActual = Uri.fromFile(sigFile);
                        tituloActualNotif = sigFile.getName().replace(".mp3", "");
                        
                        String estNow = estiloDj(sigFile);
                        if (btnDjRef != null) btnDjRef.setText("🎧 DJ · " + estNow);
                        actualizarNotificacionSegundoPlano(true);
                        programarVigilanteDj();
                    }
                }
            };
            djMixHandler.postDelayed(fadeStep, 250);
        } catch (Throwable e) {
            detenerMezclaDj();
            indiceDj = sigIdx;
            reproducirConFundidoDj(sigFile);
        }
    }

    private void programarVigilanteDj() {
        if (modoLista) return; // V344
        if (djMixWatcher != null) djMixHandler.removeCallbacks(djMixWatcher);
        djMixWatcher = new Runnable() {
            @Override public void run() {
                if (!modoDjActivo || reproductor == null) return;
                try {
                    if (reproductor.isPlaying() && !djMezclando) {
                        int dur = reproductor.getDuration();
                        int pos = reproductor.getCurrentPosition();
                        if (dur > 15000 && (dur - pos) <= 7500) {
                            iniciarMezclaCruzadaDj();
                            return;
                        }
                    }
                } catch (Throwable ignored) {}
                djMixHandler.postDelayed(this, 350);
            }
        };
        djMixHandler.postDelayed(djMixWatcher, 500);
    }

    private void reproducirConFundidoDj(java.io.File f) {
        detenerMezclaDj();
        registrarEscuchada(Uri.fromFile(f).toString());
        if (btnDjRef != null && modoDjActivo) {
            String estNow = estiloDj(f);
            String etq = (modoDjBloques3 && etiquetasDj != null && indiceDj < etiquetasDj.size()) ? etiquetasDj.get(indiceDj) : null;
            btnDjRef.setText(etq != null ? "🎧 " + etq : ("🎧 DJ · " + estNow));
        }
        mantenerDj = true;
        if (colaDj != colaDjListada) {
            listaFavoritas.clear();
            for (java.io.File x : colaDj) listaFavoritas.add(Uri.fromFile(x).toString());
            colaDjListada = colaDj;
        }
        modoLista = true;
        posicionFavorita = indiceDj;
        reproducir(Uri.fromFile(f));
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (modoDjActivo) programarVigilanteDj();
        }, 800);
    }

    private void abrirBuscadorGlobal() {
        final android.widget.EditText caja = new android.widget.EditText(this);
        caja.setHint("Ej: Bordón 4, Quevedo, Paloma Blanca..."); caja.setPadding(40, 30, 40, 30);
        new AlertDialog.Builder(this).setTitle("🔍 Buscar en Móvil y NAS").setView(caja).setPositiveButton("Buscar", (d, w) -> {
            String q = simple(caja.getText().toString()).trim();
            if (!q.isEmpty()) ejecutarBusquedaGlobal(q);
        }).setNegativeButton("Cancelar", null).show();
    }
    private void ejecutarBusquedaGlobal(final String q) {
        aviso("Buscando '" + q + "'...");
        new Thread(() -> {
            final java.util.ArrayList<String> et = new java.util.ArrayList<>();
            final java.util.ArrayList<Object> ds = new java.util.ArrayList<>();
            java.io.File base = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
            java.util.ArrayList<java.io.File> loc = new java.util.ArrayList<>();
            recolectarMp3Directo(base, loc);
            for (java.io.File f : loc) if (simple(f.getName()).contains(q)) { et.add("📱 " + f.getName().replace(".mp3", "")); ds.add(f); }
            try {
                for (String r : new java.util.ArrayList<String>()) {
                    String nom = r.contains("/") ? r.substring(r.lastIndexOf("/") + 1) : r;
                    if (simple(nom).contains(q)) { et.add("☁️ " + nom.replace(".mp3", "")); ds.add(r); }
                }
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                if (et.isEmpty()) { aviso("Sin resultados para '" + q + "'"); return; }
                new AlertDialog.Builder(this).setTitle("Resultados (" + et.size() + ")").setItems(et.toArray(new String[0]), (d, idx) -> {
                    Object o = ds.get(idx);
                    if (o instanceof java.io.File) { modoDjActivo = false; java.io.File f = (java.io.File) o; tituloActualNotif = f.getName().replace(".mp3", ""); reproducir(Uri.fromFile(f)); }
                    else reproducirListaNas(java.util.Collections.singletonList((String) o), 0);
                }).setNegativeButton("Cerrar", null).show();
            });
        }).start();
    }
    private void descargarCarpetaNasAlMovil(final java.util.List<String> rutas) {
        if (rutas == null || rutas.isEmpty()) return;
        aviso("Bajando carpeta al móvil sin duplicar...");
        new Thread(() -> {
            java.io.File destDir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
            destDir.mkdirs();
            java.util.HashSet<String> ya = new java.util.HashSet<>();
            java.util.ArrayList<java.io.File> loc = new java.util.ArrayList<>();
            recolectarMp3Directo(destDir, loc);
            for (java.io.File f : loc) ya.add(claveNombre(f.getName()));
            int bjs = 0;
            for (String r : rutas) {
                String nom = r.contains("/") ? r.substring(r.lastIndexOf("/") + 1) : r;
                if (ya.contains(claveNombre(nom))) continue;
                try { descargarDelNas(r, new java.io.File(destDir, nom)); ya.add(claveNombre(nom)); bjs++; } catch (Throwable ignored) {}
            }
            final int tb = bjs;
            runOnUiThread(() -> aviso("⬇️ " + tb + " canciones nuevas bajadas al móvil"));
        }).start();
    }
    private String detectarEstiloPersonalizado(String nom, String aut, String gen, int anio) {
        String todo = simple(nom + " " + (aut == null ? "" : aut));
        String[] h26 = {"2026", "ni borracho", "la graciosa", "al golpito", "scandic", "choque", "el baifo", "caprichoso", "te dare", "mas cara", "galdar", "superestrella", "pa ti toa", "dardos", "bby wow", "la perla", "largate", "soleao", "yo y tu", "cosita linda", "una vida en llamas", "tus iniciales", "milko freestyle", "dai dai", "las muñequitas", "drop dead", "dama y vagabundo", "no me guillo", "maladie", "una casa en el teide", "punto de partida", "ser artista", "demonias muñecas"};
        if (esNavidad(nom + " " + (aut == null ? "" : aut))) return "Navidad"; // V348
        if (anio == 2026) return "Novedades 2026";
        for (String h : h26) if (todo.contains(simple(h))) return "Novedades 2026";
        String[] fl = {"flamenc", "bordon 4", "asi canta jerez", "paloma blanca", "los del rio", "el mani", "camela", "estopa", "melendi", "el barrio", "niña pastori", "nina pastori", "india martinez", "fondo flamenco", "decai", "rebujitos", "canelita", "galvan real", "daviles", "keen levy", "nyno vargas", "moncho chavea", "omar montes", "maka", "original elias", "demarco", "chambao", "ketama", "paco de lucia", "camaron", "jose merce", "diego el cigala", "estrella morente", "falete", "parrita", "bambino", "chiquetete", "manzanita", "peret", "kiko veneno", "juanito makande", "el canijo", "tomasito", "la hungara", "maria artes", "el tren de los sueños", "son de sol", "las chuches", "los caños", "andy", "lucas", "kiko y shara", "raya real", "siempre asi", "requiebros", "ecos del rocio", "cantores de hispalis", "los marismeños", "sevillanas", "rumba"};
        for (String f : fl) if (todo.contains(simple(f))) return "Flamenkito";
        String[] ba = {"camilo sesto", "julio iglesias", "jose luis perales", "nino bravo", "raphael", "jose jose", "rocio durcal", "luis miguel", "juan gabriel", "mocedades", "dyango", "jose luis rodriguez", "roberto carlos", "umberto tozzi", "nino bravo", "los panchos", "emmanuel", "marco antonio solis", "pimpinela", "chayanne", "ricardo montaner", "alejandro fernandez", "jose feliciano", "dioni", "manuel mijares", "jarabe de palo", "cesar vidal"}; // V352
        for (String b : ba) if (todo.contains(simple(b))) return "Baladas";
        String aprendido = estiloAprendido(aut); // V345: el estilo que ya le diste a otras canciones del mismo artista
        if (aprendido != null) return aprendido;
        String est = estiloDeGenero(gen);
        if (est != null) return est;
        if (anio >= 1980 && anio <= 2009) return anio < 1990 ? "Años 80" : anio < 2000 ? "Años 90" : "Años 2000";
        if (gen != null) {
            String g = gen.trim();
            String gl = g.toLowerCase(java.util.Locale.ROOT);
            if (g.length() >= 3 && g.length() <= 24 && !g.matches("\\(?\\d+\\)?")
                && !gl.equals("other") && !gl.equals("unknown") && !gl.equals("desconocido") && !gl.equals("genre") && !gl.equals("varios"))
                return Character.toUpperCase(g.charAt(0)) + g.substring(1);
        }
        return "Pop";
    }

    // GENERO-KULI: estilo de un archivo leyendo sus etiquetas; se guarda para no repetir el trabajo.
    private java.util.HashMap<String, String> estilosDj = new java.util.HashMap<>();
    private java.util.ArrayList<String> etiquetasDj = new java.util.ArrayList<>();

    // V345: aprende artista -> estilo mirando las carpetas de estilo que ya tienes en KULI MUSIC.
    private volatile java.util.HashMap<String, String> artistaEstilo = null;

    private String estiloAprendido(String artista) {
        java.util.HashMap<String, String> m = artistaEstilo;
        if (m == null || artista == null) return null;
        String k = simple(artista).trim();
        return k.isEmpty() ? null : m.get(k);
    }

    private void cargarArtistasAprendidos() {
        if (artistaEstilo != null) return;
        java.util.HashMap<String, java.util.HashMap<String, Integer>> cuenta = new java.util.HashMap<>();
        try {
            java.io.File base = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "KULI MUSIC");
            java.io.File[] subs = base.listFiles();
            if (subs != null) for (java.io.File d : subs) {
                String n = d.getName();
                if (!d.isDirectory() || n.equals(OrdenarMusica.ALBUMES) || n.equals(OrdenarMusica.SUELTAS) || n.equals(OrdenarMusica.DUPLICADAS)) continue;
                java.util.ArrayList<java.io.File> fs = new java.util.ArrayList<>();
                recolectarMp3Directo(d, fs);
                for (java.io.File f : fs) {
                    String autor = null;
                    MediaMetadataRetriever m = new MediaMetadataRetriever();
                    try { m.setDataSource(f.getAbsolutePath()); autor = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST); }
                    catch (Exception ignorar) { } finally { try { m.release(); } catch (Exception ignorar) { } }
                    if (autor == null) continue;
                    String k = simple(autor).trim();
                    if (k.length() < 3) continue;
                    java.util.HashMap<String, Integer> c = cuenta.get(k);
                    if (c == null) { c = new java.util.HashMap<>(); cuenta.put(k, c); }
                    Integer v = c.get(n);
                    c.put(n, v == null ? 1 : v + 1);
                }
            }
        } catch (Throwable ignorar) { }
        java.util.HashMap<String, String> res = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.HashMap<String, Integer>> e : cuenta.entrySet()) {
            String mejor = null; int max = 0;
            for (java.util.Map.Entry<String, Integer> x : e.getValue().entrySet()) if (x.getValue() > max) { max = x.getValue(); mejor = x.getKey(); }
            if (mejor != null) res.put(e.getKey(), mejor);
        }
        artistaEstilo = res;
    }

    private String estiloDj(java.io.File f) {
        String v = estilosDj.get(f.getAbsolutePath());
        return v != null ? v : detectarEstiloPersonalizado(simple(f.getName()), "", "", 0);
    }

    private String estiloDeArchivo(java.io.File f, android.content.SharedPreferences cache, android.content.SharedPreferences.Editor ed) {
        String k = "v4|" + (artistaEstilo == null ? 0 : artistaEstilo.size()) + "|" + f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        String v = cache.getString(k, null);
        if (v != null) return v;
        String genero = null, autor = null;
        int anio = 0;
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(f.getAbsolutePath());
            genero = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
            autor = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String y = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
            if (y != null && y.length() >= 4) anio = Integer.parseInt(y.substring(0, 4));
        } catch (Exception ignored) { } finally { try { m.release(); } catch (Exception ignored) { } }
        String st = detectarEstiloPersonalizado(simple(f.getName()), autor, genero, anio);
        ed.putString(k, st);
        return st;
    }


    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String act = intent != null ? intent.getAction() : null;
        if (KuliPlaybackService.ACTION_NEXT.equals(act)) {
            saltarGeneral(1);
        } else if (KuliPlaybackService.ACTION_PREV.equals(act)) {
            saltarGeneral(-1);
        } else if (KuliPlaybackService.ACTION_TOGGLE.equals(act)) {
            if (reproductor != null) {
                if (reproductor.isPlaying()) { reproductor.pause(); actualizarNotificacionSegundoPlano(false); }
                else { reproductor.start(); actualizarNotificacionSegundoPlano(true); }
                actualizarProgreso();
            }
        }
    }

    private void buscarCaratulaOnline(final Uri uriObjetivo, final String titulo, final String artista) {
        new Thread(() -> {
            try {
                String q = ((artista != null && !artista.contains("desconocido") ? artista + " " : "") + (titulo != null ? titulo : "")).replaceAll("\\.[^.]+$", "").trim();
                if (q.isEmpty() || q.equalsIgnoreCase("Mi canción")) return;
                java.io.File dirCache = new java.io.File(getCacheDir(), "caratulas");
                dirCache.mkdirs();
                java.io.File fImg = new java.io.File(dirCache, Math.abs(q.toLowerCase().hashCode()) + ".jpg");
                Bitmap bmp = null;
                if (fImg.exists() && fImg.length() > 500) {
                    bmp = BitmapFactory.decodeFile(fImg.getAbsolutePath());
                } else {
                    String urlStr = "https://apple.com" + java.net.URLEncoder.encode(q, "UTF-8") + "&media=music&limit=1";
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                    c.setConnectTimeout(4500); c.setReadTimeout(4500);
                    java.io.InputStream is = c.getInputStream();
                    java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                    String json = s.hasNext() ? s.next() : "";
                    is.close();
                    org.json.JSONObject jo = new org.json.JSONObject(json);
                    org.json.JSONArray arr = jo.optJSONArray("results");
                    if (arr != null && arr.length() > 0) {
                        String artUrl = arr.getJSONObject(0).optString("artworkUrl100", "").replace("100x100bb", "500x500bb");
                        if (!artUrl.isEmpty()) {
                            java.net.HttpURLConnection ci = (java.net.HttpURLConnection) new java.net.URL(artUrl).openConnection();
                            ci.setConnectTimeout(4500); ci.setReadTimeout(4500);
                            try (java.io.InputStream isi = ci.getInputStream()) {
                                BitmapFactory.Options opciones = new BitmapFactory.Options();
                                opciones.inJustDecodeBounds = true;
                                byte[] data = streamToBytes(isi);
                                BitmapFactory.decodeByteArray(data, 0, data.length, opciones);
                                opciones.inSampleSize = calcularSampleSize(opciones, 512, 512);
                                opciones.inJustDecodeBounds = false;
                                bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opciones);
                                if (bmp != null) {
                                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(fImg)) { bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos); }
                                }
                            }
                        }
                    }
                }
                final Bitmap finalBmp = bmp;
                if (finalBmp != null && uriObjetivo != null && uriObjetivo.equals(uriActual)) {
                    runOnUiThread(() -> {
                        portada.clearColorFilter();
                        portada.setPadding(0, 0, 0, 0);
                        portada.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        portada.setImageBitmap(finalBmp);
                        KuliPlaybackService.portadaActual = finalBmp;
                        KuliPlaybackService.artistaActual = artistaActual;
                        actualizarNotificacionSegundoPlano(reproductor != null && reproductor.isPlaying());
                    });
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private byte[] streamToBytes(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) buffer.write(data, 0, nRead);
        return buffer.toByteArray();
    }

    private int calcularSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) inSampleSize *= 2;
        }
        return inSampleSize;
    }

}
