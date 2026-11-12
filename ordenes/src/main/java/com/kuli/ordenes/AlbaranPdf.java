package com.kuli.ordenes;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Lee el PDF de un albarán de CIRCET y lo convierte al texto que entiende el almacén.
final class AlbaranPdf {
    static final class Resultado {
        String id = "", fecha = "";
        String texto = "";        // formato del almacén (ALBARAN; S; C)
        String trabajos = "";     // trabajo | dirección | material
        int series, cantidades;
        java.util.LinkedHashMap<String, Integer> entregado = new java.util.LinkedHashMap<>(); // código -> unidades que llegan
        java.util.HashMap<String, String> nombres = new java.util.HashMap<>();
    }

    private static final Pattern ENT = Pattern.compile("^(\\d{8})\\s*-\\s*(.+?)\\s+(\\d+)\\s+UD\\b.*$");
    private static final Pattern SER = Pattern.compile("^(\\d{8})\\s*-\\s*(.+?)\\s+([0-9A-Za-z/]*[0-9][0-9A-Za-z/]{7,19})\\s*$");
    private static final Pattern DET = Pattern.compile(
        "^(\\d{8})\\s*-\\s*(.+?)\\s+[AB]\\s+\\d+\\s+UD\\s+(\\d{4}/\\d{7}/\\d{7})\\s+\\d{2}/\\d{2}/\\d{2}\\s+\\d+\\s+\\d+\\s+(.+)$");
    private static final Pattern ID = Pattern.compile("Albar[aá]n\\s+(\\d{2}/\\d{6,8})");
    private static final Pattern FECHA = Pattern.compile("Fecha\\s+(\\d{2}/\\d{2}/\\d{4})");

    static Resultado leer(byte[] pdf) throws Exception {
        String t;
        PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf));
        try {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            t = s.getText(doc);
        } finally { doc.close(); }
        return interpretar(t);
    }

    static Resultado interpretar(String t) {
        Resultado r = new Resultado();
        Matcher m = ID.matcher(t);
        if (m.find()) r.id = m.group(1);
        m = FECHA.matcher(t);
        if (m.find()) r.fecha = m.group(1);

        ArrayList<String[]> ser = new ArrayList<>();               // código, nombre, serie
        LinkedHashMap<String, String[]> ent = new LinkedHashMap<>(); // código -> {nombre, cantidad}
        java.util.HashSet<String> conSerie = new java.util.HashSet<>();
        LinkedHashMap<String, String> dir = new LinkedHashMap<>();
        LinkedHashMap<String, ArrayList<String>> mat = new LinkedHashMap<>();

        String[] ls = t.split("\\r?\\n");
        int modo = 0; // 1 entregas · 2 desmontes · 3 serializable · 4 detalle
        for (int i = 0; i < ls.length; i++) {
            String l = ls[i].trim();
            if (l.isEmpty()) continue;
            String low = l.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("resumen de entregas")) { modo = 1; continue; }
            if (low.startsWith("resumen desmontes")) { modo = 2; continue; }
            if (low.startsWith("resumen material serializable")) { modo = 3; continue; }
            if (low.startsWith("detalle de materiales")) { modo = 4; continue; }
            Matcher x;
            if (modo == 1 && (x = ENT.matcher(l)).matches()) {
                String[] a = ent.get(x.group(1));
                int q = Integer.parseInt(x.group(3));
                if (a == null) ent.put(x.group(1), new String[]{limpio(x.group(2)), String.valueOf(q)});
                else a[1] = String.valueOf(Integer.parseInt(a[1]) + q);
            } else if (modo == 3 && (x = SER.matcher(l)).matches()) {
                ser.add(new String[]{x.group(1), limpio(x.group(2)), x.group(3).toUpperCase(java.util.Locale.ROOT)});
                conSerie.add(x.group(1));
            } else if (modo == 4 && (x = DET.matcher(l)).matches()) {
                String trabajo = x.group(3);
                if (!dir.containsKey(trabajo)) {
                    StringBuilder d = new StringBuilder(x.group(4).trim());
                    for (int k = i + 1; k < ls.length && k <= i + 2; k++) {
                        String n = ls[k].trim();
                        if (n.isEmpty() || n.matches("^\\d{8}\\s*-.*") || n.toLowerCase(java.util.Locale.ROOT).contains("albar")) break;
                        d.append(' ').append(n);
                    }
                    dir.put(trabajo, d.toString());
                }
                ArrayList<String> mm = mat.get(trabajo);
                if (mm == null) { mm = new ArrayList<>(); mat.put(trabajo, mm); }
                boolean retirado = x.group(1).startsWith("99");
                mm.add((retirado ? "RETIRADO " : "") + limpio(x.group(2)));
            }
        }

        StringBuilder b = new StringBuilder();
        b.append("ALBARAN;").append(r.id.isEmpty() ? "PDF-" + System.currentTimeMillis() : r.id).append('\n');
        if (!r.fecha.isEmpty()) b.append("FECHA;").append(r.fecha).append('\n');
        for (String[] s : ser) { b.append("S;").append(s[0]).append(';').append(s[1]).append(';').append(s[2]).append('\n'); r.series++; }
        for (java.util.Map.Entry<String, String[]> e : ent.entrySet()) {
            if (conSerie.contains(e.getKey())) continue;
            b.append("C;").append(e.getKey()).append(';').append(e.getValue()[0]).append(';').append(e.getValue()[1]).append('\n');
            r.cantidades += Integer.parseInt(e.getValue()[1]);
        }
        r.texto = b.toString();
        for (String[] x : ser) { Integer c = r.entregado.get(x[0]); r.entregado.put(x[0], c == null ? 1 : c + 1); r.nombres.put(x[0], x[1]); }
        for (java.util.Map.Entry<String, String[]> e : ent.entrySet()) {
            if (conSerie.contains(e.getKey())) continue;
            r.entregado.put(e.getKey(), Integer.parseInt(e.getValue()[1]));
            r.nombres.put(e.getKey(), e.getValue()[0]);
        }

        StringBuilder tr = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : dir.entrySet())
            tr.append(e.getKey()).append(" | ").append(e.getValue()).append(" | ")
              .append(android.text.TextUtils.join(", ", mat.get(e.getKey()))).append('\n');
        r.trabajos = tr.toString();
        return r;
    }

    private static String limpio(String s) { return s.replace(";", ",").replaceAll("\\s+", " ").trim(); }
}
