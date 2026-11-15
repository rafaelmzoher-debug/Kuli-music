package com.kuli.music;
import android.app.*;
import android.content.Intent;
import android.os.*;

public class KuliPlaybackService extends Service {
    public static final String CHANNEL_ID = "kuli_music_playback";
    public static final String ACTION_PREV = "com.kuli.music.PREV";
    public static final String ACTION_TOGGLE = "com.kuli.music.TOGGLE";
    public static final String ACTION_NEXT = "com.kuli.music.NEXT";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Reproducción Kuli Music", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        MainActivity a = MainActivity.instanciaActiva;
        if (ACTION_PREV.equals(act) && a != null) a.runOnUiThread(a::anteriorDesdeNotificacion);
        else if (ACTION_TOGGLE.equals(act) && a != null) a.runOnUiThread(a::alternarDesdeNotificacion);
        else if (ACTION_NEXT.equals(act) && a != null) a.runOnUiThread(a::siguienteDesdeNotificacion);
        String titulo = intent != null && intent.hasExtra("titulo") ? intent.getStringExtra("titulo") : "Kuli Music";
        boolean sonando = intent == null || intent.getBooleanExtra("sonando", true);
        startForeground(1001, buildNotif(titulo, sonando));
        return START_STICKY;
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
          .setContentText(sonando ? "Segundo plano · Kuli Music" : "En pausa · Kuli Music")
          .setContentIntent(pOpen).setOngoing(sonando)
          .addAction(android.R.drawable.ic_media_previous, "Anterior", pPrev)
          .addAction(sonando ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, sonando ? "Pausa" : "Play", pTog)
          .addAction(android.R.drawable.ic_media_next, "Siguiente", pNext);
        if (Build.VERSION.SDK_INT >= 21) {
            nb.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
            nb.setVisibility(Notification.VISIBILITY_PUBLIC);
        }
        return nb.build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
