package dev.mbaiforinstinct.rebornlauncher.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import dev.mbaiforinstinct.rebornlauncher.data.PhoneStore;
import dev.mbaiforinstinct.rebornlauncher.text.Multitap;

public class NokiaUi extends View {

    public enum Screen {
        IDLE, MENU, LIST, THREADS, READ, COMPOSE_NUMBER, COMPOSE_TEXT, DIALER, CALLLOG, CONTACTS
    }

    public interface Actions {
        void dial(String number);
        void openRoute(String section, String item);
        List<PhoneStore.Sms> sms();
        List<String[]> callLog();
        List<String[]> contacts();
        boolean sendSms(String number, String text);
        int missedCalls();
        int unreadSms();
        int batteryPercent();
    }

    private final Actions actions;
    private final Handler handler = new Handler();
    private Screen screen = Screen.IDLE;

    private static final String[] MENU_ITEMS = {
            "Messaging", "Contacts", "Call log", "Gallery", "Organiser",
            "Settings", "Music", "Radio", "Applications"
    };

    private static final String[][] LIST_ITEMS = {
            {"Conversations", "New message"},
            {"Alarm clock", "Calendar"}
    };
    private static final String[] LIST_TITLES = {"Messaging", "Organiser"};

    private int selected = 0;
    private int row = 0;
    private int listSection = 0;

    private List<PhoneStore.Sms> threads = new ArrayList<>();
    private List<String[]> rows = new ArrayList<>();
    private int readIndex = 0;

    private final StringBuilder dialNumber = new StringBuilder();
    private final StringBuilder composeNumber = new StringBuilder();
    private final Multitap composeTap = new Multitap();
    private boolean composeSent = false;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            invalidate();
            handler.postDelayed(this, 30_000);
        }
    };
    private final Runnable commitTick = () -> {
        composeTap.commit();
        invalidate();
    };

    public NokiaUi(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setFocusable(true);
        setFocusableInTouchMode(true);
        handler.postDelayed(minuteTick, 30_000);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        drawScreenBackground(c, w, h);
        drawStatus(c, w);
        switch (screen) {
            case IDLE: drawIdle(c, w, h); break;
            case MENU: drawMenu(c, w, h); break;
            case LIST: drawList(c, w, h); break;
            case THREADS: drawThreads(c, w, h); break;
            case READ: drawRead(c, w, h); break;
            case COMPOSE_NUMBER:
            case COMPOSE_TEXT: drawCompose(c, w, h); break;
            case DIALER: drawDialer(c, w, h); break;
            case CALLLOG:
            case CONTACTS: drawRows(c, w, h); break;
        }
        drawSoftkeys(c, w, h);
    }

    public boolean handleKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_ENDCALL) {
            composeTap.commit();
            screen = Screen.IDLE;
            row = 0;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SOFT_LEFT) {
            // The left softkey label names the screen's primary action
            // ("Next"/"Send"/"Reply"/"Call"), so it must fire it everywhere.
            selectCurrent();
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT && screen == Screen.IDLE) {
            rows = actions.contacts();
            row = 0;
            screen = Screen.CONTACTS;
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SOFT_RIGHT || keyCode == KeyEvent.KEYCODE_BACK) {
            back();
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CALL) {
            callKey();
            invalidate();
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            digit(keyCode - KeyEvent.KEYCODE_0);
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_POUND) {
            symbol(keyCode == KeyEvent.KEYCODE_STAR ? "*" : "#");
            invalidate();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            deleteKey();
            invalidate();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: moveHorizontal(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: moveHorizontal(1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: selectCurrent(); break;
            default: return false;
        }
        invalidate();
        return true;
    }

    private void digit(int d) {
        switch (screen) {
            case IDLE:
                dialNumber.setLength(0);
                dialNumber.append(d);
                screen = Screen.DIALER;
                break;
            case DIALER:
                if (dialNumber.length() < 24) dialNumber.append(d);
                break;
            case COMPOSE_NUMBER:
                if (composeNumber.length() < 24) composeNumber.append(d);
                break;
            case COMPOSE_TEXT:
                handler.removeCallbacks(commitTick);
                composeTap.press(d);
                handler.postDelayed(commitTick, 1600);
                break;
            default: break;
        }
    }

    private void symbol(String s) {
        if (screen == Screen.DIALER && dialNumber.length() < 24) dialNumber.append(s);
        else if (screen == Screen.COMPOSE_NUMBER && composeNumber.length() < 24) composeNumber.append(s);
    }

    private void deleteKey() {
        if (screen == Screen.DIALER && dialNumber.length() > 0) {
            dialNumber.deleteCharAt(dialNumber.length() - 1);
        } else if (screen == Screen.COMPOSE_NUMBER && composeNumber.length() > 0) {
            composeNumber.deleteCharAt(composeNumber.length() - 1);
        } else if (screen == Screen.COMPOSE_TEXT) {
            handler.removeCallbacks(commitTick);
            composeTap.backspace();
        }
    }

    private void callKey() {
        switch (screen) {
            case DIALER:
                if (dialNumber.length() > 0) {
                    actions.dial(dialNumber.toString());
                    screen = Screen.IDLE;
                    dialNumber.setLength(0);
                }
                break;
            case CALLLOG:
                if (!rows.isEmpty()) {
                    actions.dial(rows.get(row)[3]);
                    screen = Screen.IDLE;
                    row = 0;
                }
                break;
            case CONTACTS:
                if (!rows.isEmpty()) {
                    actions.dial(rows.get(row)[1]);
                    screen = Screen.IDLE;
                    row = 0;
                }
                break;
            case READ:
                if (!threads.isEmpty()) {
                    actions.dial(threads.get(readIndex).address);
                    screen = Screen.IDLE;
                }
                break;
            default:
                screen = Screen.DIALER;
                dialNumber.setLength(0);
                break;
        }
    }

    // MainActivity calls this when a background refresh has new data, so a
    // visible list re-reads the cache (never the provider) and repaints.
    public void dataChanged() {
        if (screen == Screen.THREADS) {
            threads = actions.sms();
        } else if (screen == Screen.CALLLOG) {
            rows = actions.callLog();
        } else if (screen == Screen.CONTACTS) {
            rows = actions.contacts();
        }
        invalidate();
    }

    private void back() {
        switch (screen) {
            case MENU: screen = Screen.IDLE; row = 0; break;
            case LIST: screen = Screen.MENU; row = 0; break;
            case THREADS: case COMPOSE_NUMBER: screen = Screen.LIST; row = 0; break;
            case READ: screen = Screen.THREADS; break;
            case COMPOSE_TEXT: screen = Screen.COMPOSE_NUMBER; break;
            case DIALER: screen = Screen.IDLE; dialNumber.setLength(0); break;
            case CALLLOG: case CONTACTS: screen = Screen.MENU; row = 0; break;
            default: break;
        }
    }

    private void move(int delta) {
        if (screen == Screen.MENU) {
            selected = (selected + delta * 3 + MENU_ITEMS.length) % MENU_ITEMS.length;
            return;
        }
        int count = listCount();
        if (count == 0) return;
        row = (row + delta + count) % count;
    }

    private void moveHorizontal(int delta) {
        if (screen == Screen.MENU) {
            selected = (selected + delta + MENU_ITEMS.length) % MENU_ITEMS.length;
        }
    }

    private int listCount() {
        switch (screen) {
            case LIST: return LIST_ITEMS[listSection].length;
            case THREADS: return threads.size();
            case CALLLOG: case CONTACTS: return rows.size();
            default: return 0;
        }
    }

    private void selectCurrent() {
        switch (screen) {
            case IDLE:
                screen = Screen.MENU;
                selected = 0;
                row = 0;
                break;
            case MENU:
                openMenuItem(MENU_ITEMS[selected]);
                break;
            case LIST:
                selectListItem();
                break;
            case THREADS:
                if (!threads.isEmpty()) {
                    readIndex = row;
                    screen = Screen.READ;
                }
                break;
            case READ:
                if (!threads.isEmpty()) {
                    composeNumber.setLength(0);
                    composeNumber.append(threads.get(readIndex).address);
                    composeTap.clear();
                    screen = Screen.COMPOSE_NUMBER;
                }
                break;
            case COMPOSE_NUMBER:
                if (composeNumber.length() > 0) {
                    composeTap.clear();
                    screen = Screen.COMPOSE_TEXT;
                }
                break;
            case COMPOSE_TEXT:
                handler.removeCallbacks(commitTick);
                composeTap.commit();
                android.util.Log.i("Reborn", "send key on COMPOSE_TEXT, text=" + composeTap.text());
                if (actions.sendSms(composeNumber.toString(), composeTap.text())) {
                    composeSent = true;
                    threads = actions.sms();
                    screen = Screen.THREADS;
                    row = 0;
                    handler.postDelayed(() -> { composeSent = false; invalidate(); }, 1500);
                }
                break;
            case DIALER:
                if (dialNumber.length() > 0) {
                    actions.dial(dialNumber.toString());
                    screen = Screen.IDLE;
                    dialNumber.setLength(0);
                }
                break;
            case CALLLOG:
                if (!rows.isEmpty()) {
                    actions.dial(rows.get(row)[3]);
                    screen = Screen.IDLE;
                    row = 0;
                }
                break;
            case CONTACTS:
                if (!rows.isEmpty()) {
                    actions.dial(rows.get(row)[1]);
                    screen = Screen.IDLE;
                    row = 0;
                }
                break;
        }
    }

    private void openMenuItem(String item) {
        switch (item) {
            case "Messaging":
                listSection = 0;
                screen = Screen.LIST;
                row = 0;
                break;
            case "Organiser":
                listSection = 1;
                screen = Screen.LIST;
                row = 0;
                break;
            case "Contacts":
                rows = actions.contacts();
                screen = Screen.CONTACTS;
                row = 0;
                break;
            case "Call log":
                rows = actions.callLog();
                screen = Screen.CALLLOG;
                row = 0;
                break;
            case "Radio":
                rows = new ArrayList<>();
                rows.add(new String[]{"Not available", "FM radio needs the phone's radio app.", "", ""});
                screen = Screen.CALLLOG;
                row = 0;
                break;
            default:
                actions.openRoute("Menu", item);
                break;
        }
    }

    private void selectListItem() {
        if (listSection == 0) {
            if (row == 0) {
                threads = actions.sms();
                screen = Screen.THREADS;
                row = 0;
            } else {
                composeNumber.setLength(0);
                composeTap.clear();
                composeSent = false;
                screen = Screen.COMPOSE_NUMBER;
            }
            return;
        }
        actions.openRoute(LIST_TITLES[listSection], LIST_ITEMS[listSection][row]);
    }

    private String timeLabel() {
        return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date());
    }

    private String dateLabel() {
        return new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date());
    }

    // ------------------------------------------------------------------
    // Reborn v4.89 skin: tokens lifted from the frozen c2-reborn source
    // (index.html v4.89 final-release). Reference frame: 240x320 LCD -
    // status 26px, screen 258px, soft bar 36px. Scaled to this canvas.
    // ------------------------------------------------------------------
    private static final int COL_STATUS_BG = Color.parseColor("#050708");
    private static final int COL_SOFT_BG = Color.parseColor("#020304");
    private static final int COL_SCREEN_BG = Color.parseColor("#252728");
    private static final int COL_TITLE_BG = Color.parseColor("#77A9C1");
    private static final int COL_SUB = Color.parseColor("#CCCCCC");
    private static final int COL_HOME_TEXT = Color.parseColor("#078DF0");
    private static final int COL_READ_BG = Color.parseColor("#F4F4F4");
    private static final int COL_READ_FG = Color.parseColor("#111111");
    private static final int COL_ACCENT = Color.parseColor("#43BEE9");

    private float statusH(int h) { return h * 0.08125f; }
    private float softTop(int h) { return h * 0.8875f; }
    private float screenH(int h) { return softTop(h) - statusH(h); }

    private void drawScreenBackground(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(COL_SCREEN_BG);
        c.drawRect(0, 0, w, h, p);
    }

    private void drawStatus(Canvas c, int w) {
        float sh = statusH(getHeight());
        p.setStyle(Paint.Style.FILL);
        p.setColor(COL_STATUS_BG);
        c.drawRect(0, 0, w, sh, p);
        p.setColor(Color.WHITE);
        // Signal glyph: four ascending bars (v4.89 status asset look).
        float base = sh * 0.78f;
        float bw = w * 0.018f;
        for (int b = 0; b < 4; b++) {
            float bh = sh * (0.22f + 0.14f * b);
            float x0 = w * 0.025f + b * bw * 1.45f;
            c.drawRect(x0, base - bh, x0 + bw, base, p);
        }
        // Battery glyph with live fill level.
        int batt = actions.batteryPercent();
        float bx = w * 0.135f, by = sh * 0.26f, bwid = w * 0.075f, bhei = sh * 0.48f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sh * 0.06f);
        c.drawRect(bx, by, bx + bwid, by + bhei, p);
        c.drawRect(bx + bwid, by + bhei * 0.3f, bx + bwid + w * 0.008f, by + bhei * 0.7f, p);
        if (batt >= 0) {
            p.setStyle(Paint.Style.FILL);
            float pad = sh * 0.08f;
            float fill = (bwid - 2 * pad) * Math.max(0, Math.min(100, batt)) / 100f;
            c.drawRect(bx + pad, by + pad, bx + pad + fill, by + bhei - pad, p);
        }
        // Time, right-aligned, condensed narrow face.
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        p.setTextSize(sh * 0.62f);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(timeLabel(), w * 0.965f, sh * 0.72f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawIdle(Canvas c, int w, int h) {
        float top = statusH(h), bot = softTop(h);
        // v4.89 home wallpaper: 160deg linear gradient + radial highlight.
        android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                0, top, w, bot,
                new int[]{Color.parseColor("#D7E2F6"), Color.parseColor("#B6CBEA"),
                          Color.parseColor("#6F96CD"), Color.parseColor("#325F9E")},
                new float[]{0f, 0.37f, 0.68f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        p.setStyle(Paint.Style.FILL);
        p.setShader(lg);
        c.drawRect(0, top, w, bot, p);
        android.graphics.RadialGradient rg = new android.graphics.RadialGradient(
                w * 0.64f, top + (bot - top) * 0.30f, (bot - top) * 0.34f,
                Color.argb(107, 155, 190, 239), Color.TRANSPARENT,
                android.graphics.Shader.TileMode.CLAMP);
        p.setShader(rg);
        c.drawRect(0, top, w, bot, p);
        p.setShader(null);

        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        p.setColor(COL_HOME_TEXT);
        // Clock top-right (24px at 240 width).
        p.setTextSize(w * 0.10f);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(timeLabel(), w * 0.9625f, top + (bot - top) * 0.039f + w * 0.085f, p);
        // Carrier top-left, date below (12px at 240 width).
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(w * 0.05f);
        String carrier = carrierName();
        float ty = top + (bot - top) * 0.039f + w * 0.045f;
        if (!carrier.isEmpty()) {
            c.drawText(carrier, w * 0.042f, ty, p);
            ty += w * 0.062f;
        }
        c.drawText(new SimpleDateFormat("EEE dd-MM-yyyy", Locale.UK).format(new Date()), w * 0.042f, ty, p);

        // S40-style idle notification: small light box, only when there is one.
        int missed = actions.missedCalls();
        int unread = actions.unreadSms();
        if (missed > 0 || unread > 0) {
            List<String> lines = new ArrayList<>();
            if (unread > 0) lines.add(unread + (unread == 1 ? " new message" : " new messages"));
            if (missed > 0) lines.add(missed + (missed == 1 ? " missed call" : " missed calls"));
            float boxW = w * 0.62f;
            float boxH = w * 0.075f + lines.size() * w * 0.085f;
            float bx = (w - boxW) / 2f;
            float by = top + (bot - top) * 0.52f - boxH / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            c.drawRect(bx, by, bx + boxW, by + boxH, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.004f);
            p.setColor(Color.parseColor("#888888"));
            c.drawRect(bx, by, bx + boxW, by + boxH, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(COL_READ_FG);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(w * 0.058f);
            float ly = by + w * 0.085f;
            for (String line : lines) {
                c.drawText(line, w * 0.5f, ly, p);
                ly += w * 0.085f;
            }
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    private String carrierName() {
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    getContext().getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String n = tm.getNetworkOperatorName();
                if (n != null && !n.trim().isEmpty()) return n.trim();
            }
        } catch (Exception ignored) { }
        return "";
    }

    private void drawMenu(Canvas c, int w, int h) {
        drawTitle(c, w, h, "Menu");
        float gridTop = statusH(h) + titleH(h);
        float gridHeight = softTop(h) - gridTop;
        float cellH = gridHeight / 3f;
        float cellW = w / 3f;
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            float cx = (i % 3) * cellW + cellW / 2f;
            float cy = (i / 3) * cellH + gridTop + cellH / 2f;
            if (i == selected) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.WHITE);
                c.drawRect(cx - cellW * 0.46f, cy - cellH * 0.30f, cx + cellW * 0.46f, cy + cellH * 0.30f, p);
            }
            p.setColor(i == selected ? Color.BLACK : Color.WHITE);
            p.setTextSize(w * 0.079f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(MENU_ITEMS[i], cx, cy + w * 0.028f, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    private float titleH(int h) { return screenH(h) * 0.097f; }

    private void drawList(Canvas c, int w, int h) {
        drawTitle(c, w, h, LIST_TITLES[listSection]);
        String[] items = LIST_ITEMS[listSection];
        drawItemRows(c, w, h, items.length, i -> items[i], null);
    }

    private void drawThreads(Canvas c, int w, int h) {
        drawTitle(c, w, h, "Conversations");
        if (threads.isEmpty()) {
            drawEmpty(c, w, h, "No conversations");
            return;
        }
        drawItemRows(c, w, h, threads.size(),
                i -> threads.get(i).address,
                i -> {
                    PhoneStore.Sms m = threads.get(i);
                    String body = m.body == null ? "" : m.body.replace('\n', ' ');
                    if (body.length() > 28) body = body.substring(0, 28) + "...";
                    return body + "  " + m.dateLabel();
                });
    }

    private void drawRead(Canvas c, int w, int h) {
        if (threads.isEmpty()) return;
        PhoneStore.Sms m = threads.get(readIndex);
        drawTitle(c, w, h, m.address);
        float top = statusH(h) + titleH(h);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        float y = top + screenH(h) * 0.055f;
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(COL_SUB);
        p.setTextSize(w * 0.058f);
        c.drawText(m.directionLabel() + "  " + m.dateLabel(), w * 0.033f, y, p);
        // v4.89 read box: light panel with dark text.
        float boxTop = y + screenH(h) * 0.03f;
        float boxBot = softTop(h) - screenH(h) * 0.04f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(COL_READ_BG);
        c.drawRect(w * 0.033f, boxTop, w * 0.967f, boxBot, p);
        p.setColor(COL_READ_FG);
        p.setTextSize(w * 0.071f);
        android.graphics.Rect clip = new android.graphics.Rect(
                (int) (w * 0.033f), (int) boxTop, (int) (w * 0.967f), (int) boxBot);
        c.save();
        c.clipRect(clip);
        drawWrapped(c, m.body == null ? "" : m.body, w * 0.075f, w * 0.85f, boxTop + w * 0.075f, w * 0.083f);
        c.restore();
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawCompose(Canvas c, int w, int h) {
        drawTitle(c, w, h, "New message");
        float top = statusH(h) + titleH(h);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        p.setTextAlign(Paint.Align.LEFT);
        float y = top + screenH(h) * 0.08f;
        p.setColor(COL_SUB);
        p.setTextSize(w * 0.058f);
        c.drawText("To:", w * 0.042f, y, p);
        // Light text field like the v4.89 editor.
        drawField(c, w, y + screenH(h) * 0.02f, composeNumber.toString(), screenH(h) * 0.10f);
        y += screenH(h) * 0.16f;
        if (screen == Screen.COMPOSE_TEXT) {
            p.setColor(COL_SUB);
            p.setTextSize(w * 0.058f);
            c.drawText("Message:", w * 0.042f, y, p);
            float fTop = y + screenH(h) * 0.02f;
            float fH = screenH(h) * 0.38f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(COL_READ_BG);
            c.drawRect(w * 0.042f, fTop, w * 0.958f, fTop + fH, p);
            p.setColor(COL_READ_FG);
            p.setTextSize(w * 0.071f);
            android.graphics.Rect clip = new android.graphics.Rect(
                    (int) (w * 0.042f), (int) fTop, (int) (w * 0.958f), (int) (fTop + fH));
            c.save();
            c.clipRect(clip);
            drawWrapped(c, composeTap.preview(), w * 0.075f, w * 0.85f, fTop + w * 0.075f, w * 0.083f);
            c.restore();
            if (composeSent) {
                p.setColor(COL_ACCENT);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(w * 0.071f);
                c.drawText("Message sent", w * 0.5f, fTop + fH + screenH(h) * 0.10f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        } else {
            p.setColor(COL_SUB);
            p.setTextSize(w * 0.058f);
            c.drawText("Type the number, then Centre", w * 0.042f, y, p);
        }
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawField(Canvas c, int w, float top, String text, float height) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(COL_READ_BG);
        c.drawRect(w * 0.042f, top, w * 0.958f, top + height, p);
        p.setColor(COL_READ_FG);
        p.setTextSize(w * 0.079f);
        c.drawText(text, w * 0.075f, top + height * 0.68f, p);
    }

    private void drawDialer(Canvas c, int w, int h) {
        drawTitle(c, w, h, "Dial");
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.WHITE);
        p.setTextSize(w * 0.11f);
        c.drawText(dialNumber.toString(), w * 0.5f, statusH(h) + screenH(h) * 0.45f, p);
        p.setColor(COL_SUB);
        p.setTextSize(w * 0.058f);
        c.drawText("Green key to call", w * 0.5f, statusH(h) + screenH(h) * 0.58f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawRows(Canvas c, int w, int h) {
        String title = screen == Screen.CALLLOG ? "Call log" : "Contacts";
        if (screen == Screen.CALLLOG && !rows.isEmpty() && rows.get(0).length > 1 && rows.get(0)[0].equals("Not available")) {
            title = "Radio";
        }
        drawTitle(c, w, h, title);
        if (rows.isEmpty()) {
            drawEmpty(c, w, h, screen == Screen.CONTACTS ? "No contacts" : "No calls yet");
            return;
        }
        drawItemRows(c, w, h, rows.size(),
                i -> rows.get(i)[0],
                i -> rows.get(i).length > 1 ? rows.get(i)[1] + "  " + (rows.get(i).length > 2 ? rows.get(i)[2] : "") : null);
    }

    private interface LabelAt { String get(int i); }

    private void drawItemRows(Canvas c, int w, int h, int count, LabelAt main, LabelAt sub) {
        float listTop = statusH(h) + titleH(h);
        float listHeight = softTop(h) - listTop;
        int visible = Math.min(count, 6);
        float rowH = listHeight / 6f;
        int first = Math.max(0, Math.min(row - 2, count - visible));
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        for (int i = 0; i < visible; i++) {
            int idx = first + i;
            float top = listTop + i * rowH;
            if (idx == row) {
                // v4.89 selection: full inversion, white row with black text.
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.WHITE);
                c.drawRect(0, top, w, top + rowH, p);
            }
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(idx == row ? Color.BLACK : Color.WHITE);
            p.setTextSize(w * 0.079f);
            c.drawText(main.get(idx), w * 0.042f, top + rowH * 0.44f, p);
            if (sub != null) {
                String s = sub.get(idx);
                if (s != null && !s.isEmpty()) {
                    p.setTextSize(w * 0.058f);
                    p.setColor(idx == row ? Color.parseColor("#444444") : COL_SUB);
                    c.drawText(s, w * 0.042f, top + rowH * 0.80f, p);
                }
            }
        }
        // v4.89 scrollbar: right-edge segments when the list overflows.
        if (count > visible) {
            float trackTop = listTop + listHeight * 0.05f;
            float trackH = listHeight * 0.90f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.parseColor("#EEEEEE"));
            c.drawRect(w - w * 0.008f, trackTop, w, trackTop + trackH, p);
            float segH = trackH * visible / (float) count;
            float segTop = trackTop + (trackH - segH) * first / (float) (count - visible);
            c.drawRect(w - w * 0.016f, segTop, w, segTop + segH, p);
        }
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawEmpty(Canvas c, int w, int h, String text) {
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(COL_SUB);
        p.setTextSize(w * 0.071f);
        c.drawText(text, w * 0.5f, statusH(h) + screenH(h) * 0.5f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private float drawWrapped(Canvas c, String text, float x, float maxWidth, float y, float lineH) {
        if (text.isEmpty()) return y;
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(candidate) > maxWidth && line.length() > 0) {
                c.drawText(line.toString(), x, y, p);
                y += lineH;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        c.drawText(line.toString(), x, y, p);
        return y + lineH;
    }

    private void drawTitle(Canvas c, int w, int h, String title) {
        float top = statusH(h);
        float th = titleH(h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(COL_TITLE_BG);
        c.drawRect(0, top, w, top + th, p);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        p.setTextSize(w * 0.079f);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(title, w * 0.02f, top + th * 0.72f, p);
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawSoftkeys(Canvas c, int w, int h) {
        float top = softTop(h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(COL_SOFT_BG);
        c.drawRect(0, top, w, h, p);
        String[] labels = softLabels();
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setTextSize((h - top) * 0.48f);
        p.setColor(Color.WHITE);
        float baseline = top + (h - top) * 0.66f;
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(labels[0], w * 0.02f, baseline, p);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(labels[1], w * 0.5f, baseline, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(labels[2], w * 0.98f, baseline, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.DEFAULT);
    }

    // S40 triple softkey labels: left | centre (navi action) | right.
    private String[] softLabels() {
        switch (screen) {
            case IDLE: return new String[]{"Menu", "Menu", "Names"};
            case MENU: case LIST: return new String[]{"", "Select", "Back"};
            case THREADS: return new String[]{"", "Open", "Back"};
            case READ: return new String[]{"", "Reply", "Back"};
            case COMPOSE_NUMBER: return new String[]{"", "Next", "Back"};
            case COMPOSE_TEXT: return new String[]{"", "Send", "Back"};
            case DIALER: case CALLLOG: case CONTACTS: return new String[]{"", "Call", "Back"};
            default: return new String[]{"", "", "Back"};
        }
    }
}
