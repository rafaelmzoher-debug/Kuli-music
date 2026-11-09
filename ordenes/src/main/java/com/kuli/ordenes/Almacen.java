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
            if (!archivo.exists()) return;
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
                items.add(it);
            }
        } catch (Exception e) {
            android.util.Log.e("KULI_ORDENES", "Leyendo almacén", e);
        }
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
                for (String r : it.raws) rr.put(r);
                j.put("raws", rr);
                a.put(j);
            }
            archivo.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(archivo)) { o.write(a.toString().getBytes("UTF-8")); }
        } catch (Exception e) {
            android.util.Log.e("KULI_ORDENES", "Guardando almacén", e);
        }
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
