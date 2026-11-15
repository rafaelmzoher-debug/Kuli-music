package com.kuli.arcade;
import android.app.Activity; import android.content.*; import android.graphics.*; import android.os.*; import android.view.*; import java.util.*;

public class GameView extends View {
    public static final int GAME_BUBBLE = 1, GAME_ARKANOID = 2;
    private final int gameType;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private int score = 0, bestScore = 0, level = 1, lives = 3;
    private boolean gameOver = false;
    private final RectF btnMenu = new RectF(), bIzq = new RectF(), bDer = new RectF(), bSalto = new RectF(), bPompa = new RectF();

    // BUBBLE BOBBLE 1986 (TAITO STYLE)
    private float bubX = 0.22f, bubY = 0.78f, bubVY = 0f, walkAnim = 0f;
    private int bubDir = 1;
    // Enemigo: x, y, vx, vy, tipo
    private final ArrayList<float[]> enemies = new ArrayList<>();
    // Pompa: x, y, vx, vy, atrapado(0/1), timer
    private final ArrayList<float[]> bubbles = new ArrayList<>();
    // Fruta bonus: x, y, tipo(0=platano, 1=cereza, 2=sandia), timer
    private final ArrayList<float[]> fruits = new ArrayList<>();

    // ARKANOID RETRO
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
        if (gameType == GAME_BUBBLE) spawnBubbleStage();
        else { padX = 0.5f; spawnBricks(); resetBall(); }
    }
    private void spawnBubbleStage() {
        bubX = 0.22f; bubY = 0.78f; bubVY = 0f; bubDir = 1;
        enemies.clear(); bubbles.clear(); fruits.clear();
        int n = Math.min(6, 3 + (level - 1));
        for (int i = 0; i < n; i++) {
            float py = (i % 3 == 0) ? 0.30f : ((i % 3 == 1) ? 0.46f : 0.62f);
            float px = 0.26f + (i * 0.14f) % 0.52f;
            enemies.add(new float[]{px, py, (i % 2 == 0 ? 1 : -1) * (0.0038f + level * 0.0005f), 0f, i % 2});
        }
    }
    private void spawnBricks() {
        for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) bricks[r][c] = (r < 2) ? 2 : 1;
    }
    private void resetBall() { aBallX = padX; aBallY = 0.78f; aVX = (rnd.nextBoolean() ? 1 : -1) * 0.008f; aVY = -0.011f; }

    private void update() {
        if (gameOver) return;
        if (gameType == GAME_BUBBLE) {
            float prevY = bubY;
            bubVY += 0.0014f;
            bubY += bubVY;
            float[] plats = {0.30f, 0.46f, 0.62f, 0.78f};
            for (float py : plats) {
                boolean hueco = (py < 0.75f && (bubX < 0.14f || bubX > 0.86f || (bubX > 0.46f && bubX < 0.54f)));
                if (!hueco && bubVY >= 0 && prevY <= py && bubY >= py) { bubY = py; bubVY = 0f; }
            }
            if (bubY > 0.78f) { bubY = 0.78f; bubVY = 0f; }

            for (int i = bubbles.size() - 1; i >= 0; i--) {
                float[] b = bubbles.get(i);
                b[0] += b[2];
                b[2] *= 0.91f;
                if (Math.abs(b[2]) < 0.002f) b[1] -= 0.0038f;
                if (b[0] < 0.08f) b[0] = 0.08f;
                if (b[0] > 0.92f) b[0] = 0.92f;
                if (b[1] < 0.16f) b[1] = 0.16f;
                b[5] += 1f;
                if (b[5] > 480f) {
                    if (b[4] > 0) enemies.add(new float[]{b[0], b[1], 0.005f, 0f, 1});
                    bubbles.remove(i);
                } else if (Math.hypot(bubX - b[0], (bubY - 0.02f) - b[1]) < 0.055f && b[5] > 14f) {
                    if (b[4] > 0) {
                        score += 200;
                        fruits.add(new float[]{b[0], b[1], rnd.nextInt(3), 0f});
                    } else {
                        score += 20;
                    }
                    RetroSound.score();
                    saveBest();
                    bubbles.remove(i);
                }
            }

            for (int i = enemies.size() - 1; i >= 0; i--) {
                float[] en = enemies.get(i);
                en[0] += en[2];
                if (en[0] < 0.09f || en[0] > 0.91f) en[2] = -en[2];
                boolean atrap = false;
                for (float[] b : bubbles) {
                    if (b[4] == 0 && Math.abs(b[2]) > 0.002f && Math.hypot(en[0] - b[0], (en[1] - 0.02f) - b[1]) < 0.06f) {
                        b[4] = 1f; b[2] = 0f; b[5] = 0f; atrap = true; RetroSound.blip(); break;
                    }
                }
                if (atrap) { enemies.remove(i); continue; }
                if (Math.hypot(bubX - en[0], bubY - en[1]) < 0.048f) {
                    lives--; RetroSound.boom(); bubX = 0.22f; bubY = 0.78f;
                    if (lives <= 0) { gameOver = true; RetroSound.gameOver(); saveBest(); }
                }
            }

            for (int i = fruits.size() - 1; i >= 0; i--) {
                float[] fr = fruits.get(i);
                if (fr[1] < 0.78f) fr[1] += 0.006f;
                fr[3] += 1f;
                if (Math.hypot(bubX - fr[0], bubY - fr[1]) < 0.055f) {
                    score += 500; RetroSound.score(); saveBest(); fruits.remove(i);
                } else if (fr[3] > 360f) {
                    fruits.remove(i);
                }
            }

            boolean quedanEnPompa = false;
            for (float[] b : bubbles) if (b[4] > 0) quedanEnPompa = true;
            if (enemies.isEmpty() && !quedanEnPompa && fruits.isEmpty()) {
                level++; score += 500; saveBest(); spawnBubbleStage();
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
        c.drawRoundRect(r, 22, 22, paint);
        paint.setColor(dorado ? Color.rgb(8, 24, 48) : Color.rgb(212, 175, 55));
        paint.setTextSize(r.height() * 0.42f);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(txt, r.centerX(), r.centerY() + r.height() * 0.15f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBrickRow(Canvas c, float x1, float y1, float x2, float y2, int col1, int col2) {
        paint.setColor(col1);
        c.drawRect(x1, y1, x2, y2, paint);
        paint.setColor(col2);
        float step = Math.max(18f, (x2 - x1) / 14f);
        for (float x = x1; x < x2 - 4; x += step) {
            c.drawRect(x + 2, y1 + 2, Math.min(x2 - 2, x + step - 3), y2 - 2, paint);
        }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(Color.rgb(5, 10, 22)); // Fondo negro/azul oscuro arcade 1986
        paint.setColor(Color.rgb(12, 34, 70)); c.drawRect(0, 0, w, h * 0.10f, paint);
        btnMenu.set(w * 0.03f, h * 0.022f, w * 0.24f, h * 0.078f);
        drawBtn(c, btnMenu, "◀ Menú", false);

        paint.setColor(Color.rgb(65, 235, 110)); paint.setTextSize(w * 0.036f); paint.setFakeBoldText(true);
        c.drawText("1UP: " + score, w * 0.28f, h * 0.062f, paint);
        paint.setColor(Color.rgb(255, 80, 90)); c.drawText("HIGH: " + bestScore, w * 0.60f, h * 0.062f, paint);

        if (gameType == GAME_BUBBLE) {
            // Muros laterales y techo de ladrillos dorados/rojos estilo Taito
            drawBrickRow(c, 0, h * 0.11f, w, h * 0.135f, Color.rgb(160, 75, 20), Color.rgb(235, 165, 45));
            paint.setColor(Color.rgb(215, 135, 35));
            c.drawRect(0, h * 0.11f, w * 0.045f, h * 0.805f, paint);
            c.drawRect(w * 0.955f, h * 0.11f, w, h * 0.805f, paint);

            // Plataformas de ladrillo con huecos laterales y central
            float[] plats = {0.30f, 0.46f, 0.62f};
            for (float py : plats) {
                drawBrickRow(c, w * 0.14f, h * py, w * 0.46f, h * (py + 0.022f), Color.rgb(150, 65, 18), Color.rgb(230, 155, 40));
                drawBrickRow(c, w * 0.54f, h * py, w * 0.86f, h * (py + 0.022f), Color.rgb(150, 65, 18), Color.rgb(230, 155, 40));
            }
            drawBrickRow(c, 0, h * 0.78f, w, h * 0.808f, Color.rgb(150, 65, 18), Color.rgb(230, 155, 40));

            paint.setColor(Color.WHITE); paint.setTextSize(w * 0.032f);
            c.drawText("ROUND " + level + "   ·   VIDAS: " + lives, w * 0.07f, h * 0.165f, paint);

            // Dragoncito Bub verde con cresta naranja y ojos blancos
            float bx = bubX * w, by = bubY * h, rBub = w * 0.034f;
            paint.setColor(Color.rgb(255, 130, 30)); // cresta dorsal naranja
            c.drawCircle(bx - bubDir * rBub * 0.75f, by - rBub * 1.15f, rBub * 0.34f, paint);
            c.drawCircle(bx - bubDir * rBub * 0.85f, by - rBub * 0.65f, rBub * 0.34f, paint);
            paint.setColor(Color.rgb(55, 225, 95)); // cuerpo verde Bub
            c.drawCircle(bx, by - rBub * 0.85f, rBub, paint);
            paint.setColor(Color.rgb(245, 245, 210)); // barriguita clara
            c.drawCircle(bx + bubDir * rBub * 0.25f, by - rBub * 0.65f, rBub * 0.55f, paint);
            paint.setColor(Color.WHITE); // ojo grande
            c.drawCircle(bx + bubDir * rBub * 0.42f, by - rBub * 1.05f, rBub * 0.30f, paint);
            paint.setColor(Color.BLACK);
            c.drawCircle(bx + bubDir * rBub * 0.50f, by - rBub * 1.05f, rBub * 0.14f, paint);

            // Enemigos Zen-Chan (rojo/naranja)
            for (float[] en : enemies) {
                float ex = en[0] * w, ey = en[1] * h;
                paint.setColor(en[4] == 0 ? Color.rgb(255, 65, 80) : Color.rgb(190, 80, 255));
                c.drawRoundRect(new RectF(ex - rBub * 0.9f, ey - rBub * 1.7f, ex + rBub * 0.9f, ey), 14, 14, paint);
                paint.setColor(Color.YELLOW);
                c.drawCircle(ex - rBub * 0.3f, ey - rBub * 1.1f, rBub * 0.2f, paint);
                c.drawCircle(ex + rBub * 0.3f, ey - rBub * 1.1f, rBub * 0.2f, paint);
            }

            // Pompas verdes luminosas (y con enemigo dentro si atrapado > 0)
            for (float[] b : bubbles) {
                float px = b[0] * w, py = b[1] * h, pr = w * 0.040f;
                if (b[4] > 0) {
                    paint.setColor(Color.rgb(255, 70, 90));
                    c.drawCircle(px, py, pr * 0.55f, paint);
                }
                paint.setColor(Color.argb(145, 70, 245, 140));
                c.drawCircle(px, py, pr, paint);
                paint.setColor(Color.argb(190, 220, 255, 230));
                c.drawCircle(px - pr * 0.32f, py - pr * 0.32f, pr * 0.22f, paint);
            }

            // Frutas bonus (platano amarillo, cereza roja, sandia)
            for (float[] fr : fruits) {
                float fx = fr[0] * w, fy = fr[1] * h - w * 0.02f;
                paint.setColor(fr[2] == 0 ? Color.YELLOW : (fr[2] == 1 ? Color.RED : Color.GREEN));
                c.drawCircle(fx, fy, w * 0.024f, paint);
            }

            bIzq.set(w*0.04f, h*0.85f, w*0.25f, h*0.95f); bDer.set(w*0.28f, h*0.85f, w*0.49f, h*0.95f);
            bSalto.set(w*0.52f, h*0.85f, w*0.73f, h*0.95f); bPompa.set(w*0.76f, h*0.85f, w*0.96f, h*0.95f);
            drawBtn(c, bIzq, "◀", false); drawBtn(c, bDer, "▶", false);
            drawBtn(c, bSalto, "▲", false); drawBtn(c, bPompa, "🫧", true);
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
            c.drawText("GAME OVER", w * 0.5f, h * 0.47f, paint);
            paint.setColor(Color.WHITE); paint.setTextSize(w * 0.045f);
            c.drawText("PUNTOS: " + score + "  ·  TOCA PARA REPETIR", w * 0.5f, h * 0.56f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getAction() == MotionEvent.ACTION_DOWN && btnMenu.contains(x, y)) { ((Activity) getContext()).finish(); return true; }
        if (gameOver && e.getAction() == MotionEvent.ACTION_DOWN) { initGame(); return true; }
        if (gameType == GAME_BUBBLE && (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE)) {
            if (bIzq.contains(x, y)) { bubDir = -1; bubX = Math.max(0.08f, bubX - 0.024f); return true; }
            if (bDer.contains(x, y)) { bubDir = 1; bubX = Math.min(0.92f, bubX + 0.024f); return true; }
            if (e.getAction() == MotionEvent.ACTION_DOWN && bSalto.contains(x, y) && bubVY == 0f) { bubVY = -0.027f; RetroSound.blip(); return true; }
            if (e.getAction() == MotionEvent.ACTION_DOWN && bPompa.contains(x, y)) {
                bubbles.add(new float[]{bubX + bubDir * 0.045f, bubY - 0.025f, bubDir * 0.030f, 0f, 0f, 0f});
                RetroSound.blip();
                return true;
            }
            return true;
        }
        if (gameType == GAME_ARKANOID) { padX = Math.max(0.15f, Math.min(0.85f, x / getWidth())); return true; }
        return true;
    }
}
