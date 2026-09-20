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

    private void drawScreenBackground(Canvas c, int w, int h) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawRect(0, 0, w, h, p);
    }

    private void drawStatus(Canvas c, int w) {
        p.setTextSize(w * 0.038f);
        p.setColor(Color.parseColor("#666666"));
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(timeLabel(), w * 0.04f, w * 0.06f, p);
        int batt = actions.batteryPercent();
        if (batt >= 0) {
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(batt + "%", w * 0.96f, w * 0.06f, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawIdle(Canvas c, int w, int h) {
        p.setTextSize(w * 0.2f);
        p.setColor(Color.parseColor("#2D5EA8"));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(timeLabel(), w * 0.5f, h * 0.28f, p);
        p.setTextSize(w * 0.055f);
        c.drawText(dateLabel(), w * 0.5f, h * 0.36f, p);

        int missed = actions.missedCalls();
        int unread = actions.unreadSms();
        float top = h * 0.52f;
        float bottom = h * 0.80f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#2D5EA8"));
        c.drawRoundRect(w * 0.06f, top, w * 0.94f, bottom, 28f, 28f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(w * 0.05f);
        if (missed == 0 && unread == 0) {
            c.drawText("No new notifications", w * 0.5f, (top + bottom) / 2f + w * 0.018f, p);
        } else {
            float y = top + h * 0.09f;
            if (missed > 0) {
                c.drawText(missed + (missed == 1 ? " missed call" : " missed calls"), w * 0.5f, y, p);
                y += h * 0.08f;
            }
            if (unread > 0) {
                c.drawText(unread + (unread == 1 ? " new message" : " new messages"), w * 0.5f, y, p);
            }
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawMenu(Canvas c, int w, int h) {
        drawTitle(c, w, "Menu");
        float gridTop = h * 0.12f;
        float gridHeight = h * 0.76f;
        float cellH = gridHeight / 3f;
        float cellW = w / 3f;
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            float cx = (i % 3) * cellW + cellW / 2f;
            float cy = (i / 3) * cellH + gridTop + cellH / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(i == selected ? Color.parseColor("#2D5EA8") : Color.parseColor("#F0F0F0"));
            c.drawRoundRect(cx - cellW * 0.42f, cy - cellH * 0.34f, cx + cellW * 0.42f, cy + cellH * 0.34f, 24f, 24f, p);
            p.setColor(i == selected ? Color.WHITE : Color.BLACK);
            p.setTextSize(w * 0.045f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(MENU_ITEMS[i], cx, cy + w * 0.016f, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawList(Canvas c, int w, int h) {
        drawTitle(c, w, LIST_TITLES[listSection]);
        String[] items = LIST_ITEMS[listSection];
        drawItemRows(c, w, h, items.length, i -> items[i], null);
    }

    private void drawThreads(Canvas c, int w, int h) {
        drawTitle(c, w, "Conversations");
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
        drawTitle(c, w, m.address);
        float y = h * 0.18f;
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Color.parseColor("#666666"));
        p.setTextSize(w * 0.042f);
        c.drawText(m.directionLabel() + "  " + m.dateLabel(), w * 0.06f, y, p);
        y += h * 0.06f;
        p.setColor(Color.BLACK);
        p.setTextSize(w * 0.05f);
        y = drawWrapped(c, m.body == null ? "" : m.body, w * 0.06f, w * 0.88f, y, w * 0.062f);
        y += h * 0.04f;
        p.setColor(Color.parseColor("#666666"));
        p.setTextSize(w * 0.042f);
        c.drawText("Centre: reply   Call key: call this number", w * 0.06f, y, p);
    }

    private void drawCompose(Canvas c, int w, int h) {
        drawTitle(c, w, "New message");
        float y = h * 0.20f;
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(Color.parseColor("#666666"));
        p.setTextSize(w * 0.042f);
        c.drawText("To:", w * 0.06f, y, p);
        p.setColor(Color.BLACK);
        p.setTextSize(w * 0.055f);
        c.drawText(composeNumber.toString(), w * 0.16f, y, p);
        if (screen == Screen.COMPOSE_TEXT) {
            y += h * 0.08f;
            p.setColor(Color.parseColor("#666666"));
            p.setTextSize(w * 0.042f);
            c.drawText("Message:", w * 0.06f, y, p);
            y += h * 0.06f;
            p.setColor(Color.BLACK);
            p.setTextSize(w * 0.05f);
            drawWrapped(c, composeTap.preview(), w * 0.06f, w * 0.88f, y, w * 0.062f);
            if (composeSent) {
                p.setColor(Color.parseColor("#1E7A34"));
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(w * 0.05f);
                c.drawText("Message sent", w * 0.5f, h * 0.75f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        } else {
            y += h * 0.08f;
            p.setColor(Color.parseColor("#666666"));
            p.setTextSize(w * 0.042f);
            c.drawText("Type the number, then Centre", w * 0.06f, y, p);
        }
    }

    private void drawDialer(Canvas c, int w, int h) {
        drawTitle(c, w, "Dial");
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.BLACK);
        p.setTextSize(w * 0.11f);
        c.drawText(dialNumber.toString(), w * 0.5f, h * 0.42f, p);
        p.setColor(Color.parseColor("#666666"));
        p.setTextSize(w * 0.045f);
        c.drawText("Green key to call", w * 0.5f, h * 0.54f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawRows(Canvas c, int w, int h) {
        String title = screen == Screen.CALLLOG ? "Call log" : "Contacts";
        if (screen == Screen.CALLLOG && !rows.isEmpty() && rows.get(0).length > 1 && rows.get(0)[0].equals("Not available")) {
            title = "Radio";
        }
        drawTitle(c, w, title);
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
        float listTop = h * 0.13f;
        float listHeight = h * 0.75f;
        int visible = Math.min(count, 6);
        float rowH = listHeight / 6f;
        int first = Math.max(0, Math.min(row - 2, count - visible));
        for (int i = 0; i < visible; i++) {
            int idx = first + i;
            float top = listTop + i * rowH;
            p.setStyle(Paint.Style.FILL);
            p.setColor(idx == row ? Color.parseColor("#2D5EA8") : Color.parseColor(idx % 2 == 0 ? "#FFFFFF" : "#F7F7F7"));
            c.drawRect(0, top, w, top + rowH, p);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(idx == row ? Color.WHITE : Color.BLACK);
            p.setTextSize(w * 0.05f);
            p.setFakeBoldText(false);
            c.drawText(main.get(idx), w * 0.06f, top + rowH * 0.42f, p);
            if (sub != null) {
                String s = sub.get(idx);
                if (s != null && !s.isEmpty()) {
                    p.setTextSize(w * 0.038f);
                    p.setColor(idx == row ? Color.parseColor("#D9E4F5") : Color.parseColor("#777777"));
                    c.drawText(s, w * 0.06f, top + rowH * 0.78f, p);
                }
            }
        }
    }

    private void drawEmpty(Canvas c, int w, int h, String text) {
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Color.parseColor("#777777"));
        p.setTextSize(w * 0.05f);
        c.drawText(text, w * 0.5f, h * 0.5f, p);
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

    private void drawTitle(Canvas c, int w, String title) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#F5F5F5"));
        c.drawRect(0, 0, w, w * 0.11f, p);
        p.setTextSize(w * 0.052f);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setColor(Color.parseColor("#1A1A1A"));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(title, w * 0.5f, w * 0.074f, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSoftkeys(Canvas c, int w, int h) {
        float top = h * 0.92f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#EFEFEF"));
        c.drawRect(0, top, w, h, p);
        p.setTextSize(w * 0.05f);
        p.setColor(Color.parseColor("#1A1A1A"));
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(leftSoftLabel(), w * 0.05f, top + (h - top) * 0.62f, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Back", w * 0.95f, top + (h - top) * 0.62f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private String leftSoftLabel() {
        switch (screen) {
            case IDLE: return "Menu";
            case MENU: case LIST: case THREADS: case CALLLOG: case CONTACTS: return "Select";
            case READ: return "Reply";
            case COMPOSE_NUMBER: return "Next";
            case COMPOSE_TEXT: return "Send";
            case DIALER: return "Call";
            default: return "";
        }
    }
}
