package com.kuli.fotos;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.os.Bundle;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Galería: enseña con miniaturas las fotos que hay en el NAS (FOTOS KULI / año / mes / origen).
public class GaleriaActivity extends Activity {
    private final int NEGRO = Color.rgb(9, 10, 15);
    private final int DORADO = Color.rgb(232, 190, 92);
    private final int GRIS = Color.rgb(150, 153, 168);

    static final class Elem { String nombre; String ruta; boolean carpeta; boolean video; long tam; }
    static final class Celda { ImageView img; TextView txt; String ruta; }

    private final ArrayList<Elem> elems = new ArrayList<>();
    private String ruta = CopiaFotos.CARPETA;
    private TextView titulo, vacio;
    private GridView rejilla;
    private BaseAdapter adaptador;
    private int lado;

    private final ExecutorService hilos = Executors.newFixedThreadPool(3);
    private final ExecutorService listador = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(40 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private SMBClient cliente;
    private Connection conexion;
    private DiskShare disco;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        lado = getResources().getDisplayMetrics().widthPixels / 3;
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(NEGRO);

        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        barra.setPadding(dp(10), dp(30), dp(10), dp(8));
        Button atras = new Button(this);
        atras.setText("‹");
        atras.setTextSize(22);
        atras.setTextColor(DORADO);
        atras.setBackgroundColor(Color.TRANSPARENT);
        atras.setOnClickListener(v -> onBackPressed());
        barra.addView(atras, new LinearLayout.LayoutParams(dp(56), dp(48)));
        titulo = new TextView(this);
        titulo.setTextColor(Color.WHITE);
        titulo.setTextSize(16);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        barra.addView(titulo, new LinearLayout.LayoutParams(0, -2, 1f));
        raiz.addView(barra, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout cuerpo = new FrameLayout(this);
        rejilla = new GridView(this);
        rejilla.setNumColumns(3);
        rejilla.setVerticalSpacing(dp(2));
        rejilla.setHorizontalSpacing(dp(2));
        adaptador = new BaseAdapter() {
            @Override public int getCount() { return elems.size(); }
            @Override public Object getItem(int i) { return elems.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) { return celda(i, v); }
        };
        rejilla.setAdapter(adaptador);
        rejilla.setOnItemClickListener((p, v, i, id) -> alTocar(i));
        cuerpo.addView(rejilla, new FrameLayout.LayoutParams(-1, -1));
        vacio = new TextView(this);
        vacio.setTextColor(GRIS);
        vacio.setTextSize(15);
        vacio.setGravity(Gravity.CENTER);
        cuerpo.addView(vacio, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        raiz.addView(cuerpo, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(raiz);
        cargar(ruta);
    }

    @Override public void onBackPressed() {
        if (ruta.equals(CopiaFotos.CARPETA)) { super.onBackPressed(); return; }
        int k = ruta.lastIndexOf('\\');
        cargar(k > 0 ? ruta.substring(0, k) : CopiaFotos.CARPETA);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        hilos.shutdownNow();
        listador.execute(() -> cerrar());
        listador.shutdown();
    }

    // ---------- Conexión al NAS ----------
    private synchronized DiskShare disco() throws Exception {
        if (disco != null && disco.isConnected()) return disco;
        cerrar();
        SmbConfig[] configs = {
            SmbConfig.createDefaultConfig(),
            SmbConfig.builder().withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
                .withSigningRequired(false).build()
        };
        AuthenticationContext[] cuentas = { AuthenticationContext.guest(), AuthenticationContext.anonymous() };
        Exception ultimo = null;
        for (SmbConfig cfg : configs) {
            for (AuthenticationContext cuenta : cuentas) {
                try {
                    cliente = new SMBClient(cfg);
                    conexion = cliente.connect(CopiaFotos.ip(this));
                    Session sesion = conexion.authenticate(cuenta);
                    disco = (DiskShare) sesion.connectShare("Public");
                    return disco;
                } catch (Exception e) {
                    ultimo = e;
                    cerrar();
                }
            }
        }
        throw ultimo != null ? ultimo : new java.io.IOException("Sin conexión con el NAS");
    }

    private synchronized void cerrar() {
        try { if (disco != null) disco.close(); } catch (Exception ignorar) { }
        try { if (conexion != null) conexion.close(); } catch (Exception ignorar) { }
        try { if (cliente != null) cliente.close(); } catch (Exception ignorar) { }
        disco = null; conexion = null; cliente = null;
    }

    // ---------- Lista de carpetas y fotos ----------
    private void cargar(final String r) {
        titulo.setText("📷 " + r.replace("\\", "  ›  "));
        vacio.setText("Cargando…");
        vacio.setVisibility(View.VISIBLE);
        listador.execute(() -> {
            final ArrayList<Elem> lista = new ArrayList<>();
            final String[] error = new String[1];
            try {
                DiskShare d = disco();
                for (FileIdBothDirectoryInformation f : d.list(r)) {
                    String n = f.getFileName();
                    if (n.equals(".") || n.equals("..")) continue;
                    Elem e = new Elem();
                    e.nombre = n; e.ruta = r + "\\" + n; e.tam = f.getEndOfFile();
                    e.carpeta = EnumWithValue.EnumUtils.isSet(f.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
                    String l = n.toLowerCase(Locale.ROOT);
                    e.video = l.endsWith(".mp4") || l.endsWith(".mov") || l.endsWith(".3gp")
                        || l.endsWith(".mkv") || l.endsWith(".avi") || l.endsWith(".webm");
                    lista.add(e);
                }
            } catch (Throwable t) {
                error[0] = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            }
            Collections.sort(lista, (a, b) -> a.carpeta != b.carpeta ? (a.carpeta ? -1 : 1)
                : b.nombre.compareToIgnoreCase(a.nombre));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (error[0] != null) {
                    vacio.setText("No se pudo abrir el NAS\n" + error[0]);
                    return;
                }
                ruta = r;
                titulo.setText("📷 " + r.replace("\\", "  ›  "));
                elems.clear();
                elems.addAll(lista);
                adaptador.notifyDataSetChanged();
                rejilla.setSelection(0);
                vacio.setText("Esta carpeta está vacía");
                vacio.setVisibility(lista.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void alTocar(int i) {
        Elem e = elems.get(i);
        if (e.carpeta) cargar(e.ruta);
        else if (e.video) abrirVideo(e);
        else abrirFoto(i);
    }

    // ---------- Celdas ----------
    private View celda(int i, View v) {
        Celda c;
        if (v == null) {
            FrameLayout caja = new FrameLayout(this);
            caja.setBackgroundColor(Color.rgb(28, 30, 40));
            caja.setLayoutParams(new AbsListView.LayoutParams(lado - dp(2), lado - dp(2)));
            c = new Celda();
            c.img = new ImageView(this);
            c.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            caja.addView(c.img, new FrameLayout.LayoutParams(-1, -1));
            c.txt = new TextView(this);
            c.txt.setTextColor(Color.WHITE);
            c.txt.setTextSize(12);
            c.txt.setGravity(Gravity.CENTER);
            c.txt.setPadding(dp(4), dp(4), dp(4), dp(4));
            caja.addView(c.txt, new FrameLayout.LayoutParams(-1, -1));
            caja.setTag(c);
            v = caja;
        } else {
            c = (Celda) v.getTag();
        }
        Elem e = elems.get(i);
        c.ruta = e.ruta;
        c.img.setImageDrawable(null);
        if (e.carpeta) {
            c.txt.setText("📁\n" + e.nombre);
            c.txt.setTextSize(15);
        } else if (e.video) {
            c.txt.setText("🎬\n" + e.nombre);
            c.txt.setTextSize(12);
        } else {
            c.txt.setText("");
            miniatura(e, c);
        }
        return v;
    }

    private void miniatura(final Elem e, final Celda c) {
        Bitmap b = cache.get(e.ruta);
        if (b != null) { c.img.setImageBitmap(b); return; }
        try {
            hilos.execute(() -> {
                Bitmap m = null;
                try { m = crearMiniatura(e); } catch (Throwable t) {
                    android.util.Log.e("KULI_FOTOS", "Miniatura " + e.nombre, t);
                }
                if (m == null) return;
                cache.put(e.ruta, m);
                final Bitmap fin = m;
                runOnUiThread(() -> { if (e.ruta.equals(c.ruta)) c.img.setImageBitmap(fin); });
            });
        } catch (Exception ignorar) { }
    }

    private Bitmap crearMiniatura(Elem e) throws Exception {
        java.io.File guardada = new java.io.File(getCacheDir(), "m" + Integer.toHexString((e.ruta + e.tam).hashCode()) + ".jpg");
        if (guardada.exists()) {
            Bitmap g = BitmapFactory.decodeFile(guardada.getAbsolutePath());
            if (g != null) return g;
        }
        if (e.tam > 40L * 1024 * 1024) return null;
        Bitmap m = decodificar(leer(e.ruta, 40L * 1024 * 1024), 320);
        if (m != null) {
            try (FileOutputStream o = new FileOutputStream(guardada)) { m.compress(Bitmap.CompressFormat.JPEG, 80, o); }
            catch (Exception ignorar) { }
        }
        return m;
    }

    private byte[] leer(String r, long max) throws Exception {
        DiskShare d = disco();
        try (File f = d.openFile(r, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
             InputStream in = f.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                o.write(buf, 0, n);
                total += n;
                if (total > max) throw new java.io.IOException("Archivo demasiado grande");
            }
            return o.toByteArray();
        }
    }

    private Bitmap decodificar(byte[] datos, int maxLado) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(datos, 0, datos.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while (o.outWidth / (s * 2) >= maxLado && o.outHeight / (s * 2) >= maxLado) s *= 2;
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

    // ---------- Ver una foto entera ----------
    private Dialog dlgFoto;
    private ImageView visor;
    private TextView pie;
    private int actual = -1;

    private void abrirFoto(int idx) {
        dlgFoto = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout caja = new FrameLayout(this);
        caja.setBackgroundColor(Color.BLACK);
        visor = new ImageView(this);
        visor.setScaleType(ImageView.ScaleType.FIT_CENTER);
        caja.addView(visor, new FrameLayout.LayoutParams(-1, -1));
        pie = new TextView(this);
        pie.setTextColor(Color.WHITE);
        pie.setTextSize(13);
        pie.setGravity(Gravity.CENTER);
        pie.setBackgroundColor(Color.argb(140, 0, 0, 0));
        pie.setPadding(dp(8), dp(8), dp(8), dp(24));
        caja.addView(pie, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        Button ant = flecha("‹");
        ant.setOnClickListener(v -> { int j = siguienteFoto(actual, -1); if (j >= 0) mostrarFoto(j); });
        caja.addView(ant, new FrameLayout.LayoutParams(dp(56), dp(96), Gravity.START | Gravity.CENTER_VERTICAL));
        Button sig = flecha("›");
        sig.setOnClickListener(v -> { int j = siguienteFoto(actual, 1); if (j >= 0) mostrarFoto(j); });
        caja.addView(sig, new FrameLayout.LayoutParams(dp(56), dp(96), Gravity.END | Gravity.CENTER_VERTICAL));
        dlgFoto.setContentView(caja);
        dlgFoto.setOnDismissListener(d -> { actual = -1; visor.setImageDrawable(null); });
        dlgFoto.show();
        mostrarFoto(idx);
    }

    private Button flecha(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(30);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.argb(90, 0, 0, 0));
        return b;
    }

    private int siguienteFoto(int desde, int paso) {
        for (int i = desde + paso; i >= 0 && i < elems.size(); i += paso) {
            Elem e = elems.get(i);
            if (!e.carpeta && !e.video) return i;
        }
        return -1;
    }

    private void mostrarFoto(final int idx) {
        actual = idx;
        final Elem e = elems.get(idx);
        pie.setText(e.nombre + "\nCargando…");
        visor.setImageDrawable(null);
        try {
            hilos.execute(() -> {
                Bitmap b = null;
                String fallo = null;
                try { b = decodificar(leer(e.ruta, 80L * 1024 * 1024), 2048); }
                catch (Throwable t) { fallo = t.getClass().getSimpleName(); }
                final Bitmap fin = b;
                final String error = fallo;
                runOnUiThread(() -> {
                    if (actual != idx || isDestroyed()) return;
                    if (fin != null) { visor.setImageBitmap(fin); pie.setText(e.nombre); }
                    else pie.setText(e.nombre + "\nNo se pudo abrir" + (error != null ? " (" + error + ")" : ""));
                });
            });
        } catch (Exception ignorar) { }
    }

    // ---------- Vídeos ----------
    private void abrirVideo(final Elem e) {
        if (e.tam > 400L * 1024 * 1024) { aviso("Vídeo demasiado grande para verlo aquí"); return; }
        aviso("Cargando vídeo…");
        try {
            hilos.execute(() -> {
                try {
                    final java.io.File tmp = new java.io.File(getCacheDir(), "video_tmp");
                    DiskShare d = disco();
                    try (File f = d.openFile(e.ruta, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                         InputStream in = f.getInputStream();
                         FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[131072];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    runOnUiThread(() -> { if (!isDestroyed()) reproducir(tmp); });
                } catch (Throwable t) {
                    runOnUiThread(() -> aviso("No se pudo abrir el vídeo"));
                }
            });
        } catch (Exception ignorar) { }
    }

    private void reproducir(java.io.File archivo) {
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout caja = new FrameLayout(this);
        caja.setBackgroundColor(Color.BLACK);
        final VideoView video = new VideoView(this);
        video.setMediaController(new MediaController(this));
        caja.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        dlg.setContentView(caja);
        video.setOnErrorListener((mp, w, x) -> { aviso("No se pudo reproducir"); dlg.dismiss(); return true; });
        dlg.setOnDismissListener(d -> { try { video.stopPlayback(); } catch (Exception ignorar) { } });
        dlg.show();
        video.setVideoPath(archivo.getAbsolutePath());
        video.start();
    }

    // ---------- Ayudas ----------
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private void aviso(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
