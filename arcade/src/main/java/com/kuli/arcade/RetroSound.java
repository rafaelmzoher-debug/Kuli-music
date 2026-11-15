package com.kuli.arcade;
import android.media.*;
public class RetroSound {
    public static boolean enabled = true;
    public static void playTone(final double freqHz, final int durationMs) {
        if (!enabled) return;
        new Thread(() -> {
            try {
                int sr = 22050, ns = (durationMs * sr) / 1000;
                short[] buf = new short[ns];
                for (int i = 0; i < ns; i++) {
                    double ph = (2.0 * Math.PI * i * freqHz) / sr;
                    buf[i] = (short) ((Math.sin(ph) >= 0 ? 1 : -1) * 6000 * (1.0 - (double) i / ns));
                }
                AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(buf.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
                t.write(buf, 0, buf.length); t.play(); Thread.sleep(durationMs + 20); t.release();
            } catch (Throwable ignored) {}
        }).start();
    }
    public static void blip() { playTone(520, 45); }
    public static void score() { playTone(880, 90); }
    public static void boom() { playTone(160, 140); }
    public static void gameOver() { playTone(190, 260); }
}
