package dev.mbaiforinstinct.rebornlauncher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Nokia C2-01 on-screen keypad for a normal touchscreen phone.
 * The d-pad is five physically separate buttons with wide gaps between them,
 * so a finger lands on one control at a time.
 */
public class OnScreenKeypad extends View {

    public interface KeySink {
        void onKey(int keyCode);
    }

    private static final class Key {
        RectF rect;
        final int code;
        final String label;
        final String sub;
        final int bg;
        final int fg;

        Key(int code, String label, String sub, int bg, int fg) {
            this.code = code;
            this.label = label;
            this.sub = sub;
            this.bg = bg;
            this.fg = fg;
        }
    }

    private final List<Key> keys = new ArrayList<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler();
    private Key pressed;
    private KeySink sink;

    private static final int KEY_BG = Color.parseColor("#171A1A");
    private static final int KEY_FG = Color.parseColor("#43BEE9");
    private static final int NAVI_BG = Color.parseColor("#E1E3E3");
    private static final int NAVI_FG = Color.parseColor("#1A1A1A");
    private static final int CALL_BG = Color.parseColor("#2E9E4F");
    private static final int END_BG = Color.parseColor("#D32F2F");
    private static final int PRESSED_BG = Color.parseColor("#43BEE9");

    // Column boundaries as fractions of the keypad width: three columns with
    // wide gutters so neighbouring buttons can never share a touch.
    private static final float[] COL_L = {0.030f, 0.365f, 0.700f};
    private static final float[] COL_R = {0.300f, 0.635f, 0.970f};

    public OnScreenKeypad(Context context) {
        super(context);
        buildKeys();
    }

    public void setKeySink(KeySink sink) {
        this.sink = sink;
    }

    private void buildKeys() {
        keys.add(new Key(KeyEvent.KEYCODE_SOFT_LEFT, "Menu", "", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_SOFT_RIGHT, "Back", "", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_DPAD_UP, "\u25B2", "", NAVI_BG, NAVI_FG));
        keys.add(new Key(KeyEvent.KEYCODE_DPAD_LEFT, "\u25C0", "", NAVI_BG, NAVI_FG));
        keys.add(new Key(KeyEvent.KEYCODE_DPAD_CENTER, "OK", "", NAVI_BG, NAVI_FG));
        keys.add(new Key(KeyEvent.KEYCODE_DPAD_RIGHT, "\u25B6", "", NAVI_BG, NAVI_FG));
        keys.add(new Key(KeyEvent.KEYCODE_CALL, "\u2713", "", CALL_BG, Color.WHITE));
        keys.add(new Key(KeyEvent.KEYCODE_DPAD_DOWN, "\u25BC", "", NAVI_BG, NAVI_FG));
        keys.add(new Key(KeyEvent.KEYCODE_ENDCALL, "\u2715", "", END_BG, Color.WHITE));
        keys.add(new Key(KeyEvent.KEYCODE_1, "1", ".,", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_2, "2", "abc", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_3, "3", "def", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_4, "4", "ghi", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_5, "5", "jkl", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_6, "6", "mno", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_7, "7", "pqrs", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_8, "8", "tuv", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_9, "9", "wxyz", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_STAR, "*", "", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_0, "0", "+", KEY_BG, KEY_FG));
        keys.add(new Key(KeyEvent.KEYCODE_POUND, "#", "", KEY_BG, KEY_FG));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutKeys(w, h);
    }

    private void layoutKeys(int w, int h) {
        // Soft row: two wide keys at the top.
        float softTop = 0.02f * h;
        float softBottom = 0.13f * h;
        keys.get(0).rect = new RectF(COL_L[0] * w, softTop, COL_R[0] * w, softBottom);
        keys.get(1).rect = new RectF(COL_L[2] * w, softTop, COL_R[2] * w, softBottom);

        // D-pad cluster: 3x3 cross with CALL bottom-left and END bottom-right.
        float clTop = 0.16f * h;
        float clH = 0.34f * h;
        float r0t = clTop, r0b = clTop + 0.28f * clH;
        float r1t = clTop + 0.36f * clH, r1b = clTop + 0.64f * clH;
        float r2t = clTop + 0.72f * clH, r2b = clTop + clH;
        keys.get(2).rect = new RectF(COL_L[1] * w, r0t, COL_R[1] * w, r0b); // UP
        keys.get(3).rect = new RectF(COL_L[0] * w, r1t, COL_R[0] * w, r1b); // LEFT
        keys.get(4).rect = new RectF(COL_L[1] * w, r1t, COL_R[1] * w, r1b); // CENTER
        keys.get(5).rect = new RectF(COL_L[2] * w, r1t, COL_R[2] * w, r1b); // RIGHT
        keys.get(6).rect = new RectF(COL_L[0] * w, r2t, COL_R[0] * w, r2b); // CALL
        keys.get(7).rect = new RectF(COL_L[1] * w, r2t, COL_R[1] * w, r2b); // DOWN
        keys.get(8).rect = new RectF(COL_L[2] * w, r2t, COL_R[2] * w, r2b); // END

        // Digit grid: 4 rows x 3 columns in the remaining space.
        float gridTop = 0.53f * h;
        float gridH = 0.45f * h;
        float rowH = gridH / 4f;
        float gap = 0.18f * rowH;
        for (int i = 0; i < 12; i++) {
            int col = i % 3;
            int row = i / 3;
            float top = gridTop + row * rowH + gap / 2f;
            keys.get(9 + i).rect = new RectF(COL_L[col] * w, top, COL_R[col] * w, top + rowH - gap);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#0A0C0E"));
        c.drawRect(0, 0, w, h, p);
        for (Key k : keys) {
            if (k.rect == null) continue;
            p.setStyle(Paint.Style.FILL);
            p.setColor(k == pressed ? PRESSED_BG : k.bg);
            float radius = Math.min(k.rect.width(), k.rect.height()) * 0.18f;
            c.drawRoundRect(k.rect, radius, radius, p);
            p.setColor(k == pressed ? Color.WHITE : k.fg);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(k.rect.height() * 0.42f);
            float baseline = k.rect.centerY() + p.getTextSize() * 0.35f - (k.sub.isEmpty() ? 0 : k.rect.height() * 0.10f);
            c.drawText(k.label, k.rect.centerX(), baseline, p);
            if (!k.sub.isEmpty()) {
                p.setTextSize(k.rect.height() * 0.20f);
                c.drawText(k.sub, k.rect.centerX(), k.rect.centerY() + k.rect.height() * 0.38f, p);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(event.getX(), event.getY());
                if (pressed != null) {
                    invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                return pressed != null;
            case MotionEvent.ACTION_UP:
                Key k = pressed;
                pressed = null;
                invalidate();
                if (k != null && k.rect.contains(event.getX(), event.getY()) && sink != null) {
                    sink.onKey(k.code);
                }
                return k != null;
            case MotionEvent.ACTION_CANCEL:
                pressed = null;
                invalidate();
                return true;
            default:
                return false;
        }
    }

    private Key hit(float x, float y) {
        for (Key k : keys) {
            if (k.rect != null && k.rect.contains(x, y)) return k;
        }
        return null;
    }
}
