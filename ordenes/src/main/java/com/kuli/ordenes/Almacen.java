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
        int cantidad;
        long fecha;
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
                a.put(j);
            }
            archivo.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(archivo)) { o.write(a.toString().getBytes("UTF-8")); }
        } catch (Exception e) {
            android.util.Log.e("KULI_ORDENES", "Guardando almacén", e);
        }
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
    synchronized int gastar(String codigo) {
        Item it = buscar(codigo);
        if (it == null) return -1;
        it.cantidad--;
        int quedan = Math.max(it.cantidad, 0);
        if (it.cantidad <= 0) items.remove(it);
        guardar();
        return quedan;
    }

    synchronized void quitar(String codigo) {
        Item it = buscar(codigo);
        if (it != null) { items.remove(it); guardar(); }
    }
}
