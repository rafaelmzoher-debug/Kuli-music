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
    private boolean modoOffline = false, modoCoche = true, modoNoche = false, pantallaDespejada = false;
    private LinearLayout topWrapRef, navBotRef;
    private int limiteActual = 120, ultimoRadarAvisado = -1;
    private TextToSpeech tts;
    private final List<double[]> radares = new ArrayList<>();
    private final List<Marker> marcadoresPoi = new ArrayList<>();
    private Marker marcadorDestino;
    private Polyline lineaRuta;
    private Button btnIniciarRuta;
    private boolean navegando = false;
    private final List<String> pasosVoz = new ArrayList<>();
    private final List<GeoPoint> pasosPts = new ArrayList<>();
    private int pasoActual = 0;

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
        map.setTilesScaleFactor(2.3f);
        map.getController().setZoom(16.5);
        map.getController().setZoom(14.5);
        map.getController().setCenter(new GeoPoint(36.7014, -4.4452));
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));
        pintarRadaresEnMapa();

        org.osmdroid.views.overlay.MapEventsOverlay evOverlay = new org.osmdroid.views.overlay.MapEventsOverlay(new org.osmdroid.events.MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint gp) {
                String coord = String.format(Locale.US, "%.5f, %.5f", gp.getLatitude(), gp.getLongitude());
                String[] ops = {"🧭 RUTA HACIA aquí", "⭐ GUARDAR en Mis lugares", "📲 COMPARTIR por WhatsApp"};
                new AlertDialog.Builder(MainActivity.this).setTitle("📍 Punto (" + coord + ")").setItems(ops, (d, idx) -> {
                    if (idx == 0) {
                        mostrarDestino(gp, "Punto seleccionado (" + coord + ")");
                    } else if (idx == 1) {
                        String nom = "Lugar " + coord;
                        String prev = getSharedPreferences("kuli_guardados", MODE_PRIVATE).getString("sitios", "");
                        getSharedPreferences("kuli_guardados", MODE_PRIVATE).edit().putString("sitios", nom + "|" + gp.getLatitude() + "," + gp.getLongitude() + ";" + prev).apply();
                        Toast.makeText(MainActivity.this, "⭐ Guardado en Mis lugares", Toast.LENGTH_SHORT).show();
                    } else if (idx == 2) {
                        Intent wa = new Intent(Intent.ACTION_SEND);
                        wa.setType("text/plain");
                        wa.putExtra(Intent.EXTRA_TEXT, "📍 Ubicación Kuli Mapas: https://google.com" + gp.getLatitude() + "," + gp.getLongitude());
                        startActivity(Intent.createChooser(wa, "Compartir ubicación"));
                    }
                }).show();
                return true;
            }
            @Override public boolean longPressHelper(GeoPoint gp) {
                pantallaDespejada = !pantallaDespejada;
                int vis = pantallaDespejada ? View.GONE : View.VISIBLE;
                if (topWrapRef != null) topWrapRef.setVisibility(vis);
                if (navBotRef != null) navBotRef.setVisibility(vis);
                Toast.makeText(MainActivity.this, pantallaDespejada ? "👁️ Pantalla despejada (mantén pulsado para volver)" : "👁️ Controles visibles", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        map.getOverlays().add(0, evOverlay);


        // Zona superior estilo Google Maps: Capsula blanca "Buscar aqui" + Chips horizontales
        LinearLayout topWrap = new LinearLayout(this); topWrapRef = topWrap;
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
        Button chSyncRad = chip("🔄 Radares ES", "");
        chSyncRad.setOnClickListener(v -> descargarTodosRadaresEspana());
        chips.addView(chSyncRad);
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

        final Button fabNoche = fabBtn("🌙", Color.rgb(16, 44, 86), Color.rgb(212, 175, 55));
        fabNoche.setOnClickListener(v -> {
            modoNoche = !modoNoche;
            fabNoche.setText(modoNoche ? "☀️" : "🌙");
            if (modoNoche) {
                float[] neg = {
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                };
                map.getOverlayManager().getTilesOverlay().setColorFilter(new ColorMatrixColorFilter(neg));
            } else {
                map.getOverlayManager().getTilesOverlay().setColorFilter(null);
            }
            map.invalidate();
        });
        fabCol.addView(fabNoche);
        root.addView(fabCol, lpFab);

        // Barra inferior blanca estilo Google Maps (Explorar, Guardados, Sin Red / Bajar)
        LinearLayout navBot = new LinearLayout(this); navBotRef = navBot;
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

        btnIniciarRuta = new Button(this);
        btnIniciarRuta.setText("▶️ INICIAR RUTA");
        btnIniciarRuta.setTextColor(Color.WHITE);
        btnIniciarRuta.setTextSize(15);
        btnIniciarRuta.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gdIni = new GradientDrawable();
        gdIni.setColor(Color.rgb(26, 115, 232));
        gdIni.setCornerRadius(dp(24));
        btnIniciarRuta.setBackground(gdIni);
        btnIniciarRuta.setElevation(dp(8));
        btnIniciarRuta.setVisibility(View.GONE);
        btnIniciarRuta.setOnClickListener(v -> alternarNavegacion());
        FrameLayout.LayoutParams lpIni = new FrameLayout.LayoutParams(dp(180), dp(46), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lpIni.setMargins(0, 0, 0, dp(76));
        root.addView(btnIniciarRuta, lpIni);

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
            // Motor 1: Geocoder nativo de Android (sin bloqueos ni limites)
            try {
                Geocoder geo = new Geocoder(this, new Locale("es", "ES"));
                List<Address> res = geo.getFromLocationName(clean + ", España", 1);
                if (res != null && !res.isEmpty()) {
                    Address a = res.get(0);
                    String nom = a.getAddressLine(0) != null ? a.getAddressLine(0) : clean;
                    runOnUiThread(() -> mostrarDestino(new GeoPoint(a.getLatitude(), a.getLongitude()), nom));
                    return;
                }
            } catch (Throwable ignored) {}

            // Motor 2: Respaldo Nominatim directo
            try {
                String u = "https://openstreetmap.org" + URLEncoder.encode(clean, "UTF-8");
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setRequestProperty("User-Agent", "KuliMapasApp/3.0 (contacto@kulimapas.local)");
                c.setConnectTimeout(6000);
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder sb = new StringBuilder(); String l;
                while ((l = br.readLine()) != null) sb.append(l);
                JSONArray arr = new JSONArray(sb.toString());
                if (arr.length() > 0) {
                    JSONObject obj = arr.getJSONObject(0);
                    double lat = obj.getDouble("lat"), lon = obj.getDouble("lon");
                    String nom = obj.optString("display_name", clean);
                    runOnUiThread(() -> mostrarDestino(new GeoPoint(lat, lon), nom));
                    return;
                }
            } catch (Throwable ignored) {}

            // Motor 3: Base local sin internet
            String low = clean.toLowerCase();
            if (low.contains("paloma") && low.contains("malaga")) { runOnUiThread(() -> mostrarDestino(new GeoPoint(36.6974, -4.4426), "Calle Palomas, Málaga (Offline)")); return; }
            if (low.contains("luz") && low.contains("malaga")) { runOnUiThread(() -> mostrarDestino(new GeoPoint(36.7014, -4.4452), "Av. de la Luz, Málaga (Offline)")); return; }
            if (low.contains("larios")) { runOnUiThread(() -> mostrarDestino(new GeoPoint(36.7185, -4.4216), "Calle Marqués de Larios, Málaga (Offline)")); return; }
            if (low.contains("malaga") || low.contains("málaga")) { runOnUiThread(() -> mostrarDestino(new GeoPoint(36.7213, -4.4214), "Málaga Centro (Offline)")); return; }
            if (low.contains("madrid")) { runOnUiThread(() -> mostrarDestino(new GeoPoint(40.4168, -3.7038), "Madrid Centro (Offline)")); return; }
            if (low.contains("sevilla")) { runOnUiThread(() -> mostrarDestino(new GeoPoint(37.3891, -5.9845), "Sevilla Centro (Offline)")); return; }
            runOnUiThread(() -> txtBanner.setText("⚠️ No se encontró: " + clean));
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
        if (btnIniciarRuta != null) btnIniciarRuta.setVisibility(View.VISIBLE);
        if (locOverlay != null && locOverlay.getMyLocation() != null) {
            GeoPoint ini = locOverlay.getMyLocation(); String perfil = modoCoche ? "driving" : "foot";
            new Thread(() -> {
                try {
                    String u = "https://project-osrm.org" + perfil + "/" + ini.getLongitude() + "," + ini.getLatitude() + ";" + dest.getLongitude() + "," + dest.getLatitude() + "?overview=full&geometries=geojson&steps=true";
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
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        cal.add(java.util.Calendar.MINUTE, min);
                        String eta = String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
                        txtBanner.setText(String.format(Locale.US, "%s %.1f km · %d min (Llegas %s) · %s", modoCoche ? "🚗" : "🚶", km, min, eta, nombre));
                        try {
                        try {
                        pasosVoz.clear(); pasosPts.clear(); pasoActual = 0;
                        JSONArray steps = r0.getJSONArray("legs").getJSONObject(0).getJSONArray("steps");
                        for (int k = 0; k < steps.length(); k++) {
                            JSONObject st = steps.getJSONObject(k);
                            String calle = st.optString("name", "");
                            String tipo = st.getJSONObject("maneuver").optString("modifier", "recto");
                            JSONArray locSt = st.getJSONObject("maneuver").getJSONArray("location");
                            pasosPts.add(new GeoPoint(locSt.getDouble(1), locSt.getDouble(0)));
                            pasosVoz.add("Gire " + tipo.replace("left","a la izquierda").replace("right","a la derecha").replace("straight","recto") + (calle.isEmpty() ? "" : " hacia " + calle));
                        }
                        btnIniciarRuta.setVisibility(View.VISIBLE);
                        } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
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

    
    private void alternarNavegacion() {
        navegando = !navegando;
        if (navegando) {
            btnIniciarRuta.setText("⏹️ SALIR");
            btnIniciarRuta.getBackground().setTint(Color.rgb(217, 48, 37));
            txtBanner.setTextSize(17);
            txtBanner.setTextColor(Color.WHITE);
            txtBanner.getBackground().setTint(Color.rgb(12, 42, 88));
            txtBanner.setPadding(dp(14), dp(12), dp(14), dp(12));
            if (lineaRuta != null) lineaRuta.getOutlinePaint().setStrokeWidth(18f); else if (marcadorDestino != null) mostrarDestino(marcadorDestino.getPosition(), marcadorDestino.getTitle());
            if (locOverlay != null) {
                locOverlay.enableFollowLocation();
                if (locOverlay.getMyLocation() != null) map.getController().animateTo(locOverlay.getMyLocation());
            }
            map.getController().setZoom(18.8);
            String msg = pasosVoz.isEmpty() ? "Siga la ruta azul hacia su destino" : pasosVoz.get(0);
            txtBanner.setText("⬆️ " + msg);
            if (tts != null) tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null);
        } else {
            btnIniciarRuta.setText("▶️ INICIAR RUTA");
            btnIniciarRuta.getBackground().setTint(Color.rgb(26, 115, 232));
            btnIniciarRuta.setVisibility(View.GONE);
            txtBanner.setTextSize(12);
            txtBanner.setTextColor(Color.rgb(25, 45, 85));
            txtBanner.getBackground().setTint(Color.argb(235, 255, 255, 255));
            map.setMapOrientation(0f);
            if (lineaRuta != null) map.getOverlays().remove(lineaRuta);
            if (marcadorDestino != null) map.getOverlays().remove(marcadorDestino);
            map.invalidate();
            txtBanner.setText("🧭 Ruta finalizada");
        }
    }

    private void cargarRadares() {
        radares.clear();
        double[][] baseMalaga = {
            // Malaga Capital y Rondas (MA-20, MA-21, MA-22, Av. Andalucia, Valle-Inclan)
            {36.6852, -4.4690, 100}, {36.6985, -4.4580, 100}, {36.7165, -4.4321, 80},
            {36.7235, -4.4480, 80},  {36.7310, -4.4350, 80},  {36.6710, -4.4820, 100},
            {36.6540, -4.4920, 100}, {36.7090, -4.4210, 50},  {36.7210, -4.4150, 50},
            // A-7 Costa del Sol Oriental (Limonar, Cerrado Calderon, El Palo, Rincon, Velez, Torrox, Nerja)
            {36.7248, -4.3895, 80},  {36.7192, -4.3510, 80},  {36.7155, -4.2810, 100},
            {36.7220, -4.2250, 100}, {36.7485, -4.0920, 120}, {36.7450, -3.9620, 100},
            {36.7510, -3.8820, 100},
            // A-7 / AP-7 Costa del Sol Occidental (Torremolinos, Benalmadena, Fuengirola, Mijas, Marbella, San Pedro, Estepona, Manilva)
            {36.6250, -4.5080, 80},  {36.6012, -4.5280, 80},  {36.5780, -4.5750, 100},
            {36.5380, -4.6345, 100}, {36.5120, -4.7150, 100}, {36.4980, -4.7850, 80},
            {36.5101, -4.8824, 80},  {36.4890, -4.9850, 80},  {36.4580, -5.0650, 100},
            {36.4250, -5.1450, 100}, {36.3650, -5.2250, 100},
            // A-45 / AP-46 Malaga - Antequera - Cordoba (Tunel Casabermeja, Las Pedrizas, Antequera)
            {36.7680, -4.4250, 100}, {36.8120, -4.4210, 100}, {36.8850, -4.4480, 100},
            {36.9650, -4.5120, 120}, {37.0185, -4.5580, 120}, {37.1450, -4.6120, 120},
            // A-357 / A-355 / A-397 Interior Malaga (Campanillas, Cartama, Coin, Ronda)
            {36.7280, -4.5250, 100}, {36.7350, -4.6150, 100}, {36.7120, -4.7150, 90},
            {36.6650, -4.7650, 90},  {36.6120, -4.9250, 80},  {36.7350, -5.1550, 80},
            // A-92 / SE-30 / GR-30 / A-4 Andalucia
            {37.0550, -4.7250, 120}, {37.1150, -4.3850, 120}, {37.1850, -3.9250, 120},
            {37.1773, -3.5986, 90},  {37.3891, -5.9845, 80},  {37.8882, -4.7794, 100},
            {36.5271, -6.2886, 80},  {36.8340, -2.4637, 80},  {37.7796, -3.7849, 90}
        };
        for (double[] r : baseMalaga) radares.add(r);
        try {
            File f = new File(getFilesDir(), "radares_espana.csv");
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                String ln;
                while ((ln = br.readLine()) != null) {
                    String[] parts = ln.split(",");
                    if (parts.length >= 3) {
                        radares.add(new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])});
                    }
                }
                br.close();
            }
        } catch (Throwable ignored) {}
        for (String item : getSharedPreferences("kuli_radares", MODE_PRIVATE).getString("lista", "").split(";")) {
            String[] pts = item.split(",");
            if (pts.length == 3) radares.add(new double[]{Double.parseDouble(pts[0]), Double.parseDouble(pts[1]), Double.parseDouble(pts[2])});
        }
    }

    private void descargarTodosRadaresEspana() {
        cargarRadares(); pintarRadaresEnMapa(); map.invalidate(); txtBanner.setText("✅ " + radares.size() + " radares de Málaga y Andalucía activos");
        new Thread(() -> {
            String[] urls = {
                "https://kumi.systems",
                "https://overpass-api.de"
            };
            // Caja geografica de Andalucia y Malaga (rapida, sin timeout) + principales corredores
            String q = "[out:json][timeout:20];(node[\"highway\"=\"speed_camera\"](35.95,-7.55,38.75,-1.60);node[\"highway\"=\"speed_camera\"](38.75,-6.50,41.20,-2.50););out body;";
            for (String api : urls) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(api).openConnection();
                    c.setRequestMethod("POST");
                    c.setRequestProperty("User-Agent", "KuliMapas/4.0");
                    c.setDoOutput(true);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.getOutputStream().write(("data=" + URLEncoder.encode(q, "UTF-8")).getBytes("UTF-8"));
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder(); String ln;
                    while ((ln = br.readLine()) != null) sb.append(ln);
                    JSONArray elems = new JSONObject(sb.toString()).getJSONArray("elements");
                    if (elems.length() > 0) {
                        File out = new File(getFilesDir(), "radares_espana.csv");
                        FileWriter fw = new FileWriter(out);
                        int count = 0;
                        for (int i = 0; i < elems.length(); i++) {
                            JSONObject el = elems.getJSONObject(i);
                            double lat = el.getDouble("lat"), lon = el.getDouble("lon");
                            int lim = 90;
                            if (el.has("tags")) {
                                String ms = el.getJSONObject("tags").optString("maxspeed", "90").replaceAll("[^0-9]", "");
                                if (!ms.isEmpty()) lim = Integer.parseInt(ms);
                            }
                            fw.write(lat + "," + lon + "," + lim + "\n");
                            count++;
                        }
                        fw.close();
                        final int total = count;
                        runOnUiThread(() -> {
                            cargarRadares();
                            pintarRadaresEnMapa();

        org.osmdroid.views.overlay.MapEventsOverlay evOverlay = new org.osmdroid.views.overlay.MapEventsOverlay(new org.osmdroid.events.MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint gp) {
                String coord = String.format(Locale.US, "%.5f, %.5f", gp.getLatitude(), gp.getLongitude());
                String[] ops = {"🧭 RUTA HACIA aquí", "⭐ GUARDAR en Mis lugares", "📲 COMPARTIR por WhatsApp"};
                new AlertDialog.Builder(MainActivity.this).setTitle("📍 Punto (" + coord + ")").setItems(ops, (d, idx) -> {
                    if (idx == 0) {
                        mostrarDestino(gp, "Punto seleccionado (" + coord + ")");
                    } else if (idx == 1) {
                        String nom = "Lugar " + coord;
                        String prev = getSharedPreferences("kuli_guardados", MODE_PRIVATE).getString("sitios", "");
                        getSharedPreferences("kuli_guardados", MODE_PRIVATE).edit().putString("sitios", nom + "|" + gp.getLatitude() + "," + gp.getLongitude() + ";" + prev).apply();
                        Toast.makeText(MainActivity.this, "⭐ Guardado en Mis lugares", Toast.LENGTH_SHORT).show();
                    } else if (idx == 2) {
                        Intent wa = new Intent(Intent.ACTION_SEND);
                        wa.setType("text/plain");
                        wa.putExtra(Intent.EXTRA_TEXT, "📍 Ubicación Kuli Mapas: https://google.com" + gp.getLatitude() + "," + gp.getLongitude());
                        startActivity(Intent.createChooser(wa, "Compartir ubicación"));
                    }
                }).show();
                return true;
            }
            @Override public boolean longPressHelper(GeoPoint gp) {
                pantallaDespejada = !pantallaDespejada;
                int vis = pantallaDespejada ? View.GONE : View.VISIBLE;
                if (topWrapRef != null) topWrapRef.setVisibility(vis);
                if (navBotRef != null) navBotRef.setVisibility(vis);
                Toast.makeText(MainActivity.this, pantallaDespejada ? "👁️ Pantalla despejada (mantén pulsado para volver)" : "👁️ Controles visibles", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        map.getOverlays().add(0, evOverlay);

                            map.invalidate();
                            txtBanner.setText("✅ " + total + " radares de Andalucía y España guardados offline");
                            Toast.makeText(this, "🚨 " + total + " radares listos sin internet", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                } catch (Throwable ignored) {}
            }
            runOnUiThread(() -> txtBanner.setText("⚠️ Servidor ocupado, usando radares locales de Málaga"));
        }).start();
    }

    private void pintarRadaresEnMapa() {
        GeoPoint centro = (GeoPoint) map.getMapCenter();
        int pintados = 0;
        for (double[] r : radares) {
            GeoPoint gp = new GeoPoint(r[0], r[1]);
            if (centro.distanceToAsDouble(gp) > 85000 && pintados > 120) continue;
            Marker m = new Marker(map);
            m.setPosition(gp);
            m.setTitle("🚨 RADAR (" + (int)r[2] + " km/h)");
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            Bitmap bmp = Bitmap.createBitmap(44, 44, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp); Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            pt.setColor(Color.WHITE); cv.drawCircle(22, 22, 20, pt);
            pt.setColor(Color.rgb(225, 35, 45)); pt.setStyle(Paint.Style.STROKE); pt.setStrokeWidth(5); cv.drawCircle(22, 22, 18, pt);
            pt.setStyle(Paint.Style.FILL); pt.setColor(Color.BLACK); pt.setTextSize(14); pt.setFakeBoldText(true); pt.setTextAlign(Paint.Align.CENTER);
            cv.drawText(String.valueOf((int)r[2]), 22, 27, pt);
            m.setIcon(new BitmapDrawable(getResources(), bmp));
            map.getOverlays().add(m);
            pintados++;
            if (pintados >= 350) break;
        }
    }

    private void anadirRadarAqui() {
        GeoPoint p = (locOverlay != null && locOverlay.getMyLocation() != null) ? locOverlay.getMyLocation() : (GeoPoint) map.getMapCenter();
        final int[] lims = {50, 80, 90, 100, 120};
        new AlertDialog.Builder(this).setTitle("Guardar radar").setItems(new String[]{"50","80","90","100","120"}, (d, idx) -> {
            int lim = lims[idx]; radares.add(new double[]{p.getLatitude(), p.getLongitude(), lim});
            String prev = getSharedPreferences("kuli_radares", MODE_PRIVATE).getString("lista", "");
            getSharedPreferences("kuli_radares", MODE_PRIVATE).edit().putString("lista", prev + p.getLatitude() + "," + p.getLongitude() + "," + lim + ";").apply();
            pintarRadaresEnMapa();

        org.osmdroid.views.overlay.MapEventsOverlay evOverlay = new org.osmdroid.views.overlay.MapEventsOverlay(new org.osmdroid.events.MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint gp) {
                String coord = String.format(Locale.US, "%.5f, %.5f", gp.getLatitude(), gp.getLongitude());
                String[] ops = {"🧭 RUTA HACIA aquí", "⭐ GUARDAR en Mis lugares", "📲 COMPARTIR por WhatsApp"};
                new AlertDialog.Builder(MainActivity.this).setTitle("📍 Punto (" + coord + ")").setItems(ops, (d, idx) -> {
                    if (idx == 0) {
                        mostrarDestino(gp, "Punto seleccionado (" + coord + ")");
                    } else if (idx == 1) {
                        String nom = "Lugar " + coord;
                        String prev = getSharedPreferences("kuli_guardados", MODE_PRIVATE).getString("sitios", "");
                        getSharedPreferences("kuli_guardados", MODE_PRIVATE).edit().putString("sitios", nom + "|" + gp.getLatitude() + "," + gp.getLongitude() + ";" + prev).apply();
                        Toast.makeText(MainActivity.this, "⭐ Guardado en Mis lugares", Toast.LENGTH_SHORT).show();
                    } else if (idx == 2) {
                        Intent wa = new Intent(Intent.ACTION_SEND);
                        wa.setType("text/plain");
                        wa.putExtra(Intent.EXTRA_TEXT, "📍 Ubicación Kuli Mapas: https://google.com" + gp.getLatitude() + "," + gp.getLongitude());
                        startActivity(Intent.createChooser(wa, "Compartir ubicación"));
                    }
                }).show();
                return true;
            }
            @Override public boolean longPressHelper(GeoPoint gp) {
                pantallaDespejada = !pantallaDespejada;
                int vis = pantallaDespejada ? View.GONE : View.VISIBLE;
                if (topWrapRef != null) topWrapRef.setVisibility(vis);
                if (navBotRef != null) navBotRef.setVisibility(vis);
                Toast.makeText(MainActivity.this, pantallaDespejada ? "👁️ Pantalla despejada (mantén pulsado para volver)" : "👁️ Controles visibles", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        map.getOverlays().add(0, evOverlay);
 map.invalidate();
        }).show();
    }

    private void iniciarGps() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 10); return;
            }
            locOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), map);
            Bitmap bmpCoche = crearIconoCoche();
            locOverlay.setPersonIcon(bmpCoche);
            locOverlay.setDirectionIcon(bmpCoche);
            locOverlay.setPersonHotspot(48f, 48f);
            locOverlay.setDirectionAnchor(0.5f, 0.5f);
            locOverlay.enableMyLocation(); locOverlay.enableFollowLocation(); map.getOverlays().add(locOverlay);
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, this);
        } catch (Throwable ignored) {}
    }

    private void centrarGps() {
        if (locOverlay != null && locOverlay.getMyLocation() != null) { map.getController().animateTo(locOverlay.getMyLocation()); map.getController().setZoom(16.0); }
    }

    private void descargarZonaActual() {
        if (modoOffline) {
            Toast.makeText(this, "Activa Online primero para descargar mapas", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] zonas = {
            "📍 Málaga Capital y Costa del Sol (Rápido)",
            "🗺️ Provincia de Málaga completa",
            "☀️ Andalucía completa (Autovías y capitales)",
            "🔍 Vista actual de pantalla"
        };
        new AlertDialog.Builder(this).setTitle("📥 Descargar mapas sin internet").setItems(zonas, (d, idx) -> {
            org.osmdroid.util.BoundingBox box;
            int zMin = 8, zMax = 14;
            if (idx == 0) { box = new org.osmdroid.util.BoundingBox(36.78, -4.30, 36.50, -4.95); zMin = 10; zMax = 15; }
            else if (idx == 1) { box = new org.osmdroid.util.BoundingBox(37.28, -3.75, 36.30, -5.60); zMin = 9; zMax = 14; }
            else if (idx == 2) { box = new org.osmdroid.util.BoundingBox(38.75, -1.60, 36.00, -7.55); zMin = 7; zMax = 12; }
            else { box = map.getBoundingBox(); zMin = (int) Math.max(6, map.getZoomLevelDouble()); zMax = Math.min(15, zMin + 3); }
            new CacheManager(map).downloadAreaAsync(this, box, zMin, zMax);
            txtBanner.setText("📥 Descargando: " + zonas[idx]);
        }).show();
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
        if (navegando && pasoActual < pasosPts.size()) {
            double dPaso = yo.distanceToAsDouble(pasosPts.get(pasoActual));
            if (dPaso < 65) {
                String ind = pasosVoz.get(pasoActual);
                txtBanner.setText("➡️ " + ind);
                if (tts != null) tts.speak(ind, TextToSpeech.QUEUE_FLUSH, null, null);
                pasoActual++;
            }
        }
        if (navegando && marcadorDestino != null && yo.distanceToAsDouble(marcadorDestino.getPosition()) < 35) {
            if (tts != null) tts.speak("Has llegado a tu destino", TextToSpeech.QUEUE_FLUSH, null, null);
            alternarNavegacion();
        }
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
    
    private Bitmap crearIconoCoche() {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Ruedas negras
        pt.setColor(Color.rgb(25, 28, 34));
        c.drawRoundRect(new RectF(21, 18, 30, 33), 2, 2, pt);
        c.drawRoundRect(new RectF(66, 18, 75, 33), 2, 2, pt);
        c.drawRoundRect(new RectF(21, 63, 30, 78), 2, 2, pt);
        c.drawRoundRect(new RectF(66, 63, 75, 78), 2, 2, pt);
        // Carroceria azul marino Kuli Edition
        pt.setColor(Color.rgb(16, 58, 122));
        c.drawRoundRect(new RectF(27, 9, 69, 87), 10, 10, pt);
        // Borde dorado
        pt.setColor(Color.rgb(212, 175, 55));
        pt.setStyle(Paint.Style.STROKE); pt.setStrokeWidth(2.5f);
        c.drawRoundRect(new RectF(27, 9, 69, 87), 10, 10, pt);
        pt.setStyle(Paint.Style.FILL);
        // Parabrisas delantero y trasero
        pt.setColor(Color.rgb(165, 220, 255));
        c.drawRoundRect(new RectF(32, 24, 64, 38), 4, 4, pt);
        c.drawRoundRect(new RectF(33, 63, 63, 74), 3, 3, pt);
        // Faros delanteros encendidos
        pt.setColor(Color.rgb(255, 245, 160));
        c.drawCircle(22, 8, 2.5f, pt);
        c.drawCircle(42, 8, 2.5f, pt);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
