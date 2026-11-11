package com.kuli.music;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.ArrayList;
import java.util.Collections;

// Busca las canciones de una carpeta del móvil (y de sus subcarpetas) elegida por el usuario.
final class CarpetaMusica {
    // Devuelve pares {nombre, uri} ordenados por nombre.
    static ArrayList<String[]> listar(ContentResolver cr, Uri arbol) {
        ArrayList<String[]> r = new ArrayList<>();
        try {
            recorrer(cr, arbol, DocumentsContract.getTreeDocumentId(arbol), r, 0);
        } catch (Throwable e) {
            android.util.Log.e("KULI_CARPETA", "Leyendo la carpeta", e);
        }
        Collections.sort(r, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return r;
    }

    private static void recorrer(ContentResolver cr, Uri arbol, String docId, ArrayList<String[]> r, int nivel) {
        if (nivel > 6 || r.size() >= 4000) return;
        Uri hijos = DocumentsContract.buildChildDocumentsUriUsingTree(arbol, docId);
        try (Cursor c = cr.query(hijos, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String nombre = c.getString(1);
                String mime = c.getString(2);
                if (nombre == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    recorrer(cr, arbol, id, r, nivel + 1);
                } else if ((mime != null && mime.startsWith("audio/"))
                        || nombre.toLowerCase().matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav|wma)$")) {
                    int punto = nombre.lastIndexOf('.');
                    String limpio = punto > 0 ? nombre.substring(0, punto) : nombre;
                    r.add(new String[]{limpio, DocumentsContract.buildDocumentUriUsingTree(arbol, id).toString()});
                }
            }
        }
    }
}
