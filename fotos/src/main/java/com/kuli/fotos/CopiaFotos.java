package com.kuli.fotos;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

// Motor de copia: busca fotos y vídeos del móvil que aún no están en el NAS y los copia ordenados.
final class CopiaFotos {
    static final String CARPETA = "FOTOS KULI";
    static final String IP_DEFECTO = "192.168.1.46";

    static final class Item {
        Uri uri; String nombre; long tam; long fecha; String origen; String clave; boolean video;
    }
    interface Progreso { void alAvanzar(int hechas, int total, String nombre); }
    static final class Resultado {
        int copiadas, omitidas, fallos;
        boolean abortado;
        String ultimoError = "";
    }
    private interface Tarea<T> { T ejecutar(DiskShare disco) throws Exception; }

    static String ip(Context c) {
        String ip = c.getSharedPreferences("fotos", Context.MODE_PRIVATE).getString("nas_ip", IP_DEFECTO);
        return (ip == null || ip.trim().isEmpty()) ? IP_DEFECTO : ip.trim();
    }

    // ---- Qué falta por copiar (solo mira el móvil, no el NAS) ----
    static ArrayList<Item> pendientes(Context c) {
        SharedPreferences sp = c.getSharedPreferences("fotos", Context.MODE_PRIVATE);
        Set<String> vistas = sp.getStringSet("copiadas", new HashSet<String>());
        ArrayList<Item> r = new ArrayList<>();
        consultar(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, vistas, r, false);
        consultar(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, vistas, r, false);
        Collections.sort(r, (a, b) -> Long.compare(b.fecha, a.fecha));
        return r;
    }

    // Todas las fotos y vídeos del móvil (copiados o no)
    static ArrayList<Item> todas(Context c) {
        ArrayList<Item> r = new ArrayList<>();
        consultar(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, new HashSet<String>(), r, false);
        consultar(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, new HashSet<String>(), r, false);
        return r;
    }

    static void olvidar(Context c) {
        c.getSharedPreferences("fotos", Context.MODE_PRIVATE).edit().remove("copiadas").apply();
    }

    private static void consultar(Context ctx, Uri base, boolean video, Set<String> vistas, List<Item> salida, boolean soloCopiadas) {
        String[] cols = {"_id", "_display_name", "_size", "date_added", "_data", "datetaken"};
        try (Cursor c = ctx.getContentResolver().query(base, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                String nombre = c.getString(1);
                long tam = c.getLong(2);
                long anadida = c.getLong(3) * 1000L;
                String ruta = c.getString(4) == null ? "" : c.getString(4);
                long tomada = c.getLong(5);
                if (nombre == null || tam <= 0) continue;
                String rl = ruta.toLowerCase(Locale.ROOT);
                if (rl.contains("/.trashed") || rl.contains("/.thumbnails") || rl.contains("/.pending")
                    || nombre.startsWith(".")) continue;
                String clave = (video ? "v" : "i") + id + ":" + tam;
                if (vistas.contains(clave) != soloCopiadas) continue;
                Item it = new Item();
                it.uri = android.content.ContentUris.withAppendedId(base, id);
                it.nombre = nombre; it.tam = tam; it.video = video; it.clave = clave;
                it.fecha = tomada > 0 ? tomada : anadida;
                it.origen = origenDe(rl);
                salida.add(it);
            }
        } catch (Exception e) { android.util.Log.e("KULI_FOTOS", "Consulta", e); }
    }

    private static String origenDe(String ruta) {
        if (ruta.contains("whatsapp")) return "WhatsApp";
        if (ruta.contains("telegram")) return "Telegram";
        if (ruta.contains("screenshot") || ruta.contains("captura")) return "Capturas";
        if (ruta.contains("/dcim/camera") || ruta.contains("/camera")) return "Camara";
        if (ruta.contains("instagram")) return "Instagram";
        if (ruta.contains("/download")) return "Descargas";
        return "Otras";
    }

    static void guardar(Context c, List<String> nuevas) {
        if (nuevas.isEmpty()) return;
        SharedPreferences sp = c.getSharedPreferences("fotos", Context.MODE_PRIVATE);
        HashSet<String> todas = new HashSet<>(sp.getStringSet("copiadas", new HashSet<String>()));
        todas.addAll(nuevas);
        sp.edit().putStringSet("copiadas", todas).apply();
    }

    // ---- Copia ----
    static Resultado copiar(final Context ctx, final Progreso p, final AtomicBoolean cancelar) throws Throwable {
        final ArrayList<Item> lista = pendientes(ctx);
        final Resultado r = new Resultado();
        if (lista.isEmpty()) return r;
        return conNas(ctx, disco -> hacer(ctx, disco, lista, p, cancelar, r));
    }

    private static Resultado hacer(Context ctx, DiskShare disco, ArrayList<Item> lista, Progreso p,
                                   AtomicBoolean cancelar, Resultado r) throws Exception {
        r.copiadas = 0; r.omitidas = 0; r.fallos = 0; r.abortado = false;
        HashSet<String> creadas = new HashSet<>();
        ArrayList<String> nuevas = new ArrayList<>();
        if (!disco.folderExists(CARPETA)) disco.mkdir(CARPETA);
        int seguidos = 0;
        try {
            for (int i = 0; i < lista.size(); i++) {
                if (cancelar != null && cancelar.get()) break;
                Item it = lista.get(i);
                if (p != null) p.alAvanzar(i, lista.size(), it.nombre);
                String dirAnio = dirAnio(it);
                String dirMes = dirMes(it);
                String dir = dirOrigen(it);
                String destino = destinoDe(it);
                try {
                    asegurar(disco, creadas, dirAnio);
                    asegurar(disco, creadas, dirMes);
                    asegurar(disco, creadas, dir);
                    if (disco.fileExists(destino)) {
                        r.omitidas++;
                    } else {
                        try {
                            long n = copiarUno(ctx, disco, it.uri, destino);
                            if (n <= 0) throw new java.io.IOException("archivo vacío");
                        } catch (Exception e) {
                            try { disco.rm(destino); } catch (Exception ignorar) { }
                            throw e;
                        }
                        r.copiadas++;
                    }
                    nuevas.add(it.clave);
                    seguidos = 0;
                } catch (Exception e) {
                    r.fallos++;
                    r.ultimoError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    android.util.Log.e("KULI_FOTOS", "Fallo con " + it.nombre, e);
                    if (++seguidos >= 3) { r.abortado = true; break; }
                }
                if (nuevas.size() >= 20) { guardar(ctx, nuevas); nuevas.clear(); }
            }
        } finally {
            guardar(ctx, nuevas);
        }
        if (p != null) p.alAvanzar(lista.size(), lista.size(), "");
        return r;
    }


    // ---- Rutas en el NAS ----
    private static String dirAnio(Item it) {
        Calendar cal = new GregorianCalendar();
        cal.setTimeInMillis(it.fecha);
        return CARPETA + "\\" + String.format(Locale.US, "%04d", cal.get(Calendar.YEAR));
    }
    private static String dirMes(Item it) {
        Calendar cal = new GregorianCalendar();
        cal.setTimeInMillis(it.fecha);
        return dirAnio(it) + "\\" + String.format(Locale.US, "%02d", cal.get(Calendar.MONTH) + 1);
    }
    private static String dirOrigen(Item it) { return dirMes(it) + "\\" + it.origen; }
    private static String destinoDe(Item it) {
        return dirOrigen(it) + "\\" + it.nombre.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ---- Liberar espacio: solo lo que está comprobado en el NAS y tiene más de 'dias' días ----
    static final class Borrables {
        final ArrayList<Item> items = new ArrayList<>();
        long bytes;
        int fotos, videos;
    }

    static Borrables verificadas(final Context ctx, final int dias) throws Throwable {
        SharedPreferences sp = ctx.getSharedPreferences("fotos", Context.MODE_PRIVATE);
        Set<String> vistas = sp.getStringSet("copiadas", new HashSet<String>());
        final ArrayList<Item> todas = new ArrayList<>();
        consultar(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, vistas, todas, true);
        consultar(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, vistas, todas, true);
        final long limite = System.currentTimeMillis() - dias * 24L * 60 * 60 * 1000;
        final ArrayList<Item> viejas = new ArrayList<>();
        for (Item it : todas) if (it.fecha < limite) viejas.add(it);
        final Borrables b = new Borrables();
        if (viejas.isEmpty()) return b;
        return conNas(ctx, disco -> {
            for (Item it : viejas) {
                try {
                    String d = destinoDe(it);
                    if (disco.fileExists(d)
                        && disco.getFileInformation(d).getStandardInformation().getEndOfFile() == it.tam) {
                        b.items.add(it); b.bytes += it.tam;
                        if (it.video) b.videos++; else b.fotos++;
                    }
                } catch (Exception e) { android.util.Log.e("KULI_FOTOS", "Comprobando " + it.nombre, e); }
            }
            return b;
        });
    }

    // Devuelve {borradas, fallidas}
    static int[] borrar(Context ctx, List<Item> items) {
        int ok = 0, mal = 0;
        for (Item it : items) {
            try {
                if (ctx.getContentResolver().delete(it.uri, null, null) > 0) ok++; else mal++;
            } catch (Exception e) { mal++; android.util.Log.e("KULI_FOTOS", "Borrando " + it.nombre, e); }
        }
        return new int[]{ok, mal};
    }

    private static void asegurar(DiskShare disco, Set<String> creadas, String ruta) {
        if (creadas.add(ruta) && !disco.folderExists(ruta)) disco.mkdir(ruta);
    }

    private static long copiarUno(Context ctx, DiskShare disco, Uri uri, String ruta) throws Exception {
        long total = 0;
        try (InputStream origen = ctx.getContentResolver().openInputStream(uri)) {
            if (origen == null) throw new java.io.IOException("No se puede leer el archivo");
            try (File destino = disco.openFile(ruta,
                    EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                 OutputStream salida = destino.getOutputStream()) {
                byte[] buffer = new byte[131072];
                int leidos;
                while ((leidos = origen.read(buffer)) != -1) {
                    salida.write(buffer, 0, leidos);
                    total += leidos;
                }
                salida.flush();
            }
        }
        return total;
    }

    // Conecta con el NAS probando invitado/anónimo y dos versiones de SMB.
    private static <T> T conNas(Context ctx, Tarea<T> tarea) throws Throwable {
        SmbConfig[] configs = {
            SmbConfig.createDefaultConfig(),
            SmbConfig.builder().withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
                .withSigningRequired(false).build()
        };
        AuthenticationContext[] cuentas = { AuthenticationContext.guest(), AuthenticationContext.anonymous() };
        Throwable ultimo = null;
        for (SmbConfig cfg : configs) {
            for (AuthenticationContext cuenta : cuentas) {
                try (SMBClient cliente = new SMBClient(cfg);
                     Connection conexion = cliente.connect(ip(ctx))) {
                    Session sesion = conexion.authenticate(cuenta);
                    try (DiskShare disco = (DiskShare) sesion.connectShare("Public")) {
                        return tarea.ejecutar(disco);
                    }
                } catch (Throwable e) {
                    ultimo = e;
                    android.util.Log.e("KULI_FOTOS", "Intento fallido", e);
                }
            }
        }
        throw ultimo != null ? ultimo : new java.io.IOException("Sin conexión con el NAS");
    }
}
