
package com.kuli.vinilo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * MainActivity - Reproductor de música local con estética de tocadiscos
 * de vinilo clásico y organizador automático por estilos.
 *
 * Requiere MANAGE_EXTERNAL_STORAGE concedido manualmente en Ajustes
 * (uso privado, no distribuible en Play Store).
 */
public class MainActivity extends android.app.Activity {

    // -------------------------------------------------------------------------
    // Constantes
    // -------------------------------------------------------------------------
    private static final int REQ_MANAGE_STORAGE = 1001;
    private static final int REQ_READ_AUDIO = 1002;

    // -------------------------------------------------------------------------
    // Vistas
    // -------------------------------------------------------------------------
    private VinylTurntableView vinylView;
    private TextView tvTrackTitle;
    private TextView tvTrackArtist;
    private TextView tvStyleLabel;
    private SeekBar seekBar;
    private TextView tvTimeCurrent;
    private TextView tvTimeTotal;
    private Button btnPrev;
    private Button btnPlayPause;
    private Button btnNext;
    private Button btnShuffle;
    private Button btnRepeat;
    private Button btnOrganize;
    private HorizontalScrollView styleScroll;
    private LinearLayout styleContainer;

    // -------------------------------------------------------------------------
    // Reproductor
    // -------------------------------------------------------------------------
    private MediaPlayer mediaPlayer;
    private final List<File> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private String currentStyle = MusicOrganizer.STYLE_VARIOS;
    private boolean shuffleEnabled = false;
    private int repeatMode = 0; // 0 = off, 1 = repeat all, 2 = repeat one
    private final Random random = new Random();

    // -------------------------------------------------------------------------
    // MediaSession para Bluetooth y pantalla de bloqueo
    // -------------------------------------------------------------------------
    private MediaSessionCompat mediaSession;

    // -------------------------------------------------------------------------
    // Handler para actualizar la barra de progreso
    // -------------------------------------------------------------------------
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                uiHandler.postDelayed(this, 500);
            }
        }
    };

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try (java.io.PrintWriter pw = new java.io.PrintWriter("/sdcard/Android/data/com.kuli.vinilo/files/crash.txt")) {
                ex.printStackTrace(pw);
            } catch (Exception ignored) {}
        });
        setContentView(R.layout.activity_main);

        bindViews();
        setupListeners();
        // setupMediaSession();

        // Comprobar permiso de acceso total a archivos
        if (!checkStoragePermissionGranted()) {
            return; // Se reanuda en onResume tras concederlo
        }

        // Asegurar estructura de carpetas y cargar primera lista
        MusicOrganizer.ensureFolderStructure();
        switchStyle(MusicOrganizer.STYLE_VARIOS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Si el usuario acaba de conceder el permiso, continuar la inicialización
        if (checkStoragePermissionGranted()) {
            if (playlist.isEmpty()) {
                MusicOrganizer.ensureFolderStructure();
                switchStyle(currentStyle);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(progressUpdater);
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
    }

    // -------------------------------------------------------------------------
    // Bind de vistas
    // -------------------------------------------------------------------------
    private void bindViews() {
        vinylView = findViewById(R.id.vinylView);
        tvTrackTitle = findViewById(R.id.tvTrackTitle);
        tvTrackArtist = findViewById(R.id.tvTrackArtist);
        tvStyleLabel = findViewById(R.id.tvStyleLabel);
        seekBar = findViewById(R.id.seekBar);
        tvTimeCurrent = findViewById(R.id.tvTimeCurrent);
        tvTimeTotal = findViewById(R.id.tvTimeTotal);
        btnPrev = findViewById(R.id.btnPrev);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNext = findViewById(R.id.btnNext);
        btnShuffle = findViewById(R.id.btnShuffle);
        btnRepeat = findViewById(R.id.btnRepeat);
        btnOrganize = findViewById(R.id.btnOrganize);
        styleScroll = findViewById(R.id.styleScroll);
        styleContainer = findViewById(R.id.styleContainer);
    }

    // -------------------------------------------------------------------------
    // Listeners
    // -------------------------------------------------------------------------
    private void setupListeners() {
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPrev.setOnClickListener(v -> playPrevious());
        btnNext.setOnClickListener(v -> playNext());
        btnShuffle.setOnClickListener(v -> toggleShuffle());
        btnRepeat.setOnClickListener(v -> toggleRepeat());
        btnOrganize.setOnClickListener(v -> runOrganizer());

        vinylView.setOnSwipeListener(new VinylTurntableView.OnSwipeListener() {
            @Override
            public void onSwipeLeft() {
                playNext();
            }

            @Override
            public void onSwipeRight() {
                playPrevious();
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
    }

    // -------------------------------------------------------------------------
    // Permisos de almacenamiento
    // -------------------------------------------------------------------------
    private boolean checkStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        // Para versiones anteriores, READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this,
                        "Concede 'Acceso a todos los archivos' para organizar tu música",
                        Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQ_MANAGE_STORAGE);
                } catch (Exception e) {
                    // Fallback: abrir la pantalla general de acceso a todos los archivos
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQ_MANAGE_STORAGE);
                }
                return false;
            }
            // En Android 13+ pedir también READ_MEDIA_AUDIO (no imprescindible con
            // MANAGE_EXTERNAL_STORAGE, pero buena práctica para mostrar en notificación)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.READ_MEDIA_AUDIO},
                            REQ_READ_AUDIO);
                }
            }
            return true;
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        REQ_READ_AUDIO);
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && Environment.isExternalStorageManager()) {
                MusicOrganizer.ensureFolderStructure();
                switchStyle(currentStyle);
            } else {
                Toast.makeText(this,
                        "Sin el permiso no se puede organizar la música",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_READ_AUDIO) {
            // No bloqueamos la app si el usuario deniega; el permiso principal
            // es MANAGE_EXTERNAL_STORAGE.
        }
    }

    // -------------------------------------------------------------------------
    // MediaSession
    // -------------------------------------------------------------------------
    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "KuliVinylSession");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { if (mediaPlayer != null) mediaPlayer.start(); updatePlaybackState(true); }
            @Override public void onPause() { if (mediaPlayer != null) mediaPlayer.pause(); updatePlaybackState(false); }
            @Override public void onSkipToNext() { playNext(); }
            @Override public void onSkipToPrevious() { playPrevious(); }
            @Override public void onSeekTo(long pos) { if (mediaPlayer != null) mediaPlayer.seekTo((int) pos); }
            @Override public void onStop() { stopPlayback(); }
        });
        mediaSession.setActive(true);
        updatePlaybackState(false);
    }

    private void updatePlaybackState(boolean playing) {
        if (mediaSession == null) return;
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_SEEK_TO);
        builder.setState(
                playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0,
                1.0f);
        mediaSession.setPlaybackState(builder.build());
    }

    private void updateMediaMetadata(File track) {
        if (mediaSession == null || track == null) return;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        String title = track.getName();
        String artist = "";
        String album = "";
        long duration = 0L;
        try {
            retriever.setDataSource(track.getAbsolutePath());
            String t = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String al = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (t != null) title = t;
            if (a != null) artist = a;
            if (al != null) album = al;
            if (d != null) duration = Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (IOException ignored) { }
        }

        MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build();
        mediaSession.setMetadata(metadata);
    }

    // -------------------------------------------------------------------------
    // Selector de estilos
    // -------------------------------------------------------------------------
    private void buildStyleSelector() {
        styleContainer.removeAllViews();
        for (final String style : MusicOrganizer.ALL_STYLES) {
            Button b = new Button(this);
            b.setText(style);
            b.setAllCaps(false);
            b.setTextSize(12f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(6, 6, 6, 6);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> switchStyle(style));
            styleContainer.addView(b);
        }
    }

    private void switchStyle(String style) {
        currentStyle = style;
        tvStyleLabel.setText("Estilo: " + style);

        // Parar reproducción actual
        stopPlayback();

        // Cargar pistas del estilo
        playlist.clear();
        File[] files = MusicOrganizer.getTracksForStyle(style);
        for (File f : files) {
            playlist.add(f);
        }

        if (playlist.isEmpty()) {
            tvTrackTitle.setText("Sin pistas en " + style);
            tvTrackArtist.setText("");
            tvTimeCurrent.setText("00:00");
            tvTimeTotal.setText("00:00");
            seekBar.setProgress(0);
            seekBar.setMax(0);
            vinylView.setAlbumArt(null);
            return;
        }

        currentIndex = 0;
        loadTrack(playlist.get(currentIndex), false);
    }

    // -------------------------------------------------------------------------
    // Reproducción
    // -------------------------------------------------------------------------
    private void loadTrack(File track, boolean autoPlay) {
        if (track == null) return;

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(track.getAbsolutePath());
            mediaPlayer.prepare();
        } catch (IOException e) {
            Toast.makeText(this, "No se puede reproducir: " + track.getName(),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        mediaPlayer.setOnCompletionListener(mp -> onTrackCompleted());

        String[] meta = MusicOrganizer.readMetadata(track);
        String title = meta[0] != null ? meta[0] : stripExtension(track.getName());
        String artist = meta[1] != null ? meta[1] : "";

        tvTrackTitle.setText(title);
        tvTrackArtist.setText(artist);

        // Carátula
        Bitmap art = loadAlbumArt(track);
        vinylView.setAlbumArt(art);

        // Duración
        int duration = mediaPlayer.getDuration();
        seekBar.setMax(duration);
        tvTimeTotal.setText(formatTime(duration));
        tvTimeCurrent.setText("00:00");
        seekBar.setProgress(0);

        updateMediaMetadata(track);
        updatePlaybackState(false);

        if (autoPlay) {
            startPlayback();
        } else {
            vinylView.setPlaying(false);
            btnPlayPause.setText("\u25B6\uFE0F");
        }
    }

    private void startPlayback() {
        if (mediaPlayer == null) return;
        mediaPlayer.start();
        vinylView.setPlaying(true);
        btnPlayPause.setText("\u23F8\uFE0F");
        updatePlaybackState(true);
        uiHandler.removeCallbacks(progressUpdater);
        uiHandler.post(progressUpdater);
    }

    private void pausePlayback() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        vinylView.setPlaying(false);
        btnPlayPause.setText("\u25B6\uFE0F");
        updatePlaybackState(false);
        uiHandler.removeCallbacks(progressUpdater);
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
        }
        vinylView.setPlaying(false);
        btnPlayPause.setText("\u25B6\uFE0F");
        updatePlaybackState(false);
        uiHandler.removeCallbacks(progressUpdater);
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) {
            if (!playlist.isEmpty()) {
                loadTrack(playlist.get(currentIndex >= 0 ? currentIndex : 0), true);
            } else {
                Toast.makeText(this, "No hay pistas cargadas", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (mediaPlayer.isPlaying()) {
            pausePlayback();
        } else {
            startPlayback();
        }
    }

    private void playNext() {
        if (playlist.isEmpty()) return;

        if (shuffleEnabled) {
            int next;
            do {
                next = random.nextInt(playlist.size());
            } while (playlist.size() > 1 && next == currentIndex);
            currentIndex = next;
        } else {
            currentIndex = (currentIndex + 1) % playlist.size();
        }
        loadTrack(playlist.get(currentIndex), true);
    }

    private void playPrevious() {
        if (playlist.isEmpty()) return;

        if (shuffleEnabled) {
            int prev;
            do {
                prev = random.nextInt(playlist.size());
            } while (playlist.size() > 1 && prev == currentIndex);
            currentIndex = prev;
        } else {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
        }
        loadTrack(playlist.get(currentIndex), true);
    }

    private void onTrackCompleted() {
        if (repeatMode == 2) {
            // Repetir una
            if (mediaPlayer != null) {
                mediaPlayer.seekTo(0);
                mediaPlayer.start();
            }
        } else if (repeatMode == 1) {
            // Repetir todas
            playNext();
        } else {
            // Sin repetir: avanzar hasta el final y parar
            if (currentIndex < playlist.size() - 1) {
                playNext();
            } else {
                stopPlayback();
                currentIndex = 0;
            }
        }
    }

    private void toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        btnShuffle.setText(shuffleEnabled ? "\uD83D\uDD01 ON" : "\uD83D\uDD01");
        Toast.makeText(this,
                shuffleEnabled ? "Aleatorio activado" : "Aleatorio desactivado",
                Toast.LENGTH_SHORT).show();
    }

    private void toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        switch (repeatMode) {
            case 0:
                btnRepeat.setText("\uD83D\uDD01");
                Toast.makeText(this, "Repetir desactivado", Toast.LENGTH_SHORT).show();
                break;
            case 1:
                btnRepeat.setText("\uD83D\uDD01 TODO");
                Toast.makeText(this, "Repetir todas", Toast.LENGTH_SHORT).show();
                break;
            case 2:
                btnRepeat.setText("\uD83D\uDD02 UNA");
                Toast.makeText(this, "Repetir una", Toast.LENGTH_SHORT).show();
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Progreso
    // -------------------------------------------------------------------------
    private void updateProgress() {
        if (mediaPlayer == null) return;
        try {
            int pos = mediaPlayer.getCurrentPosition();
            int dur = mediaPlayer.getDuration();
            seekBar.setMax(dur);
            seekBar.setProgress(pos);
            tvTimeCurrent.setText(formatTime(pos));
            tvTimeTotal.setText(formatTime(dur));
        } catch (IllegalStateException ignored) {
        }
    }

    private String formatTime(int ms) {
        if (ms <= 0) return "00:00";
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------
    private Bitmap loadAlbumArt(File track) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(track.getAbsolutePath());
            byte[] art = retriever.getEmbeddedPicture();
            if (art != null) {
                return BitmapFactory.decodeByteArray(art, 0, art.length);
            }
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (IOException ignored) { }
        }
        return null;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // -------------------------------------------------------------------------
    // Organizador automático
    // -------------------------------------------------------------------------
    private void runOrganizer() {
        if (!checkStoragePermissionGranted()) {
            Toast.makeText(this,
                    "Concede primero 'Acceso a todos los archivos'",
                    Toast.LENGTH_LONG).show();
            checkAndRequestStoragePermission();
            return;
        }

        Toast.makeText(this, "Organizando descargas...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            final int moved = MusicOrganizer.organizeDownloads();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this,
                        "Organización completada: " + moved + " archivos movidos",
                        Toast.LENGTH_LONG).show();
                switchStyle(currentStyle);
            });
        }).start();
    }

    // -------------------------------------------------------------------------
    // Botón atrás: pausar en lugar de salir
    // -------------------------------------------------------------------------
    @Override
    public void onBackPressed() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            pausePlayback();
        } else {
            super.onBackPressed();
        }
    }
}
