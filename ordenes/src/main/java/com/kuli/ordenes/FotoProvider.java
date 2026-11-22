package com.kuli.ordenes;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

// Provee acceso a archivos de fotos tomadas con la cámara.
// Usa File patrón: content://com.kuli.ordenes.fotos/tmp/<nombre>
// La cámara escribe aquí vía MediaStore.EXTRA_OUTPUT y la app lee después.
public class FotoProvider extends ContentProvider {
    static final String AUTHORITY = "com.kuli.ordenes.fotos";
    static final String PATH_TMP = "tmp";

    private File carpetaTmp;

    @Override public boolean onCreate() {
        carpetaTmp = new File(getContext().getCacheDir(), PATH_TMP);
        carpetaTmp.mkdirs();
        return true;
    }

    // Devuelve el archivo solicitado. La cámara escribe en EXTRA_OUTPUT apuntando aquí.
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String filename = uri.getLastPathSegment();
        if (filename == null) throw new FileNotFoundException("Nombre de archivo nulo en URI: " + uri);
        File f = new File(getContext().getCacheDir(), filename);
        if (!f.exists()) {
            // La cámara podría no haber escrito aún; intentar crear vacío para que el intent no falle
            try {
                f.createNewFile();
            } catch (IOException e) {
                Log.e("FotoProvider", "No se pudo crear archivo temporal", e);
                throw new FileNotFoundException("No pudo crearse el archivo temporal", e);
            }
        }
        int m = ParcelFileDescriptor.parseMode(mode);
        try {
            return ParcelFileDescriptor.open(f, m);
        } catch (Exception e) {
            throw new FileNotFoundException("No pudo abrirse el archivo: " + f.getAbsolutePath());
        }
    }

    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return "image/jpeg";
        String m = n.toLowerCase(java.util.Locale.ROOT);
        if (m.endsWith(".jpg") || m.equals("tmp")) return "image/jpeg";
        if (m.endsWith(".png")) return "image/png";
        return "image/*";
    }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String order) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String sel, String[] args) {
        String filename = uri.getLastPathSegment();
        if (filename != null) {
            File f = new File(getContext().getCacheDir(), filename);
            f.delete();
        }
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) {
        return 0;
    }
}