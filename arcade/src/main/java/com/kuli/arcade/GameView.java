package com.kuli.arcade;
import android.content.*; import android.graphics.*; import android.os.*; import android.view.*; import java.util.*;

public class GameView extends View {
    public static final int GAME_TETRIS = 0, GAME_BOBBLE = 1, GAME_ARKANOID = 2, GAME_PACMAN = 3;
    private final int gameType;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private int score = 0, bestScore = 0;
    private boolean gameOver = false;
    private float touchStartX, touchStartY;

    private final int TW = 10, TH = 20;
    private int[][] tGrid = new int[TH][TW];
    private int[][] curPiece;
    private int curColor, curX, curY, nextIdx;
    private long lastDrop = 0;
    private final int[][][] SHAPES = {{{1,1,1,1}},{{1,1},{1,1}},{{0,1,0},{1,1,1}},{{1,0,0},{1,1,1}},{{0,0,1},{1,1,1}},{{0,1,1},{1,1,0}},{{1,1,0},{0,1,1}}};
    private final int[] TCOLORS = {Color.CYAN, Color.YELLOW, Color.MAGENTA, Color.rgb(255,140,0), Color.rgb(70,130,255), Color.GREEN, Color.RED};

    private final int BCOLS = 9, BROWS = 13;
    private int[][] bGrid = new int[BROWS][BCOLS];
    private final int[] BCOLORS = {Color.RED, Color.CYAN, Color.YELLOW, Color.GREEN, Color.MAGENTA};
    private float aimAngle = (float)(-Math.PI/2), ballX, ballY, ballVX, ballVY;
    private boolean ballMoving = false;
    private int curBallColor = 1, nextBallColor = 2, shotsCount = 0;

    private float padX = 0.5f, aBallX = 0.5f, aBallY = 0.7f, aVX = 0.008f, aVY = -0.011f;
    private int lives = 3;
    private int[][] bricks = new int[6][8];

    private final int PW = 15, PH = 17;
    private int[][] pMap = new int[PH][PW];
    private int pacX = 7, pacY = 14, pacDir = 0, nextPacDir = 0, powerTimer = 0;
    private int[] gX = {6,7,8,7}, gY = {8,8,8,7}, gDir = {0,2,1,3};
    private long lastPacTick = 0;

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
        if (gameType == GAME_TETRIS) { tGrid = new int[TH][TW]; nextIdx = rnd.nextInt(SHAPES.length); spawnTetris(); }
        else if (gameType == GAME_BOBBLE) {
            bGrid = new int[BROWS][BCOLS];
            for (int r = 0; r < 5; r++) for (int c = 0; c < BCOLS; c++) bGrid[r][c] = rnd.nextInt(BCOLORS.length) + 1;
            curBallColor = rnd.nextInt(BCOLORS.length) + 1; nextBallColor = rnd.nextInt(BCOLORS.length) + 1; ballMoving = false; shotsCount = 0;
        } else if (gameType == GAME_ARKANOID) {
            lives = 3; padX = 0.5f; resetArkanoidBall();
            for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) bricks[r][c] = (r < 2) ? 2 : 1;
        } else { initPacman(); }
    }
    private void spawnTetris() {
        int idx = nextIdx; nextIdx = rnd.nextInt(SHAPES.length); curPiece = SHAPES[idx]; curColor = TCOLORS[idx];
        curX = TW / 2 - curPiece[0].length / 2; curY = 0;
        if (!canMoveTetris(curPiece, curX, curY)) { gameOver = true; RetroSound.gameOver(); saveBest(); }
    }
    private boolean canMoveTetris(int[][] p, int nx, int ny) {
        for (int r = 0; r < p.length; r++) for (int c = 0; c < p[r].length; c++) {
            if (p[r][c] == 0) continue;
            int gx = nx + c, gy = ny + r;
            if (gx < 0 || gx >= TW || gy >= TH || (gy >= 0 && tGrid[gy][gx] != 0)) return false;
        }
        return true;
    }
    private void rotateTetris() {
        int rows = curPiece.length, cols = curPiece[0].length; int[][] rot = new int[cols][rows];
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) rot[c][rows - 1 - r] = curPiece[r][c];
        if (canMoveTetris(rot, curX, curY)) { curPiece = rot; RetroSound.blip(); }
    }
    private void lockTetris() {
        for (int r = 0; r < curPiece.length; r++) for (int c = 0; c < curPiece[r].length; c++)
            if (curPiece[r][c] != 0 && curY + r >= 0) tGrid[curY + r][curX + c] = curColor;
        int cleared = 0;
        for (int r = TH - 1; r >= 0; r--) {
            boolean full = true; for (int c = 0; c < TW; c++) if (tGrid[r][c] == 0) full = false;
            if (full) { cleared++; for (int rr = r; rr > 0; rr--) tGrid[rr] = tGrid[rr - 1].clone(); tGrid[0] = new int[TW]; r++; }
        }
        if (cleared > 0) { score += cleared * cleared * 100; RetroSound.score(); saveBest(); } else RetroSound.blip();
        spawnTetris();
    }
    private void popBobbleCluster(int sr, int sc, int color) {
        boolean[][] vis = new boolean[BROWS][BCOLS]; ArrayList<int[]> q = new ArrayList<>();
        q.add(new int[]{sr, sc}); vis[sr][sc] = true; int head = 0;
        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1},{-1,-1},{-1,1},{1,-1},{1,1}};
        while (head < q.size()) {
            int[] cur = q.get(head++);
            for (int[] d : dirs) {
                int nr = cur[0] + d[0], nc = cur[1] + d[1];
                if (nr >= 0 && nr < BROWS && nc >= 0 && nc < BCOLS && !vis[nr][nc] && bGrid[nr][nc] == color) { vis[nr][nc] = true; q.add(new int[]{nr, nc}); }
            }
        }
        if (q.size() >= 3) { for (int[] cell : q) bGrid[cell[0]][cell[1]] = 0; score += q.size() * 30; RetroSound.score(); saveBest(); }
        else RetroSound.blip();
    }
    private void resetArkanoidBall() { aBallX = padX; aBallY = 0.78f; aVX = (rnd.nextBoolean() ? 1 : -1) * 0.008f; aVY = -0.011f; }
    private void initPacman() {
        int[][] tpl = {
            {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1}, {1,3,2,2,2,2,2,1,2,2,2,2,2,3,1}, {1,2,1,1,2,1,2,1,2,1,2,1,1,2,1},
            {1,2,2,2,2,2,2,2,2,2,2,2,2,2,1}, {1,2,1,1,2,1,1,1,1,1,2,1,1,2,1}, {1,2,2,2,2,2,2,1,2,2,2,2,2,2,1},
            {1,1,1,1,2,1,0,0,0,1,2,1,1,1,1}, {0,0,0,1,2,1,0,0,0,1,2,1,0,0,0}, {1,1,1,1,2,1,0,0,0,1,2,1,1,1,1},
            {1,2,2,2,2,2,2,2,2,2,2,2,2,2,1}, {1,2,1,1,2,1,1,1,1,1,2,1,1,2,1}, {1,2,2,1,2,2,2,1,2,2,2,1,2,2,1},
            {1,1,2,1,2,1,2,1,2,1,2,1,2,1,1}, {1,2,2,2,2,1,2,2,2,1,2,2,2,2,1}, {1,2,1,1,1,1,2,0,2,1,1,1,1,2,1},
            {1,3,2,2,2,2,2,2,2,2,2,2,2,3,1}, {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1}
        };
        for (int r = 0; r < PH; r++) pMap[r] = tpl[r].clone();
        pacX = 7; pacY = 14; pacDir = 0; nextPacDir = 0; powerTimer = 0;
        gX = new int[]{6, 7, 8, 7}; gY = new int[]{8, 8, 8, 7};
    }

    private void update() {
        if (gameOver) return;
        long now = System.currentTimeMillis();
        if (gameType == GAME_TETRIS) {
            if (now - lastDrop > Math.max(140, 520 - (score / 400) * 35)) {
                lastDrop = now;
                if (canMoveTetris(curPiece, curX, curY + 1)) curY++; else lockTetris();
            }
        } else if (gameType == GAME_BOBBLE && ballMoving) {
            ballX += ballVX; ballY += ballVY;
            if (ballX <= 0.06f) { ballX = 0.06f; ballVX = -ballVX; RetroSound.blip(); }
            if (ballX >= 0.94f) { ballX = 0.94f; ballVX = -ballVX; RetroSound.blip(); }
            int hitR = -1, hitC = -1;
            if (ballY <= 0.14f) { hitR = 0; hitC = Math.max(0, Math.min(BCOLS - 1, (int)(ballX * BCOLS))); }
            else {
                for (int r = 0; r < BROWS; r++) {
                    for (int c = 0; c < BCOLS; c++) {
                        if (bGrid[r][c] == 0) continue;
                        if (Math.hypot(ballX - (c + 0.5f) / BCOLS, ballY - (0.14f + r * 0.052f)) < 0.075f) {
                            hitR = Math.min(BROWS - 1, r + 1); hitC = Math.max(0, Math.min(BCOLS - 1, (int)(ballX * BCOLS))); break;
                        }
                    }
                    if (hitR != -1) break;
                }
            }
            if (hitR != -1) {
                ballMoving = false;
                while (hitR < BROWS && bGrid[hitR][hitC] != 0) hitR++;
                if (hitR >= BROWS - 1) { gameOver = true; RetroSound.gameOver(); saveBest(); }
                else {
                    bGrid[hitR][hitC] = curBallColor; popBobbleCluster(hitR, hitC, curBallColor);
                    curBallColor = nextBallColor; nextBallColor = rnd.nextInt(BCOLORS.length) + 1;
                    if (++shotsCount % 6 == 0) {
                        for (int r = BROWS - 1; r > 0; r--) bGrid[r] = bGrid[r - 1].clone();
                        for (int c = 0; c < BCOLS; c++) bGrid[0][c] = rnd.nextInt(BCOLORS.length) + 1;
                    }
                }
            }
        } else if (gameType == GAME_ARKANOID) {
            aBallX += aVX; aBallY += aVY;
            if (aBallX <= 0.03f || aBallX >= 0.97f) { aVX = -aVX; RetroSound.blip(); }
            if (aBallY <= 0.12f) { aVY = -aVY; RetroSound.blip(); }
            if (aBallY >= 0.84f && aBallY <= 0.87f && Math.abs(aBallX - padX) <= 0.14f && aVY > 0) {
                aVY = -Math.abs(aVY) * 1.015f; aVX = (aBallX - padX) * 0.11f; RetroSound.blip();
            }
            if (aBallY > 0.96f) {
                lives--; RetroSound.boom();
                if (lives <= 0) { gameOver = true; RetroSound.gameOver(); saveBest(); } else resetArkanoidBall();
            }
            boolean anyBrick = false;
            for (int r = 0; r < 6; r++) for (int c = 0; c < 8; c++) {
                if (bricks[r][c] <= 0) continue;
                anyBrick = true;
                float bx1 = 0.04f + c * 0.115f, by1 = 0.16f + r * 0.042f;
                if (aBallX >= bx1 && aBallX <= bx1 + 0.105f && aBallY >= by1 && aBallY <= by1 + 0.034f) {
                    bricks[r][c]--; aVY = -aVY; score += 50; RetroSound.score(); saveBest();
                }
            }
            if (!anyBrick) initGame();
        } else if (gameType == GAME_PACMAN && now - lastPacTick > 180) {
            lastPacTick = now; if (powerTimer > 0) powerTimer--;
            int[] dx = {1,0,-1,0}, dy = {0,1,0,-1};
            int nx = pacX + dx[nextPacDir], ny = pacY + dy[nextPacDir];
            if (nx >= 0 && nx < PW && ny >= 0 && ny < PH && pMap[ny][nx] != 1) pacDir = nextPacDir;
            nx = pacX + dx[pacDir]; ny = pacY + dy[pacDir];
            if (nx >= 0 && nx < PW && ny >= 0 && ny < PH && pMap[ny][nx] != 1) {
                pacX = nx; pacY = ny;
                if (pMap[pacY][pacX] == 2) { pMap[pacY][pacX] = 0; score += 10; RetroSound.blip(); saveBest(); }
                else if (pMap[pacY][pacX] == 3) { pMap[pacY][pacX] = 0; score += 50; powerTimer = 35; RetroSound.score(); saveBest(); }
            }
            boolean dots = false;
            for (int r = 0; r < PH; r++) for (int c = 0; c < PW; c++) if (pMap[r][c] == 2 || pMap[r][c] == 3) dots = true;
            if (!dots) initPacman();
            for (int i = 0; i < 4; i++) {
                int gx = gX[i] + dx[gDir[i]], gy = gY[i] + dy[gDir[i]];
                if (gx < 1 || gx >= PW - 1 || gy < 1 || gy >= PH - 1 || pMap[gy][gx] == 1 || rnd.nextInt(4) == 0) gDir[i] = rnd.nextInt(4);
                else { gX[i] = gx; gY[i] = gy; }
                if (gX[i] == pacX && gY[i] == pacY) {
                    if (powerTimer > 0) { score += 200; gX[i] = 7; gY[i] = 8; RetroSound.score(); saveBest(); }
                    else { gameOver = true; RetroSound.gameOver(); saveBest(); }
                }
            }
        }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        c.drawColor(Color.rgb(6, 20, 44));
        paint.setColor(Color.rgb(12, 42, 82)); c.drawRect(0, 0, w, h * 0.10f, paint);
        paint.setColor(Color.rgb(212, 175, 55)); paint.setTextSize(w * 0.044f); paint.setFakeBoldText(true);
        c.drawText("PUNTOS: " + score, w * 0.05f, h * 0.062f, paint);
        paint.setColor(Color.WHITE); c.drawText("RÉCORD: " + bestScore, w * 0.54f, h * 0.062f, paint);

        if (gameType == GAME_TETRIS) {
            float cell = Math.min((w * 0.72f) / TW, (h * 0.74f) / TH), ox = (w - cell * TW) / 2f, oy = h * 0.13f;
            paint.setColor(Color.rgb(12, 34, 68)); c.drawRect(ox, oy, ox + cell * TW, oy + cell * TH, paint);
            for (int r = 0; r < TH; r++) for (int col = 0; col < TW; col++) if (tGrid[r][col] != 0) {
                paint.setColor(tGrid[r][col]);
                c.drawRoundRect(new RectF(ox + col * cell + 2, oy + r * cell + 2, ox + (col + 1) * cell - 2, oy + (r + 1) * cell - 2), 6, 6, paint);
            }
            for (int r = 0; r < curPiece.length; r++) for (int col = 0; col < curPiece[r].length; col++) if (curPiece[r][col] != 0) {
                paint.setColor(curColor);
                c.drawRoundRect(new RectF(ox + (curX + col) * cell + 2, oy + (curY + r) * cell + 2, ox + (curX + col + 1) * cell - 2, oy + (curY + r + 1) * cell - 2), 6, 6, paint);
            }
            paint.setColor(Color.rgb(180, 210, 245)); paint.setTextSize(w * 0.036f);
            c.drawText("TOCA = GIRAR  ·  DESLIZA = MOVER / BAJAR", w * 0.11f, h * 0.93f, paint);
        } else if (gameType == GAME_BOBBLE) {
            float rad = (w / BCOLS) * 0.44f;
            for (int r = 0; r < BROWS; r++) for (int col = 0; col < BCOLS; col++) {
                int v = bGrid[r][col]; if (v == 0) continue;
                paint.setColor(BCOLORS[(v - 1) % BCOLORS.length]);
                c.drawCircle((col + 0.5f) * (w / BCOLS), h * (0.14f + r * 0.052f), rad, paint);
            }
            float sx = w * 0.5f, sy = h * 0.87f;
            paint.setColor(Color.rgb(212, 175, 55)); paint.setStrokeWidth(10);
            c.drawLine(sx, sy, sx + (float)Math.cos(aimAngle) * 110, sy + (float)Math.sin(aimAngle) * 110, paint);
            paint.setColor(BCOLORS[(curBallColor - 1) % BCOLORS.length]);
            c.drawCircle(ballMoving ? ballX * w : sx, ballMoving ? ballY * h : sy, rad, paint);
            paint.setColor(BCOLORS[(nextBallColor - 1) % BCOLORS.length]);
            c.drawCircle(w * 0.22f, h * 0.90f, rad * 0.7f, paint);
        } else if (gameType == GAME_ARKANOID) {
            int[] rowCols = {Color.RED, Color.rgb(255,140,0), Color.YELLOW, Color.GREEN, Color.CYAN, Color.MAGENTA};
            for (int r = 0; r < 6; r++) for (int col = 0; col < 8; col++) {
                if (bricks[r][col] <= 0) continue;
                paint.setColor(rowCols[r]);
                float bx1 = w * (0.04f + col * 0.115f), by1 = h * (0.16f + r * 0.042f);
                c.drawRoundRect(new RectF(bx1, by1, bx1 + w * 0.105f, by1 + h * 0.034f), 8, 8, paint);
            }
            paint.setColor(Color.rgb(212, 175, 55));
            c.drawRoundRect(new RectF(w * (padX - 0.13f), h * 0.85f, w * (padX + 0.13f), h * 0.875f), 14, 14, paint);
            paint.setColor(Color.WHITE); c.drawCircle(aBallX * w, aBallY * h, w * 0.022f, paint);
            paint.setTextSize(w * 0.038f); c.drawText("VIDAS: " + lives, w * 0.06f, h * 0.94f, paint);
        } else {
            float cw = w / PW, ch = (h * 0.76f) / PH, oy = h * 0.13f;
            for (int r = 0; r < PH; r++) for (int col = 0; col < PW; col++) {
                int v = pMap[r][col];
                if (v == 1) { paint.setColor(Color.rgb(24, 90, 190)); c.drawRoundRect(new RectF(col * cw + 2, oy + r * ch + 2, (col + 1) * cw - 2, oy + (r + 1) * ch - 2), 8, 8, paint); }
                else if (v == 2) { paint.setColor(Color.rgb(255, 225, 150)); c.drawCircle((col + 0.5f) * cw, oy + (r + 0.5f) * ch, cw * 0.12f, paint); }
                else if (v == 3) { paint.setColor(Color.rgb(212, 175, 55)); c.drawCircle((col + 0.5f) * cw, oy + (r + 0.5f) * ch, cw * 0.26f, paint); }
            }
            paint.setColor(Color.YELLOW); c.drawCircle((pacX + 0.5f) * cw, oy + (pacY + 0.5f) * ch, cw * 0.40f, paint);
            int[] gCols = {Color.RED, Color.CYAN, Color.MAGENTA, Color.rgb(255,165,0)};
            for (int i = 0; i < 4; i++) {
                paint.setColor(powerTimer > 0 ? Color.rgb(60, 120, 255) : gCols[i]);
                c.drawCircle((gX[i] + 0.5f) * cw, oy + (gY[i] + 0.5f) * ch, cw * 0.38f, paint);
            }
            paint.setColor(Color.rgb(180, 210, 245)); paint.setTextSize(w * 0.036f);
            c.drawText("DESLIZA EN CUALQUIER DIRECCIÓN PARA GIRAR", w * 0.09f, h * 0.94f, paint);
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
        if (gameOver && e.getAction() == MotionEvent.ACTION_DOWN) { initGame(); return true; }
        if (gameType == GAME_ARKANOID) { padX = Math.max(0.14f, Math.min(0.86f, e.getX() / getWidth())); return true; }
        if (gameType == GAME_BOBBLE) {
            float dx = e.getX() - getWidth() * 0.5f, dy = e.getY() - getHeight() * 0.87f;
            if (dy < -20) aimAngle = (float)Math.atan2(dy, dx);
            if (e.getAction() == MotionEvent.ACTION_UP && !ballMoving) {
                ballX = 0.5f; ballY = 0.87f; ballVX = (float)Math.cos(aimAngle) * 0.022f; ballVY = (float)Math.sin(aimAngle) * 0.022f;
                ballMoving = true; RetroSound.blip();
            }
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_DOWN) { touchStartX = e.getX(); touchStartY = e.getY(); }
        else if (e.getAction() == MotionEvent.ACTION_UP) {
            float dx = e.getX() - touchStartX, dy = e.getY() - touchStartY;
            if (Math.abs(dx) < 28 && Math.abs(dy) < 28) { if (gameType == GAME_TETRIS) rotateTetris(); }
            else if (Math.abs(dx) > Math.abs(dy)) {
                if (gameType == GAME_TETRIS) { int st = dx > 0 ? 1 : -1; if (canMoveTetris(curPiece, curX + st, curY)) { curX += st; RetroSound.blip(); } }
                else if (gameType == GAME_PACMAN) nextPacDir = dx > 0 ? 0 : 2;
            } else {
                if (gameType == GAME_TETRIS && dy > 0) { while (canMoveTetris(curPiece, curX, curY + 1)) curY++; lockTetris(); }
                else if (gameType == GAME_PACMAN) nextPacDir = dy > 0 ? 1 : 3;
            }
        }
        return true;
    }
}
