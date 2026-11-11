package com.kuli.music;

import android.media.MediaMetadataRetriever;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;

// Ordena la carpeta Música › KULI MUSIC en «Álbumes», «Sueltas» y «Duplicadas».
final class OrdenarMusica {
    static final String ALBUMES = "Álbumes", SUELTAS = "Sueltas", DUPLICADAS = "Duplicadas";
    static final int MIN_ALBUM = 3; // canciones del mismo álbum para considerarlo álbum

    static final class Pista {
        File f; String titulo = "", artista = "", album = ""; long dur, tam;
    }

    static final class Plan {
        final ArrayList<File[]> mover = new ArrayList<>();      // {origen, destino}
        final ArrayList<File[]> duplicadas = new ArrayList<>(); // {origen, destino}
        int total, enAlbumes, nAlbumes, enSueltas;
    }

    static boolean esAudio(String n) {
        return n.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav|wma)$");
    }

    private static void recorrer(File dir, File duplicadas, ArrayList<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { if (!f.equals(duplicadas)) recorrer(f, duplicadas, out); }
            else if (esAudio(f.getName())) out.add(f);
        }
    }

    static String norm(String t) {
        String n = Normalizer.normalize(t == null ? "" : t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    static String seguro(String t) {
        String s = t.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "Sin nombre" : s;
    }

    private static Pista leer(File f) {
        Pista p = new Pista();
        p.f = f; p.tam = f.length();
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(f.getAbsolutePath());
            String t = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            if (a == null || a.trim().isEmpty()) a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String al = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (t != null) p.titulo = t.trim();
            if (a != null) p.artista = a.trim();
            if (al != null) p.album = al.trim();
            if (d != null) p.dur = Long.parseLong(d.trim());
        } catch (Throwable ignorar) {
        } finally {
            try { m.release(); } catch (Throwable ignorar) { }
        }
        if (p.titulo.isEmpty()) {
            String n = f.getName();
            int punto = n.lastIndexOf('.');
            if (punto > 0) n = n.substring(0, punto);
            p.titulo = n.replaceFirst("^\\d{1,3}\\s*[-._ ]+\\s*", "");
        }
        return p;
    }

    static Plan planear(File base) {
        Plan plan = new Plan();
        File dupDir = new File(base, DUPLICADAS);
        ArrayList<File> archivos = new ArrayList<>();
        recorrer(base, dupDir, archivos);
        plan.total = archivos.size();
        ArrayList<Pista> pistas = new ArrayList<>();
        for (File f : archivos) pistas.add(leer(f));

        // 1) Duplicadas: mismo título y artista y duración parecida (±2 s). Se queda la más grande.
        HashMap<String, ArrayList<Pista>> porClave = new HashMap<>();
        for (Pista p : pistas) {
            if (p.dur <= 0) continue;
            String k = norm(p.titulo) + "|" + norm(p.artista);
            if (norm(p.titulo).isEmpty()) continue;
            ArrayList<Pista> l = porClave.get(k);
            if (l == null) { l = new ArrayList<>(); porClave.put(k, l); }
            l.add(p);
        }
        java.util.HashSet<File> sobran = new java.util.HashSet<>();
        for (ArrayList<Pista> l : porClave.values()) {
            if (l.size() < 2) continue;
            Collections.sort(l, (a, b) -> Long.compare(b.tam, a.tam));
            ArrayList<Pista> quedan = new ArrayList<>();
            for (Pista p : l) {
                boolean repetida = false;
                for (Pista q : quedan) if (Math.abs(q.dur - p.dur) <= 2000) { repetida = true; break; }
                if (repetida) {
                    sobran.add(p.f);
                    plan.duplicadas.add(new File[]{p.f, new File(dupDir, p.f.getName())});
                } else quedan.add(p);
            }
        }

        // 2) Álbumes (3 o más del mismo álbum) y sueltas
        ArrayList<Pista> validas = new ArrayList<>();
        for (Pista p : pistas) if (!sobran.contains(p.f)) validas.add(p);
        HashMap<String, Integer> cuenta = new HashMap<>();
        for (Pista p : validas) {
            if (p.album.isEmpty()) continue;
            String k = norm(p.artista) + "|" + norm(p.album);
            Integer c = cuenta.get(k);
            cuenta.put(k, c == null ? 1 : c + 1);
        }
        java.util.HashSet<String> albumesVistos = new java.util.HashSet<>();
        for (Pista p : validas) {
            String k = norm(p.artista) + "|" + norm(p.album);
            File destino;
            if (!p.album.isEmpty() && cuenta.get(k) >= MIN_ALBUM) {
                String carpeta = seguro((p.artista.isEmpty() ? "" : p.artista + " - ") + p.album);
                destino = new File(new File(new File(base, ALBUMES), carpeta), p.f.getName());
                plan.enAlbumes++;
                albumesVistos.add(k);
            } else {
                destino = new File(new File(base, SUELTAS), p.f.getName());
                plan.enSueltas++;
            }
            if (!destino.equals(p.f)) plan.mover.add(new File[]{p.f, destino});
        }
        plan.nAlbumes = albumesVistos.size();
        return plan;
    }

    static File libre(File destino) {
        if (!destino.exists()) return destino;
        String n = destino.getName();
        int punto = n.lastIndexOf('.');
        String raiz = punto > 0 ? n.substring(0, punto) : n, ext = punto > 0 ? n.substring(punto) : "";
        for (int i = 2; i < 1000; i++) {
            File f = new File(destino.getParentFile(), raiz + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return destino;
    }

    private static boolean mover(File origen, File destino) {
        File d = libre(destino);
        d.getParentFile().mkdirs();
        if (origen.renameTo(d)) return true;
        try (FileInputStream in = new FileInputStream(origen); FileOutputStream out = new FileOutputStream(d)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        } catch (Exception e) {
            d.delete();
            return false;
        }
        return origen.delete();
    }

    // Ejecuta el plan. Devuelve las rutas tocadas (para avisar a la galería) y deja en fallos cuántas no se pudieron mover.
    static ArrayList<String> ejecutar(Plan plan, File base, int[] fallos) {
        ArrayList<String> rutas = new ArrayList<>();
        ArrayList<File[]> todo = new ArrayList<>(plan.duplicadas);
        todo.addAll(plan.mover);
        for (File[] par : todo) {
            rutas.add(par[0].getAbsolutePath());
            if (mover(par[0], par[1])) rutas.add(par[1].getAbsolutePath()); else fallos[0]++;
        }
        quitarVacias(base, true);
        return rutas;
    }

    private static void quitarVacias(File dir, boolean esBase) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.isDirectory()) quitarVacias(f, false);
        if (!esBase) dir.delete(); // solo borra si está vacía
    }

    static ArrayList<File> duplicadasGuardadas(File base) {
        ArrayList<File> l = new ArrayList<>();
        File[] fs = new File(base, DUPLICADAS).listFiles();
        if (fs != null) for (File f : fs) if (f.isFile()) l.add(f);
        return l;
    }
}
