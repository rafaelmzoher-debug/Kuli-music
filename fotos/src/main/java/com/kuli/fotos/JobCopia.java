package com.kuli.fotos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.atomic.AtomicBoolean;

// Copia automática en segundo plano: se ejecuta sola cuando hay WiFi.
public class JobCopia extends JobService {
    private final AtomicBoolean cancelar = new AtomicBoolean(false);

    @Override public boolean onStartJob(final JobParameters params) {
        cancelar.set(false);
        new Thread(() -> {
            try {
                CopiaFotos.Resultado r = CopiaFotos.copiar(this, null, cancelar);
                if (r.copiadas > 0) avisar(r.copiadas + " fotos y vídeos copiados al NAS");
            } catch (Throwable e) {
                android.util.Log.e("KULI_FOTOS", "Copia automática", e);
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        cancelar.set(true);
        return false;
    }

    private void avisar(String texto) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("fotos", "Copia de fotos",
                    NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, "fotos")
                    .setContentTitle("KULI FOTOS")
                    .setContentText(texto)
                    .setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setAutoCancel(true)
                    .build();
            nm.notify(2, n);
        } catch (Throwable ignorar) { }
    }
}
