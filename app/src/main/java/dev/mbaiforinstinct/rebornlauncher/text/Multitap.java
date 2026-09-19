package dev.mbaiforinstinct.rebornlauncher.text;

public final class Multitap {
    private static final String[] KEYS = {
        " 0+", "", "abc2", "def3", "ghi4", "jkl5",
        "mno6", "pqrs7", "tuv8", "wxyz9"
    };

    private final StringBuilder committed = new StringBuilder();
    private int lastDigit = -1;
    private int cycle = 0;

    public void press(int digit) {
        if (digit < 0 || digit > 9) return;
        if (digit == lastDigit) {
            cycle++;
        } else {
            commit();
            lastDigit = digit;
            cycle = 0;
        }
    }

    public void commit() {
        if (lastDigit >= 0) {
            String set = KEYS[lastDigit];
            committed.append(set.charAt(cycle % set.length()));
            lastDigit = -1;
            cycle = 0;
        }
    }

    public void backspace() {
        if (lastDigit >= 0) {
            lastDigit = -1;
            cycle = 0;
        } else if (committed.length() > 0) {
            committed.deleteCharAt(committed.length() - 1);
        }
    }

    public String preview() {
        if (lastDigit >= 0) {
            String set = KEYS[lastDigit];
            return committed.toString() + set.charAt(cycle % set.length());
        }
        return committed.toString();
    }

    public String text() {
        return committed.toString();
    }

    public void clear() {
        committed.setLength(0);
        lastDigit = -1;
        cycle = 0;
    }
}
