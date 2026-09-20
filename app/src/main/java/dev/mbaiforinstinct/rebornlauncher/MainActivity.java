package dev.mbaiforinstinct.rebornlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.mbaiforinstinct.rebornlauncher.data.PhoneStore;
import dev.mbaiforinstinct.rebornlauncher.ui.NokiaUi;
import dev.mbaiforinstinct.rebornlauncher.ui.OnScreenKeypad;

public class MainActivity extends Activity implements NokiaUi.Actions {

    private static final String[] PERMS = {
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
    };

    private NokiaUi ui;
    private OnScreenKeypad keypad;

    // Cached phone data: loaded once, refreshed in the background when the
    // providers change, never queried per screen open or per draw.
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler bgHandler;
    {
        android.os.HandlerThread t = new android.os.HandlerThread("reborn-observers");
        t.start();
        bgHandler = new Handler(t.getLooper());
    }
    private List<PhoneStore.Sms> cacheSms = new ArrayList<>();
    private List<String[]> cacheCallLog = new ArrayList<>();
    private List<String[]> cacheContacts = new ArrayList<>();
    private volatile int cacheUnread = 0;
    private volatile int cacheMissed = 0;
    private ContentObserver smsObserver;
    private ContentObserver callLogObserver;
    private ContentObserver contactsObserver;

    private final Runnable refreshSmsTask = new Runnable() {
        @Override public void run() { refreshSms(); }
    };
    private final Runnable refreshCallLogTask = new Runnable() {
        @Override public void run() { refreshCallLog(); }
    };
    private final Runnable refreshContactsTask = new Runnable() {
        @Override public void run() { refreshContacts(); }
    };

    private void refreshAll() {
        bg.execute(refreshSmsTask);
        bg.execute(refreshCallLogTask);
        bg.execute(refreshContactsTask);
    }

    private void refreshSms() {
        final List<PhoneStore.Sms> sms;
        final int unread;
        try {
            sms = PhoneStore.sms(this, 40);
            unread = PhoneStore.unreadSms(this);
        } catch (Exception e) { return; }
        mainHandler.post(() -> {
            cacheSms = sms;
            cacheUnread = unread;
            if (ui != null) ui.dataChanged();
        });
    }

    private void refreshCallLog() {
        final List<String[]> log;
        final int missed;
        try {
            log = PhoneStore.callLog(this, 40);
            missed = PhoneStore.missedCalls(this);
        } catch (Exception e) { return; }
        mainHandler.post(() -> {
            cacheCallLog = log;
            cacheMissed = missed;
            if (ui != null) ui.dataChanged();
        });
    }

    private void refreshContacts() {
        final List<String[]> contacts;
        try {
            contacts = PhoneStore.contacts(this, 60);
        } catch (Exception e) { return; }
        mainHandler.post(() -> {
            cacheContacts = contacts;
            if (ui != null) ui.dataChanged();
        });
    }

    private void registerObservers() {
        smsObserver = observe(Uri.parse("content://sms"), refreshSmsTask);
        callLogObserver = observe(CallLog.Calls.CONTENT_URI, refreshCallLogTask);
        contactsObserver = observe(ContactsContract.AUTHORITY_URI, refreshContactsTask);
    }

    private ContentObserver observe(Uri uri, final Runnable refreshTask) {
        ContentObserver observer = new ContentObserver(bgHandler) {
            @Override public void onChange(boolean selfChange) {
                // Providers notify in bursts; coalesce into one reload.
                bgHandler.removeCallbacks(refreshTask);
                bgHandler.postDelayed(refreshTask, 400);
            }
        };
        try {
            getContentResolver().registerContentObserver(uri, true, observer);
        } catch (Exception ignored) {
            // Never let observer registration kill launch.
        }
        return observer;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshAll();
    }

    @Override
    protected void onDestroy() {
        if (smsObserver != null) getContentResolver().unregisterContentObserver(smsObserver);
        if (callLogObserver != null) getContentResolver().unregisterContentObserver(callLogObserver);
        if (contactsObserver != null) getContentResolver().unregisterContentObserver(contactsObserver);
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new NokiaUi(this, this);
        keypad = new OnScreenKeypad(this);
        keypad.setKeySink(ui::handleKey);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(ui, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 55f));
        layout.addView(keypad, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 45f));
        setContentView(layout);
        registerObservers();
        refreshAll();
        requestNeededPermissions();
    }

    private void requestNeededPermissions() {
        boolean missing = false;
        for (String perm : PERMS) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                missing = true;
                break;
            }
        }
        if (missing) {
            requestPermissions(PERMS, 7);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        return ui.handleKey(keyCode) || super.onKeyDown(keyCode, event);
    }

    @Override
    public void dial(String number) {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
        startActivity(intent);
    }

    @Override
    public void openRoute(String section, String item) {
        Intent intent = null;
        if ("Menu".equals(section)) {
            switch (item) {
                case "Gallery":
                    intent = new Intent(Intent.ACTION_VIEW);
                    intent.setType("image/*");
                    break;
                case "Settings":
                    intent = new Intent(Settings.ACTION_SETTINGS);
                    break;
                case "Music":
                    intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                    break;
                case "Applications":
                case "Apps.":
                    intent = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
                    break;
                default:
                    break;
            }
        } else if ("Organiser".equals(section)) {
            if ("Alarm clock".equals(item)) {
                intent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            } else if ("Calendar".equals(item)) {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setData(CalendarContract.CONTENT_URI);
                intent = view;
            }
        } else if ("Go to".equals(section)) {
            switch (item) {
                case "Camera":
                    intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                    break;
                case "Video recorder":
                    intent = new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA);
                    break;
                case "Calculator":
                    intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR);
                    break;
                case "Nokia Browser":
                    intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
                    break;
                case "Media player":
                    intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                    break;
                default:
                    break;
            }
        }
        if (intent != null) {
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException ignored) {
                // The Nokia UI keeps running; a missing system app is a phone
                // configuration matter, not a launcher failure.
            }
        }
    }

    @Override
    public List<PhoneStore.Sms> sms() {
        return cacheSms;
    }

    @Override
    public List<String[]> callLog() {
        return cacheCallLog;
    }

    @Override
    public List<String[]> drafts() {
        List<String[]> out = new ArrayList<>();
        String raw = getSharedPreferences("c2reborn", MODE_PRIVATE).getString("drafts", "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String[] parts = line.split("\t", -1);
                if (parts.length == 2) out.add(parts);
            }
        }
        return out;
    }

    @Override
    public void saveDraft(String number, String text) {
        List<String[]> all = drafts();
        all.add(0, new String[]{number, text});
        StringBuilder sb = new StringBuilder();
        for (String[] d : all) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(d[0]).append('\t').append(d[1]);
        }
        getSharedPreferences("c2reborn", MODE_PRIVATE).edit().putString("drafts", sb.toString()).apply();
    }

    @Override
    public boolean addContact(String name, String number) {
        return PhoneStore.insertContact(this, name, number); // contactsObserver refreshes the cache
    }

    @Override
    public boolean updateContact(String oldName, String oldNumber, String newName, String newNumber) {
        return PhoneStore.updateContact(this, oldName, oldNumber, newName, newNumber);
    }

    @Override
    public boolean deleteContact(String name, String number) {
        return PhoneStore.deleteContact(this, name, number);
    }

    @Override
    public List<String[]> contacts() {
        return cacheContacts;
    }

    @Override
    public boolean sendSms(String number, String text) {
        boolean ok = PhoneStore.sendSms(number, text);
        if (ok) bg.execute(refreshSmsTask);
        return ok;
    }

    @Override
    public int missedCalls() {
        return cacheMissed;
    }

    @Override
    public int unreadSms() {
        return cacheUnread;
    }

    @Override
    public int batteryPercent() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return Math.round(level * 100f / scale);
    }
}
