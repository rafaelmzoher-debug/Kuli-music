package com.kuli.mapas;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.io.File;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

public class MainActivity extends Activity implements LocationListener {
    private MapView map;
    private MyLocationNewOverlay locOverlay;
    private TextView txtVel, txtEstado;
    private boolean modoOffline = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        File base = new File(getCacheDir(), "osmdroid");
        base.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));
        Configuration.getInstance().setUserAgentValue("KuliMapas/1.0");

        FrameLayout root = new FrameLayout(this);
        map = new MapView(this);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(6.3);
        map.getController().setCenter(new GeoPoint(40.4168, -3.7038));
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(Color.argb(225, 8, 26, 54));
        top.setPadding(dp(14), dp(28), dp(14), dp(10));

        TextView titulo = new TextView(this);
        titulo.setText("🧭 KULI MAPAS · ESPAÑA  ✦ KULI EDITION ✦");
        titulo.setTextColor(Color.rgb(212, 175, 55));
        titulo.setTextSize(13);
        titulo.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(titulo);

        txtEstado = new TextView(this);
        txtEstado.setText("Modo: ONLINE (descargando y guardando caché)");
        txtEstado.setTextColor(Color.WHITE);
        txtEstado.setTextSize(12);
        top.addView(txtEstado);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        txtVel = new TextView(this);
        txtVel.setText("0\nkm/h");
        txtVel.setTextColor(Color.WHITE);
        txtVel.setTextSize(20);
        txtVel.setTypeface(Typeface.DEFAULT_BOLD);
        txtVel.setGravity(Gravity.CENTER);
        GradientDrawable gdVel = new GradientDrawable();
        gdVel.setColor(Color.argb(235, 10, 34, 68));
        gdVel.setCornerRadius(dp(42));
        gdVel.setStroke(dp(2), Color.rgb(212, 175, 55));
        txtVel.setBackground(gdVel);
        FrameLayout.LayoutParams lpVel = new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM | Gravity.START);
        lpVel.setMargins(dp(14), 0, 0, dp(22));
        root.addView(txtVel, lpVel);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lpPan = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lpPan.setMargins(0, 0, dp(14), dp(22));

        Button btnGps = pastilla("🎯 Mi GPS", true);
        btnGps.setOnClickListener(v -> centrarGps());
        panel.addView(btnGps);

        final Button btnModo = pastilla("🌐 Online", false);
        btnModo.setOnClickListener(v -> {
            modoOffline = !modoOffline;
            map.setUseDataConnection(!modoOffline);
            btnModo.setText(modoOffline ? "📴 Sin Red" : "🌐 Online");
            txtEstado.setText(modoOffline ? "Modo: 100% SIN INTERNET (solo mapas guardados)" : "Modo: ONLINE (descargando y guardando caché)");
        });
        panel.addView(btnModo);

        Button btnBajar = pastilla("📥 Bajar Zona", false);
        btnBajar.setOnClickListener(v -> descargarZonaActual());
        panel.addView(btnBajar);

        root.addView(panel, lpPan);
        setContentView(root);
        iniciarGps();
    }

    private void iniciarGps() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 10);
                return;
            }
            locOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), map);
            locOverlay.enableMyLocation();
            locOverlay.enableFollowLocation();
            map.getOverlays().add(locOverlay);
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, this);
            }
        } catch (Throwable ignored) {}
    }

    private void centrarGps() {
        if (locOverlay != null && locOverlay.getMyLocation() != null) {
            map.getController().animateTo(locOverlay.getMyLocation());
            map.getController().setZoom(16.0);
        } else Toast.makeText(this, "Buscando señal GPS...", Toast.LENGTH_SHORT).show();
    }

    private void descargarZonaActual() {
        if (modoOffline) { Toast.makeText(this, "Activa Online primero para descargar la zona", Toast.LENGTH_SHORT).show(); return; }
        int zMin = (int) Math.max(6, map.getZoomLevelDouble());
        int zMax = Math.min(15, zMin + 3);
        new CacheManager(map).downloadAreaAsync(this, map.getBoundingBox(), zMin, zMax);
        txtEstado.setText("Descargando mapas sin conexión de esta zona...");
    }

    @Override public void onLocationChanged(Location loc) {
        txtVel.setText(Math.round(loc.getSpeed() * 3.6f) + "\nkm/h");
    }
    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) { iniciarGps(); }
    @Override protected void onResume() { super.onResume(); map.onResume(); }
    @Override protected void onPause() { super.onPause(); map.onPause(); }

    private Button pastilla(String txt, boolean dorado) {
        Button b = new Button(this); b.setText(txt); b.setAllCaps(false); b.setTextSize(13); b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable(); gd.setCornerRadius(dp(22));
        if (dorado) { gd.setColor(Color.rgb(212, 175, 55)); b.setTextColor(Color.rgb(8, 24, 48)); }
        else { gd.setColor(Color.argb(235, 10, 34, 68)); gd.setStroke(dp(1), Color.rgb(120, 175, 235)); b.setTextColor(Color.WHITE); }
        b.setBackground(gd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(124), dp(42)); lp.setMargins(0, dp(4), 0, dp(4)); b.setLayoutParams(lp);
        return b;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
