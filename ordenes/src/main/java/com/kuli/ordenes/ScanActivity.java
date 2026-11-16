package com.kuli.ordenes;

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
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Escáner propio: cámara en alta resolución, enfoque al tocar, zoom con dos dedos y linterna.
// Lee DataMatrix, QR y códigos de rayas. Devuelve el texto en el extra "raw".
public class ScanActivity extends ComponentActivity {
    private PreviewView vista;
    private Camera camara;
    private BarcodeScanner lector;
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
        lg.bottomMargin = dp(90);
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
        lector = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF)
            .build());
        final ListenableFuture<ProcessCameraProvider> futuro = ProcessCameraProvider.getInstance(this);
        futuro.addListener(() -> {
            try {
                ProcessCameraProvider proveedor = futuro.get();
                Preview previa = new Preview.Builder().build();
                previa.setSurfaceProvider(vista.getSurfaceProvider());
                ImageAnalysis analisis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1080, 1920))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analisis.setAnalyzer(hilo, this::analizar);
                proveedor.unbindAll();
                camara = proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previa, analisis);
            } catch (Throwable e) {
                android.util.Log.e("KULI_ORDENES", "Cámara", e);
                Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_LONG).show();
                setResult(RESULT_FIRST_USER);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void enfocar(float x, float y) {
        if (camara == null) return;
        MeteringPoint punto = vista.getMeteringPointFactory().createPoint(x, y);
        camara.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(punto,
            FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3, TimeUnit.SECONDS).build());
    }

    @ExperimentalGetImage
    private void analizar(ImageProxy ip) {
        if (terminado || ip.getImage() == null) { ip.close(); return; }
        InputImage img = InputImage.fromMediaImage(ip.getImage(), ip.getImageInfo().getRotationDegrees());
        lector.process(img)
            .addOnSuccessListener(lista -> {
                for (Barcode c : lista) {
                    String v = c.getRawValue();
                    if (v != null && !v.trim().isEmpty()) { entregar(v); break; }
                }
            })
            .addOnCompleteListener(t -> ip.close());
    }

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
