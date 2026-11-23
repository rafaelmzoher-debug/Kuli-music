package com.kuli.vinilo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

public class VinylTurntableView extends View {

    public interface OnSwipeListener {
        void onSwipeLeft();
        void onSwipeRight();
    }

    private final Paint woodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vinylPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spindlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap albumArt;
    private BitmapShader artShader;
    private final Matrix artMatrix = new Matrix();

    private boolean playing = false;
    private float angleDeg = 0f;
    private float armDeg = 0f;
    private OnSwipeListener swipeListener;
    private GestureDetector detector;

    public VinylTurntableView(Context c) { super(c); init(c); }
    public VinylTurntableView(Context c, AttributeSet a) { super(c, a); init(c); }
    public VinylTurntableView(Context c, AttributeSet a, int d) { super(c, a, d); init(c); }

    private void init(Context c) {
        woodPaint.setColor(Color.parseColor("#3B2416"));
        rimPaint.setColor(Color.parseColor("#262220"));
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(10f);
        vinylPaint.setColor(Color.parseColor("#111111"));
        groovePaint.setColor(Color.parseColor("#2A2A2A"));
        groovePaint.setStyle(Paint.Style.STROKE);
        groovePaint.setStrokeWidth(2f);
        labelPaint.setColor(Color.parseColor("#D4A359"));
        spindlePaint.setColor(Color.parseColor("#E0E0E0"));
        armPaint.setColor(Color.parseColor("#CFC7BC"));
        armPaint.setStrokeWidth(14f);
        armPaint.setStrokeCap(Paint.Cap.ROUND);
        headPaint.setColor(Color.parseColor("#E8C27A"));

        detector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (swipeListener != null && Math.abs(vx) > 250) {
                    if (vx < 0) swipeListener.onSwipeLeft();
                    else swipeListener.onSwipeRight();
                    return true;
                }
                return false;
            }
        });
    }

    public void setOnSwipeListener(OnSwipeListener l) { this.swipeListener = l; }
    public void setPlaying(boolean p) { this.playing = p; invalidate(); }
    public void setTrackInfo(String t, String a) { invalidate(); }

    public void setAlbumArt(Bitmap bmp) {
        this.albumArt = bmp;
        this.artShader = bmp != null ? new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return detector.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad = 16f;
        canvas.drawRoundRect(new RectF(pad, pad, w - pad, h - pad), 36f, 36f, woodPaint);

        float cx = w * 0.46f;
        float cy = h * 0.52f;
        float r = Math.min(w * 0.40f, h * 0.42f);

        canvas.drawCircle(cx, cy, r + 8f, rimPaint);

        canvas.save();
        canvas.rotate(angleDeg, cx, cy);
        canvas.drawCircle(cx, cy, r, vinylPaint);

        for (float gr = r * 0.42f; gr < r * 0.94f; gr += 14f) {
            canvas.drawCircle(cx, cy, gr, groovePaint);
        }

        float labelR = r * 0.34f;
        canvas.drawCircle(cx, cy, labelR, labelPaint);

        if (artShader != null && albumArt != null) {
            float scale = (labelR * 2f) / Math.min(albumArt.getWidth(), albumArt.getHeight());
            artMatrix.reset();
            artMatrix.setScale(scale, scale);
            artMatrix.postTranslate(cx - labelR, cy - labelR);
            artShader.setLocalMatrix(artMatrix);
            artPaint.setShader(artShader);
            canvas.drawCircle(cx, cy, labelR * 0.92f, artPaint);
        }

        canvas.drawCircle(cx, cy, 12f, spindlePaint);
        canvas.restore();

        float targetArm = playing ? 26f : 0f;
        armDeg += (targetArm - armDeg) * 0.15f;

        float px = w * 0.86f;
        float py = h * 0.18f;
        canvas.drawCircle(px, py, 24f, headPaint);

        canvas.save();
        canvas.translate(px, py);
        canvas.rotate(armDeg);
        canvas.drawLine(0f, 0f, -r * 0.25f, r * 1.15f, armPaint);
        canvas.drawRoundRect(new RectF(-r * 0.25f - 14f, r * 1.10f, -r * 0.25f + 14f, r * 1.24f), 6f, 6f, headPaint);
        canvas.restore();

        if (playing || Math.abs(targetArm - armDeg) > 0.3f) {
            if (playing) angleDeg = (angleDeg + 2.2f) % 360f;
            postInvalidateOnAnimation();
        }
    }
}
