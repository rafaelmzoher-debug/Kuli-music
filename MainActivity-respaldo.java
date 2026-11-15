package com.kuli.music;wc -l app/src/main/java/com/kuli/music/MainActivity.java
wc -l < app/src/main/java/com/kuli/music/MainActivity.java

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private MediaPlayer reproductor;
    private TextView cancion;
    private TextView estado;

    private final int NEGRO = Color.rgb(9, 10, 15);
    private final int DORADO = Color.rgb(232, 190, 92);
    private final int TARJETA = Color.rgb(26, 28, 37);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(NEGRO);
        getWindow().setNavigationBarColor(NEGRO);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(NEGRO);

        LinearLayout pantalla = new LinearLayout(this);
        pantalla.setOrientation(LinearLayout.VERTICAL);
        pantalla.setGravity(Gravity.CENTER);
        pantalla.setPadding(24, 35, 24, 35);

        TextView logo = texto("♫", 65, DORADO);
        pantalla.addView(logo);

        TextView titulo = texto("KULI MUSIC", 35, DORADO);
        titulo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pantalla.addView(titulo);

        TextView subtitulo = texto(
            "TU MÚSICA · TU ESTILO", 13,
            Color.LTGRAY
        );
        pantalla.addView(subtitulo);

        espacio(pantalla, 35);

        TextView disco = texto("♪", 95, DORADO);
        disco.setBackground(fondo(TARJETA, 30));
        disco.setPadding(20, 5, 20, 5);
        pantalla.addView(disco);

        espacio(pantalla, 25);

        cancion = texto("Selecciona tu canción", 20, Color.WHITE);
        cancion.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pantalla.addView(cancion);

        estado = texto("LISTO PARA REPRODUCIR", 12, DORADO);
        pantalla.addView(estado);

        espacio(pantalla, 25);

        LinearLayout controles = new LinearLayout(this);
        controles.setGravity(Gravity.CENTER);

        Button play = boton("▶", true);
        Button pausa = boton("Ⅱ", false);
        Button parar = boton("■", false);

        controles.addView(play,
            new LinearLayout.LayoutParams(85, 65));
        controles.addView(pausa,
            new LinearLayout.LayoutParams(85, 65));
        controles.addView(parar,
            new LinearLayout.LayoutParams(85, 65));

        pantalla.addView(controles);

        play.setOnClickListener(v -> {
            if (reproductor != null) {
                reproductor.start();
                estado.setText("▶ REPRODUCIENDO");
            } else {
                aviso("Primero selecciona una canción");
            }
        });

        pausa.setOnClickListener(v -> {
            if (reproductor != null && reproductor.isPlaying()) {
                reproductor.pause();
                estado.setText("Ⅱ EN PAUSA");
            }
        });

        parar.setOnClickListener(v -> {
            if (reproductor != null) {
                reproductor.pause();
                reproductor.seekTo(0);
                estado.setText("■ DETENIDO");
            }
        });

        espacio(pantalla, 25);

        Button movil = boton("♫  CANCIONES DEL MÓVIL", true);
        pantalla.addView(movil);
        movil.setOnClickListener(v -> abrirCanciones());

        espacio(pantalla, 12);

        Button favoritas = boton("♫  MIS CANCIONES", false);
        pantalla.addView(favoritas);
        favoritas.setOnClickListener(v -> abrirCanciones());

        espacio(pantalla, 12);

        Button buscar = boton("⌕  BUSCAR MÚSICA", false);
        pantalla.addView(buscar);

        buscar.setOnClickListener(v -> {
            Intent i = new Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.jamendo.com/search")
            );
            startActivity(i);
        });

        espacio(pantalla, 35);

        TextView firma = texto("✦  KULI EDITION  ✦", 12, DORADO);
        pantalla.addView(firma);

        scroll.addView(pantalla);
        setContentView(scroll);
    }

    private TextView texto(String contenido, int tamano, int color) {
        TextView t = new TextView(this);
        t.setText(contenido);
        t.setTextSize(tamano);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(5, 8, 5, 8);
        return t;
    }

    private GradientDrawable fondo(int color, int radio) {
        GradientDrawable f = new GradientDrawable();
        f.setColor(color);
        f.setCornerRadius(radio);
        f.setStroke(2, DORADO);
        return f;
    }

    private Button boton(String nombre, boolean principal) {
        Button b = new Button(this);
        b.setText(nombre);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(principal ? NEGRO : DORADO);
        b.setBackgroundTintList(null);
        b.setBackground(fondo(
            principal ? DORADO : TARJETA, 22
        ));
        b.setPadding(12, 12, 12, 12);
        return b;
    }

    private void espacio(LinearLayout l, int altura) {
        View v = new View(this);
        l.addView(v, new LinearLayout.LayoutParams(1, altura));
    }

    private void abrirCanciones() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, 1);
    }

    private void aviso(String mensaje) {
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(
        int requestCode, int resultCode, Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 &&
            resultCode == RESULT_OK &&
            data != null &&
            data.getData() != null) {

            try {
                if (reproductor != null) {
                    reproductor.release();
                }

                Uri uri = data.getData();
                reproductor = MediaPlayer.create(this, uri);

                if (reproductor == null) {
                    throw new Exception("No se pudo abrir");
                }

                String nombre = "Mi canción";

                try (android.database.Cursor cursor =
                    getContentResolver().query(
                        uri, null, null, null, null
                    )) {

                    if (cursor != null && cursor.moveToFirst()) {
                        int columna = cursor.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        );

                        if (columna >= 0) {
                            nombre = cursor.getString(columna);
                        }
                    }
                } catch (Exception ignorado) {}

                cancion.setText(nombre);
                reproductor.start();
                estado.setText("▶ REPRODUCIENDO");

                reproductor.setOnCompletionListener(mp ->
                    estado.setText("✓ REPRODUCCIÓN TERMINADA")
                );

            } catch (Exception e) {
                aviso("Error: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (reproductor != null) {
            reproductor.release();
            reproductor = null;
        }
        super.onDestroy();
    }
}
