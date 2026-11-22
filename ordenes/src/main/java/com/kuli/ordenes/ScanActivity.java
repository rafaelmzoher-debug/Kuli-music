package com.kuli.ordenes;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.mlkit.vision.barcode.*;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import android.media.Image;
import com.google.zxing.common.HybridBinarizer;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Escáner propio: cámara en alta resolución, enfoque al tocar, zoom con dos dedos y linterna.
// Lee DataMatrix, QR y códigos de rayas. Devuelve el texto en el extra "raw".
public class ScanActivity extends ComponentActivity {
    private PreviewView vista;
    private Camera camara;
    private BarcodeScanner lector;
    private final AtomicBoolean bloqueado = new AtomicBoolean(false);
    private final ExecutorService hilo = Executors.newSingleThreadExecutor();
    private volatile boolean terminado = false;
    private boolean linterna = false;
    private ScaleGestureDetector zoomGesto;
    private long ultimoPellizco = 0;

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.BLACK);
        vista = new PreviewView(this);
        vista.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        raiz.addView(vista, new FrameLayout.LayoutParams(-1, -1));

        // marco de ayuda
        View marco = new View(this);
        marco.setBackgroundColor(Color.TRANSPARENT);
        TextView guia = new TextView(this);
        guia.setText("Apunta al código · toca para enfocar · dos dedos para acercar");
        guia.setTextColor(Color.WHITE);
        guia.setTextSize(14);
        guia.setGravity(Gravity.CENTER);
        guia.setBackgroundColor(Color.argb(140, 0, 0, 0));
        guia.setPadding(dp(12), dp(10), dp(12), dp(10));
        FrameLayout.LayoutParams lg = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lg.bottomMargin = dp(130);
        raiz.addView(guia, lg);

        Button cerrar = boton("✕");
        cerrar.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(dp(56), dp(48), Gravity.TOP | Gravity.START);
        lc.topMargin = dp(36); lc.leftMargin = dp(12);
        raiz.addView(cerrar, lc);

        Button luz = boton("🔦");
        luz.setOnClickListener(v -> {
            if (camara == null) return;
            linterna = !linterna;
            camara.getCameraControl().enableTorch(linterna);
        });
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(56), dp(48), Gravity.TOP | Gravity.END);
        ll.topMargin = dp(36); ll.rightMargin = dp(12);
        raiz.addView(luz, ll);

        Button mano = boton("✍ Escribir el nº de serie a mano"); // V223
        mano.setTextSize(13);
        mano.setOnClickListener(v -> { setResult(2); finish(); });
        FrameLayout.LayoutParams lmm = new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM);
        lmm.bottomMargin = dp(76); lmm.leftMargin = dp(24); lmm.rightMargin = dp(24);
        raiz.addView(mano, lmm);

        Button google = boton("Probar el escáner de Google");
        google.setTextSize(13);
        google.setOnClickListener(v -> { setResult(RESULT_FIRST_USER); finish(); });
        FrameLayout.LayoutParams lgg = new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM);
        lgg.bottomMargin = dp(24); lgg.leftMargin = dp(24); lgg.rightMargin = dp(24);
        raiz.addView(google, lgg);
        setContentView(raiz);

        zoomGesto = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (camara == null || camara.getCameraInfo().getZoomState().getValue() == null) return true;
                float z = camara.getCameraInfo().getZoomState().getValue().getZoomRatio();
                camara.getCameraControl().setZoomRatio(z * d.getScaleFactor());
                ultimoPellizco = System.currentTimeMillis();
                return true;
            }
        });
        vista.setOnTouchListener((v, e) -> {
            zoomGesto.onTouchEvent(e);
            if (e.getAction() == MotionEvent.ACTION_UP && System.currentTimeMillis() - ultimoPellizco > 400) {
                enfocar(e.getX(), e.getY());
                v.performClick();
            }
            return true;
        });

        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) arrancar();
        else requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 7);
    }

    private Button boton(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundColor(Color.argb(150, 0, 0, 0));
        return b;
    }

    @Override public void onRequestPermissionsResult(int cod, String[] p, int[] r) {
        super.onRequestPermissionsResult(cod, p, r);
        if (cod == 7) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) arrancar();
            else { Toast.makeText(this, "Sin permiso de cámara no se puede escanear", Toast.LENGTH_LONG).show(); setResult(RESULT_CANCELED); finish(); }
        }
    }

    private void arrancar() {
        lector = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build());
        final ListenableFuture<ProcessCameraProvider> futuro = ProcessCameraProvider.getInstance(this);
        futuro.addListener(() -> {
            try {
                ProcessCameraProvider proveedor = futuro.get();
                Preview previa = new Preview.Builder().build();
                previa.setSurfaceProvider(vista.getSurfaceProvider());
                ImageAnalysis analisis = new ImageAnalysis.Builder().setTargetResolution(new Size(1440, 2560)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analisis.setAnalyzer(hilo, this::analizar);
                proveedor.unbindAll();
                camara = proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previa, analisis);
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        }, ContextCompat.getMainExecutor(this));
    }
    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void analizar(ImageProxy ip) {
        if (terminado || bloqueado.get()) { ip.close(); return; }
        Image img = ip.getImage();
        if (img == null) { ip.close(); return; }
        lector.process(InputImage.fromMediaImage(img, ip.getImageInfo().getRotationDegrees()))
            .addOnSuccessListener(hilo, list -> {
                boolean leido = false;
                for (Barcode c : list) {
                    if (c.getRawValue() != null && !c.getRawValue().trim().isEmpty()) {
                        if (bloqueado.compareAndSet(false, true)) entregar(c.getRawValue().trim());
                        leido = true;
                        break;
                    }
                }
                if (!leido && !terminado && (++cuadros % 2) == 0) { // V223: segundo lector (ZXing, con más esfuerzo)
                    String z = leerConZxing(ip);
                    if (z != null && bloqueado.compareAndSet(false, true)) entregar(z);
                }
            }).addOnCompleteListener(hilo, t -> ip.close());
    }
    private int cuadros = 0;
    private com.google.zxing.MultiFormatReader zx;

    // V223: lee DataMatrix / QR / rayas con ZXing sobre la imagen en blanco y negro. Devuelve null si no hay nada.
    private String leerConZxing(ImageProxy ip) {
        try {
            if (zx == null) {
                zx = new com.google.zxing.MultiFormatReader();
                java.util.Map<com.google.zxing.DecodeHintType, Object> h = new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);
                h.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
                h.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS, java.util.Arrays.asList(
                    com.google.zxing.BarcodeFormat.DATA_MATRIX, com.google.zxing.BarcodeFormat.QR_CODE,
                    com.google.zxing.BarcodeFormat.CODE_128, com.google.zxing.BarcodeFormat.CODE_39));
                zx.setHints(h);
            }
            java.nio.ByteBuffer buf = ip.getPlanes()[0].getBuffer();
            int ancho = ip.getWidth(), alto = ip.getHeight(), paso = ip.getPlanes()[0].getRowStride();
            byte[] y = new byte[buf.remaining()];
            buf.get(y);
            com.google.zxing.PlanarYUVLuminanceSource fuente =
                new com.google.zxing.PlanarYUVLuminanceSource(y, paso, alto, 0, 0, ancho, alto, false);
            com.google.zxing.Result r = zx.decodeWithState(new com.google.zxing.BinaryBitmap(new HybridBinarizer(fuente)));
            String t = r == null ? null : r.getText();
            return (t == null || t.trim().isEmpty()) ? null : t.trim();
        } catch (Throwable e) { return null; }
        finally { if (zx != null) zx.reset(); }
    }

    private void enfocar(float x, float y){if(camara!=null)camara.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(vista.getMeteringPointFactory().createPoint(x,y)).build());}
    private void entregar(String v) {
        if (terminado) return;
        terminado = true;
        try { ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(60); } catch (Throwable ignorar) { }
        setResult(RESULT_OK, new Intent().putExtra("raw", v));
        runOnUiThread(this::finish);
    }

    @Override protected void onDestroy() {
        terminado = true;
        try { if (lector != null) lector.close(); } catch (Throwable ignorar) { }
        hilo.shutdown();
        super.onDestroy();
    }
}
