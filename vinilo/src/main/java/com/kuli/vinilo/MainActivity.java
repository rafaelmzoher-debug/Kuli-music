        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(btn("⏮", v -> play(idx - 1)));
        btnPlay = btn("▶", v -> toggle());
        row.addView(btnPlay);
        row.addView(btn("⏭", v -> play(idx + 1)));
        row.addView(btn("📥", v -> organize()));
        root.addView(row);
        setContentView(root);
        scan();
    }

    private Button btn(String t, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setTextSize(20f);
        b.setTextColor(Color.parseColor("#181310"));
        b.setBackgroundColor(Color.parseColor("#E8C27A"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(150, 130);
        lp.setMargins(12, 12, 12, 0); b.setLayoutParams(lp);
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
            runOnUiThread(() -> { Toast.makeText(this, "Organizadas: " + n, Toast.LENGTH_LONG).show(); scan(); });
        }).start();
    }

    private void scan() {
        list.clear(); if (!okPerm()) return;
        addDir(new File("/storage/emulated/0/Music"));
        tvInfo.setText("KULI VINILO · " + list.size() + " pistas");
    }

    private void addDir(File d) {
        File[] fs = d.listFiles(); if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) addDir(f);
            else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".mp3") || f.getName().toLowerCase(Locale.ROOT).endsWith(".m4a")) list.add(f);
        }
    }

    private void toggle() {
        if (list.isEmpty()) { organize(); return; }
        if (mp == null) { play(0); return; }
        if (mp.isPlaying()) { mp.pause(); vinyl.setPlaying(false); btnPlay.setText("▶"); }
        else { mp.start(); vinyl.setPlaying(true); btnPlay.setText("⏸"); }
    }

    private void play(int i) {
        if (list.isEmpty()) return;
        idx = (i + list.size()) % list.size();
        File f = list.get(idx);
        try {
            if (mp != null) mp.release();
            mp = new MediaPlayer(); mp.setDataSource(f.getAbsolutePath()); mp.prepare(); mp.start();
            mp.setOnCompletionListener(m -> play(idx + 1));
            MediaMetadataRetriever mmr = new MediaMetadataRetriever(); mmr.setDataSource(f.getAbsolutePath());
            byte[] art = mmr.getEmbeddedPicture();
            vinyl.setAlbumArt(art != null ? BitmapFactory.decodeByteArray(art, 0, art.length) : null);
            mmr.release();
            tvInfo.setText(f.getName().replaceFirst("\\.[^.]+$", ""));
            vinyl.setPlaying(true); btnPlay.setText("⏸");
        } catch (Exception e) { Toast.makeText(this, "Error audio", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onResume() { super.onResume(); if (list.isEmpty()) scan(); }
    @Override protected void onDestroy() { super.onDestroy(); if (mp != null) mp.release(); }
}
