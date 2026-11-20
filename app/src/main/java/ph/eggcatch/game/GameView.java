package ph.eggcatch.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Egg Catch: a retro LCD handheld-style game.
 * Hens on four ramps lay eggs that roll down step by step. Move the farmer's basket
 * to the end of the right ramp before each egg drops. Three broken eggs end the game.
 *
 * All drawing happens in a 400 x 300 "LCD" coordinate space that is scaled to the screen.
 * Every LCD segment has a faint "ghost" so the screen looks like a real segment display.
 */
public class GameView extends View {

    // ramps: 0 top-left, 1 bottom-left, 2 top-right, 3 bottom-right
    private static final int STEPS = 5;

    private enum State { MENU, PLAY, MISS, OVER, PAUSED }

    private State state = State.MENU, resumeState = State.PLAY;
    private boolean gameB;
    private boolean sound = true;
    private int score, misses, pos;          // pos = basket position 0..3
    private int hiA, hiB;
    private float tickTimer, animTime;
    private int tickCount;
    private int missRamp = -1;
    private boolean newHi;

    private static final class Egg {
        int ramp, step, endTick;
        Egg(int r, int t) { ramp = r; step = 0; endTick = t + STEPS; }
    }

    private final List<Egg> eggs = new ArrayList<>();
    private final Random rnd = new Random();
    private ToneGenerator tone;
    private final SharedPreferences prefs;
    private long lastFrame;

    // screen layout
    private int W, H;
    private float lcdX, lcdY, lcdS;           // LCD origin and scale (pixels per LCD unit)
    private final RectF[] pad = {new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF bGameA = new RectF(), bGameB = new RectF(), bSound = new RectF(), bPause = new RectF();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private static final int LCD_BG = Color.rgb(198, 204, 180);
    private static final int INK = Color.rgb(28, 32, 28);
    private static final int GHOST = Color.argb(22, 28, 32, 28);
    private int ink = INK;    // current segment colour (INK or GHOST)

    public GameView(Context c) {
        super(c);
        prefs = c.getSharedPreferences("eggcatch", Context.MODE_PRIVATE);
        hiA = prefs.getInt("hiA", 0);
        hiB = prefs.getInt("hiB", 0);
        sound = prefs.getBoolean("sound", true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60); } catch (RuntimeException e) { tone = null; }
        setKeepScreenOn(true);
    }

    private void beep(int t, int ms) {
        if (!sound || tone == null) return;
        try { tone.startTone(t, ms); } catch (RuntimeException ignored) { }
    }

    public void release() { if (tone != null) { tone.release(); tone = null; } }

    public void pause() {
        if (state == State.PLAY || state == State.MISS) { resumeState = state; state = State.PAUSED; invalidate(); }
    }

    public boolean onBack() {
        if (state == State.MENU) return false;
        if (state == State.PLAY || state == State.MISS) { pause(); return true; }
        state = State.MENU;
        invalidate();
        return true;
    }

    // ---------------- layout ----------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        // LCD keeps a 4:3 shape and leaves room on both sides for the buttons
        float lw = Math.min(W * 0.64f, H * 0.82f * 4f / 3f);
        lcdS = lw / 400f;
        lcdX = (W - lw) / 2f;
        lcdY = (H - 300 * lcdS) / 2f;
        float side = lcdX;                                 // width of each side panel
        float r = Math.min(side * 0.36f, H * 0.14f);
        float cxL = side / 2f, cxR = W - side / 2f;
        float y1 = H * 0.36f, y2 = H * 0.72f;
        pad[0].set(cxL - r, y1 - r, cxL + r, y1 + r);
        pad[1].set(cxL - r, y2 - r, cxL + r, y2 + r);
        pad[2].set(cxR - r, y1 - r, cxR + r, y1 + r);
        pad[3].set(cxR - r, y2 - r, cxR + r, y2 + r);
        float bw = Math.min(side * 0.7f, r * 2.2f), bh = Math.max(r * 0.42f, 28);
        bPause.set(cxR - bw / 2, H * 0.08f, cxR + bw / 2, H * 0.08f + bh);
    }

    // ---------------- game logic ----------------

    private void startGame(boolean b) {
        gameB = b;
        score = 0; misses = 0; pos = 0;
        eggs.clear();
        tickCount = 0;
        tickTimer = 0;
        newHi = false;
        state = State.PLAY;
        lastFrame = 0;
        beep(ToneGenerator.TONE_PROP_ACK, 150);
        invalidate();
    }

    private float tickInterval() {
        float base = gameB ? 0.40f : 0.52f;
        float min = gameB ? 0.14f : 0.18f;
        int s = score % 1000;
        return Math.max(min, base - s * (gameB ? 0.0011f : 0.0010f));
    }

    private int maxEggs() {
        return gameB ? Math.min(5, 2 + score / 30) : Math.min(4, 1 + score / 25);
    }

    private void tick() {
        tickCount++;
        boolean moved = !eggs.isEmpty();
        // resolve eggs that leave their ramp this tick
        for (int i = eggs.size() - 1; i >= 0; i--) {
            Egg e = eggs.get(i);
            if (e.step >= STEPS - 1) {
                eggs.remove(i);
                if (e.ramp == pos) catchEgg();
                else { missEgg(e.ramp); return; }
            } else {
                e.step++;
            }
        }
        if (moved) beep(ToneGenerator.TONE_PROP_BEEP, 12);
        spawn();
    }

    /** Lays a new egg if there is room; never lets two eggs drop on the same tick. */
    private void spawn() {
        if (eggs.size() >= maxEggs()) return;
        if (!eggs.isEmpty() && rnd.nextFloat() > (gameB ? 0.6f : 0.45f)) return;
        int end = tickCount + STEPS;
        for (Egg e : eggs) if (e.endTick == end) return;
        int[] order = {0, 1, 2, 3};
        for (int i = 3; i > 0; i--) { int j = rnd.nextInt(i + 1); int t = order[i]; order[i] = order[j]; order[j] = t; }
        for (int r : order) {
            boolean busy = false;
            for (Egg e : eggs) if (e.ramp == r && e.step <= 1) { busy = true; break; }
            if (!busy) { eggs.add(new Egg(r, tickCount)); return; }
        }
    }

    private void catchEgg() {
        score = Math.min(999, score + 1);
        beep(ToneGenerator.TONE_PROP_BEEP2, 40);
        if (score == 200 || score == 500) {
            misses = 0;                          // bonus: all misses forgiven
            beep(ToneGenerator.TONE_CDMA_CONFIRM, 400);
        }
    }

    private void missEgg(int ramp) {
        misses++;
        missRamp = ramp;
        eggs.clear();
        state = State.MISS;
        animTime = 0;
        beep(ToneGenerator.TONE_CDMA_LOW_L, 300);
    }

    private void update(float dt) {
        if (state == State.PLAY) {
            tickTimer += dt;
            float iv = tickInterval();
            while (tickTimer >= iv && state == State.PLAY) {
                tickTimer -= iv;
                tick();
            }
        } else if (state == State.MISS) {
            animTime += dt;
            if (animTime > 1.6f) {
                missRamp = -1;
                if (misses >= 3) {
                    state = State.OVER;
                    animTime = 0;
                    if (gameB && score > hiB) { hiB = score; newHi = true; }
                    if (!gameB && score > hiA) { hiA = score; newHi = true; }
                    prefs.edit().putInt("hiA", hiA).putInt("hiB", hiB).apply();
                    beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
                } else {
                    state = State.PLAY;
                    tickTimer = 0;
                }
            }
        } else if (state == State.OVER) {
            animTime += dt;
        }
    }

    // ---------------- drawing ----------------

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (state != State.PAUSED && state != State.MENU) update(dt);

        drawBody(c);
        c.save();
        c.translate(lcdX, lcdY);
        c.scale(lcdS, lcdS);
        fill.setColor(LCD_BG);
        c.drawRoundRect(new RectF(0, 0, 400, 300), 8, 8, fill);
        drawGhosts(c);
        drawLive(c);
        c.restore();
        if (state == State.MENU || state == State.OVER || state == State.PAUSED) drawOverlay(c);
        if (state == State.PLAY || state == State.MISS || state == State.OVER) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    private void drawBody(Canvas c) {
        c.drawColor(Color.rgb(176, 36, 40));
        // brushed metal face plate behind the LCD
        fill.setColor(Color.rgb(205, 198, 182));
        float m = 18 * lcdS;
        c.drawRoundRect(new RectF(lcdX - m, lcdY - m, lcdX + 400 * lcdS + m, lcdY + 300 * lcdS + m), m, m, fill);
        fill.setColor(Color.rgb(40, 40, 44));
        float b = 6 * lcdS;
        c.drawRoundRect(new RectF(lcdX - b, lcdY - b, lcdX + 400 * lcdS + b, lcdY + 300 * lcdS + b), b * 2, b * 2, fill);
        // side buttons
        String[] lab = {"↖", "↙", "↗", "↘"};
        for (int i = 0; i < 4; i++) {
            RectF r = pad[i];
            boolean on = state == State.PLAY && pos == i;
            fill.setColor(Color.argb(90, 0, 0, 0));
            c.drawOval(new RectF(r.left + r.width() * 0.05f, r.top + r.height() * 0.08f, r.right + r.width() * 0.05f, r.bottom + r.height() * 0.08f), fill);
            fill.setColor(on ? Color.rgb(255, 215, 120) : Color.rgb(245, 200, 60));
            c.drawOval(r, fill);
            stroke.setColor(Color.rgb(120, 70, 10));
            stroke.setStrokeWidth(Math.max(2, r.width() * 0.04f));
            c.drawOval(r, stroke);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(r.height() * 0.45f);
            text.setColor(Color.rgb(110, 60, 0));
            c.drawText(lab[i], r.centerX(), r.centerY() + r.height() * 0.16f, text);
        }
        // title and pause on the plate
        float ts = Math.max(14, Math.min(lcdX * 0.13f, H * 0.045f));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(ts);
        text.setColor(Color.rgb(255, 230, 200));
        c.drawText("EGG", lcdX / 2f, H * 0.11f, text);
        c.drawText("CATCH", lcdX / 2f, H * 0.11f + ts * 1.2f, text);
        if (state == State.PLAY || state == State.MISS) {
            fill.setColor(Color.rgb(60, 60, 66));
            c.drawRoundRect(bPause, bPause.height() / 2, bPause.height() / 2, fill);
            text.setTextSize(bPause.height() * 0.5f);
            text.setColor(Color.WHITE);
            c.drawText("PAUSE", bPause.centerX(), bPause.centerY() + bPause.height() * 0.18f, text);
        }
    }

    // ---- LCD geometry (400 x 300) ----

    private static float mx(int ramp, float x) { return ramp < 2 ? x : 400 - x; }
    private static float rampTopY(int ramp) { return ramp % 2 == 0 ? 78 : 168; }

    /** egg centre for a ramp and step */
    private static float eggX(int ramp, int step) { return mx(ramp, 64 + step * 21); }
    private static float eggY(int ramp, int step) { return rampTopY(ramp) + 40f * (64 + step * 21 - 48) / 110f - 8; }

    private void drawGhosts(Canvas c) {
        ink = GHOST;
        for (int r = 0; r < 4; r++) {
            for (int k = 0; k < STEPS; k++) drawEgg(c, r, k);
            drawBasket(c, r);
        }
        for (int side = 0; side < 2; side++) {
            drawBroken(c, side);
            for (int f = 0; f < 4; f++) drawChick(c, side, f);
        }
        for (int i = 0; i < 3; i++) drawMissIcon(c, i);
        drawNumber(c, 888, 200, 14, 26);
    }

    private void drawLive(Canvas c) {
        ink = INK;
        // fixed scenery: ramps, hens, floor
        for (int r = 0; r < 4; r++) { drawRamp(c, r); drawHen(c, r); }
        stroke.setColor(INK);
        stroke.setStrokeWidth(2);
        c.drawLine(10, 292, 390, 292, stroke);

        if (state == State.MENU) {
            drawNumber(c, 0, 200, 14, 26);
            drawPlayer(c, 0, true);
            drawBasket(c, 0);
            drawEgg(c, 0, 2);
            drawEgg(c, 3, 3);
            return;
        }
        if (!(state == State.OVER && ((int) (animTime * 2)) % 2 == 1)) drawNumber(c, score, 200, 14, 26);
        for (int i = 0; i < misses && i < 3; i++) drawMissIcon(c, i);
        for (Egg e : eggs) drawEgg(c, e.ramp, e.step);
        drawBasket(c, pos);
        drawPlayer(c, pos, true);
        if (state == State.MISS && missRamp >= 0) {
            int side = missRamp < 2 ? 0 : 1;
            drawBroken(c, side);
            int f = Math.min(3, (int) (animTime / 0.35f));
            if (animTime > 0.3f) drawChick(c, side, f);
        }
    }

    private void drawRamp(Canvas c, int r) {
        stroke.setColor(ink);
        stroke.setStrokeWidth(3);
        float y = rampTopY(r);
        c.drawLine(mx(r, 48), y, mx(r, 158), y + 40, stroke);
        stroke.setStrokeWidth(2);
        c.drawLine(mx(r, 52), y + 2, mx(r, 52), y + 14, stroke);
    }

    private void drawHen(Canvas c, int r) {
        float s = r < 2 ? 1 : -1;
        float x = mx(r, 30), y = rampTopY(r) - 18;
        fill.setColor(ink);
        c.drawOval(new RectF(x - 16, y - 10, x + 14, y + 12), fill);             // body
        c.drawCircle(x + s * 12, y - 14, 7, fill);                               // head
        path.reset();                                                            // tail
        path.moveTo(x - s * 14, y - 4);
        path.lineTo(x - s * 24, y - 16);
        path.lineTo(x - s * 18, y + 2);
        path.close();
        c.drawPath(path, fill);
        path.reset();                                                            // beak
        path.moveTo(x + s * 18, y - 16);
        path.lineTo(x + s * 25, y - 13);
        path.lineTo(x + s * 18, y - 11);
        path.close();
        c.drawPath(path, fill);
        // comb
        c.drawCircle(x + s * 10, y - 22, 3, fill);
        c.drawCircle(x + s * 14, y - 22, 3, fill);
        // eye and wing in LCD background colour
        fill.setColor(LCD_BG);
        c.drawCircle(x + s * 14, y - 15, 1.6f, fill);
        stroke.setColor(LCD_BG);
        stroke.setStrokeWidth(1.5f);
        c.drawArc(new RectF(x - 8, y - 4, x + 6, y + 8), r < 2 ? 200 : -20, 140, false, stroke);
    }

    private void drawEgg(Canvas c, int r, int k) {
        float x = eggX(r, k), y = eggY(r, k);
        c.save();
        c.translate(x, y);
        c.rotate((r < 2 ? 1 : -1) * (k % 2 == 0 ? 20 : 70));
        fill.setColor(ink);
        c.drawOval(new RectF(-6, -8, 6, 8), fill);
        c.restore();
    }

    private void drawBasket(Canvas c, int p) {
        float x = mx(p, 168), y = rampTopY(p) + 48;
        fill.setColor(ink);
        path.reset();
        path.moveTo(x - 15, y - 8);
        path.lineTo(x + 15, y - 8);
        path.lineTo(x + 11, y + 8);
        path.lineTo(x - 11, y + 8);
        path.close();
        c.drawPath(path, fill);
        stroke.setColor(ink);
        stroke.setStrokeWidth(2);
        c.drawArc(new RectF(x - 13, y - 20, x + 13, y + 2), 180, 180, false, stroke);
    }

    /** The farmer stands in the middle, leaning away from the basket so the arms have room. */
    private void drawPlayer(Canvas c, int p, boolean arms) {
        boolean left = p < 2;
        float s = left ? -1 : 1;
        float cx = left ? 207 : 193;
        fill.setColor(ink);
        stroke.setColor(ink);
        // legs and feet
        stroke.setStrokeWidth(7);
        c.drawLine(cx - 6, 244, cx - 8, 282, stroke);
        c.drawLine(cx + 6, 244, cx + 8, 282, stroke);
        c.drawOval(new RectF(cx - 8 + s * 5 - 8, 279, cx - 8 + s * 5 + 8, 289), fill);
        c.drawOval(new RectF(cx + 8 + s * 5 - 8, 279, cx + 8 + s * 5 + 8, 289), fill);
        // body
        c.drawRoundRect(new RectF(cx - 15, 180, cx + 15, 248), 10, 10, fill);
        // head and nose
        float hx = cx + s * 2;
        c.drawCircle(hx, 163, 13, fill);
        c.drawCircle(hx + s * 13, 166, 3.2f, fill);
        // straw hat
        c.drawOval(new RectF(hx - 26, 146, hx + 26, 155), fill);
        c.drawRoundRect(new RectF(hx - 12, 132, hx + 12, 151), 5, 5, fill);
        // face details and belt in background colour
        fill.setColor(LCD_BG);
        c.drawCircle(hx + s * 7, 162, 2f, fill);
        c.drawRect(hx - 13, 148.5f, hx + 13, 150, fill);
        c.drawRect(cx - 15, 224, cx + 15, 227, fill);
        if (!arms) return;
        // two arms reach for the basket
        stroke.setColor(ink);
        stroke.setStrokeWidth(6);
        boolean up = p % 2 == 0;
        float shx = cx + s * 13, shy = 192;
        float bx = mx(p, 168) - s * 4, by = rampTopY(p) + (up ? 44 : 46);
        float ex = shx + s * 6, ey = up ? shy - 8 : shy + 22;
        path.reset();
        path.moveTo(shx, shy);
        path.quadTo(ex, ey, bx, by);
        c.drawPath(path, stroke);
        path.reset();
        path.moveTo(shx - s * 4, shy + 10);
        path.quadTo(ex, ey + 10, bx + s * 2, by + 6);
        c.drawPath(path, stroke);
    }

    private void drawBroken(Canvas c, int side) {
        float x = side == 0 ? 132 : 268, y = 284;
        fill.setColor(ink);
        path.reset();
        path.moveTo(x - 14, y + 4);
        path.lineTo(x - 10, y - 6);
        path.lineTo(x - 5, y - 1);
        path.lineTo(x, y - 9);
        path.lineTo(x + 5, y - 1);
        path.lineTo(x + 10, y - 6);
        path.lineTo(x + 14, y + 4);
        path.close();
        c.drawPath(path, fill);
    }

    private void drawChick(Canvas c, int side, int f) {
        float dir = side == 0 ? -1 : 1;
        float x = (side == 0 ? 112 : 288) + dir * f * 26, y = 276;
        boolean step = f % 2 == 0;
        fill.setColor(ink);
        c.drawOval(new RectF(x - 8, y - 6, x + 8, y + 8), fill);
        c.drawCircle(x + dir * 7, y - 9, 5, fill);
        path.reset();
        path.moveTo(x + dir * 11, y - 10);
        path.lineTo(x + dir * 16, y - 8);
        path.lineTo(x + dir * 11, y - 6);
        path.close();
        c.drawPath(path, fill);
        stroke.setColor(ink);
        stroke.setStrokeWidth(2);
        c.drawLine(x - 3, y + 7, x - 3 + (step ? -4 : 3), y + 14, stroke);
        c.drawLine(x + 3, y + 7, x + 3 + (step ? 4 : -3), y + 14, stroke);
    }

    private void drawMissIcon(Canvas c, int i) {
        float x = 282 + i * 20, y = 26;
        fill.setColor(ink);
        c.drawOval(new RectF(x - 7, y - 5, x + 7, y + 7), fill);
        c.drawCircle(x + 6, y - 8, 4.5f, fill);
        if (i == 0) {
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(11);
            text.setColor(ink);
            c.drawText("MISS", x - 11, y + 5, text);
        }
    }

    // ---- seven-segment digits ----

    private static final int[] SEG = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};

    private void drawNumber(Canvas c, int n, float cx, float top, float h) {
        float w = h * 0.55f, gap = w * 0.45f;
        float total = 3 * w + 2 * gap;
        float x = cx - total / 2;
        int[] d = {(n / 100) % 10, (n / 10) % 10, n % 10};
        boolean lead = true;
        for (int i = 0; i < 3; i++) {
            boolean blank = ink == INK && lead && d[i] == 0 && i < 2;
            if (d[i] != 0) lead = false;
            if (!blank) drawDigit(c, ink == GHOST ? 8 : d[i], x + i * (w + gap), top, w, h);
        }
    }

    private void drawDigit(Canvas c, int digit, float x, float y, float w, float h) {
        int m = SEG[digit];
        float t = w * 0.22f, hh = h / 2;
        fill.setColor(ink);
        if ((m & 1) != 0) c.drawRect(x + t, y, x + w - t, y + t, fill);                 // a
        if ((m & 2) != 0) c.drawRect(x + w - t, y + t, x + w, y + hh, fill);            // b
        if ((m & 4) != 0) c.drawRect(x + w - t, y + hh, x + w, y + h - t, fill);        // c
        if ((m & 8) != 0) c.drawRect(x + t, y + h - t, x + w - t, y + h, fill);         // d
        if ((m & 16) != 0) c.drawRect(x, y + hh, x + t, y + h - t, fill);               // e
        if ((m & 32) != 0) c.drawRect(x, y + t, x + t, y + hh, fill);                   // f
        if ((m & 64) != 0) c.drawRect(x + t, y + hh - t / 2, x + w - t, y + hh + t / 2, fill); // g
    }

    // ---- menu / game over / pause overlay ----

    private void drawOverlay(Canvas c) {
        float L = lcdX, T = lcdY, S = lcdS;
        RectF box = new RectF(L + 70 * S, T + 60 * S, L + 330 * S, T + 250 * S);
        fill.setColor(Color.argb(235, 198, 204, 180));
        c.drawRoundRect(box, 10 * S, 10 * S, fill);
        stroke.setColor(INK);
        stroke.setStrokeWidth(2.5f * S);
        c.drawRoundRect(box, 10 * S, 10 * S, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(INK);
        float cx = box.centerX();
        if (state == State.MENU) {
            text.setTextSize(30 * S);
            c.drawText("EGG CATCH", cx, T + 98 * S, text);
            float bw = 100 * S, bh = 34 * S, y = T + 116 * S;
            bGameA.set(cx - bw - 6 * S, y, cx - 6 * S, y + bh);
            bGameB.set(cx + 6 * S, y, cx + bw + 6 * S, y + bh);
            lcdButton(c, bGameA, "GAME A");
            lcdButton(c, bGameB, "GAME B");
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(13 * S);
            c.drawText("A: normal   B: fast", cx, T + 168 * S, text);
            c.drawText("HI-SCORE  A " + hiA + "   B " + hiB, cx, T + 188 * S, text);
            bSound.set(cx - 55 * S, T + 200 * S, cx + 55 * S, T + 228 * S);
            lcdButton(c, bSound, sound ? "SOUND ON" : "SOUND OFF");
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(11 * S);
            c.drawText("Catch every egg. 3 misses = game over", cx, T + 243 * S, text);
        } else if (state == State.OVER) {
            text.setTextSize(30 * S);
            c.drawText("GAME OVER", cx, T + 105 * S, text);
            text.setTextSize(18 * S);
            c.drawText((gameB ? "GAME B" : "GAME A") + "  SCORE " + score, cx, T + 140 * S, text);
            if (newHi) c.drawText("NEW HI-SCORE!", cx, T + 168 * S, text);
            text.setTextSize(13 * S);
            c.drawText("Tap the screen to continue", cx, T + 225 * S, text);
        } else {
            text.setTextSize(30 * S);
            c.drawText("PAUSED", cx, T + 120 * S, text);
            text.setTextSize(14 * S);
            c.drawText("Tap the screen to resume", cx, T + 160 * S, text);
            c.drawText("Back again for the menu", cx, T + 182 * S, text);
        }
    }

    private void lcdButton(Canvas c, RectF r, String label) {
        fill.setColor(INK);
        c.drawRoundRect(r, r.height() * 0.25f, r.height() * 0.25f, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(r.height() * 0.48f);
        text.setColor(LCD_BG);
        c.drawText(label, r.centerX(), r.centerY() + r.height() * 0.17f, text);
        text.setColor(INK);
    }

    // ---------------- input ----------------

    private void move(int p) {
        if (p == pos) return;
        pos = p;
        beep(ToneGenerator.TONE_PROP_BEEP, 8);
        invalidate();
    }

    /** Which basket position a touch selects, or -1. Side buttons first, then LCD quadrants. */
    private int padAt(float x, float y) {
        for (int i = 0; i < 4; i++) {
            RectF r = pad[i];
            float grow = r.width() * 0.25f;
            if (x >= r.left - grow && x <= r.right + grow && y >= r.top - grow && y <= r.bottom + grow) return i;
        }
        boolean left = x < W / 2f;
        boolean top = y < lcdY + 150 * lcdS + 10 * lcdS;
        if (x < lcdX || x > lcdX + 400 * lcdS) {
            // anywhere on a side panel: upper or lower half of the screen
            top = y < (pad[0].bottom + pad[1].top) / 2f;
        }
        return left ? (top ? 0 : 1) : (top ? 2 : 3);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_POINTER_DOWN) return true;
        int idx = e.getActionIndex();
        float x = e.getX(idx), y = e.getY(idx);
        switch (state) {
            case MENU:
                if (bGameA.contains(x, y)) startGame(false);
                else if (bGameB.contains(x, y)) startGame(true);
                else if (bSound.contains(x, y)) {
                    sound = !sound;
                    prefs.edit().putBoolean("sound", sound).apply();
                    beep(ToneGenerator.TONE_PROP_BEEP, 60);
                }
                invalidate();
                break;
            case OVER:
                if (animTime > 0.8f) { state = State.MENU; invalidate(); }
                break;
            case PAUSED:
                state = resumeState;
                lastFrame = 0;
                invalidate();
                break;
            default:
                if (bPause.contains(x, y)) { pause(); break; }
                move(padAt(x, y));
                break;
        }
        return true;
    }
}
