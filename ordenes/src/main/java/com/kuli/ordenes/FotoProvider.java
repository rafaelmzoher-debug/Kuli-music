package com.kuli.ordenes;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

// Deja que la app de cámara escriba la foto en un archivo temporal de KULI ORDENES.
public class FotoProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File archivo(Uri u) {
        String n = u.getLastPathSegment();
        if (n == null) return null;
        File d = new File(getContext().getCacheDir(), "tmp");
        d.mkdirs();
        return new File(d, new File(n).getName());
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = archivo(uri);
        if (f == null) throw new FileNotFoundException();
        int m = ParcelFileDescriptor.parseMode(mode);
        try { if (!f.exists() && (m & ParcelFileDescriptor.MODE_CREATE) != 0) f.createNewFile(); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        return ParcelFileDescriptor.open(f, m);
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String orden) {
        File f = archivo(uri);
        String[] cols = proj != null ? proj : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        Object[] fila = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) fila[i] = f == null ? "foto.jpg" : f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) fila[i] = f == null ? 0L : f.length();
        }
        c.addRow(fila);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
