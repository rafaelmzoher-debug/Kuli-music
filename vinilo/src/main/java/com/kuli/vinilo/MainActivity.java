package com.kuli.vinilo;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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
    private LinearLayout styleContainer;
    private SeekBar seekBar;
    private Button btnPlay, btnShuffle, btnRepeat;
    private MediaPlayer mp;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private SharedPreferences prefs;
    private final List<File> list = new ArrayList<>();
    private int idx = 0;
    private String currentStyle = "TODOS";
    private boolean isShuffle = false, isRepeat = false, wasPlayingBeforeLoss = false;
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
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("kuli_vinilo_prefs", MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        currentStyle = prefs.getString("last_style", "TODOS");
        isShuffle = prefs.getBoolean("shuffle", false);
        isRepeat = prefs.getBoolean("repeat", false);

        styleContainer = findViewById(R.id.styleContainer);
        tvStyle = findViewById(R.id.tvStyleLabel);
        vinyl = findViewById(R.id.vinylView);
        tvTitle = findViewById(R.id.tvTrackTitle);
        tvArtist = findViewById(R.id.tvTrackArtist);
        tvCur = findViewById(R.id.tvTimeCurrent);
        seekBar = findViewById(R.id.seekBar);
        tvTot = findViewById(R.id.tvTimeTotal);
        btnShuffle = findViewById(R.id.btnShuffle);
        btnPlay = findViewById(R.id.btnPlayPause);
        btnRepeat = findViewById(R.id.btnRepeat);
        updateModeButtons();
        List<String> styles = new ArrayList<>(); styles.add("TODOS");
        Collections.addAll(styles, MusicOrganizer.ALL_STYLES);
        for (final String st : styles) {
            Button c = new Button(this);
            c.setText(st); c.setTextSize(11f);
            c.setTextColor(Color.parseColor("#F5E6C8"));
            c.setBackgroundColor(Color.parseColor("#3D2614"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, 10, 0); c.setLayoutParams(lp);
            c.setOnClickListener(v -> loadStyle(st, true));
            styleContainer.addView(c);
        }

        vinyl.setOnSwipeListener(new VinylTurntableView.OnSwipeListener() {
            @Override public void onSwipeLeft() { nextTrack(); }
            @Override public void onSwipeRight() { play(idx - 1, 0, true); }
        });

        findViewById(R.id.btnPrev).setOnClickListener(v -> play(idx - 1, 0, true));
        btnPlay.setOnClickListener(v -> toggle());
        findViewById(R.id.btnNext).setOnClickListener(v -> nextTrack());
        btnShuffle.setOnClickListener(v -> { isShuffle = !isShuffle; prefs.edit().putBoolean("shuffle", isShuffle).apply(); updateModeButtons(); });
        btnRepeat.setOnClickListener(v -> { isRepeat = !isRepeat; prefs.edit().putBoolean("repeat", isRepeat).apply(); updateModeButtons(); });
        findViewById(R.id.btnOrganize).setOnClickListener(v -> organize());
        findViewById(R.id.btnTrackList).setOnClickListener(v -> openTrackSearchDialog());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { if (u && mp != null) mp.seekTo(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        loadStyle(currentStyle, false);
        handler.post(ticker);
    }

    private void updateModeButtons() {
        btnShuffle.setAlpha(isShuffle ? 1.0f : 0.45f);
        btnRepeat.setAlpha(isRepeat ? 1.0f : 0.45f);
    }

    private void nextTrack() {
        if (list.isEmpty()) return;
        play(isShuffle ? (int) (Math.random() * list.size()) : idx + 1, 0, true);
    }

    private void openTrackSearchDialog() {
        if (list.isEmpty()) { Toast.makeText(this, "No hay pistas cargadas", Toast.LENGTH_SHORT).show(); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(24, 20, 24, 12);
        EditText search = new EditText(this);
        search.setHint("🔍 Buscar canción o artista...");
        box.addView(search);
        ListView lv = new ListView(this);
        box.addView(lv, new LinearLayout.LayoutParams(-1, 800));

        final List<Integer> mapIdx = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) { mapIdx.add(i); names.add((i + 1) + ". " + list.get(i).getName().replaceFirst("\\.[^.]+$", "")); }
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        lv.setAdapter(ad);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().toLowerCase(Locale.ROOT).trim();
                mapIdx.clear(); names.clear();
                for (int i = 0; i < list.size(); i++) {
                    String n = list.get(i).getName().replaceFirst("\\.[^.]+$", "");
                    if (n.toLowerCase(Locale.ROOT).contains(q)) { mapIdx.add(i); names.add((i + 1) + ". " + n); }
                }
                ad.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Pistas · " + currentStyle + " (" + list.size() + ")").setView(box).setNegativeButton("Cerrar", null).create();
        lv.setOnItemClickListener((p, v, pos, id) -> { dlg.dismiss(); play(mapIdx.get(pos), 0, true); });
        dlg.show();
    }

    private boolean okPerm() { return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(); }

    private void organize() {
        if (!okPerm()) { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); return; }
        new Thread(() -> {
            MusicOrganizer.ensureFolderStructure();
            int n = MusicOrganizer.organizeDownloads();
            runOnUiThread(() -> { Toast.makeText(this, "Organizadas: " + n, Toast.LENGTH_LONG).show(); loadStyle(currentStyle, false); });
        }).start();
    }

    private void loadStyle(String st, boolean autoPlayFirst) {
        currentStyle = st; prefs.edit().putString("last_style", st).apply(); list.clear();
        if (!okPerm()) { tvStyle.setText("Toca 📥 Organizar para dar permiso"); return; }
        MusicOrganizer.ensureFolderStructure();
        if ("TODOS".equals(st)) addDir(new File("/storage/emulated/0/Music"));
        else addDir(new File(MusicOrganizer.BASE_MUSIC_DIR, st));
        tvStyle.setText("Estilo: " + st + " (" + list.size() + " pistas)");
        if (list.isEmpty()) { tvTitle.setText("Sin pistas en " + st); tvArtist.setText(""); return; }
        String savedPath = prefs.getString("last_path", "");
        int savedPos = prefs.getInt("last_pos", 0), foundIdx = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).getAbsolutePath().equals(savedPath)) { foundIdx = i; break; }
        if (autoPlayFirst) play(0, 0, true); else play(foundIdx, savedPos, false);
    }

    private void addDir(File d) {
        File[] fs = d.listFiles(); if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) addDir(f);
            else { String n = f.getName().toLowerCase(Locale.ROOT); if ((n.endsWith(".mp3") || n.endsWith(".m4a")) && !list.contains(f)) list.add(f); }
        }
    }

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener(this).build();
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
            mp.setOnCompletionListener(m -> { if (isRepeat) play(idx, 0, true); else nextTrack(); });
            String title = f.getName().replaceFirst("\\.[^.]+$", ""), artist = "Kuli Vinilo Hi-Fi";
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever(); mmr.setDataSource(f.getAbsolutePath());
                String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
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
