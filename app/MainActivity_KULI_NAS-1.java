import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android./gradlew assembleDebug > error.txt 2>&1; grep -A 4 -m 1 'error:' error.txtsed -n '355,405p' app/src/main/java/com/kuli/music/MainActivity.java
.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileAttributes;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
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
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO = 100;
    private final int NEGRO = Color.rgb(9, 10, 15);
    private final int DORADO = Color.rgb(232, 190, 92);
    private final int TARJETA = Color.rgb(26, 28, 37);
    private MediaPlayer reproductor;
    private MediaPlayer siguienteReproductor;
    private int indicePreparado = -1;
    private ImageView portada;
    private TextView cancion, artista, estado;
    private Button favorito;
    private Uri uriActual;
    private String tituloActual = "", artistaActual = "";
    private SharedPreferences favoritos;
    private final ArrayList<String> listaFavoritas = new ArrayList<>();
    private int posicionFavorita = -1;
    private boolean modoLista = false, aleatorio = false, repetir = true;

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
        Button ymusic = new Button(this);
        ymusic.setText("🎧 ABRIR YMUSIC");
        ymusic.setOnClickListener(v -> {
            android.content.Intent i = buscarYMusic();
            if (i != null) startActivity(i);
            else android.widget.Toast.makeText(this, "YMusic no encontrado", 0).show();
        });
        pantalla.addView(ymusic);
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
        espacio(pantalla, 10);
        LinearLayout navegacion = new LinearLayout(this);
        navegacion.setGravity(Gravity.CENTER);
        Button anterior = boton("⏮", false);
        Button siguiente = boton("⏭", false);
        Button mezcla = boton("🔀", false);
        Button bucle = boton("🔁", true);
        navegacion.addView(anterior, new LinearLayout.LayoutParams(0, dp(55), 1));
        navegacion.addView(siguiente, new LinearLayout.LayoutParams(0, dp(55), 1));
        navegacion.addView(mezcla, new LinearLayout.LayoutParams(0, dp(55), 1));
        navegacion.addView(bucle, new LinearLayout.LayoutParams(0, dp(55), 1));
        pantalla.addView(navegacion, new LinearLayout.LayoutParams(-1, -2));
        anterior.setOnClickListener(v -> saltar(-1));
        siguiente.setOnClickListener(v -> saltar(1));
        mezcla.setOnClickListener(v -> {
            aleatorio = !aleatorio;
            mezcla.setText(aleatorio ? "🔀✓" : "🔀");
            prepararSiguiente();
            aviso(aleatorio ? "Aleatorio activado" : "Aleatorio desactivado");
        });
        bucle.setOnClickListener(v -> {
            repetir = !repetir;
            bucle.setText(repetir ? "🔁✓" : "🔁");
            prepararSiguiente();
            aviso(repetir ? "Repetir lista activado" : "Repetir lista desactivado");
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
            modoLista = false;
            reproducir(uri);
        }
    }
    private void reproducir(Uri uri) {
        try {
            liberarSiguiente();
            if (reproductor != null) { reproductor.release(); reproductor = null; }
            reproductor = MediaPlayer.create(this, uri);
            if (reproductor == null) throw new Exception("No se pudo abrir el audio");
            uriActual = uri;
            mostrarDatos(uri);
            reproductor.setOnCompletionListener(mp -> alTerminar());
            prepararSiguiente();
            reproductor.start(); estado.setText("▶ REPRODUCIENDO");
        } catch (Exception e) { aviso("Error: " + e.getMessage()); }
    }

    private void mostrarDatos(Uri uri) {
        uriActual = uri;
        try {
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

    // Prepara la siguiente pista y la encadena al reproductor actual.
    private void prepararSiguiente() {
        liberarSiguiente();
        int n = indiceSiguiente();
        if (n < 0 || reproductor == null) return;
        try {
            MediaPlayer proximo = MediaPlayer.create(this, Uri.parse(listaFavoritas.get(n)));
            if (proximo == null) return;
            reproductor.setNextMediaPlayer(proximo);
            siguienteReproductor = proximo;
            indicePreparado = n;
        } catch (Exception ignored) { liberarSiguiente(); }
    }

    private void alTerminar() {
        if (!modoLista || listaFavoritas.isEmpty()) {
            estado.setText("✓ REPRODUCCIÓN TERMINADA");
            return;
        }
        if (siguienteReproductor != null && indicePreparado >= 0) {
            MediaPlayer anterior = reproductor;
            reproductor = siguienteReproductor;
            posicionFavorita = indicePreparado;
            siguienteReproductor = null;
            indicePreparado = -1;
            try { anterior.release(); } catch (Exception ignored) { }
            mostrarDatos(Uri.parse(listaFavoritas.get(posicionFavorita)));
            reproductor.setOnCompletionListener(mp -> alTerminar());
            estado.setText("▶ REPRODUCIENDO");
            prepararSiguiente();
        } else {
            // Si no se pudo precargar, mantiene el avance normal.
            saltar(1);
        }
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
            aviso("Guardada en favoritas. Copiando al NAS…");
            copiarFavoritaAlNas(uriActual);
        }
        actualizarBotonFavorito();
    }
    // Copia una canción seleccionada al NAS solo con WiFi. No borra el original.
    private void copiarFavoritaAlNas(Uri uri) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            aviso("Favorita guardada; la copia al NAS requiere WiFi de casa");
            return;
        }
        String nombre = "cancion.mp3";
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) nombre = c.getString(0);
        } catch (Exception ignored) { }
        if (nombre == null || nombre.trim().isEmpty()) nombre = "cancion.mp3";
        nombre = nombre.replaceAll("[\\/:*?\"<>|]", "_");
        final String archivo = nombre;
        new Thread(() -> {
            try (SMBClient cliente = new SMBClient();
                 Connection conexion = cliente.connect("192.168.1.46")) {
                Session sesion = conexion.authenticate(AuthenticationContext.anonymous());
                try (DiskShare disco = (DiskShare) sesion.connectShare("Public");
                     InputStream origen = getContentResolver().openInputStream(uri)) {
                    if (origen == null) throw new Exception("No se puede leer la canción");
                    try (File destino = disco.openFile("KULI MUSIC\\" + archivo,
                            EnumSet.of(AccessMask.GENERIC_WRITE),
                            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                         OutputStream salida = destino.getOutputStream()) {
                        byte[] buffer = new byte[65536];
                        int leidos;
                        while ((leidos = origen.read(buffer)) != -1) salida.write(buffer, 0, leidos);
                        salida.flush();
                    }
                }
                runOnUiThread(() -> aviso("☁ Copiada al NAS: " + archivo));
            } catch (Exception e) {
                runOnUiThread(() -> aviso("No se copió al NAS: " + e.getMessage()));
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
            .setPositiveButton("▶ REPRODUCIR TODAS", (dialog, which) -> {
                modoLista = true;
                posicionFavorita = 0;
                reproducir(Uri.parse(listaFavoritas.get(0)));
            })
            .setNegativeButton("CERRAR", null).show();
    }
    private void saltar(int direccion) {
        if (!modoLista || listaFavoritas.isEmpty()) {
            aviso("Primero abre MIS FAVORITAS"); return;
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
                return;
            }
            siguienteIndice = 0;
        }
        if (siguienteIndice < 0) siguienteIndice = listaFavoritas.size() - 1;
        posicionFavorita = siguienteIndice;
        reproducir(Uri.parse(listaFavoritas.get(posicionFavorita)));
    }

    @Override protected void onDestroy() {
        liberarSiguiente();
        if (reproductor != null) { reproductor.release(); reproductor = null; }
        super.onDestroy();
    }
    private Intent buscarYMusic() {
        android.content.pm.PackageManager pm = getPackageManager();
        for (android.content.pm.ApplicationInfo app :
                pm.getInstalledApplications(0)) {
            String nombre = pm.getApplicationLabel(app).toString();
            if (nombre.equalsIgnoreCase("YMusic")) {
                return pm.getLaunchIntentForPackage(app.packageName);
            }
        }
        return null;
    }

}
