package com.kuli.mapas;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.*;
import org.osmdroid.views.overlay.mylocation.*;

public class MainActivity extends Activity implements LocationListener {
    private MapView map;
    private MyLocationNewOverlay locOverlay;
    private EditText txtBuscar;
    private TextView txtVel, txtLimite, txtEstado;
    private boolean modoOffline = false;
    private int limiteActual = 120, ultimoRadarAvisado = -1;
    private TextToSpeech tts;
    private final List<double[]> radares = new ArrayList<>(); // lat, lon, lim
    private Marker marcadorDestino;
    private Polyline lineaRuta;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        File base = new File(getCacheDir(), "osmdroid"); base.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));
        Configuration.getInstance().setUserAgentValue("KuliMapas/2.0");

        tts = new TextToSpeech(this, st -> { if (st == TextToSpeech.SUCCESS) tts.setLanguage(new Locale("es", "ES")); });
        cargarRadares();

        FrameLayout root = new FrameLayout(this);
        map = new MapView(this);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(6.3);
        map.getController().setCenter(new GeoPoint(40.4168, -3.7038));
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        pintarRadaresEnMapa();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(Color.argb(232, 8, 26, 54));
        top.setPadding(dp(12), dp(28), dp(12), dp(10));

        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable gdBus = new GradientDrawable();
        gdBus.setColor(Color.rgb(16, 44, 86));
        gdBus.setCornerRadius(dp(24));
        gdBus.setStroke(dp(1), Color.rgb(212, 175, 55));
        barra.setBackground(gdBus);
        barra.setPadding(dp(12), dp(2), dp(8), dp(2));

        txtBuscar = new EditText(this);
        txtBuscar.setHint("Buscar calle, pueblo o sitio...");
        txtBuscar.setHintTextColor(Color.rgb(165, 195, 230));
        txtBuscar.setTextColor(Color.WHITE);
        txtBuscar.setTextSize(14);
        txtBuscar.setSingleLine(true);
        txtBuscar.setBackgroundColor(Color.TRANSPARENT);
        txtBuscar.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        txtBuscar.setOnEditorActionListener((v, id, ev) -> { buscarDireccion(txtBuscar.getText().toString()); return true; });
        barra.addView(txtBuscar, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button btnLupa = miniBtn("🔍"); btnLupa.setOnClickListener(v -> buscarDireccion(txtBuscar.getText().toString())); barra.addView(btnLupa);
        Button btnMic = miniBtn("🎙️"); btnMic.setOnClickListener(v -> escucharVoz()); barra.addView(btnMic);
        top.addView(barra);

        txtEstado = new TextView(this);
        txtEstado.setText("🧭 KULI MAPAS · Online (caché automática activa)");
        txtEstado.setTextColor(Color.rgb(212, 175, 55));
        txtEstado.setTextSize(11);
        txtEstado.setPadding(dp(6), dp(6), dp(6), 0);
        top.addView(txtEstado);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout velBox = new LinearLayout(this);
        velBox.setOrientation(LinearLayout.HORIZONTAL);
        velBox.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams lpVel = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lpVel.setMargins(dp(14), 0, 0, dp(22));

        txtVel = new TextView(this);
        txtVel.setText("0\nkm/h"); txtVel.setTextColor(Color.WHITE); txtVel.setTextSize(18);
        txtVel.setTypeface(Typeface.DEFAULT_BOLD); txtVel.setGravity(Gravity.CENTER);
        txtVel.setBackground(circulo(Color.argb(240, 10, 34, 68), Color.rgb(212, 175, 55), 3));
        velBox.addView(txtVel, new LinearLayout.LayoutParams(dp(78), dp(78)));

        txtLimite = new TextView(this);
        txtLimite.setText("120"); txtLimite.setTextColor(Color.BLACK); txtLimite.setTextSize(20);
        txtLimite.setTypeface(Typeface.DEFAULT_BOLD); txtLimite.setGravity(Gravity.CENTER);
        txtLimite.setBackground(circulo(Color.WHITE, Color.rgb(225, 35, 45), 6));
        LinearLayout.LayoutParams lpLim = new LinearLayout.LayoutParams(dp(58), dp(58));
        lpLim.setMargins(dp(8), 0, 0, 0);
        velBox.addView(txtLimite, lpLim);
        root.addView(velBox, lpVel);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lpPan = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lpPan.setMargins(0, 0, dp(14), dp(22));

        Button btnGps = pastilla("🎯 Mi GPS", true); btnGps.setOnClickListener(v -> centrarGps()); panel.addView(btnGps);
        final Button btnModo = pastilla("🌐 Online", false);
        btnModo.setOnClickListener(v -> {
            modoOffline = !modoOffline; map.setUseDataConnection(!modoOffline);
            btnModo.setText(modoOffline ? "📴 Sin Red" : "🌐 Online");
            txtEstado.setText(modoOffline ? "📴 Modo 100% SIN INTERNET (mapas y radares locales)" : "🌐 Modo ONLINE (caché automática activa)");
        });
        panel.addView(btnModo);

        Button btnBajar = pastilla("📥 Bajar Zona", false); btnBajar.setOnClickListener(v -> descargarZonaActual()); panel.addView(btnBajar);
        Button btnRadar = pastilla("🚨 + Radar", false); btnRadar.setOnClickListener(v -> anadirRadarAqui()); panel.addView(btnRadar);

        root.addView(panel, lpPan);
        setContentView(root);
        iniciarGps();
    }

    private void escucharVoz() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Di una dirección, calle o pueblo de España");
            startActivityForResult(i, 77);
        } catch (Throwable e) { Toast.makeText(this, "Voz no disponible", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 77 && res == RESULT_OK && data != null) {
            ArrayList<String> lista = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (lista != null && !lista.isEmpty()) { txtBuscar.setText(lista.get(0)); buscarDireccion(lista.get(0)); }
        }
    }

    private void buscarDireccion(String q) {
        if (q == null || q.trim().isEmpty()) return;
        txtEstado.setText("🔍 Buscando: " + q + "...");
        new Thread(() -> {
            try {
                String url = "https://openstreetmap.org" + URLEncoder.encode(q.trim(), "UTF-8");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent", "KuliMapas/2.0");
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JSONArray arr = new JSONArray(sb.toString());
                if (arr.length() > 0) {
                    JSONObject obj = arr.getJSONObject(0);
                    double lat = obj.getDouble("lat"), lon = obj.getDouble("lon");
                    String nom = obj.getString("display_name");
                    runOnUiThread(() -> mostrarDestino(new GeoPoint(lat, lon), nom));
                } else runOnUiThread(() -> txtEstado.setText("⚠️ No encontrado: " + q));
            } catch (Throwable e) { runOnUiThread(() -> txtEstado.setText("⚠️ Activa Online para buscar direcciones")); }
        }).start();
    }

    private void mostrarDestino(GeoPoint dest, String nombre) {
        if (marcadorDestino != null) map.getOverlays().remove(marcadorDestino);
        if (lineaRuta != null) map.getOverlays().remove(lineaRuta);
        marcadorDestino = new Marker(map); marcadorDestino.setPosition(dest); marcadorDestino.setTitle(nombre); map.getOverlays().add(marcadorDestino);
        if (locOverlay != null && locOverlay.getMyLocation() != null) {
            lineaRuta = new Polyline(); lineaRuta.addPoint(locOverlay.getMyLocation()); lineaRuta.addPoint(dest);
            lineaRuta.getOutlinePaint().setColor(Color.rgb(30, 144, 255)); lineaRuta.getOutlinePaint().setStrokeWidth(10f); map.getOverlays().add(lineaRuta);
        }
        map.getController().animateTo(dest); map.getController().setZoom(15.5); txtEstado.setText("📍 " + nombre);
        if (tts != null) tts.speak("Destino fijado", TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void cargarRadares() {
        double[][] base = {{36.7165,-4.4321,80},{36.6852,-4.4690,100},{36.5101,-4.8824,80},{40.4168,-3.7038,50},{41.3851,2.1734,80}};
        for (double[] r : base) radares.add(r);
        for (String item : getSharedPreferences("kuli_radares", MODE_PRIVATE).getString("lista", "").split(";")) {
            String[] p = item.split(",");
            if (p.length == 3) radares.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])});
        }
    }
    private void pintarRadaresEnMapa() {
        for (double[] r : radares) {
            Marker m = new Marker(map); m.setPosition(new GeoPoint(r[0], r[1]));
            m.setTitle("🚨 RADAR (" + (int)r[2] + " km/h)"); map.getOverlays().add(m);
        }
    }
    private void anadirRadarAqui() {
        GeoPoint p = (locOverlay != null && locOverlay.getMyLocation() != null) ? locOverlay.getMyLocation() : (GeoPoint) map.getMapCenter();
        final int[] lims = {50, 80, 90, 100, 120};
        new android.app.AlertDialog.Builder(this).setTitle("Guardar radar").setItems(new String[]{"50","80","90","100","120"}, (d, idx) -> {
            int lim = lims[idx]; radares.add(new double[]{p.getLatitude(), p.getLongitude(), lim});
            String prev = getSharedPreferences("kuli_radares", MODE_PRIVATE).getString("lista", "");
            getSharedPreferences("kuli_radares", MODE_PRIVATE).edit().putString("lista", prev + p.getLatitude() + "," + p.getLongitude() + "," + lim + ";").apply();
            pintarRadaresEnMapa(); map.invalidate();
        }).show();
    }
    private void iniciarGps() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 10); return;
            }
            locOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), map);
            locOverlay.enableMyLocation(); locOverlay.enableFollowLocation(); map.getOverlays().add(locOverlay);
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, this);
        } catch (Throwable ignored) {}
    }
    private void centrarGps() {
        if (locOverlay != null && locOverlay.getMyLocation() != null) { map.getController().animateTo(locOverlay.getMyLocation()); map.getController().setZoom(16.0); }
    }
    private void descargarZonaActual() {
        if (modoOffline) return;
        int z = (int) Math.max(6, map.getZoomLevelDouble());
        new CacheManager(map).downloadAreaAsync(this, map.getBoundingBox(), z, Math.min(15, z + 3));
        txtEstado.setText("📥 Descargando mapas sin conexión...");
    }
    @Override public void onLocationChanged(Location loc) {
        int kmh = Math.round(loc.getSpeed() * 3.6f); txtVel.setText(kmh + "\nkm/h");
        GeoPoint yo = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        for (int i = 0; i < radares.size(); i++) {
            double[] r = radares.get(i);
            double dist = yo.distanceToAsDouble(new GeoPoint(r[0], r[1]));
            if (dist < 600) {
                limiteActual = (int) r[2]; txtLimite.setText(String.valueOf(limiteActual));
                if (ultimoRadarAvisado != i && tts != null) { ultimoRadarAvisado = i; tts.speak("Atención, radar a " + Math.round(dist) + " metros, límite " + limiteActual, TextToSpeech.QUEUE_FLUSH, null, null); }
            }
        }
        txtVel.setTextColor(kmh > limiteActual ? Color.rgb(255, 75, 85) : Color.WHITE);
    }
    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) { iniciarGps(); }
    @Override protected void onResume() { super.onResume(); map.onResume(); }
    @Override protected void onPause() { super.onPause(); map.onPause(); }
    private GradientDrawable circulo(int f, int b, int g) { GradientDrawable gd = new GradientDrawable(); gd.setShape(GradientDrawable.OVAL); gd.setColor(f); gd.setStroke(dp(g), b); return gd; }
    private Button miniBtn(String t) { Button b = new Button(this); b.setText(t); b.setTextSize(17); b.setBackgroundColor(Color.TRANSPARENT); b.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42))); return b; }
    private Button pastilla(String txt, boolean dorado) {
        Button b = new Button(this); b.setText(txt); b.setAllCaps(false); b.setTextSize(12); b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable(); gd.setCornerRadius(dp(20));
        if (dorado) { gd.setColor(Color.rgb(212, 175, 55)); b.setTextColor(Color.rgb(8, 24, 48)); }
        else { gd.setColor(Color.argb(235, 10, 34, 68)); gd.setStroke(dp(1), Color.rgb(120, 175, 235)); b.setTextColor(Color.WHITE); }
        b.setBackground(gd); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(116), dp(38)); lp.setMargins(0, dp(3), 0, dp(3)); b.setLayoutParams(lp); return b;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
