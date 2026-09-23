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
import android.media.AudioManager;
import android.os.Build;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
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
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS
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
    // Real-call tracking: telephony state drives the C2 incall screen.
    // Registered once READ_PHONE_STATE is granted; additive only.
    private TelephonyManager telephonyManager;
    private PhoneStateListener legacyCallListener;
    private Object modernCallCallback;
    private boolean callStateRegistered = false;
    private boolean callUiActive = false;
    private String lastDialNumber;
    private long lastDialAtMs;
    private String lastRingingNumber;

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
        registerCallStateListener();
    }

    @Override
    protected void onDestroy() {
        if (smsObserver != null) getContentResolver().unregisterContentObserver(smsObserver);
        if (callLogObserver != null) getContentResolver().unregisterContentObserver(callLogObserver);
        if (contactsObserver != null) getContentResolver().unregisterContentObserver(contactsObserver);
        if (callStateRegistered && telephonyManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 31 && modernCallCallback != null) {
                    telephonyManager.unregisterTelephonyCallback((android.telephony.TelephonyCallback) modernCallCallback);
                } else if (legacyCallListener != null) {
                    telephonyManager.listen(legacyCallListener, PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception ignored) { }
        }
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
        registerCallStateListener();
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
        lastDialNumber = number;
        lastDialAtMs = System.currentTimeMillis();
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            // Real call; the C2 incall screen tracks it via telephony state.
            try {
                startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number)));
                return;
            } catch (Exception ignored) { }
        }
        // Without the call permission the system dialer confirms the number.
        startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
    }

    @Override
    public boolean endCall() {
        if (Build.VERSION.SDK_INT < 28) return false;
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            return tm != null && tm.endCall();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setSpeakerphone(boolean on) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.setSpeakerphoneOn(on);
        } catch (Exception ignored) { }
    }

    @Override
    public void setMicMute(boolean mute) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) am.setMicrophoneMute(mute);
        } catch (Exception ignored) { }
    }

    private void registerCallStateListener() {
        if (callStateRegistered) return;
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        try {
            telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (telephonyManager == null) return;
            if (Build.VERSION.SDK_INT >= 31) {
                final android.telephony.TelephonyCallback callback = new android.telephony.TelephonyCallback() {
                    @Override public void onCallStateChanged(int state) {
                        handleCallState(state, null);
                    }
                };
                modernCallCallback = callback;
                telephonyManager.registerTelephonyCallback(getMainExecutor(), callback);
            } else {
                legacyCallListener = new PhoneStateListener() {
                    @Override public void onCallStateChanged(int state, String phoneNumber) {
                        handleCallState(state, phoneNumber);
                    }
                };
                telephonyManager.listen(legacyCallListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
            callStateRegistered = true;
        } catch (Exception ignored) {
            // Call-state tracking is additive; never let it block launch.
        }
    }

    private void handleCallState(int state, String number) {
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            lastRingingNumber = number;
            return;
        }
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            if (callUiActive) return;
            callUiActive = true;
            String shown = (lastDialNumber != null && System.currentTimeMillis() - lastDialAtMs < 120000)
                    ? lastDialNumber
                    : (lastRingingNumber != null ? lastRingingNumber : "");
            final String n = shown;
            mainHandler.post(() -> { if (ui != null) ui.callStarted(n); });
            return;
        }
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            lastRingingNumber = null;
            if (!callUiActive) return;
            callUiActive = false;
            mainHandler.post(() -> { if (ui != null) ui.callEnded(); });
        }
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
    public void clearDrafts() {
        getSharedPreferences("c2reborn", MODE_PRIVATE).edit().putString("drafts", "").apply();
    }

    @Override
    public int getProfile() {
        return getSharedPreferences("c2reborn", MODE_PRIVATE).getInt("profile", 0);
    }

    @Override
    public void setProfile(int index) {
        getSharedPreferences("c2reborn", MODE_PRIVATE).edit().putInt("profile", index).apply();
    }

    // Sim v4.89 default alarm: off, 07:00, no repeat, Nokia tune, 10 min snooze.
    @Override
    public String[] getAlarm() {
        android.content.SharedPreferences sp = getSharedPreferences("c2reborn", MODE_PRIVATE);
        return new String[]{
                sp.getBoolean("alarm_on", false) ? "1" : "0",
                sp.getString("alarm_time", "07:00"),
                sp.getBoolean("alarm_repeat", false) ? "1" : "0",
                sp.getString("alarm_tone", "Nokia tune"),
                sp.getString("alarm_snooze", "10"),
        };
    }

    @Override
    public String[] getPlayer() {
        android.content.SharedPreferences sp = getSharedPreferences("c2reborn", MODE_PRIVATE);
        return new String[]{
                sp.getString("player_track", "0"),
                sp.getBoolean("player_playing", false) ? "1" : "0",
                sp.getString("player_elapsed", "0"),
                String.valueOf(sp.getInt("player_volume", 6)),
                sp.getBoolean("player_shuffle", false) ? "1" : "0",
                sp.getBoolean("player_repeat", false) ? "1" : "0",
                sp.getString("equaliser", "Normal"),
        };
    }

    @Override
    public void setPlayer(String[] a) {
        getSharedPreferences("c2reborn", MODE_PRIVATE).edit()
                .putString("player_track", a[0])
                .putBoolean("player_playing", "1".equals(a[1]))
                .putString("player_elapsed", a[2])
                .putInt("player_volume", Integer.parseInt(a[3]))
                .putBoolean("player_shuffle", "1".equals(a[4]))
                .putBoolean("player_repeat", "1".equals(a[5]))
                .putString("equaliser", a[6])
                .apply();
    }

    @Override
    public void setAlarm(String[] a) {
        getSharedPreferences("c2reborn", MODE_PRIVATE).edit()
                .putBoolean("alarm_on", "1".equals(a[0]))
                .putString("alarm_time", a[1])
                .putBoolean("alarm_repeat", "1".equals(a[2]))
                .putString("alarm_tone", a[3])
                .putString("alarm_snooze", a[4])
                .apply();
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
