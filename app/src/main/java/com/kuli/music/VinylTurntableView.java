
package com.kuli.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * VinylTurntableView - Vista personalizada que dibuja un tocadiscos vintage
 * con vinilo negro, surcos, reflejos, carátula central, brazo metálico animado
 * y detección de gestos laterales para cambiar de canción.
 *
 * Toda la estética se dibuja con Canvas. No usa imágenes externas salvo la
 * carátula opcional que se le pase con setAlbumArt().
 */
public class VinylTurntableView extends View {

    // -------------------------------------------------------------------------
    // Listener para gestos laterales
    // -------------------------------------------------------------------------
    public interface OnSwipeListener {
        void onSwipeLeft();   // siguiente canción
        void onSwipeRight();  // canción anterior
    }

    private OnSwipeListener swipeListener;

    public void setOnSwipeListener(OnSwipeListener listener) {
        this.swipeListener = listener;
    }

    // -------------------------------------------------------------------------
    // Estado de reproducción y animación
    // -------------------------------------------------------------------------
    private boolean isPlaying = false;
    private float rotationAngle = 0f;          // grados acumulados del vinilo
    private float armAngle = 0f;               // 0 = brazo en soporte, 1 = sobre disco
    private float armTarget = 0f;              // objetivo de animación del brazo
    private long lastFrameTime = 0L;

    private Bitmap albumArt = null;
    private BitmapShader albumArtShader = null;
    private final Matrix albumArtMatrix = new Matrix();

    // Velocidad de giro: 33⅓ RPM = 200 grados/segundo aproximadamente
    private static final float DEGREES_PER_SECOND = 200f;
    // Duración de la animación del brazo en milisegundos
    private static final long ARM_ANIMATION_MS = 700L;
    private long armAnimationStart = 0L;
    private float armAnimationFrom = 0f;

    // -------------------------------------------------------------------------
    // Paints reutilizables (evitar allocations en onDraw)
    // -------------------------------------------------------------------------
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plinthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plinthEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vinylPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spindlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint armBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint armHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headshellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pivotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path armPath = new Path();
    private final RectF tmpRect = new RectF();

    // -------------------------------------------------------------------------
    // GestureDetector para fling lateral
    // -------------------------------------------------------------------------
    private final GestureDetector gestureDetector;

    private static final int SWIPE_MIN_DISTANCE_DP = 60;
    private static final int SWIPE_MIN_VELOCITY_DP = 200;

    public VinylTurntableView(Context context) {
        super(context);
        gestureDetector = createGestureDetector(context);
        initPaints();
    }

    public VinylTurntableView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gestureDetector = createGestureDetector(context);
        initPaints();
    }

    public VinylTurntableView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        gestureDetector = createGestureDetector(context);
        initPaints();
    }

    private GestureDetector createGestureDetector(Context context) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int minDistance = (int) (SWIPE_MIN_DISTANCE_DP * density);
        final int minVelocity = (int) (SWIPE_MIN_VELOCITY_DP * density);

        return new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;

                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();

                // Solo contar gestos predominantemente horizontales
                if (Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (Math.abs(dx) > minDistance && Math.abs(velocityX) > minVelocity) {
                        if (swipeListener != null) {
                            if (dx < 0) {
                                // deslizar a la izquierda => siguiente
                                swipeListener.onSwipeLeft();
                            } else {
                                // deslizar a la derecha => anterior
                                swipeListener.onSwipeRight();
                            }
                        }
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void initPaints() {
        // Fondo general: madera oscura
        backgroundPaint.setColor(Color.parseColor("#2B1B0E"));

        // Base del tocadiscos (plinto) con degradado
        plinthPaint.setColor(Color.parseColor("#3E2A18"));

        plinthEdgePaint.setColor(Color.parseColor("#1A0F07"));
        plinthEdgePaint.setStyle(Paint.Style.STROKE);
        plinthEdgePaint.setStrokeWidth(6f);

        // Vinilo negro
        vinylPaint.setColor(Color.parseColor("#0A0A0A"));
        vinylPaint.setStyle(Paint.Style.FILL);

        // Surcos: gris muy sutil
        groovePaint.setColor(Color.parseColor("#1C1C1C"));
        groovePaint.setStyle(Paint.Style.STROKE);
        groovePaint.setStrokeWidth(1.2f);

        // Reflejos: blanco translúcido
        highlightPaint.setColor(Color.parseColor("#33FFFFFF"));
        highlightPaint.setStyle(Paint.Style.STROKE);

        // Etiqueta central (sello vintage por defecto)
        labelPaint.setColor(Color.parseColor("#C9A227")); // dorado vintage
        labelPaint.setStyle(Paint.Style.FILL);

        labelRingPaint.setColor(Color.parseColor("#7A5C10"));
        labelRingPaint.setStyle(Paint.Style.STROKE);
        labelRingPaint.setStrokeWidth(3f);

        // Eje central
        spindlePaint.setColor(Color.parseColor("#D9D9D9"));
        spindlePaint.setStyle(Paint.Style.FILL);

        // Base del brazo (pivote)
        armBasePaint.setColor(Color.parseColor("#5A5A5A"));
        armBasePaint.setStyle(Paint.Style.FILL);

        // Brazo metálico
        armPaint.setColor(Color.parseColor("#B8B8B8"));
        armPaint.setStyle(Paint.Style.STROKE);
        armPaint.setStrokeWidth(8f);
        armPaint.setStrokeCap(Paint.Cap.ROUND);
        armPaint.setStrokeJoin(Paint.Join.ROUND);

        armHighlightPaint.setColor(Color.parseColor("#F0F0F0"));
        armHighlightPaint.setStyle(Paint.Style.STROKE);
        armHighlightPaint.setStrokeWidth(2.5f);
        armHighlightPaint.setStrokeCap(Paint.Cap.ROUND);

        // Cápsula / cabeza del brazo
        headshellPaint.setColor(Color.parseColor("#3A3A3A"));
        headshellPaint.setStyle(Paint.Style.FILL);

        // Aguja
        needlePaint.setColor(Color.parseColor("#E0E0E0"));
        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(2f);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);

        pivotPaint.setColor(Color.parseColor("#8C8C8C"));
        pivotPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.parseColor("#F5E6C8"));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    // -------------------------------------------------------------------------
    // API pública para MainActivity
    // -------------------------------------------------------------------------

    /**
     * Actualiza la carátula central. Si es null, se dibuja el sello vintage.
     */
    public void setAlbumArt(Bitmap bitmap) {
        if (bitmap == null) {
            this.albumArt = null;
            this.albumArtShader = null;
        } else {
            this.albumArt = bitmap;
            this.albumArtShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        invalidate();
    }

    /**
     * Inicia o detiene la reproducción. Anima el brazo hacia el disco o hacia
     * su soporte, y activa/desactiva el giro del vinilo.
     */
    public void setPlaying(boolean playing) {
        if (this.isPlaying == playing) return;
        this.isPlaying = playing;

        armAnimationFrom = armAngle;
        armTarget = playing ? 1f : 0f;
        armAnimationStart = System.currentTimeMillis();

        if (playing) {
            lastFrameTime = System.currentTimeMillis();
        }
        invalidate();
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * Fuerza el estado del brazo sin animar (útil al inicializar).
     */
    public void setArmPositionImmediate(boolean onDisc) {
        armAngle = onDisc ? 1f : 0f;
        armTarget = armAngle;
        invalidate();
    }

    // -------------------------------------------------------------------------
    // Touch: delegamos al GestureDetector, permitiendo también clicks
    // -------------------------------------------------------------------------
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        return handled || super.onTouchEvent(event);
    }

    // -------------------------------------------------------------------------
    // onDraw
    // -------------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        long now = System.currentTimeMillis();

        // Avanzar rotación del vinilo si está reproduciendo
        if (isPlaying && lastFrameTime > 0L) {
            float dt = (now - lastFrameTime) / 1000f;
            rotationAngle = (rotationAngle + DEGREES_PER_SECOND * dt) % 360f;
            lastFrameTime = now;
        } else if (!isPlaying) {
            lastFrameTime = 0L;
        }

        // Animar el brazo
        if (armAngle != armTarget) {
            float elapsed = now - armAnimationStart;
            float t = Math.min(1f, elapsed / (float) ARM_ANIMATION_MS);
            float eased = new DecelerateInterpolator().getInterpolation(t);
            armAngle = armAnimationFrom + (armTarget - armAnimationFrom) * eased;
            if (t >= 1f) {
                armAngle = armTarget;
            }
        }

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // ---------------------------------------------------------------------
        // 1. Fondo madera
        // ---------------------------------------------------------------------
        canvas.drawRect(0, 0, w, h, backgroundPaint);

        // ---------------------------------------------------------------------
        // 2. Plinto del tocadiscos (rectángulo redondeado con bisel)
        // ---------------------------------------------------------------------
        float plinthMargin = Math.min(w, h) * 0.04f;
        tmpRect.set(plinthMargin, plinthMargin, w - plinthMargin, h - plinthMargin);
        float plinthRadius = Math.min(w, h) * 0.05f;

        // Sombra/bisel exterior
        canvas.drawRoundRect(tmpRect, plinthRadius, plinthRadius, plinthEdgePaint);

        // Relleno del plinto con degradado vertical
        LinearGradient plinthGradient = new LinearGradient(
                0, tmpRect.top, 0, tmpRect.bottom,
                Color.parseColor("#4A3220"),
                Color.parseColor("#2B1B0E"),
                Shader.TileMode.CLAMP);
        plinthPaint.setShader(plinthGradient);
        canvas.drawRoundRect(tmpRect, plinthRadius, plinthRadius, plinthPaint);
        plinthPaint.setShader(null);

        // ---------------------------------------------------------------------
        // 3. Geometría del vinilo
        // ---------------------------------------------------------------------
        float centerX = w * 0.46f;
        float centerY = h * 0.50f;
        float maxRadius = Math.min(w, h) * 0.36f;
        float vinylRadius = maxRadius;
        float labelRadius = vinylRadius * 0.30f;
        float spindleRadius = vinylRadius * 0.035f;

        // Sombra suave debajo del vinilo
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.parseColor("#66000000"));
        canvas.drawCircle(centerX + 4f, centerY + 6f, vinylRadius, shadowPaint);

        // ---------------------------------------------------------------------
        // 4. Disco de vinilo
        // ---------------------------------------------------------------------
        // Cuerpo negro con ligero degradado radial para dar volumen
        RadialGradient vinylGradient = new RadialGradient(
                centerX - vinylRadius * 0.3f,
                centerY - vinylRadius * 0.3f,
                vinylRadius * 1.4f,
                new int[]{Color.parseColor("#1A1A1A"), Color.parseColor("#050505")},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP);
        vinylPaint.setShader(vinylGradient);
        canvas.drawCircle(centerX, centerY, vinylRadius, vinylPaint);
        vinylPaint.setShader(null);

        // Surcos concéntricos
        int grooveCount = 40;
        for (int i = 1; i <= grooveCount; i++) {
            float r = labelRadius + (vinylRadius - labelRadius) * (i / (float) (grooveCount + 1));
            // Alternar intensidad de surcos para simular separación de pistas
            if (i % 5 == 0) {
                groovePaint.setColor(Color.parseColor("#2A2A2A"));
                groovePaint.setStrokeWidth(1.6f);
            } else {
                groovePaint.setColor(Color.parseColor("#161616"));
                groovePaint.setStrokeWidth(1f);
            }
            canvas.drawCircle(centerX, centerY, r, groovePaint);
        }

        // Reflejo de luz: arco suave en la parte superior izquierda,
        // rotando con el disco para dar sensación de giro
        canvas.save();
        canvas.rotate(rotationAngle + 45f, centerX, centerY);

        SweepGradient shineGradient = new SweepGradient(
                centerX, centerY,
                new int[]{
                        Color.TRANSPARENT,
                        Color.parseColor("#22FFFFFF"),
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Color.parseColor("#11FFFFFF"),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.05f, 0.15f, 0.5f, 0.56f, 0.7f});
        highlightPaint.setShader(shineGradient);
        highlightPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(centerX, centerY, vinylRadius, highlightPaint);
        highlightPaint.setShader(null);
        highlightPaint.setStyle(Paint.Style.STROKE);

        canvas.restore();

        // ---------------------------------------------------------------------
        // 5. Etiqueta central (carátula o sello vintage)
        // ---------------------------------------------------------------------
        // Aro exterior de la etiqueta
        canvas.drawCircle(centerX, centerY, labelRadius, labelPaint);
        canvas.drawCircle(centerX, centerY, labelRadius - 2f, labelRingPaint);

        // Si hay carátula, la dibujamos rotando con el disco
        if (albumArt != null && albumArtShader != null) {
            albumArtMatrix.reset();
            // Escalar la carátula al diámetro de la etiqueta
            float scale = (labelRadius * 2f) / Math.min(albumArt.getWidth(), albumArt.getHeight());
            float scaledW = albumArt.getWidth() * scale;
            float scaledH = albumArt.getHeight() * scale;

            albumArtMatrix.postTranslate(-scaledW / 2f, -scaledH / 2f);
            albumArtMatrix.postRotate(rotationAngle);
            albumArtMatrix.postTranslate(centerX, centerY);
            albumArtShader.setLocalMatrix(albumArtMatrix);

            Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            artPaint.setShader(albumArtShader);
            canvas.drawCircle(centerX, centerY, labelRadius - 4f, artPaint);
            artPaint.setShader(null);
        } else {
            // Sello vintage por defecto: texto circular simplificado
            canvas.save();
            canvas.rotate(rotationAngle, centerX, centerY);

            // Círculo interior del sello
            Paint innerLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
            innerLabel.setColor(Color.parseColor("#A8871C"));
            innerLabel.setStyle(Paint.Style.FILL);
            canvas.drawCircle(centerX, centerY, labelRadius * 0.85f, innerLabel);

            // Texto "KULI MUSIC" curvado de forma simplificada (línea recta rotada)
            textPaint.setTextSize(labelRadius * 0.28f);
            canvas.drawText("KULI MUSIC", centerX, centerY + labelRadius * 0.10f, textPaint);

            // Texto inferior pequeño
            textPaint.setTextSize(labelRadius * 0.16f);
            canvas.drawText("33\u2153 RPM", centerX, centerY + labelRadius * 0.45f, textPaint);

            canvas.restore();
        }

        // Eje central
        canvas.drawCircle(centerX, centerY, spindleRadius, spindlePaint);
        // Brillo del eje
        Paint spindleShine = new Paint(Paint.ANTI_ALIAS_FLAG);
        spindleShine.setColor(Color.parseColor("#FFFFFF"));
        canvas.drawCircle(centerX - spindleRadius * 0.3f, centerY - spindleRadius * 0.3f,
                spindleRadius * 0.35f, spindleShine);

        // ---------------------------------------------------------------------
        // 6. Brazo y aguja
        // ---------------------------------------------------------------------
        drawTonearm(canvas, w, h, vinylRadius, centerX, centerY);

        // ---------------------------------------------------------------------
        // 7. Redibujar a ~60fps si hay animación en curso
        // ---------------------------------------------------------------------
        if (isPlaying || armAngle != armTarget) {
            postInvalidateOnAnimation();
        }
    }

    /**
     * Dibuja el brazo metálico con su base, la cápsula y la aguja.
     * El brazo pivota desde la esquina superior derecha del plinto.
     */
    private void drawTonearm(Canvas canvas, int w, int h, float vinylRadius,
                             float vinylCenterX, float vinylCenterY) {
        // Pivote del brazo: esquina superior derecha del plinto
        float pivotX = w * 0.86f;
        float pivotY = h * 0.20f;

        // Longitud del brazo
        float armLength = Math.min(w, h) * 0.55f;

        // Ángulos:
        // - Reposo (armAngle=0): brazo apuntando hacia abajo-derecha, fuera del disco.
        // - Sobre disco (armAngle=1): brazo apuntando hacia el centro del disco.
        float restAngleDeg = 25f;    // inclinación hacia fuera en reposo
        float playAngleDeg = -48f;   // inclinación hacia el disco al reproducir

        float currentAngleDeg = restAngleDeg + (playAngleDeg - restAngleDeg) * armAngle;
        double currentAngleRad = Math.toRadians(currentAngleDeg);

        // Extremo del brazo
        float endX = pivotX + (float) (Math.cos(currentAngleRad) * armLength);
        float endY = pivotY + (float) (Math.sin(currentAngleRad) * armLength);

        // Base del pivote (círculo grande metálico)
        canvas.drawCircle(pivotX, pivotY, armLength * 0.09f, armBasePaint);
        canvas.drawCircle(pivotX, pivotY, armLength * 0.09f, plinthEdgePaint);

        // Tubo del brazo con sombra
        Paint shadowArm = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowArm.setColor(Color.parseColor("#40000000"));
        shadowArm.setStyle(Paint.Style.STROKE);
        shadowArm.setStrokeWidth(10f);
        shadowArm.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(pivotX + 3f, pivotY + 3f, endX + 3f, endY + 3f, shadowArm);

        // Tubo principal
        canvas.drawLine(pivotX, pivotY, endX, endY, armPaint);

        // Brillo metálico a lo largo del tubo
        canvas.drawLine(pivotX - 1f, pivotY - 1f, endX - 1f, endY - 1f, armHighlightPaint);

        // Cápsula / headshell en el extremo
        float headshellRadius = armLength * 0.055f;
        canvas.drawCircle(endX, endY, headshellRadius, headshellPaint);

        // Brillo de la cápsula
        Paint hsShine = new Paint(Paint.ANTI_ALIAS_FLAG);
        hsShine.setColor(Color.parseColor("#666666"));
        canvas.drawCircle(endX - headshellRadius * 0.3f, endY - headshellRadius * 0.3f,
                headshellRadius * 0.4f, hsShine);

        // Aguja: pequeña línea desde la cápsula hacia el disco (perpendicular al brazo)
        double needleAngleRad = currentAngleRad + Math.PI / 2;
        float needleLength = armLength * 0.07f;
        float needleEndX = endX + (float) (Math.cos(needleAngleRad) * needleLength);
        float needleEndY = endY + (float) (Math.sin(needleAngleRad) * needleLength);
        canvas.drawLine(endX, endY, needleEndX, needleEndY, needlePaint);

        // Punto de contacto de la aguja: pequeño círculo
        Paint needleTip = new Paint(Paint.ANTI_ALIAS_FLAG);
        needleTip.setColor(Color.parseColor("#F0F0F0"));
        canvas.drawCircle(needleEndX, needleEndY, 2.5f, needleTip);

        // Pivote central (tornillo)
        canvas.drawCircle(pivotX, pivotY, armLength * 0.025f, pivotPaint);
        Paint pivotShine = new Paint(Paint.ANTI_ALIAS_FLAG);
        pivotShine.setColor(Color.parseColor("#DDDDDD"));
        canvas.drawCircle(pivotX - 2f, pivotY - 2f, armLength * 0.010f, pivotShine);
    }
}

