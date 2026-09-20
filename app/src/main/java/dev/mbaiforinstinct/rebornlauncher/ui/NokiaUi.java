package dev.mbaiforinstinct.rebornlauncher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    // Sim-extracted v4.89 menu icons (56x56 PNG), embedded as base64 so the
    // launcher needs no separate resource files.
    private static final String ICON_B64_0 = "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";
    private static final String ICON_B64_1 = "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";
    private static final String ICON_B64_2 = "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";
    private static final String ICON_B64_3 = "iVBORw0KGgoAAAANSUhEUgAAADgAAAA4CAYAAACohjseAAAHhUlEQVRo3u2aTWhb2RXHf09Pcp4+HFmyJDuOnViT2B6lpHUJaZmShGkySTCUMospcWCgpV0VGihdBNISSqHQTegiZaBdDO0itJsWGmYmJjCBmUzKBDsziWnskFiu7HFsSbZjfflZkqX3bheuFD1btj4cu0nIWfm9P/fd87/nnP8991qSEIKX2Uy85PaK4CuCz7mZy72UJGk7fbD9tf+XR74feOOnwHDh5eJy+jNN1+O7f9f/RTUfWU8szc/BIu9KZZc6gbf1ucW3TU1WsMg4GqwrRH/zAcC9nJa/lde1T7y/feefgLapCG6zyYU/cp8+eppF9gYk2w5MXgdSk7XX4m3stVh2/Czx66spXehXI4uxPwR+/+PBF4Fg+ZRTlxHqMvpc6inpJivy3uZGS2fzux1O77vzF//x+b2Z8Z+89f75By+FyIh4mvzwY7JXh8nfmUQR8htOq/3nQMcLF8FKpk08QZuOo+1MA1hfzm0ip6HPJOqqQRlo2SY3d2z7Pgg0HzlypPvs2bPdW83u9u3b6A227u0maO7v7+8+c+bMn7ZcOIRATK9Ns799O4MzLeFLmtizIONLmp4pQXRdR9f1Lc9PXdeRynQhk648vh4fDxMJstklduQk9izIdM/KdEXNKHlpcwSvXLnCrVu3AOjr66Ovr6+IDQwMMDAwUHzu6uri3LlzVePBYJDLly8DEAqFeN3eyg+/+w3D/NeuXaOzsxO73U4ikeD8+fOMjY3xYTCI8rpE5O+f8yvnsfoJTk5OEovFAOjt7TVEMxwOc/fuXUOa1YInk8kinkwm8bVY1swfDodRVRWLZQULBAIEAgGy2SzBYJCzV/7Mx5lxDsm7Nt/JrHawXGNbC15P6hfGWCwWAoEALpeLdDrNx6nxrSe4WbwWgqVmtVqRZZlEIlE7wf7+fo4fPw5Aa2urYYKTJ09y8ODB4rPD4agJ9/v9XLp0CYAbN27gWlwRjFLap06d4sSJE/h8PgNBTdNIp9NcvHgRgOnpaS5cuFA7QY/Hg9/vN9RN6cqVYrIsGwg4nU6sVmP3VDq+QBLA6/WiSBkEIEooulwuOjo6aGtrW2lacjlUVSWTyQDgdruLhOtK0Wg0ytjYWFXp43A42LdvX/F5fn6eaDRa1dhoNIpLlRAuKM3cJ0+eEAqFCIfDRCIRjh0zKubNmzcBaGxsrI+gEKLqWlm9Z9YyVggBQkIg0EsimM1mefjwIWazuWykCvNVEixztcJSiwiVaxJyuRxzc3PE43HDe1VVceFAgIHg7OwsHo8Hp9MJQD6fL0uw7hStRe02iuDS0hIjIyNMTEwA0GF1YzM1PB2bWUYoYiWCJfMVvrEekWcSwVrSbHUEhRCMjo4yOjpKt72FH7V/h97Gdmxyw9o6TCfWpGjhm4XvrhfBbSG4OoK6rjM4OMjc4zA/aD3ECU8AXc2SH4+xOBNDjy8hchompw25yYa704NuBW0Dgs88grWk6OoI3r9/n9nHYX7hP0m74iI98pjsyMzaU3lcJRdXyUzMYbI3bJiiWxLBakWmdKVTqRR37tzhndZDtClNqIP/YXlyrvIVhJopO3+lCNYtMvXW4NDQELsVF99y+VHvTZCdmK37nLilKVovwVAoxJs7/WjLedSH4U0dhJ8rgrlcjng8TiQSoXnvAbKzCYNo1HXS38oarFZk8vk8U1NTpNNpdF1H0zQ0dDShkxP13Qg8kdLbU4MbrU4mk2FiYoJIJEJTUxM7d+4sNt7z2UX2Nu0mXyfBz+QZTCYTNptt+/fBxcVFpqamiEQiTxVQ04or6fP5+HTmPoe+5qehq4WlR7XV4ZKU54Y8RXNzs8H5La/BWCxGKBRa00sWJik4sGfPHj7690fc+GqYN7/5ddTZOLm4WhW5NHnes9xDV2TcbvfWEiyQnJmZIRwOlyVWOnkhhex2O36/n/e+/ID0bJwDsepuvxakDH+xjBCR0/jb/EiSZHD+mTfboVAITdOKB8xKG33pRF6vF4/Hw/tTNzlgdvNWfi+7hL3s2LiU5ZY8zb/MMyiKwmsdr9HQ0LAmMs88gpqmIcsydru9IkFFUQwrbDab8fv9tLS0EI1G+WNihCaxA5dQ8OsrYhQyJYlJGeJSFkVR6NnVUzwarafWpVbwq9J/o9cl2NraSk9PT9XiULrCbre7eKVQaN9isRipVIqv/pcRdqUDj9WK1+uteCovF8GCb5VuDtYlODw8XFTK/fv3G64kxsfHCQaDBkKHDx+uGl9YWGBoaAiABw8ebIgX7PTp04bn69evF8+bdRFMJBJFFfX5fIYVTCaThm1CCFETnslkasLLRbCALy8vb/5edLWIrC7s1Q5Wwlc7WwmvRi03ffFbWuTlLnZrwcstwEZ4OZHZNMHu7m66uroAsNlshhVsb283iIjFYqkJdzgcHD16tGq8XAQLeCwWY3JysnaCdrvd4GTpBIqioCjKug5UwmVZNny7Vrz04rfufVDX9brTYjutEkGpXEMtSVIbcAj4Hs+/fQF8IoR4VEsE88A88OELQDDFBj/tWi+CAD5enN/RzAshlqsm+DLZqx/EviL4iuD/1/4LGlF2UaGl2O8AAAAASUVORK5CYII=";
    private static final String ICON_B64_4 = "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";
    private static final String ICON_B64_5 = "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";
    private static final String ICON_B64_6 = "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";
    private static final String ICON_B64_7 = "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";
    private static final String ICON_B64_8 = "iVBORw0KGgoAAAANSUhEUgAAADgAAAA4CAYAAACohjseAAAFlElEQVRo3u1aTWgUZxh+Zn9md42bSHarJlYaKu5Bay6hlmrUYMG20BbBg6KIxYIIemjrpbdCaS8VS4ptkR7EHgoWexAsJS1UA9oEWgStomRqktWExGyy62Znf2a/3x52VveUZOf7CoPkg4WFZd+ZZ57neb95nxlDSonneQXwnK9lgMsAfb5CC/1oGEb9q/nZ5fLhUCTWC6CryWPkGXFu5KZHL/QffyX7f4BYqFGGlvD/xKc/538Nhs2t+SID5c2fQHsMe0fTk8faUm+9O28NWL5hEIC558jne8Lh6NZb41U8mBGeDhIxKphNF1It6147OG8NnAFg+wVgsqU1GRecwZp0PB+kggCAIIzwig4Aq/0E0JRCQHAGTqpqPkEIkjEACPpKopJzSEaVAQpGITh7WjednjkcjUbXe6mVycwMdXen/gDAVQFCCg7BGQRVBEgJJGfo7d255vz5H6+Ew+EUpQycN9e1gsEgWlvbcO7chcvHj79/CEBZGWCNQUeRQQLBKE5++NEbkUgkNTeXB/XSkgFkMlMol6t7d+zYvev69au/L8TkEgDq8WBdom1tqzoqFQfFYtlzrZaWNlBK0dHRuR5AAkBGjUGNEuWcgxCCalWtHmMUnItFMSwOkHMIpolBxsA5A6UMhBClekv17+IApYDg6h7kLoOMcVBKlRmklGoCyDmkFgZJzcucgzGqSaI6AIqaRIUqQNooUR0M6pKocCVK1RmUnD6VqLoHNTIodWwTlLoSrTUZ/0iUcwhGEZIUxOPGDADceQLJak1Ghwe1SfTR6E174+ZdSMRymCh4G5ckzUFyCj5/J835e106JMoYhRDqAIvWnavZfKE6mHPWbYMRMj1PE+WRYV6y0pyzLh0S1eXBPIBs5uGfvwCwjOhLnZ7AkcwcRGUagMUY7/PTPlgGMA1gEEBaOg8THs/HBmABAOfMXx4EMOWeYBaAV4lyVw0JzjkCgYASQMaYvm2igQEdMUNicnLCbm1dhVKpgGAwjEAg2HRzyedzYIxibMzK6gKobfX3f3l39+63LcMIpVTqjI9bg4VCftGLbiyUKTbkorrWagAbABxsb3+hIxw2I16KlEq2XSwWbrq94b6UsqzEYH//t5379x/6As2HvvWONzk29uC7vr7X/wKQOHXqk5u9vTs/bm9vT3qpNzU1lT579qvvh4ZuZBaLLJbCYOfIyKNrsdiKFCEUQjT3uM0wDASDwPDwkH3p0k9vdndvwdGjx34zjECcEIZmH98FAgaq1QoGB69ZJ058sA1AViXZNk+f/vod0zRT2az3DKVadfD4cSbe1fXyvp6eVyGljM/OPoHXZ5O53Bwch6QOHDiy7+LFHy4AIF4BJoUQYIwpZSiAAdOMwDACcdM0US47cBzvA3RLS7xxH0y6W5k3BmsDqvqtlZTS3eT1TPS6pglTiBpA1ZtjQqh71Zm/5kHOhRYGKaXuqOSzcanuQT03x7Wr7quBt+5BHZJijPtPonUPql5xQgiYHyd6vR7Umarpmei1MfgMoB4P1iWvjUE9HnwmUfVMhvmVQeav6F6fB581GV9tE/q6aKMHfcigYQCO4+2khBCw7fmnEo1EwkoACalqA8gHBq5kN2/eQmZmpkxKOcwmh3DGGEqlAihluHfvlpVMtsW3b+9DJjONUMhEKBRqGpxtz6NSKdm3b/89rRpZrAawac2ajpMbNmzqi0ZjCY/+s9Ppf4cnJsa/AbBy48ZNx9aufbEnHDbjXuo5TiU7MvLPYDY7ewbAqJQy4xWgCWALgB73o7IsN0MBgD4AKcV6gwDuA7gjpeQqkUXCzWLi7sfTUI9arjoBIOKGTwn3u5dlo5azTiwWWSw1VTPdyTkGb28qFQHMNUQLKwCsArDSQy3eAJDUh2lPAJ+HtfzG7zJAn6//AGIqyk5n8KswAAAAAElFTkSuQmCC";
    private static final String[] MENU_ICON_B64 = {
            ICON_B64_0, ICON_B64_1, ICON_B64_2, ICON_B64_3, ICON_B64_4, ICON_B64_5, ICON_B64_6, ICON_B64_7, ICON_B64_8
    };
    private final Bitmap[] menuIcons = new Bitmap[MENU_ICON_B64.length];

    private void loadMenuIcons() {
        if (menuIcons[0] != null) return;
        for (int i = 0; i < MENU_ICON_B64.length; i++) {
            try {
                byte[] bytes = android.util.Base64.decode(MENU_ICON_B64[i], android.util.Base64.DEFAULT);
                menuIcons[i] = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception ignored) { }
        }
    }

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
        // Clock top-right: 24px at 240px LCD width (v4.89 token).
        p.setTextSize(w * 0.10f);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(timeLabel(), w * 0.9625f, top + (bot - top) * 0.039f + w * 0.085f, p);
        // Carrier top-left, date below: 12px at 240 (v4.89 token).
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
            p.setTextSize(w * 0.071f);
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
        } catch (Throwable ignored) { }
        return "";
    }

    private void drawMenu(Canvas c, int w, int h) {
        loadMenuIcons();
        // Sim v4.89 menu: small plain title top-left (no blue bar), 3x3 icon
        // grid on the dark screen, white labels, inverted selection.
        float top = statusH(h);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(w * 0.058f);
        c.drawText("Menu", w * 0.03f, top + w * 0.075f, p);

        float gridTop = top + w * 0.11f;
        float gridBot = softTop(h);
        float cellH = (gridBot - gridTop) / 3f;
        float cellW = w / 3f;
        float iconSize = Math.min(cellW, cellH) * 0.46f;
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            float cx = (i % 3) * cellW + cellW / 2f;
            float cy = (i / 3) * cellH + gridTop + cellH / 2f;
            boolean sel = i == selected;
            if (sel) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.WHITE);
                float pad = cellW * 0.06f;
                c.drawRect((i % 3) * cellW + pad, (i / 3) * cellH + gridTop + pad,
                        (i % 3) * cellW + cellW - pad, (i / 3) * cellH + gridTop + cellH - pad, p);
            }
            Bitmap icon = menuIcons[i];
            if (icon != null) {
                float ix = cx - iconSize / 2f;
                float iy = cy - iconSize * 0.62f;
                android.graphics.Rect dst = new android.graphics.Rect(
                        (int) ix, (int) iy, (int) (ix + iconSize), (int) (iy + iconSize));
                p.setColorFilter(sel ? new android.graphics.PorterDuffColorFilter(
                        Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN) : null);
                c.drawBitmap(icon, null, dst, p);
                p.setColorFilter(null);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(sel ? Color.BLACK : Color.WHITE);
            p.setTextSize(w * 0.046f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(MENU_ITEMS[i], cx, cy + iconSize * 0.62f, p);
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
        p.setTextSize(w * 0.071f);
        c.drawText(m.directionLabel() + "  " + m.dateLabel(), w * 0.033f, y, p);
        // v4.89 read box: light panel with dark text.
        float boxTop = y + screenH(h) * 0.03f;
        float boxBot = softTop(h) - screenH(h) * 0.04f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(COL_READ_BG);
        c.drawRect(w * 0.033f, boxTop, w * 0.967f, boxBot, p);
        p.setColor(COL_READ_FG);
        p.setTextSize(w * 0.079f);
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
        p.setTextSize(w * 0.071f);
        c.drawText("To:", w * 0.042f, y, p);
        // Light text field like the v4.89 editor.
        drawField(c, w, y + screenH(h) * 0.02f, composeNumber.toString(), screenH(h) * 0.10f);
        y += screenH(h) * 0.16f;
        if (screen == Screen.COMPOSE_TEXT) {
            p.setColor(COL_SUB);
            p.setTextSize(w * 0.071f);
            c.drawText("Message:", w * 0.042f, y, p);
            float fTop = y + screenH(h) * 0.02f;
            float fH = screenH(h) * 0.38f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(COL_READ_BG);
            c.drawRect(w * 0.042f, fTop, w * 0.958f, fTop + fH, p);
            p.setColor(COL_READ_FG);
            p.setTextSize(w * 0.079f);
            android.graphics.Rect clip = new android.graphics.Rect(
                    (int) (w * 0.042f), (int) fTop, (int) (w * 0.958f), (int) (fTop + fH));
            c.save();
            c.clipRect(clip);
            drawWrapped(c, composeTap.preview(), w * 0.075f, w * 0.85f, fTop + w * 0.075f, w * 0.083f);
            c.restore();
            if (composeSent) {
                p.setColor(COL_ACCENT);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTextSize(w * 0.079f);
                c.drawText("Message sent", w * 0.5f, fTop + fH + screenH(h) * 0.10f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        } else {
            p.setColor(COL_SUB);
            p.setTextSize(w * 0.071f);
            c.drawText("Type the number, then Centre", w * 0.042f, y, p);
        }
        p.setTypeface(Typeface.DEFAULT);
    }

    private void drawField(Canvas c, int w, float top, String text, float height) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(COL_READ_BG);
        c.drawRect(w * 0.042f, top, w * 0.958f, top + height, p);
        p.setColor(COL_READ_FG);
        p.setTextSize(w * 0.083f);
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
        p.setTextSize(w * 0.071f);
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
            p.setTextSize(w * 0.083f);
            c.drawText(main.get(idx), w * 0.042f, top + rowH * 0.44f, p);
            if (sub != null) {
                String s = sub.get(idx);
                if (s != null && !s.isEmpty()) {
                    p.setTextSize(w * 0.071f);
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
        p.setTextSize(w * 0.079f);
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
        p.setTextSize(w * 0.083f);
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
