package com.kuli.mapas;
import android.bluetooth.BluetoothDevice;
import android.content.*;

public class AutoStartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
            boolean auto = ctx.getSharedPreferences("kuli_mapas_cfg", Context.MODE_PRIVATE).getBoolean("auto_coche", true);
            if (auto) {
                Intent i = new Intent(ctx, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                i.putExtra("modo_inicio", "coche");
                ctx.startActivity(i);
            }
        }
    }
}