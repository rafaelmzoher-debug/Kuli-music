package com.kuli.arcade;
import android.app.Activity; import android.content.*; import android.graphics.*; import android.os.*; import android.view.*; import java.util.*;

public class GameView extends View {
    public static final int GAME_BOBBLE = 1, GAME_ARKANOID = 2;
    private final int gameType;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private int score = 0, bestScore = 0;
    private boolean gameOver = false;
    private final RectF btnMenu = new RectF(), b1 = new RectF(), b2 = new RectF(), b3 = new RectF(), b4 = new RectF();

    // BUBBLE BOBBLE
    private float bubX = 0.15f, bubY = 0.80f, bubVY = 0f;
    private int bubDir = 1, bubLives = 3, bubLevel = 1;
    private final ArrayList<float[]> bEnemies = new ArrayList<>();
    private final ArrayList<float[]> bBubbles = new ArrayList<>();

    // ARKANOID
    private float padX = 0.5f, aBallX = 0.5f, aBallY = 0.75f, aVX = 0.008f, aVY = -0.011f;
    private int lives = 3, arkLevel = 1;
    private int[][] bricks = new int[6][8];

    private final Runnable loop = new Runnable() { @Override public void run() { update(); invalidate(); handler.postDelayed(this, 16); } };
    public GameView(Context ctx, int type) {
        super(ctx); this.gameType = type;
        bestScore = ctx.getSharedPreferences("kuli_arcade_scores", Context.MODE_PRIVATE).getInt("best_" + gameType, 0);
        initGame();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); handler.post(loop); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); handler.removeCallbacks(loop); }
    private void saveBest() {
        if (score > bestScore) { bestScore = score; getContext().getSharedPreferences("kuli_arcade_scores", Context.MODE_PRIVATE).edit().putInt("best_" + gameType, bestScore).apply(); }
    }
    private void initGame() {
        score = 0; gameOver = false;
        if (gameType == GAME_BOBBLE) { bubX = 0.15f; bubY = 0.80f; bubVY = 0f; bubDir = 1; bubLives = 3; bubLevel = 1; spawnBubbleLevel(); }
        else { lives = 3; arkLevel = 1; padX = 0.5f; spawnBricks(); resetBall(); }
    }
    private void spawnBubbleLevel() {
        bEnemies.clear(); bBubbles.clear();
        int n = Math.min(6, 2 + bubLevel);
        for (int i = 0; i < n; i++) {
            float py = (i % 3 == 0) ? 0.28f : ((i % 3 == 1) ? 0.46f : 0.64f);
            float px = 0.22f + (i * 0.14f) % 0.66f;
            bEnemies.add(new float[]{px, py, (i % 2 == 0 ? 1 : -1) * (0.004f + bubLevel * 0.0007f), 0f});
        }
    }
    private void spawnBricks() {
        for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) bricks[r][c] = (r < 2) ? 2 : 1;
    }
    private void resetBall() { aBallX = padX; aBallY = 0.78f; aVX = (rnd.nextBoolean() ? 1 : -1) * 0.008f; aVY = -0.011f; }

    private void update() {
        if (gameOver) return;
        if (gameType == GAME_BOBBLE) {
            float prevY = bubY; bubVY += 0.0014f; bubY += bubVY;
            float[] plats = {0.28f, 0.46f, 0.64f, 0.80f};
            for (float py : plats) {
                boolean hueco = (py < 0.75f && (bubX < 0.12f || bubX > 0.88f));
                if (!hueco && bubVY >= 0 && prevY <= py && bubY >= py) { bubY = py; bubVY = 0f; }
            }
            if (bubY > 0.80f) { bubY = 0.80f; bubVY = 0f; }
            for (int i = bBubbles.size() - 1; i >= 0; i--) {
                float[] b = bBubbles.get(i);
                b[0] += b[2]; b[2] *= 0.92f;
                if (Math.abs(b[2]) < 0.002f) b[1] -= 0.0045f;
                if (b[0] < 0.06f) b[0] = 0.06f; if (b[0] > 0.94f) b[0] = 0.94f;
                if (b[1] < 0.14f) b[1] = 0.14f;
                b[4] += 1f;
                if (b[4] > 420f) bBubbles.remove(i);
                else if (b[3] > 0 && Math.hypot(bubX - b[0], bubY - b[1]) < 0.065f) {
                    bBubbles.remove(i); score += 150; RetroSound.score(); saveBest();
                }
            }
            for (int i = bEnemies.size() - 1; i >= 0; i--) {
                float[] en = bEnemies.get(i); en[0] += en[2];
                if (en[0] < 0.08f || en[0] > 0.92f) en[2] = -en[2];
                boolean atrap = false;
                for (float[] b : bBubbles) {
                    if (b[3] == 0 && Math.abs(b[2]) > 0.002f && Math.hypot(en[0] - b[0], en[1] - b[1]) < 0.065f) {
                        b[3] = 1f; b[2] = 0f; atrap = true; RetroSound.blip(); break;
                    }
                }
                if (atrap) { bEnemies.remove(i); continue; }
                if (Math.hypot(bubX - en[0], bubY - en[1]) < 0.055f) {
                    bubLives--; RetroSound.boom(); bubX = 0.15f; bubY = 0.80f;
                    if (bubLives <= 0) { gameOver = true; RetroSound.gameOver(); saveBest(); }
                }
            }
            boolean q = false; for (float[] b : bBubbles) if (b[3] > 0) q = true;
            if (bEnemies.isEmpty() && !q) { bubLevel++; score += 300; spawnBubbleLevel(); }
        } else {
            aBallX += aVX; aBallY += aVY;
            if (aBallX <= 0.03f || aBallX >= 0.97f) { aVX = -aVX; RetroSound.blip(); }
            if (aBallY <= 0.12f) { aVY = -aVY; RetroSound.blip(); }
            if (aBallY >= 0.84f && aBallY <= 0.87f && Math.abs(aBallX - padX) <= 0.15f && aVY > 0) {
                aVY = -Math.abs(aVY) * 1.015f; aVX = (aBallX - padX) * 0.11f; RetroSound.blip();
            }
            if (aBallY > 0.96f) {
                lives--; RetroSound.boom();
                if (lives <= 0) { gameOver = true; RetroSound.gameOver(); saveBest(); } else resetBall();
            }
            boolean any = false;
            for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) {
                if (bricks[r][c] <= 0) continue;
                any = true;
                float bx1 = 0.04f + c * 0.115f, by1 = 0.16f + r * 0.042f;
                if (aBallX >= bx1 && aBallX <= bx1 + 0.105f && aBallY >= by1 && aBallY <= by1 + 0.034f) {
                    bricks[r][c]--; aVY = -aVY; score += 50; RetroSound.score(); saveBest();
                }
            }
            if (!any) { arkLevel++; score += 300; spawnBricks(); resetBall(); }
        }
    }

    private void drawBtn(Canvas c, RectF r, String txt) {
        paint.setColor(Color.rgb(18, 56, 108));
        c.drawRoundRect(r, 22, 22, paint);
        paint.setColor(Color.rgb(212, 175, 55));
        paint.setTextSize(r.height() * 0.44f);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(txt, r.centerX(), r.centerY() + r.height() * 0.16f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(Color.rgb(6, 20, 44));
        paint.setColor(Color.rgb(12, 42, 82)); c.drawRect(0, 0, w, h * 0.10f, paint);
        btnMenu.set(w * 0.03f, h * 0.022f, w * 0.24f, h * 0.078f);
        drawBtn(c, btnMenu, "◀ Menú");

        paint.setColor(Color.rgb(212, 175, 55)); paint.setTextSize(w * 0.040f); paint.setFakeBoldText(true);
        c.drawText("PTS: " + score, w * 0.28f, h * 0.062f, paint);
        paint.setColor(Color.WHITE); c.drawText("RÉCORD: " + bestScore, w * 0.60f, h * 0.062f, paint);

        if (gameType == GAME_BOBBLE) {
            paint.setColor(Color.rgb(30, 115, 220));
            float[] plats = {0.28f, 0.46f, 0.64f};
            for (float py : plats) c.drawRoundRect(new RectF(w * 0.12f, h * py, w * 0.88f, h * (py + 0.018f)), 8, 8, paint);
            c.drawRect(0, h * 0.80f, w, h * 0.822f, paint);
            paint.setColor(Color.rgb(65, 230, 110));
            c.drawCircle(bubX * w, (bubY - 0.022f) * h, w * 0.036f, paint);
            paint.setColor(Color.WHITE);
            c.drawCircle((bubX + bubDir * 0.014f) * w, (bubY - 0.028f) * h, w * 0.008f, paint);
            paint.setColor(Color.rgb(255, 75, 95));
            for (float[] en : bEnemies) c.drawCircle(en[0] * w, (en[1] - 0.02f) * h, w * 0.033f, paint);
            for (float[] b : bBubbles) {
                paint.setColor(b[3] > 0 ? Color.rgb(255, 130, 160) : Color.argb(170, 90, 230, 255));
                c.drawCircle(b[0] * w, (b[1] - 0.02f) * h, w * 0.038f, paint);
            }
            paint.setColor(Color.WHITE); paint.setTextSize(w * 0.034f);
            c.drawText("NVL " + bubLevel + " · VIDAS " + bubLives, w * 0.04f, h * 0.125f, paint);
            b1.set(w*0.04f, h*0.85f, w*0.25f, h*0.95f); b2.set(w*0.28f, h*0.85f, w*0.49f, h*0.95f);
            b3.set(w*0.52f, h*0.85f, w*0.73f, h*0.95f); b4.set(w*0.76f, h*0.85f, w*0.96f, h*0.95f);
            drawBtn(c, b1, "◀"); drawBtn(c, b2, "▶"); drawBtn(c, b3, "▲"); drawBtn(c, b4, "🫧");
        } else {
            int[] rowCols = {Color.RED, Color.rgb(255,140,0), Color.YELLOW, Color.GREEN, Color.CYAN, Color.MAGENTA};
            for (int r = 0; r < 6; r++) for (int col = 0; col < 8; col++) {
                if (bricks[r][col] <= 0) continue;
                paint.setColor(bricks[r][col] == 2 ? Color.rgb(235, 195, 75) : rowCols[r]);
                float bx1 = w * (0.04f + col * 0.115f), by1 = h * (0.16f + r * 0.042f);
                c.drawRoundRect(new RectF(bx1, by1, bx1 + w * 0.105f, by1 + h * 0.034f), 8, 8, paint);
            }
            paint.setColor(Color.rgb(212, 175, 55));
            c.drawRoundRect(new RectF(w * (padX - 0.14f), h * 0.85f, w * (padX + 0.14f), h * 0.875f), 14, 14, paint);
            paint.setColor(Color.WHITE); c.drawCircle(aBallX * w, aBallY * h, w * 0.022f, paint);
            paint.setTextSize(w * 0.038f); c.drawText("NVL " + arkLevel + " · VIDAS: " + lives, w * 0.06f, h * 0.94f, paint);
        }

        if (gameOver) {
            paint.setColor(Color.argb(215, 6, 18, 38)); c.drawRect(0, h * 0.35f, w, h * 0.65f, paint);
            paint.setColor(Color.rgb(212, 175, 55)); paint.setTextSize(w * 0.08f); paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("FIN DE PARTIDA", w * 0.5f, h * 0.47f, paint);
            paint.setColor(Color.WHITE); paint.setTextSize(w * 0.045f);
            c.drawText("PUNTOS: " + score + "  ·  TOCA PARA REPETIR", w * 0.5f, h * 0.56f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getAction() == MotionEvent.ACTION_DOWN && btnMenu.contains(x, y)) { ((Activity) getContext()).finish(); return true; }
        if (gameOver && e.getAction() == MotionEvent.ACTION_DOWN) { initGame(); return true; }
        if (gameType == GAME_BOBBLE && (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE)) {
            if (b1.contains(x, y)) { bubDir = -1; bubX = Math.max(0.06f, bubX - 0.024f); return true; }
            if (b2.contains(x, y)) { bubDir = 1; bubX = Math.min(0.94f, bubX + 0.024f); return true; }
            if (e.getAction() == MotionEvent.ACTION_DOWN && b3.contains(x, y) && bubVY == 0f) { bubVY = -0.027f; RetroSound.blip(); return true; }
            if (e.getAction() == MotionEvent.ACTION_DOWN && b4.contains(x, y)) { bBubbles.add(new float[]{bubX + bubDir * 0.04f, bubY - 0.02f, bubDir * 0.028f, 0f, 0f}); RetroSound.blip(); return true; }
            return true;
        }
        if (gameType == GAME_ARKANOID) { padX = Math.max(0.15f, Math.min(0.85f, x / getWidth())); return true; }
        return true;
    }
}
