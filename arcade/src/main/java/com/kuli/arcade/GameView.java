package com.kuli.arcade;
import android.app.Activity; import android.content.*; import android.graphics.*; import android.os.*; import android.view.*; import java.util.*;

public class GameView extends View {
    public static final int GAME_PANG = 1, GAME_ARKANOID = 2;
    private final int gameType;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private int score = 0, bestScore = 0, level = 1, lives = 3;
    private boolean gameOver = false;
    private final RectF btnMenu = new RectF(), bIzq = new RectF(), bDer = new RectF(), bDisp = new RectF();

    // PANG / MUÑEQUITO DISPARANDO BOLAS
    private float heroX = 0.5f, stepAnim = 0f;
    private int heroDir = 1;
    private final ArrayList<float[]> shots = new ArrayList<>(); // x, yTop
    private final ArrayList<float[]> balls = new ArrayList<>(); // x, y, vx, vy, radius, colorIdx

    // ARKANOID
    private float padX = 0.5f, aBallX = 0.5f, aBallY = 0.75f, aVX = 0.008f, aVY = -0.011f;
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
        score = 0; level = 1; lives = 3; gameOver = false;
        if (gameType == GAME_PANG) spawnPangLevel();
        else { padX = 0.5f; spawnBricks(); resetBall(); }
    }
    private void spawnPangLevel() {
        heroX = 0.5f; shots.clear(); balls.clear();
        int count = Math.min(4, 1 + (level - 1) / 2);
        for (int i = 0; i < count; i++) {
            float bx = 0.25f + (i * 0.22f) % 0.55f;
            balls.add(new float[]{bx, 0.24f, (i % 2 == 0 ? 1 : -1) * (0.0045f + level * 0.0004f), 0f, 0.075f, i % 4});
        }
    }
    private void spawnBricks() {
        for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) bricks[r][c] = (r < 2) ? 2 : 1;
    }
    private void resetBall() { aBallX = padX; aBallY = 0.78f; aVX = (rnd.nextBoolean() ? 1 : -1) * 0.008f; aVY = -0.011f; }

    private void update() {
        if (gameOver) return;
        if (gameType == GAME_PANG) {
            for (int i = shots.size() - 1; i >= 0; i--) {
                float[] s = shots.get(i);
                s[1] -= 0.024f;
                if (s[1] <= 0.11f) shots.remove(i);
            }
            for (int i = balls.size() - 1; i >= 0; i--) {
                float[] b = balls.get(i);
                b[3] += 0.00042f; // gravedad suave arcade
                b[0] += b[2];
                b[1] += b[3];
                float r = b[4];
                if (b[0] - r < 0.03f) { b[0] = 0.03f + r; b[2] = Math.abs(b[2]); }
                if (b[0] + r > 0.97f) { b[0] = 0.97f - r; b[2] = -Math.abs(b[2]); }
                if (b[1] + r > 0.81f) {
                    b[1] = 0.81f - r;
                    b[3] = -(0.014f + r * 0.11f);
                    RetroSound.blip();
                }
                boolean hit = false;
                for (int j = shots.size() - 1; j >= 0; j--) {
                    float[] s = shots.get(j);
                    if (Math.abs(s[0] - b[0]) <= r + 0.015f && s[1] <= b[1] + r) {
                        shots.remove(j);
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    balls.remove(i);
                    score += 100;
                    RetroSound.score();
                    saveBest();
                    if (r > 0.032f) {
                        float nr = r * 0.62f;
                        balls.add(new float[]{b[0] - nr * 0.5f, b[1], -Math.abs(b[2]) * 1.08f, -0.013f, nr, (int)b[5]});
                        balls.add(new float[]{b[0] + nr * 0.5f, b[1], Math.abs(b[2]) * 1.08f, -0.013f, nr, (int)b[5]});
                    }
                    continue;
                }
                if (Math.hypot(heroX - b[0], 0.76f - b[1]) < r + 0.028f) {
                    lives--;
                    RetroSound.boom();
                    if (lives <= 0) { gameOver = true; RetroSound.gameOver(); saveBest(); }
                    else { spawnPangLevel(); }
                    return;
                }
            }
            if (balls.isEmpty()) {
                level++;
                score += 500;
                saveBest();
                spawnPangLevel();
            }
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
            if (!any) { level++; score += 300; spawnBricks(); resetBall(); }
        }
    }

    private void drawBtn(Canvas c, RectF r, String txt, boolean dorado) {
        paint.setColor(dorado ? Color.rgb(212, 175, 55) : Color.rgb(18, 56, 108));
        c.drawRoundRect(r, 24, 24, paint);
        paint.setColor(dorado ? Color.rgb(8, 24, 48) : Color.rgb(212, 175, 55));
        paint.setTextSize(r.height() * 0.42f);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(txt, r.centerX(), r.centerY() + r.height() * 0.15f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(Color.rgb(6, 20, 44));
        paint.setColor(Color.rgb(12, 42, 82)); c.drawRect(0, 0, w, h * 0.10f, paint);
        btnMenu.set(w * 0.03f, h * 0.022f, w * 0.24f, h * 0.078f);
        drawBtn(c, btnMenu, "◀ Menú", false);

        paint.setColor(Color.rgb(212, 175, 55)); paint.setTextSize(w * 0.038f); paint.setFakeBoldText(true);
        c.drawText("PTS: " + score, w * 0.28f, h * 0.062f, paint);
        paint.setColor(Color.WHITE); c.drawText("RÉCORD: " + bestScore, w * 0.58f, h * 0.062f, paint);

        if (gameType == GAME_PANG) {
            // Marco y suelo arcade
            paint.setColor(Color.rgb(24, 78, 150));
            c.drawRect(w * 0.02f, h * 0.11f, w * 0.98f, h * 0.122f, paint);
            c.drawRect(0, h * 0.81f, w, h * 0.828f, paint);
            paint.setColor(Color.WHITE); paint.setTextSize(w * 0.034f);
            c.drawText("NIVEL " + level + "   ·   VIDAS: " + lives, w * 0.05f, h * 0.155f, paint);

            // Disparos verticales (arpones con punta dorada)
            for (float[] s : shots) {
                paint.setColor(Color.rgb(120, 225, 255));
                c.drawRect((s[0] - 0.006f) * w, s[1] * h, (s[0] + 0.006f) * w, h * 0.81f, paint);
                paint.setColor(Color.rgb(235, 195, 75));
                c.drawCircle(s[0] * w, s[1] * h, w * 0.016f, paint);
            }

            // Muñequito detallado (gorra roja, cara, camiseta azul, piernas animadas y lanzador)
            float hx = heroX * w, hy = h * 0.81f;
            float walk = (float) Math.sin(stepAnim) * w * 0.012f;
            paint.setColor(Color.rgb(40, 120, 230)); // piernas
            c.drawRoundRect(new RectF(hx - w * 0.024f, hy - h * 0.032f + walk, hx - w * 0.004f, hy), 6, 6, paint);
            c.drawRoundRect(new RectF(hx + w * 0.004f, hy - h * 0.032f - walk, hx + w * 0.024f, hy), 6, 6, paint);
            paint.setColor(Color.WHITE); // camiseta
            c.drawRoundRect(new RectF(hx - w * 0.028f, hy - h * 0.062f, hx + w * 0.028f, hy - h * 0.028f), 8, 8, paint);
            paint.setColor(Color.rgb(255, 210, 170)); // cabeza
            c.drawCircle(hx, hy - h * 0.076f, w * 0.024f, paint);
            paint.setColor(Color.rgb(235, 60, 75)); // gorra arcade
            c.drawArc(new RectF(hx - w * 0.026f, hy - h * 0.095f, hx + w * 0.026f, hy - h * 0.068f), 180, 180, true, paint);
            c.drawRect(hx, hy - h * 0.083f, hx + heroDir * w * 0.036f, hy - h * 0.077f, paint);

            // Bolas gigantes/medianas/pequeñas con brillo
            int[] bCols = {Color.rgb(255, 65, 85), Color.rgb(65, 225, 115), Color.rgb(75, 175, 255), Color.rgb(255, 195, 50)};
            for (float[] b : balls) {
                float bx = b[0] * w, by = b[1] * h, br = b[4] * w;
                paint.setColor(bCols[(int)b[5] % 4]);
                c.drawCircle(bx, by, br, paint);
                paint.setColor(Color.argb(140, 255, 255, 255));
                c.drawCircle(bx - br * 0.32f, by - br * 0.32f, br * 0.24f, paint);
            }

            // Controles grandes abajo
            bIzq.set(w * 0.04f, h * 0.85f, w * 0.28f, h * 0.95f);
            bDer.set(w * 0.31f, h * 0.85f, w * 0.55f, h * 0.95f);
            bDisp.set(w * 0.60f, h * 0.85f, w * 0.96f, h * 0.95f);
            drawBtn(c, bIzq, "◀", false);
            drawBtn(c, bDer, "▶", false);
            drawBtn(c, bDisp, "🔥 DISPARAR", true);
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
            paint.setTextSize(w * 0.038f); c.drawText("NVL " + level + " · VIDAS: " + lives, w * 0.06f, h * 0.94f, paint);
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
        if (gameType == GAME_PANG) {
            if (bIzq.contains(x, y)) { heroDir = -1; heroX = Math.max(0.06f, heroX - 0.022f); stepAnim += 0.6f; return true; }
            if (bDer.contains(x, y)) { heroDir = 1; heroX = Math.min(0.94f, heroX + 0.022f); stepAnim += 0.6f; return true; }
            if (e.getAction() == MotionEvent.ACTION_DOWN && bDisp.contains(x, y) && shots.size() < 2) {
                shots.add(new float[]{heroX, 0.72f});
                RetroSound.blip();
                return true;
            }
            return true;
        }
        if (gameType == GAME_ARKANOID) { padX = Math.max(0.15f, Math.min(0.85f, x / getWidth())); return true; }
        return true;
    }
}
