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
import android.provider.Settings;
import android.widget.LinearLayout;

import java.util.List;

import dev.mbaiforinstinct.rebornlauncher.data.PhoneStore;
import dev.mbaiforinstinct.rebornlauncher.ui.NokiaUi;
import dev.mbaiforinstinct.rebornlauncher.ui.OnScreenKeypad;

public class MainActivity extends Activity implements NokiaUi.Actions {

    private static final String[] PERMS = {
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
    };

    private NokiaUi ui;
    private OnScreenKeypad keypad;

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
        return PhoneStore.sms(this, 40);
    }

    @Override
    public List<String[]> callLog() {
        return PhoneStore.callLog(this, 40);
    }

    @Override
    public List<String[]> contacts() {
        return PhoneStore.contacts(this, 60);
    }

    @Override
    public boolean sendSms(String number, String text) {
        return PhoneStore.sendSms(number, text);
    }

    @Override
    public int missedCalls() {
        return PhoneStore.missedCalls(this);
    }

    @Override
    public int unreadSms() {
        return PhoneStore.unreadSms(this);
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
