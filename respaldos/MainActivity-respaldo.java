package com.kuli.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 100;
    private final int NEGRO = Color.rgb(9, 10, 15);
    private final int DORADO = Color.rgb(232, 190, 92);
    private final int TARJETA = Color.rgb(26, 28, 37);
    private MediaPlayer reproductor;
    private ImageView portada;
    private TextView cancion, artista, estado;
    private Button favorito;
    private Uri uriActual;
    private String tituloActual = "", artistaActual = "";
    private SharedPreferences favoritos;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        favoritos = getSharedPreferences("kuli_favoritos", MODE_PRIVATE);
        getWindow().setStatusBarColor(NEGRO);
        getWindow().setNavigationBarColor(NEGRO);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(NEGRO);
        LinearLayout pantalla = new LinearLayout(this);
        pantalla.setOrientation(LinearLayout.VERTICAL);
        pantalla.setGravity(Gravity.CENTER_HORIZONTAL);
        pantalla.setPadding(dp(20), dp(28), dp(20), dp(28));

        TextView nota = texto("♫", 52, DORADO);
        pantalla.addView(nota);
        TextView titulo = texto("KULI MUSIC", 34, DORADO);
        titulo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pantalla.addView(titulo);
        pantalla.addView(texto("TU MÚSICA · TU ESTILO", 13, Color.LTGRAY));
        espacio(pantalla, 24);

        portada = new ImageView(this);
        portada.setScaleType(ImageView.ScaleType.CENTER_CROP);
        portada.setBackground(fondo(TARJETA, dp(22)));
        portada.setImageResource(android.R.drawable.ic_media_play);
        portada.setColorFilter(DORADO);
        pantalla.addView(portada, new LinearLayout.LayoutParams(dp(255), dp(255)));
        espacio(pantalla, 20);

        cancion = texto("Selecciona tu canción", 22, Color.WHITE);
        cancion.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pantalla.addView(cancion);
        artista = texto("Artista desconocido", 16, Color.LTGRAY);
        pantalla.addView(artista);
        estado = texto("LISTO PARA REPRODUCIR", 12, DORADO);
        pantalla.addView(estado);
        espacio(pantalla, 15);

        LinearLayout controles = new LinearLayout(this);
        controles.setGravity(Gravity.CENTER);
        Button play = boton("▶", true);
        Button pausa = boton("Ⅱ", false);
        Button parar = boton("■", false);
        controles.addView(play, new LinearLayout.LayoutParams(dp(76), dp(55)));
        controles.addView(pausa, new LinearLayout.LayoutParams(dp(76), dp(55)));
        controles.addView(parar, new LinearLayout.LayoutParams(dp(76), dp(55)));
        pantalla.addView(controles);
        play.setOnClickListener(v -> {
            if (reproductor == null) { aviso("Selecciona una canción primero"); return; }
            reproductor.start(); estado.setText("▶ REPRODUCIENDO");
        });
        pausa.setOnClickListener(v -> {
            if (reproductor != null && reproductor.isPlaying()) {
                reproductor.pause(); estado.setText("Ⅱ EN PAUSA");
            }
        });
        parar.setOnClickListener(v -> {
            if (reproductor != null) {
                reproductor.pause(); reproductor.seekTo(0); estado.setText("■ DETENIDO");
            }
        });
        espacio(pantalla, 16);

        Button movil = boton("♫  CANCIONES DEL MÓVIL", true);
        pantalla.addView(movil, ancho());
        movil.setOnClickListener(v -> abrirCanciones());
        espacio(pantalla, 10);
        favorito = boton("♡  GUARDAR EN FAVORITOS", false);
        pantalla.addView(favorito, ancho());
        favorito.setOnClickListener(v -> alternarFavorito());
        espacio(pantalla, 10);
        Button misCanciones = boton("♥  MIS FAVORITAS", false);
        pantalla.addView(misCanciones, ancho());
        misCanciones.setOnClickListener(v -> mostrarFavoritas());
        espacio(pantalla, 10);
        Button buscar = boton("⌕  BUSCAR MÚSICA", false);
        pantalla.addView(buscar, ancho());
        buscar.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.jamendo.com/search"));
            try { startActivity(i); } catch (Exception e) { aviso("No se pudo abrir el navegador"); }
        });
        espacio(pantalla, 22);
        pantalla.addView(texto("✦  KULI EDITION  ✦", 12, DORADO));
        scroll.addView(pantalla);
        setContentView(scroll);
    }

    private LinearLayout.LayoutParams ancho() {
        return new LinearLayout.LayoutParams(-1, dp(65));
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView texto(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(4), dp(6), dp(4), dp(6));
        return t;
    }
    private GradientDrawable fondo(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(radius); d.setStroke(dp(1), DORADO);
        return d;
    }
    private Button boton(String s, boolean principal) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(15); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(principal ? NEGRO : DORADO);
        b.setBackgroundTintList(null);
        b.setBackground(fondo(principal ? DORADO : TARJETA, dp(12)));
        return b;
    }
    private void espacio(LinearLayout l, int n) {
        l.addView(new View(this), new LinearLayout.LayoutParams(1, dp(n)));
    }
    private void aviso(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
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
            reproducir(uri);
        }
    }
    private void reproducir(Uri uri) {
        try {
            if (reproductor != null) { reproductor.release(); reproductor = null; }
            reproductor = MediaPlayer.create(this, uri);
            if (reproductor == null) throw new Exception("No se pudo abrir el audio");
            uriActual = uri;
            tituloActual = "Mi canción"; artistaActual = "Artista desconocido";
            portada.setImageResource(android.R.drawable.ic_media_play);
            portada.setColorFilter(DORADO);
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
                    if (bitmap != null) { portada.clearColorFilter(); portada.setImageBitmap(bitmap); }
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
            reproductor.setOnCompletionListener(mp -> estado.setText("✓ REPRODUCCIÓN TERMINADA"));
            reproductor.start(); estado.setText("▶ REPRODUCIENDO");
        } catch (Exception e) { aviso("Error: " + e.getMessage()); }
    }
    private void actualizarBotonFavorito() {
        boolean guardada = uriActual != null && favoritos.contains(uriActual.toString());
        favorito.setText(guardada ? "♥  QUITAR DE FAVORITOS" : "♡  GUARDAR EN FAVORITOS");
    }
    private void alternarFavorito() {
        if (uriActual == null) { aviso("Selecciona una canción primero"); return; }
        String clave = uriActual.toString();
        if (favoritos.contains(clave)) {
            favoritos.edit().remove(clave).apply(); aviso("Eliminada de favoritas");
        } else {
            favoritos.edit().putString(clave, tituloActual + " — " + artistaActual).apply();
            aviso("Guardada en favoritas");
        }
        actualizarBotonFavorito();
    }
    private void mostrarFavoritas() {
        Map<String, ?> datos = favoritos.getAll();
        if (datos.isEmpty()) { aviso("Aún no tienes canciones favoritas"); return; }
        ArrayList<String> uris = new ArrayList<>(datos.keySet());
        String[] nombres = new String[uris.size()];
        for (int n = 0; n < uris.size(); n++) nombres[n] = String.valueOf(datos.get(uris.get(n)));
        new AlertDialog.Builder(this).setTitle("♥ MIS FAVORITAS")
            .setItems(nombres, (dialog, index) -> reproducir(Uri.parse(uris.get(index))))
            .setNegativeButton("CERRAR", null).show();
    }
    @Override protected void onDestroy() {
        if (reproductor != null) { reproductor.release(); reproductor = null; }
        super.onDestroy();
    }
}
