package com.kuli.ordenes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

// El almacén: lista de materiales (código, nombre, cantidad) guardada en un archivo del móvil.
final class Almacen {
    static final class Item {
        String codigo, nombre;
        int cantidad, minimo; // minimo: avisar cuando queden esas o menos (0 = no avisar)
        long fecha;
        ArrayList<String> raws = new ArrayList<>(); // códigos completos de las cajas ya contadas
        java.util.HashMap<String, Long> fechas = new java.util.HashMap<>(); // cuándo entró cada unidad con serie
    }

    static final String NOMBRE_ALMACEN = "TESA I+M MALAGA";

    // Fecha en que entró una unidad (o, si no tiene serie, la del material).
    long fechaDe(Item it, String raw) {
        Long f = raw == null ? null : it.fechas.get(raw);
        return f != null ? f : it.fecha;
    }

    private final File archivo;
    private final ArrayList<Item> items = new ArrayList<>();

    Almacen(File carpeta) {
        archivo = new File(carpeta, "almacen.json");
        cargar();
    }

    synchronized void cargar() {
        items.clear();
        try {
            if (!archivo.exists()) { restaurarUltimaSiHay(); return; }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(archivo)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            }
            JSONArray a = new JSONArray(new String(o.toByteArray(), "UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Item it = new Item();
                it.codigo = j.getString("codigo");
                it.nombre = j.optString("nombre", it.codigo);
                it.cantidad = j.optInt("cantidad", 1);
                it.fecha = j.optLong("fecha", 0);
                it.minimo = j.optInt("minimo", 0);
                JSONArray rr = j.optJSONArray("raws");
                if (rr != null) for (int k = 0; k < rr.length(); k++) it.raws.add(rr.getString(k));
                JSONObject ff = j.optJSONObject("fechas");
                if (ff != null) {
                    java.util.Iterator<String> ks = ff.keys();
                    while (ks.hasNext()) { String k = ks.next(); it.fechas.put(k, ff.optLong(k, it.fecha)); }
                }
                for (String r : it.raws) if (!it.fechas.containsKey(r)) it.fechas.put(r, it.fecha > 0 ? it.fecha : System.currentTimeMillis());
                items.add(it);
            }
        } catch (Exception e) {
            android.util.Log.e("KULI_ORDENES", "Leyendo almacén", e);
            items.clear();
            // el archivo está roto: se aparta y se vuelve a la última copia buena
            try { archivo.renameTo(new File(archivo.getParentFile(), "almacen_roto_" + marca() + ".json")); } catch (Exception ignorar) { }
            restaurarUltimaSiHay();
        }
    }

    // ---- Copias de seguridad ----
    private static String marca() { return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date()); }

    File carpetaCopias() { return new File(archivo.getParentFile(), "Copias"); }

    synchronized ArrayList<File> copias() {
        ArrayList<File> r = new ArrayList<>();
        File[] fs = carpetaCopias().listFiles((x, n) -> n.startsWith("almacen_") && n.endsWith(".json"));
        if (fs != null) for (File f : fs) r.add(f);
        Collections.sort(r, (a, b) -> b.getName().compareTo(a.getName()));
        return r;
    }

    // Cuenta las unidades de una copia (para enseñarlo en la lista).
    int unidadesDe(File f) {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            }
            JSONArray a = new JSONArray(new String(o.toByteArray(), "UTF-8"));
            int t = 0;
            for (int i = 0; i < a.length(); i++) t += a.getJSONObject(i).optInt("cantidad", 0);
            return t;
        } catch (Exception e) { return -1; }
    }

    private void copiarArchivo(File de, File a) throws Exception {
        a.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(de); FileOutputStream o = new FileOutputStream(a)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
        }
    }

    // Guarda una copia del almacén tal como estaba antes de cambiarlo (como mucho una cada 3 minutos, y siempre si se va a vaciar).
    private void copiaAntesDeGuardar(boolean vaciando) {
        try {
            if (!archivo.exists() || archivo.length() < 3) return;
            ArrayList<File> cs = copias();
            if (!vaciando && !cs.isEmpty() && System.currentTimeMillis() - cs.get(0).lastModified() < 3 * 60 * 1000L) return;
            copiarArchivo(archivo, new File(carpetaCopias(), "almacen_" + marca() + ".json"));
            cs = copias();
            for (int i = 40; i < cs.size(); i++) cs.get(i).delete();
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Copia del almacén", e); }
    }

    // Devuelve el almacén a una copia. Antes guarda una copia de lo actual.
    synchronized boolean restaurar(File copia) {
        try {
            copiaAntesDeGuardar(true);
            copiarArchivo(copia, archivo);
            cargar();
            return true;
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Restaurando", e); return false; }
    }

    private void restaurarUltimaSiHay() {
        try {
            for (File c : copias()) {
                if (unidadesDe(c) <= 0) continue;
                copiarArchivo(c, archivo);
                cargarSinRestaurar();
                return;
            }
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Restaurando última copia", e); }
    }

    private void cargarSinRestaurar() {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(archivo)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
            }
            JSONArray a = new JSONArray(new String(o.toByteArray(), "UTF-8"));
            items.clear();
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Item it = new Item();
                it.codigo = j.getString("codigo");
                it.nombre = j.optString("nombre", it.codigo);
                it.cantidad = j.optInt("cantidad", 1);
                it.fecha = j.optLong("fecha", 0);
                it.minimo = j.optInt("minimo", 0);
                JSONArray rr = j.optJSONArray("raws");
                if (rr != null) for (int k = 0; k < rr.length(); k++) it.raws.add(rr.getString(k));
                JSONObject ff = j.optJSONObject("fechas");
                if (ff != null) {
                    java.util.Iterator<String> ks = ff.keys();
                    while (ks.hasNext()) { String k = ks.next(); it.fechas.put(k, ff.optLong(k, it.fecha)); }
                }
                for (String r : it.raws) if (!it.fechas.containsKey(r)) it.fechas.put(r, it.fecha > 0 ? it.fecha : System.currentTimeMillis());
                items.add(it);
            }
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Leyendo copia", e); items.clear(); }
    }

    synchronized void guardar() {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject j = new JSONObject();
                j.put("codigo", it.codigo);
                j.put("nombre", it.nombre);
                j.put("cantidad", it.cantidad);
                j.put("fecha", it.fecha);
                j.put("minimo", it.minimo);
                JSONArray rr = new JSONArray();
                JSONObject ff = new JSONObject();
                for (String r : it.raws) {
                    rr.put(r);
                    Long f = it.fechas.get(r);
                    if (f == null) { f = System.currentTimeMillis(); it.fechas.put(r, f); }
                    ff.put(r, f.longValue());
                }
                j.put("raws", rr);
                j.put("fechas", ff);
                a.put(j);
            }
            archivo.getParentFile().mkdirs();
            copiaAntesDeGuardar(items.isEmpty());
            File tmp = new File(archivo.getParentFile(), "almacen.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(a.toString().getBytes("UTF-8"));
                o.getFD().sync();
            }
            if (!tmp.renameTo(archivo)) {
                try (FileOutputStream o = new FileOutputStream(archivo)) { o.write(a.toString().getBytes("UTF-8")); }
                tmp.delete();
            }
        } catch (Exception e) {
            android.util.Log.e("KULI_ORDENES", "Guardando almacén", e);
        }
    }

    // Lee lo que devuelve el escáner. Las etiquetas de acometida (cables) llevan un DataMatrix cuyo texto
    // mezcla código, fabricante y serie: aquí se saca el número de serie de la unidad.
    private static final String[] FABRICANTES = {"PRYSMIAN", "COMMSCOPE", "TELNET", "ELECTROSON"};

    String limpiarLectura(String raw) {
        if (raw == null) return null;
        String r = raw.trim();
        if (r.isEmpty()) return r;
        String mayus = r.toUpperCase(java.util.Locale.ROOT);
        // 1) Electroson: S/N 26/009103
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<!\\d)(\\d{2}/\\d{5,7})(?!\\d)").matcher(mayus);
        boolean tieneFab = false;
        for (String f : FABRICANTES) if (mayus.contains(f)) tieneFab = true;
        if (m.find() && (tieneFab || mayus.contains("S/N") || mayus.length() > 12)) return m.group(1);
        // 2) Prysmian / CommScope / Telnet: código + fabricante + serie
        if (tieneFab) {
            String t = mayus;
            for (String f : FABRICANTES) t = t.replace(f, " ");
            java.util.regex.Matcher c = java.util.regex.Pattern.compile("0053\\d{4}|(?<!\\d)53\\d{4}(?!\\d)").matcher(t);
            String resto = t;
            if (c.find()) resto = t.substring(0, c.start()) + " " + t.substring(c.end());
            String mejor = null;
            for (String tok : resto.split("[^0-9A-Z]+")) {
                if (tok.length() < 6 || !tok.matches(".*\\d.*")) continue;
                if (mejor == null || tok.length() > mejor.length()) mejor = tok;
            }
            if (mejor == null) {   // el texto venía sin separadores: 01 + serie, ej. "01 260002700138"
                String junto = resto.replaceAll("[^0-9A-Z]", "");
                if (junto.length() >= 8) mejor = junto;
            }
            if (mejor != null) return mejor;
        }
        // 3) Texto con varias partes (equipos con DataMatrix): se busca una serie que ya conozcamos
        String[] partes = mayus.split("[\\s;|,\\u001d]+");
        if (partes.length > 1) {
            for (String p : partes) if (buscarPorRaw(p) != null) return p;
            for (String p : partes) if (p.matches("[0-9A-F]{12}")) return p;
        }
        return r;
    }

    // Etiqueta larga de Movistar: 8 cifras del material + proveedor + pedido + lote (puede acabar en letras, ej. EL26).
    static boolean esEtiquetaLarga(String raw) {
        return raw != null && raw.trim().matches("\\d{8}[0-9A-Za-z]{16,}");
    }

    // Número de serie corto de cada equipo (ej. 88FEB6268920): único de cada unidad.
    static boolean esSerie(String raw) {
        return raw != null && raw.trim().matches("[0-9A-Za-z/\\-]{8,20}") && !esEtiquetaLarga(raw);
    }

    synchronized Item buscarPorRaw(String raw) {
        for (Item it : items) if (it.raws.contains(raw)) return it;
        return null;
    }

    // Se agrupa por las 8 primeras cifras (el código del material). Algunas etiquetas llevan 88… en vez de 00….
    static String clave(String raw) {
        String r = raw.trim();
        if (!esEtiquetaLarga(r)) return r;
        r = r.substring(0, 8);
        if (r.equals("88414936")) return "00414936";
        if (r.equals("88412547")) return "00412537";
        return r;
    }

    static String nombreConocido(String clave) { return Albaran.nombre(clave); }

    // Carga el albarán de la dotación inicial. Devuelve cuántas unidades ha metido.
    synchronized int cargarAlbaran(boolean vaciar) {
        if (vaciar) items.clear();
        int n = 0;
        long ahora = System.currentTimeMillis();
        for (String[] f : Albaran.CON_SERIE) {
            Item it = buscar(f[0]);
            if (it == null) {
                it = new Item();
                it.codigo = f[0];
                it.nombre = f[1];
                it.cantidad = 0;
                items.add(it);
            }
            for (int k = 2; k < f.length; k++) {
                if (!Albaran.usado(f[k]) && !it.raws.contains(f[k])) { it.raws.add(f[k]); it.cantidad++; n++; }
            }
            it.fecha = ahora;
        }
        for (String[] f : Albaran.SIN_SERIE) {
            if (buscar(f[0]) != null) continue;
            Item it = new Item();
            it.codigo = f[0];
            it.nombre = f[1];
            it.cantidad = Math.max(0, Integer.parseInt(f[2]) - Albaran.usadosDe(f[0]));
            it.fecha = ahora;
            if (it.cantidad > 0) { items.add(it); n += it.cantidad; }
        }
        guardar();
        return n;
    }

    // ---- Albaranes desde archivo de texto ----
    private java.util.HashSet<String> leerCargados() {
        java.util.HashSet<String> r = new java.util.HashSet<>();
        try {
            File f = new File(archivo.getParentFile(), "albaranes_cargados.txt");
            if (!f.exists()) return r;
            for (String l : leerLineas(f)) if (!l.trim().isEmpty()) r.add(l.trim());
        } catch (Exception ignorar) { }
        return r;
    }

    private void guardarCargados(java.util.HashSet<String> c) {
        try {
            archivo.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(archivo.getParentFile(), "albaranes_cargados.txt"))) {
                o.write(android.text.TextUtils.join("\n", c).getBytes("UTF-8"));
            }
        } catch (Exception e) { android.util.Log.e("KULI_ORDENES", "Guardando albaranes cargados", e); }
    }

    private static ArrayList<String> leerLineas(File f) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
        }
        String t = new String(o.toByteArray(), "UTF-8").replace("\uFEFF", "");
        ArrayList<String> r = new ArrayList<>();
        for (String l : t.split("\\r?\\n")) r.add(l);
        return r;
    }

    // Suma al almacén lo que dice el archivo. Lanza IllegalStateException si ya estaba cargado.
    synchronized String importarAlbaran(File f, boolean vaciar) throws Exception {
        String id = f.getName();
        java.util.HashSet<String> usadosS = new java.util.HashSet<>();
        java.util.HashMap<String, Integer> usadosC = new java.util.HashMap<>();
        long fechaAlb = 0;
        ArrayList<String> gastarS = new ArrayList<>();
        ArrayList<String[]> gastarC = new ArrayList<>();
        ArrayList<String[]> lineas = new ArrayList<>();
        for (String l : leerLineas(f)) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split(";");
            for (int i = 0; i < p.length; i++) p[i] = p[i].trim();
            if (p[0].equals("ALBARAN") && p.length > 1) id = p[1];
            else if (p[0].equals("FECHA") && p.length > 1) {
                try { fechaAlb = new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).parse(p[1]).getTime(); }
                catch (Exception ignorar) { }
            }
            else if (p[0].equals("U") && p.length > 1) usadosS.add(p[1].toUpperCase(Locale.ROOT));
            else if (p[0].equals("UC") && p.length > 2) usadosC.put(p[1], Integer.parseInt(p[2]));
            else if (p[0].equals("G") && p.length > 1) gastarS.add(p[1].toUpperCase(Locale.ROOT));
            else if (p[0].equals("GC") && p.length > 2) gastarC.add(new String[]{p[1], p[2]});
            else lineas.add(p);
        }
        java.util.HashSet<String> cargados = leerCargados();
        if (vaciar) { items.clear(); cargados.clear(); }
        else if (cargados.contains(id)) throw new IllegalStateException("ya cargado");
        int anadidas = 0, saltadas = 0;
        long ahora = System.currentTimeMillis();
        for (String[] p : lineas) {
            if (p.length < 4) continue;
            if (p[0].equals("S")) {
                String serie = p[3].toUpperCase(Locale.ROOT);
                if (usadosS.contains(serie) || buscarPorRaw(serie) != null) { saltadas++; continue; }
                Item it = buscar(p[1]);
                if (it == null) { it = new Item(); it.codigo = p[1]; it.nombre = p[2]; it.cantidad = 0; items.add(it); }
                it.raws.add(serie);
                if (fechaAlb > 0) it.fechas.put(serie, fechaAlb);
                it.cantidad++;
                it.fecha = ahora;
                anadidas++;
            } else if (p[0].equals("C")) {
                int cant = Integer.parseInt(p[3]);
                Integer u = usadosC.get(p[1]);
                if (u != null) cant -= u;
                if (cant <= 0) continue;
                Item it = buscar(p[1]);
                if (it == null) { it = new Item(); it.codigo = p[1]; it.nombre = p[2]; it.cantidad = 0; items.add(it); }
                it.cantidad += cant;
                it.fecha = ahora;
                anadidas += cant;
            }
        }
        // G = equipo gastado (por serie) · GC = cantidad gastada de un material sin serie
        int gastadas = 0, noEncontradas = 0;
        for (String serie : gastarS) {
            Item it = buscarPorRaw(serie);
            if (it == null) { noEncontradas++; continue; }
            gastar(it.codigo, serie);
            gastadas++;
        }
        for (String[] g : gastarC) {
            Item it = buscar(g[0]);
            int n = Integer.parseInt(g[1]);
            if (it == null) { noEncontradas += n; continue; }
            for (int k = 0; k < n; k++) { gastar(it.codigo); gastadas++; }
        }
        cargados.add(id);
        guardarCargados(cargados);
        guardar();
        return "Añadidas " + anadidas + " unidades" + (saltadas > 0 ? " (" + saltadas + " ya estaban o ya usadas)" : "")
            + (gastadas + noEncontradas > 0 ? " · Restadas " + gastadas + (noEncontradas > 0 ? " (" + noEncontradas + " no estaban en el almacén)" : "") : "");
    }

    synchronized void crear(String clave, String nombre, String raw) {
        Item it = new Item();
        it.codigo = clave;
        it.nombre = nombre == null || nombre.trim().isEmpty() ? clave : nombre.trim();
        it.cantidad = 1;
        it.fecha = System.currentTimeMillis();
        if (raw != null) it.raws.add(raw);
        items.add(it);
        guardar();
    }

    synchronized void sumar(String clave, String raw) {
        Item it = buscar(clave);
        if (it == null) return;
        if (raw != null && !it.raws.contains(raw)) it.raws.add(raw);
        it.cantidad++;
        it.fecha = System.currentTimeMillis();
        guardar();
    }

    synchronized ArrayList<Item> lista() {
        ArrayList<Item> r = new ArrayList<>(items);
        Collections.sort(r, (a, b) -> a.nombre.toLowerCase(Locale.ROOT).compareTo(b.nombre.toLowerCase(Locale.ROOT)));
        return r;
    }

    synchronized int total() {
        int t = 0;
        for (Item it : items) t += it.cantidad;
        return t;
    }

    synchronized Item buscar(String codigo) {
        for (Item it : items) if (it.codigo.equals(codigo)) return it;
        return null;
    }

    synchronized void anadir(String codigo, String nombre, int cantidad) {
        Item it = buscar(codigo);
        if (it == null) {
            it = new Item();
            it.codigo = codigo;
            it.nombre = nombre == null || nombre.trim().isEmpty() ? codigo : nombre.trim();
            it.cantidad = 0;
            items.add(it);
        }
        it.cantidad += cantidad;
        it.fecha = System.currentTimeMillis();
        guardar();
    }

    synchronized void renombrar(String codigo, String nombre) {
        Item it = buscar(codigo);
        if (it != null && nombre != null && !nombre.trim().isEmpty()) { it.nombre = nombre.trim(); guardar(); }
    }

    // Resta una unidad. Devuelve las que quedan, o -1 si ese código no estaba. Si llega a 0, sale del almacén.
    synchronized int gastar(String codigo) { return gastar(codigo, null); }

    synchronized int gastar(String codigo, String raw) {
        Item it = buscar(codigo);
        if (it == null) return -1;
        if (raw != null) it.raws.remove(raw);
        it.cantidad--;
        int quedan = Math.max(it.cantidad, 0);
        if (it.cantidad <= 0) { it.cantidad = 0; if (it.minimo <= 0) items.remove(it); }
        guardar();
        return quedan;
    }

    synchronized void setMinimo(String codigo, int minimo) {
        Item it = buscar(codigo);
        if (it != null) { it.minimo = minimo; guardar(); }
    }

    synchronized ArrayList<Item> bajos() {
        ArrayList<Item> r = new ArrayList<>();
        for (Item it : items) if (it.minimo > 0 && it.cantidad <= it.minimo) r.add(it);
        return r;
    }

    synchronized void quitar(String codigo) {
        Item it = buscar(codigo);
        if (it != null) { items.remove(it); guardar(); }
    }
}
