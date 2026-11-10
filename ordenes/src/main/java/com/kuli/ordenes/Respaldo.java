package com.kuli.ordenes;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.protocol.commons.EnumWithValue;
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
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;

// Copia de seguridad de la carpeta «Almacen de Rafa» en el NAS (carpeta Public/Almacen de Rafa).
final class Respaldo {
    static final String CARPETA = "Almacen de Rafa";
    static final String IP_DEFECTO = "192.168.1.46";

    private interface Tarea<T> { T ejecutar(DiskShare disco) throws Exception; }

    static String ip(Context c) {
        String v = c.getSharedPreferences("ordenes", Context.MODE_PRIVATE).getString("nas_ip", IP_DEFECTO);
        return (v == null || v.trim().isEmpty()) ? IP_DEFECTO : v.trim();
    }

    @SuppressWarnings("deprecation")
    static boolean hayWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) { return false; }
    }

    // Devuelve cuántos archivos se han copiado.
    static int subir(Context c, final File raiz) throws Throwable {
        return conNas(c, d -> {
            int[] n = {0};
            if (!d.folderExists(CARPETA)) d.mkdir(CARPETA);
            subirCarpeta(d, raiz, CARPETA, n);
            return n[0];
        });
    }

    // Trae del NAS lo que falte en el móvil. Devuelve cuántos archivos ha bajado.
    static int bajar(Context c, final File raiz, final boolean reemplazar) throws Throwable {
        return conNas(c, d -> {
            int[] n = {0};
            if (!d.folderExists(CARPETA)) throw new java.io.IOException("No hay copia en el NAS todavía");
            bajarCarpeta(d, CARPETA, raiz, reemplazar, n);
            return n[0];
        });
    }

    private static void bajarCarpeta(DiskShare d, String ruta, File dir, boolean reemplazar, int[] n) throws Exception {
        dir.mkdirs();
        for (FileIdBothDirectoryInformation f : d.list(ruta)) {
            String nombre = f.getFileName();
            if (nombre.equals(".") || nombre.equals("..")) continue;
            String r = ruta + "\\" + nombre;
            File local = new File(dir, nombre);
            if (EnumWithValue.EnumUtils.isSet(f.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_DIRECTORY)) {
                bajarCarpeta(d, r, local, reemplazar, n);
            } else {
                boolean datos = nombre.equals("almacen.json") || nombre.equals("albaranes_cargados.txt");
                if (local.exists() && !(reemplazar && datos)) continue;
                try (com.hierynomus.smbj.share.File origen = d.openFile(r,
                         EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                         SMB2CreateDisposition.FILE_OPEN,
                         EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                     InputStream in = origen.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(local)) {
                    byte[] buf = new byte[131072];
                    int k;
                    while ((k = in.read(buf)) != -1) out.write(buf, 0, k);
                }
                n[0]++;
            }
        }
    }

    private static void subirCarpeta(DiskShare d, File dir, String ruta, int[] n) throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String nombre = f.getName();
            if (nombre.startsWith(".")) continue;
            String r = ruta + "\\" + nombre.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (f.isDirectory()) {
                if (!d.folderExists(r)) d.mkdir(r);
                subirCarpeta(d, f, r, n);
            } else {
                boolean datos = nombre.endsWith(".json") || nombre.endsWith(".txt"); // estos cambian: se copian siempre
                if (!datos && d.fileExists(r)
                    && d.getFileInformation(r).getStandardInformation().getEndOfFile() == f.length()) continue;
                copiar(d, f, r);
                n[0]++;
            }
        }
    }

    private static void copiar(DiskShare d, File f, String ruta) throws Exception {
        try (InputStream in = new FileInputStream(f);
             com.hierynomus.smbj.share.File destino = d.openFile(ruta,
                 EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                 SMB2CreateDisposition.FILE_OVERWRITE_IF,
                 EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
             OutputStream out = destino.getOutputStream()) {
            byte[] buf = new byte[131072];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        }
    }

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
                    android.util.Log.e("KULI_ORDENES", "Respaldo: intento fallido", e);
                }
            }
        }
        throw ultimo != null ? ultimo : new java.io.IOException("Sin conexión con el NAS");
    }
}
