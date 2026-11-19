package com.kuli.music;
import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.media.*;
import android.media.session.*;
import android.os.*;

public class KuliPlaybackService extends Service {
    public static final String CHANNEL_ID = "kuli_music_bg";
    public static final String ACTION_PREV = "com.kuli.music.PREV";
    public static final String ACTION_TOGGLE = "com.kuli.music.TOGGLE";
    public static final String ACTION_NEXT = "com.kuli.music.NEXT";
    public static Bitmap portadaActual = null;
    public static String artistaActual = "Kuli Music";
    private MediaSession mediaSession;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Kuli Music Reproducción", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        mediaSession = new MediaSession(this, "KuliMusicSession");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { enviarAccion(ACTION_TOGGLE); }
            @Override public void onPause() { enviarAccion(ACTION_TOGGLE); }
            @Override public void onSkipToNext() { enviarAccion(ACTION_NEXT); }
            @Override public void onSkipToPrevious() { enviarAccion(ACTION_PREV); }
            @Override public void onStop() { enviarAccion(ACTION_TOGGLE); }
        });
        mediaSession.setActive(true);
    }

    private void enviarAccion(String act) {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(act);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PREV.equals(action) || ACTION_TOGGLE.equals(action) || ACTION_NEXT.equals(action)) {
            enviarAccion(action);
            return START_STICKY;
        }
        String titulo = intent != null && intent.getStringExtra("titulo") != null ? intent.getStringExtra("titulo") : "Kuli Music";
        boolean sonando = intent == null || intent.getBooleanExtra("sonando", true);
        actualizarSesionCoche(titulo, artistaActual, portadaActual, sonando);
        startForeground(1001, buildNotif(titulo, sonando));
        return START_STICKY;
    }

    private void actualizarSesionCoche(String titulo, String artista, Bitmap art, boolean sonando) {
        if (mediaSession == null) return;
        mediaSession.setActive(true);
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artista != null ? artista : "Kuli Music")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Kuli Music");
        if (art != null) {
            mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            mb.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        }
        mediaSession.setMetadata(mb.build());
        long acciones = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE

                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        PlaybackState pb = new PlaybackState.Builder()
                .setActions(acciones)
                .setState(sonando ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build();
        mediaSession.setPlaybackState(pb);
        AutoService.reflejarMovil(titulo, artista, sonando);
    }

    private Notification buildNotif(String titulo, boolean sonando) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, fl);
        PendingIntent pPrev = PendingIntent.getService(this, 1, new Intent(this, KuliPlaybackService.class).setAction(ACTION_PREV), fl);
        PendingIntent pTog = PendingIntent.getService(this, 2, new Intent(this, KuliPlaybackService.class).setAction(ACTION_TOGGLE), fl);
        PendingIntent pNext = PendingIntent.getService(this, 3, new Intent(this, KuliPlaybackService.class).setAction(ACTION_NEXT), fl);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(titulo)
                .setContentText(sonando ? artistaActual + " · Kuli Music" : "En pausa · Kuli Music")
                .setContentIntent(pOpen).setOngoing(sonando)
                .addAction(android.R.drawable.ic_media_previous, "Anterior", pPrev)
                .addAction(sonando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, sonando ? "Pausa" : "Play", pTog)
                .addAction(android.R.drawable.ic_media_next, "Siguiente", pNext);
        if (portadaActual != null) nb.setLargeIcon(portadaActual);
        if (Build.VERSION.SDK_INT >= 21) {
            Notification.MediaStyle ms = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
            if (mediaSession != null) ms.setMediaSession(mediaSession.getSessionToken());
            nb.setStyle(ms);
            nb.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return nb.build();
    }

    @Override public void onDestroy() {
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
