package com.kuli.familia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

// Mantiene la app viva (con una notificación con botones) mientras suena la música.
public class MusicaService extends Service {
    static final String PAUSA = "com.kuli.familia.PAUSA";
    static final String SIGUIENTE = "com.kuli.familia.SIGUIENTE";
    static final String ANTERIOR = "com.kuli.familia.ANTERIOR";
    private static final String CANAL = "kuli_reproduccion";

    static MusicaService instancia;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instancia = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CANAL, "Reproducción", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public void onDestroy() {
        if (instancia == this) instancia = null;
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent == null ? null : intent.getAction();
        if (accion != null) {
            MainActivity m = MainActivity.instancia;
            if (m == null) { stopSelf(); return START_NOT_STICKY; }
            m.accionNotificacion(accion);
            return START_NOT_STICKY;
        }
        String t = intent == null ? "" : intent.getStringExtra("t");
        String a = intent == null ? "" : intent.getStringExtra("a");
        boolean suena = intent == null || intent.getBooleanExtra("p", true);
        refrescar(t, a, suena);
        return START_NOT_STICKY;
    }

    private Notification.Action boton(int icono, String texto, String accion, int codigo) {
        Intent i = new Intent(this, MusicaService.class).setAction(accion);
        PendingIntent pi = PendingIntent.getService(this, codigo, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this, icono), texto, pi).build();
    }

    // Pone título, artista y el botón de pausa/play correctos.
    void refrescar(String titulo, String artista, boolean suena) {
        if (titulo == null || titulo.isEmpty()) titulo = "KULI MUSIC";
        if (artista == null || artista.isEmpty()) artista = "Reproduciendo tu música";
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CANAL)
                .setContentTitle(titulo)
                .setContentText(artista)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(boton(android.R.drawable.ic_media_previous, "Anterior", ANTERIOR, 1))
                .addAction(boton(suena ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        suena ? "Pausa" : "Reproducir", PAUSA, 2))
                .addAction(boton(android.R.drawable.ic_media_next, "Siguiente", SIGUIENTE, 3))
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
                .build();
        startForeground(1, n);
    }
}
