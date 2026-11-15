package com.kuli.arcade;
import android.app.Activity; import android.content.*; import android.graphics.*; import android.graphics.drawable.GradientDrawable;
import android.os.Bundle; import android.view.Gravity; import android.widget.*; import java.io.*;

public class MainActivity extends Activity {
    private TextView txtRecords;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Color.rgb(6,22,46), Color.rgb(13,46,88), Color.rgb(6,20,42)}));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(dp(20), dp(38), dp(20), dp(26));
        TextView logo = new TextView(this); logo.setText("🕹️"); logo.setTextSize(48); logo.setGravity(Gravity.CENTER); root.addView(logo);
        TextView title = new TextView(this); title.setText("KULI ARCADE"); title.setTextColor(Color.WHITE); title.setTextSize(28); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub = new TextView(this); sub.setText("CLÁSICOS RETRO · SIN ANUNCIOS · v1.0"); sub.setTextColor(Color.rgb(155,195,240)); sub.setTextSize(12); sub.setGravity(Gravity.CENTER); sub.setPadding(0, dp(4), 0, dp(18)); root.addView(sub);
        txtRecords = new TextView(this); txtRecords.setTextColor(Color.rgb(232,195,92)); txtRecords.setTextSize(13); txtRecords.setGravity(Gravity.CENTER); txtRecords.setPadding(dp(12), dp(10), dp(12), dp(18)); root.addView(txtRecords);
        root.addView(btnJuego("🐉  BUBBLE BOBBLE", "El clásico de Taito 1986: atrapa bichos en pompas", 1, true));
        root.addView(btnJuego("🏓  ARKANOID RETRO", "Rompe todos los bloques con la bola", 2, false));
        LinearLayout fila = new LinearLayout(this); fila.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f); lp.setMargins(dp(5), dp(18), dp(5), dp(8));
        final Button btnSon = pastilla("🔊 Sonido: SÍ", false);
        btnSon.setOnClickListener(v -> { RetroSound.enabled = !RetroSound.enabled; btnSon.setText(RetroSound.enabled ? "🔊 Sonido: SÍ" : "🔇 Sonido: NO"); if (RetroSound.enabled) RetroSound.score(); });
        fila.addView(btnSon, lp);
        Button btnComp = pastilla("📲 Compartir APK", true); btnComp.setOnClickListener(v -> compartir()); fila.addView(btnComp, lp);
        root.addView(fila);
        TextView foot = new TextView(this); foot.setText("\n✦ KULI EDITION ✦"); foot.setTextColor(Color.rgb(212,175,55)); foot.setTextSize(13); foot.setTypeface(Typeface.DEFAULT_BOLD); foot.setGravity(Gravity.CENTER); foot.setPadding(0, dp(18), 0, 0); root.addView(foot);
        scroll.addView(root); setContentView(scroll);
    }
    @Override protected void onResume() {
        super.onResume();
        SharedPreferences sp = getSharedPreferences("kuli_arcade_scores", MODE_PRIVATE);
        txtRecords.setText("🏆 RÉCORDS:  Bubble Bobble " + sp.getInt("best_1",0) + "  ·  Arkanoid " + sp.getInt("best_2",0));
    }
    private Button btnJuego(String t, String d, final int id, boolean dest) {
        Button b = pastilla(t + "\n" + d, dest);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(72)); lp.setMargins(0, dp(8), 0, dp(8)); b.setLayoutParams(lp);
        b.setOnClickListener(v -> { RetroSound.blip(); Intent i = new Intent(this, GameActivity.class); i.putExtra("game", id); startActivity(i); });
        return b;
    }
    private Button pastilla(String txt, boolean fill) {
        Button b = new Button(this); b.setText(txt); b.setAllCaps(false); b.setTextSize(15); b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable(); gd.setCornerRadius(dp(26));
        if (fill) { gd.setColor(Color.rgb(120,175,235)); b.setTextColor(Color.rgb(8,26,52)); }
        else { gd.setColor(Color.rgb(12,38,76)); gd.setStroke(dp(1), Color.rgb(120,175,235)); b.setTextColor(Color.WHITE); }
        b.setBackground(gd); return b;
    }
    private void compartir() {
        try {
            File src = new File(getApplicationInfo().sourceDir);
            File dst = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Kuli-Arcade.apk");
            try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Intent s = new Intent(Intent.ACTION_SEND); s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, "¡Te paso KULI ARCADE! Tienes el instalador guardado como Kuli-Arcade.apk en Descargas para jugar a Bubble Bobble y Arkanoid sin anuncios.");
            startActivity(Intent.createChooser(s, "Compartir KULI ARCADE"));
        } catch (Throwable ignored) {}
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}