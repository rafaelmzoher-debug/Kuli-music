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
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
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

    private final int NEGRO = Color.rgb(6, 42, 92);              // fondo azul marino
    private final int DORADO = Color.rgb(255, 255, 255);         // acento principal: blanco
    private final int ORO = Color.rgb(255, 205, 80);             // detalles dorados
    private final int GRIS = Color.rgb(170, 200, 235);
    private final int TARJETA = Color.rgb(12, 74, 146);

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

    static final class Resumen { String nombre; int fotos; int mat; long ultima; boolean cerrada; String datos = ""; double metros; }
    private String filtro = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        File nueva = new File(Environment.getExternalStorageDirectory(), "Almacen de Rafa");
        File vieja = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KULI ORDENES");
        if (!nueva.exists() && vieja.exists()) vieja.renameTo(nueva); // pasa lo que ya tenías a la carpeta nueva
        raiz = (nueva.exists() || nueva.mkdirs()) ? nueva : vieja;
        almacen = new Almacen(raiz);
        Almacen.alFallar = msg -> runOnUiThread(() -> avisoLargo(msg)); // V223
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
        android.content.SharedPreferences spU = getSharedPreferences("ordenes", MODE_PRIVATE);
        boolean pideUbic = !spU.getBoolean("pidio_ubic", false)
            && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED;
        if (!tienePermiso() || pideUbic) {
            if (pideUbic) spU.edit().putBoolean("pidio_ubic", true).apply();
            requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.ACCESS_FINE_LOCATION}, PERMISO); // V223: la ubicación se pide una vez, para poner la calle en la foto
        }
        if (ordenAbierta != null) mostrarOrden(ordenAbierta); else mostrarInicio();
        copiaDiaria();
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

    @Override protected void onPause() {
        super.onPause();
        try {
            android.content.SharedPreferences sp = getSharedPreferences("ordenes", MODE_PRIVATE);
            long ahora = System.currentTimeMillis();
            if (ahora - sp.getLong("ult_resp", 0) > 180000) {
                sp.edit().putLong("ult_resp", ahora).apply();
                respaldoNas(false);
            }
        } catch (Exception ignorar) { }
    }

    // Una vez al día (si ha pasado más de 20 h) copia sola al NAS al abrir la app, si hay wifi.
    private void purgarPapelera() {
        try {
            File[] fs = new File(raiz, "Papelera").listFiles();
            if (fs == null) return;
            long limite = System.currentTimeMillis() - 7L * 24 * 3600000L;
            for (File f : fs) if (f.isFile() && f.lastModified() < limite) f.delete();
        } catch (Throwable ignorar) { }
    }

    private void copiaDiaria() {
        purgarPapelera();
        final android.content.SharedPreferences sp = getSharedPreferences("ordenes", MODE_PRIVATE);
        if (System.currentTimeMillis() - sp.getLong("nas_ok", 0) < 20L * 3600000L) return;
        hilos.execute(() -> {
            try {
                if (!Respaldo.hayWifi(this)) return;
                Respaldo.subir(this, raiz);
                sp.edit().putLong("nas_ok", System.currentTimeMillis()).apply();
            } catch (Throwable t) { android.util.Log.e("KULI_ORDENES", "Copia diaria", t); }
        });
    }

    private void respaldoNas(final boolean manual) {
        if (manual) aviso("Copiando al NAS…");
        hilos.execute(() -> {
            try {
                if (!Respaldo.hayWifi(this)) throw new java.io.IOException("No hay wifi");
                final int n = Respaldo.subir(this, raiz);
                getSharedPreferences("ordenes", MODE_PRIVATE).edit().putLong("nas_ok", System.currentTimeMillis()).apply();
                if (manual) runOnUiThread(() -> aviso("✓ Copia hecha en el NAS (" + n + " archivos nuevos)"));
            } catch (Throwable t) {
                android.util.Log.e("KULI_ORDENES", "Copia al NAS", t);
                if (manual) runOnUiThread(() -> aviso("No se pudo copiar al NAS. ¿Estás en el wifi de casa?"));
            }
        });
    }

    private void cargarAlbaran() {
        final File dir = new File(raiz, "Albaranes");
        dir.mkdirs();
        File hoy = new File(dir, "albaran_1925340.txt");
        if (!hoy.exists()) {
            try (FileOutputStream o = new FileOutputStream(hoy)) { o.write(Albaran.comoTexto().getBytes("UTF-8")); }
            catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Creando albarán", e); }
        }
        final File[] fs = dir.listFiles((x, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (fs == null || fs.length == 0) {
            new AlertDialog.Builder(this).setTitle("No hay albaranes")
                .setMessage("Guarda el archivo del albarán en la carpeta «Almacen de Rafa / Albaranes» del móvil y vuelve a pulsar.")
                .setPositiveButton("OK", null).show();
            return;
        }
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] nombres = new String[fs.length];
        for (int i = 0; i < fs.length; i++) nombres[i] = fs[i].getName();
        new AlertDialog.Builder(this).setTitle("¿Qué albarán cargo?")
            .setItems(nombres, (d, i) -> confirmarAlbaran(fs[i]))
            .setPositiveButton("📄 DESDE PDF", (d, w) -> elegirPdf())
            .setNeutralButton("📋 PEGAR TEXTO", (d, w) -> pegarAlbaran(dir))
            .setNegativeButton("CANCELAR", null).show();
    }

    // Sin pasar archivos: se copia el texto del albarán o de los gastos desde el chat y se pega aquí.
    private void pegarAlbaran(final File dir) {
        final EditText campo = new EditText(this);
        campo.setHint("Pega aquí el texto (empieza por «ALBARAN;…»)");
        campo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        campo.setMinLines(6);
        campo.setGravity(Gravity.TOP);
        campo.setTextSize(13);
        new AlertDialog.Builder(this).setTitle("Pegar texto del albarán").setView(campo)
            .setPositiveButton("CARGAR", (d, w) -> {
                String t = campo.getText().toString().trim();
                if (t.isEmpty()) { aviso("No has pegado nada"); return; }
                File f = new File(dir, "pegado_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".txt");
                try (FileOutputStream o = new FileOutputStream(f)) { o.write((t + "\n").getBytes("UTF-8")); }
                catch (Exception e) { aviso("No he podido guardar el texto"); return; }
                hacerAlbaran(f, false);
            })
            .setNegativeButton("CANCELAR", null).show();
    }

    private void confirmarAlbaran(final File f) {
        new AlertDialog.Builder(this).setTitle("Cargar " + f.getName())
            .setMessage("Se suma a lo que ya tienes en el almacén. Los equipos ya contados no se repiten.")
            .setPositiveButton("CARGAR", (d, w) -> hacerAlbaran(f, false))
            .setNeutralButton("VACIAR Y CARGAR", (d, w) -> hacerAlbaran(f, true))
            .setNegativeButton("CANCELAR", null).show();
    }

    private java.util.TreeMap<String, Integer> fotoAlmacen() {
        java.util.TreeMap<String, Integer> m = new java.util.TreeMap<>();
        for (Almacen.Item it : almacen.lista()) m.put(it.nombre + "|" + it.codigo, it.cantidad);
        return m;
    }

    // ---------- Fotos a salvo: si las borras de la galería del móvil, se recuperan ----------
    private boolean respaldoFotosHecho = false;

    private void copiarArchivo(File a, File b) throws Exception {
        b.getParentFile().mkdirs();
        try (InputStream in = new java.io.FileInputStream(a); FileOutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
        }
    }

    private File respaldoDe(File foto) {
        return new File(new File(new File(raiz, ".respaldo_fotos"), foto.getParentFile().getName()), foto.getName());
    }

    private void sincronizarFotos() {
        if (respaldoFotosHecho || !tienePermiso()) return;
        respaldoFotosHecho = true;
        hilos.execute(() -> {
            int restauradas = 0;
            try {
                File rb = new File(raiz, ".respaldo_fotos");
                rb.mkdirs();
                new File(rb, ".nomedia").createNewFile();
                File[] ords = rb.listFiles();
                if (ords != null) for (File od : ords) {
                    if (!od.isDirectory()) continue;
                    File[] fs = od.listFiles();
                    if (fs == null) continue;
                    for (File b : fs) {
                        File orig = new File(new File(raiz, od.getName()), b.getName());
                        if (!orig.exists()) { copiarArchivo(b, orig); restauradas++; }
                    }
                }
                File[] dirs = raiz.listFiles();
                if (dirs != null) for (File d : dirs) {
                    if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                    File[] fs = d.listFiles((x, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jpg"));
                    if (fs == null) continue;
                    for (File f : fs) { File b = respaldoDe(f); if (!b.exists()) copiarArchivo(f, b); }
                }
            } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Respaldo de fotos", e); }
            final int n = restauradas;
            if (n > 0) runOnUiThread(() -> avisoLargo("↩ He recuperado " + n + (n == 1 ? " foto" : " fotos") + " que se habían borrado del móvil"));
        });
    }

    private void hacerAlbaran(File f, boolean vaciar) { hacerAlbaran(f, vaciar, null); }

    // ---------- Gastos pendientes de reponer ----------
    private File fGastos() { return new File(raiz, "gastos_pendientes.txt"); }

    private ArrayList<String> leerGastos() {
        ArrayList<String> r = new ArrayList<>();
        try {
            if (!fGastos().exists()) return r;
            try (InputStream in = new java.io.FileInputStream(fGastos())) {
                for (String l : new String(leerTodo(in), "UTF-8").split("\\r?\\n")) if (!l.trim().isEmpty()) r.add(l.trim());
            }
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Leyendo gastos", e); }
        return r;
    }

    private void escribirGastos(ArrayList<String> ls) {
        try (FileOutputStream o = new FileOutputStream(fGastos())) {
            o.write((android.text.TextUtils.join("\n", ls) + (ls.isEmpty() ? "" : "\n")).getBytes("UTF-8"));
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Guardando gastos", e); }
    }

    private void apuntarGasto(String codigo, String orden) {
        ArrayList<String> ls = leerGastos();
        ls.add(codigo + ";" + orden + ";" + new SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(new Date()));
        escribirGastos(ls);
    }

    private void quitarGasto(String codigo, String orden) {
        ArrayList<String> ls = leerGastos();
        for (int i = ls.size() - 1; i >= 0; i--) {
            String[] p = ls.get(i).split(";");
            if (p.length > 1 && p[0].equals(codigo) && p[1].equals(orden)) { ls.remove(i); break; }
        }
        escribirGastos(ls);
    }

    // Compara lo que has gastado en tus órdenes con lo que trae el albarán, y apunta lo que sigue pendiente.
    private String comprobarReposicion(AlbaranPdf.Resultado r) {
        ArrayList<String> ls = leerGastos();
        java.util.TreeMap<String, Integer> g = new java.util.TreeMap<>();
        for (String l : ls) { String c = l.split(";")[0]; Integer n = g.get(c); g.put(c, n == null ? 1 : n + 1); }
        java.util.TreeSet<String> codigos = new java.util.TreeSet<>(g.keySet());
        codigos.addAll(r.entregado.keySet());
        StringBuilder sb = new StringBuilder();
        int faltan = 0;
        for (String c : codigos) {
            int gas = g.containsKey(c) ? g.get(c) : 0, rep = r.entregado.containsKey(c) ? r.entregado.get(c) : 0;
            String nom = r.nombres.containsKey(c) ? r.nombres.get(c) : (almacen.buscar(c) != null ? almacen.buscar(c).nombre : c);
            if (gas == 0) { sb.append("ℹ ").append(nom).append(": llegan ").append(rep).append(" (sin gasto apuntado)\n"); continue; }
            if (rep >= gas) sb.append("✓ ").append(nom).append(": gastado ").append(gas).append(" · repuesto ").append(rep)
                .append(rep > gas ? " (sobran " + (rep - gas) + ")" : "").append("\n");
            else { sb.append("⚠ ").append(nom).append(": gastado ").append(gas).append(" · repuesto ").append(rep).append(" → FALTAN ").append(gas - rep).append("\n"); faltan += gas - rep; }
            int quitar = Math.min(gas, rep);
            for (int i = ls.size() - 1; i >= 0 && quitar > 0; i--) if (ls.get(i).split(";")[0].equals(c)) { ls.remove(i); quitar--; }
        }
        escribirGastos(ls);
        return "\n\nREPOSICIÓN (lo gastado en tus órdenes contra este albarán):\n" + sb
            + (faltan == 0 ? "\nTodo lo apuntado como gastado está repuesto." : "\nTe faltan " + faltan + " por reponer.");
    }

    // ---------- Historial de albaranes cargados (día y hora) ----------
    private File fHistorial() { return new File(raiz, "Historial_albaranes.txt"); }

    private String datoDe(File f, String clave) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            for (String l : new String(leerTodo(in), "UTF-8").split("\\r?\\n")) {
                l = l.trim();
                if (l.startsWith(clave + ";")) return l.substring(clave.length() + 1).trim();
            }
        } catch (Exception ignorar) { }
        return "";
    }

    private void apuntarHistorial(File f, int antes, int despues, boolean pdf) {
        try {
            String id = datoDe(f, "ALBARAN");
            String fecha = datoDe(f, "FECHA");
            String linea = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(new Date())
                + " | " + (id.isEmpty() ? f.getName() : id)
                + (fecha.isEmpty() ? "" : " | albarán del " + fecha)
                + " | " + (despues >= antes ? "+" : "") + (despues - antes) + " unidades"
                + " | " + (pdf ? "PDF" : "texto") + "\n";
            try (FileOutputStream o = new FileOutputStream(fHistorial(), true)) { o.write(linea.getBytes("UTF-8")); }
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Historial", e); }
    }

    private void mostrarHistorial() {
        ArrayList<String> ls = new ArrayList<>();
        try {
            if (fHistorial().exists()) try (InputStream in = new java.io.FileInputStream(fHistorial())) {
                for (String l : new String(leerTodo(in), "UTF-8").split("\\r?\\n")) if (!l.trim().isEmpty()) ls.add(l.trim());
            }
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Leyendo historial", e); }
        Collections.reverse(ls);
        new AlertDialog.Builder(this).setTitle("🕒 Albaranes cargados")
            .setMessage(ls.isEmpty() ? "Todavía no hay ninguno apuntado. Se apuntan solos al cargar un albarán, con el día y la hora."
                : android.text.TextUtils.join("\n\n", ls))
            .setPositiveButton("CERRAR", null).show();
    }

    private void hacerAlbaran(File f, boolean vaciar, AlbaranPdf.Resultado pdf) {
        try {
            java.util.TreeMap<String, Integer> antes = fotoAlmacen();
            int totalAntes = almacen.total();
            String r = almacen.importarAlbaran(f, vaciar);
            java.util.TreeMap<String, Integer> despues = fotoAlmacen();
            StringBuilder sb = new StringBuilder(r).append("\n\nCambios por material:\n");
            java.util.TreeSet<String> claves = new java.util.TreeSet<>(antes.keySet());
            claves.addAll(despues.keySet());
            int cambios = 0;
            for (String k : claves) {
                int a = antes.containsKey(k) ? antes.get(k) : 0, d = despues.containsKey(k) ? despues.get(k) : 0;
                if (a == d) continue;
                cambios++;
                sb.append(d > a ? "➕ " : "➖ ").append(k.substring(0, k.indexOf('|'))).append(": ").append(d > a ? "+" : "").append(d - a)
                  .append("  (ahora ").append(d).append(")\n");
            }
            if (cambios == 0) sb.append("Ninguno.\n");
            sb.append("\nAlmacén: ").append(totalAntes).append(" → ").append(almacen.total()).append(" unidades");
            if (pdf != null) sb.append(comprobarReposicion(pdf));
            aviso("✓ " + r);
            apuntarHistorial(f, totalAntes, almacen.total(), pdf != null);
            sb.insert(0, "Cargado el " + new SimpleDateFormat("dd/MM/yyyy 'a las' HH:mm", Locale.ROOT).format(new Date()) + "\n\n");
            new AlertDialog.Builder(this).setTitle("✓ Albarán cargado").setMessage(sb.toString()).setPositiveButton("OK", null).show();
        } catch (IllegalStateException e) {
            aviso("Ese albarán ya estaba cargado");
        } catch (Exception e) {
            android.util.Log.e("KULI_ORDENES", "Leyendo albarán", e);
            aviso("No he podido leer ese archivo");
        }
        mostrarAlmacen();
    }

    // ---------- Albarán desde el PDF ----------
    private void elegirPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        try { startActivityForResult(i, PIDE_PDF); }
        catch (Exception e) { aviso("No se pudo abrir el selector de archivos"); }
    }

    // Al abrir la app, busca PDF de albaranes nuevos en Descargas y pregunta si cargarlos.
    private void buscarAlbaranesNuevos() {
        try {
            final android.content.SharedPreferences sp = getSharedPreferences("ordenes", MODE_PRIVATE);
            final java.util.Set<String> vistos = new java.util.HashSet<>(sp.getStringSet("pdfs_vistos", new java.util.HashSet<String>()));
            File dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            File[] fs = dl.listFiles((x, n) -> n.toLowerCase(Locale.ROOT).endsWith(".pdf") && n.toLowerCase(Locale.ROOT).startsWith("alb"));
            if (fs == null) return;
            File nuevo = null;
            for (File f : fs) {
                String clave = f.getName() + "|" + f.length();
                if (!vistos.contains(clave) && (nuevo == null || f.lastModified() > nuevo.lastModified())) nuevo = f;
            }
            if (nuevo == null) return;
            final File cand = nuevo;
            final String clave = cand.getName() + "|" + cand.length();
            new AlertDialog.Builder(this).setTitle("📄 Albarán nuevo en Descargas")
                .setMessage("He encontrado:\n" + cand.getName() + "\n\n¿Lo cargo al almacén?\n(Si ya lo cargaste, no se suma dos veces.)")
                .setPositiveButton("CARGAR", (d, w) -> {
                    vistos.add(clave);
                    sp.edit().putStringSet("pdfs_vistos", vistos).apply();
                    importarPdf(Uri.fromFile(cand));
                })
                .setNegativeButton("IGNORAR", (d, w) -> {
                    vistos.add(clave);
                    sp.edit().putStringSet("pdfs_vistos", vistos).apply();
                })
                .setNeutralButton("DESPUÉS", null).show();
        } catch (Throwable t) { android.util.Log.e("KULI_ORDENES", "Buscando albaranes", t); }
    }

    private boolean albaranesRevisados = false;

    private void importarPdf(final Uri uri) {
        aviso("Leyendo el PDF…");
        hilos.execute(() -> {
            try {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());
                InputStream in = getContentResolver().openInputStream(uri);
                byte[] datos;
                try { datos = leerTodo(in); } finally { in.close(); }
                final AlbaranPdf.Resultado r = AlbaranPdf.leer(datos);
                if (r.series + r.cantidades == 0) {
                    runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("No he encontrado material")
                        .setMessage("No he podido leer materiales en ese PDF. Mándame una captura del PDF y lo miro.")
                        .setPositiveButton("OK", null).show());
                    return;
                }
                File dir = new File(raiz, "Albaranes");
                dir.mkdirs();
                String nombre = "pdf_" + r.id.replace('/', '_') + ".txt";
                final File f = new File(dir, nombre);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(r.texto.getBytes("UTF-8")); }
                if (!r.trabajos.isEmpty()) {
                    File td = new File(raiz, "Trabajos");
                    td.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(new File(td, r.id.replace('/', '_') + ".txt"))) {
                        o.write(("Albarán " + r.id + " (" + r.fecha + ")\n" + r.trabajos).getBytes("UTF-8"));
                    }
                }
                runOnUiThread(() -> hacerAlbaran(f, false, r));
            } catch (Throwable t) {
                android.util.Log.e("KULI_ORDENES", "Leyendo PDF", t);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("No he podido leer el PDF")
                    .setMessage("Error: " + t.getClass().getSimpleName() + ". Usa «PEGAR TEXTO» mientras lo arreglo.")
                    .setPositiveButton("OK", null).show());
            }
        });
    }

    // ---------- Copias de seguridad del almacén ----------
    private void mostrarCopias() {
        final ArrayList<File> cs = almacen.copias();
        if (cs.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("🛟 Copias del almacén")
                .setMessage("Todavía no hay copias. Se hacen solas cada vez que cambias el almacén.")
                .setPositiveButton("OK", null).show();
            return;
        }
        String[] nombres = new String[cs.size()];
        for (int i = 0; i < nombres.length; i++) {
            String n = cs.get(i).getName().replace("almacen_", "").replace(".json", "");
            String bonito = n.length() >= 15 ? n.substring(6, 8) + "/" + n.substring(4, 6) + "  " + n.substring(9, 11) + ":" + n.substring(11, 13) : n;
            int u = almacen.unidadesDe(cs.get(i));
            nombres[i] = bonito + "  ·  " + (u < 0 ? "?" : String.valueOf(u)) + " unidades";
        }
        new AlertDialog.Builder(this).setTitle("🛟 Volver a una copia")
            .setItems(nombres, (d, i) -> new AlertDialog.Builder(this).setTitle("¿Volver a esta copia?")
                .setMessage(nombres[i] + "\n\nAntes de cambiar se guarda una copia de lo de ahora.")
                .setPositiveButton("VOLVER", (d2, w) -> {
                    boolean ok = almacen.restaurar(cs.get(i));
                    aviso(ok ? "✓ Almacén recuperado" : "No se pudo recuperar");
                    mostrarAlmacen();
                })
                .setNegativeButton("CANCELAR", null).show())
            .setNegativeButton("CERRAR", null).show();
    }

    // ---------- Resumen de trabajos ----------
    private void resumenTrabajos() {
        StringBuilder sb = new StringBuilder("📍 TRABAJOS · " + formatoLargo(System.currentTimeMillis()) + "\n\n");
        sb.append("ÓRDENES (WK) CON MATERIAL:\n");
        int n = 0;
        for (Resumen r : listarOrdenes()) {
            ArrayList<String> mat = leerMaterial(r.nombre);
            if (mat.isEmpty()) continue;
            n++;
            sb.append("• ").append(r.nombre).append("\n");
            for (String l : mat) {
                String[] p = l.split(" \\| ");
                if (p.length < 2) continue;
                sb.append("    - ").append(p[1].trim());
                if (p.length > 2 && !p[2].trim().isEmpty()) sb.append(" (").append(p[2].trim()).append(")");
                sb.append("\n");
            }
        }
        if (n == 0) sb.append("(ninguna con material apuntado)\n");
        File td = new File(raiz, "Trabajos");
        File[] fs = td.listFiles((x, nm) -> nm.endsWith(".txt"));
        if (fs != null && fs.length > 0) {
            Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            sb.append("\nTRABAJOS DEL ÚLTIMO ALBARÁN PDF (dirección):\n");
            try (InputStream in = new java.io.FileInputStream(fs[0])) { sb.append(new String(leerTodo(in), "UTF-8")); }
            catch (Exception e) { sb.append("(no se pudo leer)\n"); }
        }
        final String texto = sb.toString();
        new AlertDialog.Builder(this).setTitle("📍 Resumen de trabajos").setMessage(texto)
            .setPositiveButton("ENVIAR", (d, w) -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, texto);
                startActivity(Intent.createChooser(i, "Enviar resumen"));
            })
            .setNegativeButton("CERRAR", null).show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (tono != null) tono.release();
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
            if (tienePermiso()) { aviso("Permiso concedido"); recreate(); }
            else aviso("Sin el permiso no se pueden guardar las fotos");
        }
    }

    // ---------- Pantalla de inicio ----------
    private void mostrarInicio() {
        sincronizarFotos();
        if (!albaranesRevisados) { albaranesRevisados = true; buscarAlbaranesNuevos(); }
        ordenAbierta = null;
        enAlmacen = false;
        contenido.removeAllViews();
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(20), dp(36), dp(20), dp(12));

        TextView t = texto("KULI ÓRDENES", 24, DORADO, true);
        t.setGravity(Gravity.CENTER);
        LinearLayout cabecera = new LinearLayout(this);
        cabecera.setGravity(Gravity.CENTER);
        cabecera.addView(new Escudo(this), new LinearLayout.LayoutParams(dp(30), dp(34)));
        cabecera.addView(t, new LinearLayout.LayoutParams(-2, -2));
        p.addView(cabecera, new LinearLayout.LayoutParams(-1, -2));
        TextView s = texto("Foto del material con el nº de orden, el día y la hora", 13, GRIS, false);
        s.setGravity(Gravity.CENTER);
        p.addView(s);
        TextView hoy = texto(resumenHoy(), 13, ORO, true);
        hoy.setGravity(Gravity.CENTER);
        p.addView(hoy);
        long nasOk = getSharedPreferences("ordenes", MODE_PRIVATE).getLong("nas_ok", 0);
        long horas = nasOk == 0 ? -1 : (System.currentTimeMillis() - nasOk) / 3600000L;
        String txtNas = nasOk == 0 ? "\u26a0\ufe0f Copia en el NAS: todav\u00eda ninguna"
            : "\u2601\ufe0f \u00daltima copia en el NAS: " + new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(new java.util.Date(nasOk));
        TextView nasV = texto(txtNas, 12, (nasOk == 0 || horas > 48) ? android.graphics.Color.rgb(217, 48, 37) : GRIS, false);
        nasV.setGravity(Gravity.CENTER);
        p.addView(nasV);

        final EditText orden = campo("Nº de orden", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        final TextView hintOrden = texto("", 12, GRIS, false);
        orden.setText(ultOrden);
        orden.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { ultOrden = e.toString(); revisarOrden(e.toString(), hintOrden); }
        });
        p.addView(orden, margen(-1, -2, 18));
        p.addView(hintOrden, margen(-2, -2, 2));
        revisarOrden(ultOrden, hintOrden);

        Button esc0 = boton("🔲  Escanear equipo de esta orden", true);
        esc0.setOnClickListener(v -> {
            String o = ultOrden.trim().toUpperCase(Locale.ROOT);
            if (!datosValidos(o)) return;
            ultOrden = o;
            gastarSeguido(o);
        });
        p.addView(esc0, margen(-1, dp(54), 10));
        Button btnAv0 = boton("🔄 Avería: retirar equipo roto (para devolver)", false);
        btnAv0.setOnClickListener(v -> registrarEquipoAveriado(orden.getText().toString().trim()));
        p.addView(btnAv0, margen(-1, -2, 8));

        final EditText nota = campo("Material (se rellena solo al escanear)", InputType.TYPE_CLASS_TEXT);
        nota.setText(ultNota);
        nota.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { ultNota = e.toString(); }
        });
        p.addView(nota, margen(-1, -2, 8));

        // V227: se enseña la calle antes de hacer la foto
        final TextView gpsTxt = texto(dirPantalla.isEmpty() ? "📍 Buscando ubicación…" : dirPantalla, 13, GRIS, false);
        p.addView(gpsTxt, margen(-1, -2, 6));
        new Thread(() -> {
            final String d = obtenerDireccionGpsActual();
            if (!d.isEmpty()) dirPantalla = d;
            runOnUiThread(() -> gpsTxt.setText(d.isEmpty() ? (dirPantalla.isEmpty() ? "📍 Sin ubicación (activa el GPS y el permiso)" : dirPantalla) : d));
        }).start();
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
        Button bus = boton("🔎  Buscar equipo (serie)", false);
        bus.setOnClickListener(v -> buscarEquipo());
        p.addView(bus, margen(-1, dp(46), 8));
        LinearLayout filaC = new LinearLayout(this);
        Button med = boton("🧾 A medias", false);
        med.setOnClickListener(v -> ordenesAMedias());
        filaC.addView(med, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button mes = boton("📊 Resumen del mes", false);
        mes.setOnClickListener(v -> resumenMes(0));
        LinearLayout.LayoutParams lmes = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lmes.leftMargin = dp(10);
        filaC.addView(mes, lmes);
        p.addView(filaC, margen(-1, -2, 4));
        Button res = boton("📍 Resumen de trabajos (WK y direcciones)", false);
        res.setOnClickListener(v -> resumenTrabajos());
        p.addView(res, margen(-1, dp(46), 4));
        Button bo = boton("🔎  Buscar orden (WK, dirección, material)", false);
        bo.setOnClickListener(v -> buscarOrden());
        p.addView(bo, margen(-1, dp(46), 8));
        Button pl = boton("📋  Plantillas de material", false);
        pl.setOnClickListener(v -> plantillas());
        p.addView(pl, margen(-1, dp(46), 8));
        Button inf = boton("📨  Informe del mes (para enviar)", false);
        inf.setOnClickListener(v -> informeMes(0));
        p.addView(inf, margen(-1, dp(46), 4));
        agrupar(p, "📦  Almacén, búsqueda y plantillas", alm, bus, bo, pl);
        Button ped = boton("🛒  Pedido de material (lo que queda poco)", false);
        ped.setOnClickListener(v -> pedidoMaterial());
        p.addView(ped, margen(-1, dp(46), 4));
        agrupar(p, "📊  Resúmenes e informe", filaC, res, inf, ped);
        ArrayList<Almacen.Item> bajos = almacen.bajos();
        if (!bajos.isEmpty()) {
            StringBuilder sb = new StringBuilder("⚠ Queda poco: ");
            for (int k = 0; k < bajos.size(); k++) sb.append(k > 0 ? ", " : "").append(bajos.get(k).nombre).append(" (").append(bajos.get(k).cantidad).append(")");
            TextView av = texto(sb.toString(), 13, Color.rgb(255, 130, 110), true);
            av.setPadding(dp(4), dp(4), dp(4), dp(4));
            p.addView(av);
        }


        final android.content.SharedPreferences prefs = getSharedPreferences("ordenes", MODE_PRIVATE);
        long ok = prefs.getLong("nas_ok", 0);
        if (ok == 0 || System.currentTimeMillis() - ok > 2L * 86400000L) {
            TextView an = texto("⚠ Sin copia al NAS " + (ok == 0 ? "todavía" : "desde " + formatoCorto(ok)) + ". Conéctate al wifi de casa.", 13, Color.rgb(255, 190, 90), true);
            an.setPadding(dp(4), dp(4), dp(4), dp(4));
            p.addView(an);
        }
        final boolean plegada = prefs.getBoolean("plegada", false);
        final ArrayList<Resumen> ordenes = listarOrdenes();
        if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 17) {
            java.util.Calendar c0 = java.util.Calendar.getInstance();
            c0.set(java.util.Calendar.HOUR_OF_DAY, 0); c0.set(java.util.Calendar.MINUTE, 0);
            c0.set(java.util.Calendar.SECOND, 0); c0.set(java.util.Calendar.MILLISECOND, 0);
            int abiertas = 0;
            for (Resumen r0 : ordenes) if (!r0.cerrada && r0.ultima >= c0.getTimeInMillis()) abiertas++;
            if (abiertas > 0) {
                TextView rec = texto("🔔 Tienes " + abiertas + (abiertas == 1 ? " orden de hoy sin cerrar" : " órdenes de hoy sin cerrar"), 13, Color.rgb(255, 190, 90), true);
                rec.setPadding(dp(4), dp(4), dp(4), dp(4));
                p.addView(rec);
            }
        }
        if (!filtro.isEmpty()) {
            String q = filtro.toLowerCase(Locale.ROOT);
            for (int k = ordenes.size() - 1; k >= 0; k--) {
                Resumen r = ordenes.get(k);
                boolean ok2 = r.nombre.toLowerCase(Locale.ROOT).contains(q) || r.datos.toLowerCase(Locale.ROOT).contains(q);
                if (!ok2) for (String l : leerMaterial(r.nombre)) if (l.toLowerCase(Locale.ROOT).contains(q)) { ok2 = true; break; }
                if (!ok2) ordenes.remove(k);
            }
            TextView chip = texto("🔎 Filtro: «" + filtro + "»  ✕  (toca para quitar)", 14, ORO, true);
            chip.setPadding(dp(8), dp(8), dp(8), dp(8));
            chip.setBackgroundColor(TARJETA);
            chip.setOnClickListener(v -> { filtro = ""; mostrarInicio(); });
            p.addView(chip, margen(-1, -2, 10));
        }
        TextView cab = texto((plegada ? "▶  " : "▼  ") + "Órdenes guardadas (" + ordenes.size() + ")", 15, GRIS, true);
        cab.setPadding(dp(4), dp(10), dp(4), dp(10));
        cab.setOnClickListener(v -> { prefs.edit().putBoolean("plegada", !plegada).apply(); mostrarInicio(); });
        p.addView(cab, margen(-2, -2, 14));

        if (plegada) {
            // lista cerrada: se abre tocando el título
        } else if (ordenes.isEmpty()) {
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
                        + "  ·  última: " + formatoCorto(r.ultima)
                        + (r.datos.isEmpty() ? "" : "\n📍 " + r.datos)
                        + (r.cerrada ? "\n✔ cerrada" : "")
                        + (r.fotos == 0 ? "\n⚠ sin foto" : "") + (r.mat == 0 ? (r.fotos == 0 ? "  ·  " : "\n") + "⚠ sin material" : ""));
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

    // Escudo propio de KULI (dibujado a mano, original).
    private static final class Escudo extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Escudo(android.content.Context c) { super(c); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(w * 0.08f, h * 0.06f);
            path.lineTo(w * 0.92f, h * 0.06f);
            path.lineTo(w * 0.92f, h * 0.50f);
            path.quadTo(w * 0.92f, h * 0.84f, w * 0.50f, h * 0.97f);
            path.quadTo(w * 0.08f, h * 0.84f, w * 0.08f, h * 0.50f);
            path.close();
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(Color.rgb(5, 28, 62));
            c.drawPath(path, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.07f);
            p.setColor(Color.WHITE);
            c.drawPath(path, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(Color.rgb(255, 205, 80));
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(h * 0.52f);
            c.drawText("K", w * 0.5f, h * 0.64f, p);
            p.setColor(Color.WHITE);
            c.drawRect(w * 0.22f, h * 0.72f, w * 0.78f, h * 0.76f, p);
        }
    }

    // Cuántas órdenes y materiales llevas hoy.
    private String resumenHoy() {
        String hoy = new SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(new Date());
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0);
        long ini = c.getTimeInMillis();
        int ordenes = 0, materiales = 0;
        for (Resumen r : listarOrdenes()) {
            if (r.ultima >= ini) ordenes++;
            for (String l : leerMaterial(r.nombre)) if (l.startsWith(hoy)) materiales++;
        }
        return "Hoy: " + ordenes + (ordenes == 1 ? " orden" : " órdenes") + " · " + materiales + (materiales == 1 ? " material" : " materiales");
    }

    // Mete varias vistas dentro de una sección que se abre y se cierra tocando el título.
    private void agrupar(LinearLayout panel, final String titulo, View... vistas) {
        int pos = panel.indexOfChild(vistas[0]);
        if (pos < 0) pos = panel.getChildCount();
        final LinearLayout cuerpo = new LinearLayout(this);
        cuerpo.setOrientation(LinearLayout.VERTICAL);
        for (View v : vistas) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            panel.removeView(v);
            cuerpo.addView(v, lp != null ? lp : new LinearLayout.LayoutParams(-1, -2));
        }
        cuerpo.setVisibility(View.GONE);
        final TextView cab = texto("▸  " + titulo.trim(), 14, DORADO, true);
        cab.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cab.setPadding(dp(14), dp(10), dp(14), dp(10));
        cab.setBackgroundColor(TARJETA);
        cab.setOnClickListener(v -> {
            boolean abrir = cuerpo.getVisibility() != View.VISIBLE;
            cuerpo.setVisibility(abrir ? View.VISIBLE : View.GONE);
            cab.setText((abrir ? "▾  " : "▸  ") + titulo.trim());
        });
        LinearLayout.LayoutParams lpCab = new LinearLayout.LayoutParams(-1, -2);
        lpCab.topMargin = dp(8);
        panel.addView(cab, Math.min(pos, panel.getChildCount()), lpCab);
        panel.addView(cuerpo, Math.min(pos + 1, panel.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
    }

    private ArrayList<Resumen> listarOrdenes() {
        ArrayList<Resumen> r = new ArrayList<>();
        File[] dirs = raiz.listFiles();
        if (dirs == null) return r;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File[] fs = d.listFiles((x, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jpg"));
            File mat = new File(d, "material.txt");
            if ((fs == null || fs.length == 0) && !mat.exists() && !new File(d, "datos.txt").exists()) continue;
            Resumen x = new Resumen();
            x.nombre = d.getName();
            x.fotos = fs == null ? 0 : fs.length;
            x.mat = leerMaterial(d.getName()).size();
            x.cerrada = new File(d, "cerrada.txt").exists();
            String[] dt = leerDatos(d.getName());
            x.datos = (dt[0] + " " + dt[1]).trim();
            x.metros = numero(dt[2]);
            if (fs != null) for (File f : fs) x.ultima = Math.max(x.ultima, fechaDeArchivo(f));
            if (mat.exists()) x.ultima = Math.max(x.ultima, mat.lastModified());
            r.add(x);
        }
        Collections.sort(r, (a, b) -> Long.compare(b.ultima, a.ultima));
        return r;
    }

    // ---------- Detalle de una orden ----------
    private void mostrarOrden(final String nombre) {
        String auto = notaAuto(nombre);
        if (!nombre.equals(ordenAbierta) || !auto.isEmpty()) ultNota = auto; // el material escaneado pasa solo a la casilla
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

        final EditText nota = campo("Material (se rellena solo al escanear)", InputType.TYPE_CLASS_TEXT);
        nota.setText(ultNota);
        nota.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { }
            public void afterTextChanged(Editable e) { ultNota = e.toString(); }
        });
        p.addView(nota, margen(-1, -2, 8));
        Button btnAvOrd = boton("🔄 Retirar equipo averiado en esta orden", false);
        btnAvOrd.setOnClickListener(v -> registrarEquipoAveriado(nombre));
        p.addView(btnAvOrd, margen(-1, -2, 8));

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

        Button mat = boton("🔲  Escanear equipo usado (sale del almacén)", false);
        mat.setOnClickListener(v -> materialUsado(nombre));
        p.addView(mat, margen(-1, dp(46), 8));
        LinearLayout filaB = new LinearLayout(this);
        Button ret = boton("↩  Equipo retirado", false);
        ret.setOnClickListener(v -> retirarSeguido(nombre));
        filaB.addView(ret, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button des = boton("↶  Deshacer", false);
        des.setOnClickListener(v -> deshacer());
        LinearLayout.LayoutParams ld = new LinearLayout.LayoutParams(0, dp(46), 1f);
        ld.leftMargin = dp(10);
        filaB.addView(des, ld);
        p.addView(filaB, margen(-1, -2, 4));
        LinearLayout filaD = new LinearLayout(this);
        Button dat = boton("✏ Datos", false);
        dat.setOnClickListener(v -> editarDatos(nombre));
        filaD.addView(dat, new LinearLayout.LayoutParams(0, dp(46), 1f));
        final boolean cerr = new File(new File(raiz, limpiar(nombre)), "cerrada.txt").exists();
        Button cer = boton(cerr ? "↺ Reabrir" : "✔ Cerrar", false);
        cer.setOnClickListener(v -> cerrarOrden(nombre, !cerr));
        LinearLayout.LayoutParams lcer = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lcer.leftMargin = dp(8);
        filaD.addView(cer, lcer);
        Button paq = boton("📦 Paquete", false);
        paq.setOnClickListener(v -> paqueteOrden(nombre));
        LinearLayout.LayoutParams lpaq = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lpaq.leftMargin = dp(8);
        filaD.addView(paq, lpaq);
        p.addView(filaD, margen(-1, -2, 4));
        String[] dtos = leerDatos(nombre);
        if (!dtos[0].isEmpty() || !dtos[1].isEmpty() || !dtos[2].isEmpty() || cerr) {
            TextView td = texto((cerr ? "✔ Cerrada" + (dtos[0].isEmpty() && dtos[1].isEmpty() ? "" : "\n") : "")
                + (dtos[0].isEmpty() ? "" : "👤 " + dtos[0]) + (dtos[1].isEmpty() ? "" : (dtos[0].isEmpty() ? "" : "\n") + "📍 " + dtos[1])
                + (dtos[2].isEmpty() ? "" : "\n📏 " + dtos[2] + " m de cable"), 13, GRIS, false);
            td.setPadding(dp(4), dp(8), dp(4), dp(2));
            p.addView(td);
        }
        final ArrayList<String> usado = leerMaterial(nombre);
        if (!usado.isEmpty()) {
            TextView mt = texto("📦 Material usado en esta orden: " + usado.size() + "  (toca para ver o quitar)", 13, GRIS, false);
            mt.setPadding(dp(4), dp(8), dp(4), dp(4));
            mt.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Material usado · orden " + nombre)
                .setItems(usado.toArray(new String[0]), (d2, i2) -> quitarLineaMaterial(nombre, usado.get(i2))).setPositiveButton("CERRAR", null).show());
            p.addView(mt);
        }

        final ArrayList<File> fotos = fotosDe(nombre);
        final java.util.HashMap<String, String> etiquetas = leerEtiquetas(nombre);
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
                    String et = etiquetas.get(f.getName());
                    pie.setText((et == null ? "" : et + " · ") + formatoCorto(fechaDeArchivo(f)));
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
        String etqF = leerEtiquetas(orden).get(f.getName());
        TextView info = texto("Orden " + orden + (etqF == null ? "" : "  ·  " + etqF) + "  ·  " + formatoLargo(fechaDeArchivo(f)), 13, Color.WHITE, false);
        abajo.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        Button comp = boton("↗", false);
        comp.setOnClickListener(v -> compartir(new ArrayList<>(Arrays.asList(f)), orden));
        abajo.addView(comp, new LinearLayout.LayoutParams(dp(54), dp(44)));
        Button etq = boton("🏷", false);
        etq.setOnClickListener(v -> elegirEtiqueta(orden, f));
        abajo.addView(etq, new LinearLayout.LayoutParams(dp(54), dp(44)));
        Button borrar = boton("🗑", false);
        borrar.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("¿Borrar esta foto?")
            .setMessage("Se borra del móvil y no se puede recuperar.")
            .setPositiveButton("BORRAR", (d, w) -> {
                respaldoDe(f).delete();
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
        if (fotos.isEmpty()) {
            Intent t = new Intent(Intent.ACTION_SEND);
            t.setType("text/plain");
            t.putExtra(Intent.EXTRA_TEXT, resumenOrden(orden));
            startActivity(Intent.createChooser(t, "Enviar resumen de la orden " + orden));
            return;
        }
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
                    i.putExtra(Intent.EXTRA_TEXT, resumenOrden(orden));
                    if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Compartir fotos de la orden " + orden));
                });
            }
        });
    }

    // ---------- Almacén ----------
    private void escanear(final Consumer<String> alLeer) { escanear(alLeer, null); }

    private Consumer<String> escAlLeer;
    private Runnable escAlTerminar;
    static final int PIDE_ESCANER = 4, PIDE_PDF = 5;

    // Escáner propio (cámara en alta resolución). Si falla, se puede cambiar al de Google desde su pantalla.
    private void escanear(final Consumer<String> alLeer, final Runnable alTerminar) {
        escAlLeer = alLeer;
        escAlTerminar = alTerminar;
        try {
            startActivityForResult(new Intent(this, ScanActivity.class), PIDE_ESCANER);
        } catch (Throwable t) {
            escanearGoogle(alLeer, alTerminar);
        }
    }

    // V223: cuando la etiqueta no se lee, se escribe el número de serie a mano.
    private void escribirSerie(final Consumer<String> alLeer, final Runnable alTerminar) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("Nº de serie (ej. 88FEB6268920 o 26/009103)");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this).setTitle("✍ Escribir el nº de serie").setView(e)
            .setPositiveButton("OK", (d, w) -> {
                String v = e.getText().toString().trim().replace(" ", "");
                if (v.isEmpty()) { if (alTerminar != null) alTerminar.run(); return; }
                recibirLectura(v, alLeer);
            })
            .setNegativeButton("CANCELAR", (d, w) -> { if (alTerminar != null) alTerminar.run(); })
            .setOnCancelListener(d -> { if (alTerminar != null) alTerminar.run(); }).show();
    }

    private void recibirLectura(String v, Consumer<String> alLeer) {
        String limpio = almacen.limpiarLectura(v);
        if (!limpio.equals(v.trim())) {
            String visto = v.trim().replace('\n', ' ');
            aviso("Leído: " + (visto.length() > 70 ? visto.substring(0, 70) + "…" : visto) + "\n→ " + limpio);
        }
        alLeer.accept(limpio);
    }

    private void escanearGoogle(final Consumer<String> alLeer, final Runnable alTerminar) {
        try {
            GmsBarcodeScannerOptions opc = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF,
                    Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();
            GmsBarcodeScanner sc = GmsBarcodeScanning.getClient(this, opc);
            sc.startScan()
                .addOnSuccessListener(b -> {
                    String v = b.getRawValue();
                    if (v == null || v.trim().isEmpty()) {
                        aviso("No he podido leer el código");
                        if (alTerminar != null) alTerminar.run();
                    } else {
                        recibirLectura(v.trim(), alLeer);
                    }
                })
                .addOnCanceledListener(() -> { if (alTerminar != null) alTerminar.run(); })
                .addOnFailureListener(e -> {
                    android.util.Log.e("KULI_ORDENES", "Escáner", e);
                    aviso("No se pudo abrir el escáner. Prueba a escribir el código a mano.");
                    if (alTerminar != null) alTerminar.run();
                });
        } catch (Throwable t) {
            aviso("El escáner no está disponible en este móvil");
            if (alTerminar != null) alTerminar.run();
        }
    }

    // ---------- Existencias: una tarjeta por unidad, lo más antiguo primero (como la app de Movistar) ----------
    private String sinAcentos(String t) {
        String n = java.text.Normalizer.normalize(t == null ? "" : t, java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private ArrayList<String[]> filasExistencias(String filtro) {
        String q = sinAcentos(filtro.trim());
        long ahora = System.currentTimeMillis();
        ArrayList<String[]> filas = new ArrayList<>();
        for (Almacen.Item it : almacen.lista()) {
            for (String raw : it.raws) {
                if (q.length() >= 3 && !sinAcentos(it.nombre + " " + it.codigo + " " + raw).contains(q)) continue;
                long dias = Math.max(0, (ahora - almacen.fechaDe(it, raw)) / 86400000L);
                filas.add(new String[]{it.codigo, it.nombre, raw, "1", String.valueOf(dias)});
            }
            int sinSerie = it.cantidad - it.raws.size();
            if (sinSerie > 0 && (q.length() < 3 || sinAcentos(it.nombre + " " + it.codigo).contains(q))) {
                long dias = Math.max(0, (ahora - it.fecha) / 86400000L);
                filas.add(new String[]{it.codigo, it.nombre, "", String.valueOf(sinSerie), String.valueOf(dias)});
            }
        }
        Collections.sort(filas, (a, b) -> Long.compare(Long.parseLong(b[4]), Long.parseLong(a[4])));
        return filas;
    }

    private String ordenDestinoDirecto = null;
    
    private void registrarPendienteReponer(String orden, String cod, String nombre, String raw) {
        try {
            File f = new File(android.os.Environment.getExternalStorageDirectory(), "Almacen de Rafa/pendiente_reponer.txt");
            f.getParentFile().mkdirs();
            String fecha = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US).format(new java.util.Date());
            String linea = fecha + " | Orden: " + orden + " | " + cod + " | " + nombre + (raw != null && !raw.isEmpty() ? " | Serie: " + raw : "") + "\n";
            try (java.io.FileWriter fw = new java.io.FileWriter(f, true)) { fw.write(linea); }
        } catch (Throwable ignored) {}
    }

    
    private void analizarYLimpiarMovil() {
        new Thread(() -> {
            java.util.ArrayList<File> basura = new java.util.ArrayList<>();
            long bytesTotales = 0;
            File root = android.os.Environment.getExternalStorageDirectory();
            File dl = new File(root, "Download");
            java.util.HashSet<String> keepApk = new java.util.HashSet<>(java.util.Arrays.asList(
                "app-debug.apk", "ordenes-debug.apk", "mapas-debug.apk", "fotos-debug.apk", "familia-debug.apk", "arcade-debug.apk"
            ));
            if (dl.exists() && dl.listFiles() != null) {
                for (File f : dl.listFiles()) {
                    String n = f.getName().toLowerCase();
                    if (f.isFile() && n.endsWith(".apk") && !keepApk.contains(f.getName())) {
                        basura.add(f);
                        bytesTotales += f.length();
                    } else if (f.isFile() && (n.endsWith(".tmp") || n.endsWith(".crdownload") || n.startsWith("raw_"))) {
                        basura.add(f);
                        bytesTotales += f.length();
                    }
                }
            }
            java.util.ArrayList<File> vacias = new java.util.ArrayList<>();
            escanearVacias(root, vacias, 0);

            final long mb = bytesTotales / (1024 * 1024);
            final StringBuilder resumen = new StringBuilder();
            resumen.append("📦 APKs viejas / temporales: ").append(basura.size()).append(" archivos (").append(mb).append(" MB)\n");
            for (int i = 0; i < basura.size(); i++) resumen.append("  • ").append(basura.get(i).getName()).append("\n");
            resumen.append("\n📂 Carpetas vacías obsoletas: ").append(vacias.size()).append("\n");
            for (int i = 0; i < vacias.size(); i++) resumen.append("  • ").append(vacias.get(i).getName()).append("\n");

            runOnUiThread(() -> {
                if (basura.isEmpty() && vacias.isEmpty()) {
                    new AlertDialog.Builder(this)
                        .setTitle("✨ Móvil limpio")
                        .setMessage("No hay APKs duplicadas, temporales ni carpetas vacías obsoletas.\n\nTus carpetas Almacen de Rafa, Music y DCIM están intactas.")
                        .setPositiveButton("Aceptar", null).show();
                    return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("🧹 Limpieza segura disponible")
                    .setMessage(resumen.toString() + "\n(Protegidas: Almacen de Rafa, Music, DCIM y Documents)")
                    .setPositiveButton("🗑️ Mover a papelera", (d, w) -> {
                        int borrados = 0;
                        // PAPELERA-KULI
                        File pap = new File(raiz, "Papelera");
                        pap.mkdirs();
                        for (File f : basura) if (f.renameTo(new File(pap, System.currentTimeMillis() + "_" + f.getName()))) borrados++;
                        java.util.Collections.sort(vacias, (a, b) -> Integer.compare(b.getAbsolutePath().length(), a.getAbsolutePath().length()));
                        for (File v : vacias) if (v.delete()) borrados++;
                        android.widget.Toast.makeText(this, "✅ Limpieza hecha: " + borrados + " elementos (los archivos est\u00e1n 7 d\u00edas en la Papelera)", android.widget.Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            });
        }).start();
    }

    private void escanearVacias(File dir, java.util.ArrayList<File> out, int nivel) {
        if (dir == null || !dir.isDirectory() || nivel > 4) return;
        String path = dir.getAbsolutePath();
        if (path.contains("/Android") || path.contains("/DCIM") || path.contains("/Music")
            || path.contains("/Almacen de Rafa") || path.contains("/Documents") || path.contains("/Pictures")) return;
        File[] hijos = dir.listFiles();
        if (hijos == null) return;
        if (hijos.length == 0 && nivel > 0) {
            out.add(dir);
            return;
        }
        for (File h : hijos) if (h.isDirectory()) escanearVacias(h, out, nivel + 1);
    }

    
    private void registrarEquipoAveriado(String ordenInicial) {
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        lay.setPadding(dp(18), dp(12), dp(18), dp(8));

        final EditText edOrd = campo("Nº Orden (WK... o nº empresa)", InputType.TYPE_CLASS_TEXT);
        if (ordenInicial != null && !ordenInicial.isEmpty()) edOrd.setText(ordenInicial);
        lay.addView(edOrd);

        final String[] tipos = {
            "Router Smart WiFi 7 (RTF8316VW / 8317)",
            "Router Smart WiFi 6 / HGU",
            "Deco UHD Movistar+",
            "Amplificador Smart WiFi",
            "ONT / Equipo Fibra",
            "Otro equipo averiado"
        };
        final android.widget.Spinner spTipo = new android.widget.Spinner(this);
        spTipo.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, tipos));
        lay.addView(spTipo, margen(-1, -2, 8));

        final EditText edSerie = campo("Nº de Serie del equipo retirado", InputType.TYPE_CLASS_TEXT);
        lay.addView(edSerie, margen(-1, -2, 8));

        final EditText edMotivo = campo("Motivo avería (ej. No enciende, Sin óptica, WiFi)", InputType.TYPE_CLASS_TEXT);
        lay.addView(edMotivo, margen(-1, -2, 8));

        new AlertDialog.Builder(this)
            .setTitle("🔄 Retirar equipo averiado")
            .setMessage("Se guarda aparte para devolver al almacén sin sumarlo a tu stock bueno.")
            .setView(lay)
            .setPositiveButton("Guardar retirada", (d, w) -> {
                String ord = edOrd.getText().toString().trim().toUpperCase(Locale.ROOT);
                if (ord.isEmpty()) ord = "SIN_ORDEN";
                String tipo = tipos[spTipo.getSelectedItemPosition()];
                String serie = edSerie.getText().toString().trim().toUpperCase(Locale.ROOT);
                if (serie.isEmpty()) serie = "SIN_SERIE";
                String mot = edMotivo.getText().toString().trim();
                if (mot.isEmpty()) mot = "Avería general";
                String fecha = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new java.util.Date());
                String linea = fecha + " | Orden: " + ord + " | " + tipo + " | Serie: " + serie + " | Motivo: " + mot + "\n";
                try {
                    File base = new File(android.os.Environment.getExternalStorageDirectory(), "Almacen de Rafa");
                    base.mkdirs();
                    File fAv = new File(base, "averiados_devolver.txt");
                    java.io.FileWriter fw = new java.io.FileWriter(fAv, true);
                    fw.write(linea);
                    fw.close();
                    File dirOrd = new File(base, ord);
                    if (dirOrd.exists()) {
                        java.io.FileWriter fw2 = new java.io.FileWriter(new File(dirOrd, "averia_retirada.txt"), true);
                        fw2.write(linea);
                        fw2.close();
                    }
                    android.widget.Toast.makeText(this, "🛠️ Averiado guardado para devolver (" + ord + ")", android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    android.widget.Toast.makeText(this, "Error guardando avería: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void verEquiposAveriados() {
        File fAv = new File(new File(android.os.Environment.getExternalStorageDirectory(), "Almacen de Rafa"), "averiados_devolver.txt");
        String contenido = "";
        if (fAv.exists()) {
            try {
                java.util.List<String> lineas = java.nio.file.Files.readAllLines(fAv.toPath());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lineas.size(); i++) {
                    sb.append(i + 1).append(". ").append(lineas.get(i)).append("\n\n");
                }
                contenido = sb.toString().trim();
            } catch (Exception ignored) {}
        }
        if (contenido.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("🛠️ Averiados retirados")
                .setMessage("No tienes ningún equipo averiado pendiente de devolver al almacén.")
                .setPositiveButton("Aceptar", null)
                .show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("🛠️ Averiados para devolver a almacén")
            .setMessage(contenido)
            .setPositiveButton("✅ Ya entregados (vaciar)", (d, w) -> {
                fAv.delete();
                android.widget.Toast.makeText(this, "✅ Lista de averiados entregados puesta a cero", android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cerrar", null)
            .show();
    }

    private void verPendienteReponer() {
        try {
            File f = new File(android.os.Environment.getExternalStorageDirectory(), "Almacen de Rafa/pendiente_reponer.txt");
            String texto = "";
            int count = 0;
            if (f.exists()) {
                java.util.List<String> lns = java.nio.file.Files.readAllLines(f.toPath());
                StringBuilder sb = new StringBuilder();
                for (String l : lns) {
                    if (!l.trim().isEmpty()) { count++; sb.append("• ").append(l).append("\n\n"); }
                }
                texto = sb.toString();
            }
            final String contenidoFinal = texto.isEmpty() ? "✅ Todo repuesto. No hay equipos pendientes." : texto;
            new AlertDialog.Builder(this)
                .setTitle("📋 Pendiente de reponer (" + count + " uds.)")
                .setMessage(contenidoFinal)
                .setPositiveButton("📲 Enviar lista", (d, w) -> {
                    Intent sh = new Intent(Intent.ACTION_SEND);
                    sh.setType("text/plain");
                    sh.putExtra(Intent.EXTRA_TEXT, "📋 MATERIAL PENDIENTE DE REPONER - RAFA:\n\n" + contenidoFinal);
                    startActivity(Intent.createChooser(sh, "Compartir parte de reposición"));
                })
                .setNeutralButton("✅ Ya repuesto (Limpiar)", (d, w) -> {
                    try {
                        if (f.exists()) {
                            File hist = new File(f.getParentFile(), "historial_repuestos.txt");
                            try (java.io.FileWriter fw = new java.io.FileWriter(hist, true)) { fw.write(contenidoFinal + "\n---\n"); }
                            f.delete();
                        }
                        android.widget.Toast.makeText(this, "✅ Lista marcada como repuesta y limpiada", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                })
                .setNegativeButton("Cerrar", null)
                .show();
        } catch (Throwable ignored) {}
    }

    private void mostrarExistencias() {
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
        atras.setOnClickListener(v -> mostrarAlmacen());
        barra.addView(atras, new LinearLayout.LayoutParams(dp(56), dp(46)));
        TextView t = texto("📋 Existencias", 20, Color.WHITE, true);
        t.setPadding(dp(12), 0, 0, 0);
        barra.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        p.addView(barra);

        final EditText buscar = new EditText(this);
        buscar.setHint("Material a buscar (mínimo 3 caracteres)");
        buscar.setSingleLine(true);
        buscar.setTextColor(Color.WHITE);
        buscar.setHintTextColor(GRIS);
        p.addView(buscar, margen(-1, -2, 6));

        TextView banner = texto("ATENCIÓN:\nEs obligatorio instalar, en primer lugar, el material más antiguo", 13, Color.rgb(150, 40, 50), true);
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundColor(Color.rgb(250, 228, 230));
        banner.setPadding(dp(10), dp(8), dp(10), dp(8));
        p.addView(banner, margen(-1, -2, 8));

        final ArrayList<String[]> filas = filasExistencias("");
        final TextView total = texto("", 12, GRIS, false);
        p.addView(total, margen(-2, -2, 6));
        final BaseAdapter adaptador = new BaseAdapter() {
            @Override public int getCount() { return filas.size(); }
            @Override public Object getItem(int i) { return filas.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup g) {
                String[] f = filas.get(i);
                long dias = Long.parseLong(f[4]);
                int color = dias >= 90 ? Color.rgb(214, 40, 40) : dias >= 30 ? Color.rgb(230, 140, 0) : Color.rgb(46, 125, 50);
                LinearLayout caja = new LinearLayout(MainActivity.this);
                caja.setPadding(0, 0, 0, dp(8));
                LinearLayout tarjeta = new LinearLayout(MainActivity.this);
                TextView cod = new TextView(MainActivity.this);
                cod.setText(f[0]);
                cod.setTextColor(Color.rgb(40, 30, 10));
                cod.setTypeface(Typeface.DEFAULT_BOLD);
                cod.setTextSize(15);
                cod.setGravity(Gravity.CENTER);
                cod.setBackgroundColor(Color.rgb(247, 167, 56));
                tarjeta.addView(cod, new LinearLayout.LayoutParams(dp(96), -1));
                TextView det = new TextView(MainActivity.this);
                det.setBackgroundColor(Color.rgb(238, 238, 243));
                det.setTextColor(Color.rgb(30, 30, 35));
                det.setTextSize(14);
                det.setPadding(dp(12), dp(8), dp(10), dp(8));
                String txt = f[1] + "\n" + (f[2].isEmpty() ? "" : "Serie: " + f[2] + "\n")
                    + "Unidades: " + f[3] + "\nAlmacén: " + Almacen.NOMBRE_ALMACEN + "\n● Antigüedad: " + dias + (dias == 1 ? " día" : " días");
                android.text.SpannableString sp = new android.text.SpannableString(txt);
                int punto = txt.lastIndexOf('●');
                sp.setSpan(new android.text.style.ForegroundColorSpan(color), punto, punto + 1, 0);
                sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, f[1].length(), 0);
                det.setText(sp);
                tarjeta.addView(det, new LinearLayout.LayoutParams(0, -2, 1f));
                caja.addView(tarjeta, new LinearLayout.LayoutParams(-1, -2));
                return caja;
            }
        };
        java.util.Collections.sort(filas, (a, b) -> {
            int ca = a[1].toLowerCase().contains("router") ? 1 : a[1].toLowerCase().contains("deco") ? 2 : a[1].toLowerCase().contains("amplificador") ? 3 : a[1].toLowerCase().contains("acometida") ? 4 : 5;
            int cb = b[1].toLowerCase().contains("router") ? 1 : b[1].toLowerCase().contains("deco") ? 2 : b[1].toLowerCase().contains("amplificador") ? 3 : b[1].toLowerCase().contains("acometida") ? 4 : 5;
            if (ca != cb) return Integer.compare(ca, cb);
            return a[0].compareTo(b[0]);
        });
        total.setText(filas.size() + " unidades · agrupadas por equipo");
        Button btnVerRep = boton("📋 Pendiente de reponer por almacén", false);
        btnVerRep.setOnClickListener(v -> verPendienteReponer());
        p.addView(btnVerRep, margen(-1, -2, 6));
        Button btnVerAv = boton("🛠️ Averiados retirados (devolver a almacén)", false);
        btnVerAv.setOnClickListener(v -> verEquiposAveriados());
        p.addView(btnVerAv, margen(-1, -2, 6));
        Button btnLimpiar = boton("🧹 Limpiar móvil (APKs y carpetas vacías)", false);
        btnLimpiar.setOnClickListener(v -> analizarYLimpiarMovil());
        p.addView(btnLimpiar, margen(-1, -2, 6));
        ListView lista = new ListView(this);
        lista.setDivider(null);
        lista.setAdapter(adaptador);
        lista.setOnItemClickListener((av, view, pos, id) -> {
            final String[] f = filas.get(pos);
            final String codEq = f[0], nomEq = f[1], serEq = f[2];
            File dirOrd = new File(android.os.Environment.getExternalStorageDirectory(), "Almacen de Rafa");
            File[] dirs = dirOrd.listFiles(File::isDirectory);
            final java.util.ArrayList<String> nOrds = new java.util.ArrayList<>();
            if (dirs != null) {
                java.util.Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File d : dirs) if (!d.getName().startsWith(".")) nOrds.add(d.getName());
            }
            if (ordenDestinoDirecto != null) {
                String ordSel = ordenDestinoDirecto;
                ordenDestinoDirecto = null;
                gastarCodigo(ordSel, codEq, serEq.isEmpty() ? null : serEq, false);
                android.widget.Toast.makeText(MainActivity.this, "✅ Añadido a orden " + ordSel, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (nOrds.isEmpty()) {
                android.widget.Toast.makeText(MainActivity.this, "No hay órdenes abiertas", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("➕ Asignar " + nomEq + (serEq.isEmpty() ? "" : " (" + serEq + ")"))
                .setItems(nOrds.toArray(new String[0]), (dlg, idx) -> {
                    String ordSel = nOrds.get(idx);
                    gastarCodigo(ordSel, codEq, serEq.isEmpty() ? null : serEq, false);
                    android.widget.Toast.makeText(MainActivity.this, "✅ Añadido a orden " + ordSel, android.widget.Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
        });
        p.addView(lista, new LinearLayout.LayoutParams(-1, 0, 1f));
        buscar.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                filas.clear();
                filas.addAll(filasExistencias(s.toString()));
                total.setText(filas.size() + " unidades · la más antigua arriba");
                adaptador.notifyDataSetChanged();
            }
        });
        contenido.addView(p, new FrameLayout.LayoutParams(-1, -1));
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

        p.addView(texto("Escanea el código corto (serie) de cada equipo, o el largo de las cajas de cable. Al terminar pulsa atrás.", 12, GRIS, false), margen(-2, -2, 6));
        LinearLayout fila = new LinearLayout(this);
        Button esc = boton("📷  Escanear y añadir", true);
        esc.setOnClickListener(v -> escanearSeguido());
        fila.addView(esc, new LinearLayout.LayoutParams(0, dp(52), 1.4f));
        Button man = boton("✎  A mano", false);
        man.setOnClickListener(v -> anadirAMano());
        LinearLayout.LayoutParams lm = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lm.leftMargin = dp(10);
        fila.addView(man, lm);
        p.addView(fila, margen(-1, -2, 8));
        LinearLayout fila2 = new LinearLayout(this);
        Button alb = boton("📥  Cargar albarán", false);
        alb.setOnClickListener(v -> cargarAlbaran());
        fila2.addView(alb, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button nas = boton("☁  Copia en el NAS", false);
        nas.setOnClickListener(v -> respaldoNas(true));
        LinearLayout.LayoutParams ln = new LinearLayout.LayoutParams(0, dp(46), 1f);
        ln.leftMargin = dp(10);
        fila2.addView(nas, ln);
        p.addView(fila2, margen(-1, -2, 8));
        Button exi = boton("📋  Existencias (por antigüedad)", false);
        exi.setOnClickListener(v -> mostrarExistencias());
        p.addView(exi, margen(-1, dp(46), 8));
        LinearLayout fila3 = new LinearLayout(this);
        Button inf = boton("📄 Informe", false);
        inf.setOnClickListener(v -> informeAlmacen());
        fila3.addView(inf, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button cua = boton("✔ Cuadre", false);
        cua.setOnClickListener(v -> cuadreAlbaranes());
        LinearLayout.LayoutParams lq = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lq.leftMargin = dp(8);
        fila3.addView(cua, lq);
        Button des3 = boton("↶ Deshacer", false);
        des3.setOnClickListener(v -> deshacer());
        LinearLayout.LayoutParams lz = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lz.leftMargin = dp(8);
        fila3.addView(des3, lz);
        p.addView(fila3, margen(-1, -2, 8));
        LinearLayout fila4 = new LinearLayout(this);
        Button rec = boton("⬇ NAS", false);
        rec.setOnClickListener(v -> recuperarNas());
        fila4.addView(rec, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button cop = boton("🛟 Copias", false);
        cop.setOnClickListener(v -> mostrarCopias());
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lc.leftMargin = dp(8);
        fila4.addView(cop, lc);
        Button his = boton("🕒 Albaranes", false);
        his.setOnClickListener(v -> mostrarHistorial());
        LinearLayout.LayoutParams lh = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lh.leftMargin = dp(8);
        fila4.addView(his, lh);
        p.addView(fila4, margen(-1, -2, 8));

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
                    boolean bajo = it.minimo > 0 && it.cantidad <= it.minimo;
                    tv.setText(it.nombre + "   ×" + it.cantidad + (bajo ? (it.cantidad == 0 ? "   ⚠ AGOTADO" : "   ⚠ POCO") : "") + "\n" + it.codigo);
                    if (bajo) tv.setTextColor(Color.rgb(255, 130, 110));
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

    private String ultMaterial = null; // último material elegido al escanear series

    private void escanearSeguido() {
        escanear(raw -> {
            if (Almacen.esEtiquetaLarga(raw)) {
                final String clave = Almacen.clave(raw);
                Almacen.Item it = almacen.buscar(clave);
                if (it != null) {
                    if (it.raws.contains(raw)) aviso("Esa caja ya estaba contada");
                    else { almacen.sumar(clave, raw); pila("ENTRA", "", clave, it.nombre, raw); aviso("➕ " + it.nombre + " · ahora hay " + it.cantidad); }
                    ultMaterial = clave;
                    escanearSeguido();
                } else {
                    pedirNombreMaterial(clave, raw);
                }
            } else if (Almacen.esSerie(raw)) {
                serieAlmacen(raw.trim().toUpperCase(Locale.ROOT));
            } else {
                aviso("No reconozco ese código. Prueba con el de rayas.");
                escanearSeguido();
            }
        }, () -> mostrarAlmacen());
    }

    // Se ha leído el número de serie de un equipo: se cuenta esa unidad y se le asigna material.
    private void serieAlmacen(final String serie) {
        Almacen.Item ya = almacen.buscarPorRaw(serie);
        if (ya != null) { aviso("Esa unidad ya estaba contada · " + ya.nombre); escanearSeguido(); return; }
        final Almacen.Item ult = ultMaterial == null ? null : almacen.buscar(ultMaterial);
        if (ult == null) { elegirMaterialSerie(serie); return; }
        new AlertDialog.Builder(this).setTitle("Serie " + serie)
            .setMessage("¿Es «" + ult.nombre + "»?")
            .setCancelable(false)
            .setPositiveButton("SÍ", (d, w) -> {
                almacen.sumar(ult.codigo, serie);
                pila("ENTRA", "", ult.codigo, ult.nombre, serie);
                aviso("➕ " + ult.nombre + " · ahora hay " + ult.cantidad);
                escanearSeguido();
            })
            .setNeutralButton("OTRO", (d, w) -> elegirMaterialSerie(serie))
            .setNegativeButton("TERMINAR", (d, w) -> mostrarAlmacen()).show();
    }

    private void elegirMaterialSerie(final String serie) {
        final ArrayList<Almacen.Item> items = almacen.lista();
        String[] nombres = new String[items.size() + 1];
        for (int i = 0; i < items.size(); i++) nombres[i] = items.get(i).nombre + "  ×" + items.get(i).cantidad;
        nombres[items.size()] = "➕ Material nuevo";
        new AlertDialog.Builder(this).setTitle("¿Qué es? · serie " + serie)
            .setCancelable(false)
            .setItems(nombres, (d, i) -> {
                if (i < items.size()) {
                    Almacen.Item it = items.get(i);
                    almacen.sumar(it.codigo, serie);
                    pila("ENTRA", "", it.codigo, it.nombre, serie);
                    ultMaterial = it.codigo;
                    aviso("➕ " + it.nombre + " · ahora hay " + it.cantidad);
                    escanearSeguido();
                } else {
                    final EditText nombre = campo("Nombre (ej. Router WiFi 7)", InputType.TYPE_CLASS_TEXT);
                    new AlertDialog.Builder(this).setTitle("Material nuevo").setView(nombre).setCancelable(false)
                        .setPositiveButton("AÑADIR", (d2, w) -> {
                            String n = nombre.getText().toString().trim();
                            if (n.isEmpty()) n = "Sin nombre";
                            String clave = "MAT-" + n.toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
                            if (almacen.buscar(clave) != null) almacen.sumar(clave, serie);
                            else almacen.crear(clave, n, serie);
                            pila("ENTRA", "", clave, n, serie);
                            ultMaterial = clave;
                            aviso("✓ Añadido al almacén");
                            escanearSeguido();
                        }).setNegativeButton("TERMINAR", (d2, w) -> mostrarAlmacen()).show();
                }
            })
            .setNegativeButton("TERMINAR", (d, w) -> mostrarAlmacen()).show();
    }

    private void pedirNombreMaterial(final String clave, final String raw) {
        final EditText nombre = campo("¿Qué material es? (ej. Deco 4K)", InputType.TYPE_CLASS_TEXT);
        nombre.setText(Almacen.nombreConocido(clave));
        new AlertDialog.Builder(this).setTitle("Material nuevo")
            .setMessage("Código del material: " + clave + (clave.equals(raw) ? "" : "\n(leído: " + raw + ")"))
            .setView(nombre)
            .setCancelable(false)
            .setPositiveButton("AÑADIR", (d, w) -> {
                almacen.crear(clave, nombre.getText().toString(), raw);
                pila("ENTRA", "", clave, nombre.getText().toString().trim(), raw);
                aviso("✓ Añadido al almacén");
                escanearSeguido();
            }).setNegativeButton("TERMINAR", (d, w) -> mostrarAlmacen()).show();
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
            .setItems(new String[]{"➕ Sumar 1", "➖ Restar 1", "✎ Cambiar nombre", "⚠ Avisar cuando quede poco", "🗑 Quitar del almacén"}, (d, i) -> {
                if (i == 0) { almacen.anadir(it.codigo, it.nombre, 1); mostrarAlmacen(); }
                else if (i == 1) { almacen.gastar(it.codigo); mostrarAlmacen(); }
                else if (i == 2) {
                    final EditText n = campo("Nombre", InputType.TYPE_CLASS_TEXT);
                    n.setText(it.nombre);
                    new AlertDialog.Builder(this).setTitle("Cambiar nombre").setView(n)
                        .setPositiveButton("GUARDAR", (d2, w) -> { almacen.renombrar(it.codigo, n.getText().toString()); mostrarAlmacen(); })
                        .setNegativeButton("CANCELAR", null).show();
                } else if (i == 3) {
                    final EditText m = campo("Avisar cuando queden… (0 = no avisar)", InputType.TYPE_CLASS_NUMBER);
                    m.setText(String.valueOf(it.minimo));
                    new AlertDialog.Builder(this).setTitle("Aviso de «" + it.nombre + "»").setView(m)
                        .setPositiveButton("GUARDAR", (d2, w) -> {
                            int q = 0;
                            try { q = Math.max(0, Integer.parseInt(m.getText().toString().trim())); } catch (Exception ignorar) { }
                            almacen.setMinimo(it.codigo, q);
                            mostrarAlmacen();
                        }).setNegativeButton("CANCELAR", null).show();
                } else {
                    new AlertDialog.Builder(this).setTitle("¿Quitar «" + it.nombre + "» del almacén?")
                        .setPositiveButton("QUITAR", (d2, w) -> { almacen.quitar(it.codigo); mostrarAlmacen(); })
                        .setNegativeButton("CANCELAR", null).show();
                }
            }).setNegativeButton("CERRAR", null).show();
    }

    // ---------- Aviso con sonido y vibración ----------
    private android.media.ToneGenerator tono;

    private void pitar(boolean ok) {
        try {
            if (tono == null) tono = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70);
            tono.startTone(ok ? android.media.ToneGenerator.TONE_PROP_ACK : android.media.ToneGenerator.TONE_PROP_NACK, ok ? 120 : 300);
            android.os.Vibrator vb = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(ok ? 60 : 250);
        } catch (Throwable ignorar) { }
    }

    // ---------- Revisar el nº de orden ----------
    private void revisarOrden(String t, TextView hint) {
        t = t.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) { hint.setText(""); return; }
        if (t.matches("WK\\d{12}")) {
            hint.setText("✓ Formato correcto (Orden WK)");
            hint.setTextColor(Color.rgb(120, 200, 140));
        } else if (t.matches("\\d{5,12}")) {
            hint.setText("✓ Formato correcto (Orden empresa)");
            hint.setTextColor(Color.rgb(120, 200, 140));
        } else {
            hint.setText("⚠ Introduce WK + 12 cifras o nº de orden de empresa (5-12 cifras).");
            hint.setTextColor(Color.rgb(255, 130, 110));
        }
    }

    // ---------- Órdenes a medias ----------
    private void ordenesAMedias() {
        StringBuilder sinFoto = new StringBuilder(), sinMat = new StringBuilder();
        File[] dirs = raiz.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.isDirectory() || d.getName().equals("Albaranes") || new File(d, "cerrada.txt").exists()) continue;
                int fotos = fotosDe(d.getName()).size();
                int mat = leerMaterial(d.getName()).size();
                if (fotos == 0 && mat > 0) sinFoto.append("• ").append(d.getName()).append("\n");
                else if (fotos > 0 && mat == 0) sinMat.append("• ").append(d.getName()).append("\n");
            }
        }
        String m = "";
        if (sinFoto.length() > 0) m += "📷 Con material pero SIN foto:\n" + sinFoto + "\n";
        if (sinMat.length() > 0) m += "📦 Con foto pero SIN material apuntado:\n" + sinMat;
        new AlertDialog.Builder(this).setTitle(m.isEmpty() ? "✔ Todas las órdenes completas" : "🧾 Órdenes a medias")
            .setMessage(m.isEmpty() ? "Todas tus órdenes tienen foto y material apuntado." : m.trim())
            .setPositiveButton("CERRAR", null).show();
    }

    private double numero(String t) {
        try { return t == null || t.isEmpty() ? 0 : Double.parseDouble(t.replace(",", ".")); } catch (Exception e) { return 0; }
    }

    // ---------- Etiquetas de las fotos (antes, después, ONT…) ----------
    private java.util.HashMap<String, String> leerEtiquetas(String orden) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        File f = new File(new File(raiz, limpiar(orden)), "etiquetas.txt");
        if (!f.exists()) return m;
        try (InputStream in = new java.io.FileInputStream(f)) {
            for (String l : new String(leerTodo(in), "UTF-8").split("\n")) {
                int k = l.indexOf('|');
                if (k > 0) m.put(l.substring(0, k), l.substring(k + 1).trim());
            }
        } catch (Throwable ignorar) { }
        return m;
    }

    private void ponerEtiqueta(String orden, File foto, String tag) {
        java.util.HashMap<String, String> m = leerEtiquetas(orden);
        if (tag == null || tag.isEmpty()) m.remove(foto.getName()); else m.put(foto.getName(), tag);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append("|").append(e.getValue()).append("\n");
        try (FileOutputStream o = new FileOutputStream(new File(new File(raiz, limpiar(orden)), "etiquetas.txt"))) { o.write(sb.toString().getBytes("UTF-8")); }
        catch (Exception e) { aviso("No se pudo guardar la etiqueta"); }
    }

    static final String[] ETIQUETAS = {"Antes", "Después", "ONT", "Roseta", "Test/Potencia", "Otra"};

    private void elegirEtiqueta(final String orden, final File foto) {
        new AlertDialog.Builder(this).setTitle("🏷 ¿Qué foto es?").setItems(ETIQUETAS, (d, i) -> {
            ponerEtiqueta(orden, foto, ETIQUETAS[i]);
            if (visor != null) visor.dismiss();
            mostrarOrden(orden);
        }).setNeutralButton("QUITAR", (d, w) -> {
            ponerEtiqueta(orden, foto, "");
            if (visor != null) visor.dismiss();
            mostrarOrden(orden);
        }).setNegativeButton("CANCELAR", null).show();
    }

    // ---------- Datos de la orden (cliente y dirección) ----------
    private String[] leerDatos(String orden) {
        File f = new File(new File(raiz, limpiar(orden)), "datos.txt");
        String[] r = {"", "", ""};
        if (!f.exists()) return r;
        try (InputStream in = new java.io.FileInputStream(f)) {
            String[] l = new String(leerTodo(in), "UTF-8").split("\n", -1);
            if (l.length > 0) r[0] = l[0].trim();
            if (l.length > 1) r[1] = l[1].trim();
            if (l.length > 2) r[2] = l[2].trim();
        } catch (Throwable ignorar) { }
        return r;
    }

    private void editarDatos(final String orden) {
        String[] actual = leerDatos(orden);
        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText cli = campo("Cliente (opcional)", InputType.TYPE_CLASS_TEXT);
        cli.setText(actual[0]);
        final EditText dir = campo("Dirección (calle, nº, localidad)", InputType.TYPE_CLASS_TEXT);
        dir.setText(actual[1]);
        final EditText met = campo("Metros de cable usados (opcional)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        met.setText(actual[2]);
        caja.addView(cli, margen(-1, -2, 4));
        caja.addView(dir, margen(-1, -2, 4));
        caja.addView(met, margen(-1, -2, 8));
        new AlertDialog.Builder(this).setTitle("✏ Datos de la orden " + orden).setView(caja)
            .setPositiveButton("GUARDAR", (d, w) -> {
                File dirOrden = new File(raiz, limpiar(orden));
                dirOrden.mkdirs();
                String txt = cli.getText().toString().replace("\n", " ").trim() + "\n" + dir.getText().toString().replace("\n", " ").trim() + "\n" + met.getText().toString().replace(",", ".").trim() + "\n";
                try (FileOutputStream o = new FileOutputStream(new File(dirOrden, "datos.txt"))) { o.write(txt.getBytes("UTF-8")); }
                catch (Exception e) { aviso("No se pudo guardar"); return; }
                aviso("✓ Datos guardados");
                mostrarOrden(orden);
            }).setNegativeButton("CANCELAR", null).show();
    }

    // ---------- Cerrar / reabrir una orden ----------
    private void cerrarOrden(final String orden, boolean cerrar) {
        if (cerrar && !new File(new File(raiz, limpiar(orden)), "cerrada.txt").exists()) {
            ArrayList<String> faltan = new ArrayList<>();
            if (fotosDe(orden).isEmpty()) faltan.add("Ninguna foto");
            if (leerMaterial(orden).isEmpty()) faltan.add("Material sin apuntar");
            java.util.Collection<String> tags = leerEtiquetas(orden).values();
            for (String t : new String[]{"ONT", "Roseta", "Test/Potencia"}) if (!tags.contains(t)) faltan.add("Foto «" + t + "» (etiqueta 🏷)");
            if (!faltan.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("✅ Antes de cerrar la orden " + orden)
                    .setMessage("Te falta:\n\n• " + android.text.TextUtils.join("\n• ", faltan) + "\n\n¿La cierras igualmente?")
                    .setPositiveButton("CERRAR IGUAL", (d, w) -> cerrarDeVerdad(orden, true))
                    .setNegativeButton("VOLVER", null).show();
                return;
            }
        }
        cerrarDeVerdad(orden, cerrar);
    }

    private void cerrarDeVerdad(String orden, boolean cerrar) {
        File m = new File(new File(raiz, limpiar(orden)), "cerrada.txt");
        if (cerrar) {
            try (FileOutputStream o = new FileOutputStream(m)) { o.write(formatoLargo(System.currentTimeMillis()).getBytes("UTF-8")); }
            catch (Exception e) { aviso("No se pudo cerrar"); return; }
            aviso("✔ Orden " + orden + " cerrada");
        } else {
            m.delete();
            aviso("Orden " + orden + " reabierta");
        }
        mostrarOrden(orden);
    }

    // ---------- Pedido de material ----------
    private void pedidoMaterial() {
        ArrayList<Almacen.Item> bajos = almacen.bajos();
        if (bajos.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("🛒 Pedido de material")
                .setMessage("Ahora mismo no hay nada con poco stock.\n\nPara que un material salga aquí, ponle un mínimo en el almacén.")
                .setPositiveButton("OK", null).show();
            return;
        }
        final StringBuilder sb = new StringBuilder("🛒 PEDIDO DE MATERIAL · " + new SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(new Date()) + "\n\n");
        for (Almacen.Item it : bajos) {
            int pedir = Math.max(it.minimo * 2 - it.cantidad, 1);
            sb.append("• ").append(it.nombre).append(" (").append(it.codigo).append("): quedan ").append(it.cantidad)
                .append(" → pedir ").append(pedir).append("\n");
        }
        new AlertDialog.Builder(this).setTitle("🛒 Pedido de material").setMessage(sb.toString())
            .setPositiveButton("ENVIAR", (d, w) -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, sb.toString());
                startActivity(Intent.createChooser(i, "Enviar pedido de material"));
            }).setNegativeButton("CERRAR", null).show();
    }

    // ---------- Paquete de una orden (zip con fotos + material + datos) ----------
    private void paqueteOrden(final String orden) {
        aviso("Preparando el paquete…");
        hilos.execute(() -> {
            try {
                File dirT = new File(getCacheDir(), "tmp");
                dirT.mkdirs();
                final String nombreZip = "Orden_" + limpiar(orden).replaceAll("[^A-Za-z0-9_-]", "_") + ".zip";
                File zip = new File(dirT, nombreZip);
                try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new FileOutputStream(zip))) {
                    File dirOrden = new File(raiz, limpiar(orden));
                    File[] fs = dirOrden.listFiles();
                    if (fs != null) for (File f : fs) {
                        String n = f.getName().toLowerCase(Locale.ROOT);
                        if (!f.isFile() || !(n.endsWith(".jpg") || n.equals("material.txt") || n.equals("datos.txt") || n.equals("etiquetas.txt"))) continue;
                        z.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
                        try (InputStream in = new java.io.FileInputStream(f)) {
                            byte[] b = new byte[65536];
                            int k;
                            while ((k = in.read(b)) != -1) z.write(b, 0, k);
                        }
                        z.closeEntry();
                    }
                    z.putNextEntry(new java.util.zip.ZipEntry("resumen.txt"));
                    z.write(resumenOrden(orden).getBytes("UTF-8"));
                    z.closeEntry();
                }
                final Uri uri = Uri.parse("content://com.kuli.ordenes.fotos/" + Uri.encode(nombreZip));
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.putExtra(Intent.EXTRA_TEXT, resumenOrden(orden));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Enviar paquete de la orden " + orden));
                });
            } catch (Throwable t) {
                android.util.Log.e("KULI_ORDENES", "Paquete", t);
                runOnUiThread(() -> aviso("No se pudo preparar el paquete"));
            }
        });
    }

    // ---------- Buscar orden ----------
    private void buscarOrden() {
        final EditText q = campo("WK, orden, dirección, cliente o material", InputType.TYPE_CLASS_TEXT);
        q.setText(filtro);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("🔎 Buscar orden").setView(q)
            .setPositiveButton("BUSCAR", (d, w) -> { filtro = q.getText().toString().trim(); mostrarInicio(); })
            .setNegativeButton("CANCELAR", null);
        if (!filtro.isEmpty()) b.setNeutralButton("QUITAR FILTRO", (d, w) -> { filtro = ""; mostrarInicio(); });
        b.show();
    }

    // ---------- Plantillas de material ----------
    private File archivoPlantillas() { return new File(raiz, "plantillas.txt"); }

    private ArrayList<String[]> leerPlantillas() {
        ArrayList<String[]> r = new ArrayList<>();
        File f = archivoPlantillas();
        if (!f.exists()) return r;
        try (InputStream in = new java.io.FileInputStream(f)) {
            for (String l : new String(leerTodo(in), "UTF-8").split("\n")) {
                String[] p = l.trim().split(";;", 2);
                if (p.length == 2 && !p[0].isEmpty() && !p[1].isEmpty()) r.add(p);
            }
        } catch (Throwable ignorar) { }
        return r;
    }

    private void guardarPlantillas(ArrayList<String[]> l) {
        StringBuilder sb = new StringBuilder();
        for (String[] p : l) sb.append(p[0]).append(";;").append(p[1]).append("\n");
        try (FileOutputStream o = new FileOutputStream(archivoPlantillas())) { o.write(sb.toString().getBytes("UTF-8")); }
        catch (Exception e) { aviso("No se pudo guardar la plantilla"); }
    }

    private void plantillas() {
        final ArrayList<String[]> lista = leerPlantillas();
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("📋 Plantillas de material");
        if (lista.isEmpty()) {
            b.setMessage("Todavía no hay ninguna.\n\nPulsa NUEVA: copia el material de una orden que ya hayas hecho (por ejemplo una instalación normal) y la guarda con un nombre.");
        } else {
            String[] nombres = new String[lista.size()];
            for (int i = 0; i < nombres.length; i++) nombres[i] = lista.get(i)[0];
            b.setItems(nombres, (d, i) -> usarPlantilla(lista.get(i)));
            b.setNeutralButton("BORRAR", (d, w) -> borrarPlantilla());
        }
        b.setPositiveButton("NUEVA", (d, w) -> nuevaPlantilla()).setNegativeButton("CERRAR", null).show();
    }

    private void borrarPlantilla() {
        final ArrayList<String[]> lista = leerPlantillas();
        if (lista.isEmpty()) return;
        String[] nombres = new String[lista.size()];
        for (int i = 0; i < nombres.length; i++) nombres[i] = lista.get(i)[0];
        new AlertDialog.Builder(this).setTitle("¿Cuál borro?").setItems(nombres, (d, i) -> {
            lista.remove(i);
            guardarPlantillas(lista);
            aviso("Plantilla borrada");
        }).setNegativeButton("CANCELAR", null).show();
    }

    private void nuevaPlantilla() {
        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText ord = campo("Orden de la que copio el material", InputType.TYPE_CLASS_TEXT);
        ord.setText(ultOrden);
        final EditText nom = campo("Nombre (ej. Instalación fibra)", InputType.TYPE_CLASS_TEXT);
        caja.addView(ord, margen(-1, -2, 4));
        caja.addView(nom, margen(-1, -2, 8));
        new AlertDialog.Builder(this).setTitle("Nueva plantilla").setView(caja)
            .setPositiveButton("GUARDAR", (d, w) -> {
                String o = ord.getText().toString().trim().toUpperCase(Locale.ROOT);
                String n = nom.getText().toString().replace(";", "").replace("\n", " ").trim();
                if (o.isEmpty() || n.isEmpty()) { aviso("Falta la orden o el nombre"); return; }
                ArrayList<String> claves = new ArrayList<>();
                for (String l : leerMaterial(o)) {
                    String[] p = l.split(" \\| ");
                    if (p.length < 3) continue;
                    if (p[1].trim().startsWith("RETIRADO") || p[1].trim().startsWith("(no estaba")) continue;
                    claves.add((p.length >= 4 ? p[3] : p[2]).trim());
                }
                if (claves.isEmpty()) { aviso("Esa orden no tiene material apuntado"); return; }
                ArrayList<String[]> lista = leerPlantillas();
                for (int i = lista.size() - 1; i >= 0; i--) if (lista.get(i)[0].equalsIgnoreCase(n)) lista.remove(i);
                lista.add(new String[]{n, android.text.TextUtils.join(",", claves)});
                guardarPlantillas(lista);
                aviso("✓ Plantilla «" + n + "» guardada (" + claves.size() + " piezas)");
            }).setNegativeButton("CANCELAR", null).show();
    }

    private void usarPlantilla(final String[] t) {
        final String orden = ultOrden.trim().toUpperCase(Locale.ROOT);
        if (!datosValidos(orden)) return;
        ultOrden = orden;
        final java.util.LinkedHashMap<String, Integer> cuenta = new java.util.LinkedHashMap<>();
        for (String c : t[1].split(",")) {
            c = c.trim();
            if (c.isEmpty()) continue;
            Integer n = cuenta.get(c);
            cuenta.put(c, n == null ? 1 : n + 1);
        }
        StringBuilder sb = new StringBuilder("Orden " + orden + "\n\nSe gastará del almacén:\n");
        for (java.util.Map.Entry<String, Integer> e : cuenta.entrySet()) {
            Almacen.Item it = almacen.buscar(e.getKey());
            sb.append("• ").append(it == null ? e.getKey() + " (no está en el almacén)" : it.nombre + (it.raws.isEmpty() ? "" : " (lo escaneas tú)")).append(" ×").append(e.getValue()).append("\n");
        }
        new AlertDialog.Builder(this).setTitle("📋 " + t[0]).setMessage(sb.toString())
            .setPositiveButton("APLICAR", (d, w) -> {
                int hechos = 0;
                ArrayList<String> aparte = new ArrayList<>();
                for (java.util.Map.Entry<String, Integer> e : cuenta.entrySet()) {
                    String clave = e.getKey();
                    Almacen.Item it = almacen.buscar(clave);
                    if (it == null) { aparte.add(clave + " (no está en el almacén)"); continue; }
                    if (!it.raws.isEmpty()) { aparte.add(it.nombre + " ×" + e.getValue() + " → escanéalo"); continue; }
                    for (int k = 0; k < e.getValue(); k++) {
                        Almacen.Item cur = almacen.buscar(clave);
                        if (cur == null || cur.cantidad <= 0) { aparte.add(it.nombre + " (se ha acabado)"); break; }
                        String nombre = cur.nombre;
                        almacen.gastar(clave, null);
                        pila("USA", orden, clave, nombre, "");
                        apuntarGasto(clave, orden);
                        anotarMaterial(orden, clave, nombre, clave);
                        hechos++;
                    }
                }
                StringBuilder r = new StringBuilder("✓ Apuntadas " + hechos + " piezas en la orden " + orden + ".");
                if (!aparte.isEmpty()) {
                    r.append("\n\nFalta por hacer:\n");
                    for (String a : aparte) r.append("• ").append(a).append("\n");
                }
                new AlertDialog.Builder(this).setTitle("Plantilla aplicada").setMessage(r.toString()).setCancelable(false)
                    .setPositiveButton("OK", (d2, w2) -> mostrarOrden(orden)).show();
            }).setNegativeButton("CANCELAR", null).show();
    }

    // ---------- Informe del mes (completo, para enviar) ----------
    private void informeMes(final int atras) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.MONTH, -atras);
        final String clave = new SimpleDateFormat("MM/yyyy", Locale.US).format(cal.getTime());
        final String titulo = new SimpleDateFormat("MMMM yyyy", new Locale("es", "ES")).format(cal.getTime());
        java.util.HashMap<String, Integer> cuenta = new java.util.HashMap<>();
        StringBuilder ords = new StringBuilder();
        ArrayList<String> retirados = new ArrayList<>();
        int nOrd = 0, total = 0, sinFoto = 0;
        double metrosTotal = 0;
        java.util.TreeMap<String, Integer> porDia = new java.util.TreeMap<>();
        ArrayList<Resumen> todas = listarOrdenes();
        Collections.sort(todas, (a, b) -> a.nombre.compareTo(b.nombre));
        for (Resumen r : todas) {
            int k = 0;
            String diaOrden = null;
            for (String l : leerMaterial(r.nombre)) {
                String[] p = l.split(" \\| ");
                if (p.length < 2) continue;
                String fecha = p[0].trim();
                if (fecha.length() < 10 || !fecha.substring(3, 10).equals(clave)) continue;
                String nom = p[1].trim();
                if (nom.startsWith("RETIRADO")) { retirados.add(r.nombre + ": " + nom + (p.length > 2 ? " " + p[2].trim() : "")); continue; }
                if (nom.startsWith("(no estaba")) nom = "Sin identificar";
                Integer c = cuenta.get(nom);
                cuenta.put(nom, c == null ? 1 : c + 1);
                total++; k++;
                if (diaOrden == null) diaOrden = fecha.substring(0, 5);
            }
            if (k == 0) continue;
            nOrd++;
            metrosTotal += r.metros;
            Integer cd = porDia.get(diaOrden);
            porDia.put(diaOrden, cd == null ? 1 : cd + 1);
            if (r.fotos == 0) sinFoto++;
            ords.append("• ").append(r.nombre).append(r.datos.isEmpty() ? "" : " — " + r.datos).append(r.cerrada ? " ✔" : "").append(": ").append(k).append(k == 1 ? " material" : " materiales")
                .append(r.fotos == 0 ? " (SIN FOTO)" : " · " + r.fotos + (r.fotos == 1 ? " foto" : " fotos")).append("\n");
        }
        ArrayList<java.util.Map.Entry<String, Integer>> lista = new ArrayList<>(cuenta.entrySet());
        Collections.sort(lista, (a, b) -> b.getValue() - a.getValue());
        final StringBuilder sb = new StringBuilder("INFORME DE " + titulo.toUpperCase(new Locale("es", "ES")) + "\n");
        sb.append("Órdenes: ").append(nOrd).append("\nUnidades de material: ").append(total).append("\n");
        if (!porDia.isEmpty()) {
            String mejor = ""; int mx = 0;
            for (java.util.Map.Entry<String, Integer> e : porDia.entrySet()) if (e.getValue() > mx) { mx = e.getValue(); mejor = e.getKey(); }
            sb.append("Días con órdenes: ").append(porDia.size()).append("\nMedia: ").append(String.format(Locale.ROOT, "%.1f", nOrd / (double) porDia.size())).append(" órdenes/día\nMejor día: ").append(mejor).append(" (").append(mx).append(")\n");
        }
        if (metrosTotal > 0) sb.append("Cable usado: ").append(String.format(Locale.ROOT, "%.0f", metrosTotal)).append(" m\n");
        if (sinFoto > 0) sb.append("Órdenes sin foto: ").append(sinFoto).append("\n");
        if (!retirados.isEmpty()) sb.append("Equipos retirados: ").append(retirados.size()).append("\n");
        sb.append("\nMATERIAL USADO\n");
        for (java.util.Map.Entry<String, Integer> e : lista) sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        if (lista.isEmpty()) sb.append("(nada apuntado)\n");
        sb.append("\nÓRDENES\n").append(ords.length() == 0 ? "(ninguna)\n" : ords.toString());
        if (!retirados.isEmpty()) {
            sb.append("\nRETIRADOS\n");
            for (String s : retirados) sb.append("• ").append(s).append("\n");
        }
        new AlertDialog.Builder(this).setTitle("📨 Informe " + titulo).setMessage(sb.toString())
            .setPositiveButton("ENVIAR", (d, w) -> enviarInforme(titulo, sb.toString()))
            .setNeutralButton(atras == 0 ? "MES ANTERIOR" : "ESTE MES", (d, w) -> informeMes(atras == 0 ? 1 : 0))
            .setNegativeButton("CERRAR", null).show();
    }

    private void enviarInforme(final String titulo, final String texto) {
        new AlertDialog.Builder(this).setTitle("¿Cómo lo envías?").setItems(new String[]{"💬 Como texto (WhatsApp, correo)", "📄 Como PDF"}, (d, i) -> {
            if (i == 0) {
                Intent it = new Intent(Intent.ACTION_SEND);
                it.setType("text/plain");
                it.putExtra(Intent.EXTRA_SUBJECT, "Informe " + titulo);
                it.putExtra(Intent.EXTRA_TEXT, texto);
                startActivity(Intent.createChooser(it, "Enviar informe del mes"));
            } else informePdf(titulo, texto);
        }).setNegativeButton("CANCELAR", null).show();
    }

    private void informePdf(final String titulo, final String texto) {
        hilos.execute(() -> {
            try {
                String limpio = texto.replaceAll("[\\uD800-\\uDFFF]", "").replace("✔", "(cerrada)").replace("→", "->").replace("•", "-");
                android.graphics.pdf.PdfDocument doc = new android.graphics.pdf.PdfDocument();
                Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
                pt.setTypeface(Typeface.MONOSPACE);
                pt.setTextSize(10f);
                pt.setColor(Color.BLACK);
                Paint ptT = new Paint(pt);
                ptT.setTextSize(14f);
                ptT.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                ArrayList<String> lineas = new ArrayList<>();
                for (String l : limpio.split("\n")) {
                    while (l.length() > 84) { lineas.add(l.substring(0, 84)); l = "   " + l.substring(84); }
                    lineas.add(l);
                }
                int pagina = 1, y = 0, i = 0;
                android.graphics.pdf.PdfDocument.Page pg = null;
                Canvas cv = null;
                while (i < lineas.size()) {
                    if (pg == null) {
                        pg = doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pagina).create());
                        cv = pg.getCanvas();
                        y = 50;
                    }
                    cv.drawText(lineas.get(i), 40, y, i == 0 ? ptT : pt);
                    y += i == 0 ? 22 : 14;
                    i++;
                    if (y > 800) { doc.finishPage(pg); pg = null; pagina++; }
                }
                if (pg != null) doc.finishPage(pg);
                File dirT = new File(getCacheDir(), "tmp");
                dirT.mkdirs();
                final String nombre = "Informe_" + titulo.replaceAll("[^A-Za-z0-9_-]", "_") + ".pdf";
                try (FileOutputStream o = new FileOutputStream(new File(dirT, nombre))) { doc.writeTo(o); }
                doc.close();
                final Uri uri = Uri.parse("content://com.kuli.ordenes.fotos/" + Uri.encode(nombre));
                runOnUiThread(() -> {
                    Intent it = new Intent(Intent.ACTION_SEND);
                    it.setType("application/pdf");
                    it.putExtra(Intent.EXTRA_STREAM, uri);
                    it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(it, "Enviar informe en PDF"));
                });
            } catch (Throwable t) {
                android.util.Log.e("KULI_ORDENES", "Informe PDF", t);
                runOnUiThread(() -> aviso("No se pudo crear el PDF"));
            }
        });
    }

    // ---------- Resumen del mes ----------
    private void resumenMes(final int atras) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.MONTH, -atras);
        final String clave = new SimpleDateFormat("MM/yyyy", Locale.US).format(cal.getTime());
        final String titulo = new SimpleDateFormat("MMMM yyyy", new Locale("es", "ES")).format(cal.getTime());
        java.util.HashMap<String, Integer> cuenta = new java.util.HashMap<>();
        int ordenes = 0, retirados = 0, total = 0;
        File[] dirs = raiz.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                boolean hay = false;
                for (String l : leerMaterial(d.getName())) {
                    String[] p = l.split(" \\| ");
                    if (p.length < 2) continue;
                    String fecha = p[0].trim();
                    if (fecha.length() < 10 || !fecha.substring(3, 10).equals(clave)) continue;
                    hay = true;
                    String nom = p[1].trim();
                    if (nom.startsWith("RETIRADO")) { retirados++; continue; }
                    if (nom.startsWith("(no estaba")) nom = "Sin identificar";
                    Integer c = cuenta.get(nom);
                    cuenta.put(nom, c == null ? 1 : c + 1);
                    total++;
                }
                if (hay) ordenes++;
            }
        }
        ArrayList<java.util.Map.Entry<String, Integer>> lista = new ArrayList<>(cuenta.entrySet());
        Collections.sort(lista, (a, b) -> b.getValue() - a.getValue());
        final StringBuilder sb = new StringBuilder("📊 " + titulo + "\n\nÓrdenes con material: " + ordenes + "\nUnidades usadas: " + total + "\n");
        if (retirados > 0) sb.append("Equipos retirados: ").append(retirados).append("\n");
        sb.append("\n");
        for (java.util.Map.Entry<String, Integer> e : lista) sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        if (lista.isEmpty()) sb.append("No hay material apuntado en este mes.");
        new AlertDialog.Builder(this).setTitle("📊 " + titulo).setMessage(sb.toString().substring(sb.toString().indexOf("\n\n") + 2))
            .setPositiveButton("CERRAR", null)
            .setNeutralButton(atras == 0 ? "MES ANTERIOR" : "ESTE MES", (d, w) -> resumenMes(atras == 0 ? 1 : 0))
            .setNegativeButton("ENVIAR", (d, w) -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, sb.toString());
                startActivity(Intent.createChooser(i, "Enviar resumen del mes"));
            }).show();
    }

    // ---------- Recuperar la copia del NAS ----------
    private void recuperarNas() {
        new AlertDialog.Builder(this).setTitle("Recuperar del NAS")
            .setMessage("Trae del NAS las órdenes, fotos y albaranes que falten en este móvil.\n\n«Solo lo que falta» no toca tu almacén actual. «Traer todo» lo sustituye por el que hay en la copia del NAS.")
            .setPositiveButton("SOLO LO QUE FALTA", (d, w) -> bajarNas(false))
            .setNeutralButton("TRAER TODO", (d, w) -> bajarNas(true))
            .setNegativeButton("CANCELAR", null).show();
    }

    private void bajarNas(final boolean reemplazar) {
        aviso("Recuperando del NAS…");
        hilos.execute(() -> {
            try {
                if (!Respaldo.hayWifi(this)) throw new java.io.IOException("No hay wifi");
                final int n = Respaldo.bajar(this, raiz, reemplazar);
                runOnUiThread(() -> { almacen.cargar(); aviso("✓ Recuperados " + n + " archivos"); mostrarAlmacen(); });
            } catch (Throwable t) {
                android.util.Log.e("KULI_ORDENES", "Recuperando del NAS", t);
                runOnUiThread(() -> aviso("No se pudo recuperar. ¿Estás en el wifi de casa y hay copia en el NAS?"));
            }
        });
    }

    // ---------- Deshacer ----------
    private final ArrayList<String[]> acciones = new ArrayList<>(); // {tipo, orden, codigo, nombre, raw}

    private void pila(String tipo, String orden, String codigo, String nombre, String raw) {
        acciones.add(new String[]{tipo, orden == null ? "" : orden, codigo, nombre == null ? "" : nombre, raw == null ? "" : raw});
        while (acciones.size() > 50) acciones.remove(0);
    }

    private void deshacer() {
        if (acciones.isEmpty()) { aviso("No hay nada que deshacer"); return; }
        final String[] a = acciones.get(acciones.size() - 1);
        String txt = a[0].equals("USA") ? "Devolver al almacén: " + a[3]
            : a[0].equals("BAJA") ? "Quitar del almacén (equipo retirado): " + a[3]
            : "Quitar del almacén: " + a[3];
        if (!a[4].isEmpty()) txt += "\n" + a[4];
        if (a[0].equals("USA") || a[0].equals("BAJA")) txt += "\n(orden " + a[1] + ")";
        new AlertDialog.Builder(this).setTitle("¿Deshacer lo último?").setMessage(txt)
            .setPositiveButton("DESHACER", (d, w) -> {
                acciones.remove(acciones.size() - 1);
                String raw = a[4].isEmpty() ? null : a[4];
                if (a[0].equals("USA")) {
                    if (almacen.buscar(a[2]) == null) almacen.crear(a[2], a[3], raw); else almacen.sumar(a[2], raw);
                    quitarUltimaLinea(a[1]);
                    quitarGasto(a[2], a[1]);
                } else {
                    almacen.gastar(a[2], raw);
                    if (a[0].equals("BAJA")) quitarUltimaLinea(a[1]);
                }
                aviso("↶ Deshecho");
                if (ordenAbierta != null) mostrarOrden(ordenAbierta);
                else if (enAlmacen) mostrarAlmacen();
                else mostrarInicio();
            }).setNegativeButton("CANCELAR", null).show();
    }

    private void quitarUltimaLinea(String orden) {
        try {
            File f = new File(new File(raiz, limpiar(orden)), "material.txt");
            if (!f.exists()) return;
            String txt;
            try (InputStream in = new java.io.FileInputStream(f)) { txt = new String(leerTodo(in), "UTF-8"); }
            ArrayList<String> ls = new ArrayList<>();
            for (String l : txt.split("\n")) if (!l.trim().isEmpty()) ls.add(l);
            if (ls.isEmpty()) return;
            ls.remove(ls.size() - 1);
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write((ls.isEmpty() ? "" : android.text.TextUtils.join("\n", ls) + "\n").getBytes("UTF-8"));
            }
            ultNota = notaAuto(orden);
        } catch (Throwable t) { android.util.Log.e("KULI_ORDENES", "Quitando línea", t); }
    }

    // ---------- Equipo retirado (BAJA): vuelve al almacén ----------
    private void elegirMaterial(final String titulo, final Runnable cancelar, final java.util.function.BiConsumer<String, String> elegido) {
        final ArrayList<Almacen.Item> items = almacen.lista();
        String[] nombres = new String[items.size() + 1];
        for (int i = 0; i < items.size(); i++) nombres[i] = items.get(i).nombre + "  ×" + items.get(i).cantidad;
        nombres[items.size()] = "➕ Material nuevo";
        new AlertDialog.Builder(this).setTitle(titulo).setCancelable(false)
            .setItems(nombres, (d, i) -> {
                if (i < items.size()) {
                    elegido.accept(items.get(i).codigo, items.get(i).nombre);
                } else {
                    final EditText nombre = campo("Nombre (ej. Router WiFi 7)", InputType.TYPE_CLASS_TEXT);
                    new AlertDialog.Builder(this).setTitle("Material nuevo").setView(nombre).setCancelable(false)
                        .setPositiveButton("AÑADIR", (d2, w) -> {
                            String n = nombre.getText().toString().trim();
                            if (n.isEmpty()) n = "Sin nombre";
                            elegido.accept("MAT-" + n.toUpperCase(Locale.ROOT).replaceAll("\\s+", "-"), n);
                        }).setNegativeButton("CANCELAR", (d2, w) -> cancelar.run()).show();
                }
            })
            .setNegativeButton("CANCELAR", (d, w) -> cancelar.run()).show();
    }

    private void retirarSeguido(final String orden) {
        escanear(raw -> {
            if (!Almacen.esSerie(raw)) { aviso("Escanea el código corto (serie) del equipo retirado"); retirarSeguido(orden); return; }
            final String serie = raw.trim().toUpperCase(Locale.ROOT);
            if (almacen.buscarPorRaw(serie) != null) { aviso("Ese equipo ya está en el almacén"); retirarSeguido(orden); return; }
            elegirMaterial("Equipo retirado · serie " + serie, () -> mostrarOrden(orden), (clave, nombre) -> {
                if (almacen.buscar(clave) != null) almacen.sumar(clave, serie); else almacen.crear(clave, nombre, serie);
                anotarMaterial(orden, serie, "RETIRADO · " + nombre);
                pila("BAJA", orden, clave, nombre, serie);
                aviso("↩ " + nombre + " vuelve al almacén");
                retirarSeguido(orden);
            });
        }, () -> mostrarOrden(orden));
    }

    // ---------- Buscar un equipo ----------
    private void buscarEquipo() {
        final EditText q = campo("Serie, código o nombre", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this).setTitle("🔎 Buscar equipo").setView(q)
            .setPositiveButton("BUSCAR", (d, w) -> resultadoBusqueda(q.getText().toString().trim()))
            .setNeutralButton("ESCANEAR", (d, w) -> escanear(raw -> resultadoBusqueda(raw.trim()), null))
            .setNegativeButton("CANCELAR", null).show();
    }

    private void resultadoBusqueda(String t) {
        if (t.isEmpty()) return;
        String b = t.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Almacen.Item it : almacen.lista()) {
            boolean ok = it.codigo.toLowerCase(Locale.ROOT).contains(b) || it.nombre.toLowerCase(Locale.ROOT).contains(b);
            for (String r : it.raws) if (r.toLowerCase(Locale.ROOT).contains(b)) ok = true;
            if (ok && n < 30) { sb.append("📦 En el almacén: ").append(it.nombre).append(" ×").append(it.cantidad).append("\n\n"); n++; }
        }
        File[] dirs = raiz.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                for (String l : leerMaterial(d.getName())) {
                    if (l.toLowerCase(Locale.ROOT).contains(b) && n < 30) {
                        sb.append("🧾 Orden ").append(d.getName()).append("\n").append(l).append("\n\n");
                        n++;
                    }
                }
            }
        }
        new AlertDialog.Builder(this).setTitle("«" + t + "»")
            .setMessage(n == 0 ? "No lo encuentro ni en el almacén ni en ninguna orden." : sb.toString().trim())
            .setPositiveButton("CERRAR", null).show();
    }

    // ---------- Resumen de la orden (se envía con las fotos) ----------
    private String resumenOrden(String orden) {
        StringBuilder sb = new StringBuilder("Orden " + orden + "\n");
        String[] dd = leerDatos(orden);
        if (!dd[0].isEmpty()) sb.append("Cliente: ").append(dd[0]).append("\n");
        if (!dd[1].isEmpty()) sb.append("Dirección: ").append(dd[1]).append("\n");
        if (!dd[2].isEmpty()) sb.append("Cable: ").append(dd[2]).append(" m\n");
        ArrayList<File> fs = fotosDe(orden);
        sb.append("Fotos: ").append(fs.size()).append("\n");
        ArrayList<String> u = leerMaterial(orden);
        if (u.isEmpty()) sb.append("Material: (ninguno apuntado)");
        else sb.append("Material:\n").append(android.text.TextUtils.join("\n", u));
        return sb.toString();
    }

    // ---------- Informe del almacén ----------
    private void informeAlmacen() {
        StringBuilder sb = new StringBuilder("📦 ALMACÉN · " + formatoLargo(System.currentTimeMillis()) + "\n\n");
        for (Almacen.Item it : almacen.lista()) {
            sb.append("• ").append(it.nombre).append(" ×").append(it.cantidad).append("  (").append(it.codigo).append(")\n");
            ArrayList<String> series = new ArrayList<>();
            for (String r : it.raws) if (r.length() <= 20) series.add(r);
            if (!series.isEmpty()) sb.append("   ").append(android.text.TextUtils.join(", ", series)).append("\n");
        }
        sb.append("\nTotal: ").append(almacen.total()).append(" unidades\n");
        ArrayList<Almacen.Item> bajos = almacen.bajos();
        if (!bajos.isEmpty()) {
            sb.append("\n⚠ HAY QUE PEDIR:\n");
            for (Almacen.Item it : bajos)
                sb.append("• ").append(it.nombre).append(": quedan ").append(it.cantidad).append(" (mínimo ").append(it.minimo).append(")\n");
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Enviar informe del almacén"));
    }

    // ---------- Cuadre con los albaranes ----------
    private void cuadreAlbaranes() {
        File dir = new File(raiz, "Albaranes");
        File[] fs = dir.listFiles((x, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (fs == null || fs.length == 0) { aviso("No hay albaranes en la carpeta Albaranes"); return; }
        StringBuilder usado = new StringBuilder();
        File[] dirs = raiz.listFiles();
        if (dirs != null) for (File d : dirs) if (d.isDirectory()) usado.append(android.text.TextUtils.join("\n", leerMaterial(d.getName()))).append("\n");
        String todo = usado.toString().toLowerCase(Locale.ROOT);
        int total = 0, bien = 0;
        StringBuilder falta = new StringBuilder();
        for (File f : fs) {
            try {
                String txt;
                try (InputStream in = new java.io.FileInputStream(f)) { txt = new String(leerTodo(in), "UTF-8").replace("\uFEFF", ""); }
                java.util.HashSet<String> usados = new java.util.HashSet<>();
                ArrayList<String[]> series = new ArrayList<>();
                for (String l : txt.split("\\r?\\n")) {
                    l = l.trim();
                    if (l.isEmpty() || l.startsWith("#")) continue;
                    String[] p = l.split(";");
                    for (int i = 0; i < p.length; i++) p[i] = p[i].trim();
                    if (p[0].equals("U") && p.length > 1) usados.add(p[1].toUpperCase(Locale.ROOT));
                    else if (p[0].equals("S") && p.length > 3) series.add(p);
                }
                java.util.HashSet<String> vistos = new java.util.HashSet<>();
                String todoLimpio = todo.replace("-", "").replace(" ", "");
                for (String[] p : series) {
                    String serie = p[3].toUpperCase(Locale.ROOT);
                    String serieLimpia = serie.replace("-", "").replace(" ", "");
                    if (!vistos.add(serieLimpia)) continue;
                    if (usados.contains(serie) || usados.contains(serieLimpia)) continue;
                    total++;
                    String cod8 = (p.length > 1 && p[1].length() >= 8) ? p[1].substring(0, 8).toLowerCase(Locale.ROOT)
                            : (serieLimpia.length() >= 8 ? serieLimpia.substring(0, 8).toLowerCase(Locale.ROOT) : "");
                    boolean ok = almacen.buscarPorRaw(serie) != null
                            || todoLimpio.contains(serieLimpia.toLowerCase(Locale.ROOT))
                            || (!cod8.isEmpty() && todoLimpio.contains(cod8));
                    if (ok) bien++;
                    else falta.append("• ").append(p[2]).append(" · ").append(serie).append("\n");
                }
            } catch (Throwable t) { android.util.Log.e("KULI_ORDENES", "Cuadre", t); }
        }
        new AlertDialog.Builder(this).setTitle(falta.length() == 0 ? "✔ Todo cuadra" : "⚠ Hay equipos sin localizar")
            .setMessage("Equipos con serie de los albaranes: " + total + "\nLocalizados (en el almacén o usados en una orden): " + bien
                + (falta.length() == 0 ? "" : "\n\nNo aparecen en el almacén ni en ninguna orden:\n" + falta))
            .setPositiveButton("CERRAR", null).show();
    }

    // QUITAR-KULI
    private void quitarLineaMaterial(final String orden, final String linea) {
        boolean contada = !linea.contains("(no estaba");
        new AlertDialog.Builder(this).setTitle("\u00bfQuitar esta l\u00ednea?")
            .setMessage(linea + (contada ? "\n\nOjo: esta pieza se rest\u00f3 del almac\u00e9n y quitar la l\u00ednea NO la devuelve. Si hace falta, s\u00famala en Almac\u00e9n." : ""))
            .setPositiveButton("QUITAR", (d, w) -> {
                try {
                    File f = new File(new File(raiz, limpiar(orden)), "material.txt");
                    boolean quitada = false;
                    StringBuilder sb = new StringBuilder();
                    for (String l : leerMaterial(orden)) {
                        if (!quitada && l.equals(linea)) { quitada = true; continue; }
                        sb.append(l).append("\n");
                    }
                    try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(sb.toString().getBytes("UTF-8")); }
                    ultNota = notaAuto(orden);
                    aviso("\u2713 L\u00ednea quitada");
                } catch (Exception e) { aviso("No se pudo quitar la l\u00ednea"); }
                mostrarOrden(orden);
            })
            .setNegativeButton("NO", null).show();
    }

    // ---------- Material usado en una orden ----------
    private void materialUsado(final String orden) {
        new AlertDialog.Builder(this).setTitle("Material usado en la orden " + orden)
            .setItems(new String[]{"📷 Escanear el código", "📋 Elegir del almacén"}, (d, i) -> {
                if (i == 0) gastarSeguido(orden);
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

    // SEGUIDO-KULI
    private boolean yaApuntado(String orden, String codigo) {
        for (String l : leerMaterial(orden)) {
            String[] q = l.split(" \\| ");
            if (q.length >= 3 && q[2].trim().equalsIgnoreCase(codigo.trim())) return true;
        }
        return false;
    }

    private void gastarSeguido(final String orden) {
        escanear(raw0 -> {
            final String raw = raw0;
            if (yaApuntado(orden, raw)) {
                new AlertDialog.Builder(this).setTitle("Ya lo apuntaste")
                    .setMessage("El c\u00f3digo " + raw + " ya est\u00e1 en esta orden.\n\n\u00bfLo apunto otra vez?")
                    .setCancelable(false)
                    .setPositiveButton("OTRA VEZ", (d, w) -> gastarLectura(orden, raw))
                    .setNegativeButton("NO", (d, w) -> gastarSeguido(orden)).show();
                return;
            }
            gastarLectura(orden, raw);
        }, () -> mostrarOrden(orden));
    }

    private void gastarLectura(final String orden, final String raw) {
            if (Almacen.esEtiquetaLarga(raw)) {
                gastarCodigo(orden, Almacen.clave(raw), raw, true);
            } else if (Almacen.esSerie(raw)) {
                final String serie = raw.trim().toUpperCase(Locale.ROOT);
                Almacen.Item it = almacen.buscarPorRaw(serie);
                if (it != null) {
                    // FIFO: si hay otra unidad del mismo material más antigua, se avisa.
                    long mia = almacen.fechaDe(it, serie), masVieja = mia;
                    String serieVieja = null;
                    for (String r : it.raws) {
                        long f = almacen.fechaDe(it, r);
                        if (f < masVieja) { masVieja = f; serieVieja = r; }
                    }
                    long difDias = (mia - masVieja) / 86400000L;
                    if (serieVieja != null && difDias >= 1) {
                        final Almacen.Item itF = it;
                        final String vieja = serieVieja;
                        new AlertDialog.Builder(this).setTitle("⚠ Hay uno más antiguo")
                            .setMessage("Del mismo material (" + it.nombre + ") tienes uno con " + difDias
                                + " día(s) más de antigüedad:\n\nSerie " + vieja + "\n\nEs obligatorio instalar primero el más antiguo. ¿Usas este igualmente?")
                            .setCancelable(false)
                            .setPositiveButton("USAR ESTE", (d, w) -> gastarCodigo(orden, itF.codigo, serie, true))
                            .setNegativeButton("ESCANEAR OTRO", (d, w) -> gastarSeguido(orden)).show();
                    } else {
                        gastarCodigo(orden, it.codigo, serie, true);
                    }
                } else {
                    new AlertDialog.Builder(this).setTitle("No estaba en el almacén")
                        .setMessage("La unidad con serie " + serie + " no estaba contada.\n\n¿La apunto igualmente como usada en esta orden?")
                        .setCancelable(false)
                        .setPositiveButton("APUNTAR", (d, w) -> { anotarMaterial(orden, serie, "(no estaba en el almacén)"); gastarSeguido(orden); })
                        .setNegativeButton("NO", (d, w) -> gastarSeguido(orden)).show();
                }
            } else {
                aviso("No reconozco ese código. Prueba con el de rayas.");
                gastarSeguido(orden);
            }
    }

    private void gastarCodigo(final String orden, final String codigo) { gastarCodigo(orden, codigo, null, false); }

    private void gastarCodigo(final String orden, final String clave, final String raw, final boolean seguir) {
        Almacen.Item it = almacen.buscar(clave);
        if (it == null) {
            new AlertDialog.Builder(this).setTitle("No está en el almacén")
                .setMessage("El código " + clave + " no estaba apuntado en el almacén.\n\n¿Lo apunto igualmente como usado en esta orden?")
                .setCancelable(false)
                .setPositiveButton("APUNTAR", (d, w) -> {
                    anotarMaterial(orden, clave, "(no estaba en el almacén)");
                    if (seguir) gastarSeguido(orden); else mostrarOrden(orden);
                })
                .setNegativeButton("NO", (d, w) -> { if (seguir) gastarSeguido(orden); else mostrarOrden(orden); }).show();
            return;
        }
        String nombre = it.nombre; int minAviso = it.minimo;
        int quedan = almacen.gastar(clave, raw);
        registrarPendienteReponer(orden, it.codigo, nombre, raw);
        pila("USA", orden, clave, nombre, raw == null ? "" : raw);
        apuntarGasto(clave, orden);
        anotarMaterial(orden, raw != null ? raw : clave, nombre, clave);
        final Runnable seguirR = () -> { if (seguir) gastarSeguido(orden); else mostrarOrden(orden); };
        Almacen.Item despues = almacen.buscar(clave);
        if (despues != null && minAviso > 0 && quedan <= minAviso) {
            new AlertDialog.Builder(this).setTitle(quedan == 0 ? "⚠ Se ha acabado" : "⚠ Queda poco")
                .setMessage(nombre + ": " + (quedan == 0 ? "ya no queda ninguno." : "quedan solo " + quedan + " (aviso a partir de " + minAviso + ")."))
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> seguirR.run()).show();
        } else {
            avisoLargo("✓ " + nombre + "\nCódigo " + clave + (raw != null ? " · Serie " + raw : "") + "\nQuedan " + quedan);
            seguirR.run();
        }
    }

    private void anotarMaterial(String orden, String codigo, String nombre) { anotarMaterial(orden, codigo, nombre, null); }

    private void avisoLargo(String s) { android.widget.Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void anotarMaterial(String orden, String codigo, String nombre, String codMaterial) {
        File dir = new File(raiz, limpiar(orden));
        dir.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(dir, "material.txt"), true)) {
            o.write((formatoLargo(System.currentTimeMillis()) + " | " + nombre + " | " + codigo + (codMaterial != null && !codMaterial.equals(codigo) ? " | " + codMaterial : "") + "\n").getBytes("UTF-8"));
        } catch (Exception e) { aviso("No se pudo apuntar el material"); }
        ultNota = notaAuto(orden);
    }

    // Nombres del material escaneado en la orden, para la casilla «Material» y la foto: «Router WiFi 7 ×2, Deco H2».
    private String notaAuto(String orden) {
        java.util.LinkedHashMap<String, Integer> m = new java.util.LinkedHashMap<>();
        for (String l : leerMaterial(orden)) {
            String[] p = l.split(" \\| ");
            if (p.length < 2) continue;
            String n = p[1].trim();
            if (n.startsWith("RETIRADO") || n.startsWith("(no estaba")) continue;
            Integer c = m.get(n);
            m.put(n, c == null ? 1 : c + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append(" ×").append(e.getValue());
        }
        return sb.toString();
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
    
    // V224: pide una ubicación nueva (espera hasta 8 s). Se llama desde un hilo, nunca desde la pantalla.
    @android.annotation.SuppressLint("MissingPermission")
    private android.location.Location ubicacionNueva(final android.location.LocationManager lm) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        final android.location.Location[] res = {null};
        final java.util.concurrent.CountDownLatch cierre = new java.util.concurrent.CountDownLatch(1);
        final android.location.LocationListener oyente = new android.location.LocationListener() {
            public void onLocationChanged(android.location.Location l) { res[0] = l; cierre.countDown(); }
            public void onStatusChanged(String p, int s, Bundle e) { }
            public void onProviderEnabled(String p) { }
            public void onProviderDisabled(String p) { }
        };
        try {
            runOnUiThread(() -> {
                try {
                    if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER))
                        lm.requestSingleUpdate(android.location.LocationManager.NETWORK_PROVIDER, oyente, android.os.Looper.getMainLooper());
                    if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))
                        lm.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, oyente, android.os.Looper.getMainLooper());
                } catch (Throwable t) { cierre.countDown(); }
            });
            cierre.await(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignorar) { }
        try { runOnUiThread(() -> { try { lm.removeUpdates(oyente); } catch (Throwable ignorar) { } }); } catch (Throwable ignorar) { }
        return res[0];
    }

    private String dirPantalla = ""; // V227
    private String obtenerDireccionGpsActual() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return "";
            }
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return "";
            android.location.Location loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            if (loc == null) loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            if (loc == null) loc = ubicacionNueva(lm); // V224: si el móvil no tiene ninguna guardada, se pide una ahora
            if (loc == null) return "";
            android.location.Geocoder geo = new android.location.Geocoder(this, java.util.Locale.getDefault());
            String coord = "📍 " + String.format(java.util.Locale.US, "%.5f, %.5f", loc.getLatitude(), loc.getLongitude());
            try { // V226: si no hay servicio de direcciones, queda al menos la posición
                java.util.List<android.location.Address> dirs = geo.getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                if (dirs != null && !dirs.isEmpty()) {
                    android.location.Address a = dirs.get(0);
                    String calle = a.getThoroughfare() != null ? a.getThoroughfare() : "";
                    String num = a.getSubThoroughfare() != null ? " " + a.getSubThoroughfare() : "";
                    String locName = a.getLocality() != null ? ", " + a.getLocality() : "";
                    String res = (calle + num + locName).trim();
                    if (res.isEmpty() && a.getMaxAddressLineIndex() >= 0 && a.getAddressLine(0) != null) res = a.getAddressLine(0).trim();
                    if (!res.isEmpty()) return "📍 " + res;
                }
            } catch (Throwable ignorar) { }
            return coord;
        } catch (Throwable ignored) {}
        return "";
    }

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
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 301);
            aviso("Da permiso a la cámara y vuelve a pulsar");
            return;
        }
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
        if (cod == PIDE_ESCANER) {
            Consumer<String> l = escAlLeer;
            Runnable t = escAlTerminar;
            String raw = datos == null ? null : datos.getStringExtra("raw");
            if (res == RESULT_OK && raw != null && !raw.trim().isEmpty() && l != null) recibirLectura(raw.trim(), l);
            else if (res == RESULT_FIRST_USER && l != null) escanearGoogle(l, t);
            else if (res == 2 && l != null) escribirSerie(l, t); // V223
            else if (t != null) t.run();
            return;
        }
        if (cod == PIDE_PDF) {
            if (res == RESULT_OK && datos != null && datos.getData() != null) importarPdf(datos.getData());
            return;
        }
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
        final String orden = ordenPendiente, nota0 = notaPendiente;
        aviso("Guardando…");
        hilos.execute(() -> {
            String nota = nota0;
            if (!fechaDeLaFoto) { // V226: la calle se añade siempre, aunque ya haya material escrito
                String dirGps = obtenerDireccionGpsActual();
            if (dirGps.isEmpty()) dirGps = dirPantalla; // V227
                if (!dirGps.isEmpty()) nota = nota.isEmpty() ? dirGps : nota + "\n" + dirGps;
            }
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
                try { copiarArchivo(salida, respaldoDe(salida)); } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Respaldo de la foto", e); }
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
        String[] dsel = leerDatos(orden);
        if (!dsel[1].isEmpty()) lineas.add(dsel[1]);
        if (nota != null && !nota.isEmpty()) for (String ln : nota.split("\n")) if (!ln.trim().isEmpty()) lineas.add(ln.trim()); // V226
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
    private void aviso(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        if (s.startsWith("Esa ") || s.startsWith("Ese ") || s.startsWith("No ") || s.startsWith("Escanea")) pitar(false);
        else if (s.startsWith("➕") || s.startsWith("✓") || s.startsWith("↩") || s.startsWith("↶")) pitar(true);
    }
}
