package com.kuli.arcade;
import android.app.Activity; import android.content.*; import android.graphics.*; import android.os.*; import android.view.*; import java.util.*;

public class GameView extends View {
    public static final int GAME_BUBBLE = 1, GAME_ARKANOID = 2, GAME_INVADERS = 3;
    private final int gameType;
    private final Paint paint = new Paint();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private int score = 0, bestScore = 0, level = 1, lives = 3;
    private boolean gameOver = false;
    private final RectF btnMenu = new RectF(), bIzq = new RectF(), bDer = new RectF(), bAcc1 = new RectF(), bAcc2 = new RectF();

    // SPRITES PIXEL ART AUTENTICOS (11x8 y 12x12)
    private final String[] SPR_BUB = {
        "...GGGG.....", "..GGGGGG....", ".OOGGWWGG...", ".OOGGWBGG...",
        "..GGGGGGGG..", ".OOGGGGGGG..", ".OOGGYYYYG..", "..GGGGGGGG..",
        "...GGGGGG...", "...GG..GG...", "..GGG..GGG.."
    };
    private final String[] SPR_ZEN = {
        "...RRRRRR...", "..RRRRRRRR..", ".RRYYRRYYRR.", ".RRYYRRYYRR.",
        ".RRRRRRRRRR.", "..RRRRRRRR..", "...RR..RR...", "..RRR..RRR.."
    };
    private final String[] SPR_ALIEN1 = {
        "..C.....C..", "...C...C...", "..CCCCCCC..", ".CC.CCC.CC.",
        "CCCCCCCCCCC", "C.CCCCCCC.C", "C.C.....C.C", "...CC.CC..."
    };
    private final String[] SPR_ALIEN2 = {
        "....MMM....", "...MMMMM...", "..MMMMMMM..", ".MM.MMM.MM.",
        "MMMMMMMMMMM", "..M.MMM.M..", ".M.......M.", "..M.....M.."
    };
    private final String[] SPR_SHIP = {
        ".....W.....", "....WWW....", "....WCW....", "..WWWWWWW..",
        ".WWWWWWWWW.", "WWWWWWWWWWW", "WW.WWWWW.WW", "W...WWW...W"
    };

    // BUBBLE BOBBLE
    private float bubX = 0.22f, bubY = 0.78f, bubVY = 0f;
    private int bubDir = 1;
    private final ArrayList<float[]> enemies = new ArrayList<>();
    private final ArrayList<float[]> bubbles = new ArrayList<>();
    private final ArrayList<float[]> fruits = new ArrayList<>();

    // SPACE INVADERS (MARCIANITOS)
    private float shipX = 0.5f, invDir = 0.004f;
    private final ArrayList<float[]> aliens = new ArrayList<>(); // x, y, tipo, vivo(1/0)
    private final ArrayList<float[]> myShots = new ArrayList<>();
    private final ArrayList<float[]> alienShots = new ArrayList<>();
    private int[] shields = new int[4];

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
        if (gameType == GAME_BUBBLE) spawnBubbleStage();
        else if (gameType == GAME_INVADERS) spawnInvadersStage();
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
    private void spawnInvadersStage() {
        shipX = 0.5f; invDir = 0.0032f + level * 0.0006f;
        aliens.clear(); myShots.clear(); alienShots.clear();
        shields = new int[]{4, 4, 4, 4};
        for (int r = 0; r < 4; r++) for (int c = 0; c < 7; c++) {
            aliens.add(new float[]{0.14f + c * 0.115f, 0.18f + r * 0.068f, r % 2, 1f});
        }
    }
    private void spawnBricks() {
        for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) bricks[r][c] = (r < 2) ? 2 : 1;
    }
    private void resetBall() { aBallX = padX; aBallY = 0.78f; aVX = (rnd.nextBoolean() ? 1 : -1) * 0.008f; aVY = -0.011f; }

    private void update() {
        if (gameOver) return;
        if (gameType == GAME_BUBBLE) {
            float prevY = bubY; bubVY += 0.0014f; bubY += bubVY;
            float[] plats = {0.30f, 0.46f, 0.62f, 0.78f};
            for (float py : plats) {
                boolean hueco = (py < 0.75f && (bubX < 0.14f || bubX > 0.86f || (bubX > 0.46f && bubX < 0.54f)));
                if (!hueco && bubVY >= 0 && prevY <= py && bubY >= py) { bubY = py; bubVY = 0f; }
            }
            if (bubY > 0.78f) { bubY = 0.78f; bubVY = 0f; }
            for (int i = bubbles.size() - 1; i >= 0; i--) {
                float[] b = bubbles.get(i); b[0] += b[2]; b[2] *= 0.91f;
                if (Math.abs(b[2]) < 0.002f) b[1] -= 0.0038f;
                if (b[0] < 0.08f) b[0] = 0.08f; if (b[0] > 0.92f) b[0] = 0.92f; if (b[1] < 0.16f) b[1] = 0.16f;
                b[5] += 1f;
                if (b[5] > 480f) { if (b[4] > 0) enemies.add(new float[]{b[0], b[1], 0.005f, 0f, 1}); bubbles.remove(i); }
                else if (Math.hypot(bubX - b[0], (bubY - 0.02f) - b[1]) < 0.055f && b[5] > 14f) {
                    if (b[4] > 0) { score += 200; fruits.add(new float[]{b[0], b[1], rnd.nextInt(3), 0f}); } else score += 20;
                    RetroSound.score(); saveBest(); bubbles.remove(i);
                }
            }
            for (int i = enemies.size() - 1; i >= 0; i--) {
                float[] en = enemies.get(i); en[0] += en[2];
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
                float[] fr = fruits.get(i); if (fr[1] < 0.78f) fr[1] += 0.006f; fr[3] += 1f;
                if (Math.hypot(bubX - fr[0], bubY - fr[1]) < 0.055f) { score += 500; RetroSound.score(); saveBest(); fruits.remove(i); }
                else if (fr[3] > 360f) fruits.remove(i);
            }
            boolean q = false; for (float[] b : bubbles) if (b[4] > 0) q = true;
            if (enemies.isEmpty() && !q && fruits.isEmpty()) { level++; score += 500; saveBest(); spawnBubbleStage(); }
        } else if (gameType == GAME_INVADERS) {
            boolean edge = false; int vivos = 0;
            for (float[] a : aliens) {
                if (a[3] == 0) continue;
                vivos++; a[0] += invDir;
                if (a[0] < 0.08f || a[0] > 0.92f) edge = true;
                if (a[1] > 0.68f) { gameOver = true; RetroSound.gameOver(); saveBest(); }
            }
            if (edge) { invDir = -invDir; for (float[] a : aliens) a[1] += 0.022f; }
            if (vivos > 0 && rnd.nextInt(Math.max(18, 52 - level * 4)) == 0) {
                float[] shooter = aliens.get(rnd.nextInt(aliens.size()));
                if (shooter[3] > 0) alienShots.add(new float[]{shooter[0], shooter[1] + 0.02f});
            }
            for (int i = myShots.size() - 1; i >= 0; i--) {
                float[] s = myShots.get(i); s[1] -= 0.018f;
                if (s[1] < 0.12f) { myShots.remove(i); continue; }
                for (float[] a : aliens) {
                    if (a[3] > 0 && Math.abs(s[0] - a[0]) < 0.042f && Math.abs(s[1] - a[1]) < 0.032f) {
                        a[3] = 0; myShots.remove(i); score += 100; RetroSound.score(); saveBest(); break;
                    }
                }
            }
            for (int i = alienShots.size() - 1; i >= 0; i--) {
                float[] s = alienShots.get(i); s[1] += 0.011f;
                if (s[1] > 0.82f) { alienShots.remove(i); continue; }
                boolean hitShield = false;
                for (int sh = 0; sh < 4; sh++) {
                    float sx = 0.18f + sh * 0.21f;
                    if (shields[sh] > 0 && Math.abs(s[0] - sx) < 0.055f && Math.abs(s[1] - 0.70f) < 0.025f) {
                        shields[sh]--; alienShots.remove(i); RetroSound.blip(); hitShield = true; break;
                    }
                }
                if (hitShield) continue;
                if (Math.abs(s[0] - shipX) < 0.048f && Math.abs(s[1] - 0.78f) < 0.030f) {
                    alienShots.remove(i); lives--; RetroSound.boom();
                    if (lives <= 0) { gameOver = true; RetroSound.gameOver(); saveBest(); }
                }
            }
            if (vivos == 0) { level++; score += 500; saveBest(); spawnInvadersStage(); }
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

    private void drawSprite(Canvas c, String[] spr, float cx, float cy, float pxSize, boolean flipX) {
        int rows = spr.length, cols = spr[0].length();
        float x0 = cx - (cols * pxSize) / 2f, y0 = cy - (rows * pxSize) / 2f;
        for (int r = 0; r < rows; r++) for (int col = 0; col < cols; col++) {
            char ch = spr[r].charAt(flipX ? (cols - 1 - col) : col);
            if (ch == '.') continue;
            if (ch == 'G') paint.setColor(Color.rgb(55, 225, 95));
            else if (ch == 'O') paint.setColor(Color.rgb(255, 135, 30));
            else if (ch == 'W') paint.setColor(Color.WHITE);
            else if (ch == 'B') paint.setColor(Color.BLACK);
            else if (ch == 'Y') paint.setColor(Color.rgb(255, 225, 65));
            else if (ch == 'R') paint.setColor(Color.rgb(255, 60, 80));
            else if (ch == 'C') paint.setColor(Color.rgb(70, 235, 255));
            else if (ch == 'M') paint.setColor(Color.rgb(215, 85, 255));
            c.drawRect(x0 + col * pxSize, y0 + r * pxSize, x0 + (col + 1) * pxSize, y0 + (r + 1) * pxSize, paint);
        }
    }
    private void drawBtn(Canvas c, RectF r, String txt, boolean dorado) {
        paint.setColor(dorado ? Color.rgb(212, 175, 55) : Color.rgb(18, 56, 108));
        c.drawRoundRect(r, 22, 22, paint);
        paint.setColor(dorado ? Color.rgb(8, 24, 48) : Color.rgb(212, 175, 55));
        paint.setTextSize(r.height() * 0.42f); paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(txt, r.centerX(), r.centerY() + r.height() * 0.15f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }
    private void drawBrickRow(Canvas c, float x1, float y1, float x2, float y2, int col1, int col2) {
        paint.setColor(col1); c.drawRect(x1, y1, x2, y2, paint); paint.setColor(col2);
        float step = Math.max(18f, (x2 - x1) / 14f);
        for (float x = x1; x < x2 - 4; x += step) c.drawRect(x + 2, y1 + 2, Math.min(x2 - 2, x + step - 3), y2 - 2, paint);
    }
    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(Color.rgb(5, 10, 22));
        paint.setColor(Color.rgb(12, 34, 70)); c.drawRect(0, 0, w, h * 0.10f, paint);
        btnMenu.set(w * 0.03f, h * 0.022f, w * 0.24f, h * 0.078f); drawBtn(c, btnMenu, "◀ Menú", false);
        paint.setColor(Color.rgb(65, 235, 110)); paint.setTextSize(w * 0.036f); paint.setFakeBoldText(true);
        c.drawText("1UP: " + score, w * 0.28f, h * 0.062f, paint);
        paint.setColor(Color.rgb(255, 80, 90)); c.drawText("HIGH: " + bestScore, w * 0.60f, h * 0.062f, paint);
        if (gameType == GAME_BUBBLE) {
            drawBrickRow(c, 0, h * 0.11f, w, h * 0.135f, Color.rgb(160, 75, 20), Color.rgb(235, 165, 45));
            paint.setColor(Color.rgb(215, 135, 35)); c.drawRect(0, h * 0.11f, w * 0.045f, h * 0.805f, paint); c.drawRect(w * 0.955f, h * 0.11f, w, h * 0.805f, paint);
            float[] plats = {0.30f, 0.46f, 0.62f};
            for (float py : plats) {
                drawBrickRow(c, w * 0.14f, h * py, w * 0.46f, h * (py + 0.022f), Color.rgb(150, 65, 18), Color.rgb(230, 155, 40));
                drawBrickRow(c, w * 0.54f, h * py, w * 0.86f, h * (py + 0.022f), Color.rgb(150, 65, 18), Color.rgb(230, 155, 40));
            }
            drawBrickRow(c, 0, h * 0.78f, w, h * 0.808f, Color.rgb(150, 65, 18), Color.rgb(230, 155, 40));
            paint.setColor(Color.WHITE); paint.setTextSize(w * 0.032f); c.drawText("ROUND " + level + "  ·  VIDAS: " + lives, w * 0.07f, h * 0.165f, paint);
            float pxSz = w * 0.0068f;
            drawSprite(c, SPR_BUB, bubX * w, bubY * h - pxSz * 5.2f, pxSz, bubDir < 0);
            for (float[] en : enemies) drawSprite(c, SPR_ZEN, en[0] * w, en[1] * h - pxSz * 3.8f, pxSz, en[2] < 0);
            for (float[] b : bubbles) {
                float px = b[0] * w, py = b[1] * h, pr = w * 0.040f;
                if (b[4] > 0) drawSprite(c, SPR_ZEN, px, py, pxSz * 0.7f, false);
                paint.setColor(Color.argb(145, 70, 245, 140)); c.drawCircle(px, py, pr, paint);
            }
            for (float[] fr : fruits) { paint.setColor(fr[2] == 0 ? Color.YELLOW : Color.RED); c.drawCircle(fr[0] * w, fr[1] * h - w * 0.02f, w * 0.024f, paint); }
            bIzq.set(w*0.04f, h*0.85f, w*0.25f, h*0.95f); bDer.set(w*0.28f, h*0.85f, w*0.49f, h*0.95f);
            bAcc1.set(w*0.52f, h*0.85f, w*0.73f, h*0.95f); bAcc2.set(w*0.76f, h*0.85f, w*0.96f, h*0.95f);
            drawBtn(c, bIzq, "◀", false); drawBtn(c, bDer, "▶", false); drawBtn(c, bAcc1, "▲", false); drawBtn(c, bAcc2, "🫧", true);
        }

        else if (gameType == GAME_INVADERS) {
            paint.setColor(Color.WHITE); paint.setTextSize(w * 0.032f);
            c.drawText("OLEADA " + level + "   ·   NAVES: " + lives, w * 0.05f, h * 0.135f, paint);
            float pxSz = w * 0.0068f;
            for (float[] a : aliens) {
                if (a[3] == 0) continue;
                drawSprite(c, a[2] == 0 ? SPR_ALIEN1 : SPR_ALIEN2, a[0] * w, a[1] * h, pxSz, false);
            }
            for (int sh = 0; sh < 4; sh++) {
                if (shields[sh] <= 0) continue;
                paint.setColor(Color.rgb(55, 225, 110));
                float sx = (0.18f + sh * 0.21f) * w, sy = 0.70f * h;
                c.drawRoundRect(new RectF(sx - w * 0.055f, sy - h * 0.015f, sx + w * 0.055f, sy + h * 0.015f), 10, 10, paint);
            }
            drawSprite(c, SPR_SHIP, shipX * w, 0.78f * h, pxSz * 1.15f, false);
            paint.setColor(Color.rgb(255, 230, 80));
            for (float[] s : myShots) c.drawRect(s[0] * w - 4, s[1] * h - 18, s[0] * w + 4, s[1] * h, paint);
            paint.setColor(Color.rgb(255, 75, 95));
            for (float[] s : alienShots) c.drawRect(s[0] * w - 4, s[1] * h, s[0] * w + 4, s[1] * h + 18, paint);
            bIzq.set(w*0.04f, h*0.85f, w*0.28f, h*0.95f); bDer.set(w*0.31f, h*0.85f, w*0.55f, h*0.95f); bAcc2.set(w*0.60f, h*0.85f, w*0.96f, h*0.95f);
            drawBtn(c, bIzq, "◀", false); drawBtn(c, bDer, "▶", false); drawBtn(c, bAcc2, "🚀 FUEGO", true);
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
            if (e.getAction() == MotionEvent.ACTION_DOWN && bAcc1.contains(x, y) && bubVY == 0f) { bubVY = -0.027f; RetroSound.blip(); return true; }
            if (e.getAction() == MotionEvent.ACTION_DOWN && bAcc2.contains(x, y)) {
                bubbles.add(new float[]{bubX + bubDir * 0.045f, bubY - 0.025f, bubDir * 0.030f, 0f, 0f, 0f}); RetroSound.blip(); return true;
            }
            return true;
        }
        if (gameType == GAME_INVADERS) {
            if (bIzq.contains(x, y)) { shipX = Math.max(0.08f, shipX - 0.025f); return true; }
            if (bDer.contains(x, y)) { shipX = Math.min(0.92f, shipX + 0.025f); return true; }
            if (e.getAction() == MotionEvent.ACTION_DOWN && bAcc2.contains(x, y) && myShots.size() < 3) { myShots.add(new float[]{shipX, 0.74f}); RetroSound.blip(); return true; }
            if (y < getHeight() * 0.82f) shipX = Math.max(0.08f, Math.min(0.92f, x / getWidth()));
            return true;
        }
        if (gameType == GAME_ARKANOID) { padX = Math.max(0.15f, Math.min(0.85f, x / getWidth())); return true; }
        return true;
    }
}
