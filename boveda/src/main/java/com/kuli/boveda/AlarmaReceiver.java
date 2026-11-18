package com.kuli.boveda;
import android.app.*;
import android.content.*;
import android.os.Build;
public class AlarmaReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String texto = intent.getStringExtra("texto");
        if (texto == null) texto = "Recordatorio de Kuli Bóveda";
        String chId = "kuli_boveda_alarmas";
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(chId, "Recordatorios Kuli Bóveda", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) System.currentTimeMillis(), new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, chId) : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("⏰ Recordatorio · Kuli Bóveda").setContentText(texto).setStyle(new Notification.BigTextStyle().bigText(texto)).setContentIntent(pi).setAutoCancel(true);
        nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
    }
}
