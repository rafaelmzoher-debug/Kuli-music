package com.kuli.vinilo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements AudioManager.OnAudioFocusChangeListener {
    private VinylTurntableView vinyl;
    private TextView tvStyle, tvTitle, tvArtist, tvCur, tvTot;
    private SeekBar seekBar;
    private Button btnPlay;
    private MediaPlayer mp;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private SharedPreferences prefs;
    private final List<File> list = new ArrayList<>();
    private int idx = 0;
    private String currentStyle = "TODOS";
    private boolean wasPlayingBeforeLoss = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (mp != null) {
                try {
                    if (mp.isPlaying()) {
                        int p = mp.getCurrentPosition(), d = mp.getDuration();
                        seekBar.setMax(Math.max(d, 1)); seekBar.setProgress(p);
                        tvCur.setText(fmt(p)); tvTot.setText(fmt(d));
                        prefs.edit().putInt("last_pos", p).apply();
                    }
                } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 500);
        }
    };
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("kuli_vinilo_prefs", MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        currentStyle = prefs.getString("last_style", "TODOS");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12100E"));
        root.setPadding(24, 42, 24, 24);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        tvStyle = new TextView(this);
        tvStyle.setTextColor(Color.parseColor("#E8C27A"));
        tvStyle.setTextSize(15f);
        top.addView(tvStyle, new LinearLayout.LayoutParams(0, -2, 1f));
        Button bOrg = new Button(this);
        bOrg.setText("📥 Organizar");
        bOrg.setTextColor(Color.parseColor("#FFD584"));
        bOrg.setBackgroundColor(Color.parseColor("#2A211A"));
        bOrg.setOnClickListener(v -> organize());
        top.addView(bOrg);
        root.addView(top);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        List<String> styles = new ArrayList<>(); styles.add("TODOS");
        Collections.addAll(styles, MusicOrganizer.ALL_STYLES);
        for (final String st : styles) {
            Button c = new Button(this);
            c.setText(st); c.setTextSize(11f);
            c.setTextColor(Color.parseColor("#FFD584"));
            c.setBackgroundColor(Color.parseColor("#221B15"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 10, 10, 10); c.setLayoutParams(lp);
            c.setOnClickListener(v -> loadStyle(st, true));
            chips.addView(c);
        }
        hsv.addView(chips);
        root.addView(hsv);

        vinyl = new VinylTurntableView(this);
        vinyl.setOnSwipeListener(new VinylTurntableView.OnSwipeListener() {
            @Override public void onSwipeLeft() { play(idx + 1, 0, true); }
            @Override public void onSwipeRight() { play(idx - 1, 0, true); }
        });
        root.addView(vinyl, new LinearLayout.LayoutParams(-1, 0, 1f));

        tvTitle = new TextView(this);
        tvTitle.setTextColor(Color.WHITE); tvTitle.setTextSize(17f);
        tvTitle.setGravity(Gravity.CENTER); tvTitle.setSingleLine(true);
        root.addView(tvTitle);

        tvArtist = new TextView(this);
        tvArtist.setTextColor(Color.parseColor("#B8A99A")); tvArtist.setTextSize(13f);
        tvArtist.setGravity(Gravity.CENTER); tvArtist.setSingleLine(true);
        root.addView(tvArtist);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        tvCur = new TextView(this); tvCur.setText("00:00"); tvCur.setTextColor(Color.LTGRAY);
        seekBar = new SeekBar(this);
        tvTot = new TextView(this); tvTot.setText("00:00"); tvTot.setTextColor(Color.LTGRAY);
        bar.addView(tvCur); bar.addView(seekBar, new LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(tvTot);
        root.addView(bar);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(btn("⏮", v -> play(idx - 1, 0, true)));
        btnPlay = btn("▶", v -> toggle());
        row.addView(btnPlay);
        row.addView(btn("⏭", v -> play(idx + 1, 0, true)));
        root.addView(row);
        setContentView(root);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { if (u && mp != null) mp.seekTo(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        loadStyle(currentStyle, false);
        handler.post(ticker);
    }

    private Button btn(String t, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(20f);
        b.setTextColor(Color.parseColor("#12100E"));
        b.setBackgroundColor(Color.parseColor("#E8C27A"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(170, 125);
        lp.setMargins(14, 10, 14, 0); b.setLayoutParams(lp);
        b.setOnClickListener(l); return b;
    }

    private boolean okPerm() { return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(); }

    private void organize() {
        if (!okPerm()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        new Thread(() -> {
            MusicOrganizer.ensureFolderStructure();
            int n = MusicOrganizer.organizeDownloads();
            runOnUiThread(() -> { Toast.makeText(this, "Organizadas: " + n, Toast.LENGTH_LONG).show(); loadStyle(currentStyle, false); });
        }).start();
    }
    private void loadStyle(String st, boolean autoPlayFirst) {
        currentStyle = st;
        prefs.edit().putString("last_style", st).apply();
        list.clear();
        if (!okPerm()) { tvStyle.setText("KULI VINILO · Toca 📥 Organizar"); tvTitle.setText("Concede permiso de archivos"); return; }
        MusicOrganizer.ensureFolderStructure();
        if ("TODOS".equals(st)) {
            for (String s : MusicOrganizer.ALL_STYLES) list.addAll(MusicOrganizer.getTracksInStyle(s));
            addDir(new File("/storage/emulated/0/Music"));
        } else {
            list.addAll(MusicOrganizer.getTracksInStyle(st));
        }
        tvStyle.setText("KULI VINILO · " + st + " (" + list.size() + ")");
        if (list.isEmpty()) { tvTitle.setText("Sin canciones en " + st); tvArtist.setText("Toca 📥 Organizar"); return; }
        String savedPath = prefs.getString("last_path", "");
        int savedPos = prefs.getInt("last_pos", 0);
        int foundIdx = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).getAbsolutePath().equals(savedPath)) { foundIdx = i; break; }
        if (autoPlayFirst) play(0, 0, true);
        else play(foundIdx, savedPos, false);
    }

    private void addDir(File d) {
        File[] fs = d.listFiles(); if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { if (!f.getAbsolutePath().equals(MusicOrganizer.BASE_MUSIC_DIR)) addDir(f); }
            else { String n = f.getName().toLowerCase(Locale.ROOT); if ((n.endsWith(".mp3") || n.endsWith(".m4a")) && !list.contains(f)) list.add(f); }
        }
    }

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(this).build();
            return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override public void onAudioFocusChange(int change) {
        if (mp == null) return;
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            wasPlayingBeforeLoss = mp.isPlaying();
            if (wasPlayingBeforeLoss) { mp.pause(); vinyl.setPlaying(false); btnPlay.setText("▶"); }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && wasPlayingBeforeLoss) {
            mp.start(); vinyl.setPlaying(true); btnPlay.setText("⏸"); wasPlayingBeforeLoss = false;
        }
    }

    private void toggle() {
        if (list.isEmpty()) { organize(); return; }
        if (mp == null) { play(idx, 0, true); return; }
        if (mp.isPlaying()) { mp.pause(); vinyl.setPlaying(false); btnPlay.setText("▶"); }
        else if (requestFocus()) { mp.start(); vinyl.setPlaying(true); btnPlay.setText("⏸"); }
    }

    private void play(int i, int seekMs, boolean startNow) {
        if (list.isEmpty()) return;
        idx = (i + list.size()) % list.size();
        File f = list.get(idx);
        try {
            if (mp != null) mp.release();
            mp = new MediaPlayer(); mp.setDataSource(f.getAbsolutePath()); mp.prepare();
            if (seekMs > 0 && seekMs < mp.getDuration()) mp.seekTo(seekMs);
            mp.setOnCompletionListener(m -> play(idx + 1, 0, true));
            String title = f.getName().replaceFirst("\\.[^.]+$", ""), artist = "Kuli Vinilo Hi-Fi";
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever(); mmr.setDataSource(f.getAbsolutePath());
                String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                String a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                byte[] art = mmr.getEmbeddedPicture();
                if (t != null && !t.trim().isEmpty()) title = t;
                if (a != null && !a.trim().isEmpty()) artist = a;
                vinyl.setAlbumArt(art != null ? BitmapFactory.decodeByteArray(art, 0, art.length) : null);
                mmr.release();
            } catch (Exception ignored) {}
            tvTitle.setText(title); tvArtist.setText(artist);
            prefs.edit().putString("last_path", f.getAbsolutePath()).apply();
            if (startNow && requestFocus()) { mp.start(); vinyl.setPlaying(true); btnPlay.setText("⏸"); }
            else { vinyl.setPlaying(false); btnPlay.setText("▶"); }
        } catch (Exception e) { Toast.makeText(this, "Error audio", Toast.LENGTH_SHORT).show(); }
    }

    private String fmt(int ms) { int s = Math.max(0, ms / 1000); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
    @Override protected void onResume() { super.onResume(); if (list.isEmpty() && okPerm()) loadStyle(currentStyle, false); }
    @Override protected void onDestroy() { super.onDestroy(); handler.removeCallbacks(ticker); if (mp != null) { mp.release(); mp = null; } }
}
