package com.kuli.mapas;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
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
import org.osmdroid.tileprovider.tilesource.*;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.*;
import org.osmdroid.views.overlay.mylocation.*;

public class MainActivity extends Activity implements LocationListener {
    private MapView map;
    private MyLocationNewOverlay locOverlay;
    private EditText txtBuscar;
    private TextView txtVel, txtLimite, txtBanner;
    private boolean modoOffline = false, modoCoche = true, modoNoche = false;
    private int limiteActual = 120, ultimoRadarAvisado = -1;
    private TextToSpeech tts;
    private final List<double[]> radares = new ArrayList<>();
    private final List<Marker> marcadoresPoi = new ArrayList<>();
    private Marker marcadorDestino;
    private Polyline lineaRuta;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        File base = new File(getCacheDir(), "osmdroid"); base.mkdirs();
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));
        Configuration.getInstance().setUserAgentValue("KuliMapas/3.0");

        tts = new TextToSpeech(this, st -> { if (st == TextToSpeech.SUCCESS) tts.setLanguage(new Locale("es", "ES")); });
        cargarRadares();

        FrameLayout root = new FrameLayout(this);
        map = new MapView(this);
        // Mapa claro estilo Google Maps (Carto Voyager)
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(14.5);
        map.getController().setCenter(new GeoPoint(36.7014, -4.4452));
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        pintarRadaresEnMapa();

        // Zona superior estilo Google Maps: Capsula blanca "Buscar aqui" + Chips horizontales
        LinearLayout topWrap = new LinearLayout(this);
        topWrap.setOrientation(LinearLayout.VERTICAL);
        topWrap.setPadding(dp(12), dp(28), dp(12), dp(6));

        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable gdBus = new GradientDrawable();
        gdBus.setColor(Color.WHITE);
        gdBus.setCornerRadius(dp(26));
        gdBus.setStroke(dp(1), Color.rgb(220, 224, 230));
        barra.setBackground(gdBus);
        barra.setElevation(dp(6));
        barra.setPadding(dp(14), dp(2), dp(8), dp(2));

        txtBuscar = new EditText(this);
        txtBuscar.setHint("Buscar aquí");
        txtBuscar.setHintTextColor(Color.rgb(110, 118, 128));
        txtBuscar.setTextColor(Color.rgb(32, 33, 36));
        txtBuscar.setTextSize(15);
        txtBuscar.setSingleLine(true);
        txtBuscar.setBackgroundColor(Color.TRANSPARENT);
        txtBuscar.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        txtBuscar.setOnEditorActionListener((v, id, ev) -> { buscarDireccion(txtBuscar.getText().toString()); return true; });
        barra.addView(txtBuscar, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button btnLupa = miniBtn("🔍"); btnLupa.setOnClickListener(v -> buscarDireccion(txtBuscar.getText().toString())); barra.addView(btnLupa);
        Button btnMic = miniBtn("🎙️"); btnMic.setOnClickListener(v -> escucharVoz()); barra.addView(btnMic);
        topWrap.addView(barra);

        // Fila de chips rapidos: Gasolineras, Restaurantes, Supermercados, + Radar
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(8), 0, dp(4));
        chips.addView(chip("⛽ Gasolineras", "gasolinera"));
        chips.addView(chip("🍴 Restaurantes", "restaurante"));
        chips.addView(chip("🛒 Supermercados", "supermercado"));
        Button chRad = chip("🚨 + Radar", "");
        chRad.setOnClickListener(v -> anadirRadarAqui());
        chips.addView(chRad);
        hsv.addView(chips);
        topWrap.addView(hsv);

        txtBanner = new TextView(this);
        txtBanner.setTextColor(Color.rgb(25, 45, 85));
        txtBanner.setTextSize(12);
        txtBanner.setTypeface(Typeface.DEFAULT_BOLD);
        txtBanner.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable gdBan = new GradientDrawable(); gdBan.setColor(Color.argb(235, 255, 255, 255)); gdBan.setCornerRadius(dp(14));
        txtBanner.setBackground(gdBan);
        txtBanner.setText("🧭 KULI MAPAS · Online · Modo Coche activo");
        topWrap.addView(txtBanner);
        root.addView(topWrap, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Velocimetro + Senal de limite abajo izquierda (encima de barra inferior)
        LinearLayout velBox = new LinearLayout(this);
        velBox.setOrientation(LinearLayout.HORIZONTAL);
        velBox.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams lpVel = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lpVel.setMargins(dp(14), 0, 0, dp(76));

        txtVel = new TextView(this);
        txtVel.setText("0\nkm/h"); txtVel.setTextColor(Color.WHITE); txtVel.setTextSize(17);
        txtVel.setTypeface(Typeface.DEFAULT_BOLD); txtVel.setGravity(Gravity.CENTER);
        txtVel.setBackground(circulo(Color.argb(242, 16, 44, 86), Color.rgb(212, 175, 55), 3));
        velBox.addView(txtVel, new LinearLayout.LayoutParams(dp(72), dp(72)));

        txtLimite = new TextView(this);
        txtLimite.setText("120"); txtLimite.setTextColor(Color.BLACK); txtLimite.setTextSize(19);
        txtLimite.setTypeface(Typeface.DEFAULT_BOLD); txtLimite.setGravity(Gravity.CENTER);
        txtLimite.setBackground(circulo(Color.WHITE, Color.rgb(225, 35, 45), 6));
        LinearLayout.LayoutParams lpLim = new LinearLayout.LayoutParams(dp(54), dp(54));
        lpLim.setMargins(dp(8), 0, 0, 0);
        velBox.addView(txtLimite, lpLim);
        root.addView(velBox, lpVel);

        // Botones flotantes circulares derecha (como en Google Maps: Centrar GPS + Modo Coche/Andando)
        LinearLayout fabCol = new LinearLayout(this);
        fabCol.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lpFab = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lpFab.setMargins(0, 0, dp(14), dp(76));

        Button fabGps = fabBtn("🎯", Color.WHITE, Color.rgb(26, 115, 232));
        fabGps.setOnClickListener(v -> centrarGps());
        fabCol.addView(fabGps);

        final Button fabRuta = fabBtn("🚗", Color.rgb(0, 121, 107), Color.WHITE);
        fabRuta.setOnClickListener(v -> {
            modoCoche = !modoCoche;
            fabRuta.setText(modoCoche ? "🚗" : "🚶");
            txtBanner.setText(modoCoche ? "🚗 Modo COCHE: ruta por carretera y radares" : "🚶 Modo ANDANDO: ruta peatonal");
            if (marcadorDestino != null) mostrarDestino(marcadorDestino.getPosition(), marcadorDestino.getTitle());
        });
        fabCol.addView(fabRuta);
        root.addView(fabCol, lpFab);

        // Barra inferior blanca estilo Google Maps (Explorar, Guardados, Sin Red / Bajar)
        LinearLayout navBot = new LinearLayout(this);
        navBot.setOrientation(LinearLayout.HORIZONTAL);
        navBot.setBackgroundColor(Color.WHITE);
        navBot.setElevation(dp(10));
        navBot.setPadding(dp(8), dp(6), dp(8), dp(6));

        Button tabExp = tabBtn("📍\nExplorar"); tabExp.setOnClickListener(v -> centrarGps());
        Button tabGua = tabBtn("🔖\nGuardados"); tabGua.setOnClickListener(v -> verGuardados());
        final Button tabRed = tabBtn("🌐\nOnline");
        tabRed.setOnClickListener(v -> {
            modoOffline = !modoOffline; map.setUseDataConnection(!modoOffline);
            tabRed.setText(modoOffline ? "📴\nSin Red" : "🌐\nOnline");
            txtBanner.setText(modoOffline ? "📴 Modo 100% SIN INTERNET (mapas y radares locales)" : "🌐 Modo ONLINE (caché automática activa)");
        });
        Button tabBaj = tabBtn("📥\nBajar Zona"); tabBaj.setOnClickListener(v -> descargarZonaActual());

        navBot.addView(tabExp, new LinearLayout.LayoutParams(0, -2, 1f));
        navBot.addView(tabGua, new LinearLayout.LayoutParams(0, -2, 1f));
        navBot.addView(tabRed, new LinearLayout.LayoutParams(0, -2, 1f));
        navBot.addView(tabBaj, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(navBot, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(root);
        iniciarGps();
    }

    private void escucharVoz() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Di una dirección o sitio (ej: Avenida de la Luz Málaga)");
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
        String clean = q.trim();
        txtBanner.setText("🔍 Buscando: " + clean + "...");
        new Thread(() -> {
            // Motor 1: ArcGIS World Geocoder (encuentra cualquier calle y numero en Espana al instante)
            try {
                String u0 = "https://arcgis.com" + URLEncoder.encode(clean, "UTF-8");
                HttpURLConnection c0 = (HttpURLConnection) new URL(u0).openConnection();
                c0.setRequestProperty("User-Agent", "Mozilla/5.0");
                c0.setConnectTimeout(6000); c0.setReadTimeout(6000);
                BufferedReader br0 = new BufferedReader(new InputStreamReader(c0.getInputStream()));
                StringBuilder sb0 = new StringBuilder(); String l0;
                while ((l0 = br0.readLine()) != null) sb0.append(l0);
                JSONArray cands = new JSONObject(sb0.toString()).getJSONArray("candidates");
                if (cands.length() > 0) {
                    JSONObject top = cands.getJSONObject(0);
                    JSONObject loc = top.getJSONObject("location");
                    double lat = loc.getDouble("y"), lon = loc.getDouble("x");
                    String nom = top.optString("address", clean);
                    runOnUiThread(() -> mostrarDestino(new GeoPoint(lat, lon), nom));
                    return;
                }
            } catch (Throwable ignored) {}
            // Motor 2: Photon con User-Agent Mozilla
            try {
                GeoPoint ref = (locOverlay != null && locOverlay.getMyLocation() != null) ? locOverlay.getMyLocation() : (GeoPoint) map.getMapCenter();
                String u1 = "https://komoot.io" + ref.getLatitude() + "&lon=" + ref.getLongitude() + "&q=" + URLEncoder.encode(clean, "UTF-8");
                HttpURLConnection c1 = (HttpURLConnection) new URL(u1).openConnection();
                c1.setRequestProperty("User-Agent", "Mozilla/5.0");
                c1.setConnectTimeout(6000);
                BufferedReader br1 = new BufferedReader(new InputStreamReader(c1.getInputStream()));
                StringBuilder sb1 = new StringBuilder(); String l1;
                while ((l1 = br1.readLine()) != null) sb1.append(l1);
                JSONArray feats = new JSONObject(sb1.toString()).getJSONArray("features");
                if (feats.length() > 0) {
                    JSONObject f0 = feats.getJSONObject(0);
                    JSONArray coords = f0.getJSONObject("geometry").getJSONArray("coordinates");
                    double lon = coords.getDouble(0), lat = coords.getDouble(1);
                    String nom = f0.getJSONObject("properties").optString("name", clean);
                    runOnUiThread(() -> mostrarDestino(new GeoPoint(lat, lon), nom));
                    return;
                }
            } catch (Throwable ignored) {}
            runOnUiThread(() -> txtBanner.setText("⚠️ Sin conexión o dirección no encontrada: " + clean));
        }).start();
    }

    private void mostrarDestino(GeoPoint dest, String nombre) {
        if (marcadorDestino != null) map.getOverlays().remove(marcadorDestino);
        if (lineaRuta != null) map.getOverlays().remove(lineaRuta);
        marcadorDestino = new Marker(map); marcadorDestino.setPosition(dest); marcadorDestino.setTitle(nombre); map.getOverlays().add(marcadorDestino);
        map.getController().animateTo(dest); map.getController().setZoom(modoCoche ? 15.5 : 17.0);
        txtBanner.setText("📍 " + nombre);
        String prev = getSharedPreferences("kuli_guardados", MODE_PRIVATE).getString("sitios", "");
        if (!prev.contains(nombre)) getSharedPreferences("kuli_guardados", MODE_PRIVATE).edit().putString("sitios", nombre + "|" + dest.getLatitude() + "," + dest.getLongitude() + ";" + prev).apply();
        if (tts != null) tts.speak("Destino fijado", TextToSpeech.QUEUE_FLUSH, null, null);
        if (locOverlay != null && locOverlay.getMyLocation() != null) {
            GeoPoint ini = locOverlay.getMyLocation(); String perfil = modoCoche ? "driving" : "foot";
            new Thread(() -> {
                try {
                    String u = "https://project-osrm.org" + perfil + "/" + ini.getLongitude() + "," + ini.getLatitude() + ";" + dest.getLongitude() + "," + dest.getLatitude() + "?overview=full&geometries=geojson";
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection(); c.setConnectTimeout(5000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String ln; while ((ln = br.readLine()) != null) sb.append(ln);
                    JSONObject r0 = new JSONObject(sb.toString()).getJSONArray("routes").getJSONObject(0);
                    double km = r0.getDouble("distance") / 1000.0; int min = (int) Math.round(r0.getDouble("duration") / 60.0);
                    JSONArray pts = r0.getJSONObject("geometry").getJSONArray("coordinates");
                    ArrayList<GeoPoint> lista = new ArrayList<>();
                    for (int i = 0; i < pts.length(); i++) { JSONArray par = pts.getJSONArray(i); lista.add(new GeoPoint(par.getDouble(1), par.getDouble(0))); }
                    runOnUiThread(() -> {
                        lineaRuta = new Polyline(); lineaRuta.setPoints(lista);
                        lineaRuta.getOutlinePaint().setColor(modoCoche ? Color.rgb(26, 115, 232) : Color.rgb(15, 157, 88));
                        lineaRuta.getOutlinePaint().setStrokeWidth(11f); map.getOverlays().add(lineaRuta); map.invalidate();
                        txtBanner.setText(String.format(Locale.US, "%s %.1f km · %d min · %s", modoCoche ? "🚗" : "🚶", km, min, nombre));
                    });
                } catch (Throwable ignored) {}
            }).start();
        }
    }

    private void verGuardados() {
        String raw = getSharedPreferences("kuli_guardados", MODE_PRIVATE).getString("sitios", "");
        if (raw.isEmpty()) { Toast.makeText(this, "Aún no tienes sitios guardados", Toast.LENGTH_SHORT).show(); return; }
        String[] items = raw.split(";");
        new AlertDialog.Builder(this).setTitle("🔖 Sitios recientes y guardados").setItems(items, (d, i) -> {
            String[] p = items[i].split("\\|");
            if (p.length == 2) { String[] ll = p[1].split(","); mostrarDestino(new GeoPoint(Double.parseDouble(ll[0]), Double.parseDouble(ll[1])), p[0]); }
        }).show();
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
            m.setTitle("🚨 RADAR (" + (int)r[2] + " km/h)"); m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            Bitmap bmp = Bitmap.createBitmap(44, 44, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp); Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            pt.setColor(Color.WHITE); cv.drawCircle(22, 22, 20, pt);
            pt.setColor(Color.rgb(225, 35, 45)); pt.setStyle(Paint.Style.STROKE); pt.setStrokeWidth(5); cv.drawCircle(22, 22, 18, pt);
            pt.setStyle(Paint.Style.FILL); pt.setColor(Color.BLACK); pt.setTextSize(14); pt.setFakeBoldText(true); pt.setTextAlign(Paint.Align.CENTER);
            cv.drawText(String.valueOf((int)r[2]), 22, 27, pt);
            m.setIcon(new BitmapDrawable(getResources(), bmp)); map.getOverlays().add(m);
        }
    }

    private void anadirRadarAqui() {
        GeoPoint p = (locOverlay != null && locOverlay.getMyLocation() != null) ? locOverlay.getMyLocation() : (GeoPoint) map.getMapCenter();
        final int[] lims = {50, 80, 90, 100, 120};
        new AlertDialog.Builder(this).setTitle("Guardar radar").setItems(new String[]{"50","80","90","100","120"}, (d, idx) -> {
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
        txtBanner.setText("📥 Descargando mapas sin conexión...");
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

    private Button chip(String txt, final String query) {
        Button b = new Button(this); b.setText(txt); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(Color.rgb(32, 33, 36));
        GradientDrawable gd = new GradientDrawable(); gd.setColor(Color.WHITE); gd.setCornerRadius(dp(18)); gd.setStroke(dp(1), Color.rgb(218, 220, 224));
        b.setBackground(gd); b.setElevation(dp(3)); b.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34)); lp.setMargins(0, 0, dp(8), 0); b.setLayoutParams(lp);
        if (!query.isEmpty()) b.setOnClickListener(v -> { txtBuscar.setText(query); buscarDireccion(query); });
        return b;
    }
    private Button fabBtn(String txt, int bg, int fg) {
        Button b = new Button(this); b.setText(txt); b.setTextSize(19); b.setTextColor(fg);
        GradientDrawable gd = new GradientDrawable(); gd.setShape(GradientDrawable.OVAL); gd.setColor(bg); gd.setStroke(dp(1), Color.rgb(218, 220, 224));
        b.setBackground(gd); b.setElevation(dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(50)); lp.setMargins(0, dp(6), 0, dp(6)); b.setLayoutParams(lp); return b;
    }
    private Button tabBtn(String txt) {
        Button b = new Button(this); b.setText(txt); b.setAllCaps(false); b.setTextSize(11); b.setTextColor(Color.rgb(60, 64, 67)); b.setBackgroundColor(Color.TRANSPARENT); return b;
    }
    private GradientDrawable circulo(int f, int b, int g) { GradientDrawable gd = new GradientDrawable(); gd.setShape(GradientDrawable.OVAL); gd.setColor(f); gd.setStroke(dp(g), b); return gd; }
    private Button miniBtn(String t) { Button b = new Button(this); b.setText(t); b.setTextSize(17); b.setBackgroundColor(Color.TRANSPARENT); b.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42))); return b; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
