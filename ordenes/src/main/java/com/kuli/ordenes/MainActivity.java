package com.kuli.ordenes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// KULI ORDENES: escribes el nº de orden, haces la foto del material y la app la guarda
// en una carpeta de esa orden con el nº de orden, el día y la hora escritos encima.
public class MainActivity extends Activity {
    static final String AUTORIDAD = "com.kuli.ordenes.fotos";
    static final int PIDE_FOTO = 1, PIDE_GALERIA = 2, PERMISO = 3;
    static final int LADO_MAX = 3200; // calidad suficiente para leer etiquetas y códigos

    private final int NEGRO = Color.rgb(9, 10, 15);
    private final int DORADO = Color.rgb(232, 190, 92);
    private final int GRIS = Color.rgb(150, 153, 168);
    private final int TARJETA = Color.rgb(24, 26, 36);

    private File raiz;
    private FrameLayout contenido;
    private String ordenAbierta = null;       // null = pantalla de inicio
    private String ultOrden = "", ultNota = "";
    private String ordenPendiente = "", notaPendiente = "";
    private String rutaTemporal = null;
    private Dialog visor;
    private Almacen almacen;
    private boolean enAlmacen = false;

    private final ExecutorService hilos = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(30 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static final class Resumen { String nombre; int fotos; long ultima; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        raiz = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KULI ORDENES");
        almacen = new Almacen(raiz);
        contenido = new FrameLayout(this);
        contenido.setBackgroundColor(NEGRO);
        setContentView(contenido);
        if (b != null) {
            ordenAbierta = b.getString("abierta");
            ultOrden = b.getString("ultOrden", "");
            ultNota = b.getString("ultNota", "");
            ordenPendiente = b.getString("ordenPend", "");
            notaPendiente = b.getString("notaPend", "");
            rutaTemporal = b.getString("temporal");
        }
        if (!tienePermiso()) requestPermissions(new String[]{
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISO);
        if (ordenAbierta != null) mostrarOrden(ordenAbierta); else mostrarInicio();
    }

    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        o.putString("abierta", ordenAbierta);
        o.putString("ultOrden", ultOrden);
        o.putString("ultNota", ultNota);
        o.putString("ordenPend", ordenPendiente);
        o.putString("notaPend", notaPendiente);
        o.putString("temporal", rutaTemporal);
    }

    @Override public void onBackPressed() {
        if (ordenAbierta != null || enAlmacen) mostrarInicio(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        hilos.shutdown();
    }

    // ---------- Permisos ----------
    private boolean tienePermiso() {
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int cod, String[] p, int[] r) {
        super.onRequestPermissionsResult(cod, p, r);
        if (cod == PERMISO) {
            if (tienePermiso()) { aviso("Permiso concedido"); if (ordenAbierta == null) mostrarInicio(); }
            else aviso("Sin el permiso no se pueden guardar las fotos");
        }
    }

    // ---------- Pantalla de inicio ----------
    private void mostrarInicio() {
        ordenAbierta = null;
        enAlmacen = false;
        contenido.removeAllViews();
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(36), dp(20), dp(12));

        TextView t = texto("KULI ÓRDENES", 24, DORADO, true);
        t.setGravity(Gravity.CENTER);
        p.addView(t);
        TextView s = texto("Foto del material con el nº de orden, el día y la hora", 13, GRIS, false);
        s.setGravity(Gravity.CENTER);
        p.addView(s);

        final EditText orden = campo("Nº de orden", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        orden.setText(ultOrden);
        orden.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { ultOrden = e.toString(); }
        });
        p.addView(orden, margen(-1, -2, 18));

        final EditText nota = campo("Material (opcional)", InputType.TYPE_CLASS_TEXT);
        nota.setText(ultNota);
        nota.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { ultNota = e.toString(); }
        });
        p.addView(nota, margen(-1, -2, 8));

        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        Button foto = boton("📷  Hacer foto", true);
        foto.setOnClickListener(v -> pedirFoto(ultOrden, ultNota));
        fila.addView(foto, new LinearLayout.LayoutParams(0, dp(54), 1f));
        Button gal = boton("🖼  Galería", false);
        gal.setOnClickListener(v -> pedirGaleria(ultOrden, ultNota));
        LinearLayout.LayoutParams lg = new LinearLayout.LayoutParams(0, dp(54), 1f);
        lg.leftMargin = dp(10);
        fila.addView(gal, lg);
        p.addView(fila, margen(-1, -2, 12));

        Button alm = boton("📦  Almacén  (" + almacen.total() + ")", false);
        alm.setOnClickListener(v -> mostrarAlmacen());
        p.addView(alm, margen(-1, dp(50), 10));

        p.addView(texto("Órdenes guardadas", 14, GRIS, true), margen(-2, -2, 22));

        final ArrayList<Resumen> ordenes = listarOrdenes();
        if (ordenes.isEmpty()) {
            TextView v = texto("Todavía no hay ninguna.\nEscribe un nº de orden y pulsa «Hacer foto».", 14, GRIS, false);
            v.setGravity(Gravity.CENTER);
            p.addView(v, margen(-1, -2, 30));
        } else {
            ListView lista = new ListView(this);
            lista.setDivider(null);
            lista.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return ordenes.size(); }
                @Override public Object getItem(int i) { return ordenes.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View v, ViewGroup g) {
                    Resumen r = ordenes.get(i);
                    TextView tv = new TextView(MainActivity.this);
                    tv.setBackgroundColor(TARJETA);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(15);
                    tv.setPadding(dp(14), dp(12), dp(14), dp(12));
                    tv.setText("Orden " + r.nombre + "\n" + r.fotos + (r.fotos == 1 ? " foto" : " fotos")
                        + "  ·  última: " + formatoCorto(r.ultima));
                    AbsListView.LayoutParams lp = new AbsListView.LayoutParams(-1, -2);
                    tv.setLayoutParams(lp);
                    LinearLayout caja = new LinearLayout(MainActivity.this);
                    caja.setPadding(0, 0, 0, dp(8));
                    caja.addView(tv, new LinearLayout.LayoutParams(-1, -2));
                    return caja;
                }
            });
            lista.setOnItemClickListener((a, v, i, id) -> mostrarOrden(ordenes.get(i).nombre));
            p.addView(lista, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        contenido.addView(p, new FrameLayout.LayoutParams(-1, -1));
    }

    private ArrayList<Resumen> listarOrdenes() {
        ArrayList<Resumen> r = new ArrayList<>();
        File[] dirs = raiz.listFiles();
        if (dirs == null) return r;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File[] fs = d.listFiles((x, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jpg"));
            File mat = new File(d, "material.txt");
            if ((fs == null || fs.length == 0) && !mat.exists()) continue;
            Resumen x = new Resumen();
            x.nombre = d.getName();
            x.fotos = fs == null ? 0 : fs.length;
            if (fs != null) for (File f : fs) x.ultima = Math.max(x.ultima, fechaDeArchivo(f));
            if (mat.exists()) x.ultima = Math.max(x.ultima, mat.lastModified());
            r.add(x);
        }
        Collections.sort(r, (a, b) -> Long.compare(b.ultima, a.ultima));
        return r;
    }

    // ---------- Detalle de una orden ----------
    private void mostrarOrden(final String nombre) {
        ordenAbierta = nombre;
        enAlmacen = false;
        contenido.removeAllViews();
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(12), dp(30), dp(12), dp(8));

        LinearLayout barra = new LinearLayout(this);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        Button atras = boton("‹", false);
        atras.setTextSize(22);
        atras.setOnClickListener(v -> mostrarInicio());
        barra.addView(atras, new LinearLayout.LayoutParams(dp(56), dp(46)));
        TextView t = texto("Orden " + nombre, 20, Color.WHITE, true);
        t.setPadding(dp(12), 0, 0, 0);
        barra.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        p.addView(barra);

        final EditText nota = campo("Material (opcional)", InputType.TYPE_CLASS_TEXT);
        nota.setText(ultNota);
        nota.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { ultNota = e.toString(); }
        });
        p.addView(nota, margen(-1, -2, 8));

        LinearLayout fila = new LinearLayout(this);
        Button mas = boton("📷  Añadir foto", true);
        mas.setOnClickListener(v -> pedirFoto(nombre, ultNota));
        fila.addView(mas, new LinearLayout.LayoutParams(0, dp(50), 1f));
        Button comp = boton("↗  Compartir", false);
        comp.setOnClickListener(v -> compartir(fotosDe(nombre), nombre));
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0, dp(50), 1f);
        lc.leftMargin = dp(10);
        fila.addView(comp, lc);
        p.addView(fila, margen(-1, -2, 10));

        Button mat = boton("➖  Material usado (sale del almacén)", false);
        mat.setOnClickListener(v -> materialUsado(nombre));
        p.addView(mat, margen(-1, dp(46), 8));
        final ArrayList<String> usado = leerMaterial(nombre);
        if (!usado.isEmpty()) {
            TextView mt = texto("📦 Material usado en esta orden: " + usado.size() + "  (toca para ver)", 13, GRIS, false);
            mt.setPadding(dp(4), dp(8), dp(4), dp(4));
            mt.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Material usado · orden " + nombre)
                .setMessage(android.text.TextUtils.join("\n\n", usado)).setPositiveButton("CERRAR", null).show());
            p.addView(mt);
        }

        final ArrayList<File> fotos = fotosDe(nombre);
        if (fotos.isEmpty()) {
            TextView v = texto("Esta orden no tiene fotos.", 14, GRIS, false);
            v.setGravity(Gravity.CENTER);
            p.addView(v, margen(-1, -2, 30));
        } else {
            GridView rejilla = new GridView(this);
            rejilla.setNumColumns(3);
            rejilla.setHorizontalSpacing(dp(3));
            rejilla.setVerticalSpacing(dp(3));
            final int lado = (getResources().getDisplayMetrics().widthPixels - dp(24) - dp(6)) / 3;
            rejilla.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return fotos.size(); }
                @Override public Object getItem(int i) { return fotos.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View v, ViewGroup g) {
                    FrameLayout caja;
                    ImageView img;
                    TextView pie;
                    if (v == null) {
                        caja = new FrameLayout(MainActivity.this);
                        caja.setBackgroundColor(TARJETA);
                        caja.setLayoutParams(new AbsListView.LayoutParams(lado, lado));
                        img = new ImageView(MainActivity.this);
                        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        caja.addView(img, new FrameLayout.LayoutParams(-1, -1));
                        pie = new TextView(MainActivity.this);
                        pie.setTextColor(Color.WHITE);
                        pie.setTextSize(10);
                        pie.setBackgroundColor(Color.argb(150, 0, 0, 0));
                        pie.setPadding(dp(4), dp(2), dp(4), dp(2));
                        caja.addView(pie, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                        caja.setTag(new View[]{img, pie});
                    } else {
                        caja = (FrameLayout) v;
                    }
                    View[] vs = (View[]) caja.getTag();
                    img = (ImageView) vs[0];
                    pie = (TextView) vs[1];
                    File f = fotos.get(i);
                    pie.setText(formatoCorto(fechaDeArchivo(f)));
                    img.setTag(f.getAbsolutePath());
                    miniatura(f, img);
                    return caja;
                }
            });
            rejilla.setOnItemClickListener((a, v, i, id) -> abrirFoto(fotos, i, nombre));
            p.addView(rejilla, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        contenido.addView(p, new FrameLayout.LayoutParams(-1, -1));
    }

    private ArrayList<File> fotosDe(String orden) {
        File[] fs = new File(raiz, orden).listFiles((x, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jpg"));
        ArrayList<File> r = new ArrayList<>();
        if (fs != null) r.addAll(Arrays.asList(fs));
        Collections.sort(r, (a, b) -> b.getName().compareTo(a.getName())); // la más reciente primero
        return r;
    }

    private void miniatura(final File f, final ImageView img) {
        Bitmap b = cache.get(f.getAbsolutePath());
        if (b != null) { img.setImageBitmap(b); return; }
        img.setImageDrawable(null);
        try {
            hilos.execute(() -> {
                Bitmap m = decodificarArchivo(f, 320);
                if (m == null) return;
                cache.put(f.getAbsolutePath(), m);
                runOnUiThread(() -> { if (f.getAbsolutePath().equals(img.getTag())) img.setImageBitmap(m); });
            });
        } catch (Exception ignorar) { }
    }

    // ---------- Ver una foto ----------
    private void abrirFoto(final ArrayList<File> fotos, final int pos, final String orden) {
        visor = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout caja = new FrameLayout(this);
        caja.setBackgroundColor(Color.BLACK);
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        caja.addView(img, new FrameLayout.LayoutParams(-1, -1));
        final File f = fotos.get(pos);
        hilos.execute(() -> {
            final Bitmap b = decodificarArchivo(f, 2048);
            runOnUiThread(() -> { if (b != null) img.setImageBitmap(b); });
        });
        LinearLayout abajo = new LinearLayout(this);
        abajo.setBackgroundColor(Color.argb(170, 0, 0, 0));
        abajo.setGravity(Gravity.CENTER);
        abajo.setPadding(dp(8), dp(8), dp(8), dp(22));
        TextView info = texto("Orden " + orden + "  ·  " + formatoLargo(fechaDeArchivo(f)), 13, Color.WHITE, false);
        abajo.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        Button comp = boton("↗", false);
        comp.setOnClickListener(v -> compartir(new ArrayList<>(Arrays.asList(f)), orden));
        abajo.addView(comp, new LinearLayout.LayoutParams(dp(54), dp(44)));
        Button borrar = boton("🗑", false);
        borrar.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("¿Borrar esta foto?")
            .setMessage("Se borra del móvil y no se puede recuperar.")
            .setPositiveButton("BORRAR", (d, w) -> {
                if (f.delete()) {
                    MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
                    cache.remove(f.getAbsolutePath());
                    visor.dismiss();
                    if (fotosDe(orden).isEmpty()) mostrarInicio(); else mostrarOrden(orden);
                } else aviso("No se pudo borrar");
            }).setNegativeButton("CANCELAR", null).show());
        abajo.addView(borrar, new LinearLayout.LayoutParams(dp(54), dp(44)));
        caja.addView(abajo, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        visor.setContentView(caja);
        visor.show();
    }

    // ---------- Compartir ----------
    private void compartir(ArrayList<File> fotos, final String orden) {
        if (fotos.isEmpty()) { aviso("No hay fotos que compartir"); return; }
        String[] rutas = new String[fotos.size()];
        for (int i = 0; i < rutas.length; i++) rutas[i] = fotos.get(i).getAbsolutePath();
        final ArrayList<Uri> uris = new ArrayList<>();
        final int[] hechos = {0};
        final int total = rutas.length;
        MediaScannerConnection.scanFile(this, rutas, null, (ruta, uri) -> {
            synchronized (uris) {
                if (uri != null) uris.add(uri);
                hechos[0]++;
                if (hechos[0] == total) runOnUiThread(() -> {
                    if (uris.isEmpty()) { aviso("No se pudieron preparar las fotos"); return; }
                    Intent i = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
                    i.setType("image/*");
                    i.putExtra(Intent.EXTRA_TEXT, "Orden " + orden);
                    if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Compartir fotos de la orden " + orden));
                });
            }
        });
    }

    // ---------- Almacén ----------
    private void escanear(final Consumer<String> alLeer) {
        try {
            GmsBarcodeScanner sc = GmsBarcodeScanning.getClient(this);
            sc.startScan()
                .addOnSuccessListener(b -> {
                    String v = b.getRawValue();
                    if (v == null || v.trim().isEmpty()) aviso("No he podido leer el código");
                    else alLeer.accept(v.trim());
                })
                .addOnFailureListener(e -> {
                    android.util.Log.e("KULI_ORDENES", "Escáner", e);
                    aviso("No se pudo abrir el escáner. Prueba a escribir el código a mano.");
                });
        } catch (Throwable t) {
            aviso("El escáner no está disponible en este móvil");
        }
    }

    private void mostrarAlmacen() {
        enAlmacen = true;
        ordenAbierta = null;
        contenido.removeAllViews();
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14), dp(30), dp(14), dp(8));

        LinearLayout barra = new LinearLayout(this);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        Button atras = boton("‹", false);
        atras.setTextSize(22);
        atras.setOnClickListener(v -> mostrarInicio());
        barra.addView(atras, new LinearLayout.LayoutParams(dp(56), dp(46)));
        TextView t = texto("📦 Almacén", 20, Color.WHITE, true);
        t.setPadding(dp(12), 0, 0, 0);
        barra.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        p.addView(barra);

        final ArrayList<Almacen.Item> items = almacen.lista();
        p.addView(texto(almacen.total() + " unidades · " + items.size() + " materiales", 13, GRIS, false), margen(-2, -2, 6));

        LinearLayout fila = new LinearLayout(this);
        Button esc = boton("📷  Escanear y añadir", true);
        esc.setOnClickListener(v -> escanear(codigo -> sumarEscaneado(codigo)));
        fila.addView(esc, new LinearLayout.LayoutParams(0, dp(52), 1.4f));
        Button man = boton("✎  A mano", false);
        man.setOnClickListener(v -> anadirAMano());
        LinearLayout.LayoutParams lm = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lm.leftMargin = dp(10);
        fila.addView(man, lm);
        p.addView(fila, margen(-1, -2, 10));

        if (items.isEmpty()) {
            TextView v = texto("El almacén está vacío.\nEscanea el código de una caja para añadirla.", 14, GRIS, false);
            v.setGravity(Gravity.CENTER);
            p.addView(v, margen(-1, -2, 30));
        } else {
            ListView lista = new ListView(this);
            lista.setDivider(null);
            lista.setAdapter(new BaseAdapter() {
                @Override public int getCount() { return items.size(); }
                @Override public Object getItem(int i) { return items.get(i); }
                @Override public long getItemId(int i) { return i; }
                @Override public View getView(int i, View v, ViewGroup g) {
                    Almacen.Item it = items.get(i);
                    TextView tv = new TextView(MainActivity.this);
                    tv.setBackgroundColor(TARJETA);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(15);
                    tv.setPadding(dp(14), dp(10), dp(14), dp(10));
                    tv.setText(it.nombre + "   ×" + it.cantidad + "\n" + it.codigo);
                    LinearLayout caja = new LinearLayout(MainActivity.this);
                    caja.setPadding(0, 0, 0, dp(8));
                    caja.addView(tv, new LinearLayout.LayoutParams(-1, -2));
                    return caja;
                }
            });
            lista.setOnItemClickListener((a, v, i, id) -> opcionesDeMaterial(items.get(i)));
            p.addView(lista, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        contenido.addView(p, new FrameLayout.LayoutParams(-1, -1));
    }

    private void sumarEscaneado(final String codigo) {
        Almacen.Item it = almacen.buscar(codigo);
        if (it != null) {
            almacen.anadir(codigo, it.nombre, 1);
            aviso("➕ " + it.nombre + " · ahora hay " + almacen.buscar(codigo).cantidad);
            mostrarAlmacen();
            return;
        }
        final EditText nombre = campo("¿Qué material es? (ej. Acometida 50 m)", InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Material nuevo")
            .setMessage("Código leído:\n" + codigo)
            .setView(nombre)
            .setPositiveButton("AÑADIR", (d, w) -> {
                almacen.anadir(codigo, nombre.getText().toString(), 1);
                aviso("✓ Añadido al almacén");
                mostrarAlmacen();
            }).setNegativeButton("CANCELAR", null).show();
    }

    private void anadirAMano() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText nombre = campo("Material", InputType.TYPE_CLASS_TEXT);
        final EditText codigo = campo("Código (opcional)", InputType.TYPE_CLASS_TEXT);
        final EditText cant = campo("Cantidad", InputType.TYPE_CLASS_NUMBER);
        cant.setText("1");
        c.addView(nombre, margen(-1, -2, 4));
        c.addView(codigo, margen(-1, -2, 8));
        c.addView(cant, margen(-1, -2, 8));
        new AlertDialog.Builder(this).setTitle("Añadir a mano").setView(c)
            .setPositiveButton("AÑADIR", (d, w) -> {
                String n = nombre.getText().toString().trim();
                String cod = codigo.getText().toString().trim();
                int q = 1;
                try { q = Math.max(1, Integer.parseInt(cant.getText().toString().trim())); } catch (Exception ignorar) { }
                if (n.isEmpty() && cod.isEmpty()) { aviso("Escribe al menos el nombre"); return; }
                if (cod.isEmpty()) cod = "MANUAL-" + n.toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
                almacen.anadir(cod, n, q);
                mostrarAlmacen();
            }).setNegativeButton("CANCELAR", null).show();
    }

    private void opcionesDeMaterial(final Almacen.Item it) {
        new AlertDialog.Builder(this).setTitle(it.nombre + "  ×" + it.cantidad)
            .setItems(new String[]{"➕ Sumar 1", "➖ Restar 1", "✎ Cambiar nombre", "🗑 Quitar del almacén"}, (d, i) -> {
                if (i == 0) { almacen.anadir(it.codigo, it.nombre, 1); mostrarAlmacen(); }
                else if (i == 1) { almacen.gastar(it.codigo); mostrarAlmacen(); }
                else if (i == 2) {
                    final EditText n = campo("Nombre", InputType.TYPE_CLASS_TEXT);
                    n.setText(it.nombre);
                    new AlertDialog.Builder(this).setTitle("Cambiar nombre").setView(n)
                        .setPositiveButton("GUARDAR", (d2, w) -> { almacen.renombrar(it.codigo, n.getText().toString()); mostrarAlmacen(); })
                        .setNegativeButton("CANCELAR", null).show();
                } else {
                    new AlertDialog.Builder(this).setTitle("¿Quitar «" + it.nombre + "» del almacén?")
                        .setPositiveButton("QUITAR", (d2, w) -> { almacen.quitar(it.codigo); mostrarAlmacen(); })
                        .setNegativeButton("CANCELAR", null).show();
                }
            }).setNegativeButton("CERRAR", null).show();
    }

    // ---------- Material usado en una orden ----------
    private void materialUsado(final String orden) {
        new AlertDialog.Builder(this).setTitle("Material usado en la orden " + orden)
            .setItems(new String[]{"📷 Escanear el código", "📋 Elegir del almacén"}, (d, i) -> {
                if (i == 0) escanear(c -> gastarCodigo(orden, c));
                else elegirDelAlmacen(orden);
            }).setNegativeButton("CANCELAR", null).show();
    }

    private void elegirDelAlmacen(final String orden) {
        final ArrayList<Almacen.Item> items = almacen.lista();
        if (items.isEmpty()) { aviso("El almacén está vacío"); return; }
        String[] nombres = new String[items.size()];
        for (int i = 0; i < nombres.length; i++) nombres[i] = items.get(i).nombre + "  ×" + items.get(i).cantidad;
        new AlertDialog.Builder(this).setTitle("¿Qué material has usado?")
            .setItems(nombres, (d, i) -> gastarCodigo(orden, items.get(i).codigo))
            .setNegativeButton("CANCELAR", null).show();
    }

    private void gastarCodigo(final String orden, final String codigo) {
        Almacen.Item it = almacen.buscar(codigo);
        if (it == null) {
            new AlertDialog.Builder(this).setTitle("No está en el almacén")
                .setMessage("El código " + codigo + " no estaba apuntado en el almacén.\n\n¿Lo apunto igualmente como usado en esta orden?")
                .setPositiveButton("APUNTAR", (d, w) -> { anotarMaterial(orden, codigo, "(no estaba en el almacén)"); mostrarOrden(orden); })
                .setNegativeButton("CANCELAR", null).show();
            return;
        }
        String nombre = it.nombre;
        int quedan = almacen.gastar(codigo);
        anotarMaterial(orden, codigo, nombre);
        aviso("✓ " + nombre + " usado · quedan " + quedan);
        mostrarOrden(orden);
    }

    private void anotarMaterial(String orden, String codigo, String nombre) {
        File dir = new File(raiz, limpiar(orden));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, "material.txt"), true)) {
            o.write((formatoLargo(System.currentTimeMillis()) + " | " + nombre + " | " + codigo + "\n").getBytes("UTF-8"));
        } catch (Exception e) { aviso("No se pudo apuntar el material"); }
    }

    private ArrayList<String> leerMaterial(String orden) {
        ArrayList<String> r = new ArrayList<>();
        File f = new File(new File(raiz, limpiar(orden)), "material.txt");
        if (!f.exists()) return r;
        try (InputStream in = new java.io.FileInputStream(f)) {
            String txt = new String(leerTodo(in), "UTF-8");
            for (String l : txt.split("\n")) if (!l.trim().isEmpty()) r.add(l.trim());
        } catch (Throwable ignorar) { }
        return r;
    }

    // ---------- Hacer o elegir foto ----------
    private boolean datosValidos(String orden) {
        if (orden == null || orden.trim().isEmpty()) { aviso("Escribe primero el nº de orden"); return false; }
        if (!tienePermiso()) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISO);
            return false;
        }
        return true;
    }

    private void pedirFoto(String orden, String nota) {
        if (!datosValidos(orden)) return;
        ordenPendiente = orden.trim();
        notaPendiente = nota == null ? "" : nota.trim();
        String nombre = "cam_" + System.currentTimeMillis() + ".jpg";
        File dir = new File(getCacheDir(), "tmp");
        dir.mkdirs();
        rutaTemporal = new File(dir, nombre).getAbsolutePath();
        Uri uri = Uri.parse("content://" + AUTORIDAD + "/tmp/" + nombre);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        i.setClipData(ClipData.newRawUri("foto", uri));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (i.resolveActivity(getPackageManager()) == null) { aviso("No encuentro la cámara"); return; }
        startActivityForResult(i, PIDE_FOTO);
    }

    private void pedirGaleria(String orden, String nota) {
        if (!datosValidos(orden)) return;
        ordenPendiente = orden.trim();
        notaPendiente = nota == null ? "" : nota.trim();
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Elige la foto"), PIDE_GALERIA);
    }

    @Override protected void onActivityResult(int cod, int res, Intent datos) {
        super.onActivityResult(cod, res, datos);
        if (res != RESULT_OK) return;
        if (cod == PIDE_FOTO && rutaTemporal != null) {
            final File tmp = new File(rutaTemporal);
            if (!tmp.exists() || tmp.length() == 0) { aviso("La cámara no devolvió la foto"); return; }
            guardar(null, tmp, false);
        } else if (cod == PIDE_GALERIA && datos != null && datos.getData() != null) {
            guardar(datos.getData(), null, true);
        }
    }

    private void guardar(final Uri uri, final File archivo, final boolean fechaDeLaFoto) {
        final String orden = ordenPendiente, nota = notaPendiente;
        aviso("Guardando…");
        hilos.execute(() -> {
            String error = null;
            File salida = null;
            long fecha = System.currentTimeMillis();
            try {
                InputStream in = archivo != null ? new java.io.FileInputStream(archivo) : getContentResolver().openInputStream(uri);
                byte[] datos;
                try { datos = leerTodo(in); } finally { in.close(); }
                if (fechaDeLaFoto) fecha = fechaExif(datos, fecha);
                Bitmap original = decodificar(datos, LADO_MAX);
                if (original == null) throw new Exception("No se pudo leer la imagen");
                Bitmap sellada = estampar(original, orden, nota, fecha);
                File dir = new File(raiz, limpiar(orden));
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("No se pudo crear la carpeta");
                String base = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(fecha));
                salida = new File(dir, base + ".jpg");
                int n = 1;
                while (salida.exists()) salida = new File(dir, base + "_" + (n++) + ".jpg");
                try (FileOutputStream o = new FileOutputStream(salida)) { sellada.compress(Bitmap.CompressFormat.JPEG, 92, o); }
                MediaScannerConnection.scanFile(this, new String[]{salida.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            } finally {
                if (archivo != null) archivo.delete();
            }
            final String err = error;
            final long fFecha = fecha;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (err != null) { aviso("No se pudo guardar: " + err); return; }
                aviso("✓ Guardada en la orden " + limpiar(orden) + " · " + formatoLargo(fFecha));
                if (ordenAbierta != null) mostrarOrden(ordenAbierta); else mostrarInicio();
            });
        });
    }

    // ---------- Imagen ----------
    private Bitmap estampar(Bitmap src, String orden, String nota, long fecha) {
        Bitmap b = src.copy(Bitmap.Config.ARGB_8888, true);
        if (b == null) return src;
        Canvas c = new Canvas(b);
        float w = b.getWidth();
        float tam = Math.max(22f, w / 26f);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTextSize(tam);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        ArrayList<String> lineas = new ArrayList<>();
        lineas.add("ORDEN " + orden);
        lineas.add(formatoLargo(fecha));
        if (nota != null && !nota.isEmpty()) lineas.add(nota);
        float alto = lineas.size() * tam * 1.25f + tam * 0.6f;
        Paint fondo = new Paint();
        fondo.setColor(Color.argb(175, 0, 0, 0));
        c.drawRect(0, b.getHeight() - alto, w, b.getHeight(), fondo);
        float y = b.getHeight() - alto + tam * 1.1f;
        for (String l : lineas) {
            c.drawText(ajustar(l, t, w - tam), tam * 0.5f, y, t);
            y += tam * 1.25f;
        }
        return b;
    }

    private String ajustar(String s, Paint p, float max) {
        while (s.length() > 1 && p.measureText(s) > max) s = s.substring(0, s.length() - 1);
        return s;
    }

    private Bitmap decodificarArchivo(File f, int maxLado) {
        try (InputStream in = new java.io.FileInputStream(f)) { return decodificar(leerTodo(in), maxLado); }
        catch (Throwable t) { return null; }
    }

    private Bitmap decodificar(byte[] datos, int maxLado) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(datos, 0, datos.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while (o.outWidth / (s * 2) >= maxLado || o.outHeight / (s * 2) >= maxLado) s *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeByteArray(datos, 0, datos.length, o);
        if (b == null) return null;
        int giro = 0;
        try {
            ExifInterface ex = new ExifInterface(new ByteArrayInputStream(datos));
            int or = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (or == 6) giro = 90; else if (or == 3) giro = 180; else if (or == 8) giro = 270;
        } catch (Exception ignorar) { }
        if (giro != 0) {
            Matrix m = new Matrix();
            m.postRotate(giro);
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        return b;
    }

    private long fechaExif(byte[] datos, long porDefecto) {
        try {
            ExifInterface ex = new ExifInterface(new ByteArrayInputStream(datos));
            String d = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (d == null) d = ex.getAttribute(ExifInterface.TAG_DATETIME);
            if (d != null) {
                Date f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(d);
                if (f != null) return f.getTime();
            }
        } catch (Exception ignorar) { }
        return porDefecto;
    }

    private byte[] leerTodo(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
        return o.toByteArray();
    }

    // ---------- Fechas y nombres ----------
    private String limpiar(String orden) {
        String s = orden.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.isEmpty() ? "SIN_NUMERO" : s;
    }

    // La fecha va en el nombre del archivo (yyyyMMdd_HHmmss)
    private long fechaDeArchivo(File f) {
        try {
            Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(f.getName().substring(0, 15));
            if (d != null) return d.getTime();
        } catch (Exception ignorar) { }
        return f.lastModified();
    }

    private String formatoLargo(long ms) {
        return new SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", new Locale("es", "ES")).format(new Date(ms));
    }

    private String formatoCorto(long ms) {
        return new SimpleDateFormat("dd/MM HH:mm", new Locale("es", "ES")).format(new Date(ms));
    }

    // ---------- Ayudas de pantalla ----------
    private TextView texto(String s, int sp, int color, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (negrita) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText campo(String pista, int tipo) {
        EditText e = new EditText(this);
        e.setHint(pista);
        e.setInputType(tipo);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(GRIS);
        e.setBackgroundColor(TARJETA);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    private Button boton(String s, boolean principal) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(principal ? Color.rgb(20, 20, 20) : DORADO);
        b.setBackgroundColor(principal ? DORADO : TARJETA);
        return b;
    }

    private LinearLayout.LayoutParams margen(int w, int h, int arriba) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(arriba);
        return p;
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private void aviso(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
