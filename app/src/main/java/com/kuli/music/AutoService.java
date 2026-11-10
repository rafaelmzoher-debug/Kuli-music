package com.kuli.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.media.MediaBrowserService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

// KULI en Android Auto: la radio aparece en la pantalla del coche.
public class AutoService extends MediaBrowserService {
    static AutoService instancia;
    private static final String RAIZ = "kuli_raiz";
    private static final String CANAL = "kuli_auto";
    private static final String FAVS = "kuli_favs";
    private static final String RADIO = "kuli_radio";
    private boolean modoFav = false; // true = suenan las favoritas del móvil; false = radio
    private final ArrayList<String> favIds = new ArrayList<>();
    private final ArrayList<String> favNombres = new ArrayList<>();

    private MediaSession sesion;
    private MediaPlayer mp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<String> nombres = new ArrayList<>();
    private final ArrayList<String> urls = new ArrayList<>();
    private String nombreActual = null, urlActual = null;
    private boolean sonando = false;
    private AudioManager audio;
    private AudioFocusRequest foco;

    @Override public void onCreate() {
        super.onCreate();
        instancia = this;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(
                CANAL, "Radio en el coche", NotificationManager.IMPORTANCE_LOW));

        sesion = new MediaSession(this, "KULI");
        sesion.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { reanudar(); }
            @Override public void onPause() { pausar(); }
            @Override public void onStop() { pausar(); }
            @Override public void onSkipToNext() { saltar(1); }
            @Override public void onSkipToPrevious() { saltar(-1); }
            @Override public void onPlayFromMediaId(String id, Bundle extras) {
                if (id.startsWith("http")) {
                    modoFav = false;
                    int i = urls.indexOf(id);
                    reproducir(i >= 0 ? nombres.get(i) : "Radio", id);
                } else {
                    modoFav = true;
                    cargarFavoritas();
                    int i = favIds.indexOf(id);
                    reproducir(i >= 0 ? favNombres.get(i) : "Favorita", id);
                }
            }
        });
        setSessionToken(sesion.getSessionToken());

        SharedPreferences sp = getSharedPreferences("kuli_auto", MODE_PRIVATE);
        nombreActual = sp.getString("nombre", null);
        urlActual = sp.getString("url", null);
        modoFav = urlActual != null && !urlActual.startsWith("http");
        poner(urlActual == null ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED);
        ponerDatos();
        sesion.setActive(true);
    }

    @Override public void onDestroy() {
        liberar();
        try { sesion.release(); } catch (Exception ignorar) { }
        if (instancia == this) instancia = null;
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(3, notificacion()); } catch (Throwable ignorar) { }
        return START_NOT_STICKY;
    }

    // ---------- Lo que ve Android Auto ----------
    @Override public BrowserRoot onGetRoot(String paquete, int uid, Bundle extras) {
        return new BrowserRoot(RAIZ, null);
    }

    @Override public void onLoadChildren(String padre, final Result<List<MediaBrowser.MediaItem>> resultado) {
        if (RAIZ.equals(padre)) { resultado.sendResult(carpetas()); return; }
        if (FAVS.equals(padre)) { cargarFavoritas(); resultado.sendResult(elementosFav()); return; }
        if (!RADIO.equals(padre)) { resultado.sendResult(new ArrayList<MediaBrowser.MediaItem>()); return; }
        if (!urls.isEmpty()) { resultado.sendResult(elementos()); return; }
        resultado.detach();
        new Thread(() -> {
            cargarEmisoras();
            final List<MediaBrowser.MediaItem> l = elementos();
            h.post(() -> resultado.sendResult(l));
        }).start();
    }

    private List<MediaBrowser.MediaItem> carpetas() {
        ArrayList<MediaBrowser.MediaItem> l = new ArrayList<>();
        l.add(new MediaBrowser.MediaItem(new MediaDescription.Builder()
                .setMediaId(FAVS).setTitle("♥ Favoritas").setSubtitle("Música guardada en el móvil").build(),
                MediaBrowser.MediaItem.FLAG_BROWSABLE));
        l.add(new MediaBrowser.MediaItem(new MediaDescription.Builder()
                .setMediaId(RADIO).setTitle("Radio").setSubtitle("Emisoras en directo").build(),
                MediaBrowser.MediaItem.FLAG_BROWSABLE));
        return l;
    }

    // Favoritas que suenan sin NAS: las del móvil, y las del NAS que ya estén descargadas en la caché.
    private synchronized void cargarFavoritas() {
        final java.util.Map<String, ?> datos = getSharedPreferences("kuli_favoritos", MODE_PRIVATE).getAll();
        ArrayList<String> claves = new ArrayList<>();
        for (String k : datos.keySet()) {
            if (k.startsWith("nas://")) {
                java.io.File f = archivoNas(k);
                if (f == null || !f.exists() || f.length() == 0) continue;
            }
            claves.add(k);
        }
        java.util.Collections.sort(claves, (a, b) ->
                String.valueOf(datos.get(a)).compareToIgnoreCase(String.valueOf(datos.get(b))));
        favIds.clear(); favNombres.clear();
        for (String k : claves) { favIds.add(k); favNombres.add(String.valueOf(datos.get(k))); }
    }

    private java.io.File archivoNas(String clave) {
        String nombre = android.net.Uri.parse(clave).getLastPathSegment();
        if (nombre == null) return null;
        return new java.io.File(new java.io.File(getCacheDir(), "nas"), nombre.replace('/', '_'));
    }

    private synchronized List<MediaBrowser.MediaItem> elementosFav() {
        ArrayList<MediaBrowser.MediaItem> l = new ArrayList<>();
        for (int i = 0; i < favIds.size(); i++) {
            MediaDescription d = new MediaDescription.Builder()
                    .setMediaId(favIds.get(i)).setTitle(favNombres.get(i)).setSubtitle("Favorita").build();
            l.add(new MediaBrowser.MediaItem(d, MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }
        return l;
    }

    private synchronized List<MediaBrowser.MediaItem> elementos() {
        ArrayList<MediaBrowser.MediaItem> l = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            MediaDescription d = new MediaDescription.Builder()
                    .setMediaId(urls.get(i)).setTitle(nombres.get(i)).setSubtitle("Radio en directo").build();
            l.add(new MediaBrowser.MediaItem(d, MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }
        return l;
    }

    private synchronized void cargarEmisoras() {
        String ruta = "/json/stations/search?countrycode=ES&order=clickcount&reverse=true&limit=40&hidebroken=true";
        String[] servidores = {"https://de1.api.radio-browser.info", "https://at1.api.radio-browser.info",
                               "https://nl1.api.radio-browser.info"};
        for (String srv : servidores) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(srv + ruta).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(9000);
                c.setRequestProperty("User-Agent", "KULIMusic/3.8");
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String linea;
                    while ((linea = r.readLine()) != null) sb.append(linea);
                }
                org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
                HashSet<String> vistos = new HashSet<>();
                ArrayList<String> n = new ArrayList<>(), u = new ArrayList<>();
                // La última que oíste va la primera.
                if (urlActual != null && nombreActual != null) { n.add(nombreActual); u.add(urlActual); vistos.add(nombreActual.toLowerCase()); }
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    String nombre = o.optString("name").trim();
                    String url = o.optString("url_resolved");
                    if (url.isEmpty()) url = o.optString("url");
                    if (nombre.isEmpty() || url.isEmpty() || !vistos.add(nombre.toLowerCase())) continue;
                    n.add(nombre); u.add(url);
                }
                nombres.clear(); urls.clear();
                nombres.addAll(n); urls.addAll(u);
                return;
            } catch (Exception e) { android.util.Log.e("KULI_AUTO", srv, e); }
        }
    }

    // ---------- Reproducción ----------
    private void reproducir(String nombre, String url) {
        liberar();
        MainActivity m = MainActivity.instancia;
        if (m != null) m.pausarDesdeAuto();
        nombreActual = nombre; urlActual = url;
        getSharedPreferences("kuli_auto", MODE_PRIVATE).edit().putString("nombre", nombre).putString("url", url).apply();
        ponerDatos();
        poner(PlaybackState.STATE_BUFFERING);
        pedirFoco();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            if (url.startsWith("http")) mp.setDataSource(url);
            else if (url.startsWith("nas://")) mp.setDataSource(archivoNas(url).getAbsolutePath());
            else mp.setDataSource(getApplicationContext(), android.net.Uri.parse(url));
            mp.setOnCompletionListener(x -> { if (modoFav) saltar(1); });
            mp.setOnPreparedListener(x -> {
                try { x.start(); } catch (Exception ignorar) { }
                sonando = true;
                poner(PlaybackState.STATE_PLAYING);
                try { startForegroundService(new Intent(this, AutoService.class)); } catch (Throwable ignorar) { }
            });
            mp.setOnErrorListener((x, w, e) -> {
                sonando = false;
                poner(PlaybackState.STATE_ERROR);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            android.util.Log.e("KULI_AUTO", "No abre " + url, e);
            sonando = false;
            poner(PlaybackState.STATE_ERROR);
        }
    }

    private void reanudar() {
        if (mp != null && !sonando) {
            try {
                pedirFoco();
                mp.start(); sonando = true;
                poner(PlaybackState.STATE_PLAYING);
                try { startForegroundService(new Intent(this, AutoService.class)); } catch (Throwable ignorar) { }
                return;
            } catch (Exception ignorar) { }
        }
        if (urlActual != null) { reproducir(nombreActual == null ? "Radio" : nombreActual, urlActual); return; }
        new Thread(() -> {
            cargarEmisoras();
            h.post(() -> { if (!urls.isEmpty()) reproducir(nombres.get(0), urls.get(0)); });
        }).start();
    }

    private void pausar() {
        try { if (mp != null && sonando) mp.pause(); } catch (Exception ignorar) { }
        sonando = false;
        poner(urlActual == null ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED);
        stopForeground(false);
    }

    private void saltar(int d) {
        if (modoFav) {
            if (favIds.isEmpty()) cargarFavoritas();
            if (favIds.isEmpty()) return;
            int j = favIds.indexOf(urlActual);
            j = j < 0 ? 0 : (j + d + favIds.size()) % favIds.size();
            reproducir(favNombres.get(j), favIds.get(j));
            return;
        }
        if (urls.isEmpty()) return;
        int i = urls.indexOf(urlActual);
        i = i < 0 ? 0 : (i + d + urls.size()) % urls.size();
        reproducir(nombres.get(i), urls.get(i));
    }

    // Si la app empieza a sonar en el móvil, la radio del coche se calla.
    void pararPorApp() {
        liberar();
        poner(urlActual == null ? PlaybackState.STATE_NONE : PlaybackState.STATE_PAUSED);
        stopForeground(true);
    }

    private void liberar() {
        sonando = false;
        if (mp != null) { try { mp.release(); } catch (Exception ignorar) { } mp = null; }
    }

    private void pedirFoco() {
        try {
            foco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(cambio -> {
                        if (cambio == AudioManager.AUDIOFOCUS_LOSS || cambio == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pausar();
                    }).build();
            audio.requestAudioFocus(foco);
        } catch (Exception ignorar) { }
    }

    // ---------- Estado que ve el coche ----------
    private void poner(int estado) {
        long acciones = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID;
        sesion.setPlaybackState(new PlaybackState.Builder()
                .setActions(acciones)
                .setState(estado, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
    }

    private void ponerDatos() {
        if (nombreActual == null) return;
        sesion.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, nombreActual)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, modoFav ? "Favorita" : "Radio en directo")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, urlActual)
                .build());
    }

    private Notification notificacion() {
        return new Notification.Builder(this, CANAL)
                .setContentTitle(nombreActual == null ? "KULI MUSIC" : nombreActual)
                .setContentText(modoFav ? "Favorita" : "Radio en directo")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setStyle(new Notification.MediaStyle().setMediaSession(sesion.getSessionToken()))
                .build();
    }
}
