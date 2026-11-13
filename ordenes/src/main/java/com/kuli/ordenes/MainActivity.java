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
        File nueva = new File(Environment.getExternalStorageDirectory(), "Almacen de Rafa");
        File vieja = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "KULI ORDENES");
        if (!nueva.exists() && vieja.exists()) vieja.renameTo(nueva); // pasa lo que ya tenías a la carpeta nueva
        raiz = (nueva.exists() || nueva.mkdirs()) ? nueva : vieja;
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

    private void respaldoNas(final boolean manual) {
        if (manual) aviso("Copiando al NAS…");
        hilos.execute(() -> {
            try {
                if (!Respaldo.hayWifi(this)) throw new java.io.IOException("No hay wifi");
                final int n = Respaldo.subir(this, raiz);
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

        final EditText nota = campo("Material (se rellena solo al escanear)", InputType.TYPE_CLASS_TEXT);
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
        ArrayList<Almacen.Item> bajos = almacen.bajos();
        if (!bajos.isEmpty()) {
            StringBuilder sb = new StringBuilder("⚠ Queda poco: ");
            for (int k = 0; k < bajos.size(); k++) sb.append(k > 0 ? ", " : "").append(bajos.get(k).nombre).append(" (").append(bajos.get(k).cantidad).append(")");
            TextView av = texto(sb.toString(), 13, Color.rgb(255, 130, 110), true);
            av.setPadding(dp(4), dp(4), dp(4), dp(4));
            p.addView(av);
        }


        final android.content.SharedPreferences prefs = getSharedPreferences("ordenes", MODE_PRIVATE);
        final boolean plegada = prefs.getBoolean("plegada", false);
        final ArrayList<Resumen> ordenes = listarOrdenes();
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
        total.setText(filas.size() + " unidades · la más antigua arriba");
        ListView lista = new ListView(this);
        lista.setDivider(null);
        lista.setAdapter(adaptador);
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
        if (t.matches("WK\\d{12}")) { hint.setText("✓ Formato correcto"); hint.setTextColor(Color.rgb(120, 200, 140)); }
        else { hint.setText("⚠ Las órdenes son WK + 12 cifras. Revisa que esté bien escrito."); hint.setTextColor(Color.rgb(255, 130, 110)); }
    }

    // ---------- Órdenes a medias ----------
    private void ordenesAMedias() {
        StringBuilder sinFoto = new StringBuilder(), sinMat = new StringBuilder();
        File[] dirs = raiz.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.isDirectory() || d.getName().equals("Albaranes")) continue;
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
                for (String[] p : series) {
                    String serie = p[3].toUpperCase(Locale.ROOT);
                    if (usados.contains(serie)) continue;
                    total++;
                    if (almacen.buscarPorRaw(serie) != null || todo.contains(serie.toLowerCase(Locale.ROOT))) bien++;
                    else falta.append("• ").append(p[2]).append(" · ").append(serie).append("\n");
                }
            } catch (Throwable t) { android.util.Log.e("KULI_ORDENES", "Cuadre", t); }
        }
        new AlertDialog.Builder(this).setTitle(falta.length() == 0 ? "✔ Todo cuadra" : "⚠ Hay equipos sin localizar")
            .setMessage("Equipos con serie de los albaranes: " + total + "\nLocalizados (en el almacén o usados en una orden): " + bien
                + (falta.length() == 0 ? "" : "\n\nNo aparecen en el almacén ni en ninguna orden:\n" + falta))
            .setPositiveButton("CERRAR", null).show();
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

    private void gastarSeguido(final String orden) {
        escanear(raw -> {
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
        }, () -> mostrarOrden(orden));
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
        String nombre = it.nombre;
        int quedan = almacen.gastar(clave, raw);
        pila("USA", orden, clave, nombre, raw == null ? "" : raw);
        apuntarGasto(clave, orden);
        anotarMaterial(orden, raw != null ? raw : clave, nombre, clave);
        final Runnable seguirR = () -> { if (seguir) gastarSeguido(orden); else mostrarOrden(orden); };
        Almacen.Item despues = almacen.buscar(clave);
        if (despues != null && despues.minimo > 0 && quedan <= despues.minimo) {
            new AlertDialog.Builder(this).setTitle(quedan == 0 ? "⚠ Se ha acabado" : "⚠ Queda poco")
                .setMessage(nombre + ": " + (quedan == 0 ? "ya no queda ninguno." : "quedan solo " + quedan + " (aviso a partir de " + despues.minimo + ")."))
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> seguirR.run()).show();
        } else {
            avisoLargo("✓ " + nombre + "\nCódigo " + clave + (raw != null ? " · Serie " + raw : "") + "\nQuedan " + quedan);
            seguirR.run();
        }
    }

    private void anotarMaterial(String orden, String codigo, String nombre) { anotarMaterial(orden, codigo, nombre, null); }

    private void avisoLargo(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

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
    private void aviso(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        if (s.startsWith("Esa ") || s.startsWith("Ese ") || s.startsWith("No ") || s.startsWith("Escanea")) pitar(false);
        else if (s.startsWith("➕") || s.startsWith("✓") || s.startsWith("↩") || s.startsWith("↶")) pitar(true);
    }
}
